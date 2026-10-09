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
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

/** Legacy keys never become exportable. Recoverable identities are an explicit native-UI choice. */
internal open class FactorySigningIdentity(
    private val context: Context,
    private val protection: PortableProtection = AndroidPortableProtection(),
) {
    data class Identity(val key: PrivateKey, val certificate: X509Certificate, val fingerprint: String) {
        override fun toString(): String = "FactorySigningIdentity.Identity([redacted])"
    }
    data class State(val existing: Boolean, val fingerprint: String?, val lastVersion: Int,
        val lastScope: FactorySigningScope? = null, val lastApkSha256: String? = null,
        val recordSha256: String? = null, val mode: String = LEGACY,
        val continuityKnown: Boolean = true, val continuityResolution: String = "local_history")
    private val directory = File(context.noBackupFilesDir, "apk-factory/identities")
    private fun file(appId: String): AtomicFile {
        FactoryIdentityBackup.validateAppId(appId)
        return AtomicFile(File(directory, "$appId.json"))
    }
    private fun alias(appId: String) = legacyAlias(appId)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun hash(bytes: ByteArray) = com.jarvys.agent.coding.ProjectScope.sha256(bytes)
    private fun digest(saved: JSONObject?) = saved?.let { hash(it.toString().toByteArray(Charsets.UTF_8)) }
    private fun record(appId: String): JSONObject? {
        val target = file(appId)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return target.openRead().use { input ->
            val bytes = input.readBytesBounded(64 * 1024)
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
    // Overridable only to supply RAM-only host fixtures; product always uses AndroidKeyStore.
    protected open fun legacyPresent(appId: String): Boolean = store().containsAlias(alias(appId))
    protected open fun legacyIdentity(appId: String): Identity {
        val entry = store().getEntry(alias(appId), null) as? KeyStore.PrivateKeyEntry
            ?: error("Signing key unavailable; no replacement was generated")
        val cert = entry.certificate as X509Certificate
        return Identity(entry.privateKey, cert, hash(cert.encoded))
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
        val saved = record(appId)
        if (saved == null) {
            check(!legacyPresent(appId) && !protection.present(appId)) {
                "Signing protection exists without its identity record. Review recovery; no replacement was created."
            }
            return@synchronized State(false, null, 0)
        }
        val mode = validateRecord(saved, appId)
        check(saved.optString("state") == "ready") {
            "Signing identity creation was interrupted. Import its matching backup if recoverable; no key was rotated."
        }
        val identity = if (mode == RECOVERABLE) portableIdentity(saved) else {
            check(legacyPresent(appId)) { "This app's signing key is missing. Existing apps cannot be updated with a replacement identity." }
            legacyIdentity(appId)
        }
        try {
            check(identity.fingerprint == saved.getString("certificateSha256")) { "Signing identity mismatch. Nothing was signed." }
            val version = saved.getInt("lastVersion")
            check(version >= 0) { "Invalid signing identity version" }
            State(true, identity.fingerprint, version,
                FactorySigningScope.readBaseline(saved, appId, identity.fingerprint, version),
                saved.optString("lastApkSha256").takeIf { it.isNotEmpty() }, digest(saved), mode,
                if (mode == RECOVERABLE) saved.getBoolean("continuityKnown") else true,
                if (mode == RECOVERABLE) saved.getString("continuityResolution") else "local_history")
        } finally {
            if (mode == RECOVERABLE) FactoryIdentityBackup.destroyBestEffort(identity.key)
        }
    }

    open fun obtainAfterApproval(appId: String, approvedState: State): Identity = synchronized(LOCK) {
        check(state(appId) == approvedState) { "Signing identity changed while awaiting approval" }
        check(approvedState.continuityKnown) { "Restore history is unresolved. Review the latest version in Factory identities before signing." }
        if (!approvedState.existing) {
            save(appId, JSONObject().put("schemaVersion", 1).put("appId", appId).put("state", "reserved"))
            createLegacy(appId)
            val identity = legacyIdentity(appId)
            save(appId, JSONObject().put("schemaVersion", 1).put("appId", appId).put("state", "ready")
                .put("certificateSha256", identity.fingerprint).put("lastVersion", 0))
        }
        if (approvedState.mode == RECOVERABLE) portableIdentity(record(appId)!!) else legacyIdentity(appId)
    }
    /** Snapshot supports repair of a pinned reserved record even when the local wrapping key is gone. */
    fun recoverySnapshot(appId: String): String? = synchronized(LOCK) { digest(record(appId)) }

    /** Called by secure native UI only, after the user has saved their encrypted backup. */
    fun createRecoverableAfterApproval(material: FactoryIdentityBackup.Material, stillApproved: () -> Boolean = { true }) = synchronized(LOCK) {
        check(stillApproved()) { "Identity creation was cancelled" }
        FactoryIdentityBackup.validateMaterial(material)
        require(material.lastVersion == 0 && material.lastApkSha256 == null) { "New identities cannot claim previous releases" }
        check(!state(material.appId).existing) { "An existing identity cannot be converted or replaced" }
        persistPortable(material, continuityKnown = true, resolution = "local_history", old = null, stillApproved = stillApproved)
    }
    fun exportRecoverableAfterApproval(appId: String, expectedState: State, stillApproved: () -> Boolean = { true }): FactoryIdentityBackup.Material = synchronized(LOCK) {
        check(stillApproved()) { "Backup export was cancelled" }
        check(state(appId) == expectedState && expectedState.existing && expectedState.mode == RECOVERABLE) {
            "Only the unchanged recoverable identity can be exported. Legacy AndroidKeyStore keys are non-exportable."
        }
        check(stillApproved()) { "Backup export was cancelled" }
        val identity = portableIdentity(record(appId)!!)
        FactoryIdentityBackup.Material(appId, identity.key, identity.certificate, expectedState.lastVersion, expectedState.lastApkSha256)
    }
    fun importRecoverableAfterApproval(material: FactoryIdentityBackup.Material, expectedRecordSha256: String?, stillApproved: () -> Boolean = { true }) = synchronized(LOCK) {
        check(stillApproved()) { "Backup import was cancelled" }
        FactoryIdentityBackup.validateMaterial(material)
        val old = record(material.appId)
        check(digest(old) == expectedRecordSha256) { "Identity changed while awaiting restore approval" }
        check(!legacyPresent(material.appId)) { "An existing non-exportable identity cannot be replaced or converted" }
        if (old != null) {
            check(validateRecord(old, material.appId) == RECOVERABLE) { "A legacy identity cannot be replaced, even if its key is missing" }
            check(old.getString("certificateSha256") == hash(material.certificate.encoded)) { "Backup certificate does not match the existing identity" }
        } else check(!protection.present(material.appId)) { "Local protection exists without its record; review recovery before import" }
        val priorVersion = old?.getInt("lastVersion") ?: 0
        check(priorVersion >= 0) { "Invalid saved version history" }
        val version = maxOf(priorVersion, material.lastVersion)
        val priorSha = old?.optString("lastApkSha256")?.takeIf { it.isNotEmpty() }
        if (priorVersion == material.lastVersion && priorSha != null && material.lastApkSha256 != null) {
            check(priorSha == material.lastApkSha256) { "Conflicting APK evidence for the same signed version" }
        }
        val sha = when {
            priorVersion > material.lastVersion -> priorSha
            material.lastVersion > priorVersion -> material.lastApkSha256
            else -> priorSha ?: material.lastApkSha256
        }
        persistPortable(material.copy(lastVersion = version, lastApkSha256 = sha), false, "restored_unknown", old, stillApproved)
    }
    /** A user-declared floor is explicit uncertainty resolution, not proof of the latest release. */
    fun reconcileAfterApproval(appId: String, expectedState: State, versionFloor: Int, stillApproved: () -> Boolean = { true }) = synchronized(LOCK) {
        check(stillApproved()) { "Version reconciliation was cancelled" }
        check(state(appId) == expectedState && expectedState.existing && expectedState.mode == RECOVERABLE) { "Identity changed during reconciliation" }
        require(versionFloor >= expectedState.lastVersion) { "A restored version floor cannot decrease" }
        val saved = record(appId)!!
        if (versionFloor > expectedState.lastVersion) { saved.remove("lastApkSha256"); saved.remove("lastSignedScope") }
        saved.put("lastVersion", versionFloor).put("continuityKnown", true).put("continuityResolution", "user_declared_floor")
        check(stillApproved()) { "Version reconciliation was cancelled" }
        save(appId, saved)
    }
    // Shared lock covers signing through publication and all identity mutations, across service instances.
    open fun recordSigned(appId: String, fingerprint: String, versionCode: Int, apkSha: String, scope: FactorySigningScope? = null) = synchronized(LOCK) {
        require(apkSha.matches(Regex("[a-f0-9]{64}"))) { "Invalid signed APK hash" }
        val current = state(appId)
        check(current.existing && current.continuityKnown && current.fingerprint == fingerprint && versionCode > current.lastVersion) { "Signing identity/version changed" }
        val saved = record(appId)!!
        saved.put("lastVersion", versionCode).put("lastApkSha256", apkSha)
        saved.remove("lastSignedScope")
        if (scope != null) saved.put("lastSignedScope", scope.anchored(appId, fingerprint, versionCode, apkSha))
        save(appId, saved)
    }
    private fun validateRecord(saved: JSONObject, appId: String): String {
        check(saved.opt("appId") is String && saved.getString("appId") == appId) { "Signing identity app ID mismatch" }
        val schema = exactInt(saved, "schemaVersion")
        check(saved.opt("state") is String && saved.getString("state") in setOf("ready", "reserved")) { "Invalid signing identity state" }
        val mode = when (schema) {
            1 -> { check(!saved.has("mode") || saved.opt("mode") == LEGACY) { "Invalid legacy identity policy" }; LEGACY }
            2 -> { check(saved.opt("mode") == RECOVERABLE) { "Unsupported identity policy" }; RECOVERABLE }
            else -> error("Unsupported signing identity format; no changes made")
        }
        if (schema == 2 || saved.getString("state") == "ready") {
            val version = exactInt(saved, "lastVersion")
            check(version >= 0 && saved.opt("certificateSha256") is String && saved.getString("certificateSha256").matches(Regex("[a-f0-9]{64}"))) { "Invalid signing identity evidence" }
            if (saved.has("lastApkSha256")) check(version > 0 && saved.opt("lastApkSha256") is String && saved.getString("lastApkSha256").matches(Regex("[a-f0-9]{64}"))) { "Invalid signed APK evidence" }
        }
        if (schema == 2) {
            check(saved.opt("continuityKnown") is Boolean) { "Invalid continuity state" }
            val resolution = saved.opt("continuityResolution")
            check(resolution in setOf("local_history", "restored_unknown", "user_declared_floor") &&
                saved.getBoolean("continuityKnown") == (resolution != "restored_unknown")) { "Invalid continuity resolution" }
            check(saved.opt("certificate") is String) { "Missing portable certificate" }
        }
        return mode
    }
    private fun exactInt(saved: JSONObject, name: String): Int {
        val raw = saved.opt(name)
        check(raw is Int || raw is Long) { "Invalid identity integer" }
        val value = (raw as Number).toLong()
        check(value in 0..Int.MAX_VALUE) { "Identity integer outside supported range" }
        return value.toInt()
    }
    private fun portableIdentity(saved: JSONObject): Identity {
        val appId = saved.getString("appId")
        val fingerprint = saved.getString("certificateSha256")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(decode(saved.getString("certificate")).inputStream()) as X509Certificate
        check(hash(certificate.encoded) == fingerprint) { "Local certificate integrity failed" }
        val bytes = protection.unwrap(appId, fingerprint, saved.getJSONObject("protectedKey"))
        try {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
            try {
                FactoryIdentityBackup.validateMaterial(FactoryIdentityBackup.Material(appId, key, certificate, saved.getInt("lastVersion"), saved.optString("lastApkSha256").takeIf { it.isNotEmpty() }))
                return Identity(key, certificate, fingerprint)
            } catch (failure: Exception) {
                FactoryIdentityBackup.destroyBestEffort(key)
                throw failure
            }
        } finally { bytes.fill(0) }
    }
    private fun persistPortable(material: FactoryIdentityBackup.Material, continuityKnown: Boolean, resolution: String, old: JSONObject?, stillApproved: () -> Boolean) {
        val fingerprint = hash(material.certificate.encoded)
        val saved = JSONObject().put("schemaVersion", 2).put("mode", RECOVERABLE).put("appId", material.appId)
            .put("certificateSha256", fingerprint).put("certificate", encode(material.certificate.encoded))
            .put("lastVersion", material.lastVersion).put("continuityKnown", continuityKnown).put("continuityResolution", resolution)
        material.lastApkSha256?.let { saved.put("lastApkSha256", it) }
        if (old != null && old.optInt("lastVersion") == material.lastVersion && old.optString("lastApkSha256") == material.lastApkSha256)
            old.optJSONObject("lastSignedScope")?.let { saved.put("lastSignedScope", it) }
        // Pin identity/version BEFORE any persistent key action. An interruption cannot authorize a new identity.
        // Existing ready records remain intact until replacement is fully encrypted and ready for AtomicFile.
        check(stillApproved()) { "Identity save was cancelled before its durable commit" }
        if (old == null) save(material.appId, JSONObject(saved.toString()).put("state", "reserved"))
        val bytes = material.key.encoded ?: error("Portable private key encoding unavailable")
        try { saved.put("protectedKey", protection.wrap(material.appId, fingerprint, bytes)) } finally { bytes.fill(0) }
        save(material.appId, saved.put("state", "ready"))
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) { val count = read(buffer); if (count < 0) return out.toByteArray()
            check(out.size() + count <= limit) { "Signing identity record exceeds its size limit" }; out.write(buffer, 0, count) }
    }
    private fun save(appId: String, value: JSONObject) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare identity storage" }
        val target = file(appId)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync() // AtomicFile logs some sync failures instead of propagating them.
        } catch (failure: Throwable) {
            target.failWrite(stream)
            throw failure
        }
        // Never call failWrite after finish: it may already have committed the new record.
        finishRecord(target, stream)
        val committed = target.openRead().use { it.readBytesBounded(64 * 1024) }
        check(committed.contentEquals(bytes)) { "Identity record commit was not verified; preserve recovery evidence and do not publish" }
    }
    protected open fun finishRecord(target: AtomicFile, stream: java.io.FileOutputStream) = target.finishWrite(stream)
    companion object {
        val LOCK = Any()
        internal fun legacyAlias(appId: String) = "jarvys.apk-factory.$appId"
        // ':' cannot occur in a valid appId, so no existing legacy signing alias can collide.
        internal fun portableProtectionAlias(appId: String) = "jarvys.apk-factory.wrap:$appId"
        const val LEGACY = "non_exportable"
        const val RECOVERABLE = "recoverable"
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(text: String): ByteArray { require(text.length <= 32768); return Base64.decode(text, Base64.NO_WRAP) }
    }
    internal interface PortableProtection {
        fun present(appId: String): Boolean
        fun wrap(appId: String, fingerprint: String, bytes: ByteArray): JSONObject
        fun unwrap(appId: String, fingerprint: String, value: JSONObject): ByteArray
    }
    private class AndroidPortableProtection : PortableProtection {
        private fun alias(appId: String) = portableProtectionAlias(appId)
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun present(appId: String) = store().containsAlias(alias(appId))
        override fun wrap(appId: String, fingerprint: String, bytes: ByteArray): JSONObject {
            if (!present(appId)) {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias(appId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                    generateKey()
                }
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, store().getKey(alias(appId), null) as SecretKey)
            cipher.updateAAD("jarvys-factory-local-v1:$appId:$fingerprint".toByteArray(Charsets.UTF_8))
            return JSONObject().put("format", 1).put("iv", encode(cipher.iv)).put("ciphertext", encode(cipher.doFinal(bytes)))
        }
        override fun unwrap(appId: String, fingerprint: String, value: JSONObject): ByteArray {
            check(value.getInt("format") == 1 && present(appId)) { "Local protection unavailable. Restore the matching encrypted backup." }
            val nonce = decode(value.getString("iv")); require(nonce.size == 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, store().getKey(alias(appId), null) as SecretKey, GCMParameterSpec(128, nonce))
            cipher.updateAAD("jarvys-factory-local-v1:$appId:$fingerprint".toByteArray(Charsets.UTF_8))
            return cipher.doFinal(decode(value.getString("ciphertext")))
        }
    }
}
