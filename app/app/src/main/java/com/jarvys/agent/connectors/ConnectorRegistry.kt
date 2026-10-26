package com.jarvys.agent.connectors

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreConnectorTool
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import com.jarvys.agent.connectors.ConnectionStateCoordinator as ConnectionCore

data class ConnectorField(val key: String, val label: String, val secret: Boolean = false)

data class ConnectorOperation(
    /** Stable runtime key; operation names are converted to valid model function names by CoreConnectorTool. */
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val write: Boolean = false,
    val displayLabel: String = name,
    val limits: Map<String, Int> = emptyMap(),
    val requiredPermissions: List<String> = emptyList(),
    val autonomyAllowed: Boolean = true,
    val permissionsOverride: Boolean = false,
    val displayLabelResourceId: Int = 0,
    val descriptionResourceId: Int = 0,
) {
    constructor(
        name: String,
        description: String,
        inputSchema: JSONObject,
        write: Boolean,
        displayLabel: String,
        limits: Map<String, Int>,
        requiredPermissions: List<String>,
    ) : this(name, description, inputSchema, write, displayLabel, limits, requiredPermissions, true, false)
}

data class ConnectorDefinition(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val capabilities: List<String> = emptyList(),
    val configurationFields: List<ConnectorField> = emptyList(),
    val operations: List<ConnectorOperation> = emptyList(),
    val runtime: ConnectorRuntime? = null,
    val readPermissions: List<String> = emptyList(),
    val writePermissions: List<String> = emptyList(),
    val permissionLabel: String = name,
    val connectionFlow: ConnectorConnectionFlow = ConnectorConnectionFlow.PERMISSIONS,
    val connectionAccessGranted: (() -> Boolean)? = null,
    val usageNoteProvider: (() -> String)? = null,
    val displayNameResourceId: Int = 0,
    val descriptionResourceId: Int = 0,
    val permissionLabelResourceId: Int = 0,
    val connectionPermissions: List<String> = readPermissions,
    val presentationGroup: ConnectorPresentationGroup = ConnectorPresentationGroup.ON_DEVICE,
    /** Live service grants are distinct from Android permissions and local autonomy policy. */
    val operationAccessGranted: ((String) -> Boolean)? = null,
    /** Definitions with a dedicated adapter are cataloged here but not wrapped as connector tools. */
    val usesDedicatedToolAdapter: Boolean = false,
) {
    constructor(
        id: String,
        name: String,
        version: String,
        description: String,
        capabilities: List<String>,
        configurationFields: List<ConnectorField>,
        operations: List<ConnectorOperation>,
        runtime: ConnectorRuntime?,
        readPermissions: List<String>,
        writePermissions: List<String>,
        permissionLabel: String,
        connectionFlow: ConnectorConnectionFlow,
        connectionAccessGranted: (() -> Boolean)?,
        usageNoteProvider: (() -> String)?,
    ) : this(
        id, name, version, description, capabilities, configurationFields, operations, runtime,
        readPermissions, writePermissions, permissionLabel, connectionFlow, connectionAccessGranted,
        usageNoteProvider, 0, 0, 0, readPermissions,
    )

    fun usageNote(): String = usageNoteProvider?.invoke().orEmpty()
}

/** Definitions consumed by a dedicated adapter belong to that adapter's settings surface. */
fun visibleConnectorCatalogEntries(definitions: Collection<ConnectorDefinition>): List<ConnectorDefinition> =
    definitions.filterNot { it.usesDedicatedToolAdapter }

enum class ConnectorConnectionFlow { PERMISSIONS, NOTIFICATION_LISTENER_SETTINGS }
enum class ConnectorPresentationGroup { ON_DEVICE, SERVICES }

interface ConnectorRuntime {
    /** Opt in only after every WRITE boundary carries the explicit callback. */
    fun hasCompleteWriteAdmissionCoverage(): Boolean = false
    fun connect(configuration: Map<String, String>, secrets: Map<String, String>)
    fun disconnect()
    fun prepareRead(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation =
        ConnectorWritePreparation(ApprovalSummary(operation, listOf(arguments.toString().take(4096))), JSONObject(arguments.toString()))
    fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation
    fun invokePrepared(operation: String, arguments: JSONObject, preparation: ConnectorWritePreparation,
                       token: CancellationToken): JSONObject = invoke(operation, arguments, token)
    fun invokePreparedWithContext(operation: String, arguments: JSONObject,
                                  preparation: ConnectorWritePreparation, token: CancellationToken,
                                  context: ConnectorInvocationContext): JSONObject =
        invokePrepared(operation, arguments, preparation, token)
    fun invokeReadPrepared(operation: String, arguments: JSONObject, preparation: ConnectorWritePreparation,
                           token: CancellationToken): JSONObject = invoke(operation, arguments, token)
    fun invokeReadPreparedWithContext(operation: String, arguments: JSONObject,
                                      preparation: ConnectorWritePreparation, token: CancellationToken,
                                      context: ConnectorInvocationContext): JSONObject =
        invokeReadPrepared(operation, arguments, preparation, token)
    fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject
}

/** Invocation context forwarded only to runtimes that need conversation-scoped host access. */
class ConnectorEffectOutcome {
    @Volatile var receipt: EffectInvocation.Receipt? = null
    /** Local runtime result, never reconstructed from provider JSON; preserves flags and evidence. */
    @Volatile var toolResult: com.jarvys.agent.CoreToolResult? = null
}

data class ConnectorInvocationContext @JvmOverloads constructor(
    val requester: String? = null,
    val requesterColorKey: String? = null,
    val conversationId: String? = null,
    val progress: ((String) -> Unit)? = null,
    val effectInvocation: EffectInvocation? = null,
    val effectOutcome: ConnectorEffectOutcome = ConnectorEffectOutcome(),
)

/** Optional run-scoped context for high-risk connectors that bind data reads to later writes. */
interface AgentRunScopedConnectorRuntime {
    fun beginAgentRun(executionId: Long, userMessage: String)
    fun beginAgentRun(executionId: Long, userMessage: String, priorUserMessages: List<String>) {
        beginAgentRun(executionId, userMessage)
    }
    fun endAgentRun(executionId: Long)
    fun validateAutonomousWrite(operation: String, arguments: JSONObject, executionId: Long)
}

class AutonomousWriteValidationFailure(val userReason: ConnectorUiText) : IllegalStateException(userReason.fallback)

data class ConnectorWritePreparation(
    val approval: ApprovalSummary,
    val executionArguments: JSONObject,
    val attachment: Any? = null,
    val fallbackOnPermissionDenied: Boolean = false,
    val permissionDeniedFallback: Boolean = false,
    /** Fresh, exact-operation admission immediately before each provider effect or batch mutation. */
    val authorizeEffect: (() -> Unit)? = null,
    /** WRITE dispatch only, distinct from preflight reads and generic authority checks. */
    val authorizeWriteEffect: (() -> Unit)? = null,
    val reportWriteOutcome: ((EffectInvocation.Outcome) -> Unit)? = null,
)

enum class ConnectorState { DISCONNECTED, CONNECTED, PERMISSION_REVOKED }

interface ConnectorConnectionPreferences {
    fun isConnected(id: String): Boolean
    fun setConnected(id: String, connected: Boolean)
    /** True when the user has explicitly connected or disconnected this definition before. */
    fun hasConnectionChoice(id: String): Boolean = true
}

/** Compatibility policy binding only. Connection selection is owned exclusively by ConnectionAccess. */
class ConnectorStateStore(context: Context) {
    init { ActionPolicyDefaults.bindDefaultPolicyPreferences(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)) }
    companion object { const val PREFERENCES = "jarvys_connectors" }
}

/** Permission and connection state is checked at the point of display and again for every invocation. */
class ConnectorRegistry private constructor(
    private val connectionAccess: ConnectionAccess,
    private val permissionGranted: (String) -> Boolean,
    private val approvalGate: ApprovalGate,
    private val autonomyStore: ConnectorAutonomyStore,
    private val autonomyNotifier: AutonomyActionNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val launchIntent: (ApprovalIntentSpec) -> Unit = { error("Native intent dispatcher unavailable") },
    private val enforceWorker: Boolean = true,
) {
    class ConnectorTransitionResult internal constructor(
        val status: Status,
        val disposition: ConnectionCore.Disposition? = null,
        val choiceOutcome: ConnectionCore.Outcome = ConnectionCore.Outcome.NOT_ATTEMPTED,
        val proofOutcome: ConnectionCore.Outcome = ConnectionCore.Outcome.NOT_ATTEMPTED,
        val activation: ConnectionCore.Activation = ConnectionCore.Activation.NOT_REQUESTED,
        val cleanup: Cleanup = Cleanup.NOT_REQUIRED,
    ) {
        enum class Status { COMPLETED, PENDING, ABORTED, NOT_READY, MISSING, SUPERSEDED, CONFLICT, PERMISSION_DENIED,
            PERSISTENCE_UNCERTAIN, RUNTIME_FAILED, CLEANUP_FAILED, ABORT_REJECTED }
        enum class Cleanup { NOT_REQUIRED, DONE, FAILED }
        val persistenceConfirmed: Boolean get() = choiceOutcome == ConnectionCore.Outcome.CONFIRMED && proofOutcome == ConnectionCore.Outcome.CONFIRMED
        val cleanupCompleted: Boolean get() = cleanup != Cleanup.FAILED
    }
    internal class DefinitionHandle(val definition: ConnectorDefinition, val generation: String = UUID.randomUUID().toString())
    internal class CleanupObligation(val handle: DefinitionHandle, val stamp: Long) {
        val completion = CompletableFuture<ConnectorTransitionResult.Cleanup>()
    }
    class ConnectionRequest internal constructor(
        val requestId: String, val connectorId: String, val kind: ConnectionCore.Kind, val status: Status,
        internal val owner: ConnectorRegistry, internal val handle: DefinitionHandle?, internal val accessRequest: ConnectionAccess.Request?,
    ) {
        enum class Status { ACCEPTED, NOT_READY, MISSING, SUPERSEDED, CONFLICT }
        internal var submitted: CompletableFuture<ConnectorTransitionResult>? = null
        @Volatile internal var terminal: ConnectorTransitionResult? = null
        @Volatile internal var terminalEpoch: String? = null
        @Volatile internal var terminalIntent: Long? = null
        internal var cleanupObligation: CleanupObligation? = null
        internal val published = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private class LifecycleLane {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "JarvysConnectorLifecycle").apply { isDaemon = true } }
        val cleanup = java.util.TreeMap<Long, CleanupObligation>()
        var active: Pair<DefinitionHandle, Long>? = null
    }
    private val handles = mutableMapOf<String, DefinitionHandle>()
    private val latestRequests = mutableMapOf<String, ConnectionRequest>()
    private val lanes = mutableMapOf<String, LifecycleLane>()
    private val _connectionReady = MutableStateFlow(connectionAccess.isReady())
    val connectionReady: StateFlow<Boolean> = _connectionReady.asStateFlow()
    private val lock = Any()
    private val _definitions = MutableStateFlow<List<ConnectorDefinition>>(emptyList())
    private val _states = MutableStateFlow<Map<String, ConnectorState>>(emptyMap())
    private val _autonomyRevision = MutableStateFlow(0L)
    val definitions: StateFlow<List<ConnectorDefinition>> = _definitions.asStateFlow()
    val states: StateFlow<Map<String, ConnectorState>> = _states.asStateFlow()
    val autonomyRevision: StateFlow<Long> = _autonomyRevision.asStateFlow()

    private val connectionListener = connectionAccess.addListener(Runnable {
        _connectionReady.value = connectionAccess.isReady()
        refreshStates()
    })

    fun get(id: String): ConnectorDefinition? = definitions.value.firstOrNull { it.id == id }

    fun state(id: String): ConnectorState {
        val definition = get(id) ?: return ConnectorState.DISCONNECTED
        return calculateState(definition)
    }

    fun state(definition: ConnectorDefinition): ConnectorState = calculateState(definition)

    fun canConnect(definition: ConnectorDefinition): Boolean = definition.readPermissions.all(permissionGranted)

    fun connectionPermissionsGranted(definition: ConnectorDefinition): Boolean =
        definition.connectionPermissions.all(permissionGranted)

    fun connectedDefinitions(): List<ConnectorDefinition> = definitions.value.filter {
        it.runtime != null && calculateState(it) == ConnectorState.CONNECTED
    }

    fun autonomyPolicy(definition: ConnectorDefinition, operation: ConnectorOperation): AutonomyPolicy =
        configuredAutonomyPolicy(definition, operation)

    fun configuredAutonomyPolicy(definition: ConnectorDefinition, operation: ConnectorOperation): AutonomyPolicy =
        runCatching { autonomyStore.policy(definition.id, operation.name) }.getOrElse { autonomyStore.unavailablePolicy() }

    fun configuredAutonomyOrigin(definition: ConnectorDefinition, operation: ConnectorOperation): ActionPolicyOrigin =
        runCatching { autonomyStore.snapshot(definition.id, operation.name).origin }.getOrDefault(ActionPolicyOrigin.INVALID)

    /** Availability is independent from the saved decision; it never silently changes ALLOW to ASK. */
    fun autonomyPolicyUnavailableReason(definition: ConnectorDefinition, operation: ConnectorOperation): String? {
        if (state(definition) != ConnectorState.CONNECTED) return "This connector is not connected."
        if (definition.operationAccessGranted?.invoke(operation.name) == false) return "The service permission is unavailable."
        val permissions = if (operation.write) writePermissions(definition, operation) else readPermissions(definition, operation)
        return permissions.firstOrNull { !permissionGranted(it) }?.let { "Grant ${it.substringAfterLast('.')} permission to execute this action." }
    }

    fun autonomyPolicyUnavailableUiReason(definition: ConnectorDefinition, operation: ConnectorOperation): ConnectorUiText? =
        autonomyAllowUnavailableReason(definition, operation)

    /** Returns a user-facing reason on failure; the current action can still be approved separately. */
    fun tryEnableAutonomyFromApproval(connectorId: String, operationName: String): String? {
        val definition = get(connectorId) ?: return "This connector is no longer available."
        val operation = definition.operations.firstOrNull { it.name == operationName }
            ?: return "This operation is no longer available."
        val failure = runCatching { setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW) }.exceptionOrNull()
        return failure?.let { it.message ?: "Could not enable Allow mode." }
    }

    fun tryEnableAutonomyFromApprovalUi(connectorId: String, operationName: String): ConnectorUiText? {
        val definition = get(connectorId) ?: return ConnectorUiText(
            R.string.approval_autonomy_connector_unavailable, fallback = "This connector is no longer available.",
        )
        val operation = definition.operations.firstOrNull { it.name == operationName } ?: return ConnectorUiText(
            R.string.approval_autonomy_operation_unavailable, fallback = "This operation is no longer available.",
        )
        val failure = runCatching { setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW) }.exceptionOrNull()
        return failure?.let { ConnectorUiText(R.string.connector_allow_failed, fallback = it.message ?: "Could not enable Allow mode.") }
    }

    private fun autonomyAllowUnavailableReason(definition: ConnectorDefinition, operation: ConnectorOperation): ConnectorUiText? =
        autonomyPolicyUnavailableReason(definition, operation)?.let { ConnectorUiText(fallback = it) }

    fun autonomyPermissions(definition: ConnectorDefinition, operation: ConnectorOperation): List<String> =
        (if (operation.write) writePermissions(definition, operation) else readPermissions(definition, operation)).filterNot(permissionGranted)

    fun autonomyNotificationsAvailable(): Boolean = autonomyNotifier.canPost()

    fun setAutonomyPolicy(definition: ConnectorDefinition, operation: ConnectorOperation, policy: AutonomyPolicy) {
        require(get(definition.id)?.operations?.any { it.name == operation.name } == true) { "Action is no longer available" }
        autonomyStore.setPolicyChecked(definition.id, operation.name, policy)
        synchronized(lock) { _autonomyRevision.value += 1L }
    }

    /** Refreshes policy rows after the shared default changes; this does not write per-action choices. */
    fun notifyDefaultAutonomyPolicyChanged() {
        synchronized(lock) { _autonomyRevision.value += 1L }
    }

    fun recentAutonomyActions(connectorId: String, limit: Int = 10): List<AutonomyAuditRecord> =
        autonomyStore.recentAudit(connectorId, limit)

    fun beginAgentRun(executionId: Long, userMessage: String) {
        beginAgentRun(executionId, userMessage, emptyList())
    }

    fun beginAgentRun(executionId: Long, userMessage: String, priorUserMessages: List<String>) {
        connectedDefinitions().mapNotNull { it.runtime as? AgentRunScopedConnectorRuntime }
            .distinct().forEach { it.beginAgentRun(executionId, userMessage, priorUserMessages) }
    }

    fun endAgentRun(executionId: Long) {
        definitions.value.mapNotNull { it.runtime as? AgentRunScopedConnectorRuntime }
            .distinct().forEach { runCatching { it.endAgentRun(executionId) } }
    }

    fun refreshStates() {
        _states.value = definitions.value.associate { it.id to calculateState(it) }
    }

    fun register(definition: ConnectorDefinition) = synchronized(lock) {
        require(ID_PATTERN.matches(definition.id)) { "Connector id is invalid" }
        require(definition.name.isNotBlank() && definition.name.length <= 100) { "Connector name is required" }
        require(definition.version.isNotBlank() && definition.description.length <= MAX_DESCRIPTION_CHARS) {
            "Connector metadata is invalid"
        }
        require(definition.operations.map { it.name }.distinct().size == definition.operations.size) {
            "Connector operation names must be unique"
        }
        val toolNames = definition.operations.map { operation ->
            require(operation.name.isNotBlank() && operation.name.length <= 128) { "Connector operation name is invalid" }
            require(operation.displayLabel.isNotBlank() && operation.displayLabel.length <= 100) { "Connector display label is invalid" }
            CoreConnectorTool.toolName(definition.id, operation.name)
        }
        require(toolNames.distinct().size == toolNames.size) { "Connector operation names collide after tool-name normalization" }
        require(_definitions.value.none { it.id == definition.id }) { "Connector is already registered: ${definition.id}" }
        handles[definition.id] = DefinitionHandle(definition)
        _definitions.value = _definitions.value + definition
        refreshStates()
    }

    /** Installs stable device definitions before any caller, including a freshly started service, discovers tools. */
    internal fun registerBuiltInDefinitions(definitions: Collection<ConnectorDefinition>) {
        definitions.forEach { definition ->
            if (get(definition.id) == null) register(definition)
        }
    }

    /** Capture is memory-only and precedes any permission/settings UI. */
    fun captureConnectionRequest(id: String, kind: ConnectionCore.Kind): ConnectionRequest =
        captureConnectionRequestSilently(id, kind).also(::publishConnectionRequest)

    /** Only memory operations: safe inside a caller's short admission lock. */
    fun captureConnectionRequestSilently(id: String, kind: ConnectionCore.Kind): ConnectionRequest = synchronized(lock) {
        val handle = handles[id]
        if (kind == ConnectionCore.Kind.DISCONNECT_AFTER_PERMISSION_ABORT)
            return@synchronized ConnectionRequest(UUID.randomUUID().toString(), id, kind, ConnectionRequest.Status.CONFLICT, this, handle, null)
        if (handle == null) return@synchronized ConnectionRequest(UUID.randomUUID().toString(), id, kind, ConnectionRequest.Status.MISSING, this, null, null)
        if (kind == ConnectionCore.Kind.SETTINGS_CONNECT && handle.definition.connectionFlow != ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS)
            return@synchronized ConnectionRequest(UUID.randomUUID().toString(), id, kind, ConnectionRequest.Status.CONFLICT, this, handle, null)
        val captured = connectionAccess.beginRequestSilently(id, handle.generation, kind)
        val status = when (captured.status.name) {
            "ACCEPTED" -> ConnectionRequest.Status.ACCEPTED
            "NOT_READY" -> ConnectionRequest.Status.NOT_READY
            "SUPERSEDED" -> ConnectionRequest.Status.SUPERSEDED
            else -> ConnectionRequest.Status.CONFLICT
        }
        val request = ConnectionRequest(captured.opId, id, kind, status, this, handle, captured)
        if (status == ConnectionRequest.Status.ACCEPTED) {
            latestRequests[id] = request
            if (!isConnect(kind)) {
                val obligation = CleanupObligation(handle, captured.stamp)
                request.cleanupObligation = obligation
                lane(id).cleanup[captured.stamp] = obligation
            }
        }
        request
    }
    fun publishConnectionRequest(request: ConnectionRequest) {
        if (request.owner !== this || !request.published.compareAndSet(false, true)) return
        request.accessRequest?.let(connectionAccess::publishRequest)
        refreshStates()
    }
    fun resolvePendingRequest(requestId: String): ConnectionRequest? = synchronized(lock) {
        latestRequests.values.firstOrNull { it.requestId == requestId && it.terminal == null && isConnectionRequestCurrent(it) }
    }
    fun isConnectionRequestCurrent(request: ConnectionRequest): Boolean = synchronized(lock) {
        val captured = request.accessRequest ?: return@synchronized false
        if (request.owner !== this || latestRequests[request.connectorId] !== request || handles[request.connectorId] !== request.handle)
            return@synchronized false
        val choice = connectionAccess.captureChoice(request.connectorId)
        if (choice.stamp != captured.stamp) return@synchronized false
        // A provisional OFF has no observed core snapshot yet, but its exact local deny intent is still current.
        if (request.terminal?.status == ConnectorTransitionResult.Status.PENDING)
            return@synchronized connectionAccess.isCurrent(captured)
        if (request.terminal != null) {
            val view = choice.coreView ?: return@synchronized false
            return@synchronized view.storeEpoch == request.terminalEpoch && view.intentEpoch(request.connectorId) == request.terminalIntent
        }
        connectionAccess.isCurrent(captured)
    }
    fun submitConnectionRequest(request: ConnectionRequest): CompletableFuture<ConnectorTransitionResult> = synchronized(request) {
        if (request.owner !== this) return@synchronized CompletableFuture.completedFuture(ConnectorTransitionResult(ConnectorTransitionResult.Status.CONFLICT))
        publishConnectionRequest(request)
        request.submitted?.let { return@synchronized it.thenApply { value -> value } }
        val future = CompletableFuture<ConnectorTransitionResult>(); request.submitted = future
        if (request.owner !== this || request.status != ConnectionRequest.Status.ACCEPTED || request.accessRequest == null) {
            finish(request, future, ConnectorTransitionResult(when (request.status) {
                ConnectionRequest.Status.NOT_READY -> ConnectorTransitionResult.Status.NOT_READY
                ConnectionRequest.Status.MISSING -> ConnectorTransitionResult.Status.MISSING
                ConnectionRequest.Status.SUPERSEDED -> ConnectorTransitionResult.Status.SUPERSEDED
                else -> ConnectorTransitionResult.Status.CONFLICT
            }))
        } else if (isConnect(request.kind)) {
            synchronized(lock) { lane(request.connectorId) }.worker.execute { runConnect(request, future) }
        } else {
            // Persistence never waits for a blocked runtime or Room projection.
            val persisted = connectionAccess.persist(request.accessRequest)
            val cleanup = requireNotNull(request.cleanupObligation).completion
            synchronized(lock) { lane(request.connectorId) }.worker.execute { drainCleanup(request.connectorId, request.accessRequest.stamp) }
            persisted.handle { result, failure -> result to failure }.thenCombine(cleanup) { pair, cleaned ->
                val failed = pair.second?.let(::unavailable)
                val result = if (failed != null) ConnectorTransitionResult(failed.status, failed.disposition, failed.choiceOutcome, failed.proofOutcome, failed.activation, cleaned)
                    else transition(requireNotNull(pair.first), cleaned)
                if (cleaned == ConnectorTransitionResult.Cleanup.FAILED && result.status == ConnectorTransitionResult.Status.COMPLETED)
                    ConnectorTransitionResult(ConnectorTransitionResult.Status.CLEANUP_FAILED, result.disposition, result.choiceOutcome, result.proofOutcome, result.activation, cleaned)
                else result
            }.whenComplete { result, failure -> finish(request, future, if (failure == null) result else unavailable(failure)) }
        }
        future.thenApply { value -> value }
    }
    fun abortConnectionRequest(request: ConnectionRequest): CompletableFuture<ConnectorTransitionResult> = synchronized(request) {
        if (request.owner !== this || request.accessRequest == null || request.submitted != null)
            return@synchronized CompletableFuture.completedFuture(ConnectorTransitionResult(ConnectorTransitionResult.Status.ABORT_REJECTED))
        val future = CompletableFuture<ConnectorTransitionResult>(); request.submitted = future
        connectionAccess.abortBeforeSelection(request.accessRequest).whenComplete { aborted, failure ->
            finish(request, future, if (failure != null) unavailable(failure) else ConnectorTransitionResult(when (aborted.disposition) {
                ConnectionCore.AbortDisposition.ABORTED -> ConnectorTransitionResult.Status.ABORTED
                ConnectionCore.AbortDisposition.SUPERSEDED -> ConnectorTransitionResult.Status.SUPERSEDED
                else -> ConnectorTransitionResult.Status.ABORT_REJECTED
            }))
        }
        future.thenApply { value -> value }
    }
    private fun lane(id: String): LifecycleLane = lanes.getOrPut(id) { LifecycleLane() }
    private fun isConnect(kind: ConnectionCore.Kind): Boolean = kind == ConnectionCore.Kind.CONNECT ||
        kind == ConnectionCore.Kind.SETTINGS_CONNECT || kind == ConnectionCore.Kind.DEFAULT_CONNECT
    private fun handleCurrent(request: ConnectionRequest): Boolean = synchronized(lock) {
        request.owner === this && handles[request.connectorId] === request.handle &&
            request.accessRequest?.let(connectionAccess::isCurrent) == true
    }
    private fun drainCleanup(id: String, through: Long): ConnectorTransitionResult.Cleanup {
        val work = synchronized(lock) { lane(id).cleanup.values.filter { it.stamp <= through }.toList() }
        var failed = false
        work.forEach { obligation ->
            val active = synchronized(lock) { lane(id).active }
            val result = if (active != null && active.second > obligation.stamp) ConnectorTransitionResult.Cleanup.NOT_REQUIRED
                else try { obligation.handle.definition.runtime?.disconnect(); ConnectorTransitionResult.Cleanup.DONE }
                catch (_: RuntimeException) { failed = true; ConnectorTransitionResult.Cleanup.FAILED }
            if (result != ConnectorTransitionResult.Cleanup.FAILED) synchronized(lock) {
                if (lane(id).cleanup[obligation.stamp] === obligation) lane(id).cleanup.remove(obligation.stamp)
                if (result == ConnectorTransitionResult.Cleanup.DONE && lane(id).active == active) lane(id).active = null
            }
            obligation.completion.complete(result)
        }
        return if (failed) ConnectorTransitionResult.Cleanup.FAILED else ConnectorTransitionResult.Cleanup.DONE
    }
    private fun cleanupActivation(request: ConnectionRequest): ConnectorTransitionResult.Cleanup {
        val obligation = CleanupObligation(requireNotNull(request.handle), requireNotNull(request.accessRequest).stamp)
        synchronized(lock) { lane(request.connectorId).cleanup.putIfAbsent(obligation.stamp, obligation) }
        return drainCleanup(request.connectorId, obligation.stamp)
    }
    private fun runConnect(request: ConnectionRequest, future: CompletableFuture<ConnectorTransitionResult>) {
        val captured = requireNotNull(request.accessRequest); val definition = requireNotNull(request.handle).definition
        var runtimeStarted = false
        var runtimeReturned = false
        try {
            if (!handleCurrent(request)) { finish(request, future, ConnectorTransitionResult(ConnectorTransitionResult.Status.SUPERSEDED)); return }
            if (request.kind != ConnectionCore.Kind.SETTINGS_CONNECT && !definition.readPermissions.all(permissionGranted)) {
                connectionAccess.abortBeforeSelection(captured).get()
                finish(request, future, ConnectorTransitionResult(ConnectorTransitionResult.Status.PERMISSION_DENIED)); return
            }
            val cleaned = drainCleanup(request.connectorId, captured.stamp - 1)
            if (cleaned == ConnectorTransitionResult.Cleanup.FAILED) {
                finish(request, future, ConnectorTransitionResult(ConnectorTransitionResult.Status.CLEANUP_FAILED, cleanup = cleaned)); return
            }
            val selected = connectionAccess.persist(captured).get()
            val permit = selected.permit
            if (permit == null) { finish(request, future, transition(selected)); return }
            if (!handleCurrent(request) || (request.kind != ConnectionCore.Kind.SETTINGS_CONNECT && !definition.readPermissions.all(permissionGranted))) {
                val stopped = connectionAccess.activationNotStarted(captured, permit).get()
                finish(request, future, transition(stopped, status = if (stopped.disposition == ConnectionCore.Disposition.SUPERSEDED)
                    ConnectorTransitionResult.Status.SUPERSEDED else ConnectorTransitionResult.Status.PERMISSION_DENIED)); return
            }
            val previous = synchronized(lock) { lane(request.connectorId).active }
            if (previous != null && previous.second != captured.stamp) {
                synchronized(lock) { lane(request.connectorId).cleanup.putIfAbsent(previous.second, CleanupObligation(previous.first, previous.second)) }
                if (drainCleanup(request.connectorId, captured.stamp - 1) == ConnectorTransitionResult.Cleanup.FAILED) {
                    val stopped = connectionAccess.activationNotStarted(captured, permit).get()
                    finish(request, future, transition(stopped, ConnectorTransitionResult.Cleanup.FAILED, ConnectorTransitionResult.Status.CLEANUP_FAILED)); return
                }
            }
            if (!handleCurrent(request) || (request.kind != ConnectionCore.Kind.SETTINGS_CONNECT && !definition.readPermissions.all(permissionGranted))) {
                val stopped = connectionAccess.activationNotStarted(captured, permit).get()
                finish(request, future, transition(stopped, status = if (stopped.disposition == ConnectionCore.Disposition.SUPERSEDED)
                    ConnectorTransitionResult.Status.SUPERSEDED else ConnectorTransitionResult.Status.PERMISSION_DENIED)); return
            }
            // This lane owns the exact runtime incarnation; any late cleanup finishes before another start.
            synchronized(lock) { lane(request.connectorId).active = requireNotNull(request.handle) to captured.stamp }
            runtimeStarted = true
            try { definition.runtime?.connect(emptyMap(), emptyMap()) }
            catch (_: RuntimeException) {
                val cleanup = cleanupActivation(request)
                val failed = connectionAccess.activationFailed(captured, permit).get()
                finish(request, future, transition(failed, cleanup, ConnectorTransitionResult.Status.RUNTIME_FAILED)); return
            }
            runtimeReturned = true
            val returned = connectionAccess.activationReturned(captured, permit).get()
            val cleanup = if (returned.cleanupRequired || !handleCurrent(request)) cleanupActivation(request) else ConnectorTransitionResult.Cleanup.NOT_REQUIRED
            finish(request, future, transition(returned, cleanup))
        } catch (failure: Exception) {
            val cleanup = if (runtimeStarted) cleanupActivation(request) else ConnectorTransitionResult.Cleanup.NOT_REQUIRED
            val failed = unavailable(failure)
            finish(request, future, ConnectorTransitionResult(failed.status, failed.disposition, failed.choiceOutcome, failed.proofOutcome,
                if (runtimeReturned) ConnectionCore.Activation.RETURNED else if (runtimeStarted) ConnectionCore.Activation.FAILED else ConnectionCore.Activation.NOT_STARTED, cleanup))
        }
    }
    private fun transition(result: ConnectionCore.Result, cleanup: ConnectorTransitionResult.Cleanup = ConnectorTransitionResult.Cleanup.NOT_REQUIRED,
                           status: ConnectorTransitionResult.Status? = null): ConnectorTransitionResult {
        val actual = status ?: when {
            result.provisional -> ConnectorTransitionResult.Status.PENDING
            result.disposition == ConnectionCore.Disposition.SUPERSEDED -> ConnectorTransitionResult.Status.SUPERSEDED
            result.disposition == ConnectionCore.Disposition.CONFLICT -> ConnectorTransitionResult.Status.CONFLICT
            result.persistence.choice != ConnectionCore.Outcome.CONFIRMED || result.persistence.proof != ConnectionCore.Outcome.CONFIRMED -> ConnectorTransitionResult.Status.PERSISTENCE_UNCERTAIN
            cleanup == ConnectorTransitionResult.Cleanup.FAILED -> ConnectorTransitionResult.Status.CLEANUP_FAILED
            else -> ConnectorTransitionResult.Status.COMPLETED
        }
        return ConnectorTransitionResult(actual, result.disposition, result.persistence.choice, result.persistence.proof, result.activation, cleanup)
    }
    private fun unavailable(failure: Throwable): ConnectorTransitionResult {
        var cause = failure
        while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) cause = cause.cause!!
        val status = if (cause is ConnectionAccess.RequestUnavailableException) when (cause.status.name) {
            "NOT_READY" -> ConnectorTransitionResult.Status.NOT_READY
            "SUPERSEDED" -> ConnectorTransitionResult.Status.SUPERSEDED
            else -> ConnectorTransitionResult.Status.CONFLICT
        } else ConnectorTransitionResult.Status.PERSISTENCE_UNCERTAIN
        return ConnectorTransitionResult(status)
    }
    private fun finish(request: ConnectionRequest, future: CompletableFuture<ConnectorTransitionResult>, result: ConnectorTransitionResult) {
        val choice = connectionAccess.captureChoice(request.connectorId)
        request.terminalEpoch = choice.coreView?.storeEpoch; request.terminalIntent = choice.coreView?.intentEpoch(request.connectorId)
        request.terminal = result
        refreshStates(); future.complete(result)
    }
    private fun requireWorker() {
        check(!enforceWorker || Looper.myLooper() != Looper.getMainLooper()) { "Connection mutations require a worker; UI must capture/submit asynchronously" }
    }
    private fun blocking(id: String, kind: ConnectionCore.Kind): ConnectorTransitionResult {
        requireWorker(); return submitConnectionRequest(captureConnectionRequest(id, kind)).get()
    }
    fun connect(id: String): ConnectorTransitionResult = blocking(id, ConnectionCore.Kind.CONNECT)
    fun connectFromSystemSettings(id: String): ConnectorTransitionResult = blocking(id, ConnectionCore.Kind.SETTINGS_CONNECT)
    fun disconnect(id: String): ConnectorTransitionResult = blocking(id, ConnectionCore.Kind.DISCONNECT)
    fun connectIfUnselected(id: String): ConnectorTransitionResult {
        requireWorker()
        // Defaults need a known absence, never an uninitialized preferences view. Await one
        // local bootstrap attempt outside Registry/lifecycle locks; early OFF remains immediate.
        try {
            if (!connectionAccess.isReady()) connectionAccess.initializeAsync().get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return ConnectorTransitionResult(ConnectorTransitionResult.Status.NOT_READY)
        } catch (_: java.util.concurrent.ExecutionException) {
            return ConnectorTransitionResult(ConnectorTransitionResult.Status.NOT_READY)
        }
        return blocking(id, ConnectionCore.Kind.DEFAULT_CONNECT)
    }
    fun unregister(id: String): ConnectorTransitionResult {
        requireWorker()
        val request = captureConnectionRequest(id, ConnectionCore.Kind.UNREGISTER)
        if (request.status == ConnectionRequest.Status.ACCEPTED) synchronized(lock) {
            if (handles[id] === request.handle) { handles.remove(id); _definitions.value = _definitions.value.filterNot { it.id == id } }
        }
        return submitConnectionRequest(request).get()
    }

    /** Observation only; final dispatch still uses its own exact gate snapshot and revalidation. */
    fun observeEffectPolicy(definition: ConnectorDefinition, operation: ConnectorOperation,
                            invocation: EffectInvocation?): AutonomyPolicy {
        val snapshot = autonomyStore.snapshot(definition.id, operation.name)
        invocation?.noteDecision(EffectInvocation.Admission(when (snapshot.policy) {
            AutonomyPolicy.ALLOW -> EffectInvocation.Authorization.ALLOW
            AutonomyPolicy.ASK -> EffectInvocation.Authorization.ASK_PENDING
            AutonomyPolicy.DENY -> EffectInvocation.Authorization.DENY
        }, "connector:${definition.id}", operation.name, snapshot.revision.toString(), snapshot.origin.name))
        return snapshot.policy
    }

    fun invoke(definition: ConnectorDefinition, operation: ConnectorOperation,
               arguments: JSONObject, token: CancellationToken): JSONObject =
        invoke(definition, operation, arguments, token, null)

    fun invoke(definition: ConnectorDefinition, operation: ConnectorOperation,
               arguments: JSONObject, token: CancellationToken, requester: String?): JSONObject =
        invoke(definition, operation, arguments, token, requester, null)

    fun invoke(definition: ConnectorDefinition, operation: ConnectorOperation,
               arguments: JSONObject, token: CancellationToken, requester: String?,
               requesterColorKey: String?): JSONObject = invokeInternal(
        definition, operation, arguments, token, requester, requesterColorKey,
        unattended = false, approvedPendingAction = false, deferAskWrite = null,
        invocationContext = ConnectorInvocationContext(requester, requesterColorKey),
    )

    fun invoke(definition: ConnectorDefinition, operation: ConnectorOperation,
               arguments: JSONObject, token: CancellationToken,
               invocationContext: ConnectorInvocationContext): JSONObject = invokeInternal(
        definition, operation, arguments, token, invocationContext.requester, invocationContext.requesterColorKey,
        unattended = false, approvedPendingAction = false, deferAskWrite = null,
        invocationContext = invocationContext,
    )

    /** Background-task invocation never enters ApprovalGate: ASK writes are handed to the durable queue. */
    fun invokeForScheduledTask(
        definition: ConnectorDefinition,
        operation: ConnectorOperation,
        arguments: JSONObject,
        token: CancellationToken,
        requester: String?,
        requesterColorKey: String?,
        approvedPendingAction: Boolean,
        deferAskWrite: java.util.function.Supplier<JSONObject>?,
    ): JSONObject = invokeForScheduledTask(definition, operation, arguments, token, requester,
        requesterColorKey, approvedPendingAction, deferAskWrite,
        ConnectorInvocationContext(requester, requesterColorKey))

    fun invokeForScheduledTask(
        definition: ConnectorDefinition,
        operation: ConnectorOperation,
        arguments: JSONObject,
        token: CancellationToken,
        requester: String?,
        requesterColorKey: String?,
        approvedPendingAction: Boolean,
        deferAskWrite: java.util.function.Supplier<JSONObject>?,
        invocationContext: ConnectorInvocationContext,
    ): JSONObject = invokeInternal(
        definition, operation, arguments, token, requester, requesterColorKey,
        unattended = true, approvedPendingAction = approvedPendingAction, deferAskWrite = deferAskWrite,
        invocationContext = invocationContext,
    )

    private fun invokeInternal(
        definition: ConnectorDefinition,
        operation: ConnectorOperation,
        arguments: JSONObject,
        token: CancellationToken,
        requester: String?,
        requesterColorKey: String?,
        unattended: Boolean,
        approvedPendingAction: Boolean,
        deferAskWrite: java.util.function.Supplier<JSONObject>?,
        invocationContext: ConnectorInvocationContext,
    ): JSONObject {
        val current = get(definition.id) ?: error("El conector ${definition.name} ya no está disponible")
        val selectedOperation = current.operations.firstOrNull { it.name == operation.name }
            ?: error("Connector operation is no longer available")
        check(current === definition && selectedOperation === operation) {
            "Connector definition or action changed before invocation"
        }
        check(current.operationAccessGranted?.invoke(selectedOperation.name) != false) {
            "Enable and authorize this operation's service permission in Conectores before using it. No write was sent."
        }
        val connectionChoice = connectionAccess.captureChoice(current.id)
        val currentState = calculateState(current, connectionChoice)
        if (currentState != ConnectorState.CONNECTED) {
            refreshStates()
            if (currentState == ConnectorState.PERMISSION_REVOKED) {
                error("El permiso de ${current.permissionLabel} fue revocado; volvé a conectarlo en Conectores")
            }
            error("${current.name} está desconectado; conectalo de nuevo en Conectores")
        }
        val connectionPin = captureConnectionPin(current, connectionChoice)
        val permissions = if (selectedOperation.write) writePermissions(current, selectedOperation) else readPermissions(current, selectedOperation)
        token.throwIfCancelled()
        val runtime = requireNotNull(current.runtime) { "El conector ${current.name} no tiene runtime" }
        val executionArguments = JSONObject(arguments.toString())
        val schema = selectedOperation.inputSchema.toString()
        val effect = invocationContext.effectInvocation
        check(effect == null || !selectedOperation.write || runtime.hasCompleteWriteAdmissionCoverage()) {
            "Connector does not yet cover every write admission boundary"
        }
        var snapshot = autonomyStore.snapshot(current.id, selectedOperation.name)
        fun decision(approved: Boolean) = EffectInvocation.Admission(
            when (snapshot.policy) {
                AutonomyPolicy.ALLOW -> EffectInvocation.Authorization.ALLOW
                AutonomyPolicy.DENY -> EffectInvocation.Authorization.DENY
                AutonomyPolicy.ASK -> if (approved) EffectInvocation.Authorization.ASK_APPROVED else EffectInvocation.Authorization.ASK_PENDING
            }, "connector:${current.id}", selectedOperation.name, snapshot.revision.toString(), snapshot.origin.name, connectionPin.observation)
        effect?.noteDecision(decision(false))
        check(snapshot.policy != AutonomyPolicy.DENY) { "This action is denied by its saved policy" }
        var approvedAsk = false
        if (snapshot.policy == AutonomyPolicy.ASK && unattended) {
            if (!approvedPendingAction) {
                val deferred = requireNotNull(deferAskWrite) {
                    "A pending-action queue is required for ASK operations in a scheduled task"
                }.get()
                invocationContext.effectOutcome.receipt = effect?.receipt(EffectInvocation.Outcome.NOT_EXECUTED)
                return deferred
            }
            approvedAsk = true
        }
        val preparation = if (selectedOperation.write) runtime.prepareWrite(selectedOperation.name, executionArguments, token)
            else runtime.prepareRead(selectedOperation.name, executionArguments, token)
        val preparedArguments = preparation.executionArguments.toString()
        var alwaysDecision: ActionPolicySnapshot? = null
        var permissionDeniedFallback = false
        val autonomous = snapshot.policy == AutonomyPolicy.ALLOW
        if (snapshot.policy == AutonomyPolicy.ASK && !unattended) {
            val decision = approvalGate.request(preparation.approval.copy(
                permission = permissions.firstOrNull { !permissionGranted(it) },
                allowAlwaysAvailable = true,
                autonomyConnectorId = current.id, autonomyOperationName = selectedOperation.name,
                requester = invocationContext.requester, requesterColorKey = invocationContext.requesterColorKey,
                enableAlways = { alwaysDecision = autonomyStore.setPolicyAndSnapshot(current.id, selectedOperation.name, AutonomyPolicy.ALLOW) },
            ), token)
            when (decision) {
                ApprovalDecision.APPROVED, ApprovalDecision.APPROVED_ALLOW_FAILED -> approvedAsk = true
                ApprovalDecision.APPROVED_ALLOW_ALWAYS -> {
                    // The explicit button durably updates the same action; no other policy change inherits this grant.
                    snapshot = requireNotNull(alwaysDecision) { "Missing exact Allow decision receipt" }
                    check(snapshot.policy == AutonomyPolicy.ALLOW) { "Allow policy was not saved or was revoked" }
                }
                ApprovalDecision.PERMISSION_DENIED -> {
                    check(preparation.fallbackOnPermissionDenied) { "Android permission was denied" }
                    alwaysDecision?.let { snapshot = it }
                    approvedAsk = snapshot.policy == AutonomyPolicy.ASK
                    permissionDeniedFallback = true
                }
                ApprovalDecision.PERMISSION_FALLBACK_LAUNCHED -> error("Legacy UI effect receipt cannot authorize another dispatch")
                ApprovalDecision.DENIED -> {
                    effect?.noteDecision(EffectInvocation.Admission(EffectInvocation.Authorization.DENY,
                        "connector:${current.id}", selectedOperation.name, snapshot.revision.toString(),
                        snapshot.origin.name + ":user_denied"))
                    error("The user denied this action; do not retry automatically")
                }
                ApprovalDecision.ACTION_FAILED -> error("Android could not complete the permission request")
                ApprovalDecision.EXPIRED -> error("Approval expired")
                ApprovalDecision.CANCELLED -> throw java.util.concurrent.CancellationException("Approval cancelled")
            }
        }
        fun validateCurrent(): ConnectionPin {
            token.throwIfCancelled()
            check(preparation.executionArguments.toString() == preparedArguments) { "Prepared action arguments changed" }
            if (selectedOperation.write) (runtime as? AgentRunScopedConnectorRuntime)?.validateAutonomousWrite(
                selectedOperation.name, preparation.executionArguments, token.generation())
            check(get(current.id) === current && current.runtime === runtime &&
                current.operations.firstOrNull { it.name == selectedOperation.name } === selectedOperation &&
                selectedOperation.inputSchema.toString() == schema) { "Connector action changed before dispatch" }
            val validated = validateConnectionPin(connectionPin)
            check(calculateState(current, validated.choice) == ConnectorState.CONNECTED) { "Connector access was revoked" }
            check(current.operationAccessGranted?.invoke(selectedOperation.name) != false) { "Service permission was revoked" }
            check(permissionDeniedFallback || permissions.all(permissionGranted)) { "Android permission is unavailable; grant it in connector settings" }
            return validated
        }
        // Admission is the linearization boundary. Later revocation blocks the next admission,
        // but cannot undo an effect already dispatched to Android or a provider.
        fun authorizeEffect(): ConnectionPin {
            val fresh = autonomyStore.snapshotMatching(snapshot)
            var validated: ConnectionPin? = null
            check(token.runIfActive { autonomyStore.admit(fresh, approvedAsk) { validated = validateCurrent() } }) { "Action cancelled before admission" }
            return requireNotNull(validated)
        }
        fun authorizeWriteEffect() {
            val admittedConnection = authorizeEffect()
            check(admittedConnection.observation == connectionPin.observation)
            // No policy/STOP lock is held while persistence crosses the Room executor.
            effect?.admit(decision(approvedAsk))
            // The persisted fact is not authority. Revalidate after the durable callback.
            authorizeEffect()
        }
        fun reportWriteOutcome(outcome: EffectInvocation.Outcome) {
            invocationContext.effectOutcome.receipt = effect?.receipt(outcome)
        }
        effect?.noteDecision(decision(approvedAsk))
        check(token.runIfActive { autonomyStore.admit(snapshot, approvedAsk) { validateCurrent(); Unit } }) { "Action cancelled before admission" }
        val intent = if (permissionDeniedFallback) preparation.approval.permissionDeniedIntent else preparation.approval.activityIntent
        if (intent != null) {
            if (selectedOperation.write) authorizeWriteEffect() else authorizeEffect()
            launchIntent(intent)
            if (selectedOperation.write) reportWriteOutcome(EffectInvocation.Outcome.OK) // Launch only, never saved contact/call.
        }
        val result = if (selectedOperation.write) runtime.invokePreparedWithContext(
            selectedOperation.name, preparation.executionArguments,
            preparation.copy(permissionDeniedFallback = permissionDeniedFallback, authorizeEffect = { authorizeEffect(); Unit },
                authorizeWriteEffect = ::authorizeWriteEffect, reportWriteOutcome = ::reportWriteOutcome), token,
            invocationContext,
        ) else runtime.invokeReadPreparedWithContext(selectedOperation.name, preparation.executionArguments,
            preparation.copy(authorizeEffect = { authorizeEffect(); Unit }), token, invocationContext)
        if (autonomous) {
            val record = AutonomyAuditRecord(
                timestampMillis = clock(),
                connectorId = current.id,
                connectorName = current.name,
                operationName = selectedOperation.name,
                operationLabel = selectedOperation.displayLabel,
                summary = autonomousActionSummary(result),
            )
            val auditSaved = runCatching { autonomyStore.appendAudit(record) }.isSuccess
            if (auditSaved) synchronized(lock) { _autonomyRevision.value += 1L }
            val notificationPosted = runCatching { autonomyNotifier.post(record) }.isSuccess
            if (!auditSaved) {
                result.put("auditWarning", "The action ran but its local audit could not be saved; do not retry without checking the outcome.")
            }
            if (!notificationPosted) {
                result.put("notificationWarning", "The action ran, but Android did not display the silent autonomy notice.")
            }
        }
        return result
    }

    private fun readPermissions(definition: ConnectorDefinition, operation: ConnectorOperation) =
        if (operation.permissionsOverride || operation.requiredPermissions.isNotEmpty()) operation.requiredPermissions
        else definition.readPermissions

    private fun writePermissions(definition: ConnectorDefinition, operation: ConnectorOperation) =
        if (operation.permissionsOverride || operation.requiredPermissions.isNotEmpty()) operation.requiredPermissions
        else definition.writePermissions

    private fun autonomousActionSummary(result: JSONObject): String = when (result.optString("status")) {
        "dialer_launch_requested" -> "Dialer launch requested"
        "call_requested", "telecom_call_request_submitted" -> "Call requested"
        else -> "Completed automatically"
    }

    fun hasPermission(permission: String): Boolean = permissionGranted(permission)

    private data class ConnectionPin(
        val handle: DefinitionHandle, val choice: ConnectionAccess.ChoiceSnapshot,
        val epoch: String, val intent: Long, val mutation: ConnectionCore.Mutation,
        val stamp: Long, val observation: ConnectionCore.Observation,
    )
    private fun captureConnectionPin(definition: ConnectorDefinition, choice: ConnectionAccess.ChoiceSnapshot): ConnectionPin {
        val handle = synchronized(lock) { handles[definition.id] }
        check(handle?.definition === definition && choice.ready && choice.selected && !choice.denied && !choice.excluded) { "Connector selection is unavailable" }
        val view = requireNotNull(choice.coreView)
        return ConnectionPin(requireNotNull(handle), choice, view.storeEpoch, view.intentEpoch(definition.id),
            requireNotNull(view.factualCurrent[definition.id]), choice.stamp, requireNotNull(choice.observation))
    }
    private fun validateConnectionPin(pin: ConnectionPin): ConnectionPin {
        val id = pin.handle.definition.id
        val choice = connectionAccess.captureChoice(id)
        val current = captureConnectionPin(pin.handle.definition, choice)
        check(current.handle === pin.handle && current.epoch == pin.epoch && current.intent == pin.intent &&
            current.mutation == pin.mutation && current.stamp == pin.stamp && current.observation == pin.observation) {
            "Connector choice changed before dispatch"
        }
        return current
    }

    private fun calculateState(definition: ConnectorDefinition, choice: ConnectionAccess.ChoiceSnapshot = connectionAccess.captureChoice(definition.id)): ConnectorState {
        if (!choice.ready || !choice.selected || choice.denied || choice.excluded) return ConnectorState.DISCONNECTED
        val permissionsGranted = definition.readPermissions.all(permissionGranted)
        val extraAccessGranted = runCatching { definition.connectionAccessGranted?.invoke() ?: true }.getOrDefault(false)
        return if (permissionsGranted && extraAccessGranted) ConnectorState.CONNECTED
        else ConnectorState.PERMISSION_REVOKED
    }

    companion object {
        private const val MAX_DESCRIPTION_CHARS = 4096
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        @Volatile private var instance: ConnectorRegistry? = null

        fun get(context: Context): ConnectorRegistry = instance ?: synchronized(this) {
            val appContext = context.applicationContext
            instance ?: ConnectorRegistry(
                requireNotNull(ConnectionAccess.find(appContext)) { "Application connection owner is unavailable" },
                { permission -> ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED },
                ApprovalGate.INSTANCE,
                SharedPreferencesConnectorAutonomyStore(appContext),
                AndroidAutonomyActionNotifier(appContext),
                launchIntent = { spec -> appContext.startActivity(com.jarvys.agent.approvalIntent(spec).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
            ).also { registry ->
                registry.registerBuiltInDefinitions(deviceDefinitions(appContext))
                instance = registry
            }
        }

        internal fun deviceDefinitions(context: Context): List<ConnectorDefinition> = listOf(
            CalendarConnector.definition(context),
            ContactsConnector.definition(context),
            DialerConnector.definition(context),
            SafDocumentConnector.definition(context),
            LocationConnector.definition(context),
            NotificationsConnector.definition(context),
        ) + FlavorDeviceConnectorDefinitions.definitions(context)

        /** Allows isolated JVM tests to exercise the same registry policy without Android globals. */
        @JvmOverloads
        fun createForTests(
            stateStore: ConnectorConnectionPreferences,
            permissionGranted: (String) -> Boolean,
            approvalGate: ApprovalGate = ApprovalGate.INSTANCE,
            initialChoices: Map<String, Boolean> = emptyMap(),
        ): ConnectorRegistry = ConnectorRegistry(
            ConnectionAccess.forTests(initialChoices, stateStore::setConnected), permissionGranted, approvalGate,
            InMemoryConnectorAutonomyStore(), NoopAutonomyActionNotifier, enforceWorker = false,
        )
        fun createForTests(
            access: ConnectionAccess, permissionGranted: (String) -> Boolean,
            approvalGate: ApprovalGate = ApprovalGate.INSTANCE,
        ): ConnectorRegistry = ConnectorRegistry(access, permissionGranted, approvalGate,
            InMemoryConnectorAutonomyStore(), NoopAutonomyActionNotifier, enforceWorker = false)

        fun createForTestsWithAutonomy(
            stateStore: ConnectorConnectionPreferences,
            permissionGranted: (String) -> Boolean,
            approvalGate: ApprovalGate,
            autonomyStore: ConnectorAutonomyStore,
            autonomyNotifier: AutonomyActionNotifier,
            clock: () -> Long = System::currentTimeMillis,
            launchIntent: (ApprovalIntentSpec) -> Unit = { error("Native intent dispatcher unavailable") },
            initialChoices: Map<String, Boolean> = emptyMap(),
        ): ConnectorRegistry = ConnectorRegistry(
            ConnectionAccess.forTests(initialChoices, stateStore::setConnected), permissionGranted, approvalGate,
            autonomyStore, autonomyNotifier, clock, launchIntent, enforceWorker = false,
        )

    }
}
