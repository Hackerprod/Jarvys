package com.jarvys.agent.coding

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactoryJson
import com.jarvys.agent.apkfactory.FactoryIcon
import com.jarvys.agent.apkfactory.FactoryApkSigner
import com.jarvys.agent.apkfactory.FactorySigningScope
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
            .put("manifestPermissions", JSONArray(manifest.permissions))
            .put("manifestFeatures", JSONArray(manifest.plan.features)).put("manifestQueries", JSONArray(manifest.plan.queries))
            .put("exportedComponents", JSONArray(manifest.plan.exportedComponents)).put("allowedHosts", JSONArray(manifest.plan.hosts))
            .put("resourceBindings", JSONObject().apply {
                manifest.resourceBindings.forEach { (role, binding) -> put(role, JSONObject()
                    .put("id", binding.id).put("type", binding.type).put("name", binding.name).put("path", binding.path)) }
            }).put("componentDex", JSONObject(manifest.componentDex))
            .put("dexSha256", JSONObject(manifest.dexSha256)).put("perAppGradleRequired", false).put("nativeToolchainRequired", false)
            .put("factoryMinimumApi", 26).put("factoryAvailable", Build.VERSION.SDK_INT >= 26).put("generatedAppMinimumApi", 24)
            .put("supportedAbis", "Architecture-neutral JVM/Android code; physical device validation is still required")
            .put("scope_version", scope.version()).put("project_id", scope.id())
            .put("specFields", JSONArray(listOf("schemaVersion", "appId", "name", "versionCode", "versionName", "capabilities", "webDir", "icon")))
            .put("limits", JSONObject().put("websiteFiles", FactorySpec.MAX_WEB_FILES).put("websiteBytes", FactorySpec.MAX_WEB_BYTES)
                .put("fileBytes", FactorySpec.MAX_FILE_BYTES).put("icon", "PNG, 48–1024 pixels per side, at most 1 MiB; or 192px geometric vector JSON"))
            .put("signing", "Explicit approval per sign. Existing non-exportable AndroidKeyStore identities stay unchanged. For a NEW recoverable identity or encrypted backup/import, open Settings > Factory identities yourself; never send passphrases or private keys to a tool or conversation. No silent rotation or shared key.")
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
            replacements["assets/factory-app.json"] = spec.runtimeConfig(
                if ("documents" in spec.capabilities || "browser" in spec.capabilities || "maps" in spec.capabilities || "phone" in spec.capabilities || "email" in spec.capabilities || "sms" in spec.capabilities) com.jarvys.factory.runtime.DocumentBrokerIdentity.buildBinding(context) else null
            )
            replacements["assets/factory-provenance.json"] = JSONObject().put("schemaVersion", 1).put("runtimeVersion", 1)
                .put("templateSha256", ProjectScope.sha256(template)).put("sourceSha256", JSONObject(sources))
                .put("renderedIconSha256", ProjectScope.sha256(icon)).toString().toByteArray(Charsets.UTF_8)
            val bytes = TemplateApk.build(template, TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities), icon, replacements)
            val manifest = TemplateApk.verify(bytes, TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities))
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
                .put("manifestContract", "closed-v1-current").put("renderedIconSha256", ProjectScope.sha256(icon)).put("dexSha256", JSONObject(manifest.dexSha256)).put("spec", spec.toJson()).put("sources", JSONObject(sources)).put("signed", false)
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
            val expectedManifest = TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities)
            val manifest = if (receipt.has("manifestContract")) {
                check(receipt.getString("manifestContract") == "closed-v1-current") { "Unsupported factory receipt manifest contract" }
                TemplateApk.verify(bytes, expectedManifest)
            } else TemplateApk.verifyExistingV1(bytes, expectedManifest)
            // v1 receipts predate inventory storage; their exact artifact hash/project binding remains authoritative.
            if (receipt.has("dexSha256")) {
                val saved = receipt.getJSONObject("dexSha256")
                check(saved.keys().asSequence().toSet() == manifest.dexSha256.keys &&
                    manifest.dexSha256.all { (name, sha) -> saved.getString(name) == sha }) { "DEX inventory differs from build receipt" }
            }
            val approvedState = synchronized(SIGN_LOCK) { identities.state(spec.appId) }
            check(approvedState.continuityKnown) { "Restored identity history is unresolved. Open Settings > Factory identities and explicitly reconcile the latest version floor before signing." }
            check(spec.versionCode > approvedState.lastVersion) { "Use a higher versionCode than the last signed release (${approvedState.lastVersion})" }
            val signingScope = FactorySigningScope.fromPlan(manifest.plan)
            val lines = mutableListOf("App: ${spec.name} (${spec.appId}), version ${spec.versionName} / ${spec.versionCode}",
                "Unsigned SHA-256: $expectedSha", "Output in this Coding project: $outputPath", "Declared capabilities: ${spec.capabilities.joinToString().ifEmpty { "none" }}",
                "Decoded Android permissions: ${manifest.permissions.joinToString().ifEmpty { "none" }}",
                "Decoded exported components: ${manifest.plan.exportedComponents.joinToString()}",
                "Decoded features/queries/allowed hosts: ${manifest.plan.features.joinToString().ifEmpty { "none" }} / ${manifest.plan.queries.joinToString().ifEmpty { "none" }} / ${manifest.plan.hosts.joinToString().ifEmpty { "none" }}",
                "Runtime DEX inventory: ${manifest.dexSha256.size} exact files, verified against the build.")
            lines += "Verified resource bindings: " + manifest.resourceBindings.entries.joinToString { (role, binding) ->
                "$role=${binding.type}/${binding.name} (${binding.id}) -> ${binding.path}"
            }
            lines += "Verified component DEX bindings: " + manifest.componentDex.entries.joinToString { (name, dex) -> "$name -> $dex" }
            if (!approvedState.existing || approvedState.lastVersion == 0)
                lines += "First signing for this app: no previously signed APK scope to compare. Review the full effective scope above."
            else {
                lines += "Compare with last signed version ${approvedState.lastVersion}, APK SHA-256: ${approvedState.lastApkSha256 ?: "unavailable"}"
                lines += signingScope.disclosure(approvedState.lastScope)
            }
            if (manifest.plan.profile != com.jarvys.factory.contract.ManifestPlan.Profile.CURRENT)
                lines += "Previously built v1 runtime: this artifact keeps its older window behavior. Rebuild from the project to receive the current runtime."
            if (approvedState.mode == FactorySigningIdentity.RECOVERABLE) {
                lines += "Reuse this app's recoverable signing identity: ${approvedState.fingerprint}. Its local copy is protected by AndroidKeyStore; recovery requires your encrypted backup and passphrase."
                lines += "Backup export is not proof of restoration. A backup knows only its recorded releases. Version history resolution: ${approvedState.continuityResolution}; floor ${approvedState.lastVersion}. A user-declared floor is not independent proof of the latest release."
            } else {
                if (approvedState.existing) lines += "Reuse this app's existing non-exportable signing identity: ${approvedState.fingerprint}"
                else lines += "Create a new, persistent, non-exportable signing key for this app only in this device's AndroidKeyStore. To choose a recoverable identity instead, cancel and create it yourself in Settings > Factory identities first."
                lines += "Clearing or uninstalling Jarvys, losing this device, or losing its Keystore key can permanently prevent updates to apps signed here. These non-exportable keys cannot be backed up or converted. No replacement key will be generated silently."
            }
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
                try {
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
                        val signedInfo = TemplateApk.verifyAgainstPlan(outputBytes, manifest.plan)
                        TemplateApk.verifyUnchangedPayload(bytes, outputBytes)
                        check(signedInfo.dexSha256 == manifest.dexSha256) { "Signed APK DEX inventory changed" }
                        val signedSha = ProjectScope.sha256(outputBytes)
                        val signed = JSONObject(receipt.toString()).put("buildId", id).put("state", "signed_staged")
                            .put("outputPath", outputPath).put("apkSha256", signedSha).put("apkBytes", outputBytes.size)
                            .put("unsignedSha256", expectedSha).put("certificateSha256", certificate).put("signed", true)
                        saveReceipt(signedSha, signed)
                        preserveStaging = true
                        // Reserve this version before publication. An interrupted publish must not silently sign another build.
                        checkActive(token)
                        lease.validate()
                        identities.recordSigned(spec.appId, certificate, spec.versionCode, signedSha, signingScope)
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
                } finally {
                    if (approvedState.mode == FactorySigningIdentity.RECOVERABLE) com.jarvys.agent.apkfactory.FactoryIdentityBackup.destroyBestEffort(identity.key)
                }
            }
        }
    }
    /** Installation is a distinct user-only native approval, never inherited from signing. */
    fun installation(action: String, inputPath: String, expectedSha: String, expectedVersion: Long, token: CancellationToken): JSONObject {
        require(action in setOf("install", "install_status", "install_cancel"))
        require(expectedSha.matches(Regex("[a-f0-9]{64}")))
        FactorySpec.relativePath(inputPath)
        val projectIdentity = scope.durableIdentity()
        fun validate() {
            checkActive(token)
            scope.require(ProjectScope.Capability.READ)
            check(scope.durableIdentity() == projectIdentity) { "Project identity changed" }
            check(scope.version() == expectedVersion) { "Project scope changed; review the artifact again" }
        }
        validate()
        val receipt = loadReceipt(expectedSha, inputPath)
        check(receipt.getString("state") == "published" && receipt.getBoolean("signed") &&
            receipt.getString("projectIdentity") == projectIdentity && receipt.getString("projectId") == scope.id() && receipt.getString("outputPath") == inputPath &&
            receipt.getString("apkSha256") == expectedSha) { "This exact signed APK has no completed receipt in this project" }
        val receiptDigest = ProjectScope.sha256(receipt.toString().toByteArray(Charsets.UTF_8))
        val spec = FactorySpec.parse(receipt.getJSONObject("spec"))
        val binding = JSONObject().put("project_id", scope.id())
            .put("project_identity_sha256", ProjectScope.sha256(scope.durableIdentity().toByteArray(Charsets.UTF_8)))
            .put("scope_version", expectedVersion).put("build_id", receipt.getString("buildId"))
            .put("receipt_sha256", receiptDigest).put("input_path", inputPath)
            .put("apk_sha256", expectedSha).put("certificate_sha256", receipt.getString("certificateSha256"))
            .put("app_id", spec.appId).put("app_name", spec.name).put("version_code", spec.versionCode).put("version_name", spec.versionName)
            .put("template_sha256", receipt.getString("templateSha256"))
        val coordinator = com.jarvys.agent.apkfactory.FactoryInstallCoordinator.get(context)
        // Status/cancellation bind durable receipt without depending on the APK still being readable.
        if (action == "install_status") return coordinator.status(binding)
        if (action == "install_cancel") return coordinator.cancel(binding)
        if (com.jarvys.agent.BuildConfig.FLAVOR != "full") return binding.put("state", "unavailable_in_play")
            .put("notice", "Integrated installation is not offered by Play. The signed artifact remains in this project; no permission, installer or bypass was invoked.")
        val bytes = FactoryProjectFiles.read(scope, inputPath, MAX_APK_BYTES, token)
        check(bytes.size.toLong() == receipt.getLong("apkBytes") && ProjectScope.sha256(bytes) == expectedSha) { "Signed APK changed" }
        val plan = TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities)
        val manifest = if (receipt.has("manifestContract")) {
            check(receipt.getString("manifestContract") == "closed-v1-current")
            TemplateApk.verify(bytes, plan)
        } else TemplateApk.verifyExistingV1(bytes, plan)
        if (receipt.has("dexSha256")) {
            val inventory = receipt.getJSONObject("dexSha256")
            check(inventory.keys().asSequence().toSet() == manifest.dexSha256.keys && manifest.dexSha256.all { (name, hash) -> inventory.getString(name) == hash })
        }
        val temp = File.createTempFile("factory-install-verify-", ".apk", context.cacheDir)
        try { temp.writeBytes(bytes); check(FactoryApkSigner.verify(temp) == binding.getString("certificate_sha256")) }
        finally { temp.delete() }
        val result = coordinator.prepare(binding, bytes, {
            validate()
            check(ProjectScope.sha256(loadReceipt(expectedSha, inputPath).toString().toByteArray(Charsets.UTF_8)) == receiptDigest) { "Signed receipt changed" }
            check(FactoryProjectFiles.sha(scope, inputPath, MAX_APK_BYTES, token) == expectedSha) { "Signed artifact changed after request" }
        }, token, ::validate)
        val launch = result.getString("launch_token")
        result.remove("launch_token")
        try {
            context.startActivity(android.content.Intent(context, com.jarvys.agent.apkfactory.FactoryInstallActivity::class.java)
                .putExtra("launch_token", launch).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: Exception) { coordinator.revokeBeforeCommit(); throw failure }
        return result.put("notice", "Native installation review requested. A human must approve this exact APK, then separately approve Android's installer. Nothing is installed by opening this screen. Settings > Factory installations retains recovery/status.")
    }
    /** Read-only evidence actions: no signing identities, permission changes, install or project mutation. */
    fun harness(action: String, inputPath: String, expectedSha: String, expectedVersion: Long, token: CancellationToken): JSONObject {
        require(action in setOf("preview", "preview_status", "test"))
        require(expectedSha.matches(Regex("[a-f0-9]{64}"))) { "expected_sha256 is required" }
        FactorySpec.relativePath(inputPath)
        val identity = scope.durableIdentity()
        fun validate() {
            checkActive(token)
            scope.require(ProjectScope.Capability.READ)
            check(scope.version() == expectedVersion && scope.durableIdentity() == identity) { "Factory project scope changed" }
        }
        validate()
        val receipt = loadReceipt(expectedSha, inputPath)
        check(receipt.getString("state") == "published" && receipt.getString("projectIdentity") == identity &&
            receipt.getString("projectId") == scope.id() && receipt.getString("outputPath") == inputPath &&
            receipt.getString("apkSha256") == expectedSha) { "This exact APK has no completed build receipt in this project" }
        val bytes = FactoryProjectFiles.read(scope, inputPath, MAX_APK_BYTES, token)
        check(ProjectScope.sha256(bytes) == expectedSha && bytes.size.toLong() == receipt.getLong("apkBytes")) { "Factory APK changed" }
        val spec = FactorySpec.parse(receipt.getJSONObject("spec"))
        check(receipt.optString("manifestContract") == "closed-v1-current") { "Rebuild this app with the current factory before preview or test" }
        check(receipt.getString("templateSha256") == ProjectScope.sha256(template())) { "Runtime template changed; rebuild this app before preview or test" }
        val manifest = TemplateApk.verify(bytes, TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities))
        val savedDex = receipt.getJSONObject("dexSha256")
        check(savedDex.keys().asSequence().toSet() == manifest.dexSha256.keys && manifest.dexSha256.all { (name, hash) -> savedDex.getString(name) == hash }) { "Runtime inventory differs from receipt" }
        val assets = sortedMapOf<String, ByteArray>()
        var config: ByteArray? = null
        var total = 0L
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                token.throwIfCancelled()
                val entry = zip.nextEntry ?: break
                if (entry.name == "assets/factory-app.json") config = readBounded(zip, FactorySpec.MAX_SPEC_BYTES)
                else if (entry.name.startsWith("assets/www/") || entry.name == "assets/factory-sdk.js") {
                    check(assets.size < FactorySpec.MAX_WEB_FILES + 1) { "Preview asset count exceeds build limit" }
                    val data = readBounded(zip, FactorySpec.MAX_FILE_BYTES)
                    total += data.size
                    check(total <= FactorySpec.MAX_WEB_BYTES.toLong() + FactorySpec.MAX_FILE_BYTES) { "Preview assets exceed build limit" }
                    check(assets.put(entry.name.removePrefix("assets/"), data) == null) { "Duplicate preview asset" }
                }
                zip.closeEntry()
            }
        }
        val runtimeConfig = config ?: error("Missing factory configuration")
        val parsedConfig = com.jarvys.factory.runtime.FactoryConfig.parsePreview(String(runtimeConfig, Charsets.UTF_8))
        check(parsedConfig.appId == spec.appId && parsedConfig.name == spec.name && parsedConfig.entryPoint == "www/index.html" &&
            parsedConfig.capabilities == spec.capabilities.toSet()) { "Runtime configuration differs from build receipt" }
        val sources = receipt.getJSONObject("sources")
        // Sorted length-delimited fields avoid JSONObject's unspecified serialization order.
        val canonicalSources = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).use { data ->
                sources.keys().asSequence().sorted().forEach { path ->
                    val name = path.toByteArray(Charsets.UTF_8)
                    val hash = sources.getString(path)
                    check(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid source receipt hash" }
                    data.writeInt(name.size); data.write(name); data.write(hash.toByteArray(Charsets.US_ASCII))
                }
            }
        }.toByteArray()
        assets.filterKeys { it.startsWith("www/") }.forEach { (path, data) ->
            check(sources.getString(spec.webDir + "/" + path.removePrefix("www/")) == ProjectScope.sha256(data)) { "Preview asset differs from build receipt" }
        }
        val metadata = JSONObject().put("schema_version", 1).put("project_id", scope.id()).put("scope_version", expectedVersion)
            .put("project_sha256", ProjectScope.sha256(canonicalSources))
            .put("project_identity_sha256", ProjectScope.sha256(identity.toByteArray(Charsets.UTF_8))).put("build_id", receipt.getString("buildId"))
            .put("template_sha256", receipt.getString("templateSha256")).put("apk_sha256", expectedSha)
            .put("app_id", spec.appId).put("version_code", spec.versionCode).put("version_name", spec.versionName)
            .put("android_api", Build.VERSION.SDK_INT).put("webview_version", JSONObject.NULL)
        validate()
        if (action == "preview_status") return FactoryPreviewRegistry.status(metadata)
        val snapshot = FactoryPreviewRegistry.Snapshot(metadata, runtimeConfig, assets)
        if (action == "test") {
            val result = FactoryRuntimeContractTests.run(snapshot, context.packageName, Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion, ::validate)
            validate()
            return result
        }
        val key = FactoryPreviewRegistry.issue(snapshot, ::validate, token)
        try {
            check(token.runIfActive {
                validate()
                context.startActivity(android.content.Intent().setClassName(context, "com.jarvys.agent.apkfactory.FactoryPreviewActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("factory_preview_token", key))
            }) { "Preview cancelled before launch" }
        } catch (failure: Exception) {
            FactoryPreviewRegistry.consume(key)?.revoke()
            throw failure
        }
        return metadata.put("mode", "functional_preview").put("state", "launch_requested")
            .put("notice", "One isolated preview with RAM app storage and a separate disposable browser profile replaces the previous preview. Native effects are simulated. Launch request is not proof of WebView rendering or installed-app behavior; use preview_status for bounded observed events.")
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
        private val SIGN_LOCK = FactorySigningIdentity.LOCK
        const val MAX_APK_BYTES = 32 * 1024 * 1024
        private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size().toLong() + n <= max) { "Template exceeds factory limit" }; out.write(buffer, 0, n) }
            return out.toByteArray()
        }
    }
}
