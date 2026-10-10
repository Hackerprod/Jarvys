package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.AudioPlaybackControl
import com.jarvys.factory.runtime.FileShareTransfer
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One foreground human audio interaction. Durable state never restores audio or playback authority. */
internal class FactoryAudioCoordinator(
    context: Context,
    private val verify: (String) -> FactoryDocumentCoordinator.Proof = {
        FactoryDocumentCoordinator.verifyInstalled(context, it, setOf("documents", "audio"))
    },
    private val receive: ((Request, () -> Unit) -> ByteArray)? = null,
) {
    data class Request(val nonce: String, val size: Int, val sha256: String, val transfer: IBinder, val control: IBinder) {
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.component != null && intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.flags and GRANT_FLAGS == 0)
                val extras = intent.extras ?: error("Missing audio request")
                require(extras.keySet() == setOf("protocolVersion", "nonce", "size", "sha256", "transfer", "control"))
                require(extras.get("protocolVersion") is Int && extras.get("protocolVersion") == 1)
                val size = extras.get("size"); require(size is Int && size in 46..FactoryAudioPcm.MAX_BYTES)
                val nonce = extras.get("nonce"); require(nonce is String && nonce.matches(Regex("[a-f0-9]{64}")))
                val hash = extras.get("sha256"); require(hash is String && hash.matches(Regex("[a-f0-9]{64}")))
                val transfer = extras.get("transfer"); val control = extras.get("control")
                require(transfer is IBinder && control is IBinder)
                return Request(nonce, size, hash, transfer, control)
            }
        }
    }
    class Session(val token: String, val proof: FactoryDocumentCoordinator.Proof, val request: Request, val expiresAt: Long) {
        val revoked = AtomicBoolean(false)
        val playerClean = AtomicBoolean(true)
        val preparing = AtomicBoolean(false)
        @Volatile var pcm: FactoryAudioPcm? = null
        @Volatile var registration: AudioPlaybackControl.Registration? = null
        @Volatile var player: FactoryAudioPlayer? = null
        @Volatile var attempted = false
        /** No disk, worker, package manager or coordinator monitor on urgent revocation. */
        fun revoke() { revoked.set(true) }
    }
    private val packageManager = context.applicationContext.packageManager
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-audio/interaction.json"))
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
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Audio I/O must run on the bounded worker" } }
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
    fun begin(callingPackage: String?, request: Request, active: () -> Unit): Session {
        worker(); require(!callingPackage.isNullOrBlank()); check(canBegin()); require(request.nonce != nonce)
        FactoryInteractionAdmission.acquire(this); busy = true
        var bytes: ByteArray? = null
        try {
            val proof = verify(callingPackage); check(proof.appId == callingPackage); active()
            protect(); check(lease!!.isReadyForUser) { "Automation in flight" }
            val now = SystemClock.elapsedRealtime(); check(now >= 0 && now <= Long.MAX_VALUE - 300000)
            val value = Session(UUID.randomUUID().toString(), proof, request, now + 300000)
            current = value; nonce = request.nonce; open = true; state = "receiving"; save()
            val checkActive = { requireActive(value); active() }
            bytes = if (receive != null) receive.invoke(request, checkActive) else {
                val output = ByteArray(request.size); var offset = 0
                try {
                    FileShareTransfer.copy(request.transfer, request.nonce, request.size, request.sha256, object : OutputStream() {
                        override fun write(value: Int) = error("Chunk writes only")
                        override fun write(buffer: ByteArray, start: Int, length: Int) {
                            checkActive(); require(length >= 0 && offset <= output.size - length)
                            System.arraycopy(buffer, start, output, offset, length); offset += length
                        }
                    }, Runnable { checkActive() })
                    check(offset == output.size); output
                } catch (failure: Exception) { output.fill(0); throw failure }
            }
            checkActive(); require(bytes.size == request.size)
            val pcm = FactoryAudioPcm.parseOwned(bytes)
            check(verify(callingPackage) == proof); checkActive()
            value.pcm = pcm; bytes = null; state = "review"; save(); return value
        } catch (failure: Exception) {
            current?.revoke(); if (open) { state = "outcome_unknown"; runCatching { save() } }
            throw failure
        } finally { bytes?.fill(0); busy = false; if (!open && !broken) { lease?.close(); lease = null; FactoryInteractionAdmission.release(this) } }
    }
    fun sourceVerifier(value: Session): FileShareTransfer.HostVerifier {
        worker(); requireActive(value)
        val uid = packageManager.getApplicationInfo(value.proof.appId, 0).uid
        check(packageManager.getPackagesForUid(uid)?.toList() == listOf(value.proof.appId))
        return FileShareTransfer.HostVerifier { caller -> caller == uid }
    }
    fun requireActive(value: Session) {
        check(current === value && open && !broken && !value.revoked.get() && SystemClock.elapsedRealtime() < value.expiresAt)
    }
    /** Worker revalidates durable evidence; main will independently recheck volatile authority before sound. */
    fun preparePlay(value: Session, human: () -> Unit) {
        worker(); requireActive(value); check(state == "review" && !value.attempted)
        human(); lease!!.requireHumanUiInteraction(); check(verify(value.proof.appId) == value.proof)
        requireActive(value); human(); lease!!.requireHumanUiInteraction()
        check(value.registration != null && !value.registration!!.isRevoked)
        value.attempted = true; state = "play_pending"; save()
    }
    fun verifyReady(value: Session, human: () -> Unit) {
        worker(); requireActive(value); human(); lease!!.requireHumanUiInteraction()
        check(state == "play_pending" && value.attempted && verify(value.proof.appId) == value.proof)
        requireActive(value); human(); check(value.registration?.isRevoked == false)
    }
    fun close(value: Session?, recovery: Boolean, human: () -> Unit): Intent? {
        worker(); check(!busy && !restoring); human(); lease?.requireHumanUiInteraction()
        if (recovery) check(needsRecovery()) else { check(value != null && current === value && open && !broken); check(!value.revoked.get()) }
        val owned = current
        check(owned?.preparing?.get() != true && owned?.playerClean?.get() != false) { "Native audio cleanup remains unconfirmed" }
        owned?.revoke(); owned?.registration?.close(); owned?.registration = null
        owned?.pcm?.bytes?.fill(0); owned?.pcm = null
        human(); lease?.requireHumanUiInteraction()
        val result = if (!recovery && owned != null) Intent().putExtra("nonce", nonce).putExtra("playbackAttempted", owned.attempted).putExtra("audibilityConfirmed", false) else null
        state = if (recovery) "closed_outcome_unknown" else "closed"; open = false
        if (!nonce.matches(Regex("[a-f0-9]{64}"))) nonce = ""
        save(); broken = false; release(); return result
    }
    private fun release() { current = null; lease?.close(); lease = null; FactoryInteractionAdmission.release(this) }
    companion object {
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        private val OPEN_STATES = setOf("receiving", "review", "play_pending", "outcome_unknown")
        private val CLOSED_STATES = setOf("closed", "closed_outcome_unknown")
        internal val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
        private var recoveryToken: String? = null
        @Synchronized fun recoveryIntent(context: Context): Intent {
            recoveryToken = UUID.randomUUID().toString()
            return Intent(context, FactoryAudioActivity::class.java).putExtra("nativeRecoveryToken", recoveryToken)
        }
        @Synchronized fun consumeRecoveryToken(intent: Intent): Boolean {
            val token = intent.getStringExtra("nativeRecoveryToken")
            if (token == null || token != recoveryToken || intent.extras?.keySet() != setOf("nativeRecoveryToken") || intent.data != null || intent.clipData != null || intent.selector != null || intent.flags and GRANT_FLAGS != 0) return false
            recoveryToken = null; return true
        }
        @Volatile private var instance: FactoryAudioCoordinator? = null
        fun get(context: Context): FactoryAudioCoordinator = instance ?: synchronized(this) {
            instance ?: FactoryAudioCoordinator(context.applicationContext).also {
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
