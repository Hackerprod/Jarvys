package com.jarvys.agent.apkfactory

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.util.AtomicFile
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.coding.ProjectScope
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One durable operation, never a queue. No automatic commit, retry, settings grant or success inference. */
internal class FactoryInstallCoordinator(
    context: Context,
    private val backend: Backend = AndroidBackend(context),
    private val fullDistribution: Boolean = BuildConfig.FLAVOR == "full"
) {
    enum class Availability { READY, SOURCE_PERMISSION_REQUIRED, UNSUPPORTED }
    interface Backend {
        fun allowed(): Boolean
        fun availability(): Availability = if (allowed()) Availability.READY else Availability.SOURCE_PERMISSION_REQUIRED
        fun ownedSessionIds(): List<Int>
        fun create(appId: String, size: Long): Int
        fun write(sessionId: Int, apk: File)
        fun commit(sessionId: Int, nonce: String)
        fun abandon(sessionId: Int)
        fun exists(sessionId: Int): Boolean
    }
    private val root = File(context.noBackupFilesDir, "factory-install")
    private val journal = AtomicFile(File(root, "session.json"))
    private val artifact = File(root, "approved.apk")
    private var protection: MemoryUiAutomationGuard.Lease? = null
    private var validation: (() -> Unit)? = null
    private var finalValidation: (() -> Unit)? = null
    private var unregister: Runnable? = null
    private var pendingIntent: Intent? = null
    private var launchToken: String? = null
    private var observer: (() -> Unit)? = null
    private var corrupt = false
    private var workInFlight = false
    private var recovering = false
    private var recoveryError: String? = null

    @Synchronized fun restore() {
        if (!journalExists()) return
        try {
            val saved = read() ?: return
            if (saved.getBoolean("interactionOpen")) {
                protect()
                if (saved.getString("state") == "checking_permission") {
                    saved.put("state", "permission_unavailable"); save(saved)
                } else if (saved.getString("state") in setOf("awaiting_user", "staging")) {
                    saved.put("state", "interrupted_before_commit")
                    save(saved)
                } else if (saved.getString("state") in setOf("committing", "pending_system", "system_ui_open")) {
                    saved.put("state", "outcome_unknown")
                    save(saved)
                }
            }
        } catch (_: Exception) {
            corrupt = true
            protect() // A damaged journal cannot silently lift the automation boundary.
        }
    }
    private fun protect() { if (protection == null) protection = MemoryUiAutomationGuard.enterProtectedSurface() }
    private fun journalExists() = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()
    private fun read(): JSONObject? {
        if (!journalExists()) return null
        val bytes = journal.readFully()
        check(bytes.size <= 16 * 1024) { "Invalid install journal size" }
        val result = FactoryJson.objectFrom(bytes, 16 * 1024)
        check(result.getInt("schemaVersion") == 1 && result.getString("nonce").matches(Regex("[a-f0-9-]{36}")))
        check(result.getString("state") in STATES)
        check(result.getInt("sessionId") >= -1)
        result.getBoolean("interactionOpen")
        result.getJSONObject("binding")
        return result
    }
    private fun save(record: JSONObject) {
        check(root.isDirectory || root.mkdirs())
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 16 * 1024)
        val stream = journal.startWrite()
        try { stream.write(bytes); journal.finishWrite(stream) }
        catch (failure: Throwable) { journal.failWrite(stream); throw failure }
    }
    @Synchronized fun prepare(metadata: JSONObject, bytes: ByteArray, validate: () -> Unit, token: CancellationToken, finalCheck: () -> Unit = validate): JSONObject {
        check(!corrupt) { "Install journal is damaged; native recovery is required" }
        check(read()?.getBoolean("interactionOpen") != true) { "An installation interaction is still open. Review it in Settings > Factory installations; never replay it." }
        validate()
        check(fullDistribution) { "Integrated installation is unavailable in Play. Keep the signed artifact; no workaround is attempted." }
        protect()
        if (protection?.isReadyForUser != true) {
            protection?.close(); protection = null
            error("An automated device action was in flight. Reopen installation manually after it ends.")
        }
        val record = JSONObject().put("schemaVersion", 1).put("nonce", UUID.randomUUID().toString())
            .put("binding", JSONObject(metadata.toString())).put("sessionId", -1).put("state", "awaiting_user")
            .put("interactionOpen", true).put("systemStatus", JSONObject.NULL)
            .put("state", "checking_permission")
        try {
            check(root.isDirectory || root.mkdirs())
            artifact.outputStream().use { it.write(bytes); it.fd.sync() }
            check(ProjectScope.sha256(artifact.readBytes()) == metadata.getString("apk_sha256"))
            save(record)
        } catch (failure: Exception) {
            // Never release protection after an uncertain durable journal write.
            corrupt = true
            throw failure
        }
        // The durable guard precedes even the permission query. No unprotected error-to-settings handoff.
        val readyState = try {
            when (backend.availability()) {
                Availability.READY -> "awaiting_user"
                Availability.SOURCE_PERMISSION_REQUIRED -> "permission_required"
                Availability.UNSUPPORTED -> "installation_unavailable"
            }
        } catch (_: Exception) { "permission_unavailable" }
        record.put("state", readyState); save(record)
        if (readyState == "awaiting_user") {
            validation = validate
            finalValidation = finalCheck
            unregister = token.registerCancelAction { revokeBeforeCommit() }
        }
        launchToken = UUID.randomUUID().toString()
        return publicStatus(record).put("launch_token", launchToken)
    }
    @Synchronized fun consumeLaunch(key: String?): Boolean {
        if (key == null || key != launchToken) return false
        launchToken = null
        return true
    }
    @Synchronized fun status(binding: JSONObject? = null): JSONObject {
        if (corrupt) return damagedStatus()
        val record = try { read() } catch (_: Exception) {
            corrupt = true; protect(); return damagedStatus()
        } ?: return JSONObject().put("state", "no_session")
        if (binding != null) requireBinding(record.getJSONObject("binding"), binding)
        return publicStatus(record)
    }
    private fun damagedStatus() = JSONObject().put("state", "journal_unavailable").put("automation_protected", true)
        .put("recovery_error", recoveryError ?: JSONObject.NULL)
    private fun publicStatus(record: JSONObject) = JSONObject(record.getJSONObject("binding").toString())
        .put("state", record.getString("state")).put("session_id", record.getInt("sessionId"))
        .put("system_status", record.opt("systemStatus")).put("automation_protected", record.getBoolean("interactionOpen"))
        .put("notice", "Only an authenticated PackageInstaller success callback confirms this session succeeded. Opening UI, missing sessions or an installed version are not success evidence. Physical app behavior and data retention are untested.")
    @Synchronized fun observe(callback: (() -> Unit)?) { observer = callback }
    @Synchronized fun revokeBeforeCommit() {
        if (corrupt) return
        val record = try { read() } catch (_: Exception) {
            corrupt = true; protect(); observer?.invoke(); return
        } ?: return
        if (record.getString("state") !in setOf("awaiting_user", "staging")) return
        record.put("state", "revoked")
        save(record)
        validation = null; finalValidation = null
        launchToken = null
        observer?.invoke()
    }
    /** Expensive APK I/O stays off the state monitor so native pause/cancel never waits for hashing. */
    fun install(humanCheck: () -> Unit) {
        val work = synchronized(this) {
            check(!corrupt)
            val record = read() ?: error("No install request")
            check(record.getString("state") == "awaiting_user") { "This request cannot be replayed" }
            humanCheck()
            val checkAuthority = validation ?: error("Installation approval expired; request a new installation")
            record.put("state", "staging"); save(record)
            workInFlight = true
            record to checkAuthority
        }
        val record = work.first
        val checkAuthority = work.second
        val binding = record.getJSONObject("binding")
        var id = -1
        var commitInvoked = false
        try {
            humanCheck(); checkAuthority()
            check(backend.allowed()) { "Per-source install permission is unavailable" }
            verifyArtifact(artifact, binding)
            synchronized(this) { requireStaging(record); humanCheck() }
            id = backend.create(binding.getString("app_id"), artifact.length())
            synchronized(this) {
                requireStaging(record)
                record.put("sessionId", id); save(record)
            }
            backend.write(id, artifact)
            verifyArtifact(artifact, binding)
            checkAuthority(); humanCheck()
            check(backend.allowed())
            synchronized(this) {
                requireStaging(record)
                // Save before Binder. If the process dies here, restart records UNKNOWN, never replays.
                record.put("state", "committing"); save(record)
                humanCheck()
                finalValidation?.invoke() ?: error("Installation authorization expired")
                humanCheck()
                commitInvoked = true
                backend.commit(id, record.getString("nonce"))
                validation = null; finalValidation = null
                unregister?.run(); unregister = null
            }
        } catch (failure: Exception) {
            val abandoned = !commitInvoked && (id < 0 || runCatching { backend.abandon(id); true }.getOrDefault(false))
            synchronized(this) {
                val current = read()
                if (current?.getString("nonce") == record.getString("nonce") && (current.getString("state") !in TERMINAL || !abandoned)) {
                    current.put("state", if (commitInvoked || !abandoned) "outcome_unknown" else "failed_before_commit")
                    if (id >= 0) current.put("sessionId", id)
                    save(current)
                }
                validation = null; finalValidation = null
            }
            throw failure
        } finally { synchronized(this) { workInFlight = false; observer?.invoke() } }
    }
    private fun requireStaging(record: JSONObject) {
        val current = read() ?: error("Install request missing")
        check(current.getString("nonce") == record.getString("nonce") && current.getString("state") == "staging") { "Install request revoked" }
    }
    @Synchronized fun callback(nonce: String?, sessionId: Int, status: Int, confirmation: Intent?) {
        if (corrupt) return
        val record = read() ?: return
        if (nonce != record.getString("nonce") || sessionId != record.getInt("sessionId") || sessionId < 0) return
        val state = record.getString("state")
        val terminal = terminalOutcome(status)
        if (state == "cancelled_outcome_unknown") {
            // Late authenticated evidence can resolve uncertainty, without reopening a closed interaction.
            if (terminal == null) return
        } else if (state !in setOf("committing", "pending_system", "system_ui_open", "outcome_unknown", "cancel_requested")) return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && state != "committing") return
        val next = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) "pending_system" else terminal ?: "outcome_unknown"
        record.put("state", next).put("systemStatus", status)
        save(record)
        pendingIntent = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) confirmation else null
        observer?.invoke()
    }
    private fun terminalOutcome(status: Int): String? = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "succeeded"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted_by_system" // Android does not reliably distinguish rejection from abort.
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked_by_system"
            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> "failed_by_system"
            else -> if (Build.VERSION.SDK_INT >= 34 && status == PackageInstaller.STATUS_FAILURE_TIMEOUT) "failed_by_system" else null
        }
    /** System confirmation is launched only by a fresh native human button, never from a receiver. */
    @Synchronized fun takeSystemIntent(humanCheck: () -> Unit): Intent {
        humanCheck()
        val record = read() ?: error("No session")
        check(record.getString("state") == "pending_system")
        val intent = pendingIntent ?: error("System confirmation is unavailable after interruption. Cancel/review this session; do not retry commit.")
        pendingIntent = null
        record.put("state", "system_ui_open"); save(record)
        return intent
    }
    @Synchronized fun cancel(binding: JSONObject? = null): JSONObject {
        check(!corrupt) { "Install journal unavailable" }
        check(!recovering) { "Native recovery is in progress" }
        val record = read() ?: return JSONObject().put("state", "no_session")
        if (binding != null) requireBinding(record.getJSONObject("binding"), binding)
        val state = record.getString("state")
        if (state !in TERMINAL || (state in setOf("interrupted_before_commit", "revoked", "failed_before_commit") && record.getInt("sessionId") >= 0 && backend.exists(record.getInt("sessionId")))) {
            val id = record.getInt("sessionId")
            record.put("state", "cancel_requested"); save(record)
            if (id >= 0) {
                try {
                    // Android throws for a missing session. Absence permits review/close, never a success claim.
                    if (backend.exists(id)) backend.abandon(id)
                    record.put("state", if (backend.exists(id)) "cancel_requested" else "cancelled_outcome_unknown")
                } catch (_: Exception) {
                    val absent = runCatching { !backend.exists(id) }.getOrDefault(false)
                    record.put("state", if (absent) "cancelled_outcome_unknown" else "outcome_unknown")
                }
            } else record.put("state", "cancelled_before_commit")
            save(record)
        }
        validation = null; pendingIntent = null; launchToken = null
        unregister?.run(); unregister = null
        observer?.invoke()
        return publicStatus(record)
    }
    /** Explicit native-only recovery. Own installer sessions are not an installed-package inventory. */
    fun recoverDamagedJournal(humanCheck: () -> Unit) {
        synchronized(this) {
            humanCheck()
            check(corrupt) { "The journal is readable; use the ordinary session controls" }
            check(!workInFlight && !recovering) { "Wait for the active operation" }
            recovering = true; workInFlight = true; recoveryError = null
        }
        var failureStage = "session_query_failed"
        try {
            val ids = backend.ownedSessionIds()
            failureStage = "too_many_or_invalid_sessions"
            check(ids.size <= 16 && ids.distinct().size == ids.size && ids.all { it >= 0 })
            for (id in ids) {
                failureStage = "foreground_lost"
                humanCheck()
                failureStage = "session_abandon_failed"
                try { backend.abandon(id) } catch (failure: Exception) {
                    if (backend.exists(id)) throw failure
                }
            }
            failureStage = "session_query_failed"
            val remaining = backend.ownedSessionIds()
            failureStage = "sessions_still_present"
            check(remaining.isEmpty())
            synchronized(this) {
                failureStage = "foreground_lost"
                humanCheck()
                val record = JSONObject().put("schemaVersion", 1).put("nonce", UUID.randomUUID().toString())
                    .put("binding", JSONObject()).put("sessionId", -1).put("state", "recovery_outcome_unknown")
                    .put("interactionOpen", true).put("systemStatus", JSONObject.NULL)
                failureStage = "journal_write_failed"
                save(record)
                validation = null; finalValidation = null; pendingIntent = null; launchToken = null
                unregister?.run(); unregister = null
                corrupt = false
            }
        } catch (failure: Exception) {
            synchronized(this) { recoveryError = failureStage }
            throw failure
        } finally {
            synchronized(this) { recovering = false; workInFlight = false; observer?.invoke() }
        }
    }
    /** Foreground protected native close only. An absent session never changes its recorded outcome. */
    @Synchronized fun closeInteraction(humanCheck: () -> Unit) {
        humanCheck()
        check(!corrupt) { "Damaged journal cannot release the safety boundary" }
        check(!workInFlight) { "Wait for staging or cancellation to finish" }
        val record = read() ?: return
        check(record.getString("state") in TERMINAL) { "Cancel or wait for the active session first" }
        val id = record.getInt("sessionId")
        check(id < 0 || !backend.exists(id)) { "Android still has this session; cancel it before closing" }
        if (record.getString("state") == "recovery_outcome_unknown") check(backend.ownedSessionIds().isEmpty()) { "Android still has owned installer sessions" }
        record.put("interactionOpen", false); save(record)
        artifact.delete()
        validation = null; pendingIntent = null; unregister?.run(); unregister = null
        protection?.close(); protection = null
    }
    companion object {
        private val TERMINAL = setOf("succeeded", "aborted_by_system", "blocked_by_system", "failed_by_system", "failed_before_commit", "cancelled_before_commit", "cancelled_outcome_unknown", "interrupted_before_commit", "revoked", "permission_required", "permission_unavailable", "installation_unavailable", "recovery_outcome_unknown")
        private val STATES = TERMINAL + setOf("checking_permission", "awaiting_user", "staging", "committing", "pending_system", "system_ui_open", "outcome_unknown", "cancel_requested")
        private var instance: FactoryInstallCoordinator? = null
        @Synchronized fun get(context: Context): FactoryInstallCoordinator = instance ?: FactoryInstallCoordinator(context.applicationContext).also { instance = it; it.restore() }
        internal fun requireBinding(saved: JSONObject, requested: JSONObject) {
            check(saved.keys().asSequence().toSet() == requested.keys().asSequence().toSet() && saved.keys().asSequence().all { saved.get(it).toString() == requested.get(it).toString() }) { "Install receipt does not match this exact project artifact" }
        }
        internal fun verifyArtifact(file: File, binding: JSONObject) {
            check(file.length() in 1..(32L * 1024 * 1024))
            check(ProjectScope.sha256(file.readBytes()) == binding.getString("apk_sha256")) { "Signed APK bytes changed" }
            check(FactoryApkSigner.verify(file) == binding.getString("certificate_sha256")) { "Signed APK certificate changed" }
        }
    }
    private class AndroidBackend(private val context: Context) : Backend {
        private val installer get() = context.packageManager.packageInstaller
        override fun allowed() = availability() == Availability.READY
        override fun availability(): Availability {
            if (Build.VERSION.SDK_INT < 26 || BuildConfig.FLAVOR != "full") return Availability.UNSUPPORTED
            val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (policy.isDeviceOwnerApp(context.packageName) || policy.isProfileOwnerApp(context.packageName)) return Availability.UNSUPPORTED
            if (context.checkSelfPermission(android.Manifest.permission.INSTALL_PACKAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED) return Availability.UNSUPPORTED
            return if (context.packageManager.canRequestPackageInstalls()) Availability.READY else Availability.SOURCE_PERMISSION_REQUIRED
        }
        override fun ownedSessionIds() = installer.mySessions.map { it.sessionId }
        override fun create(appId: String, size: Long): Int {
            check(allowed())
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appId); setSize(size)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            return installer.createSession(params)
        }
        override fun write(sessionId: Int, apk: File) {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }; session.fsync(output)
                }
            }
        }
        override fun commit(sessionId: Int, nonce: String) {
            val intent = Intent(context, FactoryInstallReceiver::class.java).setAction("com.jarvys.agent.FACTORY_INSTALL_RESULT")
                .setData("jarvys-install:$nonce".toUri())
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val sender = PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
            installer.openSession(sessionId).use { it.commit(sender) }
        }
        override fun abandon(sessionId: Int) = installer.abandonSession(sessionId)
        override fun exists(sessionId: Int) = installer.getSessionInfo(sessionId) != null
    }
}

/** Runs before activities/services: restore the persisted external-consent automation boundary. */
class FactoryInstallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FactoryExternalLaunchCoordinator.get(this)
        FactoryBrowserCoordinator.get(this)
        FactoryAudioCoordinator.get(this)
        FactoryFileShareCoordinator.get(this)
        FactoryDocumentCoordinator.get(this)
        FactoryPhotoCoordinator.get(this)
        FactoryInstallCoordinator.get(this)
    }
}

class FactoryInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.jarvys.agent.FACTORY_INSTALL_RESULT" || intent.data?.scheme != "jarvys-install") return
        @Suppress("DEPRECATION")
        val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        FactoryInstallCoordinator.get(context).callback(intent.data?.schemeSpecificPart,
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE), confirmation)
    }
}
