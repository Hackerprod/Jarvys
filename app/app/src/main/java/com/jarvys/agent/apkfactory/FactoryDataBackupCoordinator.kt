package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.BackupReviewControl
import com.jarvys.factory.runtime.DatabaseBackupFormat
import com.jarvys.factory.runtime.DatabaseBackupPlan
import com.jarvys.factory.runtime.FileShareTransfer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Host consent authority only. Never opens the generated database or persists archive/URI/table data. */
internal class FactoryDataBackupCoordinator(
    context: Context,
    private val currentIdentity: (FactoryDataBackupPolicy.Verified) -> Unit = { FactoryDataBackupPolicy.requireCurrent(context, it) },
    private val verify: (String, List<String>) -> FactoryDataBackupPolicy.Verified = { appId, tables -> FactoryDataBackupPolicy.verify(context, appId, tables) },
) {
    data class Request(val plan: DatabaseBackupPlan, val transfer: IBinder, val control: IBinder) {
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.component != null && intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.action == null && intent.type == null && intent.categories == null && intent.`package` == null && intent.flags and GRANT_FLAGS == 0)
                val extras = intent.extras ?: error("Missing backup request")
                require(extras.keySet() == setOf("protocolVersion", "plan", "transfer", "control"))
                @Suppress("DEPRECATION") val version = extras.get("protocolVersion")
                require(version is Int && version == 1)
                @Suppress("DEPRECATION") val plan = extras.get("plan")
                @Suppress("DEPRECATION") val transfer = extras.get("transfer")
                @Suppress("DEPRECATION") val control = extras.get("control")
                require(plan is String && transfer is IBinder && control is IBinder)
                return Request(DatabaseBackupPlan.parse(plan), transfer, control)
            }
        }
    }
    class Session(val verified: FactoryDataBackupPolicy.Verified, val request: Request) {
        val revoked = AtomicBoolean(false)
        @Volatile var preparing = true
        @Volatile var pickerOutstanding = false
        @Volatile var abandoned = false
        @Volatile var registration: BackupReviewControl.Registration? = null
        @Volatile var archive: DatabaseBackupFormat.Archive? = null
            private set
        val plan get() = request.plan
        @Synchronized fun revoke() { revoked.set(true); archive = null }
        @Synchronized fun installArchive(value: DatabaseBackupFormat.Archive) {
            check(!revoked.get()); archive = value
        }
    }
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-data-backup/interaction.json"))
    private var lease: MemoryUiAutomationGuard.Lease? = null
    @Volatile private var current: Session? = null
    @Volatile private var state = "idle"
    @Volatile private var open = false
    @Volatile private var broken = false
    @Volatile private var busy = false
    @Volatile private var restoring = false
    private var nonce = ""
    private var startupWaiter: (() -> Unit)? = null
    fun session() = current
    fun status() = state
    fun isBusy() = busy || restoring || current?.preparing == true
    fun needsRecovery() = open && !restoring && (current == null || current?.revoked?.get() == true || broken)
    fun canBegin() = !open && !broken && !busy && !restoring && FactoryInteractionAdmission.available()
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Backup review requires the bounded worker" } }
    private fun protect() { if (lease == null) lease = MemoryUiAutomationGuard.enterProtectedSurface() }
    private fun prepareRestore() { restoring = true; protect(); FactoryInteractionAdmission.restore(this) }
    private fun save() {
        worker()
        try {
            check(journal.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val bytes = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", nonce).toString().toByteArray()
            val stream = journal.startWrite()
            try { stream.write(bytes); stream.fd.sync() } catch (failure: Throwable) { journal.failWrite(stream); throw failure }
            journal.finishWrite(stream); check(journal.readFully().contentEquals(bytes))
        } catch (failure: Exception) {
            broken = true; open = true; state = "outcome_unknown"; current?.revoke(); protect(); FactoryInteractionAdmission.restore(this); throw failure
        }
    }
    fun restore() {
        worker(); if (!restoring) prepareRestore()
        try {
            if (journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()) {
                val bytes = journal.openRead().use { input ->
                    val buffer = ByteArray(4097); var count = 0
                    while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; require(n > 0); count += n }
                    require(count <= 4096); buffer.copyOf(count)
                }
                val saved = FactoryJson.objectFrom(bytes, 4096)
                require(saved.keys().asSequence().toSet() == setOf("schemaVersion", "state", "open", "nonce"))
                require(saved.get("schemaVersion") is Int && saved.getInt("schemaVersion") == 1 && saved.get("open") is Boolean && saved.get("nonce") is String)
                open = saved.getBoolean("open"); nonce = saved.getString("nonce"); val old = saved.getString("state")
                require(if (open) old in OPEN_STATES else old in CLOSED_STATES)
                require(nonce.matches(Regex("[a-f0-9]{64}")) || (!open && old == "closed_outcome_unknown" && nonce.isEmpty()))
                state = if (open) "outcome_unknown" else old
            }
            if (open) save() else release()
        } catch (_: Exception) { broken = true; open = true; state = "outcome_unknown"; protect(); FactoryInteractionAdmission.restore(this) }
        finally {
            val ready = synchronized(this) { restoring = false; startupWaiter.also { startupWaiter = null } }
            ready?.let { Handler(Looper.getMainLooper()).post(it) }
        }
    }
    fun begin(callingPackage: String?, request: Request, active: () -> Unit): Session {
        worker(); require(!callingPackage.isNullOrBlank()); check(canBegin()); require(request.plan.nonce != nonce)
        checkDeadline(request.plan); require(callingPackage == request.plan.appId)
        FactoryInteractionAdmission.acquire(this); busy = true
        try {
            val verified = verify(callingPackage, request.plan.tables); FactoryDataBackupPolicy.validate(request.plan, verified); active(); checkDeadline(request.plan)
            protect(); check(lease!!.isReadyForUser)
            val value = Session(verified, request)
            current = value; nonce = request.plan.nonce; open = true; state = "review"; save(); return value
        } catch (failure: Exception) { current?.preparing = false; current?.revoke(); throw failure }
        finally { busy = false; if (!open && !broken) release() }
    }
    fun prepare(value: Session, active: () -> Unit, revoked: Runnable) {
        worker(); requireActive(value)
        var copied = false
        try {
            value.registration = BackupReviewControl.register(value.request.control, value.plan.nonce,
                BackupReviewControl.HostVerifier { uid ->
                    if (uid != value.verified.source.uid) false
                    else runCatching { currentIdentity(value.verified) }.fold({ true }, { value.revoke(); revoked.run(); false })
                }, Runnable { value.revoke(); revoked.run() })
            check(value.registration?.isRevoked == false)
            val output = object : ByteArrayOutputStream(value.plan.size) {
                fun erase() { java.util.Arrays.fill(buf, 0.toByte()); reset() }
            }
            try {
                FileShareTransfer.copy(value.request.transfer, value.plan.nonce, value.plan.size, value.plan.archiveSha256, output) { requireActive(value); currentIdentity(value.verified); requireActive(value); active() }
                copied = true
                val bytes = output.toByteArray()
                try {
                    val archive = DatabaseBackupFormat.parse(bytes); value.plan.validateArchive(archive)
                    requireActive(value); active(); revalidate(value); value.installArchive(archive); requireActive(value); active()
                } finally { bytes.fill(0) }
            } finally { output.erase() }
        } catch (failure: Exception) { value.revoke(); throw failure }
        finally {
            // A blocked transaction/cleanup retains both this worker and host admission.
            try { if (!copied) FileShareTransfer.cancel(value.request.transfer, value.plan.nonce) }
            finally { value.preparing = false }
        }
    }
    fun requireActive(value: Session) {
        check(current === value && open && !broken && !value.revoked.get()); checkDeadline(value.plan)
    }
    private fun revalidate(value: Session) {
        requireActive(value)
        check(verify(value.plan.appId, value.plan.tables) == value.verified)
        requireActive(value); check(value.registration?.isRevoked == false)
    }
    private fun human(value: Session, action: () -> Unit) {
        worker(); requireActive(value); check(!value.preparing && value.archive != null)
        action(); lease!!.requireHumanUiInteraction(); revalidate(value); action(); lease!!.requireHumanUiInteraction()
    }
    fun launchPicker(value: Session, action: () -> Unit): Intent {
        human(value, action); check(state == "review" && value.plan.operation == "export" && !value.pickerOutstanding)
        state = "picker"; value.pickerOutstanding = true; save()
        return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION).putExtra(Intent.EXTRA_TITLE, value.plan.filename)
    }
    fun pickerTerminal(value: Session, selected: Boolean) {
        worker(); check(current === value && open && value.pickerOutstanding)
        value.pickerOutstanding = false
        state = if (selected && !value.revoked.get() && state == "picker") "selected" else "cancelled"
        save()
    }
    fun cancelPicker(value: Session) {
        worker(); check(current === value && open && value.pickerOutstanding)
        value.revoke(); state = "cancelling"; save()
    }
    fun approve(value: Session, action: () -> Unit): Intent {
        human(value, action)
        check(!value.pickerOutstanding && if (value.plan.operation == "export") state == "selected" else state == "review")
        val result = Intent().putExtra("nonce", value.plan.nonce).putExtra("planSha256", value.plan.sha256()).putExtra("approved", true)
        state = "approved"; open = false; save(); release(); return result
    }
    fun close(value: Session?, recovery: Boolean, externalClosedByUser: Boolean, action: () -> Unit) {
        worker(); check(!busy && !restoring); action(); lease?.requireHumanUiInteraction()
        if (recovery) { check(needsRecovery()); check(value == null || current === value) }
        else check(value != null && current === value && open && !broken)
        val owned = current; check(owned?.preparing != true)
        if (owned?.pickerOutstanding == true) check(recovery && owned.abandoned && externalClosedByUser)
        if (recovery) check(externalClosedByUser)
        owned?.revoke(); owned?.registration?.close(); owned?.registration = null
        action(); lease?.requireHumanUiInteraction()
        state = if (recovery) "closed_outcome_unknown" else "closed"; open = false
        if (!nonce.matches(Regex("[a-f0-9]{64}"))) nonce = ""
        save(); broken = false; release()
    }
    private fun release() {
        current?.revoke(); current?.registration?.close(); current?.registration = null; current = null
        lease?.close(); lease = null; FactoryInteractionAdmission.release(this)
    }
    companion object {
        internal const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        private val OPEN_STATES = setOf("review", "picker", "cancelling", "selected", "cancelled", "outcome_unknown")
        private val CLOSED_STATES = setOf("approved", "closed", "closed_outcome_unknown")
        internal val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
        internal fun checkDeadline(plan: DatabaseBackupPlan) {
            val now = SystemClock.elapsedRealtime(); check(now >= 0 && plan.deadline > now && plan.deadline - now <= DatabaseBackupPlan.LIFETIME_MILLIS)
        }
        private var recoveryToken: String? = null
        @Synchronized fun recoveryIntent(context: Context): Intent {
            recoveryToken = UUID.randomUUID().toString()
            return Intent(context, FactoryDataBackupActivity::class.java).putExtra("nativeRecoveryToken", recoveryToken)
        }
        @Synchronized fun consumeRecoveryToken(intent: Intent): Boolean {
            val token = intent.getStringExtra("nativeRecoveryToken")
            if (token == null || token != recoveryToken || intent.extras?.keySet() != setOf("nativeRecoveryToken") || intent.data != null || intent.clipData != null || intent.selector != null || intent.flags and GRANT_FLAGS != 0) return false
            recoveryToken = null; return true
        }
        @Volatile private var instance: FactoryDataBackupCoordinator? = null
        fun get(context: Context): FactoryDataBackupCoordinator = instance ?: synchronized(this) {
            instance ?: FactoryDataBackupCoordinator(context.applicationContext).also {
                it.prepareRestore(); instance = it
                try { WORKER.execute { it.restore() } } catch (_: java.util.concurrent.RejectedExecutionException) { it.restoring = false; it.open = true; it.broken = true; it.state = "outcome_unknown" }
            }
        }
        fun afterStartup(action: () -> Unit): Boolean {
            val value = instance
            if (value != null) synchronized(value) { if (value.restoring) { if (value.startupWaiter != null) return false; value.startupWaiter = action; return true } }
            action(); return true
        }
    }
}
