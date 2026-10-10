package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import android.util.AtomicFile
import com.jarvys.agent.MemoryUiAutomationGuard
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One human-owned SAF interaction. The journal never contains a document URI or document bytes. */
internal class FactoryDocumentCoordinator(context: Context, private val verify: (String) -> Proof = { verifyInstalled(context, it) }) {
    data class Request(val operation: String, val mimeType: String, val filename: String?, val nonce: String) {
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) == 0)
                val extras = intent.extras ?: error("Missing document request")
                require(extras.keySet() == setOf("operation", "mimeType", "nonce") || extras.keySet() == setOf("operation", "mimeType", "filename", "nonce"))
                fun string(key: String): String { @Suppress("DEPRECATION") val value = extras.get(key); require(value is String); return value }
                val operation = string("operation"); require(operation == "open" || operation == "create")
                val mime = string("mimeType")
                val plain = mime.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*"))
                val wildcard = mime == "*/*" || mime.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/\\*"))
                require(mime.length <= 127 && (plain || (operation == "open" && wildcard)))
                val filename = if (extras.containsKey("filename")) string("filename") else null
                require(if (operation == "open") filename == null else filename != null && filename.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}")) && !filename.endsWith(".") && !filename.contains(".."))
                val nonce = string("nonce"); require(nonce.matches(Regex("[a-f0-9]{64}")))
                return Request(operation, mime, filename, nonce)
            }
        }
    }
    data class Proof(val appId: String, val certificate: String, val apk: String, val version: Long, val record: String)
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-documents/interaction.json"))
    private var lease: MemoryUiAutomationGuard.Lease? = null
    private var owner: String? = null
    private var proof: Proof? = null
    private var request: Request? = null
    private var broken = false
    private var verifying = false
    private var state = "idle"
    private var open = false
    private var previousNonce: String? = null
    private fun protect() { if (lease == null) lease = MemoryUiAutomationGuard.enterProtectedSurface() }
    @Synchronized fun restore() {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return
        // Protection precedes parsing: malformed/truncated state also fails closed.
        protect(); FactoryInteractionAdmission.restore(this)
        try {
            val bytes = journal.openRead().use { input -> val bytes = ByteArray(4097); var count = 0; while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; count += n }; require(count <= 4096); bytes.copyOf(count) }
            val saved = FactoryJson.objectFrom(bytes, 4096)
            require(saved.keys().asSequence().toSet() == setOf("schemaVersion", "state", "open", "nonce"))
            require(saved.get("schemaVersion") == 1 && saved.get("open") is Boolean && saved.get("nonce") is String)
            require(saved.getString("state") in STATES)
            previousNonce = saved.getString("nonce")
            open = saved.getBoolean("open")
            val savedState = saved.getString("state")
            require(if (open) savedState in setOf("review", "picker", "cancelling", "selected", "cancelled", "outcome_unknown") else savedState in setOf("returned", "cancelled", "closed_outcome_unknown"))
            require(previousNonce!!.matches(Regex("[a-f0-9]{64}")) || (previousNonce == "" && savedState == "closed_outcome_unknown"))
            state = if (open) "outcome_unknown" else saved.getString("state")
            if (open) save() else { lease?.close(); lease = null; FactoryInteractionAdmission.release(this) }
        } catch (_: Exception) { broken = true; open = true; state = "outcome_unknown" }
    }
    private fun save() {
        try {
            check(journal.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val bytes = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", previousNonce ?: "").toString().toByteArray()
            val stream = journal.startWrite()
            try { stream.write(bytes); stream.fd.sync() } catch (e: Throwable) { journal.failWrite(stream); throw e }
            journal.finishWrite(stream)
            check(journal.readFully().contentEquals(bytes))
        } catch (e: Exception) { broken = true; open = true; state = "outcome_unknown"; protect(); throw e }
    }
    @Synchronized fun canBegin() = !open && !broken && !verifying && FactoryInteractionAdmission.available()
    fun begin(callingPackage: String?, value: Request): String {
        require(!callingPackage.isNullOrBlank()) { "Android calling package is required" }
        synchronized(this) {
            check(!open && !broken && !verifying) { "A document interaction is already in progress" }
            require(value.nonce != previousNonce) { "Replayed document request" }
            FactoryInteractionAdmission.acquire(this)
            verifying = true
        }
        try {
            val identity = verify(callingPackage)
            return synchronized(this) {
                check(!open && !broken)
                require(identity.appId == callingPackage)
                protect()
                if (lease?.isReadyForUser != true) { lease?.close(); lease = null; error("Automated action in flight") }
                owner = UUID.randomUUID().toString(); proof = identity; request = value
                previousNonce = value.nonce; state = "review"; open = true; save()
                owner!!
            }
        } finally { synchronized(this) { verifying = false; if (!open && !broken) FactoryInteractionAdmission.release(this) } }
    }
    @Synchronized fun status() = state
    @Synchronized fun needsRecovery() = open && (owner == null || broken)
    private fun checkOwner(token: String) { check(open && !broken && token == owner) }
    private fun verified(token: String, human: () -> Unit): Proof {
        val p = synchronized(this) { checkOwner(token); human(); lease!!.requireHumanUiInteraction(); proof ?: error("Authority revoked") }
        check(verify(p.appId) == p) { "Installed app or signing evidence changed" }
        return p
    }
    fun launch(token: String, human: () -> Unit): Intent {
        val p = verified(token, human)
        return synchronized(this) {
        checkOwner(token); human(); lease!!.requireHumanUiInteraction(); check(proof == p && state == "review")
        val r = request!!
        state = "picker"; save()
        Intent(if (r.operation == "open") Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(r.mimeType)
            .addFlags(if (r.operation == "open") Intent.FLAG_GRANT_READ_URI_PERMISSION else Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .apply { if (r.filename != null) putExtra(Intent.EXTRA_TITLE, r.filename) }
        }
    }
    @Synchronized fun pickerTerminal(token: String, selected: Boolean) {
        checkOwner(token); check(state == "picker" || state == "cancelling")
        state = if (selected && state == "picker") "selected" else "cancelled"; save()
    }
    @Synchronized fun cancelPicker(token: String) { checkOwner(token); check(state == "picker"); state = "cancelling"; proof = null; save() }
    @Synchronized fun revoke(token: String) {
        if (owner != token || !open) return
        proof = null; request = null; owner = null; state = "outcome_unknown"; save()
    }
    /** Only the foreground original Activity may return the URI, after a second native human action. */
    fun close(token: String, deliver: Boolean, human: () -> Unit): Request? {
        val p = if (deliver) verified(token, human) else null
        return synchronized(this) {
        checkOwner(token); human(); lease!!.requireHumanUiInteraction()
        check(state in setOf("review", "selected", "cancelled"))
        val result = if (deliver) { check(state == "selected" && proof == p); request } else null
        state = if (deliver) "returned" else "cancelled"; open = false; save()
        proof = null; request = null; owner = null; lease?.close(); lease = null; FactoryInteractionAdmission.release(this)
        result
        }
    }
    /** This acknowledges uncertainty, never asserts that an old external picker was closed. */
    @Synchronized fun acknowledgeRecovery(human: () -> Unit) {
        human(); check(needsRecovery()); lease!!.requireHumanUiInteraction()
        state = "closed_outcome_unknown"; open = false; if (previousNonce?.matches(Regex("[a-f0-9]{64}")) != true) previousNonce = ""; save(); broken = false
        proof = null; request = null; owner = null; lease?.close(); lease = null; FactoryInteractionAdmission.release(this)
    }
    companion object {
        private val STATES = setOf("idle", "review", "picker", "cancelling", "selected", "cancelled", "returned", "outcome_unknown", "closed_outcome_unknown")
        private val HOSTS = setOf("com.jarvys.agent", "com.jarvys.agent.recoverytest")
        private var recoveryToken: String? = null
        @Synchronized fun recoveryIntent(context: Context): Intent {
            recoveryToken = UUID.randomUUID().toString()
            return Intent(context, FactoryDocumentActivity::class.java).putExtra("nativeRecoveryToken", recoveryToken)
        }
        @Synchronized fun consumeRecoveryToken(intent: Intent): Boolean {
            val token = intent.getStringExtra("nativeRecoveryToken")
            if (token == null || token != recoveryToken || intent.extras?.keySet() != setOf("nativeRecoveryToken")) return false
            recoveryToken = null
            return true
        }
        @Volatile private var instance: FactoryDocumentCoordinator? = null
        fun get(context: Context): FactoryDocumentCoordinator = instance ?: synchronized(this) {
            instance ?: FactoryDocumentCoordinator(context.applicationContext).also { it.restore(); instance = it }
        }
        internal fun verifyInstalled(context: Context, appId: String, requiredCapabilities: Set<String> = setOf("documents")): Proof {
            require(context.packageName in HOSTS && appId !in HOSTS)
            val pm = context.packageManager
            fun requireOnlyHost() {
                for (host in HOSTS - context.packageName) {
                    val absent = try { pm.getApplicationInfo(host, 0); false } catch (_: PackageManager.NameNotFoundException) { true }
                    check(absent) { "Both Jarvys hosts are installed; document access is disabled" }
                }
            }
            requireOnlyHost()
            val before = installedSnapshot(pm, appId)
            // Reject unknown/unsigned callers before spending I/O on their installed APK.
            val evidence = FactorySigningIdentity(context).state(appId)
            verifyEvidence(appId, before.certificate, evidence.lastApkSha256 ?: error("Unknown signed APK"), before.version, evidence, requiredCapabilities)
            val file = File(before.sourceDir)
            val originalLength = file.length()
            require(file.isFile && originalLength in 1..MAX_APK_BYTES)
            val apk = file.inputStream().use { hashApk(it) }
            require(file.length() == originalLength) { "Installed APK changed during verification" }
            require(evidence == FactorySigningIdentity(context).state(appId)) { "Signing evidence changed during verification" }
            requireOnlyHost()
            require(before == installedSnapshot(pm, appId)) { "Installed package changed during verification" }
            return verifyEvidence(appId, before.certificate, apk, before.version, evidence, requiredCapabilities)
        }
        private const val MAX_APK_BYTES = 256L * 1024 * 1024
        private data class InstalledSnapshot(val sourceDir: String, val certificate: String, val version: Long, val uid: Int, val updated: Long)
        private fun installedSnapshot(pm: PackageManager, appId: String): InstalledSnapshot {
            @Suppress("DEPRECATION") val info = pm.getPackageInfo(appId, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val app = info.applicationInfo ?: error("Missing installed application")
            verifyPackageShape(appId, info, pm.getPackagesForUid(app.uid)?.toSet())
            @Suppress("DEPRECATION") val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            require(signatures?.size == 1)
            @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            return InstalledSnapshot(app.sourceDir, digest(signatures!![0].toByteArray()), version, app.uid, info.lastUpdateTime)
        }
        internal fun hashApk(input: java.io.InputStream, limit: Long = MAX_APK_BYTES): String {
            require(limit in 1..MAX_APK_BYTES)
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32768)
            var count = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(n > 0 && n <= limit - count) { "Installed APK exceeds the verification limit" }
                count += n; md.update(buffer, 0, n)
            }
            require(count > 0)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun verifyPackageShape(appId: String, info: PackageInfo, uidPackages: Set<String>?) {
            val app = info.applicationInfo ?: error("Missing installed application")
            @Suppress("DEPRECATION")
            require(info.sharedUserId == null && uidPackages == setOf(appId)) { "Shared or unknown caller UID" }
            require(info.packageName == appId && app.packageName == appId && app.splitSourceDirs.isNullOrEmpty() && info.splitNames.isNullOrEmpty())
        }
        internal fun verifyEvidence(appId: String, cert: String, apk: String, version: Long, evidence: FactorySigningIdentity.State, requiredCapabilities: Set<String> = setOf("documents")): Proof {
            require(evidence.existing && evidence.continuityKnown && evidence.fingerprint == cert && evidence.lastApkSha256 == apk && evidence.lastVersion.toLong() == version)
            val scope = evidence.lastScope?.toJson()?.getJSONArray("capabilities") ?: error("Unknown signed scope")
            require(requiredCapabilities.isNotEmpty() && requiredCapabilities.all { required -> (0 until scope.length()).any { scope.getString(it) == required } })
            return Proof(appId, cert, apk, version, evidence.recordSha256 ?: error("Unknown identity record"))
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
