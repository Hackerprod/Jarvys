package com.jarvys.agent.coding

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactoryJson
import com.jarvys.agent.apkfactory.FactoryIcon
import com.jarvys.agent.apkfactory.FactoryApkSigner
import com.jarvys.agent.apkfactory.FactorySigningIdentity
import com.jarvys.agent.apkfactory.FactorySpec
import com.jarvys.agent.apkfactory.TemplateApk
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Native, offline pipeline: immutable bundled template -> project source snapshot -> verified artifact. */
internal class FactoryProjectService(
    private val context: Context,
    private val scope: ProjectScope,
    private val owner: String,
    private val gate: ApprovalGate,
    private val available: () -> Boolean,
    private val identities: FactorySigningIdentity = FactorySigningIdentity(context),
) {
    private val privateRoot = File(context.noBackupFilesDir, "apk-factory")
    private fun checkActive(token: CancellationToken) {
        token.throwIfCancelled()
        check(available()) { "APK factory is no longer available to this Coding run" }
        scope.validate()
    }
    private fun template(): ByteArray {
        val expected = context.assets.open("apk_factory/template.sha256").bufferedReader().use { it.readText().trim() }
        check(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundled template provenance" }
        val bytes = context.assets.open("apk_factory/template.apk").use { readBounded(it, MAX_APK_BYTES) }
        check(ProjectScope.sha256(bytes) == expected) { "Bundled runtime template hash mismatch" }
        return bytes
    }
    fun inspect(token: CancellationToken): JSONObject {
        checkActive(token)
        val bytes = template()
        val manifest = TemplateApk.inspect(bytes)
        return JSONObject().put("schemaVersion", 1).put("runtimeVersion", 1)
            .put("templateSha256", ProjectScope.sha256(bytes)).put("templateBytes", bytes.size)
            .put("templatePackage", manifest.appId).put("implementedCapabilities", JSONArray(FactorySpec.CAPABILITIES))
            .put("manifestPermissions", JSONArray()).put("perAppGradleRequired", false).put("nativeToolchainRequired", false)
            .put("factoryMinimumApi", 26).put("factoryAvailable", Build.VERSION.SDK_INT >= 26).put("generatedAppMinimumApi", 24)
            .put("supportedAbis", "Architecture-neutral JVM/Android code; physical device validation is still required")
            .put("scope_version", scope.version()).put("project_id", scope.id())
            .put("specFields", JSONArray(listOf("schemaVersion", "appId", "name", "versionCode", "versionName", "capabilities", "webDir", "icon")))
            .put("limits", JSONObject().put("websiteFiles", FactorySpec.MAX_WEB_FILES).put("websiteBytes", FactorySpec.MAX_WEB_BYTES)
                .put("fileBytes", FactorySpec.MAX_FILE_BYTES).put("icon", "PNG, 48–1024 pixels per side, at most 1 MiB; or 192px geometric vector JSON"))
            .put("signing", "Explicit approval per sign. Unique non-exportable AndroidKeyStore key per app. Clearing/uninstalling Jarvys or losing this device may permanently prevent updates; no key export, silent rotation or shared debug key.")
            .put("unsupported", "Native APIs outside the listed template capabilities require a reviewed template update; no network, camera, microphone, arbitrary shell or automatic installation.")
    }
    fun build(specPath: String, outputPath: String, expectedVersion: Long, token: CancellationToken): JSONObject {
        checkActive(token)
        if (Build.VERSION.SDK_INT < 26) throw IllegalStateException("Native APK generation requires Android 8/API 26 or newer for bounded project file access. Generated apps support API 24+.")
        FactorySpec.relativePath(specPath)
        scope.require(ProjectScope.Capability.WRITE)
        scope.acquireWriter(owner, expectedVersion).use { lease ->
            FactoryProjectFiles.output(scope, outputPath)
            val source = FactoryProjectFiles.read(scope, specPath, FactorySpec.MAX_SPEC_BYTES, token)
            val spec = FactorySpec.parse(FactoryJson.objectFrom(source, FactorySpec.MAX_SPEC_BYTES))
            require(!outputPath.startsWith(spec.webDir + "/")) { "Output cannot be inside webDir" }
            val iconSource = FactoryProjectFiles.read(scope, spec.icon, FactorySpec.MAX_FILE_BYTES, token)
            val icon = FactoryIcon.render(spec.icon, iconSource)
            val web = FactoryProjectFiles.website(scope, spec.webDir, token)
            val sources = sortedMapOf(specPath to ProjectScope.sha256(source), spec.icon to ProjectScope.sha256(iconSource))
            web.forEach { (path, bytes) -> sources["${spec.webDir}/$path"] = ProjectScope.sha256(bytes) }
            val template = template()
            val replacements = sortedMapOf<String, ByteArray>()
            web.forEach { (path, bytes) -> replacements["assets/www/$path"] = bytes }
            replacements["assets/factory-app.json"] = spec.runtimeConfig()
            replacements["assets/factory-provenance.json"] = JSONObject().put("schemaVersion", 1).put("runtimeVersion", 1)
                .put("templateSha256", ProjectScope.sha256(template)).put("sourceSha256", JSONObject(sources))
                .put("renderedIconSha256", ProjectScope.sha256(icon)).toString().toByteArray(Charsets.UTF_8)
            val bytes = TemplateApk.build(template, TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName), icon, replacements)
            require(bytes.size <= MAX_APK_BYTES) { "Generated APK exceeds factory size limit" }
            checkActive(token)
            sources.forEach { (path, sha) -> check(FactoryProjectFiles.sha(scope, path, if (path == specPath) FactorySpec.MAX_SPEC_BYTES else FactorySpec.MAX_FILE_BYTES, token) == sha) { "Factory source changed before publication: $path" } }
            val id = UUID.randomUUID().toString()
            val job = newJob(id)
            val staged = File(job, "unsigned.apk").apply { writeBytes(bytes) }
            val sha = ProjectScope.sha256(bytes)
            val receipt = JSONObject().put("schemaVersion", 1).put("buildId", id).put("state", "staged")
                .put("projectIdentity", scope.durableIdentity()).put("projectId", scope.id()).put("outputPath", outputPath)
                .put("apkSha256", sha).put("apkBytes", bytes.size).put("templateSha256", ProjectScope.sha256(template))
                .put("renderedIconSha256", ProjectScope.sha256(icon)).put("spec", spec.toJson()).put("sources", JSONObject(sources)).put("signed", false)
            saveReceipt(sha, receipt)
            try {
                FactoryProjectFiles.publish(scope, outputPath, staged, lease, token) { checkActive(token) }
                receipt.put("state", "published")
                saveReceipt(sha, receipt)
                job.deleteRecursively()
            } catch (failure: Exception) {
                // A partial new output can exist. Keep staged bytes and status; never replay automatically.
                receipt.put("state", "publication_unconfirmed")
                saveReceipt(sha, receipt)
                throw IOException("APK publication was not confirmed. Inspect $outputPath before retrying; staging and recovery evidence were preserved.", failure)
            }
            return publicReceipt(receipt).put("scope_version", scope.version()).put("notice", "Unsigned APK built and verified. It is not installable until explicitly signed; no installation was attempted.")
        }
    }
    fun sign(inputPath: String, expectedSha: String, outputPath: String, expectedVersion: Long, token: CancellationToken): JSONObject {
        checkActive(token)
        if (Build.VERSION.SDK_INT < 26) throw IllegalStateException("Native factory signing requires Android 8/API 26 or newer")
        require(expectedSha.matches(Regex("[a-f0-9]{64}"))) { "expected_sha256 is required" }
        FactorySpec.relativePath(inputPath)
        scope.acquireWriter(owner, expectedVersion).use { lease ->
            FactoryProjectFiles.output(scope, outputPath)
            val receipt = loadReceipt(expectedSha, inputPath)
            check(receipt.getString("state") == "published" && !receipt.getBoolean("signed") && receipt.getString("projectIdentity") == scope.durableIdentity()
                && receipt.getString("outputPath") == inputPath && receipt.getString("apkSha256") == expectedSha) { "This exact unsigned APK has no completed build receipt in this project" }
            val bytes = FactoryProjectFiles.read(scope, inputPath, MAX_APK_BYTES, token)
            check(ProjectScope.sha256(bytes) == expectedSha) { "Unsigned APK changed; build it again from inspected source" }
            val spec = FactorySpec.parse(receipt.getJSONObject("spec"))
            val manifest = TemplateApk.inspect(bytes)
            check(manifest.appId == spec.appId && manifest.versionCode == spec.versionCode && manifest.label == spec.name && manifest.permissions.isEmpty()) {
                "APK does not match its validated factory build"
            }
            val approvedState = synchronized(SIGN_LOCK) { identities.state(spec.appId) }
            check(spec.versionCode > approvedState.lastVersion) { "Use a higher versionCode than the last signed release (${approvedState.lastVersion})" }
            val lines = mutableListOf("App: ${spec.name} (${spec.appId}), version ${spec.versionName} / ${spec.versionCode}",
                "Unsigned SHA-256: $expectedSha", "Output in this Coding project: $outputPath", "Declared capabilities: ${spec.capabilities.joinToString().ifEmpty { "none" }}; no Android manifest permissions.")
            if (approvedState.existing) lines += "Reuse this app's existing signing identity: ${approvedState.fingerprint}"
            else lines += "Create a new, persistent, non-exportable signing key for this app only in this device's AndroidKeyStore."
            lines += "Clearing or uninstalling Jarvys, losing this device, or losing its Keystore key can permanently prevent updates to apps signed here. Keys cannot currently be backed up or transferred. No replacement key will be generated silently."
            lines += "This signs a local APK only. It does not install, publish, upload or grant permissions to an app."
            checkActive(token)
            val decision = gate.request(ApprovalSummary("Sign generated APK", lines, allowAlwaysAvailable = false, requester = "Coding"), token)
            check(decision == ApprovalDecision.APPROVED) { "Signing was not approved. Nothing was signed; do not retry through another tool." }
            checkActive(token)
            check(FactoryProjectFiles.sha(scope, inputPath, MAX_APK_BYTES, token) == expectedSha) { "Unsigned APK changed during approval" }
            FactoryProjectFiles.output(scope, outputPath)
            synchronized(SIGN_LOCK) {
                checkActive(token)
                lease.validate()
                val identity = token.callIfActive({
                    checkActive(token)
                    lease.validate()
                    identities.obtainAfterApproval(spec.appId, approvedState)
                }, null) ?: throw java.util.concurrent.CancellationException("Signing cancelled before identity creation")
                checkActive(token)
                val id = UUID.randomUUID().toString()
                val job = newJob(id)
                val input = File(job, "unsigned.apk").apply { writeBytes(bytes) }
                val output = File(job, "signed.apk")
                var preserveStaging = false
                try {
                    val certificate = FactoryApkSigner.sign(input, output, identity.key, identity.certificate)
                    checkActive(token)
                    val outputBytes = output.readBytes()
                    val signedInfo = TemplateApk.inspect(outputBytes)
                    check(signedInfo.appId == spec.appId && signedInfo.versionCode == spec.versionCode) { "Signed APK identity changed" }
                    val signedSha = ProjectScope.sha256(outputBytes)
                    val signed = JSONObject(receipt.toString()).put("buildId", id).put("state", "signed_staged")
                        .put("outputPath", outputPath).put("apkSha256", signedSha).put("apkBytes", outputBytes.size)
                        .put("unsignedSha256", expectedSha).put("certificateSha256", certificate).put("signed", true)
                    saveReceipt(signedSha, signed)
                    preserveStaging = true
                    // Reserve this version before publication. An interrupted publish must not silently sign another build.
                    checkActive(token)
                    lease.validate()
                    identities.recordSigned(spec.appId, certificate, spec.versionCode, signedSha)
                    try {
                        FactoryProjectFiles.publish(scope, outputPath, output, lease, token) { checkActive(token) }
                        signed.put("state", "published")
                        saveReceipt(signedSha, signed)
                    } catch (failure: Exception) {
                        signed.put("state", "publication_unconfirmed"); saveReceipt(signedSha, signed)
                        throw IOException("Signature verified but output publication was not confirmed. Preserve and inspect $outputPath; do not sign the same version again.", failure)
                    }
                    job.deleteRecursively()
                    return publicReceipt(signed).put("scope_version", scope.version()).put("notice", "Signed APK verified with APK Signature Scheme v2. No installation or device compatibility test was performed.")
                } catch (failure: Exception) {
                    // Only ambiguous publication needs recovery bytes; failed signing attempts do not accumulate files.
                    if (!preserveStaging) job.deleteRecursively()
                    throw failure
                }
            }
        }
    }
    private fun publicReceipt(receipt: JSONObject): JSONObject = JSONObject().put("build_id", receipt.getString("buildId"))
        .put("output_path", receipt.getString("outputPath")).put("sha256", receipt.getString("apkSha256"))
        .put("bytes", receipt.getLong("apkBytes")).put("signed", receipt.getBoolean("signed"))
        .put("app_id", receipt.getJSONObject("spec").getString("appId"))
        .put("version_code", receipt.getJSONObject("spec").getInt("versionCode"))
        .put("template_sha256", receipt.getString("templateSha256"))
        .put("certificate_sha256", receipt.opt("certificateSha256") ?: JSONObject.NULL)
        .put("source_file_count", receipt.getJSONObject("sources").length())
        .put("source_manifest_sha256", ProjectScope.sha256(receipt.getJSONObject("sources").toString().toByteArray(Charsets.UTF_8)))
    private fun newJob(id: String): File {
        val directory = File(privateRoot, "staging/$id")
        check(directory.mkdirs()) { "Cannot create private factory staging" }
        return directory
    }
    private fun receiptFile(sha: String, outputPath: String) = AtomicFile(File(privateRoot, "builds/${scope.id()}/$sha-${ProjectScope.sha256(outputPath.toByteArray(Charsets.UTF_8))}.json"))
    private fun saveReceipt(sha: String, data: JSONObject) {
        val target = receiptFile(sha, data.getString("outputPath"))
        check(target.baseFile.parentFile!!.isDirectory || target.baseFile.parentFile!!.mkdirs()) { "Cannot save factory receipt" }
        val out = target.startWrite()
        try { out.write(data.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(out) }
        catch (error: Throwable) { target.failWrite(out); throw error }
    }
    private fun loadReceipt(sha: String, outputPath: String): JSONObject = try { JSONObject(String(receiptFile(sha, outputPath).readFully(), Charsets.UTF_8)) }
        catch (failure: Exception) { throw IOException("No verified factory receipt for this APK", failure) }
    companion object {
        private val SIGN_LOCK = Any()
        const val MAX_APK_BYTES = 32 * 1024 * 1024
        private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size().toLong() + n <= max) { "Template exceeds factory limit" }; out.write(buffer, 0, n) }
            return out.toByteArray()
        }
    }
}
