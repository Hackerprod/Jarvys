package com.jarvys.agent.apkfactory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

internal object FactoryIdentityId {
    private val APP_ID = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,15}")
    fun validate(value: String) {
        require(value.length <= 127 && APP_ID.matches(value) && value !in setOf("android", "com.jarvys.agent")) {
            "Invalid application ID"
        }
    }
}

/** Signing identities stay local. Older locally protected records remain readable for updates. */
internal open class FactorySigningIdentity(
    private val context: Context,
    private val protection: LocalProtection = AndroidLocalProtection(),
) {
    data class Identity(val key: PrivateKey, val certificate: X509Certificate, val fingerprint: String) {
        override fun toString() = "FactorySigningIdentity.Identity([redacted])"
    }
    data class State(
        val existing: Boolean,
        val fingerprint: String?,
        val lastVersion: Int,
        val lastScope: FactorySigningScope? = null,
        val lastApkSha256: String? = null,
        val recordSha256: String? = null,
    )

    private val directory = File(context.noBackupFilesDir, "apk-factory/identities")
    private fun file(appId: String): AtomicFile {
        FactoryIdentityId.validate(appId)
        return AtomicFile(File(directory, "$appId.json"))
    }
    private fun alias(appId: String) = legacyAlias(appId)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun hash(bytes: ByteArray) = com.jarvys.agent.coding.ProjectScope.sha256(bytes)
    private fun digest(saved: JSONObject?) = saved?.let { hash(it.toString().toByteArray(Charsets.UTF_8)) }

    private fun record(appId: String): JSONObject? {
        val target = file(appId)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
            FactoryArtifactAuthority.invalidate(appId)
            return null
        }
        return target.openRead().use { input ->
            val bytes = input.readBytesBounded(64 * 1024)
            JSONObject(String(bytes, Charsets.UTF_8)).also { validateRecord(it, appId) }
        }
    }

    protected open fun legacyPresent(appId: String) = store().containsAlias(alias(appId))
    protected open fun legacyIdentity(appId: String): Identity {
        val entry = store().getEntry(alias(appId), null) as? KeyStore.PrivateKeyEntry
            ?: error("Signing key unavailable; no replacement was generated")
        val certificate = entry.certificate as X509Certificate
        return Identity(entry.privateKey, certificate, hash(certificate.encoded))
    }
    protected open fun createLegacy(appId: String) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias(appId), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=Jarvys Generated App"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(0)).setCertificateNotAfter(Date(4102444800000L)).build())
        generator.generateKeyPair()
    }

    open fun state(appId: String): State = synchronized(LOCK) {
        try {
            val saved = record(appId)
            if (saved == null) {
                check(!legacyPresent(appId) && !protection.present(appId)) {
                    "Signing protection exists without its identity record"
                }
                return@synchronized State(false, null, 0)
            }
            check(saved.optString("state") == "ready" || saved.optString("state") == "reserved") {
                "Invalid signing identity state"
            }
            if (saved.optString("state") == "reserved") return@synchronized State(false, null, 0, recordSha256 = digest(saved))
            val identity = loadIdentity(saved, appId)
            try {
                check(identity.fingerprint == saved.getString("certificateSha256")) { "Signing identity mismatch" }
                val version = exactInt(saved, "lastVersion")
                check(version >= 0)
                State(true, identity.fingerprint, version,
                    FactorySigningScope.readBaseline(saved, appId, identity.fingerprint, version),
                    saved.optString("lastApkSha256").takeIf(String::isNotEmpty), digest(saved)).also {
                    FactoryArtifactAuthority.observeIdentity(appId, it)
                }
            } finally {
                if (hasLocallyWrappedKey(saved)) identity.key.encoded?.fill(0)
            }
        } catch (failure: Exception) {
            FactoryArtifactAuthority.invalidate(appId)
            throw failure
        }
    }

    /** Creates the per-app AndroidKeyStore key on first signing; existing local identities are reused. */
    open fun obtain(appId: String, expectedState: State): Identity = synchronized(LOCK) {
        FactoryIdentityId.validate(appId)
        check(state(appId) == expectedState) { "Signing identity changed before key use" }
        if (!expectedState.existing) {
            save(appId, JSONObject().put("schemaVersion", 1).put("appId", appId).put("state", "reserved"))
            if (!legacyPresent(appId)) createLegacy(appId)
            val created = legacyIdentity(appId)
            save(appId, JSONObject().put("schemaVersion", 1).put("appId", appId).put("state", "ready")
                .put("certificateSha256", created.fingerprint).put("lastVersion", 0))
        }
        val saved = record(appId) ?: error("Signing identity record unavailable")
        loadIdentity(saved, appId)
    }

    open fun recordSigned(appId: String, fingerprint: String, versionCode: Int, apkSha: String, scope: FactorySigningScope? = null) = synchronized(LOCK) {
        require(apkSha.matches(Regex("[a-f0-9]{64}"))) { "Invalid signed APK hash" }
        val current = state(appId)
        check(current.existing && current.fingerprint == fingerprint && versionCode > current.lastVersion) { "Signing identity/version changed" }
        val saved = record(appId) ?: error("Signing identity record unavailable")
        saved.put("lastVersion", versionCode).put("lastApkSha256", apkSha).remove("lastSignedScope")
        scope?.let { saved.put("lastSignedScope", it.anchored(appId, fingerprint, versionCode, apkSha)) }
        if (hasLocallyWrappedKey(saved)) normalizeLegacyRecord(saved)
        save(appId, saved, preserveRetainedArtifacts = true)
    }

    private fun loadIdentity(saved: JSONObject, appId: String): Identity {
        if (!hasLocallyWrappedKey(saved)) {
            check(legacyPresent(appId)) { "This app's signing key is missing; no replacement identity was generated" }
            return legacyIdentity(appId)
        }
        val fingerprint = saved.getString("certificateSha256")
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(decode(saved.getString("certificate")).inputStream()) as X509Certificate
        check(hash(certificate.encoded) == fingerprint) { "Local certificate integrity failed" }
        val bytes = protection.unwrap(appId, fingerprint, saved.getJSONObject("protectedKey"))
        try {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
            val identity = Identity(key, certificate, fingerprint)
            check(matches(identity)) { "Local signing key does not match its certificate" }
            return identity
        } finally { bytes.fill(0) }
    }

    private fun matches(identity: Identity): Boolean {
        val challenge = "jarvys-factory-signing-identity".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(identity.key); update(challenge); sign()
        }
        return Signature.getInstance("SHA256withRSA").run {
            initVerify(identity.certificate.publicKey); update(challenge); verify(signature)
        }
    }

    private fun hasLocallyWrappedKey(saved: JSONObject) = saved.optInt("schemaVersion") in setOf(2, 3)

    private fun validateRecord(saved: JSONObject, appId: String) {
        check(saved.opt("appId") is String && saved.getString("appId") == appId) { "Signing identity app ID mismatch" }
        val schema = exactInt(saved, "schemaVersion")
        check(schema in setOf(1, 2, 3)) { "Unsupported signing identity format" }
        check(saved.opt("state") is String && saved.getString("state") in setOf("ready", "reserved")) { "Invalid signing identity state" }
        if (saved.getString("state") == "ready") {
            val version = exactInt(saved, "lastVersion")
            check(version >= 0 && saved.opt("certificateSha256") is String &&
                saved.getString("certificateSha256").matches(Regex("[a-f0-9]{64}"))) { "Invalid signing identity evidence" }
            if (saved.has("lastApkSha256")) check(version > 0 && saved.opt("lastApkSha256") is String &&
                saved.getString("lastApkSha256").matches(Regex("[a-f0-9]{64}"))) { "Invalid signed APK evidence" }
        }
        if (schema in setOf(2, 3)) {
            if (schema == 2) check(saved.opt("mode") == "recoverable") { "Invalid legacy signing identity" }
            if (saved.getString("state") == "ready")
                check(saved.opt("certificate") is String && saved.opt("protectedKey") is JSONObject) { "Missing local signing key" }
        }
    }

    private fun normalizeLegacyRecord(saved: JSONObject) {
        saved.put("schemaVersion", 3)
        saved.remove("mode")
        saved.remove("continuityKnown")
        saved.remove("continuityResolution")
    }

    private fun exactInt(saved: JSONObject, name: String): Int {
        val raw = saved.opt(name)
        check(raw is Int || raw is Long) { "Invalid signing identity integer" }
        val value = (raw as Number).toLong()
        check(value in 0..Int.MAX_VALUE) { "Signing identity integer outside supported range" }
        return value.toInt()
    }

    private fun save(appId: String, value: JSONObject, preserveRetainedArtifacts: Boolean = false) {
        val target = file(appId)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        FactoryArtifactAuthority.beforeIdentityWrite(appId, preserveRetainedArtifacts)
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare identity storage" }
            val stream = target.startWrite()
            try { stream.write(bytes); stream.flush(); stream.fd.sync() }
            catch (failure: Throwable) { target.failWrite(stream); throw failure }
            target.finishWrite(stream)
            check(target.openRead().use { it.readBytesBounded(64 * 1024).contentEquals(bytes) }) {
                "Identity record commit was not verified"
            }
        } catch (failure: Exception) { FactoryArtifactAuthority.invalidate(appId); throw failure }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return out.toByteArray()
            check(count > 0 && out.size() + count <= limit) { "Signing identity record exceeds its size limit" }
            out.write(buffer, 0, count)
        }
    }

    companion object {
        val LOCK = Any()
        internal fun legacyAlias(appId: String) = "jarvys.apk-factory.$appId"
        internal fun localProtectionAlias(appId: String) = "jarvys.apk-factory.wrap:$appId"
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(text: String): ByteArray { require(text.length <= 32768); return Base64.decode(text, Base64.NO_WRAP) }
    }

    internal interface LocalProtection {
        fun present(appId: String): Boolean
        fun wrap(appId: String, fingerprint: String, bytes: ByteArray): JSONObject
        fun unwrap(appId: String, fingerprint: String, value: JSONObject): ByteArray
    }

    private class AndroidLocalProtection : LocalProtection {
        private fun alias(appId: String) = localProtectionAlias(appId)
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun present(appId: String) = store().containsAlias(alias(appId))
        override fun wrap(appId: String, fingerprint: String, bytes: ByteArray): JSONObject {
            if (!present(appId)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias(appId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, store().getKey(alias(appId), null) as SecretKey)
            cipher.updateAAD("jarvys-factory-local-v1:$appId:$fingerprint".toByteArray(Charsets.UTF_8))
            return JSONObject().put("format", 1).put("iv", encode(cipher.iv)).put("ciphertext", encode(cipher.doFinal(bytes)))
        }
        override fun unwrap(appId: String, fingerprint: String, value: JSONObject): ByteArray {
            check(value.getInt("format") == 1 && present(appId)) { "Local signing identity is unavailable" }
            val nonce = decode(value.getString("iv")); require(nonce.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, store().getKey(alias(appId), null) as SecretKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD("jarvys-factory-local-v1:$appId:$fingerprint".toByteArray(Charsets.UTF_8))
            return cipher.doFinal(decode(value.getString("ciphertext")))
        }
    }
}
