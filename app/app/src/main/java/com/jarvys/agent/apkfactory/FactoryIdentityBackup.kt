package com.jarvys.agent.apkfactory

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable signing-identity envelope. It has no file, Android, prompt or logging access.
 *
 * V1 uses a fixed PBKDF2-HMAC-SHA256 cost and AES-256-GCM. The complete bounded header,
 * including format/KDF/cipher identifiers, lengths, salt and nonce, is authenticated as AAD.
 * App ID, certificate fingerprint and version/APK evidence are inside the encrypted payload.
 * A restored version is only evidence as of export, never proof that no later release exists.
 *
 * The caller owns and must clear the supplied passphrase. Internal password copies, derived
 * key bytes and plaintext buffers are cleared in finally blocks. JVM/provider copies and
 * the returned private-key object cannot be guaranteed erased by a managed-memory runtime.
 */
internal object FactoryIdentityBackup {
    const val MAX_CONTAINER_BYTES = 64 * 1024
    const val PBKDF2_ITERATIONS = 600_000
    const val MIN_PASSPHRASE_CHARS = 12
    const val MAX_PASSPHRASE_CHARS = 1024

    data class Material(
        val appId: String,
        val key: PrivateKey,
        val certificate: X509Certificate,
        val lastVersion: Int,
        val lastApkSha256: String?,
    ) {
        // Data-class defaults would call provider key.toString(), which can reveal key material.
        override fun toString(): String = "FactoryIdentityBackup.Material([redacted])"
    }

    /** No nested provider/parser exception is retained for UI, traces or receipts. */
    class BackupException internal constructor() : IllegalArgumentException("Cannot process signing identity backup")

    private val magic = byteArrayOf(0x4a, 0x56, 0x46, 0x4b, 0x45, 0x59, 0x42, 0x4b) // JVFKEYBK
    private const val FORMAT_VERSION = 1
    private const val PAYLOAD_VERSION = 1
    private const val KDF_PBKDF2_SHA256 = 1
    private const val CIPHER_AES_256_GCM = 1
    private const val SALT_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HASH_BYTES = 32
    private const val HEADER_BYTES = 8 + 7 * 4 + SALT_BYTES + NONCE_BYTES
    private const val MAX_KEY_BYTES = 16 * 1024
    private const val MAX_CERTIFICATE_BYTES = 16 * 1024
    private val appIdPattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
    private val hashPattern = Regex("[a-f0-9]{64}")

    fun validatePassphrase(passphrase: CharArray) {
        require(passphrase.size in MIN_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS && passphrase.any { !it.isWhitespace() }) {
            "Use a passphrase of 12–1024 characters that is not blank"
        }
    }

    fun validateAppId(appId: String) {
        require(appId.length <= 127 && appIdPattern.matches(appId) && appId.split('.').size in 2..16 &&
            !appId.startsWith("android.") && !appId.startsWith("com.jarvys.")) {
            "Invalid signing application ID"
        }
    }

    fun fingerprint(certificate: X509Certificate): String = hex(sha256(certificate.encoded))

    /** Validates continuity metadata and proves key/certificate possession without exporting the key. */
    fun validateMaterial(material: Material) = sanitized { validateCore(material) }

    fun encrypt(material: Material, passphrase: CharArray): ByteArray = sanitized {
        validatePassphrase(passphrase)
        validateCore(material)
        var plaintext: ByteArray? = null
        try {
            plaintext = encodePayload(material)
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cipherLength = plaintext.size + TAG_BYTES
            require(cipherLength <= MAX_CONTAINER_BYTES - HEADER_BYTES)
            val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).putInt(FORMAT_VERSION)
                .putInt(KDF_PBKDF2_SHA256).putInt(PBKDF2_ITERATIONS).putInt(CIPHER_AES_256_GCM)
                .putInt(SALT_BYTES).putInt(NONCE_BYTES).putInt(cipherLength).put(salt).put(nonce).array()
            val encrypted = crypt(Cipher.ENCRYPT_MODE, plaintext, passphrase, salt, nonce, header)
            require(encrypted.size == cipherLength)
            header + encrypted
        } finally {
            plaintext?.fill(0)
        }
    }

    fun decrypt(bytes: ByteArray, passphrase: CharArray): Material = sanitized {
        validatePassphrase(passphrase)
        // Reject bounds before copying, allocating from a declared length, or running the KDF.
        require(bytes.size in (HEADER_BYTES + TAG_BYTES + 1)..MAX_CONTAINER_BYTES)
        val envelope = bytes.copyOf()
        var plaintext: ByteArray? = null
        try {
            val input = ByteBuffer.wrap(envelope)
            val actualMagic = ByteArray(magic.size).also(input::get)
            require(actualMagic.contentEquals(magic))
            require(input.int == FORMAT_VERSION && input.int == KDF_PBKDF2_SHA256)
            require(input.int == PBKDF2_ITERATIONS && input.int == CIPHER_AES_256_GCM)
            require(input.int == SALT_BYTES && input.int == NONCE_BYTES)
            val cipherLength = input.int
            require(cipherLength == envelope.size - HEADER_BYTES)
            val salt = ByteArray(SALT_BYTES).also(input::get)
            val nonce = ByteArray(NONCE_BYTES).also(input::get)
            val header = envelope.copyOfRange(0, HEADER_BYTES)
            val ciphertext = envelope.copyOfRange(HEADER_BYTES, envelope.size)
            plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, passphrase, salt, nonce, header)
            decodePayload(plaintext)
        } finally {
            plaintext?.fill(0)
            envelope.fill(0)
        }
    }

    private fun encodePayload(material: Material): ByteArray {
        val key = material.key.encoded ?: throw BackupException()
        try {
            require(material.key.format == "PKCS#8" && key.size in 1..MAX_KEY_BYTES)
            val certificate = material.certificate.encoded
            require(certificate.size in 1..MAX_CERTIFICATE_BYTES)
            val appId = material.appId.toByteArray(Charsets.US_ASCII)
            val apkHash = material.lastApkSha256?.let(::unhex) ?: byteArrayOf()
            val length = 7 * 4 + appId.size + HASH_BYTES + apkHash.size + certificate.size + key.size
            require(length <= MAX_CONTAINER_BYTES - HEADER_BYTES - TAG_BYTES)
            // This single exact-sized buffer avoids unerasable ByteArrayOutputStream capacity copies.
            return ByteBuffer.allocate(length).putInt(PAYLOAD_VERSION).putInt(appId.size).put(appId)
                .put(sha256(certificate)).putInt(material.lastVersion).putInt(apkHash.size).put(apkHash)
                .putInt(certificate.size).put(certificate).putInt(key.size).put(key)
                // Reserved zero field is versioned and validated, never silently ignored.
                .putInt(0).array()
        } finally {
            key.fill(0)
        }
    }

    private fun decodePayload(plaintext: ByteArray): Material {
        val input = ByteBuffer.wrap(plaintext)
        require(input.int == PAYLOAD_VERSION)
        val appBytes = take(input, 127)
        require(appBytes.all { it.toInt() in 0..127 })
        val appId = String(appBytes, Charsets.US_ASCII)
        validateAppId(appId)
        val certificateHash = ByteArray(HASH_BYTES).also(input::get)
        val lastVersion = input.int
        val apkHash = take(input, HASH_BYTES, allowEmpty = true)
        require(apkHash.isEmpty() || apkHash.size == HASH_BYTES)
        val certificateBytes = take(input, MAX_CERTIFICATE_BYTES)
        val keyBytes = take(input, MAX_KEY_BYTES)
        var privateKey: PrivateKey? = null
        var success = false
        try {
            require(input.int == 0 && !input.hasRemaining())
            val certificate = readCertificate(certificateBytes)
            require(MessageDigest.isEqual(certificateHash, sha256(certificate.encoded)))
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            // Reject accepted prefixes, trailing bytes and noncanonical PKCS#8 encodings.
            val canonicalKey = privateKey.encoded ?: throw BackupException()
            try { require(MessageDigest.isEqual(keyBytes, canonicalKey)) } finally { canonicalKey.fill(0) }
            val material = Material(appId, privateKey, certificate, lastVersion, apkHash.takeIf { it.isNotEmpty() }?.let(::hex))
            validateCore(material)
            success = true
            return material
        } finally {
            keyBytes.fill(0)
            if (!success) destroyBestEffort(privateKey)
        }
    }

    private fun readCertificate(bytes: ByteArray): X509Certificate {
        require(bytes.size in 1..MAX_CERTIFICATE_BYTES)
        val input = ByteArrayInputStream(bytes)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        require(input.available() == 0 && MessageDigest.isEqual(bytes, certificate.encoded))
        return certificate
    }

    private fun validateCore(material: Material) {
        validateAppId(material.appId)
        require(material.lastVersion >= 0)
        require(material.lastApkSha256 == null || (material.lastVersion > 0 && hashPattern.matches(material.lastApkSha256)))
        val certificate = readCertificate(material.certificate.encoded)
        val publicKey = certificate.publicKey as? RSAPublicKey ?: throw BackupException()
        val privateKey = material.key as? RSAPrivateKey ?: throw BackupException()
        require(publicKey.modulus.bitLength() in 2048..4096 && privateKey.modulus == publicKey.modulus)
        require(publicKey.publicExponent.signum() > 0 && publicKey.publicExponent.testBit(0) && publicKey.publicExponent.bitLength() in 2..32)
        require(privateKey.privateExponent.signum() > 0 && privateKey.privateExponent < privateKey.modulus)
        require(certificate.sigAlgOID == "1.2.840.113549.1.1.11" && certificate.subjectX500Principal == certificate.issuerX500Principal)
        require(certificate.basicConstraints == -1 && !certificate.hasUnsupportedCriticalExtension())
        require(certificate.keyUsage?.let { it.isNotEmpty() && it[0] } != false)
        require(certificate.notBefore.before(certificate.notAfter))
        // APK certificates are identity containers, not PKI trust assertions. An old backup is not
        // rejected merely because its certificate's validity date has elapsed or a clock changed.
        certificate.verify(publicKey)
        val challenge = ByteArray(HASH_BYTES).also(SecureRandom()::nextBytes)
        var signature: ByteArray? = null
        try {
            signature = Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey, SecureRandom()); update(challenge); sign()
            }
            require(Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey); update(challenge); verify(signature)
            })
        } finally {
            challenge.fill(0)
            signature?.fill(0)
        }
    }

    private fun crypt(mode: Int, input: ByteArray, passphrase: CharArray, salt: ByteArray,
        nonce: ByteArray, aad: ByteArray): ByteArray {
        val password = passphrase.copyOf()
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        password.fill('\u0000')
        var derived: javax.crypto.SecretKey? = null
        var keyBytes: ByteArray? = null
        var aesKey: SecretKeySpec? = null
        try {
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            keyBytes = derived.encoded
            require(keyBytes != null && keyBytes.size == 32)
            aesKey = SecretKeySpec(keyBytes, "AES")
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, aesKey, GCMParameterSpec(TAG_BYTES * 8, nonce))
                updateAAD(aad)
                doFinal(input)
            }
        } finally {
            password.fill('\u0000')
            spec.clearPassword()
            keyBytes?.fill(0)
            destroyBestEffort(derived)
            destroyBestEffort(aesKey)
        }
    }

    private fun take(input: ByteBuffer, maximum: Int, allowEmpty: Boolean = false): ByteArray {
        val length = input.int
        require(length in (if (allowEmpty) 0 else 1)..maximum && length <= input.remaining())
        return ByteArray(length).also(input::get)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val alphabet = "0123456789abcdef"
        bytes.forEach { byte -> append(alphabet[(byte.toInt() ushr 4) and 15]); append(alphabet[byte.toInt() and 15]) }
    }
    private fun unhex(value: String): ByteArray = ByteArray(HASH_BYTES) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    fun destroyBestEffort(value: Any?) {
        // Destroyable exists on all supported Android versions; key interfaces inherit it only
        // from API 26 onward. A checked cast keeps this pure helper safe on older runtimes too.
        runCatching { (value as? javax.security.auth.Destroyable)?.destroy() }
    }
    private inline fun <T> sanitized(action: () -> T): T = try { action() } catch (_: Exception) { throw BackupException() }
}
