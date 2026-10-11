package com.jarvys.agent.apkfactory

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.FileShareTransfer
import com.jarvys.factory.runtime.StreamingFileShareTransfer
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Policy-authorized binary sharing. No external callback is evidence of recipient closure or delivery. */
internal class FactoryFileShareCoordinator(
    context: Context,
    private val verify: (String) -> FactoryDocumentCoordinator.Proof = {
        FactoryDocumentCoordinator.verifyInstalled(context, it, setOf("documents", "share"))
    },
    private val receive: ((Request, () -> Unit) -> FactoryFileShareStore.Snapshot)? = null,
    private val clean: (() -> Unit)? = null,
    private val policy: FactoryOperationPolicy = FactoryOperationPolicy.get(context),
) {
    data class Request(val nonce: String, val filename: String, val mimeType: String, val size: Long, val sha256: String, val transfer: IBinder, val protocolVersion: Int = 1) {
        constructor(nonce: String, filename: String, mimeType: String, size: Int, sha256: String, transfer: IBinder) : this(nonce, filename, mimeType, size.toLong(), sha256, transfer, 1)
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.component != null) { "An explicit component is required" }
                require(intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.flags and GRANT_FLAGS == 0)
                val extras = intent.extras ?: error("Missing file sharing request")
                require(extras.keySet() == setOf("protocolVersion", "nonce", "filename", "mimeType", "size", "sha256", "transfer"))
                fun value(key: String): Any? = extras.get(key)
                fun string(key: String): String = value(key).also { require(it is String) } as String
                val version = value("protocolVersion"); require(version is Int && version in 1..2)
                val rawSize = value("size")
                val size: Long = if (version == 1) {
                    require(rawSize is Int && rawSize in 1..FileShareTransfer.MAX_BYTES); rawSize.toLong()
                } else {
                    require(rawSize is Long && rawSize in 1..StreamingFileShareTransfer.MAX_BYTES); rawSize
                }
                val nonce = string("nonce"); require(nonce.matches(Regex("[a-f0-9]{64}")))
                val hash = string("sha256"); require(hash.matches(Regex("[a-f0-9]{64}")))
                val filename = string("filename")
                require(filename.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}")) && !filename.endsWith(".") && !filename.contains(".."))
                val mime = string("mimeType")
                require(mime.length <= 127 && mime.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*")))
                val transfer = value("transfer"); require(transfer is IBinder)
                return Request(nonce, filename, mime, size, hash, transfer, version)
            }
        }
    }
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-file-sharing/interaction.json"))
    private val store = FactoryFileShareStore(context.applicationContext)
    private var lease: MemoryUiAutomationGuard.Lease? = null
    private var owner: String? = null
    private var proof: FactoryDocumentCoordinator.Proof? = null
    private var request: Request? = null
    private var snapshot: FactoryFileShareStore.Snapshot? = null
    private var permit: FactoryOperationPolicy.Grant? = null
    private var revocation: AutoCloseable? = null
    private var transferLease = false
    private var leaseId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var expire: Runnable? = null
    private var nonce = ""
    private var state = "idle"
    private var open = false
    private var broken = false
    private var busy = false
    private var restoring = false
    private var startupWaiter: (() -> Unit)? = null
    private var opened = false // Process-local only. Restart must never claim the chooser opened.
        @Synchronized fun isRestoring() = restoring
    @Synchronized fun status() = state
    @Synchronized fun canBegin() = !open && !broken && !busy && !restoring && FactoryInteractionAdmission.available(this)
    @Synchronized fun needsRecovery() = !restoring && open && (owner == null || broken)
    @Synchronized fun chooserAttempted() = state in setOf("chooser_pending", "chooser_opened", "launch_failed")
    @Synchronized fun chooserOpened() = opened
    private fun requireWorker() { check(Looper.myLooper() != Looper.getMainLooper()) { "File sharing I/O must not run on the main thread" } }
    private fun cleanup() { if (clean != null) clean.invoke() else store.cleanup() }
    private fun save() {
        requireWorker()
        try {
            check(journal.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val bytes = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", nonce).toString().toByteArray()
            val stream = journal.startWrite()
            try { stream.write(bytes); stream.fd.sync() } catch (failure: Throwable) { journal.failWrite(stream); throw failure }
            journal.finishWrite(stream); check(journal.readFully().contentEquals(bytes))
        } catch (failure: Exception) { broken = true; open = true; owner = null; state = "outcome_unknown"; FactoryInteractionAdmission.restore(this); throw failure }
    }
    /** Called synchronously at application startup before components can automate anything. */
    @Synchronized private fun prepareRestore() {
        restoring = true; FactoryInteractionAdmission.restore(this)
    }
    /** Disk work runs only on the one bounded host sharing worker. URI capabilities are never restored. */
    fun restore() {
        requireWorker()
        synchronized(this) { if (!restoring) prepareRestore() }
        try {
            val exists = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()
            if (exists) {
                val bytes = journal.openRead().use { input ->
                    val bytes = ByteArray(4097); var count = 0
                    while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; check(n > 0); count += n }
                    require(count <= 4096); bytes.copyOf(count)
                }
                val saved = FactoryJson.objectFrom(bytes, 4096)
                require(saved.keys().asSequence().toSet() == setOf("schemaVersion", "state", "open", "nonce"))
                require(saved.get("schemaVersion") == 1 && saved.get("open") is Boolean && saved.get("nonce") is String)
                val savedState = saved.getString("state"); val savedOpen = saved.getBoolean("open"); val savedNonce = saved.getString("nonce")
                require(if (savedOpen) savedState in OPEN_STATES else savedState in CLOSED_STATES)
                require(savedNonce.matches(Regex("[a-f0-9]{64}")) || (!savedOpen && savedState == "closed_outcome_unknown" && savedNonce.isEmpty()))
                synchronized(this) { nonce = savedNonce; open = savedOpen; state = if (open) "outcome_unknown" else savedState }
            }
            cleanup() // Any surviving cache bytes are revoked/deleted without restoring a URI registry.
            synchronized(this) {
                if (open) save() else release()
            }
        } catch (_: Exception) {
            synchronized(this) { broken = true; open = true; state = "outcome_unknown"; owner = null; FactoryInteractionAdmission.restore(this) }
        } finally {
            val ready = synchronized(this) { restoring = false; startupWaiter.also { startupWaiter = null } }
            ready?.let { Handler(Looper.getMainLooper()).post(it) }
        }
    }
    fun begin(callingPackage: String?, value: Request, checkActive: () -> Unit): String {
        requireWorker(); require(!callingPackage.isNullOrBlank()) { "Android calling package is required" }
        synchronized(this) { check(canBegin()); require(value.nonce != nonce) { "Replayed file sharing request" } }
        val identity = verify(callingPackage)
        check(identity.appId == callingPackage); checkActive()
        require(value.protocolVersion == 1 || value.protocolVersion == 2)
        require(value.size in 1..identity.resources.fileShare.sourceBytes) { "File-share source exceeds signed budget" }
        if (value.protocolVersion == 1) require(value.size <= FileShareTransfer.MAX_BYTES)
        val started = android.os.SystemClock.elapsedRealtime()
        fun checkDeadline() {
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            check(elapsed >= 0 && elapsed < identity.resources.fileShare.transferTimeoutMs) { "File-share transfer expired" }
        }
        val grant = policy.acquire(FactoryOperationPolicy.Subject.installed(identity), FactoryOperationPolicy.Operation.FILE_SHARE,
            FactoryOperationPolicy.binding(value.nonce, value.filename, value.mimeType, value.size.toString(), value.sha256, value.protocolVersion.toString(), identity.apk, identity.record))
        try { FactoryInteractionAdmission.acquire(this) } catch (failure: Exception) { grant.close(); throw failure }
        synchronized(this) { busy = true }
        var authenticated = false
        try {
            authenticated = true; checkActive(); grant.requireCurrent()
            val token = synchronized(this) {
                check(!open && !broken)
                val boundOwner = UUID.randomUUID().toString()
                owner = boundOwner; leaseId = boundOwner; proof = identity; request = value; nonce = value.nonce; permit = grant; transferLease = false
                revocation = grant.onRevoked { revokeTransfer(boundOwner, grant) }
                open = true; opened = false; state = "receiving"; save(); owner!!
            }
            cleanup()
            val active = { synchronized(this) { checkOwner(token); check(state == "receiving"); grant.requireCurrent() }; checkDeadline(); checkActive() }
            val staged = if (receive != null) receive.invoke(value, active)
                else store.stageStreaming(value.nonce, value.filename, value.mimeType, value.size, value.sha256, value.transfer,
                    value.protocolVersion, identity.resources.fileShare, active) { grant.checkBeforeEffect() }
            active()
            // Revalidate after the bounded transfer: no old installed APK or signing evidence is accepted.
            check(verify(callingPackage) == identity)
            checkDeadline() // APK/source revalidation cannot extend the signed transfer deadline.
            return synchronized(this) {
                checkOwner(token); checkActive(); grant.requireCurrent(); snapshot = staged; state = "review"; save()
                expire = Runnable { revokeTransfer(token, grant) }.also { handler.postDelayed(it, maxOf(0, staged.expiresAt - android.os.SystemClock.elapsedRealtime())) }
                token
            }
        } catch (failure: Exception) {
            synchronized(this) {
                if (open) { owner = null; proof = null; request = null; state = "outcome_unknown"; runCatching { save() } }
            }
            if (synchronized(this) { open }) runCatching { cleanup() }
            // This endpoint belongs to the verified caller only. A stalled cancellation keeps this
            // same worker and admission occupied; never spawn a replacement Binder worker.
            if (authenticated) runCatching { StreamingFileShareTransfer.cancel(value.transfer, value.nonce, value.protocolVersion) }
            grant.close(); throw failure
        } finally {
            synchronized(this) { busy = false; if (!open && !broken) FactoryInteractionAdmission.release(this) }
        }
    }
    private fun checkOwner(token: String) { check(open && !broken && owner == token); permit?.requireCurrent() }
    @Synchronized fun requiresApproval() = permit?.requiresApproval == true
    @Synchronized fun checkCurrent(token: String) { checkOwner(token); permit?.checkBeforeEffect() }
    private fun revokeTransfer(boundOwner: String, boundGrant: FactoryOperationPolicy.Grant) {
        // A stale expiry/retry must not invalidate another request's global registry.
        synchronized(this) {
            if (!open || leaseId != boundOwner || permit !== boundGrant) return
            FactoryFileShareRegistry.invalidate()
        }
        try { WORKER.execute {
            synchronized(this) {
                if (!open || leaseId != boundOwner || permit !== boundGrant) return@execute
                if (busy) { handler.postDelayed({ revokeTransfer(boundOwner, boundGrant) }, 1000); return@execute }
            }
            runCatching {
                cleanup()
                synchronized(this) {
                    if (!open || leaseId != boundOwner || permit !== boundGrant) return@synchronized
                    state = if (state == "outcome_unknown") "closed_outcome_unknown" else "closed"; open = false; save(); release()
                }
            }.onFailure {
                synchronized(this) {
                    if (open && leaseId == boundOwner && permit === boundGrant) {
                        owner = null; opened = false; state = "outcome_unknown"
                        FactoryInteractionAdmission.restore(this); runCatching { save() }
                    }
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            handler.postDelayed({ revokeTransfer(boundOwner, boundGrant) }, 1000)
        }
    }
    fun prepareChooser(token: String, title: String, human: () -> Unit): Intent {
        requireWorker()
        val p = synchronized(this) { checkOwner(token); check(state == "review"); human(); permit!!.approveOnce(human); permit!!.checkBeforeEffect(); proof!! }
        check(verify(p.appId) == p)
        return synchronized(this) {
            checkOwner(token); check(state == "review" && proof == p); human(); permit?.checkBeforeEffect()
            val file = snapshot ?: error("No verified snapshot")
            check(android.os.SystemClock.elapsedRealtime() < file.expiresAt) { "File sharing snapshot expired" }
            val send = Intent(Intent.ACTION_SEND).setType(file.mimeType).putExtra(Intent.EXTRA_STREAM, file.uri)
                .apply { clipData = ClipData.newRawUri("file", file.uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            state = "chooser_pending"; save() // Durable latch precedes every possible external launch.
            Intent.createChooser(send, title).apply {
                clipData = ClipData.newRawUri("file", file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    /** These are only in-process observations; neither releases recipient authority nor writes a delivery claim. */
    @Synchronized fun chooserLaunched(token: String) { checkOwner(token); check(state == "chooser_pending"); opened = true; state = "chooser_opened" }
    @Synchronized fun chooserLaunchFailed(token: String) { checkOwner(token); check(state == "chooser_pending"); opened = false; state = "launch_failed" }
    @Synchronized fun chooserCallback(token: String) { checkOwner(token); check(chooserAttempted()) /* No release, no delivery/closure inference. */ }
    /** No disk/Binder work on lifecycle callbacks; durable open journal already preserves uncertainty. */
    @Synchronized fun invalidate(token: String) {
        if (owner != token || !open || transferLease) return
        permit?.revoke()
        owner = null; proof = null; request = null; snapshot = null; opened = false; state = "outcome_unknown"
    }
    /** The returned UI is not the recipient's lifetime. Retain only bounded, revocable read authority. */
    fun detachRecipientLease(token: String, active: () -> Unit): Intent {
        requireWorker()
        return synchronized(this) {
            checkOwner(token); check(chooserAttempted()); active(); permit!!.checkBeforeEffect()
            val result = Intent().putExtra("nonce", nonce).putExtra("chooserOpened", opened).putExtra("deliveryConfirmed", false)
            transferLease = true; state = "transfer_active"; save()
            lease?.close(); lease = null; FactoryInteractionAdmission.release(this)
            result
        }
    }
    fun close(token: String, acknowledgedRecipientClosed: Boolean, human: () -> Unit): Intent {
        requireWorker()
        val result = synchronized(this) {
            check(open && owner == token); human()
            check(state == "review" || chooserAttempted())
            Intent().putExtra("nonce", nonce).putExtra("chooserOpened", opened).putExtra("deliveryConfirmed", false)
        }
        cleanup() // Invalidate registry, revoke grants globally, remove cache BEFORE journal/guard release.
        synchronized(this) {
            check(open && owner == token); human()
            state = "closed"; open = false; save(); release()
        }
        return result
    }
    fun acknowledgeRecovery(human: () -> Unit) {
        requireWorker()
        synchronized(this) { check(needsRecovery() && !busy); human() }
        cleanup()
        synchronized(this) {
            check(needsRecovery() && !busy); human()
            if (!nonce.matches(Regex("[a-f0-9]{64}"))) nonce = ""
            state = "closed_outcome_unknown"; open = false; save(); broken = false; release()
        }
    }
    private fun release() {
        expire?.let(handler::removeCallbacks); expire = null
        revocation?.close(); revocation = null; permit?.close(); permit = null; transferLease = false; leaseId = null
        proof = null; request = null; snapshot = null; owner = null; opened = false
        lease?.close(); lease = null; FactoryInteractionAdmission.release(this)
    }
    companion object {
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        private val OPEN_STATES = setOf("receiving", "review", "chooser_pending", "chooser_opened", "launch_failed", "transfer_active", "outcome_unknown")
        private val CLOSED_STATES = setOf("closed", "closed_outcome_unknown")
        internal val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
        /** At most one incoming Activity waits for startup, never a queue of external requests. */
        fun startupPending() = instance?.isRestoring() == true
        fun afterStartup(action: () -> Unit): Boolean {
            val current = instance
            if (current != null) synchronized(current) {
                if (current.restoring) {
                    if (current.startupWaiter != null) return false
                    current.startupWaiter = action
                    return true
                }
            }
            action(); return true
        }
        private var recoveryToken: String? = null
        @Synchronized fun recoveryIntent(context: Context): Intent {
            recoveryToken = UUID.randomUUID().toString()
            return Intent(context, FactoryFileShareActivity::class.java).putExtra("nativeRecoveryToken", recoveryToken)
        }
        @Synchronized fun consumeRecoveryToken(intent: Intent): Boolean {
            val token = intent.getStringExtra("nativeRecoveryToken")
            if (token == null || token != recoveryToken || intent.extras?.keySet() != setOf("nativeRecoveryToken") || intent.data != null || intent.clipData != null || intent.selector != null || intent.flags and GRANT_FLAGS != 0) return false
            recoveryToken = null; return true
        }
        @Volatile private var instance: FactoryFileShareCoordinator? = null
        fun get(context: Context): FactoryFileShareCoordinator = instance ?: synchronized(this) {
            instance ?: FactoryFileShareCoordinator(context.applicationContext).also {
                it.prepareRestore(); instance = it
                try { WORKER.execute { it.restore() } } catch (_: java.util.concurrent.RejectedExecutionException) {
                    synchronized(it) { it.restoring = false; it.open = true; it.broken = true; it.state = "outcome_unknown" }
                }
            }
        }
    }
}
