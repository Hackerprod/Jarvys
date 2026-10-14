package com.jarvys.agent.apkfactory

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.contract.CapabilityCatalog
import com.jarvys.factory.runtime.NativeBackgroundAuthorization
import com.jarvys.factory.runtime.NativeOperationAuthorization
import com.jarvys.factory.runtime.NativeOperationRequest
import com.jarvys.factory.runtime.ResourceBudgetsJson
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Authenticated, bounded transport that rechecks the installed app and signing evidence. */
open class FactoryOperationAuthorizationProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // Capture the kernel caller before dispatching any worker or clearing Binder identity.
        val uid = Binder.getCallingUid()
        val caller = callingPackage ?: throw SecurityException("Missing native caller")
        val app = requireNotNull(context).applicationContext
        if (method == METHOD_GUARD_STATUS) return peerGuardStatus(app, caller, uid, arg, extras)
        if (method == METHOD_DESCRIBE_INSTALLED) return describeInstalled(app, caller, uid, arg, extras)
        val background = method == METHOD_ACQUIRE_BACKGROUND
        require((method == METHOD_ACQUIRE || background) && arg == null)
        val finiteHttpJob = background && extras?.containsKey("leaseKind") == true
        if (finiteHttpJob) require(extras?.getString("leaseKind") == "finite_http_job")
        val expectedKeys = setOf("protocolVersion", "operation", "nonce", "details") +
            (if (background) setOf("deadlineElapsedMs", "expectedSource") else emptySet()) +
            (if (finiteHttpJob) setOf("leaseKind") else emptySet())
        require(extras != null && extras.keySet() == expectedKeys && !extras.hasFileDescriptors())
        val started = SystemClock.elapsedRealtime()
        var backgroundDeadline = if (background) {
            @Suppress("DEPRECATION") val rawDeadline = extras.get("deadlineElapsedMs")
            require(rawDeadline is Long && rawDeadline > started)
            minOf(rawDeadline, started + if (finiteHttpJob) 305_000L else 8_000L)
        } else null
        val acquisitionDeadline = if (backgroundDeadline != null) minOf(backgroundDeadline, started + 2_000L) else started + 30_000L
        val expectedSource = if (background) {
            @Suppress("DEPRECATION") val rawSource = extras.get("expectedSource")
            require(rawSource is String)
            NativeBackgroundAuthorization.SourceIdentity.parse(rawSource).also { require(it.appId == caller) }
        } else null
        @Suppress("DEPRECATION") val version = extras.get("protocolVersion")
        @Suppress("DEPRECATION") val operation = extras.get("operation")
        @Suppress("DEPRECATION") val nonce = extras.get("nonce")
        @Suppress("DEPRECATION") val details = extras.get("details")
        require(version is Int && version == 1 && operation is String && nonce is String && nonce.matches(HEX) && details is String)
        requireCaller(app, caller, uid)
        val request = NativeOperationRequest.parse(operation, details)
        if (finiteHttpJob) {
            require(request.operation == "jobs.enqueueHttp")
            val disclosure = request.details()
            require(disclosure.getString("executionMode") == "scheduled" && !disclosure.getBoolean("replayPossible"))
            val timeout = disclosure.getJSONObject("request").getLong("timeoutMs")
            require(timeout in 1L..300_000L)
            backgroundDeadline = minOf(requireNotNull(backgroundDeadline), started + timeout + 5_000L)
        }
        val leaseDeadline = backgroundDeadline
        require(CapabilityCatalog.METHODS.containsKey(request.operation))
        val cancelled = AtomicBoolean()
        val completed = AtomicReference<Session?>()
        val work = WORKER.submit<Session> {
            var session: Session? = null
            var trust: AutoCloseable? = null
            try {
                check(!cancelled.get())
                requireCaller(app, caller, uid)
                val before = sourceStamp(app, caller, uid)
                val requiredCapabilities = setOf(request.capability) + when (request.operation) {
                    "secrets.request", "jobs.enqueueHttp" -> setOf("network")
                    "network.upload", "network.download" -> setOf("documents")
                    else -> emptySet()
                }
                val proof = verifiedCaller(app, caller, requiredCapabilities)
                require(proof.appId == caller)
                if (expectedSource != null) require(proof.appId == expectedSource.appId && proof.certificate == expectedSource.certificateSha256 &&
                    proof.apk == expectedSource.apkSha256 && proof.record == expectedSource.recordSha256 && proof.version == expectedSource.versionCode) { "Installed source changed after description" }
                val stamp = sourceStamp(app, caller, uid)
                require(stamp == before && stamp.certificate == proof.certificate && stamp.version == proof.version)
                val authority = FactoryDocumentCoordinator.captureTrustedAuthority(app, proof, requiredCapabilities)
                trust = authority
                check(!cancelled.get())
                val binding = if (expectedSource == null) request.binding(nonce) else request.binding(nonce, expectedSource.bindingEvidence())
                check(SystemClock.elapsedRealtime() < acquisitionDeadline)
                check(!cancelled.get())
                val value = Session(app, uid, caller, nonce, request, proof, binding, stamp,
                    { authority.requireCurrent() }, { authority.close() }, leaseDeadline)
                session = value
                register(value)
                value.attachDeadline()
                value.attachTrustRevocation(authority.onRevoked { MAIN.post { value.close() } })
                completed.set(value)
                check(!cancelled.get())
                value.requireCurrent()
                value
            } catch (failure: Throwable) {
                session?.close() ?: trust?.close()
                throw failure
            }
        }
        try {
            val value = work.get(maxOf(0L, acquisitionDeadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
            check(!cancelled.get() && SystemClock.elapsedRealtime() < acquisitionDeadline)
            return Bundle().apply {
                putInt("protocolVersion", 1); putString("nonce", value.nonce); putString("binding", value.binding)
                putBinder("lease", value.lease); putInt("status", if (value.closed.get()) NativeOperationAuthorization.TERMINAL else NativeOperationAuthorization.ACTIVE)
            }
        } catch (failure: Throwable) {
            // A worker finishing after timeout cannot publish an orphan authorization lease.
            cancelled.set(true); completed.get()?.close(); work.cancel(true)
            if (work is Runnable) WORKER.remove(work)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw SecurityException("Native operation authorization unavailable", failure)
        }
    }

    /**
     * Point-in-time installed source metadata for a cold receiver. Each effect still needs a fresh live lease.
     */
    private fun describeInstalled(app: Context, caller: String, uid: Int, arg: String?, extras: Bundle?): Bundle {
        require(arg == null && extras != null && !extras.hasFileDescriptors() &&
            extras.keySet() == setOf("protocolVersion", "nonce", "capability", "deadlineElapsedMs"))
        @Suppress("DEPRECATION") val version = extras.get("protocolVersion")
        @Suppress("DEPRECATION") val nonce = extras.get("nonce")
        @Suppress("DEPRECATION") val capability = extras.get("capability")
        @Suppress("DEPRECATION") val rawDeadline = extras.get("deadlineElapsedMs")
        val started = SystemClock.elapsedRealtime()
        require(version is Int && version == 1 && nonce is String && nonce.matches(HEX) &&
            capability is String && capability in CapabilityCatalog.SUPPORTED && rawDeadline is Long && rawDeadline > started)
        val deadline = minOf(rawDeadline, started + 2_000L)
        requireCaller(app, caller, uid)
        val cancelled = AtomicBoolean()
        fun requireCurrent() {
            check(!cancelled.get() && !Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline)
        }
        val work = try { WORKER.submit<Bundle> {
            requireCurrent()
            requireCaller(app, caller, uid)
            val before = sourceStamp(app, caller, uid)
            val capabilities = setOf(capability)
            val proof = verifiedCaller(app, caller, capabilities)
            requireCurrent()
            require(proof.appId == caller && proof.certificate.matches(HEX) && proof.apk.matches(HEX) &&
                proof.record.matches(HEX) && proof.version > 0)
            val stamp = sourceStamp(app, caller, uid)
            require(stamp == before && stamp.certificate == proof.certificate && stamp.version == proof.version)
            // Capture re-authenticates the signed receipt and budgets under the signing identity
            // lock. The temporary token is always closed, including interrupted or late workers.
            FactoryDocumentCoordinator.captureTrustedAuthority(app, proof, capabilities).use { authority ->
                requireCurrent()
                authority.requireCurrent()
                val result = Bundle().apply {
                    putInt("protocolVersion", 1); putString("nonce", nonce); putString("capability", capability)
                    putLong("deadlineElapsedMs", deadline); putString("appId", proof.appId)
                    putString("certificateSha256", proof.certificate); putString("apkSha256", proof.apk)
                    putString("recordSha256", proof.record); putLong("versionCode", proof.version)
                    putString("resourceBudgets", ResourceBudgetsJson.toJson(proof.resources).toString())
                }
                check(sourceStamp(app, caller, uid) == stamp) { "Installed source changed" }
                authority.requireCurrent()
                requireCurrent()
                result
            }
        } } catch (failure: Exception) {
            cancelled.set(true)
            throw SecurityException("Installed source verification unavailable", failure)
        }
        try {
            val result = work.get(maxOf(0L, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
            requireCurrent()
            return result
        } catch (failure: Throwable) {
            cancelled.set(true); work.cancel(true)
            if (work is Runnable) WORKER.remove(work)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw SecurityException("Installed source verification unavailable", failure)
        }
    }

    internal open fun verifiedCaller(context: Context, caller: String, capabilities: Set<String>) =
        FactoryDocumentCoordinator.verifyInstalled(context, caller, capabilities)

    // This provider is not a document store, file broker, or generic query interface.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = throw SecurityException("Unsupported")
    override fun getType(uri: Uri): String = throw SecurityException("Unsupported")
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw SecurityException("Unsupported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Unsupported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Unsupported")
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor = throw SecurityException("Unsupported")

    /**
     * Device automation coordinates only with the other installed host's live UI guard.
     * This endpoint performs no Factory operation and reads or changes no durable preferences.
     */
    internal class PeerGuardSession(private val context: Context, private val caller: String,
                                    private val uid: Int, private val stamp: SourceStamp) : Binder(), AutoCloseable {
        private val closed = AtomicBoolean()
        private val nextAction = AtomicLong()
        private val actions = ConcurrentHashMap<Long, PeerAction>()

        private inner class PeerAction(val id: Long, val active: AutoCloseable, val client: IBinder) : AutoCloseable {
            val ended = AtomicBoolean()
            val death = IBinder.DeathRecipient { close() }
            override fun close() {
                if (!ended.compareAndSet(false, true)) return
                actions.remove(id, this)
                try { active.close() } finally {
                    PEER_ACTIONS.decrementAndGet()
                    runCatching { client.unlinkToDeath(death, 0) }
                }
            }
        }

        private fun requireSource() {
            check(!closed.get())
            require(Binder.getCallingUid() == uid)
            requirePeerCaller(context, caller, uid)
            check(peerSourceStamp(context, caller, uid) == stamp) { "Peer automation package changed" }
        }

        private fun begin(epoch: Long, client: IBinder): Long {
            val admitted = PEER_ACTIONS.incrementAndGet()
            if (admitted > MAX_PEER_ACTIONS) { PEER_ACTIONS.decrementAndGet(); error("Peer automation capacity reached") }
            val active = try { MemoryUiAutomationGuard.localBeginAsyncAction(epoch) }
                catch (failure: Throwable) { PEER_ACTIONS.decrementAndGet(); throw failure }
            val action = PeerAction(nextAction.incrementAndGet(), active, client)
            actions[action.id] = action
            try {
                check(!closed.get())
                // Never hold the UI guard's monitor across Binder or native dispatch. A fresh
                // death recipient for each action also makes concurrent END/BEGIN unambiguous.
                client.linkToDeath(action.death, 0)
                if (action.ended.get() || closed.get()) {
                    runCatching { client.unlinkToDeath(action.death, 0) }
                    error("Peer automation caller ended")
                }
                return action.id
            } catch (failure: Throwable) { action.close(); throw failure }
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (flags != 0 || reply == null || data.dataSize() > 1024 || data.hasFileDescriptors() ||
                code !in GUARD_CHECK..GUARD_END) return false
            return try {
                require(Binder.getCallingUid() == uid)
                // END only releases this connection's own action. Metadata unavailability must
                // not strand an already completed gesture, nor may CHECK prematurely release one.
                if (code != GUARD_END) requireSource()
                data.enforceInterface(GUARD_INTERFACE)
                require(data.readString() == GUARD_INSTANCE)
                val value = data.readLong()
                val client = if (code == GUARD_BEGIN) data.readStrongBinder() else null
                require(data.dataAvail() == 0)
                when (code) {
                    GUARD_CHECK -> {
                        reply.writeNoException()
                        reply.writeInt(if (MemoryUiAutomationGuard.localIsAutomationEpochValid(value)) 1 else 0)
                    }
                    GUARD_BEGIN -> {
                        val id = begin(value, requireNotNull(client))
                        reply.writeNoException(); reply.writeLong(id)
                    }
                    GUARD_END -> {
                        require(value > 0)
                        actions[value]?.close()
                        reply.writeNoException(); reply.writeInt(1)
                    }
                }
                true
            } catch (_: Exception) { false }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            actions.values.toList().forEach { it.close() }
        }
    }

    internal class Session(
        private val context: Context, val uid: Int, val caller: String, val nonce: String,
        val request: NativeOperationRequest, val proof: FactoryDocumentCoordinator.Proof,
        val binding: String, private val stamp: SourceStamp,
        private val trusted: (FactoryDocumentCoordinator.Proof) -> Unit,
        private val closeTrust: () -> Unit = {},
        private val hardDeadline: Long? = null,
    ) : AutoCloseable {
        val deadline = hardDeadline ?: (SystemClock.elapsedRealtime() + LEASE_MS)
        val closed = AtomicBoolean()
        private val trustSubscription = AtomicReference<AutoCloseable?>()
        private val expired = Runnable { if (hardDeadline != null || !lease.isConnected) close() }
        val lease = NativeOperationAuthorization(nonce, binding, deadline,
            { callerUid -> callerUid == uid && runCatching { requireCaller(context, caller, uid); true }.getOrDefault(false) },
            object : NativeOperationAuthorization.Authority {
                override fun status(): Int {
                    requireCurrent()
                    return NativeOperationAuthorization.ACTIVE
                }
                override fun checkBeforeEffect() { requireCurrent() }
            }, Runnable { finish() })
        fun attachDeadline() {
            MAIN.postDelayed(expired, maxOf(0, deadline - SystemClock.elapsedRealtime()))
            if (closed.get()) MAIN.removeCallbacks(expired)
        }
        fun attachTrustRevocation(value: AutoCloseable) {
            trustSubscription.set(value)
            if (closed.get()) trustSubscription.getAndSet(null)?.close()
        }
        fun requireCurrent() {
            check(!closed.get() && SystemClock.elapsedRealtime() < deadline)
            check(sourceStamp(context, caller, uid) == stamp) { "Installed native caller changed" }
            trusted(proof)
            check(!closed.get() && SystemClock.elapsedRealtime() < deadline)
        }
        override fun close() { lease.close() }
        private fun finish() {
            if (!closed.compareAndSet(false, true)) return
            MAIN.removeCallbacks(expired)
            trustSubscription.getAndSet(null)?.close()
            try { closeTrust() }
            finally { synchronized(REGISTRY) { REGISTRY.values.remove(this) } }
        }
    }
    internal data class SourceStamp(val uid: Int, val version: Long, val updated: Long, val path: String, val certificate: String)

    companion object {
        const val AUTHORITY_SUFFIX = ".factory.operations"
        const val METHOD_ACQUIRE = "acquire"
        const val METHOD_ACQUIRE_BACKGROUND = "acquire_background"
        const val METHOD_DESCRIBE_INSTALLED = "describe_installed"
        const val METHOD_GUARD_STATUS = "guardStatus"
        internal const val GUARD_INTERFACE = "com.jarvys.agent.PeerUiAutomationGuard.v1"
        internal const val GUARD_CHECK = IBinder.FIRST_CALL_TRANSACTION
        internal const val GUARD_BEGIN = GUARD_CHECK + 1
        internal const val GUARD_END = GUARD_CHECK + 2
        private const val MAX_PEER_ACTIONS = 64
        private const val LEASE_MS = 300_000L
        private val HEX = Regex("[a-f0-9]{64}")
        private val RANDOM = SecureRandom()
        private val GUARD_INSTANCE = randomToken()
        private val PEER_ACTIONS = AtomicInteger()
        private val MAIN = Handler(Looper.getMainLooper())
        internal val WORKER = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8)).apply { allowCoreThreadTimeOut(true) }
        private val REGISTRY = linkedMapOf<String, Session>()
        private val SEEN = linkedMapOf<String, Long>()
        private fun randomToken() = ByteArray(32).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }
        internal fun peerGuardStatus(context: Context, caller: String, uid: Int, arg: String?, extras: Bundle?): Bundle {
            require(arg == null && extras != null && !extras.hasFileDescriptors() && extras.keySet() == setOf("protocolVersion"))
            @Suppress("DEPRECATION") val version = extras.get("protocolVersion")
            require(version is Int && version == 1)
            requirePeerCaller(context, caller, uid)
            val stamp = peerSourceStamp(context, caller, uid)
            val snapshot = MemoryUiAutomationGuard.localSnapshot()
            return Bundle().apply {
                putInt("protocolVersion", 1); putString("instance", GUARD_INSTANCE)
                putLong("epoch", snapshot[0]); putBoolean("protected", snapshot[1] != 0L)
                putBinder("guard", PeerGuardSession(context.applicationContext, caller, uid, stamp))
            }
        }
        private fun requirePeerCaller(context: Context, caller: String, uid: Int) {
            val hosts = setOf("com.jarvys.agent", "com.jarvys.agent.recoverytest")
            require(context.packageName in hosts && caller in hosts && caller != context.packageName)
            requireCaller(context, caller, uid)
        }
        @Suppress("DEPRECATION")
        private fun peerSourceStamp(context: Context, caller: String, uid: Int): SourceStamp {
            val pm = context.packageManager
            val info = pm.getPackageInfo(caller, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val app = requireNotNull(info.applicationInfo)
            require(info.packageName == caller && app.packageName == caller && app.uid == uid && app.enabled && info.sharedUserId == null)
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            require(signatures?.size == 1)
            val certificate = MessageDigest.getInstance("SHA-256").digest(signatures!![0].toByteArray()).joinToString("") { "%02x".format(it) }
            return SourceStamp(uid, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(), info.lastUpdateTime, app.sourceDir, certificate)
        }
        internal fun register(value: Session) = synchronized(REGISTRY) {
            val now = SystemClock.elapsedRealtime()
            SEEN.entries.removeAll { it.value <= now }
            check(REGISTRY.size < 32 && REGISTRY.values.count { it.uid == value.uid } < 4 && SEEN.size < 256) { "Native authorization capacity reached" }
            val replay = "${value.caller}:${value.nonce}"
            check(replay !in SEEN && value.nonce !in REGISTRY && REGISTRY.values.none { it.caller == value.caller && it.nonce == value.nonce }) { "Replayed native operation request" }
            SEEN[replay] = value.deadline; REGISTRY[value.nonce] = value
        }
        internal fun requireCaller(context: Context, caller: String, uid: Int) {
            require(caller != context.packageName && context.packageManager.getPackagesForUid(uid)?.toSet() == setOf(caller)) { "Unknown or shared native caller UID" }
            require(context.packageManager.getApplicationInfo(caller, 0).uid == uid)
        }
        @Suppress("DEPRECATION")
        internal fun sourceStamp(context: Context, caller: String, uid: Int): SourceStamp {
            requireCaller(context, caller, uid)
            val pm = context.packageManager
            val info = pm.getPackageInfo(caller, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            FactoryDocumentCoordinator.verifyPackageShape(caller, info, pm.getPackagesForUid(uid)?.toSet())
            val app = requireNotNull(info.applicationInfo); require(app.uid == uid)
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            require(signatures?.size == 1)
            val certificate = MessageDigest.getInstance("SHA-256").digest(signatures!![0].toByteArray()).joinToString("") { "%02x".format(it) }
            return SourceStamp(uid, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(), info.lastUpdateTime, app.sourceDir, certificate)
        }
    }
}
