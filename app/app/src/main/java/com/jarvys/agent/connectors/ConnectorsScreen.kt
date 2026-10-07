package com.jarvys.agent.connectors

import com.jarvys.agent.LucideIcons
import com.jarvys.agent.JarvysListRow
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.mcp.McpConnectionManager
import com.jarvys.agent.mcp.McpOAuthManager
import com.jarvys.agent.mcp.McpServerRepository
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.ColumnScope
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectorsScreen(
    registry: ConnectorRegistry,
    remoteServicesOverride: (@Composable (String?, (String, String) -> Unit) -> Unit)? = null,
    googleServicesOverride: (@Composable (String?, (String, String) -> Unit) -> Unit)? = null,
    onOpenDevice: (String) -> Unit,
    onOpenRemote: (String, String) -> Unit,
    onOpenGoogle: (String, String) -> Unit,
) {
    val definitions by registry.definitions.collectAsState()
    val states by registry.states.collectAsState()
    LaunchedEffect(registry) { registry.refreshStates() }
    Column(Modifier.fillMaxSize().testTag("connectors-list-screen")) {
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val deviceDefinitions = definitions.filter { it.presentationGroup == ConnectorPresentationGroup.ON_DEVICE }
            if (deviceDefinitions.isNotEmpty()) {
                Text(stringResource(R.string.connectors_device_section), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                ConnectorGroup(Modifier.testTag("connectors-device-group")) {
                    deviceDefinitions.forEachIndexed { index, definition ->
                        ConnectorRow(
                            definition = definition,
                            status = when (states[definition.id] ?: ConnectorState.DISCONNECTED) {
                                ConnectorState.CONNECTED -> ConnectorShortStatus.CONNECTED
                                ConnectorState.PERMISSION_REVOKED -> ConnectorShortStatus.PERMISSION_PENDING
                                ConnectorState.DISCONNECTED -> null
                            },
                            onClick = { onOpenDevice(definition.id) },
                        )
                        if (index != deviceDefinitions.lastIndex) ConnectorDivider()
                    }
                }
            }
            FlavorAutonomyUi.ConnectorControls()
            ConnectorsRemoteServicesHost(override = remoteServicesOverride, selectedId = null, onSelect = onOpenRemote)
            ConnectorsGoogleServicesHost(override = googleServicesOverride, selectedId = null, onSelect = onOpenGoogle)
            if (deviceDefinitions.isEmpty() && definitions.isEmpty()) Text(stringResource(R.string.connector_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp), fontSize = 14.sp)
        }
    }
}

@Composable
internal fun ConnectorsDeviceDetailScreen(
    registry: ConnectorRegistry,
    connectorId: String,
    onDisconnected: () -> Unit,
    onMissingConnector: () -> Unit,
) {
    val context = LocalContext.current
    val definitions by registry.definitions.collectAsState()
    val states by registry.states.collectAsState()
    val autonomyRevision by registry.autonomyRevision.collectAsState()
    val definition = definitions.firstOrNull { it.id == connectorId }
    var connectAfterPermission by remember(connectorId) { mutableStateOf<ConnectorDefinition?>(null) }
    var autonomyAfterPermission by remember(connectorId) { mutableStateOf<Pair<ConnectorDefinition, ConnectorOperation>?>(null) }
    var permissionMessage by remember(connectorId) { mutableStateOf<ConnectorUiText?>(null) }
    var permissionMessageAction by remember(connectorId) { mutableStateOf(ConnectorNoticeAction.APP_SETTINGS) }
    fun showPermissionMessage(text: ConnectorUiText, action: ConnectorNoticeAction = ConnectorNoticeAction.APP_SETTINGS) {
        permissionMessage = text
        permissionMessageAction = action
    }
    LaunchedEffect(registry) { registry.refreshStates() }
    LaunchedEffect(connectorId, definition) { if (definition == null) onMissingConnector() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val pending = connectAfterPermission
        connectAfterPermission = null
        if (pending != null && registry.canConnect(pending)) {
            registry.connect(pending.id)
            registry.refreshStates()
        } else if (pending != null) {
            showPermissionMessage(ConnectorUiText(R.string.connector_permission_denied,
                listOf(pending.localizedNameText()), "${pending.name} access was denied. Jarvys cannot use this connector until you allow it."))
        }
    }
    val autonomyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val pending = autonomyAfterPermission
        autonomyAfterPermission = null
        if (pending != null && grants.values.all { it }) {
            runCatching { registry.setAutonomyPolicy(pending.first, pending.second, AutonomyPolicy.ALLOW) }
                .onFailure { showPermissionMessage(ConnectorUiText(R.string.connector_allow_failed,
                    fallback = "Could not enable autonomous actions.")) }
        } else if (pending != null) {
            val needsNotificationSettings = registry.autonomyPermissions(pending.first, pending.second)
                .contains(android.Manifest.permission.POST_NOTIFICATIONS) || !registry.autonomyNotificationsAvailable()
            showPermissionMessage(if (needsNotificationSettings) ConnectorUiText(R.string.connector_notifications_allow_required,
                fallback = "Enable Jarvys app notifications to use Allow mode; the operation remains in Ask mode.")
            else ConnectorUiText(R.string.connector_permission_allow_required,
                fallback = "Required operation permission was denied; the operation remains in Ask mode."),
                if (needsNotificationSettings) ConnectorNoticeAction.APP_NOTIFICATIONS else ConnectorNoticeAction.APP_SETTINGS)
        }
    }
    fun changePolicy(target: ConnectorDefinition, operation: ConnectorOperation, policy: AutonomyPolicy) {
        if (policy != AutonomyPolicy.ALLOW) {
            registry.setAutonomyPolicy(target, operation, policy)
            return
        }
        val missing = registry.autonomyPermissions(target, operation)
        if (missing.isNotEmpty()) {
            autonomyAfterPermission = target to operation
            autonomyPermissionLauncher.launch(missing.toTypedArray())
        } else if (!registry.autonomyNotificationsAvailable()) {
            showPermissionMessage(ConnectorUiText(R.string.connector_notifications_settings_required,
                fallback = "Enable Jarvys app notifications in Android settings to use Allow mode."),
                ConnectorNoticeAction.APP_NOTIFICATIONS)
        } else runCatching { registry.setAutonomyPolicy(target, operation, policy) }
            .onFailure { showPermissionMessage(ConnectorUiText(R.string.connector_allow_failed, fallback = "Could not enable Allow mode.")) }
    }
    if (definition != null) {
        Box(Modifier.fillMaxSize().testTag("connectors-detail-screen")) {
            ConnectorDeviceDetailPage(
                definition = definition,
                state = states[definition.id] ?: registry.state(definition),
                registry = registry,
                autonomyRevision = autonomyRevision,
                onConnect = {
                if (definition.connectionFlow == ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS) {
                    registry.connectFromSystemSettings(definition.id)
                    registry.refreshStates()
                    if (registry.state(definition) != ConnectorState.CONNECTED) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            .onFailure { showPermissionMessage(ConnectorUiText(R.string.connector_notification_settings_failed,
                                fallback = ""), ConnectorNoticeAction.NOTIFICATION_LISTENER) }
                    }
                } else {
                    val missing = definition.connectionPermissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) { registry.connect(definition.id); registry.refreshStates() }
                    else {
                        val activity = context as? Activity
                        val prefs = context.getSharedPreferences(ConnectorStateStore.PREFERENCES, 0)
                        val askedBefore = prefs.getBoolean("permission_asked_${definition.id}", false)
                        val permanentlyDenied = missing.any { permission ->
                            activity != null && !activity.shouldShowRequestPermissionRationale(permission)
                        }
                        if (askedBefore && permanentlyDenied && registry.canConnect(definition)) {
                            registry.connect(definition.id); registry.refreshStates()
                        } else if (askedBefore && permanentlyDenied) {
                            showPermissionMessage(ConnectorUiText(R.string.connector_permission_blocked,
                                listOf(definition.localizedNameText()), fallback = ""))
                        } else {
                            prefs.edit().putBoolean("permission_asked_${definition.id}", true).apply()
                            connectAfterPermission = definition
                            permissionLauncher.launch(missing.toTypedArray())
                        }
                    }
                }
                },
                onDisconnect = { registry.disconnect(definition.id); registry.refreshStates(); onDisconnected() },
                onPolicy = { operation, policy -> changePolicy(definition, operation, policy) },
                openSettings = { openConnectorSettings(context, definition) },
            )
        }
    } else Box(Modifier.fillMaxSize())
    permissionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { permissionMessage = null },
            title = { Text(context.getString(R.string.connector_access_title)) },
            text = { ScrollableDialogContent { Text(message.resolve(context)) } },
            confirmButton = { TextButton(onClick = {
                permissionMessage = null
                when (permissionMessageAction) {
                    ConnectorNoticeAction.NOTIFICATION_LISTENER -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    ConnectorNoticeAction.APP_NOTIFICATIONS -> {
                        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                    ConnectorNoticeAction.APP_SETTINGS -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
                }
            }) { Text(context.getString(when (permissionMessageAction) {
                ConnectorNoticeAction.NOTIFICATION_LISTENER -> R.string.connector_notification_settings
                ConnectorNoticeAction.APP_NOTIFICATIONS -> R.string.connector_app_notification_settings
                ConnectorNoticeAction.APP_SETTINGS -> R.string.connector_open_app_settings
            })) } },
            dismissButton = { TextButton(onClick = { permissionMessage = null }) { Text(context.getString(R.string.connector_close)) } },
        )
    }
}

@Composable
internal fun ConnectorsRemoteServiceScreen(
    serviceId: String,
    override: (@Composable (String?, (String, String) -> Unit) -> Unit)?,
    onSelect: (String, String) -> Unit,
    onMissing: () -> Unit,
    onDetailExit: () -> Unit,
) {
    val exists = override != null || RemoteServiceCatalog.find(serviceId) != null
    LaunchedEffect(serviceId, exists) { if (!exists) onMissing() }
    if (exists) Box(Modifier.fillMaxSize().testTag("connectors-remote-detail")) {
        ConnectorsRemoteServicesHost(override, serviceId, onSelect, onDetailExit)
    }
}

@Composable
internal fun ConnectorsGoogleServiceScreen(
    serviceId: String,
    override: (@Composable (String?, (String, String) -> Unit) -> Unit)?,
    onSelect: (String, String) -> Unit,
    onMissing: () -> Unit,
    onDetailExit: () -> Unit,
) {
    val context = LocalContext.current
    val exists = override != null || flavorSupportsRemoteService(context, serviceId)
    LaunchedEffect(serviceId, exists) { if (!exists) onMissing() }
    if (exists) Box(Modifier.fillMaxSize().testTag("connectors-google-detail")) {
        ConnectorsGoogleServicesHost(override, serviceId, onSelect, onDetailExit)
    }
}

@Composable
internal fun ConnectorsRemoteServicesHost(
    override: (@Composable (String?, (String, String) -> Unit) -> Unit)?,
    selectedId: String?,
    onSelect: (String, String) -> Unit,
    onDetailExit: () -> Unit = {},
) {
    if (override != null) override(selectedId, onSelect) else {
        val context = LocalContext.current
        val repository = remember(context) { McpServerRepository.get(context) }
        val connections = remember(context) { McpConnectionManager.get(context) }
        val oauth = remember(repository) { McpOAuthManager.get(repository) }
        RemoteServicesSection(repository, connections, oauth, selectedId, onSelect, onDetailExit)
    }
}

@Composable
internal fun ConnectorsGoogleServicesHost(
    override: (@Composable (String?, (String, String) -> Unit) -> Unit)?,
    selectedId: String?,
    onSelect: (String, String) -> Unit,
    onDetailExit: () -> Unit = {},
) {
    if (override != null) override(selectedId, onSelect)
    else FlavorRemoteServicesContent(selectedId, onSelect, onDetailExit)
}

@Composable
private fun ConnectorDeviceDetailPage(
    definition: ConnectorDefinition,
    state: ConnectorState,
    registry: ConnectorRegistry,
    autonomyRevision: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPolicy: (ConnectorOperation, AutonomyPolicy) -> Unit,
    openSettings: () -> Unit,
) {
    val context = LocalContext.current
    val connected = state == ConnectorState.CONNECTED
    val recentActions = remember(definition.id, autonomyRevision) { registry.recentAutonomyActions(definition.id, 10) }
    val subtitle = when (state) {
        ConnectorState.CONNECTED -> context.getString(R.string.connector_on_device)
        ConnectorState.PERMISSION_REVOKED -> context.getString(R.string.connector_status_permission_pending)
        ConnectorState.DISCONNECTED -> context.getString(R.string.connector_on_device)
    }
    ConnectorDetailScaffold(
        title = definition.localizedNameText().resolve(context), subtitle = subtitle,
        icon = { DeviceConnectorIconTile(connectorIcon(definition.id), size = 42.dp) },
        connected = connected,
        summary = definition.localizedDescriptionText().resolve(context),
        primaryActionLabel = context.getString(if (state == ConnectorState.PERMISSION_REVOKED) R.string.connector_reconnect else R.string.connector_connect),
        onPrimaryAction = onConnect,
        disconnectLabel = context.getString(R.string.connector_disconnect),
        disconnectExplanation = context.getString(R.string.connector_disconnect_explanation),
        onDisconnect = onDisconnect,
        showIdentityHeader = false,
        content = {
            val reads = definition.operations.filterNot { it.write }
            val writes = definition.operations.filter { it.write }
            if (reads.isNotEmpty()) ConnectorPermissionGroup(context.getString(R.string.remote_service_read_tool)) {
                reads.forEach { operation ->
                    ConnectorOperationPolicyRow(operation.localizedDisplayText().resolve(context),
                        context.getString(R.string.connector_policy_allow), null)
                }
            }
            if (writes.isNotEmpty()) ConnectorPermissionGroup(context.getString(R.string.remote_service_write_tool)) {
                writes.forEach { operation ->
                    AutonomyPolicyRow(
                        connectorId = definition.id, operation = operation,
                        configuredPolicy = registry.configuredAutonomyPolicy(definition, operation),
                        effectivePolicy = registry.autonomyPolicy(definition, operation),
                        effectiveReason = registry.autonomyPolicyUnavailableUiReason(definition, operation),
                        onPolicy = { onPolicy(operation, it) },
                    )
                }
            }
            if (definition.id == LocationConnector.ID && !LocationConnector.isSystemLocationEnabled(context)) {
                Text(stringResource(R.string.connector_location_disabled), color = MaterialTheme.colorScheme.error)
            }
        },
        about = {
            Text(definition.localizedDescriptionText().resolve(context))
            Text(stringResource(R.string.connector_data_provider_disclosure))
            Text(if (definition.connectionFlow == ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS)
                stringResource(R.string.connector_notification_access_disclosure)
            else stringResource(R.string.connector_connected_access_disclosure))
            if (definition.writePermissions.isNotEmpty()) Text(stringResource(R.string.connector_write_permission_disclosure))
            if (definition.operations.any { it.write && !it.autonomyAllowed }) Text(stringResource(R.string.connector_system_app_confirmation))
        },
        advanced = {
            if (definition.configurationFields.isNotEmpty()) definition.configurationFields.forEach { field -> Text(field.label) }
            TextButton(onClick = openSettings) { Text(stringResource(if (definition.connectionFlow == ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS)
                R.string.connector_notification_settings else R.string.connector_app_settings)) }
            if (definition.id == LocationConnector.ID && !LocationConnector.isSystemLocationEnabled(context)) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                    Text(stringResource(R.string.connector_location_settings))
                }
            }
            if (recentActions.isNotEmpty()) {
                Text(stringResource(R.string.connector_recent_autonomous_actions), fontWeight = FontWeight.SemiBold)
                recentActions.forEach { action ->
                    val op = definition.operations.firstOrNull { it.name == action.operationName }?.localizedDisplayText()?.resolve(context)
                        ?: action.operationLabel
                    Text(context.getString(R.string.connector_recent_action_item,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(action.timestampMillis)),
                        op, localizedAuditSummary(context, action.summary)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
internal fun ConnectorDetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(JarvysUiTokens.ScreenPadding)) {
        JarvysSectionLabel(title)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun AutonomyPolicyRow(
    connectorId: String,
    operation: ConnectorOperation,
    configuredPolicy: AutonomyPolicy,
    effectivePolicy: AutonomyPolicy,
    effectiveReason: ConnectorUiText?,
    onPolicy: (AutonomyPolicy) -> Unit,
) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(operation.localizedDisplayText().resolve(context), Modifier.weight(1f), fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        ConnectorPolicySelector(
            selectedLabelResource = when (configuredPolicy) {
                AutonomyPolicy.ASK -> R.string.connector_policy_ask
                AutonomyPolicy.ALLOW -> R.string.connector_policy_allow
                AutonomyPolicy.DENY -> R.string.connector_policy_deny
            },
            choices = buildList {
                add(ConnectorPolicyChoice(R.string.connector_policy_ask) { onPolicy(AutonomyPolicy.ASK) })
                if (operation.autonomyAllowed) add(ConnectorPolicyChoice(R.string.connector_policy_allow) { onPolicy(AutonomyPolicy.ALLOW) })
                add(ConnectorPolicyChoice(R.string.connector_policy_deny) { onPolicy(AutonomyPolicy.DENY) })
            },
        )
    }
    if (configuredPolicy != effectivePolicy) {
        Text(
            context.getString(R.string.connector_active_policy, effectivePolicy.label(context), effectiveReason?.resolve(context).orEmpty()),
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp,
        )
    }
    if (!operation.autonomyAllowed) {
        Text(context.getString(R.string.connector_system_app_confirmation),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun ConnectorPermissionGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(JarvysUiTokens.ScreenPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JarvysSectionLabel(title)
            content()
        }
    }
}

@Composable
private fun ConnectorOperationPolicyRow(name: String, value: String, onSelect: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onSelect == null) Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        else TextButton(onClick = onSelect) { Text(value) }
    }
}

private fun AutonomyPolicy.label(context: android.content.Context): String = context.getString(when (this) {
    AutonomyPolicy.ASK -> R.string.connector_policy_ask
    AutonomyPolicy.ALLOW -> R.string.connector_policy_allow
    AutonomyPolicy.DENY -> R.string.connector_policy_deny
})

private fun ConnectorDefinition.localizedNameText() = ConnectorUiText(displayNameResourceId, fallback = name)
private fun ConnectorDefinition.localizedDescriptionText() = ConnectorUiText(descriptionResourceId, fallback = description)
private fun ConnectorOperation.localizedDisplayText() = ConnectorUiText(displayLabelResourceId, fallback = displayLabel)
private fun ConnectorOperation.localizedDescriptionText() = ConnectorUiText(descriptionResourceId, fallback = description)

private fun localizedAuditSummary(context: android.content.Context, summary: String): String = when (summary) {
    "Dialer opened", "Dialer opened; user must press Call" -> context.getString(R.string.connector_audit_dialer_opened)
    "Call requested", "Telecom call request submitted; connection unverified" -> context.getString(R.string.connector_audit_call_requested)
    "SMS send requested", "SMS submission requested; delivery unconfirmed" -> context.getString(R.string.connector_audit_sms_requested)
    "Completed automatically" -> context.getString(R.string.connector_audit_completed)
    else -> summary
}

private enum class ConnectorNoticeAction { APP_SETTINGS, APP_NOTIFICATIONS, NOTIFICATION_LISTENER }

private fun openConnectorSettings(context: android.content.Context, definition: ConnectorDefinition) {
    val intent = if (definition.connectionFlow == ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
    context.startActivity(intent)
}

@Composable
private fun ConnectorGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    JarvysGroup(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { content() }
}

@Composable
private fun ConnectorRow(
    definition: ConnectorDefinition,
    status: ConnectorShortStatus?,
    onClick: () -> Unit,
) {
    val statusText = status?.let {
        stringResource(when (it) {
                ConnectorShortStatus.CONNECTED -> R.string.connector_status_connected
                ConnectorShortStatus.CONNECTING -> R.string.remote_service_connecting_short
                ConnectorShortStatus.REAUTHORIZE -> R.string.connector_status_reauthorize
                ConnectorShortStatus.ERROR -> R.string.connector_status_error
                ConnectorShortStatus.PERMISSION_PENDING -> R.string.connector_status_permission_pending
            })
    }
    JarvysListRow(
        title = definition.localizedNameText().resolve(LocalContext.current),
        subtitle = statusText,
        subtitleColor = if (status in setOf(ConnectorShortStatus.ERROR, ConnectorShortStatus.PERMISSION_PENDING))
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        icon = connectorIcon(definition.id),
        onClick = onClick,
        trailing = { Icon(LucideIcons.ChevronRight, contentDescription = stringResource(R.string.connector_details_accessibility),
            modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun connectorIcon(id: String): ImageVector = when (id) {
    ContactsConnector.ID -> LucideIcons.ContactRound
    DialerConnector.ID -> LucideIcons.Smartphone
    SmsComposerConnector.ID -> LucideIcons.MessageSquare
    NotificationsConnector.ID -> LucideIcons.Bell
    LocationConnector.ID -> LucideIcons.MapPin
    else -> LucideIcons.Calendar
}

@Composable
private fun ConnectorDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 72.dp, end = 16.dp), thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
}
