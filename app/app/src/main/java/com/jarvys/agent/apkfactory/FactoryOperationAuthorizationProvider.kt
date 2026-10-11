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
import com.jarvys.factory.runtime.NativeOperationAuthorization
import com.jarvys.factory.runtime.NativeOperationRequest
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

/** Authenticated, bounded transport for the existing durable FactoryOperationPolicy. */
open class FactoryOperationAuthorizationProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // Capture the kernel caller before dispatching any worker or clearing Binder identity.
        val uid = Binder.getCallingUid()
        val caller = callingPackage ?: throw SecurityException("Missing native caller")
        val app = requireNotNull(context).applicationContext
        if (method == METHOD_GUARD_STATUS) return peerGuardStatus(app, caller, uid, arg, extras)
        require(method == METHOD_ACQUIRE && arg == null)
        require(extras != null && extras.keySet() == setOf("protocolVersion", "operation", "nonce", "details") && !extras.hasFileDescriptors())
        @Suppress("DEPRECATION") val version = extras.get("protocolVersion")
        @Suppress("DEPRECATION") val operation = extras.get("operation")
        @Suppress("DEPRECATION") val nonce = extras.get("nonce")
        @Suppress("DEPRECATION") val details = extras.get("details")
        require(version is Int && version == 1 && operation is String && nonce is String && nonce.matches(HEX) && details is String)
        requireCaller(app, caller, uid)
        val request = NativeOperationRequest.parse(operation, details)
        val selected = FactoryOperationPolicy.Operation.entries.singleOrNull { it.id == request.operation }
            ?: throw SecurityException("Unsupported native operation")
        val cancelled = AtomicBoolean()
        val completed = AtomicReference<Session?>()
        val work = WORKER.submit<Session> {
            var grant: FactoryOperationPolicy.Grant? = null
            var session: Session? = null
            var trust: AutoCloseable? = null
            try {
                check(!cancelled.get())
                requireCaller(app, caller, uid)
                val before = sourceStamp(app, caller, uid)
                val proof = verifiedCaller(app, caller, setOf(request.capability))
                require(proof.appId == caller)
                val stamp = sourceStamp(app, caller, uid)
                require(stamp == before && stamp.certificate == proof.certificate && stamp.version == proof.version)
                val authority = FactoryDocumentCoordinator.captureTrustedAuthority(app, proof, setOf(request.capability))
                trust = authority
                check(!cancelled.get())
                val binding = request.binding(nonce)
                val policyBinding = request.binding(nonce, FactoryOperationPolicy.binding(proof.appId, proof.certificate, proof.apk, proof.version.toString(), proof.record))
                grant = operationPolicy(app).acquire(FactoryOperationPolicy.Subject.installed(proof), selected, policyBinding)
                check(!cancelled.get())
                val value = Session(app, uid, caller, nonce, request, proof, grant, binding, stamp,
                    { authority.requireCurrent() }, { authority.close() })
                session = value
                register(value)
                value.attachRevocation()
                value.attachTrustRevocation(authority.onRevoked { MAIN.post { value.close() } })
                completed.set(value)
                check(!cancelled.get())
                value.requireCurrent()
                value
            } catch (failure: Throwable) {
                session?.close() ?: run { grant?.close(); trust?.close() }
                throw failure
            }
        }
        try {
            val value = work.get(30, TimeUnit.SECONDS)
            check(!cancelled.get())
            return Bundle().apply {
                putInt("protocolVersion", 1); putString("nonce", value.nonce); putString("binding", value.binding)
                putBinder("lease", value.lease); putInt("status", if (value.closed.get()) NativeOperationAuthorization.TERMINAL
                    else if (value.grant.requiresApproval) NativeOperationAuthorization.PENDING else NativeOperationAuthorization.APPROVED)
                if (value.grant.requiresApproval) putString("reviewToken", value.token)
            }
        } catch (failure: Throwable) {
            // A worker finishing after timeout cannot publish an orphan grant or review token.
            cancelled.set(true); completed.get()?.close(); work.cancel(true)
            throw SecurityException("Native operation authorization unavailable", failure)
        }
    }

    internal open fun verifiedCaller(context: Context, caller: String, capabilities: Set<String>) =
        FactoryDocumentCoordinator.verifyInstalled(context, caller, capabilities)
    internal open fun operationPolicy(context: Context) = FactoryOperationPolicy.get(context)

    // This provider is not a document store, file broker, or generic query interface.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = throw SecurityException("Unsupported")
    override fun getType(uri: Uri): String = throw SecurityException("Unsupported")
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw SecurityException("Unsupported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Unsupported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Unsupported")
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor = throw SecurityException("Unsupported")

    /**
     * Device automation coordinates only with the other installed host's live UI guard.
     * This endpoint grants no Factory operation and reads or changes no durable policy.
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
        val grant: FactoryOperationPolicy.Grant, val binding: String, private val stamp: SourceStamp,
        private val trusted: (FactoryDocumentCoordinator.Proof) -> Unit,
        private val closeTrust: () -> Unit = {},
    ) : AutoCloseable {
        val token = randomToken()
        val deadline = SystemClock.elapsedRealtime() + LEASE_MS
        val closed = AtomicBoolean()
        val claimed = AtomicBoolean()
        private val subscription = AtomicReference<AutoCloseable?>()
        private val trustSubscription = AtomicReference<AutoCloseable?>()
        private val expired = Runnable { if (!lease.isConnected || grant.requiresApproval) close() }
        val lease = NativeOperationAuthorization(nonce, binding, deadline,
            { callerUid -> callerUid == uid && runCatching { requireCaller(context, caller, uid); true }.getOrDefault(false) },
            object : NativeOperationAuthorization.Authority {
                override fun status(): Int {
                    requireCurrent()
                    return if (grant.requiresApproval) NativeOperationAuthorization.PENDING else NativeOperationAuthorization.APPROVED
                }
                override fun checkBeforeEffect() { requireCurrent(); grant.checkBeforeEffect(); requireCurrent() }
            }, Runnable { finish() })
        fun attachRevocation() {
            val value = grant.onRevoked { close() }
            subscription.set(value)
            if (closed.get()) subscription.getAndSet(null)?.close()
            else {
                MAIN.postDelayed(expired, maxOf(0, deadline - SystemClock.elapsedRealtime()))
                if (closed.get()) MAIN.removeCallbacks(expired)
            }
        }
        fun attachTrustRevocation(value: AutoCloseable) {
            trustSubscription.set(value)
            if (closed.get()) trustSubscription.getAndSet(null)?.close()
        }
        fun requireCurrent() {
            check(!closed.get() && ((!lease.isConnected || grant.requiresApproval).not() || SystemClock.elapsedRealtime() < deadline))
            grant.requireCurrent()
            check(sourceStamp(context, caller, uid) == stamp) { "Installed native caller changed" }
            trusted(proof)
            grant.requireCurrent()
            check(!closed.get() && ((!lease.isConnected || grant.requiresApproval).not() || SystemClock.elapsedRealtime() < deadline))
        }
        fun approve(human: () -> Unit) {
            val pending = grant.requiresApproval
            try {
                check(!closed.get())
                grant.approveOnce {
                    check(!closed.get() && SystemClock.elapsedRealtime() < deadline)
                    trusted(proof); human()
                    check(!closed.get() && SystemClock.elapsedRealtime() < deadline)
                }
                // A delayed main callback cannot turn an expired pending review into an unbounded lease.
                if (pending) check(SystemClock.elapsedRealtime() < deadline)
            } catch (failure: Throwable) { close(); throw failure }
        }
        override fun close() { lease.close() }
        private fun finish() {
            if (!closed.compareAndSet(false, true)) return
            MAIN.removeCallbacks(expired)
            subscription.getAndSet(null)?.close(); trustSubscription.getAndSet(null)?.close()
            try { grant.revoke(); closeTrust() }
            finally { synchronized(REGISTRY) { if (REGISTRY[token] === this) REGISTRY.remove(token) } }
        }
    }
    internal data class SourceStamp(val uid: Int, val version: Long, val updated: Long, val path: String, val certificate: String)

    companion object {
        const val AUTHORITY_SUFFIX = ".factory.operations"
        const val METHOD_ACQUIRE = "acquire"
        const val METHOD_GUARD_STATUS = "guardStatus"
        const val ACTIVITY = "com.jarvys.agent.apkfactory.FactoryOperationApprovalActivity"
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
            check(replay !in SEEN && value.token !in REGISTRY && REGISTRY.values.none { it.caller == value.caller && it.nonce == value.nonce }) { "Replayed native operation request" }
            SEEN[replay] = value.deadline; REGISTRY[value.token] = value
        }
        /** Wrong caller/nonce/token never consumes another requester's review opportunity. */
        internal fun claim(context: Context, caller: String, token: String, nonce: String): Session {
            require(token.matches(HEX) && nonce.matches(HEX))
            val value = synchronized(REGISTRY) { REGISTRY[token] } ?: error("Unknown native review")
            require(value.caller == caller && value.nonce == nonce)
            requireCaller(context, caller, value.uid)
            value.requireCurrent()
            check(value.grant.requiresApproval && value.claimed.compareAndSet(false, true)) { "Native review already consumed" }
            return value
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
