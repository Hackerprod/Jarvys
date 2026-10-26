package com.jarvys.agent.connectors

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.connectors.ConnectionStateCoordinator as Core
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/** Implemented by the real Application; context wrappers must delegate the SAME process handle. */
interface ConnectionAccessProvider { fun connectionAccess(): ConnectionAccess }

/** Local choice only. Never grants OS/OAuth access or substitutes effect admission. */
class ConnectionAccess private constructor(
    private val app: Application?,
    private val initializer: Executor,
    private val portFactory: () -> Port,
) {
    enum class RequestStatus { ACCEPTED, NOT_READY, RETIRED, SUPERSEDED, CONFLICT, FAILED }
    enum class RepairOutcome { SUCCESS, RETRY, BLOCKED }
    class RequestUnavailableException(val status: RequestStatus) : IllegalStateException("Connection request is unavailable")
    class Request internal constructor(
        val opId: String, val stamp: Long, val connectorId: String, val definitionGeneration: String,
        val kind: Core.Kind, val status: RequestStatus,
    ) {
        internal val tokenReady = CompletableFuture<Core.RequestToken>()
        @Volatile internal var token: Core.RequestToken? = null
        @Volatile internal var cancelled = false
        internal var previousOffStamp: Long? = null
        internal var previousOffRequest: Request? = null
        internal var published = false
        internal val publications = mutableListOf<() -> Unit>()
        internal var persisted: CompletableFuture<Core.Result>? = null
        @Volatile internal var lastResult: Core.Result? = null
    }
    data class ChoiceSnapshot(
        val coreView: Core.ReadView?, val ready: Boolean, val hasChoice: Boolean, val selected: Boolean,
        val denied: Boolean, val excluded: Boolean, val stamp: Long, val observation: Core.Observation?,
    )
    private interface Port {
        fun snapshot(): Core.ReadView
        fun bootstrap(): CompletableFuture<Core.BootstrapAttempt>
        fun begin(command: Core.Command): Core.RequestToken
        fun isCurrent(token: Core.RequestToken): Boolean
        fun persist(token: Core.RequestToken): CompletableFuture<Core.Result>
        fun returned(permit: Core.ActivationPermit): CompletableFuture<Core.Result>
        fun failed(permit: Core.ActivationPermit): CompletableFuture<Core.Result>
        fun notStarted(permit: Core.ActivationPermit): CompletableFuture<Core.Result>
        fun abort(token: Core.RequestToken): CompletableFuture<Core.AbortResult>
        fun restore(off: Core.RequestToken, on: Core.RequestToken): CompletableFuture<Core.OffRestoration>
        fun project(): RepairOutcome
    }
    private val lock = Any() // Memory only; never IO, a writer lock, callback, or Registry entry.
    private var port: Port? = null
    private var publishedReady = false
    private var initialization: CompletableFuture<Core.BootstrapAttempt>? = null
    private var lastBootstrap: Core.BootstrapAttempt? = null
    private val latest = mutableMapOf<String, Request>()
    private val stamps = mutableMapOf<String, Long>()
    private val offLedger = mutableMapOf<String, Long>()
    private val offObligations = mutableMapOf<String, Request>()
    private val listeners = mutableSetOf<Runnable>()
    private val projectionLock = Any()
    private val projector = Executors.newSingleThreadExecutor { task -> Thread(task, "JarvysConnectionProjection").apply { isDaemon = true } }
    private var projectionFlight: CompletableFuture<RepairOutcome>? = null
    private var dirtyVersion = 0L

    fun initializeAsync(): CompletableFuture<Core.BootstrapAttempt> = initialize(true)
    private fun initialize(schedule: Boolean): CompletableFuture<Core.BootstrapAttempt> {
        if (schedule) scheduleRepair()
        val owned: CompletableFuture<Core.BootstrapAttempt>
        synchronized(lock) {
            lastBootstrap?.takeIf { publishedReady }?.let { return CompletableFuture.completedFuture(it) }
            initialization?.takeIf { !it.isDone }?.let { return observer(it) }
            owned = CompletableFuture(); initialization = owned
        }
        initializer.execute {
            try {
                val acquired = synchronized(lock) { port } ?: portFactory().also { synchronized(lock) { port = it } }
                acquired.bootstrap().whenComplete { attempt, failure ->
                    if (failure != null) {
                        owned.completeExceptionally(unwrap(failure)); notifyListeners()
                    } else {
                        val completions = mutableListOf<() -> Unit>()
                        synchronized(lock) {
                            lastBootstrap = attempt
                            if (attempt.status == Core.BootstrapStatus.READY) {
                                // Handoff and READY publication are atomic with new early-OFF requests.
                                latest.values.filter { it.status == RequestStatus.ACCEPTED && it.published && it.token == null && !it.cancelled }
                                    .forEach { bindLocked(acquired, it, completions) }
                                publishedReady = true
                            }
                        }
                        completions.forEach { it() }
                        owned.complete(attempt); notifyListeners()
                    }
                }
            } catch (failure: Exception) { owned.completeExceptionally(failure); notifyListeners() }
        }
        return observer(owned)
    }

    fun isReady(): Boolean = synchronized(lock) { publishedReady && port?.snapshot()?.bootstrap == Core.Bootstrap.READY }
    fun captureChoice(id: String): ChoiceSnapshot = synchronized(lock) {
        val view = port?.snapshot()
        val ready = publishedReady && view?.bootstrap == Core.Bootstrap.READY
        val overlay = offLedger.containsKey(id)
        val excluded = id in RETIRED || view?.excluded?.contains(id) == true
        val denied = !ready || overlay || excluded || view?.denied?.contains(id) == true
        ChoiceSnapshot(view, ready, view?.hasChoice(id) == true, !denied && view?.locallySelected(id) == true,
            denied, excluded, stamps[id] ?: 0L, if (ready && !denied) view?.observation(id) else null)
    }
    fun beginRequest(id: String, generation: String, kind: Core.Kind): Request =
        beginRequestSilently(id, generation, kind).also(::publishRequest)

    /** Memory-only capture for callers holding an external session lock. Publish after releasing it. */
    fun beginRequestSilently(id: String, generation: String, kind: Core.Kind): Request {
        require(id.isNotEmpty() && generation.isNotEmpty())
        val completions = mutableListOf<() -> Unit>()
        val request: Request
        synchronized(lock) {
            val local = port; val ready = publishedReady && local?.snapshot()?.bootstrap == Core.Bootstrap.READY
            val off = kind == Core.Kind.DISCONNECT || kind == Core.Kind.UNREGISTER
            val status = when { kind == Core.Kind.DISCONNECT_AFTER_PERMISSION_ABORT -> RequestStatus.CONFLICT; id in RETIRED -> RequestStatus.RETIRED; !ready && !off -> RequestStatus.NOT_READY; else -> RequestStatus.ACCEPTED }
            val stamp = if (status == RequestStatus.ACCEPTED) Math.addExact(stamps[id] ?: 0L, 1L) else stamps[id] ?: 0L
            request = Request(UUID.randomUUID().toString(), stamp, id, generation, kind, status)
            if (status != RequestStatus.ACCEPTED) {
                completions += { request.tokenReady.completeExceptionally(RequestUnavailableException(status)) }
            } else {
                stamps[id] = stamp
                latest.put(id, request)?.let { old -> if (old.token == null) completions += {
                    old.tokenReady.completeExceptionally(RequestUnavailableException(RequestStatus.SUPERSEDED))
                } }
                request.previousOffStamp = offLedger[id]
                request.previousOffRequest = offObligations[id]
                if (off) offObligations[id] = request
                if (off || offLedger.containsKey(id)) offLedger[id] = stamp
                if (ready) bindLocked(requireNotNull(local), request, completions)
            }
        }
        synchronized(lock) { request.publications += completions }
        return request
    }
    fun publishRequest(request: Request) {
        val actions: List<() -> Unit>
        synchronized(lock) {
            if (request.published) return
            request.published = true
            if (request.status == RequestStatus.ACCEPTED && request.token == null && publishedReady)
                bindLocked(requireNotNull(port), request, request.publications)
            actions = request.publications.toList(); request.publications.clear()
        }
        actions.forEach { it() }; notifyListeners()
        if (request.status == RequestStatus.ACCEPTED && request.token == null) initializeAsync()
    }
    private fun bindLocked(local: Port, request: Request, completions: MutableList<() -> Unit>) {
        if (latest[request.connectorId] !== request || request.cancelled) {
            completions += { request.tokenReady.completeExceptionally(RequestUnavailableException(RequestStatus.SUPERSEDED)) }; return
        }
        val view = local.snapshot()
        val token = local.begin(Core.Command(request.connectorId, request.definitionGeneration, request.opId, request.kind,
            view.revision(request.connectorId), view.intentEpoch(request.connectorId), null))
        request.token = token
        completions += { request.tokenReady.complete(token) }
    }
    fun isCurrent(request: Request): Boolean = synchronized(lock) {
        request.status == RequestStatus.ACCEPTED && !request.cancelled && latest[request.connectorId] === request &&
            (request.token?.let { port?.isCurrent(it) == true } ?: !publishedReady)
    }
    fun persist(request: Request): CompletableFuture<Core.Result> {
        val owned: CompletableFuture<Core.Result>
        synchronized(lock) {
            request.persisted?.let { return observer(it) }
            owned = CompletableFuture(); request.persisted = owned
        }
        request.tokenReady.whenComplete { token, failure ->
            if (failure != null) owned.completeExceptionally(unwrap(failure))
            else if (!isCurrent(request)) owned.completeExceptionally(RequestUnavailableException(RequestStatus.SUPERSEDED))
            else requirePort().persist(token).whenComplete { result, error ->
                if (error != null) owned.completeExceptionally(unwrap(error))
                else { acceptResult(request, result, true); owned.complete(result) }
            }
        }
        val provisional = synchronized(lock) {
            if (isRestrictive(request.kind) && !publishedReady && request.status == RequestStatus.ACCEPTED && request.lastResult == null)
                Core.pendingRestrictive(request.kind).also { request.lastResult = it }
            else null
        }
        provisional?.let { owned.complete(it) } // UI outcome only; tokenReady continuation remains process-owned.
        return observer(owned)
    }
    fun currentResult(request: Request): Core.Result? = synchronized(lock) { request.lastResult }

    fun activationReturned(request: Request, permit: Core.ActivationPermit): CompletableFuture<Core.Result> =
        finish(request, permit, true)
    fun activationFailed(request: Request, permit: Core.ActivationPermit): CompletableFuture<Core.Result> =
        finish(request, permit, false)
    fun activationNotStarted(request: Request, permit: Core.ActivationPermit): CompletableFuture<Core.Result> {
        val owned = CompletableFuture<Core.Result>()
        requirePort().notStarted(permit).whenComplete { result, error ->
            if (error != null) owned.completeExceptionally(unwrap(error))
            else { acceptResult(request, result, true); owned.complete(result) }
        }
        return observer(owned)
    }
    private fun finish(request: Request, permit: Core.ActivationPermit, returned: Boolean): CompletableFuture<Core.Result> {
        val owned = CompletableFuture<Core.Result>()
        val operation = if (returned) requirePort().returned(permit) else requirePort().failed(permit)
        operation.whenComplete { result, error ->
            if (error != null) owned.completeExceptionally(unwrap(error))
            else { acceptResult(request, result, true); owned.complete(result) }
        }
        return observer(owned)
    }
    fun abortBeforeSelection(request: Request): CompletableFuture<Core.AbortResult> {
        synchronized(lock) {
            if (latest[request.connectorId] === request && !isRestrictive(request.kind)) request.cancelled = true
        }
        val owned = CompletableFuture<Core.AbortResult>()
        request.tokenReady.whenComplete { token, failure ->
            if (failure != null) owned.completeExceptionally(unwrap(failure))
            else requirePort().abort(token).whenComplete { result, error ->
                if (error != null) owned.completeExceptionally(unwrap(error))
                else {
                    val original = synchronized(lock) {
                        if (result.disposition == Core.AbortDisposition.NOT_ABORTABLE && latest[request.connectorId] === request)
                            request.cancelled = false
                        request.previousOffRequest?.takeIf {
                            result.disposition == Core.AbortDisposition.ABORTED && latest[request.connectorId] === request &&
                                offLedger[request.connectorId] == request.stamp && offObligations[request.connectorId] === it
                        }
                    }
                    if (original?.token != null) restoreOff(request, original, token, result, owned)
                    else { owned.complete(result); notifyListeners() }
                }
            }
        }
        return observer(owned)
    }
    private fun restoreOff(aborted: Request, original: Request, onToken: Core.RequestToken,
                           abort: Core.AbortResult, answer: CompletableFuture<Core.AbortResult>) {
        requirePort().restore(requireNotNull(original.token), onToken).whenComplete { restored, failure ->
            if (failure != null) { answer.completeExceptionally(unwrap(failure)); notifyListeners(); return@whenComplete }
            var child: Request? = null
            synchronized(lock) {
                val id = aborted.connectorId
                if (latest[id] === aborted && offLedger[id] == aborted.stamp && offObligations[id] === original) {
                    if (restored.status == Core.OffRestoration.Status.RESTORED && restored.request != null && requirePort().isCurrent(restored.request)) {
                        val token = restored.request
                        val stamp = Math.addExact(stamps[id] ?: 0L, 1L)
                        child = Request(token.command.operationId, stamp, id, token.command.definitionGeneration, token.command.kind, RequestStatus.ACCEPTED)
                            .also { it.token = token; it.published = true }
                        stamps[id] = stamp; latest[id] = requireNotNull(child); offLedger[id] = stamp; offObligations[id] = requireNotNull(child)
                    } else if (restored.status == Core.OffRestoration.Status.NOT_NEEDED) {
                        offLedger.remove(id); offObligations.remove(id)
                    }
                }
            }
            child?.let { it.tokenReady.complete(requireNotNull(it.token)); persist(it) }
            answer.complete(abort); notifyListeners()
        }
    }
    fun addListener(listener: Runnable): AutoCloseable {
        synchronized(lock) { listeners += listener }
        return AutoCloseable { synchronized(lock) { listeners -= listener } }
    }
    private fun notifyListeners() {
        val callbacks = synchronized(lock) { listeners.toList() }
        callbacks.forEach { try { it.run() } catch (_: Exception) { /* A UI observer cannot undo persistence. */ } }
    }
    private fun requirePort(): Port = synchronized(lock) { requireNotNull(port) { "Connection storage is not ready" } }
    private fun acceptResult(request: Request, result: Core.Result, schedule: Boolean) {
        synchronized(lock) {
            request.lastResult = result
            if (!result.provisional && result.persistence.choice == Core.Outcome.CONFIRMED && result.persistence.proof == Core.Outcome.CONFIRMED &&
                offLedger[request.connectorId] == request.stamp && latest[request.connectorId] === request &&
                result.snapshot?.choices?.get(request.connectorId)?.mutation?.let { !it.selected || it.phase == Core.Phase.READY } == true)
                { offLedger.remove(request.connectorId); offObligations.remove(request.connectorId) }
        }
        synchronized(projectionLock) { dirtyVersion = Math.addExact(dirtyVersion, 1L) }
        notifyListeners()
        if (schedule) scheduleRepair()
    }

    private fun scheduleRepair() {
        app?.let { try { ConnectionRepairWorker.enqueue(it) } catch (_: Exception) {
            // A failed scheduler cannot undo local state; next startup/explicit request/commit retries.
        } }
    }

    /** One durable-worker attempt: no runtime connect and no invented replacement request IDs. */
    fun repairOnce(): CompletableFuture<RepairOutcome> {
        val answer = CompletableFuture<RepairOutcome>()
        initialize(false).whenComplete { bootstrap, failure ->
            if (failure != null) {
                answer.complete(if (unwrap(failure) is ConnectionPreferencesOwner.MetadataUnconfirmedException) RepairOutcome.RETRY else RepairOutcome.BLOCKED)
            } else if (bootstrap.status != Core.BootstrapStatus.READY) {
                answer.complete(if (bootstrap.persistence.values.any { it.choice == Core.Outcome.UNCERTAIN || it.proof == Core.Outcome.UNCERTAIN })
                    RepairOutcome.RETRY else RepairOutcome.BLOCKED)
            } else {
                val pendingOff = synchronized(lock) { latest.values.filter { request ->
                    isRestrictive(request.kind) && request.persisted != null && request.lastResult?.persistence?.proof != Core.Outcome.CONFIRMED
                }.toList() }
                var repairs = CompletableFuture.completedFuture(false)
                pendingOff.forEach { request -> repairs = repairs.thenCompose { retry ->
                    val token = request.token
                    if (token == null || !isCurrent(request)) CompletableFuture.completedFuture(retry)
                    else requirePort().persist(token).thenApply { result ->
                        acceptResult(request, result, false)
                        retry || result.persistence.choice == Core.Outcome.UNCERTAIN || result.persistence.proof == Core.Outcome.UNCERTAIN
                    }
                } }
                repairs.whenComplete { retry, repairError ->
                    if (repairError != null || retry == true) answer.complete(RepairOutcome.RETRY)
                    else projectAsync().whenComplete { outcome, projectionError ->
                        answer.complete(if (projectionError != null) RepairOutcome.RETRY else outcome)
                    }
                }
            }
        }
        return observer(answer)
    }
    private fun projectAsync(): CompletableFuture<RepairOutcome> {
        val owned: CompletableFuture<RepairOutcome>
        synchronized(projectionLock) {
            projectionFlight?.let { return observer(it) }
            owned = CompletableFuture(); projectionFlight = owned
        }
        projector.execute {
            while (true) {
                val observed = synchronized(projectionLock) { dirtyVersion }
                val outcome = try { requirePort().project() } catch (_: Exception) { RepairOutcome.RETRY }
                val finished = synchronized(projectionLock) {
                    if (outcome != RepairOutcome.SUCCESS || observed == dirtyVersion) {
                        projectionFlight = null; true
                    } else false // Drain only after progress/new commits; errors never busy-loop.
                }
                if (finished) { owned.complete(outcome); break }
            }
        }
        return observer(owned)
    }

    private class ProductionPort(private val app: Application, private val owner: ConnectionPreferencesOwner) : Port {
        override fun snapshot() = owner.snapshot()
        override fun bootstrap() = owner.initializeAsync()
        override fun begin(command: Core.Command) = owner.beginRequest(command)
        override fun isCurrent(token: Core.RequestToken) = owner.isCurrent(token)
        override fun persist(token: Core.RequestToken) = owner.persist(token)
        override fun returned(permit: Core.ActivationPermit) = owner.activationReturned(permit)
        override fun failed(permit: Core.ActivationPermit) = owner.activationFailed(permit)
        override fun notStarted(permit: Core.ActivationPermit) = owner.activationNotStarted(permit)
        override fun abort(token: Core.RequestToken) = owner.abortBeforeSelection(token)
        override fun restore(off: Core.RequestToken, on: Core.RequestToken) = owner.restoreOffAfterPermissionAbort(off, on)
        override fun project(): RepairOutcome {
            val view = owner.snapshot()
            if (view.confirmedPending.isEmpty() && view.lastConfirmedSnapshots.isEmpty()) return RepairOutcome.SUCCESS
            return try {
                val bridge = ChatDatabaseBridge.getInstance(app)
                val evidence = bridge.connectionProjectionEvidenceSync() // Room readiness only on projector.
                val binding = owner.bindDestination(evidence) ?: return RepairOutcome.RETRY
                val batch = owner.prepareProjection(binding)
                val ack = bridge.projectConnectionStateSync(batch) // Its lease is released before Prefs ACK.
                if (owner.ackProjection(batch, ack) == Core.Outcome.CONFIRMED) RepairOutcome.SUCCESS else RepairOutcome.RETRY
            } catch (failure: Exception) {
                owner.invalidateDestination()
                var cause: Throwable? = failure
                while (cause != null) {
                    if (cause is SQLiteDatabaseCorruptException) return RepairOutcome.BLOCKED
                    cause = cause.cause
                }
                RepairOutcome.RETRY
            }
        }
    }

    /** Pure test port; never selected as a fallback for a missing production Application provider. */
    private class TestPort(initial: Map<String, Boolean>, mirror: (String, Boolean) -> Unit,
                           outcome: (Core.Mutation, Boolean) -> Core.Outcome = { _, _ -> Core.Outcome.CONFIRMED },
                           private val projection: (() -> RepairOutcome)? = null,
                           private val beforeBootstrap: () -> Unit = {}) : Port {
        private val storage = TestStorage(initial, mirror, outcome)
        private val fence = ReentrantLock()
        private val issuer = Any()
        private val core = Core(storage, { 1L }, "test-process", fence, issuer)
        override fun snapshot() = core.read()
        override fun bootstrap() = completed { beforeBootstrap(); core.applyBootstrap(core.prepareBootstrap()) }
        override fun begin(command: Core.Command) = core.beginIntent(command)
        override fun isCurrent(token: Core.RequestToken) = core.isCurrent(token)
        override fun persist(token: Core.RequestToken) = completed { core.persistSelection(token) }
        override fun returned(permit: Core.ActivationPermit) = completed { core.activationReturned(permit) }
        override fun failed(permit: Core.ActivationPermit) = completed { core.activationFailed(permit) }
        override fun notStarted(permit: Core.ActivationPermit) = completed { core.activationNotStarted(permit) }
        override fun abort(token: Core.RequestToken) = completed { core.abortBeforePersistence(token) }
        override fun restore(off: Core.RequestToken, on: Core.RequestToken) = completed { core.restoreOffAfterPermissionAbort(off, on) }
        override fun project(): RepairOutcome {
            projection?.let { return it() }
            core.bindDestination(object : Core.DestinationEvidence {
                override fun generation() = "test-destination"
                override fun providerIdentity() = issuer
                override fun fenceIdentity() = fence
                override fun isCurrentPure() = true
            })
            val batch = core.prepareProjection("test-destination")
            return if (core.ackProjection(batch, Core.Ack(batch.sourceEpoch, batch.targetGeneration, batch.events)) == Core.Outcome.CONFIRMED)
                RepairOutcome.SUCCESS else RepairOutcome.RETRY
        }
    }
    private class TestStorage(initial: Map<String, Boolean>, private val mirror: (String, Boolean) -> Unit,
                              private val outcome: (Core.Mutation, Boolean) -> Core.Outcome) : Core.Storage {
        private val epoch = "test-${UUID.randomUUID()}"
        private val identity = Any()
        private var version = 0L
        private var water = 0L
        private var ack: String? = null
        private var binding: Core.DestinationBinding? = null
        private val current = linkedMapOf<String, Core.Mutation>()
        private val proofs = linkedMapOf<String, Core.Mutation>()
        private val candidates = linkedMapOf<String, Core.UncertainCandidate>()
        private val pending = linkedMapOf<String, Core.Event>()
        private val revisions = linkedMapOf<String, Long>()
        private val intents = linkedMapOf<String, Long>()
        private val legacy = (initial.keys + RETIRED).map { id -> Core.LegacyRecord("legacy:$id", id, Core.LEGACY_DEFINITION,
            if (id in RETIRED) Core.LegacyState.RETIRED else if (initial[id] == true) Core.LegacyState.TRUE else Core.LegacyState.FALSE, null) }
        override fun load() = image()
        private fun image() = Core.StoredImage(epoch, water, null, ack, current, proofs, pending, candidates, legacy, revisions, intents)
        override fun writeCandidate(m: Core.Mutation): Core.Outcome {
            current[m.event.connectorId] = m; candidates[m.version] = Core.UncertainCandidate(m, false)
            pending[m.event.eventId] = m.event; water = maxOf(water, m.highWater)
            revisions[m.event.connectorId] = maxOf(revisions[m.event.connectorId] ?: 0, m.revision)
            intents[m.event.connectorId] = maxOf(intents[m.event.connectorId] ?: 0, m.command.expectedIntentEpoch + 1)
            version++
            val result = outcome(m, false)
            if (result == Core.Outcome.CONFIRMED) mirror(m.event.connectorId, m.selected)
            return result
        }
        override fun writeProof(m: Core.Mutation): Core.Outcome {
            check(candidates[m.version]?.mutation == m || proofs[m.version] == m)
            version++
            val result = outcome(m, true)
            if (result == Core.Outcome.CONFIRMED) proofs[m.version] = m
            return result
        }
        override fun bindDestination(binding: Core.DestinationBinding?) { this.binding = binding; version++ }
        override fun retentionSnapshot() = Core.RetentionSnapshot(image(), identity, version)
        override fun cleanup(batch: Core.ProjectionBatch, plan: Core.RetentionPlan): Core.Outcome {
            if (batch.binding !== binding || plan.expectedSnapshot.ownerIdentity !== identity || plan.expectedSnapshot.version != version)
                return Core.Outcome.NOT_ATTEMPTED
            batch.events.forEach { pending.remove(it.eventId) }
            proofs.keys.retainAll(plan.keepProofVersions); candidates.keys.retainAll(plan.keepCandidateVersions)
            plan.carryCandidates.forEach { (key, m) -> candidates[key] = Core.UncertainCandidate(m, false) }
            pending.putAll(plan.carryPending); revisions.putAll(plan.reservedRevisions); intents.putAll(plan.intentHighWater)
            water = plan.highWater; ack = batch.targetGeneration; version++; return Core.Outcome.CONFIRMED
        }
    }

    companion object {
        private val RETIRED = setOf("email", "sms_draft")
        private fun isRestrictive(kind: Core.Kind) = kind == Core.Kind.DISCONNECT || kind == Core.Kind.UNREGISTER || kind == Core.Kind.DISCONNECT_AFTER_PERMISSION_ABORT
        @JvmStatic @JvmOverloads fun forTests(initialChoices: Map<String, Boolean>, onChoiceCommit: (String, Boolean) -> Unit = { _, _ -> }): ConnectionAccess {
            val access = ConnectionAccess(null, Executor { it.run() }) { TestPort(initialChoices.toMap(), onChoiceCommit) }
            access.initialize(false).join()
            return access
        }
        internal fun forTestsControlled(initialChoices: Map<String, Boolean>, executor: Executor,
                                        outcome: (Core.Mutation, Boolean) -> Core.Outcome = { _, _ -> Core.Outcome.CONFIRMED },
                                        projection: (() -> RepairOutcome)? = null,
                                        beforeBootstrap: () -> Unit = {}): ConnectionAccess =
            ConnectionAccess(null, executor) { TestPort(initialChoices.toMap(), { _, _ -> }, outcome, projection, beforeBootstrap) }
        private fun <T> completed(operation: () -> T): CompletableFuture<T> {
            val result = CompletableFuture<T>()
            try { result.complete(operation()) } catch (failure: Exception) { result.completeExceptionally(failure) }
            return result
        }
        @JvmStatic fun find(context: Context): ConnectionAccess? =
            (context.applicationContext as? ConnectionAccessProvider)?.connectionAccess()
        @JvmStatic fun isLocallySelected(context: Context, id: String): Boolean = find(context)?.captureChoice(id)?.selected == true
        @JvmStatic fun createForApplication(application: Application): ConnectionAccess = ConnectionAccess(application,
            Executors.newSingleThreadExecutor { task -> Thread(task, "JarvysConnectionAccess").apply { isDaemon = true } }) {
                val anchor = ChatDatabaseBridge.connectionProjectionAnchor(application)
                ProductionPort(application, ConnectionPreferencesOwner.forContext(application, anchor.fence, anchor.providerIdentity))
            }
        private fun <T> observer(owned: CompletableFuture<T>): CompletableFuture<T> = owned.thenApply { it }
        private fun unwrap(failure: Throwable): Throwable =
            if (failure is java.util.concurrent.CompletionException || failure is java.util.concurrent.ExecutionException) failure.cause ?: failure else failure
    }
}
