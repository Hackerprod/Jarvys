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
import com.jarvys.agent.CoreAgentRuntime
import com.jarvys.agent.tasks.TaskPendingActionHandler
import com.jarvys.agent.tasks.TaskPendingActionHandlerProvider
import com.jarvys.agent.tasks.TaskPendingActionExecutor
import com.jarvys.agent.tasks.TaskPendingActionWork
import com.jarvys.agent.coding.ProjectScope
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One durable installation transaction. Android owns source permission and PackageInstaller consent. */
internal class FactoryInstallCoordinator(
    context: Context,
    private val backend: Backend = AndroidBackend(context),
    private val fullDistribution: Boolean = BuildConfig.FLAVOR == "full",
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
    private fun releaseOperation() { unregister?.run(); unregister = null; cancellation = null }
    private fun unprotect() { protection?.close(); protection = null }
    private var validation: (() -> Unit)? = null
    private var finalValidation: (() -> Unit)? = null
    private var unregister: Runnable? = null
    private var cancellation: CancellationToken? = null
    private var pendingIntent: Intent? = null
    private var launchToken: String? = null
    private var observer: (() -> Unit)? = null
    private var corrupt = false
    private var workInFlight = false

    @Synchronized fun restore() {
        if (!journalExists()) return
        try {
            val saved = read() ?: return
            if (saved.getBoolean("interactionOpen")) retire(saved)
        } catch (_: Exception) {
            corrupt = true
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
    private fun retire(record: JSONObject) {
        val id = runCatching { record.getInt("sessionId") }.getOrDefault(-1)
        if (id >= 0 && backend.exists(id)) {
            backend.abandon(id)
            check(!backend.exists(id)) { "Previous PackageInstaller session is still active" }
        }
        abandonOwnedSessions()
        record.put("state", "closed_outcome_unknown").put("interactionOpen", false)
        save(record)
        pendingIntent = null; launchToken = null; validation = null; finalValidation = null
        releaseOperation(); unprotect(); artifact.delete()
        observer?.invoke()
    }

    private fun retirePrevious() {
        while (workInFlight) {
            cancellation?.cancel()
            stopOperation()
            try { (this as java.lang.Object).wait() }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Previous installation is still stopping", interrupted)
            }
        }
        val previous = try { read() } catch (failure: Exception) { corrupt = true; null }
        if (previous != null && (previous.optBoolean("interactionOpen") || previous.optString("state") in RETIRE_ON_NEXT_REQUEST)) {
            retire(previous)
        } else if (corrupt) {
            abandonOwnedSessions()
            journal.delete()
            pendingIntent = null; launchToken = null; validation = null; finalValidation = null
            releaseOperation(); unprotect(); artifact.delete()
            corrupt = false
        } else abandonOwnedSessions()
    }
    private fun abandonOwnedSessions() {
        backend.ownedSessionIds().forEach { id ->
            if (backend.exists(id)) {
                backend.abandon(id)
                check(!backend.exists(id)) { "Previous PackageInstaller session is still active" }
            }
        }
    }
    @Synchronized fun prepare(metadata: JSONObject, bytes: ByteArray, validate: () -> Unit, token: CancellationToken, finalCheck: () -> Unit = validate): JSONObject {
        retirePrevious()
        validate()
        check(fullDistribution) { "Integrated installation is unavailable in Play. Keep the signed artifact; no workaround is attempted." }
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
            corrupt = true
            releaseOperation(); unprotect()
            throw failure
        }
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
            cancellation = token
            unregister = token.registerCancelAction { stopOperation() }
        } else { releaseOperation(); unprotect() }
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
            corrupt = true; unprotect(); releaseOperation(); return damagedStatus()
        } ?: return JSONObject().put("state", "no_session")
        if (binding != null) requireBinding(record.getJSONObject("binding"), binding)
        return publicStatus(record)
    }
    private fun damagedStatus() = JSONObject().put("state", "journal_unavailable").put("automation_protected", protection != null)
    private fun publicStatus(record: JSONObject) = JSONObject(record.getJSONObject("binding").toString())
        .put("state", record.getString("state")).put("session_id", record.getInt("sessionId"))
        .put("system_status", record.opt("systemStatus")).put("automation_protected", protection != null)
        .put("notice", "Only an authenticated PackageInstaller success callback confirms this session succeeded. Opening UI, missing sessions or an installed version are not success evidence. Physical app behavior and data retention are untested.")
    @Synchronized fun observe(callback: (() -> Unit)?) { observer = callback }
    /** STOP cancels local work and asks PackageInstaller to abandon any session already created. */
    @Synchronized private fun stopOperation() {
        releaseOperation()
        runCatching {
            revokeBeforeCommit()
            if (status().optString("state") in setOf("committing", "pending_system", "system_ui_open", "outcome_unknown")) cancel()
        }
    }
    @Synchronized fun revokeBeforeCommit() {
        if (corrupt) return
        val record = try { read() } catch (_: Exception) {
            corrupt = true; unprotect(); releaseOperation(); observer?.invoke(); return
        } ?: return
        if (record.getString("state") !in setOf("awaiting_user", "staging")) return
        record.put("state", "revoked")
        save(record)
        validation = null; finalValidation = null
        launchToken = null
        unprotect()
        observer?.invoke()
    }
    /** Expensive APK I/O stays off the state monitor so native pause/cancel never waits for hashing. */
    fun install(activeCheck: () -> Unit) {
        val work = synchronized(this) {
            check(!corrupt)
            val record = read() ?: error("No install request")
            check(record.getString("state") == "awaiting_user") { "This request cannot be replayed" }
            activeCheck(); unprotect()
            val checkAuthority = validation ?: error("Installation request is no longer active")
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
            activeCheck(); checkAuthority()
            check(backend.allowed()) { "Per-source install permission is unavailable" }
            verifyArtifact(artifact, binding)
            synchronized(this) { requireStaging(record); activeCheck() }
            id = backend.create(binding.getString("app_id"), artifact.length())
            synchronized(this) {
                requireStaging(record)
                record.put("sessionId", id); save(record)
            }
            backend.write(id, artifact)
            verifyArtifact(artifact, binding)
            checkAuthority(); activeCheck()
            check(backend.allowed())
            synchronized(this) {
                requireStaging(record)
                // Save before Binder. If the process dies here, restart records UNKNOWN, never replays.
                record.put("state", "committing"); save(record)
                activeCheck()
                finalValidation?.invoke() ?: error("Installation request is no longer active")
                activeCheck()
                val activeToken = cancellation ?: error("Installation cancellation scope expired")
                check(activeToken.runIfActive {
                    activeCheck()
                    commitInvoked = true
                    backend.commit(id, record.getString("nonce"))
                }) { "Installation stopped before commit" }
                validation = null; finalValidation = null
                // Keep the cancellation callback alive while Android may still ask for confirmation.
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
        } finally { synchronized(this) { workInFlight = false; (this as java.lang.Object).notifyAll(); observer?.invoke() } }
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
        if (terminal != null) { record.put("interactionOpen", false); save(record); unprotect(); releaseOperation() }
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
    /** Android may require real system confirmation; opening that UI is not an extra Jarvys approval. */
    @Synchronized fun takeSystemIntent(activeCheck: () -> Unit): Intent {
        activeCheck()
        val record = read() ?: error("No session")
        check(record.getString("state") == "pending_system")
        val intent = pendingIntent ?: error("System confirmation is unavailable after interruption. Send a new installation request; commit will not be replayed.")
        check(cancellation?.isCancellationRequested == false) { "Installation was stopped" }
        protect()
        if (protection?.isReadyForUser != true) { unprotect(); error("Wait for the automated device action before opening Android confirmation") }
        pendingIntent = null
        record.put("state", "system_ui_open"); save(record)
        return intent
    }
    @Synchronized fun cancel(binding: JSONObject? = null): JSONObject {
        check(!corrupt) { "Install journal unavailable" }
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
        releaseOperation()
        val id = record.getInt("sessionId")
        if (id < 0 || runCatching { !backend.exists(id) }.getOrDefault(false)) unprotect()
        observer?.invoke()
        return publicStatus(record)
    }
    @Synchronized fun requireCurrentInstall() {
        check(!corrupt && cancellation?.isCancellationRequested == false && read()?.getBoolean("interactionOpen") == true) {
            "Installation request is no longer active"
        }
    }
    companion object {
        private val TERMINAL = setOf("succeeded", "aborted_by_system", "blocked_by_system", "failed_by_system", "failed_before_commit", "cancelled_before_commit", "cancelled_outcome_unknown", "interrupted_before_commit", "revoked", "permission_required", "permission_unavailable", "installation_unavailable", "recovery_outcome_unknown", "closed_outcome_unknown")
        private val RETIRE_ON_NEXT_REQUEST = setOf("awaiting_user", "staging", "committing", "pending_system", "system_ui_open", "outcome_unknown", "cancel_requested", "cancelled_outcome_unknown", "interrupted_before_commit", "revoked", "failed_before_commit", "recovery_outcome_unknown", "closed_outcome_unknown")
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
            if (policy.isDeviceOwnerApp(context.packageName) || policy.isProfileOwnerApp(context.packageName) ||
                context.checkSelfPermission(android.Manifest.permission.INSTALL_PACKAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED) return Availability.READY
            return if (context.packageManager.canRequestPackageInstalls()) Availability.READY else Availability.SOURCE_PERMISSION_REQUIRED
        }
        override fun ownedSessionIds() = installer.mySessions.map { it.sessionId }
        override fun create(appId: String, size: Long): Int {
            check(allowed())
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appId); setSize(size)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED) // Android still decides whether confirmation is required.
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

/** Restores family-local outcome and resource journals at application startup. */
class FactoryInstallApplication : Application(), TaskPendingActionHandlerProvider, androidx.work.Configuration.Provider,
    com.jarvys.agent.connectors.ConnectionAccessProvider {
    // One early-deny ledger for the real Application; construction performs no storage/Room IO.
    private val connectorConnectionAccess by lazy {
        com.jarvys.agent.connectors.ConnectionAccess.createForApplication(this)
    }
    override fun connectionAccess(): com.jarvys.agent.connectors.ConnectionAccess = connectorConnectionAccess

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.jarvys.agent.SingleSessionReset.register(base.filesDir)
    }

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    override fun taskPendingActionHandler(): TaskPendingActionHandler =
        TaskPendingActionHandler { context, action -> TaskPendingActionWork.enqueue(context, action) }

    override fun taskPendingActionExecutor(): TaskPendingActionExecutor =
        object : TaskPendingActionExecutor {
            override fun execute(context: Context, task: com.jarvys.agent.tasks.ScheduledTask,
                                 action: com.jarvys.agent.tasks.TaskPendingAction) =
                CoreAgentRuntime.executeScheduledTaskAction(context, task, action.toolName,
                    JSONObject(action.argumentsJson), CancellationToken.uncancellable())
            override fun execute(context: Context, task: com.jarvys.agent.tasks.ScheduledTask,
                                 action: com.jarvys.agent.tasks.TaskPendingAction,
                                 invocation: com.jarvys.agent.EffectInvocation) =
                CoreAgentRuntime.executeScheduledTaskAction(context, task, action.toolName,
                    JSONObject(action.argumentsJson), CancellationToken.uncancellable(), invocation)
            override fun hasCompleteEffectAdmissionCoverage() = true
            override fun effectClassification(context: Context, task: com.jarvys.agent.tasks.ScheduledTask,
                                              action: com.jarvys.agent.tasks.TaskPendingAction) =
                CoreAgentRuntime.scheduledTaskActionEffectClass(context, task, action.toolName, JSONObject(action.argumentsJson))
            override fun actionEffectClassification(context: Context, task: com.jarvys.agent.tasks.ScheduledTask,
                                                    action: com.jarvys.agent.tasks.TaskPendingAction) =
                CoreAgentRuntime.scheduledTaskActionClassification(context, task, action.toolName, JSONObject(action.argumentsJson))
        }

    override fun onCreate() {
        super.onCreate()
        com.jarvys.agent.SingleSessionReset.start(this)
        connectionAccess().initializeAsync() // Process-owned local bootstrap; does not await Room.
        com.jarvys.agent.modules.ModuleHostVisibility.install(this) {
            com.jarvys.agent.tasks.TaskPendingActionWork.resumeWaitingForVisibleApp(this)
        }
        // Register only the lazy facade here; SQLite opens on its first background use.
        com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(this)
        clearRetiredOperationPolicyState()
        com.jarvys.agent.LocalDiagnostics.install(this)
        FactoryDataBackupCoordinator.get(this)
        FactoryContactCoordinator.get(this)
        FactoryExternalLaunchCoordinator.get(this)
        FactoryBrowserCoordinator.get(this)
        FactoryAudioCoordinator.get(this)
        FactoryFileShareCoordinator.get(this)
        FactoryDocumentCoordinator.get(this)
        FactoryPhotoCoordinator.get(this)
        FactoryInstallCoordinator.get(this)
    }

    private fun clearRetiredOperationPolicyState() {
        runCatching {
            AtomicFile(File(noBackupFilesDir, "factory-operation-policy/policy.json")).delete()
            File(noBackupFilesDir, "factory-operation-policy").delete()
        }
        val preferences = getSharedPreferences("jarvys_connectors", Context.MODE_PRIVATE)
        val values = preferences.all
        val editor = preferences.edit()
        var changed = false
        values.keys.filter { it.startsWith("autonomy_native_") }.forEach { editor.remove(it); changed = true }
        values.forEach { (key, value) ->
            if (key.startsWith("ux44_action_policy_v1_") && value is String &&
                runCatching { JSONObject(value).optString("connectorId") == "native" }.getOrDefault(false)) {
                editor.remove(key); changed = true
            }
        }
        // Legacy audit belongs to the strict, off-main Room migration. Never rewrite a partially
        // parsed array here: doing so could discard records before that migration can preserve them.
        if (changed) runCatching { editor.commit() }
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
