package com.jarvys.agent.apkfactory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Per-application, non-exportable identity. No default key, import, rotation or recovery-by-replacement. */
internal open class FactorySigningIdentity(private val context: Context) {
    data class Identity(val key: PrivateKey, val certificate: X509Certificate, val fingerprint: String)
    data class State(val existing: Boolean, val fingerprint: String?, val lastVersion: Int)
    private val directory = File(context.noBackupFilesDir, "apk-factory/identities")
    private fun file(appId: String) = AtomicFile(File(directory, "$appId.json"))
    private fun alias(appId: String) = "jarvys.apk-factory.$appId"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun hash(bytes: ByteArray) = com.jarvys.agent.coding.ProjectScope.sha256(bytes)
    private fun record(appId: String): JSONObject? {
        val file = file(appId)
        return if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) JSONObject(String(file.readFully(), Charsets.UTF_8)) else null
    }
    open fun state(appId: String): State {
        val saved = record(appId)
        val keystore = store()
        val present = keystore.containsAlias(alias(appId))
        if (saved == null) {
            check(!present) { "Signing key exists without its identity record. Review recovery; no replacement key was created." }
            return State(false, null, 0)
        }
        check(saved.optInt("schemaVersion") == 1 && saved.optString("appId") == appId && saved.optString("state") == "ready") {
            "Signing identity creation was interrupted. Review recovery; no key was rotated."
        }
        check(present) { "This app's signing key is missing. Existing apps cannot be updated with a replacement identity." }
        val fingerprint = hash(keystore.getCertificate(alias(appId)).encoded)
        check(fingerprint == saved.getString("certificateSha256")) { "Signing identity mismatch. Nothing was signed." }
        return State(true, fingerprint, saved.getInt("lastVersion"))
    }
    open fun obtainAfterApproval(appId: String, approvedState: State): Identity {
        check(state(appId) == approvedState) { "Signing identity changed while awaiting approval" }
        if (!approvedState.existing) {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare signing identity storage" }
            save(appId, JSONObject().put("schemaVersion", 1).put("appId", appId).put("state", "reserved"))
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias(appId), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=Jarvys Generated App"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0)).setCertificateNotAfter(Date(4102444800000L)).build())
            generator.generateKeyPair()
            val certificate = store().getCertificate(alias(appId)) as X509Certificate
            save(appId, JSONObject().put("schemaVersion", 1).put("appId", appId).put("state", "ready")
                .put("certificateSha256", hash(certificate.encoded)).put("lastVersion", 0))
        }
        val entry = store().getEntry(alias(appId), null) as? KeyStore.PrivateKeyEntry
            ?: error("Signing key unavailable; no replacement was generated")
        val certificate = entry.certificate as X509Certificate
        return Identity(entry.privateKey, certificate, hash(certificate.encoded))
    }
    open fun recordSigned(appId: String, fingerprint: String, versionCode: Int, apkSha: String) {
        val current = state(appId)
        check(current.existing && current.fingerprint == fingerprint && versionCode > current.lastVersion) { "Signing identity/version changed" }
        save(appId, JSONObject().put("schemaVersion", 1).put("appId", appId).put("state", "ready")
            .put("certificateSha256", fingerprint).put("lastVersion", versionCode).put("lastApkSha256", apkSha))
    }
    private fun save(appId: String, value: JSONObject) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare identity storage" }
        val target = file(appId)
        val stream = target.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
    }
}
