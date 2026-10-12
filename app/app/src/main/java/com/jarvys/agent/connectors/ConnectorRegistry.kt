package com.jarvys.agent.connectors

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreConnectorTool
import com.jarvys.agent.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

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

enum class ConnectorConnectionFlow { PERMISSIONS, NOTIFICATION_LISTENER_SETTINGS }
enum class ConnectorPresentationGroup { ON_DEVICE, SERVICES }

interface ConnectorRuntime {
    fun connect(configuration: Map<String, String>, secrets: Map<String, String>)
    fun disconnect()
    fun prepareRead(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation =
        ConnectorWritePreparation(ApprovalSummary(operation, listOf(arguments.toString().take(4096))), JSONObject(arguments.toString()))
    fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation
    fun invokePrepared(operation: String, arguments: JSONObject, preparation: ConnectorWritePreparation,
                       token: CancellationToken): JSONObject = invoke(operation, arguments, token)
    fun invokeReadPrepared(operation: String, arguments: JSONObject, preparation: ConnectorWritePreparation,
                           token: CancellationToken): JSONObject = invoke(operation, arguments, token)
    fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject
}

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
)

enum class ConnectorState { DISCONNECTED, CONNECTED, PERMISSION_REVOKED }

interface ConnectorConnectionPreferences {
    fun isConnected(id: String): Boolean
    fun setConnected(id: String, connected: Boolean)
}

class ConnectorStateStore(context: Context) : ConnectorConnectionPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun isConnected(id: String): Boolean = preferences.getBoolean("connected_$id", false)
    override fun setConnected(id: String, connected: Boolean) {
        preferences.edit().putBoolean("connected_$id", connected).apply()
    }

    companion object { const val PREFERENCES = "jarvys_connectors" }
}

/** Permission and connection state is checked at the point of display and again for every invocation. */
class ConnectorRegistry private constructor(
    private val stateStore: ConnectorConnectionPreferences,
    private val permissionGranted: (String) -> Boolean,
    private val approvalGate: ApprovalGate,
    private val autonomyStore: ConnectorAutonomyStore,
    private val autonomyNotifier: AutonomyActionNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val launchIntent: (ApprovalIntentSpec) -> Unit = { error("Native intent dispatcher unavailable") },
) {
    private val lock = Any()
    private val _definitions = MutableStateFlow<List<ConnectorDefinition>>(emptyList())
    private val _states = MutableStateFlow<Map<String, ConnectorState>>(emptyMap())
    private val _autonomyRevision = MutableStateFlow(0L)
    val definitions: StateFlow<List<ConnectorDefinition>> = _definitions.asStateFlow()
    val states: StateFlow<Map<String, ConnectorState>> = _states.asStateFlow()
    val autonomyRevision: StateFlow<Long> = _autonomyRevision.asStateFlow()

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
        runCatching { autonomyStore.policy(definition.id, operation.name) }.getOrDefault(AutonomyPolicy.ASK)

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
        _definitions.value = _definitions.value + definition
        refreshStates()
    }

    /** Installs stable device definitions before any caller, including a freshly started service, discovers tools. */
    internal fun registerBuiltInDefinitions(definitions: Collection<ConnectorDefinition>) {
        definitions.forEach { definition ->
            if (get(definition.id) == null) register(definition)
        }
    }

    fun connect(id: String) {
        val definition = get(id) ?: return
        require(definition.readPermissions.all(permissionGranted)) { "The required connector permission has not been granted" }
        activate(definition)
    }

    fun connectFromSystemSettings(id: String) {
        val definition = get(id) ?: return
        require(definition.connectionFlow == ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS) {
            "This connector does not use system notification settings"
        }
        activate(definition)
    }

    private fun activate(definition: ConnectorDefinition) {
        stateStore.setConnected(definition.id, true)
        try {
            definition.runtime?.connect(emptyMap(), emptyMap())
        } catch (failure: RuntimeException) {
            stateStore.setConnected(definition.id, false)
            refreshStates()
            throw failure
        }
        refreshStates()
    }

    fun disconnect(id: String) {
        val definition = get(id) ?: return
        stateStore.setConnected(id, false)
        runCatching { definition.runtime?.disconnect() }
        refreshStates()
    }

    fun unregister(id: String) = synchronized(lock) {
        val current = get(id) ?: return@synchronized
        stateStore.setConnected(id, false)
        runCatching { current.runtime?.disconnect() }
        _definitions.value = _definitions.value.filterNot { it.id == id }
        refreshStates()
    }

    fun invoke(definition: ConnectorDefinition, operation: ConnectorOperation,
               arguments: JSONObject, token: CancellationToken): JSONObject =
        invoke(definition, operation, arguments, token, null)

    fun invoke(definition: ConnectorDefinition, operation: ConnectorOperation,
               arguments: JSONObject, token: CancellationToken, requester: String?): JSONObject =
        invoke(definition, operation, arguments, token, requester, null)

    fun invoke(definition: ConnectorDefinition, operation: ConnectorOperation,
               arguments: JSONObject, token: CancellationToken, requester: String?,
               requesterColorKey: String?): JSONObject {
        val current = get(definition.id) ?: error("El conector ${definition.name} ya no está disponible")
        val selectedOperation = current.operations.firstOrNull { it.name == operation.name }
            ?: error("Connector operation is no longer available")
        check(current.operationAccessGranted?.invoke(selectedOperation.name) != false) {
            "Enable and authorize this operation's service permission in Conectores before using it. No write was sent."
        }
        val currentState = state(current)
        if (currentState != ConnectorState.CONNECTED) {
            refreshStates()
            if (currentState == ConnectorState.PERMISSION_REVOKED) {
                error("El permiso de ${current.permissionLabel} fue revocado; volvé a conectarlo en Conectores")
            }
            error("${current.name} está desconectado; conectalo de nuevo en Conectores")
        }
        val permissions = if (selectedOperation.write) writePermissions(current, selectedOperation) else readPermissions(current, selectedOperation)
        token.throwIfCancelled()
        val runtime = requireNotNull(current.runtime) { "El conector ${current.name} no tiene runtime" }
        val executionArguments = JSONObject(arguments.toString())
        val schema = selectedOperation.inputSchema.toString()
        var snapshot = autonomyStore.snapshot(current.id, selectedOperation.name)
        check(snapshot.policy != AutonomyPolicy.DENY) { "This action is denied by its saved policy" }
        val preparation = if (selectedOperation.write) runtime.prepareWrite(selectedOperation.name, executionArguments, token)
            else runtime.prepareRead(selectedOperation.name, executionArguments, token)
        val preparedArguments = preparation.executionArguments.toString()
        var approvedAsk = false
        var alwaysDecision: ActionPolicySnapshot? = null
        var permissionDeniedFallback = false
        val autonomous = snapshot.policy == AutonomyPolicy.ALLOW
        if (snapshot.policy == AutonomyPolicy.ASK) {
            val decision = approvalGate.request(preparation.approval.copy(
                permission = permissions.firstOrNull { !permissionGranted(it) },
                allowAlwaysAvailable = true,
                autonomyConnectorId = current.id, autonomyOperationName = selectedOperation.name,
                requester = requester, requesterColorKey = requesterColorKey,
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
                ApprovalDecision.DENIED -> error("The user denied this action; do not retry automatically")
                ApprovalDecision.ACTION_FAILED -> error("Android could not complete the permission request")
                ApprovalDecision.EXPIRED -> error("Approval expired")
                ApprovalDecision.CANCELLED -> throw java.util.concurrent.CancellationException("Approval cancelled")
            }
        }
        fun validateCurrent() {
            token.throwIfCancelled()
            check(preparation.executionArguments.toString() == preparedArguments) { "Prepared action arguments changed" }
            if (selectedOperation.write) (runtime as? AgentRunScopedConnectorRuntime)?.validateAutonomousWrite(
                selectedOperation.name, preparation.executionArguments, token.generation())
            check(get(current.id) === current && current.runtime === runtime &&
                current.operations.firstOrNull { it.name == selectedOperation.name } === selectedOperation &&
                selectedOperation.inputSchema.toString() == schema) { "Connector action changed before dispatch" }
            check(state(current) == ConnectorState.CONNECTED) { "Connector access was revoked" }
            check(current.operationAccessGranted?.invoke(selectedOperation.name) != false) { "Service permission was revoked" }
            check(permissionDeniedFallback || permissions.all(permissionGranted)) { "Android permission is unavailable; grant it in connector settings" }
        }
        // Admission is the linearization boundary. Later revocation blocks the next admission,
        // but cannot undo an effect already dispatched to Android or a provider.
        fun authorizeEffect() {
            val fresh = autonomyStore.snapshotMatching(snapshot)
            check(token.runIfActive { autonomyStore.admit(fresh, approvedAsk, ::validateCurrent) }) { "Action cancelled before admission" }
        }
        check(token.runIfActive { autonomyStore.admit(snapshot, approvedAsk, ::validateCurrent) }) { "Action cancelled before admission" }
        val intent = if (permissionDeniedFallback) preparation.approval.permissionDeniedIntent else preparation.approval.activityIntent
        if (intent != null) {
            authorizeEffect()
            launchIntent(intent)
        }
        val result = if (selectedOperation.write) runtime.invokePrepared(
            selectedOperation.name, preparation.executionArguments,
            preparation.copy(permissionDeniedFallback = permissionDeniedFallback, authorizeEffect = ::authorizeEffect), token,
        ) else runtime.invokeReadPrepared(selectedOperation.name, preparation.executionArguments,
            preparation.copy(authorizeEffect = ::authorizeEffect), token)
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

    private fun calculateState(definition: ConnectorDefinition): ConnectorState {
        if (!stateStore.isConnected(definition.id)) return ConnectorState.DISCONNECTED
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
                ConnectorStateStore(appContext),
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
            EmailIntentConnector.definition(),
            SmsComposerConnector.definition(),
            SafDocumentConnector.definition(context),
            LocationConnector.definition(context),
            NotificationsConnector.definition(context),
        ) + FlavorDeviceConnectorDefinitions.definitions(context)

        /** Allows isolated JVM tests to exercise the same registry policy without Android globals. */
        fun createForTests(
            stateStore: ConnectorConnectionPreferences,
            permissionGranted: (String) -> Boolean,
            approvalGate: ApprovalGate = ApprovalGate.INSTANCE,
        ): ConnectorRegistry = ConnectorRegistry(
            stateStore, permissionGranted, approvalGate,
            InMemoryConnectorAutonomyStore(), NoopAutonomyActionNotifier,
        )

        fun createForTestsWithAutonomy(
            stateStore: ConnectorConnectionPreferences,
            permissionGranted: (String) -> Boolean,
            approvalGate: ApprovalGate,
            autonomyStore: ConnectorAutonomyStore,
            autonomyNotifier: AutonomyActionNotifier,
            clock: () -> Long = System::currentTimeMillis,
            launchIntent: (ApprovalIntentSpec) -> Unit = { error("Native intent dispatcher unavailable") },
        ): ConnectorRegistry = ConnectorRegistry(
            stateStore, permissionGranted, approvalGate, autonomyStore, autonomyNotifier, clock, launchIntent,
        )

    }
}
