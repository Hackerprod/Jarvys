package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.jarvys.factory.runtime.BrowserLaunchControl
import com.jarvys.factory.contract.BrowserUrl
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Durable authenticated browser handoff; URLs and recipients are never journaled. */
internal class FactoryBrowserCoordinator(
    context: Context,
    private val verify: (String) -> FactoryDocumentCoordinator.Proof = {
        FactoryDocumentCoordinator.verifyInstalled(context, it, setOf("browser"))
    },
) {
    data class Request(val nonce: String, val url: String, val control: IBinder) {
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.component != null && intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.flags and GRANT_FLAGS == 0)
                val extras = intent.extras ?: error("Missing browser request")
                require(extras.keySet() == setOf("protocolVersion", "nonce", "url", "control"))
                require(extras.get("protocolVersion") is Int && extras.get("protocolVersion") == 1)
                val nonce = extras.get("nonce"); require(nonce is String && nonce.matches(Regex("[a-f0-9]{64}")))
                val url = extras.get("url"); require(url is String); BrowserUrl.parse(url)
                val control = extras.get("control"); require(control is IBinder)
                return Request(nonce, url, control)
            }
        }
    }
    class Session(val token: String, val proof: FactoryDocumentCoordinator.Proof, val request: Request, val expiresAt: Long) {
        val revoked = AtomicBoolean(false)
        @Volatile var registration: BrowserLaunchControl.Registration? = null
        @Volatile var attempted = false
        @Volatile var launchRequested = false
        @Volatile var preparing = false
        fun revoke() { revoked.set(true); registration?.close() }
    }
    private val packageManager = context.applicationContext.packageManager
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-browser/interaction.json"))
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
    fun canBegin() = !broken && !busy && !restoring && (open || current == null && FactoryInteractionAdmission.available(this))
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Browser I/O must run on the bounded worker" } }
    private fun save() {
        worker()
        try {
            check(journal.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val bytes = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", nonce).toString().toByteArray()
            val stream = journal.startWrite()
            try { stream.write(bytes); stream.fd.sync() } catch (failure: Throwable) { journal.failWrite(stream); throw failure }
            journal.finishWrite(stream); check(journal.readFully().contentEquals(bytes))
        } catch (failure: Exception) {
            broken = true; open = true; state = "outcome_unknown"; current?.revoke()
            throw failure
        }
    }
    private fun prepareRestore() { restoring = true }
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
                state = if (open) "closed_outcome_unknown" else old
                if (open) { open = false; save() }
            }
            if (open) { state = "closed_outcome_unknown"; open = false; save() }
            release()
        } catch (_: Exception) {
            broken = false; open = false; state = "closed_outcome_unknown"; nonce = ""
            runCatching { save() }; release()
        }
        finally {
            val ready = synchronized(this) { restoring = false; startupWaiter.also { startupWaiter = null } }
            ready?.let { Handler(Looper.getMainLooper()).post(it) }
        }
    }
    fun begin(callingPackage: String?, request: Request, preparing: Boolean = false, active: () -> Unit): Session {
        worker(); require(!callingPackage.isNullOrBlank()); check(canBegin()); require(request.nonce != nonce)
        busy = true
        try {
            BrowserUrl.parse(request.url)
            val proof = verify(callingPackage); check(proof.appId == callingPackage); active()
            if (open) {
                check(!broken && current?.preparing != true)
                val previous = current
                if (previous == null) FactoryInteractionAdmission.acquire(this)
                previous?.revoke(); previous?.registration?.close(); previous?.registration = null
                state = "closed_outcome_unknown"; open = false; save(); current = null
            } else FactoryInteractionAdmission.acquire(this)
            val now = SystemClock.elapsedRealtime(); check(now >= 0 && now <= Long.MAX_VALUE - 300000)
            val value = Session(UUID.randomUUID().toString(), proof, request, now + 300000)
            value.preparing = preparing
            current = value; nonce = request.nonce; open = true; state = "review"; save(); return value
        } catch (failure: Exception) {
            current?.preparing = false; current?.revoke(); if (current != null && open) { state = "outcome_unknown"; runCatching { save() } }
            throw failure
        } finally { busy = false; if (!open && !broken) FactoryInteractionAdmission.release(this) }
    }
    fun sourceVerifier(value: Session): BrowserLaunchControl.HostVerifier {
        worker(); requireActive(value)
        val uid = packageManager.getApplicationInfo(value.proof.appId, 0).uid
        check(packageManager.getPackagesForUid(uid)?.toList() == listOf(value.proof.appId))
        return BrowserLaunchControl.HostVerifier { caller -> caller == uid }
    }
    fun requireActive(value: Session) {
        check(current === value && open && !broken && !value.revoked.get() && SystemClock.elapsedRealtime() < value.expiresAt)
    }
    fun requireCurrent(value: Session) { requireActive(value); check(verify(value.proof.appId) == value.proof) }
    /** Consume authority durably before any external dispatch. No state ever permits replay. */
    fun prepareLaunch(value: Session, active: () -> Unit) {
        worker(); requireActive(value); check(state == "review" && !value.attempted)
        active(); requireCurrent(value)
        requireActive(value); active(); requireCurrent(value)
        check(value.registration?.isRevoked == false)
        value.attempted = true; state = "launch_pending"; save()
    }
    fun verifyReady(value: Session, active: () -> Unit) {
        worker(); requireActive(value); active(); requireCurrent(value)
        check(state == "launch_pending" && value.attempted && verify(value.proof.appId) == value.proof)
        requireActive(value); active(); requireCurrent(value); check(value.registration?.isRevoked == false)
    }
    fun close(value: Session?, active: () -> Unit): Intent? {
        worker(); check(!busy && !restoring); active()
        check(value != null && current === value && open && !broken)
        val owned = requireNotNull(current)
        check(owned?.preparing != true) { "Native browser preparation is still active" }
        owned?.revoke(); owned?.registration?.close(); owned?.registration = null
        active()
        val result = Intent().putExtra("nonce", nonce).putExtra("launchRequested", owned.launchRequested).putExtra("pageLoadConfirmed", false)
        state = "closed"; open = false
        if (!nonce.matches(Regex("[a-f0-9]{64}"))) nonce = ""
        save(); broken = false; release(); return result
    }
    private fun release() { current = null; FactoryInteractionAdmission.release(this) }
    companion object {
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        private val OPEN_STATES = setOf("review", "launch_pending", "outcome_unknown")
        private val CLOSED_STATES = setOf("closed", "closed_outcome_unknown")
        internal val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
        @Volatile private var instance: FactoryBrowserCoordinator? = null
        fun get(context: Context): FactoryBrowserCoordinator = instance ?: synchronized(this) {
            instance ?: FactoryBrowserCoordinator(context.applicationContext).also {
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
