package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import com.jarvys.agent.coding.ProjectScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Host-private, exact signed-artifact evidence. Installed assets never supply authority.
 * Keeps 64 recent artifacts plus the currently installed matching-certificate version, if older.
 * Signing key and version state remain owned by FactorySigningIdentity, not this history.
 */
internal open class FactorySignedArtifactHistory(
    private val directory: File,
    private val installedVersion: (String, String) -> Long? = { _, _ -> null },
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "apk-factory/signed-artifacts"),
        { appId, certificate -> installedVersion(context, appId, certificate) })

    private data class Artifact(val appId: String, val certificate: String, val version: Int,
        val apk: String, val scope: FactorySigningScope) {
        fun encoded() = scope.anchored(appId, certificate, version, apk).toString()
        fun digest() = ProjectScope.sha256(encoded().toByteArray(Charsets.UTF_8))
    }
    private fun file(appId: String): AtomicFile {
        FactoryIdentityId.validate(appId)
        return AtomicFile(File(directory, "$appId.json"))
    }

    /** Preserve a legacy/latest receipt before its identity record advances; never invent unknown scope. */
    fun preserveLatest(appId: String, state: FactorySigningIdentity.State) = synchronized(FactorySigningIdentity.LOCK) {
        val previous = read(appId)
        if (!state.existing) {
            check(previous.isNullOrEmpty()) { "Artifact history exists without its signing identity" }
            if (previous == null) write(appId, emptyList())
            return@synchronized
        }
        val certificate = state.fingerprint ?: error("Unknown signing certificate")
        require(certificate.matches(HASH) && state.lastVersion >= 0)
        validateHistory(appId, previous, certificate, state.lastVersion)
        val scope = state.lastScope ?: run {
            if (previous == null) write(appId, emptyList())
            return@synchronized
        }
        val apk = state.lastApkSha256 ?: error("Signed scope has no exact APK evidence")
        require(state.lastVersion > 0 && apk.matches(HASH))
        val artifact = Artifact(appId, certificate, state.lastVersion, apk, scope)
        val existing = previous.orEmpty().firstOrNull { it.version == artifact.version || it.apk == artifact.apk }
        check(existing == null || existing == artifact) { "Conflicting signed artifact evidence; history was not replaced" }
        if (existing != null) return@synchronized
        val all = (previous.orEmpty() + artifact).sortedByDescending { it.version }
        val recent = all.take(RECENT_ARTIFACTS)
        val retained = if (all.size > RECENT_ARTIFACTS) {
            // Retention only. Admission still verifies UID, certificate, version and the entire installed APK.
            val pinnedVersion = installedVersion(appId, certificate)
            val pinned = all.firstOrNull { it.version.toLong() == pinnedVersion }
            (recent + listOfNotNull(pinned).filter { it !in recent }).sortedByDescending { it.version }
        } else recent
        write(appId, retained)
    }

    /** New signatures must acquire durable exact scope before their bytes can be published. */
    fun recordCurrent(appId: String, state: FactorySigningIdentity.State) {
        require(state.existing && state.lastScope != null && state.lastApkSha256 != null && state.lastVersion > 0)
        preserveLatest(appId, state)
    }

    fun verify(appId: String, certificate: String, apk: String, version: Long,
        state: FactorySigningIdentity.State, requiredCapabilities: Set<String>): FactoryDocumentCoordinator.Proof =
        verified(appId, certificate, apk, version, state, requiredCapabilities).proof

    fun capture(proof: FactoryDocumentCoordinator.Proof, state: FactorySigningIdentity.State, requiredCapabilities: Set<String>): FactoryArtifactAuthority.Token = synchronized(FactorySigningIdentity.LOCK) {
        val verified = verified(proof.appId, proof.certificate, proof.apk, proof.version, state, requiredCapabilities)
        check(verified.proof == proof) { "Signed artifact authority changed" }
        FactoryArtifactAuthority.capture(proof, verified.retained)
    }
    private data class Verified(val proof: FactoryDocumentCoordinator.Proof, val retained: Boolean)
    private fun verified(appId: String, certificate: String, apk: String, version: Long,
        state: FactorySigningIdentity.State, requiredCapabilities: Set<String>): Verified = synchronized(FactorySigningIdentity.LOCK) {
        authenticateIdentity(appId, certificate, state)
        require(apk.matches(HASH) && version in 1..state.lastVersion.toLong()) { "Unknown signed artifact version or hash" }
        val entries = read(appId)
        if (entries == null) {
            // Compatibility applies only to genuinely absent history, never a damaged file or missing entry.
            return@synchronized Verified(FactoryDocumentCoordinator.verifyEvidence(appId, certificate, apk, version, state, requiredCapabilities), false)
        }
        validateHistory(appId, entries, certificate, state.lastVersion)
        val selected = entries.singleOrNull { it.version.toLong() == version && it.apk == apk }
            ?: error("No retained signing receipt for this exact installed APK")
        val capabilities = selected.scope.toJson().getJSONArray("capabilities")
        require(requiredCapabilities.isNotEmpty() && requiredCapabilities.all { required ->
            (0 until capabilities.length()).any { capabilities.getString(it) == required }
        }) { "This signed artifact does not declare the requested capability" }
        Verified(FactoryDocumentCoordinator.Proof(appId, certificate, apk, version, selected.digest(), selected.scope.resources()), true)
    }

    private fun validateHistory(appId: String, entries: List<Artifact>?, certificate: String, floor: Int) {
        if (entries.orEmpty().any { it.certificate != certificate || it.version > floor }) {
            FactoryArtifactAuthority.invalidate(appId)
            error("Artifact history conflicts with the current signing identity or version history")
        }
    }
    private fun read(appId: String): List<Artifact>? = try {
        val target = file(appId)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists() && !File(target.baseFile.path + ".new").exists()) {
            FactoryArtifactAuthority.historyMissing(appId)
            null
        } else {
            val bytes = target.openRead().use { bounded(it, MAX_HISTORY_BYTES) }
            decode(appId, bytes).also { FactoryArtifactAuthority.retainHistory(appId, it.map { artifact -> artifact.digest() }.toSet()) }
        }
    } catch (failure: Exception) { FactoryArtifactAuthority.invalidate(appId); throw failure }
    private fun decode(appId: String, bytes: ByteArray): List<Artifact> {
        val saved = FactoryJson.objectFrom(bytes, MAX_HISTORY_BYTES)
        require(saved.keys().asSequence().toSet() == setOf("schemaVersion", "appId", "artifacts"))
        require(saved.get("schemaVersion") == 1 && saved.get("appId") == appId)
        val records = saved.getJSONArray("artifacts")
        require(records.length() in 0..MAX_ARTIFACTS)
        val artifacts = (0 until records.length()).map { index ->
            val entry = records.getJSONObject(index)
            require(entry.keys().asSequence().toSet() == setOf("record", "sha256"))
            require(entry.get("record") is String && entry.get("sha256") is String)
            // Each scope gets its own grammar/token budget; a large valid history cannot exceed it accidentally.
            val encoded = entry.getString("record").toByteArray(Charsets.UTF_8)
            require(ProjectScope.sha256(encoded) == entry.getString("sha256")) { "Signed artifact receipt integrity failed" }
            val record = FactoryJson.objectFrom(encoded, MAX_RECORD_BYTES)
            require(record.get("appId") == appId && record.get("certificateSha256") is String && record.get("apkSha256") is String)
            val certificate = record.getString("certificateSha256"); val apk = record.getString("apkSha256")
            val version = record.get("versionCode")
            require(certificate.matches(HASH) && apk.matches(HASH) && version is Int && version > 0)
            val scope = FactorySigningScope.readBaseline(JSONObject().put("lastApkSha256", apk).put("lastSignedScope", record),
                appId, certificate, version) ?: error("Invalid signed artifact scope binding")
            Artifact(appId, certificate, version, apk, scope).also { require(it.digest() == entry.getString("sha256")) }
        }
        require(artifacts.map { it.version }.toSet().size == artifacts.size && artifacts.map { it.apk }.toSet().size == artifacts.size)
        require(artifacts.map { it.certificate }.toSet().size <= 1)
        return artifacts
    }
    private fun write(appId: String, artifacts: List<Artifact>) {
        FactoryArtifactAuthority.retainHistory(appId, artifacts.map { it.digest() }.toSet())
        try {
            val bytes = JSONObject().put("schemaVersion", 1).put("appId", appId).put("artifacts", JSONArray(artifacts.map {
                JSONObject().put("record", it.encoded()).put("sha256", it.digest())
            })).toString().toByteArray(Charsets.UTF_8)
            check(decode(appId, bytes) == artifacts) { "Artifact history could not be verified before commit" }
            val target = file(appId)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare signed artifact history" }
            val stream = target.startWrite()
            try { stream.write(bytes); stream.flush(); stream.fd.sync() }
            catch (failure: Throwable) { target.failWrite(stream); throw failure }
            // A failed/ambiguous finish is not rolled back: publication must stop and retain recovery evidence.
            finishRecord(target, stream)
            check(target.openRead().use { bounded(it, MAX_HISTORY_BYTES) }.contentEquals(bytes)) { "Signed artifact history commit was not verified; do not publish" }
        } catch (failure: Throwable) { FactoryArtifactAuthority.invalidate(appId); throw failure }
    }
    protected open fun finishRecord(target: AtomicFile, stream: FileOutputStream) { target.finishWrite(stream) }

    companion object {
        internal const val RECENT_ARTIFACTS = 64
        internal const val MAX_ARTIFACTS = RECENT_ARTIFACTS + 1
        internal const val MAX_HISTORY_BYTES = 8 * 1024 * 1024
        private const val MAX_RECORD_BYTES = 64 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        internal fun authenticateIdentity(appId: String, certificate: String, state: FactorySigningIdentity.State) {
            FactoryIdentityId.validate(appId)
            FactoryArtifactAuthority.observeIdentity(appId, state)
            require(state.existing && state.fingerprint == certificate && certificate.matches(HASH)
                && state.lastVersion > 0 && state.recordSha256?.matches(HASH) == true) { "Signing identity is unavailable or inconsistent" }
        }
        private fun bounded(input: java.io.InputStream, limit: Int): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return result.toByteArray()
                require(count > 0 && count <= limit - result.size()) { "Signed artifact history exceeds its size limit" }
                result.write(buffer, 0, count)
            }
        }
        private fun installedVersion(context: Context, appId: String, certificate: String): Long? {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = try { pm.getPackageInfo(appId, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES) }
                catch (_: PackageManager.NameNotFoundException) { return null }
            @Suppress("DEPRECATION") val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            check(signatures?.size == 1) { "Cannot safely determine installed artifact retention" }
            if (ProjectScope.sha256(signatures!![0].toByteArray()) != certificate) return null
            @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            return version
        }
    }
}
