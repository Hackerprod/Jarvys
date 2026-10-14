package com.jarvys.agent.coding

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactoryPackagingResources
import com.jarvys.agent.apkfactory.FactoryJson
import com.jarvys.agent.apkfactory.FactoryIcon
import com.jarvys.agent.apkfactory.FactoryApkSigner
import com.jarvys.agent.apkfactory.FactorySigningScope
import com.jarvys.agent.apkfactory.FactorySignedArtifactHistory
import com.jarvys.agent.apkfactory.FactorySigningIdentity
import com.jarvys.factory.runtime.FactoryUiAssets
import com.jarvys.agent.apkfactory.FactorySpec
import com.jarvys.agent.apkfactory.TemplateApk
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
    private val available: () -> Boolean,
    private val identities: FactorySigningIdentity = FactorySigningIdentity(context),
) {
    private val privateRoot = File(context.noBackupFilesDir, "apk-factory")
    private fun checkActive(token: CancellationToken) {
        token.throwIfCancelled()
        check(available()) { "APK factory is no longer available to this Coding run" }
        scope.validate()
    }
    private fun template(token: CancellationToken): ByteArray {
        val expected = context.assets.open("apk_factory/template.sha256").bufferedReader().use { it.readText().trim() }
        check(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundled template provenance" }
        // Count without materializing first; the trusted asset may be compressed in the host APK.
        val plannedBytes = context.assets.open("apk_factory/template.apk").use { input ->
            val buffer = ByteArray(8192)
            var count = 0L
            while (true) { token.throwIfCancelled(); val n = input.read(buffer); if (n < 0) break; count += n
                check(count <= MAX_APK_BYTES) { "Bundled template exceeds technical APK limit" } }
            count
        }
        FactoryPackagingResources.requireHeap(FactoryPackagingResources.readEstimate(plannedBytes))
        val bytes = context.assets.open("apk_factory/template.apk").use { readBounded(it, plannedBytes.toInt(), token) }
        FactoryPackagingResources.requireHeap(FactoryPackagingResources.artifactEstimate(bytes.size.toLong(), TemplateApk.expandedBytes(bytes)))
        check(ProjectScope.sha256(bytes) == expected) { "Bundled runtime template hash mismatch" }
        FactoryUiAssets.fromApk(bytes)
        return bytes
    }
    private fun localUiLibraries(): JSONObject = JSONObject()
        .put("library", FactoryUiAssets.LIBRARY).put("version", FactoryUiAssets.VERSION)
        .put("licenses", JSONArray(listOf("BSD-3-Clause", "MIT")))
        .put("inclusionPolicy", "fixed-runtime-assets; styles load only when linked")
        .put("bytes", FactoryUiAssets.TOTAL_BYTES).put("capabilityRequired", false)
        .put("files", JSONArray(FactoryUiAssets.paths().map { path -> JSONObject()
            .put("path", "/$path").put("bytes", FactoryUiAssets.size(path)).put("sha256", FactoryUiAssets.sha256(path)) }))

    fun inspect(token: CancellationToken): JSONObject =
        FactoryPackagingResources.acquire().use { inspectOwned(token) }

    private fun inspectOwned(token: CancellationToken): JSONObject {
        checkActive(token)
        val bytes = template(token)
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
            .put("localUiLibraries", localUiLibraries())
            .put("specFields", JSONArray(listOf("schemaVersion", "appId", "name", "versionCode", "versionName", "capabilities", "webDir", "icon", "dataBackup", "resourceBudgets", "incomingShare", "notifications", "bluetooth", "adaptiveIcon")))
            .put("limits", JSONObject().put("websiteFiles", FactorySpec.MAX_WEB_FILES).put("websiteBytes", FactorySpec.MAX_WEB_BYTES)
                .put("fileBytes", FactorySpec.MAX_FILE_BYTES)
                .put("sourceBudgetMaxima", JSONObject().put("sourceFiles", TemplateApk.MAX_ENTRIES)
                    .put("singleSourceBytes", TemplateApk.MAX_ENTRY_BYTES).put("totalSourceBytes", TemplateApk.MAX_APK_BYTES))
                .put("technicalZipEntries", TemplateApk.MAX_ENTRIES).put("technicalExpandedBytes", TemplateApk.MAX_APK_BYTES)
                .put("technicalApkBytes", TemplateApk.MAX_APK_BYTES)
                .put("resourceAdmission", "Defaults shown above; signed resourceBudgets.packaging can raise source limits within maxima. Complete APK includes fixed runtime/config/provenance and ZIP overhead. One process-local operation with conservative actual-size heap admission; availability is not guaranteed.").put("icon", "PNG, 48–1024 pixels per side, at most 1 MiB; or 192px geometric vector JSON")
                .put("adaptiveIcon", "Optional foreground PNG or transparent geometric JSON, opaque #RRGGBB background, insetPercent 0–40 (default 0 preserves canvas); compiled API26 adaptive resource plus legacy fallback"))
            .put("signing", "Signing uses a separate RSA key for each app, created locally in AndroidKeyStore on first signing. Android still controls any later installation confirmation and the source-specific install setting.")
            .put("unsupported", "Implemented operations depend on declared capabilities, applicable user grants, and device/OS availability. Native APIs outside the compiled capability catalog require a reviewed template update; per-app native code and compilation are unsupported.")
    }
    fun build(specPath: String, outputPath: String, expectedVersion: Long, token: CancellationToken): JSONObject =
        FactoryPackagingResources.acquire().use { buildOwned(specPath, outputPath, expectedVersion, token) }

    private fun buildOwned(specPath: String, outputPath: String, expectedVersion: Long, token: CancellationToken): JSONObject {
        checkActive(token)
        if (Build.VERSION.SDK_INT < 26) throw IllegalStateException("Native APK generation requires Android 8/API 26 or newer for bounded project file access. Generated apps support API 24+.")
        FactorySpec.relativePath(specPath)
        scope.require(ProjectScope.Capability.WRITE)
        scope.acquireWriter(owner, expectedVersion).use { lease ->
            FactoryProjectFiles.output(scope, outputPath)
            val source = FactoryProjectFiles.read(scope, specPath, FactorySpec.MAX_SPEC_BYTES, token)
            val spec = FactorySpec.parse(FactoryJson.objectFrom(source, FactorySpec.MAX_SPEC_BYTES))
            require(!outputPath.startsWith(spec.webDir + "/")) { "Output cannot be inside webDir" }
            val websitePlan = FactoryProjectFiles.planWebsite(scope, spec.webDir, spec.resources.packaging, token)
            val template = template(token)
            FactoryPackagingResources.requireHeap(FactoryPackagingResources.buildEstimate(websitePlan.totalBytes,
                template.size.toLong(), TemplateApk.expandedBytes(template)))
            val iconSource = FactoryProjectFiles.read(scope, spec.icon, FactorySpec.MAX_ICON_BYTES, token)
            val icon = FactoryIcon.render(spec.icon, iconSource)
            val adaptiveSource = spec.adaptiveIcon?.let { FactoryProjectFiles.read(scope, it.foreground, FactorySpec.MAX_ICON_BYTES, token) }
            val adaptiveLayers = spec.adaptiveIcon?.let { FactoryIcon.renderAdaptive(it, adaptiveSource!!) }
            val adaptiveHashes = adaptiveLayers?.let { JSONObject().put("foreground", ProjectScope.sha256(it.foreground()))
                .put("background", ProjectScope.sha256(it.background())) }
            val web = FactoryProjectFiles.readWebsite(scope, websitePlan, token)
            val sources = sortedMapOf(specPath to ProjectScope.sha256(source), spec.icon to ProjectScope.sha256(iconSource))
            spec.adaptiveIcon?.let { sources[it.foreground] = ProjectScope.sha256(adaptiveSource!!) }
            web.forEach { (path, bytes) -> sources["${spec.webDir}/$path"] = ProjectScope.sha256(bytes) }
            val replacements = sortedMapOf<String, ByteArray>()
            web.forEach { (path, bytes) -> replacements["assets/www/$path"] = bytes }
            replacements["assets/factory-app.json"] = spec.runtimeConfig(
                if (com.jarvys.factory.contract.CapabilityCatalog.requiresHostBroker(spec.capabilities)) com.jarvys.factory.runtime.DocumentBrokerIdentity.buildBinding(context) else null
            )
            replacements["assets/factory-provenance.json"] = JSONObject().put("schemaVersion", 1).put("runtimeVersion", 1)
                .put("templateSha256", ProjectScope.sha256(template)).put("sourceSha256", JSONObject(sources))
                .put("renderedIconSha256", ProjectScope.sha256(icon)).put("localUiLibraries", localUiLibraries())
                .also { if (adaptiveHashes != null) it.put("renderedAdaptiveIconSha256", adaptiveHashes) }.toString().toByteArray(Charsets.UTF_8)
            val bytes = TemplateApk.build(template, TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities, spec.incomingShare, spec.notifications, spec.bluetooth, spec.adaptiveIcon != null), icon, replacements, adaptiveLayers)
            val manifest = TemplateApk.verify(bytes, TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities, spec.incomingShare, spec.notifications, spec.bluetooth, spec.adaptiveIcon != null))
            require(bytes.size <= MAX_APK_BYTES && bytes.size.toLong() <= spec.resources.packaging.apkBytes) { "Generated APK exceeds factory size limit" }
            FactoryUiAssets.fromApk(bytes)
            checkActive(token)
            sources.forEach { (path, sha) -> check(FactoryProjectFiles.sha(scope, path, when (path) { specPath -> FactorySpec.MAX_SPEC_BYTES; spec.icon, spec.adaptiveIcon?.foreground -> FactorySpec.MAX_ICON_BYTES; else -> spec.resources.packaging.singleSourceBytes.toInt() }, token) == sha) { "Factory source changed before publication: $path" } }
            val id = UUID.randomUUID().toString()
            val job = newJob(id)
            val staged = File(job, "unsigned.apk").apply { writeBytes(bytes) }
            val sha = ProjectScope.sha256(bytes)
            val receipt = JSONObject().put("schemaVersion", 1).put("buildId", id).put("state", "staged")
                .put("projectIdentity", scope.durableIdentity()).put("projectId", scope.id()).put("outputPath", outputPath)
                .put("apkSha256", sha).put("apkBytes", bytes.size).put("templateSha256", ProjectScope.sha256(template))
                .put("manifestContract", "closed-v5-generic-native").put("renderedIconSha256", ProjectScope.sha256(icon)).put("dexSha256", JSONObject(manifest.dexSha256)).put("spec", spec.toJson()).put("sources", JSONObject(sources)).put("signed", false)
            if (adaptiveHashes != null) receipt.put("renderedAdaptiveIconSha256", adaptiveHashes)
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
    fun sign(inputPath: String, expectedSha: String, outputPath: String, expectedVersion: Long, token: CancellationToken): JSONObject =
        FactoryPackagingResources.acquire().use { signOwned(inputPath, expectedSha, outputPath, expectedVersion, token) }

    private fun signOwned(inputPath: String, expectedSha: String, outputPath: String, expectedVersion: Long, token: CancellationToken): JSONObject {
        checkActive(token)
        if (Build.VERSION.SDK_INT < 26) throw IllegalStateException("Native factory signing requires Android 8/API 26 or newer")
        require(expectedSha.matches(Regex("[a-f0-9]{64}"))) { "expected_sha256 is required" }
        FactorySpec.relativePath(inputPath)
        scope.acquireWriter(owner, expectedVersion).use { lease ->
            FactoryProjectFiles.output(scope, outputPath)
            val receipt = loadReceipt(expectedSha, inputPath)
            check(receipt.getString("state") == "published" && !receipt.getBoolean("signed") && receipt.getString("projectIdentity") == scope.durableIdentity()
                && receipt.getString("outputPath") == inputPath && receipt.getString("apkSha256") == expectedSha) { "This exact unsigned APK has no completed build receipt in this project" }
            val bytes = readArtifact(inputPath, receipt.getLong("apkBytes"), token)
            check(ProjectScope.sha256(bytes) == expectedSha) { "Unsigned APK changed; build it again from inspected source" }
            val spec = FactorySpec.parse(receipt.getJSONObject("spec"))
            val expectedManifest = TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities, spec.incomingShare, spec.notifications, spec.bluetooth, spec.adaptiveIcon != null)
            val manifest = verifyReceiptManifest(bytes, expectedManifest, receipt)
            verifyRuntimeConfig(bytes, spec)
            // v1 receipts predate inventory storage; their exact artifact hash/project binding remains authoritative.
            if (receipt.has("dexSha256")) {
                val saved = receipt.getJSONObject("dexSha256")
                check(saved.keys().asSequence().toSet() == manifest.dexSha256.keys &&
                    manifest.dexSha256.all { (name, sha) -> saved.getString(name) == sha }) { "DEX inventory differs from build receipt" }
            }
            val identityState = synchronized(SIGN_LOCK) { identities.state(spec.appId) }
            check(spec.versionCode > identityState.lastVersion) { "Use a higher versionCode than the last signed release (${identityState.lastVersion})" }
            val signingScope = FactorySigningScope.fromPlan(manifest.plan, spec.resources)
            checkActive(token)
            check(FactoryProjectFiles.sha(scope, inputPath, MAX_APK_BYTES, token) == expectedSha) { "Unsigned APK changed" }
            FactoryProjectFiles.output(scope, outputPath)
            synchronized(SIGN_LOCK) {
                checkActive(token)
                lease.validate()
                val identity = token.callIfActive({
                    checkActive(token)
                    lease.validate()
                    check(identities.state(spec.appId) == identityState) { "Signing identity changed before key creation" }
                    FactorySignedArtifactHistory(context).preserveLatest(spec.appId, identityState)
                    identities.obtain(spec.appId, identityState)
                }, null) ?: throw java.util.concurrent.CancellationException("Signing cancelled before identity creation")
                val id = UUID.randomUUID().toString()
                val job = newJob(id)
                val input = File(job, "unsigned.apk").apply { writeBytes(bytes) }
                val output = File(job, "signed.apk")
                var preserveStaging = false
                try {
                    val certificate = FactoryApkSigner.sign(input, output, identity.key, identity.certificate)
                    checkActive(token)
                    require(output.length() <= minOf(MAX_APK_BYTES.toLong(), spec.resources.packaging.apkBytes)) {
                        "Signed APK exceeds declared or technical APK byte limit"
                    }
                    val outputBytes = output.inputStream().use {
                        readBounded(it, minOf(MAX_APK_BYTES.toLong(), spec.resources.packaging.apkBytes).toInt())
                    }
                    val signedInfo = TemplateApk.verifyAgainstPlan(outputBytes, manifest.plan)
                    TemplateApk.verifyUnchangedPayload(bytes, outputBytes)
                    check(signedInfo.dexSha256 == manifest.dexSha256) { "Signed APK DEX inventory changed" }
                    val signedSha = ProjectScope.sha256(outputBytes)
                    val signed = JSONObject(receipt.toString()).put("buildId", id).put("state", "signed_staged")
                        .put("outputPath", outputPath).put("apkSha256", signedSha).put("apkBytes", outputBytes.size)
                        .put("unsignedSha256", expectedSha).put("certificateSha256", certificate).put("signed", true)
                    saveReceipt(signedSha, signed)
                    preserveStaging = true
                    checkActive(token)
                    lease.validate()
                    identities.recordSigned(spec.appId, certificate, spec.versionCode, signedSha, signingScope)
                    try { FactorySignedArtifactHistory(context).recordCurrent(spec.appId, identities.state(spec.appId)) }
                    catch (failure: Exception) {
                        throw IOException("Signature recorded but exact artifact history was not confirmed. Staging was preserved; do not sign this version again.", failure)
                    }
                    try {
                        FactoryProjectFiles.publish(scope, outputPath, output, lease, token) { checkActive(token) }
                        signed.put("state", "published")
                        saveReceipt(signedSha, signed)
                    } catch (failure: Exception) {
                        signed.put("state", "publication_unconfirmed")
                        saveReceipt(signedSha, signed)
                        throw IOException("Signature verified but output publication was not confirmed. Preserve and inspect $outputPath; do not sign the same version again.", failure)
                    }
                    job.deleteRecursively()
                    return publicReceipt(signed).put("scope_version", scope.version())
                        .put("notice", "Signed APK verified with APK Signature Scheme v2. No installation or device compatibility test was performed.")
                } catch (failure: Exception) {
                    if (!preserveStaging) job.deleteRecursively()
                    throw failure
                }
            }
        }
    }
    /** Installation delegates the final confirmation and result to Android PackageInstaller. */
    fun installation(action: String, inputPath: String, expectedSha: String, expectedVersion: Long, token: CancellationToken): JSONObject =
        if (action != "install") installationOwned(action, inputPath, expectedSha, expectedVersion, token) else
        FactoryPackagingResources.acquire().use { installationOwned(action, inputPath, expectedSha, expectedVersion, token) }

    private fun installationOwned(action: String, inputPath: String, expectedSha: String, expectedVersion: Long, token: CancellationToken): JSONObject {
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
            .put("notice", "Android installation is unavailable in this distribution. The signed artifact remains in this project.")
        val bytes = readArtifact(inputPath, receipt.getLong("apkBytes"), token)
        check(bytes.size.toLong() == receipt.getLong("apkBytes") && ProjectScope.sha256(bytes) == expectedSha) { "Signed APK changed" }
        val plan = TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities, spec.incomingShare, spec.notifications, spec.bluetooth, spec.adaptiveIcon != null)
        val manifest = verifyReceiptManifest(bytes, plan, receipt)
        verifyRuntimeConfig(bytes, spec)
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
        return result.put("notice", "Installation request sent to Android PackageInstaller. Android may require its own confirmation or the system setting that allows Jarvys to install apps. This request is not evidence of success; install_status reports the recorded result.")
    }
    /** Read-only evidence actions: no signing identities, permission changes, install or project mutation. */
    fun harness(action: String, inputPath: String, expectedSha: String, expectedVersion: Long, token: CancellationToken): JSONObject =
        FactoryPackagingResources.acquire().use { harnessOwned(action, inputPath, expectedSha, expectedVersion, token) }

    private fun harnessOwned(action: String, inputPath: String, expectedSha: String, expectedVersion: Long, token: CancellationToken): JSONObject {
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
        val bytes = readArtifact(inputPath, receipt.getLong("apkBytes"), token)
        check(ProjectScope.sha256(bytes) == expectedSha && bytes.size.toLong() == receipt.getLong("apkBytes")) { "Factory APK changed" }
        val spec = FactorySpec.parse(receipt.getJSONObject("spec"))
        check(receipt.getString("templateSha256") == ProjectScope.sha256(template(token))) { "Runtime template changed; rebuild this app before preview or test" }
        val manifest = verifyReceiptManifest(bytes, TemplateApk.Spec(spec.appId, spec.name, spec.versionCode, spec.versionName, spec.capabilities, spec.incomingShare, spec.notifications, spec.bluetooth, spec.adaptiveIcon != null), receipt)
        val savedDex = receipt.getJSONObject("dexSha256")
        check(savedDex.keys().asSequence().toSet() == manifest.dexSha256.keys && manifest.dexSha256.all { (name, hash) -> savedDex.getString(name) == hash }) { "Runtime inventory differs from receipt" }
        val assets = sortedMapOf<String, ByteArray>()
        var config: ByteArray? = null
        var webBytes = 0L
        var webFiles = 0
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                token.throwIfCancelled()
                val entry = zip.nextEntry ?: break
                if (entry.name == "assets/factory-app.json") config = readBounded(zip, FactorySpec.MAX_SPEC_BYTES)
                else if (entry.name.startsWith("assets/www/") || entry.name == "assets/factory-sdk.js" ||
                    (entry.name.startsWith("assets/") && FactoryUiAssets.isNamespace(entry.name.removePrefix("assets/")))) {
                    val path = entry.name.removePrefix("assets/")
                    check(!entry.isDirectory && !assets.containsKey(path)) { "Duplicate or directory preview asset" }
                    if (path.startsWith("www/")) {
                        check(++webFiles <= spec.resources.packaging.sourceFiles) { "Preview website exceeds declared sourceFiles" }
                    }
                    val data = if (FactoryUiAssets.isNamespace(path)) FactoryUiAssets.read(path, zip)
                        else readBounded(zip, if (path.startsWith("www/")) minOf(spec.resources.packaging.singleSourceBytes, spec.resources.packaging.totalSourceBytes - webBytes).toInt() else TemplateApk.MAX_ENTRY_BYTES)
                    if (path.startsWith("www/")) {
                        webBytes += data.size
                        check(webFiles <= spec.resources.packaging.sourceFiles && webBytes <= spec.resources.packaging.totalSourceBytes) {
                            "Preview website exceeds declared packaging budget"
                        }
                    }
                    assets[path] = data
                }
                zip.closeEntry()
            }
        }
        val runtimeConfig = config ?: error("Missing factory configuration")
        verifyRuntimeConfigBytes(runtimeConfig, spec)
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
        val snapshot = FactoryPreviewRegistry.Snapshot(metadata, runtimeConfig, assets, spec.resources.packaging)
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
    /** Exact historical profiles are selected only by this project's authenticated receipt. */
    private fun verifyReceiptManifest(bytes: ByteArray, spec: TemplateApk.Spec, receipt: JSONObject): TemplateApk.ManifestInfo =
        when (receipt.optString("manifestContract")) {
            "closed-v2-clipboard-broker" -> TemplateApk.verifyExistingClosedV2(bytes, spec)
            "closed-v3-native-operations" -> TemplateApk.verifyExistingClosedV3(bytes, spec)
            "closed-v4-incoming-share" -> TemplateApk.verifyExistingClosedV4(bytes, spec)
            "closed-v5-generic-native" -> TemplateApk.verify(bytes, spec)
            "closed-v1-current" -> TemplateApk.verifyExistingClosedV1(bytes, spec)
            "" -> TemplateApk.verifyExistingV1(bytes, spec)
            else -> error("Unsupported factory receipt manifest contract")
        }

    private fun verifyRuntimeConfig(bytes: ByteArray, spec: FactorySpec) {
        var config: ByteArray? = null
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "assets/factory-app.json") {
                    check(!entry.isDirectory && config == null) { "Duplicate or invalid factory configuration" }
                    config = readBounded(zip, FactorySpec.MAX_SPEC_BYTES)
                }
                zip.closeEntry()
            }
        }
        verifyRuntimeConfigBytes(config ?: error("Missing factory configuration"), spec)
    }
    private fun verifyRuntimeConfigBytes(bytes: ByteArray, spec: FactorySpec) {
        val parsed = com.jarvys.factory.runtime.FactoryConfig.parsePreview(String(bytes, Charsets.UTF_8))
        check(parsed.appId == spec.appId && parsed.name == spec.name && parsed.entryPoint == "www/index.html" &&
            parsed.capabilities == spec.capabilities.toSet() && parsed.dataBackupTables == spec.dataBackupTables.toSet() &&
            parsed.dataBackupKeys == spec.dataBackupKeys.toSet() && parsed.resources == spec.resources && parsed.incomingShare == spec.incomingShare &&
            parsed.notifications == (spec.notifications ?: com.jarvys.factory.contract.NotificationPolicy.disabled()) && parsed.bluetooth == spec.bluetooth) {
            "Runtime configuration differs from build receipt"
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
    private fun readArtifact(path: String, plannedBytes: Long, token: CancellationToken): ByteArray {
        require(plannedBytes in 22..MAX_APK_BYTES.toLong()) { "Receipt APK bytes exceed technical limit" }
        FactoryPackagingResources.requireHeap(FactoryPackagingResources.readEstimate(plannedBytes))
        val bytes = FactoryProjectFiles.read(scope, path, plannedBytes.toInt(), token)
        check(bytes.size.toLong() == plannedBytes) { "Artifact size differs from receipt" }
        FactoryPackagingResources.requireHeap(FactoryPackagingResources.artifactEstimate(plannedBytes, TemplateApk.expandedBytes(bytes)))
        return bytes
    }
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
        private fun readBounded(input: java.io.InputStream, max: Int, token: CancellationToken? = null): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { token?.throwIfCancelled(); val n = input.read(buffer); if (n < 0) break; check(out.size().toLong() + n <= max) { "Template exceeds factory limit" }; out.write(buffer, 0, n) }
            return out.toByteArray()
        }
    }
}
