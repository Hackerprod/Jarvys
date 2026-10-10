package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.ExternalLaunchControl
import com.jarvys.factory.contract.ContactPickSpec
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Durable contact-selection authority. Personal data, URI and source identity are never journaled. */
internal class FactoryContactCoordinator(
    context: Context,
    private val verify: (String, String) -> FactoryDocumentCoordinator.Proof = { appId, capability ->
        FactoryDocumentCoordinator.verifyInstalled(context, appId, setOf(capability))
    },
) {
    data class Request(val nonce: String, val kind: String, val control: IBinder) {
        init { ContactPickSpec.kind(kind) }
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.component != null && intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.action == null && intent.type == null && intent.categories == null && intent.`package` == null)
                require(intent.flags == 0 && intent.sourceBounds == null && (android.os.Build.VERSION.SDK_INT < 29 || intent.identifier == null))
                val extras = intent.extras ?: error("Missing contact request")
                require(!extras.hasFileDescriptors())
                require(extras.keySet() == setOf("protocolVersion", "nonce", "kind", "control"))
                require(extras.get("protocolVersion") is Int && extras.get("protocolVersion") == 1)
                val nonce = extras.get("nonce"); require(nonce is String && nonce.matches(Regex("[a-f0-9]{64}")))
                val kind = extras.get("kind"); require(kind is String)
                val control = extras.get("control"); require(control is IBinder)
                return Request(nonce, kind, control)
            }
        }
    }
    class Session(val token: String, val proof: FactoryDocumentCoordinator.Proof, val request: Request, val expiresAt: Long) {
        val revoked = AtomicBoolean(false)
        @Volatile var registration: ExternalLaunchControl.Registration? = null
        @Volatile var attempted = false
        @Volatile var pickerReturned = false
        @Volatile var preparing = false
        fun revoke() { revoked.set(true) }
    }
    private val packageManager = context.applicationContext.packageManager
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-contacts/interaction.json"))
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
    fun isBusy() = busy || restoring
    fun needsRecovery(): Boolean { val value = current; return open && !restoring && (value == null || value.revoked.get() || broken) }
    fun canBegin() = !open && !broken && !busy && !restoring && FactoryInteractionAdmission.available()
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "External action I/O must run on the bounded worker" } }
    private fun protect() { if (lease == null) lease = MemoryUiAutomationGuard.enterProtectedSurface() }
    private fun save() {
        worker()
        try {
            check(journal.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val bytes = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", nonce).toString().toByteArray()
            val stream = journal.startWrite()
            try { stream.write(bytes); stream.fd.sync() } catch (failure: Throwable) { journal.failWrite(stream); throw failure }
            journal.finishWrite(stream); check(journal.readFully().contentEquals(bytes))
        } catch (failure: Exception) {
            broken = true; open = true; state = "outcome_unknown"; current?.revoke(); protect(); FactoryInteractionAdmission.restore(this)
            throw failure
        }
    }
    private fun prepareRestore() { restoring = true; protect(); FactoryInteractionAdmission.restore(this) }
    fun restore() {
        worker()
        if (!restoring) prepareRestore()
        try {
            if (journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()) {
                val bytes = journal.openRead().use { input ->
                    val bytes = ByteArray(4097); var count = 0
                    while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; check(n > 0); count += n }
                    require(count <= 4096); bytes.copyOf(count)
                }
                val saved = FactoryJson.objectFrom(bytes, 4096)
                require(saved.keys().asSequence().toSet() == setOf("schemaVersion", "state", "open", "nonce"))
                require(saved.get("schemaVersion") == 1 && saved.get("open") is Boolean && saved.get("nonce") is String)
                open = saved.getBoolean("open"); nonce = saved.getString("nonce")
                val old = saved.getString("state")
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
    fun begin(callingPackage: String?, request: Request, preparing: Boolean = false, active: () -> Unit): Session {
        worker(); require(!callingPackage.isNullOrBlank()); check(canBegin()); require(request.nonce != nonce)
        FactoryInteractionAdmission.acquire(this); busy = true
        try {
            ContactPickSpec.kind(request.kind)
            val proof = verify(callingPackage, "contacts"); check(proof.appId == callingPackage); active()
            protect(); check(lease!!.isReadyForUser) { "Automation in flight" }
            val now = SystemClock.elapsedRealtime(); check(now >= 0 && now <= Long.MAX_VALUE - 300000)
            val value = Session(UUID.randomUUID().toString(), proof, request, now + 300000)
            value.preparing = preparing
            current = value; nonce = request.nonce; open = true; state = "review"; save(); return value
        } catch (failure: Exception) {
            current?.preparing = false; current?.revoke(); if (open) { state = "outcome_unknown"; runCatching { save() } }
            throw failure
        } finally { busy = false; if (!open && !broken) { lease?.close(); lease = null; FactoryInteractionAdmission.release(this) } }
    }
    fun sourceVerifier(value: Session): ExternalLaunchControl.HostVerifier {
        worker(); requireActive(value)
        val uid = packageManager.getApplicationInfo(value.proof.appId, 0).uid
        check(packageManager.getPackagesForUid(uid)?.toList() == listOf(value.proof.appId))
        return ExternalLaunchControl.HostVerifier { caller -> caller == uid }
    }
    fun requireActive(value: Session) {
        check(current === value && open && !broken && !value.revoked.get() && SystemClock.elapsedRealtime() < value.expiresAt)
    }
    /** Consume authority durably before any external dispatch. No state ever permits replay. */
    fun prepareLaunch(value: Session, human: () -> Unit) {
        worker(); requireActive(value); check(state == "review" && !value.attempted)
        human(); lease!!.requireHumanUiInteraction(); check(verify(value.proof.appId, "contacts") == value.proof)
        requireActive(value); human(); lease!!.requireHumanUiInteraction()
        check(value.registration?.isRevoked == false)
        value.attempted = true; state = "launch_pending"; save()
    }
    fun verifyReady(value: Session, human: () -> Unit) {
        worker(); requireActive(value); human(); lease!!.requireHumanUiInteraction()
        check(state == "launch_pending" && value.attempted && verify(value.proof.appId, "contacts") == value.proof)
        requireActive(value); human(); check(value.registration?.isRevoked == false)
    }
    fun prepareRead(value: Session, human: () -> Unit) {
        worker(); requireActive(value); check(value.attempted && value.pickerReturned && state == "launch_pending")
        human(); lease!!.requireHumanUiInteraction(); check(verify(value.proof.appId, "contacts") == value.proof)
        requireActive(value); human(); check(value.registration?.isRevoked == false)
    }
    fun selected(value: Session) {
        worker(); requireActive(value); check(value.attempted && state == "launch_pending" && value.pickerReturned)
        check(value.registration?.isRevoked == false); state = "selected"; save()
    }
    /** Only the original foreground owner can approve the exact in-memory selected datum. */
    fun deliver(value: Session, datum: String, human: () -> Unit): Intent {
        worker(); requireActive(value); check(!value.preparing && state == "selected" && value.pickerReturned)
        ContactPickSpec.value(datum); human(); lease!!.requireHumanUiInteraction()
        check(verify(value.proof.appId, "contacts") == value.proof)
        requireActive(value); human(); check(value.registration?.isRevoked == false)
        val result = Intent().putExtra("nonce", nonce).putExtra("kind", value.request.kind).putExtra("value", datum)
        // Persist consumption before returning personal data; a failed/uncertain return is never replayed.
        state = "closed"; open = false; save()
        value.revoke(); value.registration?.close(); value.registration = null; release(); return result
    }
    fun close(value: Session?, recovery: Boolean, externalClosedByUser: Boolean, human: () -> Unit) {
        worker(); check(!busy && !restoring); human(); lease?.requireHumanUiInteraction()
        if (recovery) check(needsRecovery()) else check(value != null && current === value && open && !broken)
        val owned = current
        check(owned?.preparing != true) { "Contact provider work is still active" }
        if (recovery || (owned?.attempted == true && !owned.pickerReturned)) check(externalClosedByUser)
        owned?.revoke(); owned?.registration?.close(); owned?.registration = null
        human(); lease?.requireHumanUiInteraction()
        state = if (recovery) "closed_outcome_unknown" else "closed"; open = false
        if (!nonce.matches(Regex("[a-f0-9]{64}"))) nonce = ""
        save(); broken = false; release()
    }
    private fun release() { current = null; lease?.close(); lease = null; FactoryInteractionAdmission.release(this) }
    companion object {
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        private val OPEN_STATES = setOf("review", "launch_pending", "selected", "outcome_unknown")
        private val CLOSED_STATES = setOf("closed", "closed_outcome_unknown")
        internal val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
        private var recoveryToken: String? = null
        @Synchronized fun recoveryIntent(context: Context): Intent {
            recoveryToken = UUID.randomUUID().toString()
            return Intent(context, FactoryContactActivity::class.java).putExtra("nativeRecoveryToken", recoveryToken)
        }
        @Synchronized fun consumeRecoveryToken(intent: Intent): Boolean {
            val token = intent.getStringExtra("nativeRecoveryToken")
            if (token == null || token != recoveryToken || intent.extras?.keySet() != setOf("nativeRecoveryToken") || intent.data != null || intent.clipData != null || intent.selector != null || intent.flags and GRANT_FLAGS != 0) return false
            recoveryToken = null; return true
        }
        @Volatile private var instance: FactoryContactCoordinator? = null
        fun get(context: Context): FactoryContactCoordinator = instance ?: synchronized(this) {
            instance ?: FactoryContactCoordinator(context.applicationContext).also {
                it.prepareRestore(); instance = it
                try { WORKER.execute { it.restore() } } catch (_: java.util.concurrent.RejectedExecutionException) { it.restoring = false; it.open = true; it.broken = true; it.state = "outcome_unknown" }
            }
        }
        fun afterStartup(action: () -> Unit): Boolean {
            val current = instance
            if (current != null) synchronized(current) {
                if (current.restoring) { if (current.startupWaiter != null) return false; current.startupWaiter = action; return true }
            }
            action(); return true
        }
    }
}
