package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import com.jarvys.factory.runtime.AudioPlaybackControl
import com.jarvys.factory.runtime.FileShareTransfer
import com.jarvys.factory.runtime.FileShareSnapshot
import com.jarvys.factory.runtime.StreamingFileShareTransfer
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One authenticated foreground audio interaction. Durable state never restores playback authority. */
internal class FactoryAudioCoordinator(
    context: Context,
    private val verify: (String) -> FactoryDocumentCoordinator.Proof = {
        FactoryDocumentCoordinator.verifyInstalled(context, it, setOf("documents", "audio"))
    },
    private val receive: ((Request, () -> Unit) -> ByteArray)? = null,
) {
    data class Request(val nonce: String, val size: Long, val sha256: String, val transfer: IBinder, val control: IBinder, val protocolVersion: Int = 1) {
        constructor(nonce: String, size: Int, sha256: String, transfer: IBinder, control: IBinder) : this(nonce, size.toLong(), sha256, transfer, control, 1)
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.component != null && intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.flags and GRANT_FLAGS == 0)
                val extras = intent.extras ?: error("Missing audio request")
                require(extras.keySet() == setOf("protocolVersion", "nonce", "size", "sha256", "transfer", "control"))
                val version = extras.get("protocolVersion"); require(version is Int && version in 1..2)
                val rawSize = extras.get("size")
                val size = if (version == 1) {
                    require(rawSize is Int && rawSize in 46..FactoryAudioPcm.MAX_BYTES); rawSize.toLong()
                } else {
                    require(rawSize is Long && rawSize in 46..FactoryAudioPcm.MAX_STREAM_BYTES); rawSize
                }
                val nonce = extras.get("nonce"); require(nonce is String && nonce.matches(Regex("[a-f0-9]{64}")))
                val hash = extras.get("sha256"); require(hash is String && hash.matches(Regex("[a-f0-9]{64}")))
                val transfer = extras.get("transfer"); val control = extras.get("control")
                require(transfer is IBinder && control is IBinder)
                return Request(nonce, size, hash, transfer, control, version)
            }
        }
    }
    class Session(val token: String, val proof: FactoryDocumentCoordinator.Proof, val request: Request, val expiresAt: Long,
        val setupExpiresAt: Long = expiresAt - proof.resources.audio.durationMs) {
        val revoked = AtomicBoolean(false)
        val playerClean = AtomicBoolean(true)
        val preparing = AtomicBoolean(false)
        @Volatile var pcm: FactoryAudioPcm? = null
        @Volatile var snapshot: FileShareSnapshot? = null
        @Volatile var registration: AudioPlaybackControl.Registration? = null
        @Volatile var player: FactoryAudioPlayer? = null
        @Volatile var attempted = false
        /** No disk, worker, package manager or coordinator monitor on urgent revocation. */
        fun revoke() { revoked.set(true); registration?.close() }
    }
    private val application = context.applicationContext
    private val packageManager = application.packageManager
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-audio/interaction.json"))
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
    private fun worker() { check(Looper.myLooper() != Looper.getMainLooper()) { "Audio I/O must run on the bounded worker" } }
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
    fun begin(callingPackage: String?, request: Request, active: () -> Unit): Session {
        worker(); require(!callingPackage.isNullOrBlank()); check(canBegin()); require(request.nonce != nonce)
        busy = true
        var bytes: ByteArray? = null
        try {
            val proof = verify(callingPackage); check(proof.appId == callingPackage); active()
            // The authenticated artifact is the only source of developer-selected limits.
            require(request.protocolVersion in 1..2 && request.size in 46..FactoryAudioPcm.MAX_STREAM_BYTES && request.size <= proof.resources.audio.sourceBytes)
            require(request.protocolVersion != 1 || request.size <= FactoryAudioPcm.MAX_BYTES)
            var replacingActiveSession = false
            if (open) {
                check(!broken)
                val previous = current
                check(previous?.preparing?.get() != true)
                previous?.revoke(); previous?.registration?.close(); previous?.registration = null
                previous?.let { interrupted ->
                    interrupted.player?.stop()?.let { cleanup ->
                        interrupted.playerClean.set(cleanup.cleanupConfirmed)
                        check(cleanup.cleanupConfirmed) { "Interrupted audio playback cleanup is not complete" }
                    }
                }
                previous?.pcm?.close(); previous?.pcm = null
                previous?.snapshot?.close(); previous?.snapshot = null
                state = "closed_outcome_unknown"; open = false; save(); current = null
                replacingActiveSession = previous != null
            }
            if (!replacingActiveSession) FactoryInteractionAdmission.acquire(this)
            val lifetime = proof.resources.audio.setupTimeoutMs + proof.resources.audio.durationMs
            val now = SystemClock.elapsedRealtime(); check(now >= 0 && now <= Long.MAX_VALUE - lifetime)
            val value = Session(UUID.randomUUID().toString(), proof, request, now + lifetime, now + proof.resources.audio.setupTimeoutMs)
            current = value; nonce = request.nonce; open = true; state = "receiving"; save()
            val checkActive = { requirePlaybackStart(value); active() }
            val pcm = if (receive != null) {
                // Legacy injectable ownership seam used by host tests; production always uses disk.
                bytes = receive.invoke(request, checkActive)
                checkActive(); require(bytes.size.toLong() == request.size)
                FactoryAudioPcm.parseOwned(bytes, proof.resources.audio)
            } else {
                val snapshot = FileShareSnapshot(application, FileShareSnapshot.AUDIO_NAMESPACE)
                value.snapshot = snapshot // Retain even an incomplete snapshot until explicit cleanup.
                snapshot.materialize(request.size, StreamingFileShareTransfer.SnapshotWriter { output, stillActive ->
                    StreamingFileShareTransfer.copy(request.transfer, request.nonce, request.size, request.sha256,
                        request.protocolVersion, output, stillActive)
                }, Runnable { checkActive() })
                checkActive(); require(snapshot.size() == request.size && snapshot.sha256() == request.sha256)
                FactoryAudioPcm.parseStream(object : InputStream() {
                    override fun read(): Int = error("Bounded chunk reads only")
                    override fun read(buffer: ByteArray, offset: Int, count: Int) = snapshot.read(buffer, offset, count)
                    override fun close() = snapshot.close()
                }, request.size, proof.resources.audio)
            }
            check(verify(callingPackage) == proof); checkActive()
            value.pcm = pcm; bytes = null; state = "review"; save(); return value
        } catch (failure: Exception) {
            current?.revoke(); if (current != null && open) { state = "outcome_unknown"; runCatching { save() } }
            throw failure
        } finally { bytes?.fill(0); busy = false; if (!open && !broken) FactoryInteractionAdmission.release(this) }
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
    /** Setup may not consume the time reserved for the complete declared clip. */
    fun requirePlaybackStart(value: Session) {
        requireActive(value)
        check(SystemClock.elapsedRealtime() < value.setupExpiresAt)
    }
    /** Worker revalidates the exact installed caller before sound. */
    fun preparePlay(value: Session, active: () -> Unit) {
        worker(); requirePlaybackStart(value); check(state == "review" && !value.attempted)
        active(); check(verify(value.proof.appId) == value.proof)
        requirePlaybackStart(value); active()
        check(value.registration != null && !value.registration!!.isRevoked)
        value.attempted = true; state = "play_pending"; save()
    }
    fun verifyReady(value: Session, active: () -> Unit) {
        worker(); requirePlaybackStart(value); active()
        check(state == "play_pending" && value.attempted && verify(value.proof.appId) == value.proof)
        requirePlaybackStart(value); active(); check(value.registration?.isRevoked == false)
    }
    fun close(value: Session?, active: () -> Unit): Intent? {
        worker(); check(!busy && !restoring); active()
        check(value != null && current === value && open && !broken)
        val owned = current
        check(owned?.preparing?.get() != true && owned?.playerClean?.get() != false) { "Native audio cleanup remains unconfirmed" }
        owned?.revoke(); owned?.registration?.close(); owned?.registration = null
        // No feeder may still read this descriptor. Failed closes retain ownership/admission.
        owned?.pcm?.close(); owned?.pcm = null
        owned?.snapshot?.close(); owned?.snapshot = null
        active()
        val result = Intent().putExtra("nonce", nonce).putExtra("playbackAttempted", owned.attempted).putExtra("audibilityConfirmed", false)
        state = "closed"; open = false
        if (!nonce.matches(Regex("[a-f0-9]{64}"))) nonce = ""
        save(); broken = false; release(); return result
    }
    private fun release() { current = null; FactoryInteractionAdmission.release(this) }
    companion object {
        const val SETUP_DURATION_MS = 300000L
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        private val OPEN_STATES = setOf("receiving", "review", "play_pending", "outcome_unknown")
        private val CLOSED_STATES = setOf("closed", "closed_outcome_unknown")
        internal val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
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
