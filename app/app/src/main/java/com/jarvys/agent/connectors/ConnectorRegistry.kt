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
    fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation
    fun invokePrepared(operation: String, arguments: JSONObject, preparation: ConnectorWritePreparation,
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

    fun autonomyPolicy(definition: ConnectorDefinition, operation: ConnectorOperation): AutonomyPolicy {
        val configured = configuredAutonomyPolicy(definition, operation)
        if (configured != AutonomyPolicy.ALLOW) return configured
        return if (autonomyPolicyUnavailableReason(definition, operation) == null) AutonomyPolicy.ALLOW else AutonomyPolicy.ASK
    }

    fun configuredAutonomyPolicy(definition: ConnectorDefinition, operation: ConnectorOperation): AutonomyPolicy =
        autonomyStore.policy(definition.id, operation.name)

    fun autonomyPolicyUnavailableReason(definition: ConnectorDefinition, operation: ConnectorOperation): String? {
        if (configuredAutonomyPolicy(definition, operation) != AutonomyPolicy.ALLOW) return null
        if (!operation.autonomyAllowed) return "This operation requires confirmation in the system app."
        if (state(definition) != ConnectorState.CONNECTED) return "Reconnect this connector to use Allow mode."
        val missing = writePermissions(definition, operation).firstOrNull { !permissionGranted(it) }
        if (missing != null) return "Grant ${missing.substringAfterLast('.')} permission to use Allow mode."
        if (!autonomyNotifier.canPost()) {
            return if (autonomyNotifier.missingRuntimePermission() != null) {
                "Grant notification permission to use Allow mode."
            } else {
                "Enable Jarvys notifications to use Allow mode."
            }
        }
        return null
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
        autonomyAllowUnavailableReason(definition, operation)?.let { return it }
        val failure = runCatching { setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW) }.exceptionOrNull()
        return failure?.let { ConnectorUiText(R.string.connector_allow_failed, fallback = it.message ?: "Could not enable Allow mode.") }
    }

    private fun autonomyAllowUnavailableReason(definition: ConnectorDefinition, operation: ConnectorOperation): ConnectorUiText? {
        if (!operation.write) return ConnectorUiText(R.string.approval_autonomy_operation_unavailable, fallback = "Allow mode is only available for write operations.")
        if (!operation.autonomyAllowed) return ConnectorUiText(R.string.approval_autonomy_system_confirmation, fallback = "This operation requires confirmation in the system app.")
        if (state(definition) != ConnectorState.CONNECTED) return ConnectorUiText(
            R.string.approval_autonomy_reconnect_required, fallback = "Reconnect this connector to use Allow mode.",
        )
        if (writePermissions(definition, operation).any { !permissionGranted(it) }) return ConnectorUiText(
            R.string.approval_autonomy_permission_required,
            listOf(ConnectorUiText(definition.permissionLabelResourceId, fallback = definition.permissionLabel)),
            "Grant ${definition.permissionLabel} permission to use Allow mode.",
        )
        if (!autonomyNotifier.canPost()) {
            return if (autonomyNotifier.missingRuntimePermission() != null) ConnectorUiText(
                R.string.approval_autonomy_notification_permission_required,
                fallback = "Grant notification permission to use Allow mode.",
            ) else ConnectorUiText(
                R.string.approval_notifications_required_reason,
                fallback = "Enable Jarvys notifications to use Allow mode.",
            )
        }
        return null
    }

    fun autonomyPermissions(definition: ConnectorDefinition, operation: ConnectorOperation): List<String> =
        (writePermissions(definition, operation).filterNot(permissionGranted) +
            listOfNotNull(autonomyNotifier.missingRuntimePermission())).distinct()

    fun autonomyNotificationsAvailable(): Boolean = autonomyNotifier.canPost()

    fun setAutonomyPolicy(definition: ConnectorDefinition, operation: ConnectorOperation, policy: AutonomyPolicy) {
        require(operation.write) { "Autonomy policy applies only to write operations" }
        if (policy == AutonomyPolicy.ALLOW) {
            require(operation.autonomyAllowed) { "This operation requires confirmation in the system app and cannot use Allow" }
            require(state(definition) == ConnectorState.CONNECTED) { "Connect this device connector before changing its autonomy policy" }
            require(writePermissions(definition, operation).all(permissionGranted)) { "Grant the operation permission before enabling Allow" }
            require(autonomyNotifier.canPost()) { "Enable Jarvys notifications before enabling Allow" }
        }
        autonomyStore.setPolicy(definition.id, operation.name, policy)
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
        val currentState = state(current)
        if (currentState != ConnectorState.CONNECTED) {
            refreshStates()
            if (currentState == ConnectorState.PERMISSION_REVOKED) {
                error("El permiso de ${current.permissionLabel} fue revocado; volvé a conectarlo en Conectores")
            }
            error("${current.name} está desconectado; conectalo de nuevo en Conectores")
        }
        val permissions = if (operation.write) writePermissions(current, operation) else readPermissions(current, operation)
        if (!operation.write && !permissions.all(permissionGranted)) {
                refreshStates()
                error("El permiso de ${current.permissionLabel} fue revocado; volvé a conectarlo en Conectores")
        }
        token.throwIfCancelled()
        val runtime = requireNotNull(current.runtime) { "El conector ${current.name} no tiene runtime" }
        if (!operation.write) return runtime.invoke(operation.name, arguments, token)

        val configuredPolicy = autonomyStore.policy(current.id, operation.name)
        if (configuredPolicy == AutonomyPolicy.DENY) {
            error("La política de autonomía de ${operation.displayLabel} está en Deny")
        }
        var autonomousWriteValidationReason: ConnectorUiText? = null
        if (configuredPolicy == AutonomyPolicy.ALLOW && operation.autonomyAllowed) {
            try {
                (runtime as? AgentRunScopedConnectorRuntime)?.validateAutonomousWrite(
                    operation.name, arguments, token.generation(),
                )
            } catch (failure: AutonomousWriteValidationFailure) {
                autonomousWriteValidationReason = failure.userReason
            }
        }
        val effectivePolicy = if (autonomousWriteValidationReason != null) AutonomyPolicy.ASK
            else autonomyPolicy(current, operation)
        val preparation = runtime.prepareWrite(operation.name, arguments, token)
        val missingWritePermission = permissions.firstOrNull { !permissionGranted(it) }
        val autonomous = effectivePolicy == AutonomyPolicy.ALLOW
        var permissionDeniedFallback = false
        if (!autonomous) {
            val missingAutonomyPermission = if (configuredPolicy == AutonomyPolicy.ALLOW) autonomyNotifier.missingRuntimePermission() else null
            val reasonText = autonomousWriteValidationReason ?: when {
                configuredPolicy == AutonomyPolicy.ALLOW && !operation.autonomyAllowed ->
                    ConnectorUiText(R.string.approval_system_confirmation_reason,
                        fallback = "This write requires confirmation in the system app and cannot run automatically.")
                configuredPolicy == AutonomyPolicy.ALLOW && missingWritePermission != null ->
                    ConnectorUiText(R.string.approval_permission_required_reason,
                        listOf(missingWritePermission.substringAfterLast('.')),
                        "Grant ${missingWritePermission.substringAfterLast('.')} permission to enable automatic execution.")
                configuredPolicy == AutonomyPolicy.ALLOW && !autonomyNotifier.canPost() ->
                    ConnectorUiText(R.string.approval_notifications_required_reason,
                        fallback = "Enable Jarvys notifications to keep Allow mode auditable.")
                else -> null
            }
            val originalLines = preparation.approval.localizedLines
                ?: preparation.approval.lines.map { ConnectorUiText(fallback = it) }
            val requestLines = if (reasonText == null) originalLines else listOf(reasonText) + originalLines
            val decision = approvalGate.request(
                preparation.approval.copy(
                    // Notification permission is a prerequisite for persistent Allow mode, not for this
                    // individually approved write. Do not gate the current action on that permission.
                    permission = missingWritePermission,
                    lines = requestLines.map { it.fallback },
                    localizedLines = requestLines,
                    allowAlwaysAvailable = configuredPolicy != AutonomyPolicy.ALLOW && operation.autonomyAllowed,
                    autonomyConnectorId = current.id,
                    autonomyOperationName = operation.name,
                    requester = requester,
                    requesterColorKey = requesterColorKey,
                    permissionLabel = missingWritePermission?.let {
                        ConnectorUiText(current.permissionLabelResourceId, fallback = current.permissionLabel)
                    },
                ),
                token,
            )
            when (decision) {
                ApprovalDecision.APPROVED, ApprovalDecision.APPROVED_ALLOW_ALWAYS,
                ApprovalDecision.APPROVED_ALLOW_FAILED -> Unit
                ApprovalDecision.DENIED -> error("El usuario rechazó la acción; no la reintentes sin una nueva solicitud explícita")
                ApprovalDecision.PERMISSION_DENIED -> {
                    if (preparation.fallbackOnPermissionDenied) permissionDeniedFallback = true
                    else error("Se denegó el permiso de escritura de ${current.name}; habilitalo en Ajustes de la app y volvé a intentar")
                }
                ApprovalDecision.PERMISSION_FALLBACK_LAUNCHED -> {
                    if (preparation.fallbackOnPermissionDenied) permissionDeniedFallback = true
                    else error("No hay un fallback aprobado para esta operación")
                }
                ApprovalDecision.ACTION_FAILED -> error("No se pudo abrir la aplicación del sistema para completar esta acción")
                ApprovalDecision.EXPIRED -> error("La aprobación expiró; pedile al usuario que intente de nuevo")
                ApprovalDecision.CANCELLED -> throw java.util.concurrent.CancellationException("Aprobación cancelada")
            }
        }
        token.throwIfCancelled()
        if (state(current) != ConnectorState.CONNECTED) {
            refreshStates()
            error("El permiso de ${current.permissionLabel} fue revocado; volvé a conectarlo en Conectores")
        }
        if (!permissionDeniedFallback && !permissions.all(permissionGranted)) {
            error("Falta el permiso de escritura de ${current.name}; habilitalo en Ajustes de la app y volvé a intentar")
        }
        val result = runtime.invokePrepared(
            operation.name,
            preparation.executionArguments,
            preparation.copy(permissionDeniedFallback = permissionDeniedFallback),
            token,
        )
        if (autonomous) {
            val record = AutonomyAuditRecord(
                timestampMillis = clock(),
                connectorId = current.id,
                connectorName = current.name,
                operationName = operation.name,
                operationLabel = operation.displayLabel,
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
        "dialer_opened" -> "Dialer opened"
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
        ): ConnectorRegistry = ConnectorRegistry(
            stateStore, permissionGranted, approvalGate, autonomyStore, autonomyNotifier, clock,
        )

    }
}
