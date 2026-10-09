package com.jarvys.agent.apkfactory

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.spec.RSAPrivateKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** All fixture keys, passphrases, plaintexts and encrypted backups exist only in host-test RAM. */
class FactoryIdentityBackupTest {
    @Test fun roundTripPreservesIdentityAndContinuityAndCanSignAgain() {
        val passphrase = password()
        val encrypted = envelope.copyOf()
        val original = encrypted.copyOf()
        val restored = FactoryIdentityBackup.decrypt(encrypted, passphrase)
        assertEquals(APP_ID, restored.appId)
        assertEquals(7, restored.lastVersion)
        assertEquals(APK_HASH, restored.lastApkSha256)
        assertArrayEquals(material.certificate.encoded, restored.certificate.encoded)
        assertEquals(FactoryIdentityBackup.fingerprint(material.certificate), FactoryIdentityBackup.fingerprint(restored.certificate))
        val message = "RAM-only restored signing proof".toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").run { initSign(restored.key); update(message); sign() }
        assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(material.certificate.publicKey); update(message); verify(signed) })
        assertArrayEquals(original, encrypted)
        assertArrayEquals(password(), passphrase)
        passphrase.fill('\u0000')
    }

    @Test fun freshlyCreatedAndExplicitUnknownFloorsRoundTripWithoutFabricatedHashes() {
        for (version in listOf(0, 23, Int.MAX_VALUE)) {
            val passphrase = password()
            try {
                val source = material.copy(lastVersion = version, lastApkSha256 = null)
                val restored = FactoryIdentityBackup.decrypt(FactoryIdentityBackup.encrypt(source, passphrase), passphrase)
                assertEquals(version, restored.lastVersion)
                assertNull(restored.lastApkSha256)
            } finally { passphrase.fill('\u0000') }
        }
    }

    @Test fun exportsUseIndependentRandomSaltAndNonceAndContainNoClearMetadataOrPrivateKey() {
        val passphrase = password()
        val second = try { FactoryIdentityBackup.encrypt(material, passphrase) } finally { passphrase.fill('\u0000') }
        assertFalse(envelope.contentEquals(second))
        assertFalse(envelope.copyOfRange(36, 68).contentEquals(second.copyOfRange(36, 68)))
        assertFalse(envelope.copyOfRange(68, 80).contentEquals(second.copyOfRange(68, 80)))
        assertTrue(envelope.size <= FactoryIdentityBackup.MAX_CONTAINER_BYTES)
        assertFalse(contains(envelope, APP_ID.toByteArray()))
        assertFalse(contains(envelope, APK_HASH.toByteArray()))
        assertFalse(contains(envelope, material.certificate.encoded))
        val key = material.key.encoded
        try { assertFalse(contains(envelope, key)) } finally { key.fill(0) }
    }

    @Test fun wrongPasswordTamperingTruncationAndMalformedHeadersHaveSameSanitizedFailure() {
        val wrong = "incorrect fixture password".toCharArray()
        val failed = assertRejected { FactoryIdentityBackup.decrypt(envelope, wrong) }
        assertArrayEquals("incorrect fixture password".toCharArray(), wrong)
        wrong.fill('\u0000')
        for (offset in listOf(0, 36, 68, 80, envelope.lastIndex)) {
            val tampered = envelope.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            assertEquals(failed.message, assertRejected { decrypt(tampered) }.message)
        }
        for (size in listOf(0, 1, 35, 79, 80, 96, envelope.size - 1)) {
            assertEquals(failed.message, assertRejected { decrypt(envelope.copyOf(size)) }.message)
        }
        assertEquals(failed.message, assertRejected { decrypt(envelope + byteArrayOf(0)) }.message)
    }

    @Test fun untrustedKdfAndCipherParametersCannotSelectWeakOrUnboundedWork() {
        // Header Int offsets: format, KDF, rounds, cipher, salt length, nonce length, ciphertext length.
        val mutations = listOf(8 to 2, 12 to 2, 16 to 1, 16 to 599_999, 16 to Int.MAX_VALUE,
            16 to -1, 20 to 2, 24 to 0, 24 to Int.MAX_VALUE, 28 to 16, 32 to -1, 32 to Int.MAX_VALUE)
        for ((offset, value) in mutations) {
            val changed = envelope.copyOf().apply { ByteBuffer.wrap(this).putInt(offset, value) }
            assertRejected { decrypt(changed) }
        }
        assertEquals(600_000, FactoryIdentityBackup.PBKDF2_ITERATIONS)
        assertEquals(600_000, ByteBuffer.wrap(envelope).getInt(16))
    }

    @Test fun sizeLimitRejectsOversizedContainerAndRandomMinimumSizedGarbage() {
        assertRejected { decrypt(ByteArray(FactoryIdentityBackup.MAX_CONTAINER_BYTES + 1)) }
        assertRejected { decrypt(ByteArray(FactoryIdentityBackup.MAX_CONTAINER_BYTES)) }
        assertRejected { decrypt(ByteArray(97)) }
    }

    @Test fun authenticatedPayloadVersionReservedFieldAndTrailingDataAreClosed() {
        assertRejectedPayload { ByteBuffer.wrap(it).putInt(0, 2); it }
        assertRejectedPayload { ByteBuffer.wrap(it).putInt(it.size - 4, 1); it }
        assertRejectedPayload { it + byteArrayOf(0) }
        assertRejectedPayload { it.copyOf(it.size - 1) }
    }

    @Test fun authenticatedMetadataCannotDisagreeWithItsCertificateOrContainInvalidValues() {
        assertRejectedPayload { it[8] = 'A'.code.toByte(); it }
        assertRejectedPayload { it[8] = 0xff.toByte(); it }
        assertRejectedPayload { it[8 + APP_ID.length] = (it[8 + APP_ID.length].toInt() xor 1).toByte(); it }
        assertRejectedPayload { ByteBuffer.wrap(it).putInt(8 + APP_ID.length + 32, -1); it }
        assertRejectedPayload { ByteBuffer.wrap(it).putInt(8 + APP_ID.length + 32, 0); it }
        assertRejectedPayload { ByteBuffer.wrap(it).putInt(8 + APP_ID.length + 36, 31); it }
    }

    @Test fun everyAuthenticatedVariableLengthIsBoundedBeforeAllocation() {
        val source = referencePlaintext()
        val input = ByteBuffer.wrap(source)
        val hashLengthOffset = 8 + APP_ID.length + 36
        val certificateLengthOffset = hashLengthOffset + 4 + input.getInt(hashLengthOffset)
        val keyLengthOffset = certificateLengthOffset + 4 + input.getInt(certificateLengthOffset)
        source.fill(0)
        for (offset in listOf(4, hashLengthOffset, certificateLengthOffset, keyLengthOffset)) {
            for (value in listOf(-1, Int.MAX_VALUE)) {
                assertRejectedPayload { ByteBuffer.wrap(it).putInt(offset, value); it }
            }
        }
    }

    @Test fun authenticatedWrongPrivateKeyOrWrongCertificateCannotRestore() {
        val different = secondPair
        val certificate = syntheticCertificate(different)
        val keyBytes = different.private.encoded
        try {
            assertRejectedCustomPayload(certificate, material.key.encoded)
            assertRejectedCustomPayload(material.certificate, keyBytes)
        } finally { keyBytes.fill(0) }
    }

    @Test fun authenticatedCertificateAndPrivateKeyTrailingBytesAreRejected() {
        val key = material.key.encoded
        try {
            assertRejectedCustomPayload(material.certificate, key + byteArrayOf(0))
            val plaintext = referencePayload(material.certificate.encoded + byteArrayOf(0), key)
            try { assertRejected { decrypt(referenceSeal(plaintext)) } } finally { plaintext.fill(0) }
        } finally { key.fill(0) }
    }

    @Test fun invalidApplicationIdsAndReservedNamespacesFailBeforeEncryption() {
        for (appId in listOf("", "single", "Org.example.app", ".org.app", "org..app", "org.app.", "org/a.app",
            "android.app", "com.jarvys.other", "a." + "b".repeat(126), (1..17).joinToString(".") { "a" })) {
            assertRejected { FactoryIdentityBackup.validateMaterial(material.copy(appId = appId)) }
        }
        FactoryIdentityBackup.validateAppId("a.b")
        FactoryIdentityBackup.validateAppId((1..16).joinToString(".") { "a" })
        FactoryIdentityBackup.validateAppId("org." + "a".repeat(123))
    }

    @Test fun malformedContinuityCannotBeExported() {
        for (source in listOf(material.copy(lastVersion = -1), material.copy(lastVersion = 0),
            material.copy(lastApkSha256 = "B".repeat(64)), material.copy(lastApkSha256 = "g".repeat(64)),
            material.copy(lastApkSha256 = "a".repeat(63)), material.copy(lastApkSha256 = ""))) {
            assertRejected { FactoryIdentityBackup.validateMaterial(source) }
        }
    }

    @Test fun mismatchedAndInvalidPrivateExponentsAreRejectedByPossessionProof() {
        assertRejected { FactoryIdentityBackup.validateMaterial(material.copy(key = secondPair.private)) }
        val original = material.key as RSAPrivateKey
        val wrongExponent = KeyFactory.getInstance("RSA").generatePrivate(RSAPrivateKeySpec(original.modulus, original.privateExponent.add(java.math.BigInteger.TWO)))
        assertRejected { FactoryIdentityBackup.validateMaterial(material.copy(key = wrongExponent)) }
    }

    @Test fun weakRsaAndInvalidCertificateSelfSignatureAreRejected() {
        val weakPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertRejected { FactoryIdentityBackup.validateMaterial(material.copy(key = weakPair.private, certificate = syntheticCertificate(weakPair))) }
        val invalidCertificate = EphemeralFactoryCertificate.create(pair.public.encoded) { value ->
            Signature.getInstance("SHA256withRSA").run { initSign(secondPair.private); update(value); sign() }
        }
        assertRejected { FactoryIdentityBackup.validateMaterial(material.copy(certificate = invalidCertificate)) }
    }

    @Test fun validationAvoidsDirectCodecExportButAllowsProviderKeyTranslation() {
        val original = material.key as RSAPrivateKey
        val codecClass = FactoryIdentityBackup::class.java.name
        val providerCopies = mutableListOf<ByteArray>()
        val translatedKey = object : RSAPrivateKey by original {
            override fun getEncoded(): ByteArray {
                // Conscrypt may encode an unfamiliar RSA wrapper when Signature.initSign imports
                // it; SunRsaSign can instead read its RSA parameters. Neither behavior is a codec
                // export. Reject direct codec serialization without pinning a signing provider.
                val caller = Throwable().stackTrace[1].className
                assertFalse("Validation must not directly serialize the key in the codec",
                    caller == codecClass || caller.startsWith("${codecClass}\$"))
                return original.encoded.also(providerCopies::add)
            }
        }
        try { FactoryIdentityBackup.validateMaterial(material.copy(key = translatedKey)) }
        finally { providerCopies.forEach { it.fill(0) } }
    }

    @Test fun exportRejectsNonExportableKeyAndDoesNotModifyCallerPassphrase() {
        val original = material.key as RSAPrivateKey
        val noExport = object : RSAPrivateKey by original { override fun getEncoded(): ByteArray? = null }
        val passphrase = password()
        try {
            assertRejected { FactoryIdentityBackup.encrypt(material.copy(key = noExport), passphrase) }
            assertArrayEquals(password(), passphrase)
        } finally { passphrase.fill('\u0000') }
    }

    @Test fun passphraseValidationIsBoundedAndNeverMutatesInput() {
        for (size in listOf(12, 1024)) {
            val valid = CharArray(size) { 'x' }
            FactoryIdentityBackup.validatePassphrase(valid)
            assertTrue(valid.all { it == 'x' })
        }
        for (invalid in listOf(CharArray(0), CharArray(11) { 'x' }, CharArray(1025) { 'x' }, CharArray(12) { ' ' })) {
            try { FactoryIdentityBackup.validatePassphrase(invalid); fail("Invalid passphrase accepted") }
            catch (expected: IllegalArgumentException) { assertFalse(expected.message.orEmpty().contains(String(invalid).takeIf { it.isNotEmpty() } ?: "secret")) }
            assertRejected { FactoryIdentityBackup.decrypt(envelope, invalid) }
        }
    }

    @Test fun unicodePassphraseRoundTripsWithoutConvertingCallerInputToString() {
        val passphrase = "é\uD83D\uDD12漢字 contraseña larga".toCharArray()
        try {
            val restored = FactoryIdentityBackup.decrypt(FactoryIdentityBackup.encrypt(material, passphrase), passphrase)
            assertEquals(APP_ID, restored.appId)
        } finally { passphrase.fill('\u0000') }
    }

    @Test fun diagnosticRepresentationNeverIncludesKeysCertificatesOrContinuity() {
        assertEquals("FactoryIdentityBackup.Material([redacted])", material.toString())
        assertFalse(material.toString().contains(APP_ID))
        assertFalse(material.toString().contains(APK_HASH))
    }

    @Test fun fingerprintMatchesIndependentSha256OfExactCertificateDer() {
        val expected = MessageDigest.getInstance("SHA-256").digest(material.certificate.encoded).joinToString("") { "%02x".format(it) }
        assertEquals(expected, FactoryIdentityBackup.fingerprint(material.certificate))
    }

    private fun decrypt(bytes: ByteArray): FactoryIdentityBackup.Material {
        val passphrase = password()
        return try { FactoryIdentityBackup.decrypt(bytes, passphrase) } finally { passphrase.fill('\u0000') }
    }

    private fun assertRejected(action: () -> Unit): FactoryIdentityBackup.BackupException {
        try { action(); fail("Invalid backup was accepted") } catch (expected: FactoryIdentityBackup.BackupException) {
            assertEquals("Cannot process signing identity backup", expected.message)
            assertNull(expected.cause)
            assertEquals(0, expected.suppressed.size)
            return expected
        }
        throw AssertionError("Expected generic backup failure")
    }

    private fun assertRejectedPayload(mutate: (ByteArray) -> ByteArray) {
        val plaintext = referencePlaintext()
        var changed: ByteArray? = null
        try {
            changed = mutate(plaintext)
            assertRejected { decrypt(referenceSeal(changed)) }
        } finally { plaintext.fill(0); changed?.fill(0) }
    }

    private fun assertRejectedCustomPayload(certificate: X509Certificate, key: ByteArray) {
        val plaintext = try { referencePayload(certificate.encoded, key) } finally { key.fill(0) }
        try { assertRejected { decrypt(referenceSeal(plaintext)) } } finally { plaintext.fill(0) }
    }

    /** Independent format encoder, deliberately capable of building authenticated invalid fixtures. */
    private fun referencePayload(certificate: ByteArray, key: ByteArray): ByteArray {
        val app = APP_ID.toByteArray(Charsets.US_ASCII)
        val hash = ByteArray(32) { 0xbb.toByte() }
        return ByteBuffer.allocate(7 * 4 + app.size + 32 + hash.size + certificate.size + key.size)
            .putInt(1).putInt(app.size).put(app).put(MessageDigest.getInstance("SHA-256").digest(certificate))
            .putInt(7).putInt(hash.size).put(hash).putInt(certificate.size).put(certificate)
            .putInt(key.size).put(key).putInt(0).array()
    }

    private fun referencePlaintext(): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, referenceKey, GCMParameterSpec(128, envelope.copyOfRange(68, 80)))
        updateAAD(envelope.copyOfRange(0, 80))
        doFinal(envelope.copyOfRange(80, envelope.size))
    }

    private fun referenceSeal(plaintext: ByteArray): ByteArray {
        val header = envelope.copyOfRange(0, 80)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        nonce.copyInto(header, 68)
        ByteBuffer.wrap(header).putInt(32, plaintext.size + 16)
        return header + Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, referenceKey, GCMParameterSpec(128, nonce))
            updateAAD(header)
            doFinal(plaintext)
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { offset -> needle.indices.all { haystack[offset + it] == needle[it] } }

    companion object {
        private const val APP_ID = "org.example.recovery"
        private val APK_HASH = "b".repeat(64)
        private fun password() = "RAM only test backup passphrase".toCharArray()
        private val pair: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val secondPair: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val material by lazy { FactoryIdentityBackup.Material(APP_ID, pair.private, syntheticCertificate(pair), 7, APK_HASH) }
        private val envelope by lazy {
            val passphrase = password()
            try { FactoryIdentityBackup.encrypt(material, passphrase) } finally { passphrase.fill('\u0000') }
        }
        private val referenceKey by lazy {
            val passphrase = password()
            val spec = PBEKeySpec(passphrase, envelope.copyOfRange(36, 68), 600_000, 256)
            passphrase.fill('\u0000')
            try {
                val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
            } finally { spec.clearPassword() }
        }
        private fun syntheticCertificate(pair: KeyPair): X509Certificate = EphemeralFactoryCertificate.create(pair.public.encoded) { value ->
            Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(value); sign() }
        }
    }
}
