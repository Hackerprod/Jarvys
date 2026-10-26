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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
            val catalogDefinitions = visibleConnectorCatalogEntries(definitions)
            val deviceDefinitions = catalogDefinitions.filter { it.presentationGroup == ConnectorPresentationGroup.ON_DEVICE }
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
            val hasGoogleRows = ConnectorsGoogleServicesAvailable(googleServicesOverride)
            ConnectorsRemoteServicesHost(
                override = remoteServicesOverride,
                selectedId = null,
                onSelect = onOpenRemote,
                leadingContent = if (hasGoogleRows) {
                    { ConnectorsGoogleServicesHost(googleServicesOverride, null, onOpenGoogle, rowsOnly = true) }
                } else null,
            )
            if (deviceDefinitions.isEmpty() && catalogDefinitions.isEmpty()) Text(stringResource(R.string.connector_empty),
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
    val connectionReady by registry.connectionReady.collectAsState()
    val autonomyRevision by registry.autonomyRevision.collectAsState()
    val definition = definitions.firstOrNull { it.id == connectorId }
    val observer = remember(registry, connectorId) { ConnectorUiObserver(context) }
    val preLaunchRequest = remember(registry, connectorId) {
        java.util.concurrent.atomic.AtomicReference<ConnectorRegistry.ConnectionRequest?>()
    }
    var connectAfterPermission by androidx.compose.runtime.saveable.rememberSaveable(connectorId) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(observer) { onDispose {
        observer.close()
        // Permission preparation is not an accepted activation. Navigation abandons that untouched
        // request; configuration recreation preserves the exact ID already owned by the OS launcher.
        val preparing = preLaunchRequest.getAndSet(null)
        preparing?.let(registry::abortConnectionRequest)
        if (connectionActivity(context)?.isChangingConfigurations != true) {
            connectAfterPermission?.takeIf { it != preparing?.requestId }
                ?.let(registry::resolvePendingRequest)?.let(registry::abortConnectionRequest)
        }
    } }
    var submittedRequest by remember(connectorId) { mutableStateOf<String?>(null) }
    var permissionMessage by remember(connectorId) { mutableStateOf<ConnectorUiText?>(null) }
    var permissionMessageAction by remember(connectorId) { mutableStateOf(ConnectorNoticeAction.APP_SETTINGS) }
    fun showPermissionMessage(text: ConnectorUiText, action: ConnectorNoticeAction = ConnectorNoticeAction.APP_SETTINGS) {
        permissionMessage = text
        permissionMessageAction = action
    }
    fun showTransition(result: ConnectorRegistry.ConnectorTransitionResult?, failed: Boolean) {
        connectionTransitionMessage(result, failed)?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    fun submit(request: ConnectorRegistry.ConnectionRequest, settings: Boolean = false, disconnect: Boolean = false) {
        preLaunchRequest.compareAndSet(request, null)
        submittedRequest = request.requestId
        observer.observe(registry.submitConnectionRequest(request)) { result, failed ->
            if (submittedRequest == request.requestId) submittedRequest = null
            if (request.status == ConnectorRegistry.ConnectionRequest.Status.ACCEPTED &&
                !registry.isConnectionRequestCurrent(request)) return@observe
            showTransition(result, failed)
            if (connectionTransitionPersisted(result)) {
                if (disconnect) onDisconnected()
                else if (settings && registry.state(request.connectorId) != ConnectorState.CONNECTED) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        .onFailure { showPermissionMessage(ConnectorUiText(R.string.connector_notification_settings_failed,
                            fallback = ""), ConnectorNoticeAction.NOTIFICATION_LISTENER) }
                }
            }
        }
    }
    fun abort(request: ConnectorRegistry.ConnectionRequest) {
        preLaunchRequest.compareAndSet(request, null)
        observer.observe(registry.abortConnectionRequest(request)) { result, failed ->
            if (!registry.isConnectionRequestCurrent(request)) return@observe
            if (failed || result?.status != ConnectorRegistry.ConnectorTransitionResult.Status.ABORTED) showTransition(result, failed)
        }
    }
    LaunchedEffect(registry) { registry.refreshStates() }
    LaunchedEffect(connectorId, definition) { if (definition == null) onMissingConnector() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val pending = connectAfterPermission?.let(registry::resolvePendingRequest)
        connectAfterPermission = null
        val current = pending?.let { registry.get(it.connectorId) }
        if (pending == null || current == null || !registry.isConnectionRequestCurrent(pending)) {
            android.widget.Toast.makeText(context, R.string.connector_transition_obsolete, android.widget.Toast.LENGTH_LONG).show()
        } else if (registry.canConnect(current)) submit(pending)
        else {
            abort(pending)
            showPermissionMessage(ConnectorUiText(R.string.connector_permission_denied,
                listOf(current.localizedNameText()), fallback = ""))
        }
    }
    if (definition != null) {
        Box(Modifier.fillMaxSize().testTag("connectors-detail-screen")) {
            ConnectorDeviceDetailPage(
                definition = definition,
                state = states[definition.id] ?: registry.state(definition),
                registry = registry,
                autonomyRevision = autonomyRevision,
                connectionActionEnabled = connectionReady && submittedRequest == null && connectAfterPermission == null,
                onConnect = {
                    val settings = definition.connectionFlow == ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS
                    val request = registry.captureConnectionRequest(definition.id,
                        if (settings) ConnectionStateCoordinator.Kind.SETTINGS_CONNECT else ConnectionStateCoordinator.Kind.CONNECT)
                    if (request.status != ConnectorRegistry.ConnectionRequest.Status.ACCEPTED) submit(request)
                    else if (settings) submit(request, settings = true)
                    else {
                        val missing = definition.connectionPermissions.filter {
                            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) submit(request)
                        else {
                            preLaunchRequest.set(request)
                            val activity = connectionActivity(context)
                            val permanentlyDenied = missing.any { permission ->
                                activity != null && !activity.shouldShowRequestPermissionRationale(permission)
                            }
                            observer.observe(ConnectorSettingsWork.submit {
                                context.applicationContext.getSharedPreferences(ConnectorStateStore.PREFERENCES, 0)
                                    .getBoolean("permission_asked_${definition.id}", false)
                            }) { askedBefore, failed ->
                                if (!registry.isConnectionRequestCurrent(request)) return@observe
                                if (failed) {
                                    abort(request); showTransition(null, true)
                                } else if (askedBefore == true && permanentlyDenied && registry.canConnect(definition)) submit(request)
                                else if (askedBefore == true && permanentlyDenied) {
                                    abort(request)
                                    showPermissionMessage(ConnectorUiText(R.string.connector_permission_blocked,
                                        listOf(definition.localizedNameText()), fallback = ""))
                                } else {
                                    observer.observe(ConnectorSettingsWork.submit {
                                        context.applicationContext.getSharedPreferences(ConnectorStateStore.PREFERENCES, 0)
                                            .edit().putBoolean("permission_asked_${definition.id}", true).apply()
                                    }) { _, markingFailed ->
                                        if (!registry.isConnectionRequestCurrent(request)) return@observe
                                        if (markingFailed) { abort(request); showTransition(null, true) }
                                        else {
                                            connectAfterPermission = request.requestId
                                            preLaunchRequest.compareAndSet(request, null)
                                            runCatching { permissionLauncher.launch(missing.toTypedArray()) }.onFailure {
                                                connectAfterPermission = null; abort(request); showTransition(null, true)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                onDisconnect = {
                    connectAfterPermission = null
                    submit(registry.captureConnectionRequest(definition.id, ConnectionStateCoordinator.Kind.DISCONNECT), disconnect = true)
                },
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
    leadingContent: (@Composable () -> Unit)? = null,
) {
    if (override != null) {
        if (selectedId == null && leadingContent != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                leadingContent()
                override(selectedId, onSelect)
            }
        } else override(selectedId, onSelect)
    } else {
        val context = LocalContext.current
        val repository = remember(context) { McpServerRepository.get(context) }
        val connections = remember(context) { McpConnectionManager.get(context) }
        val oauth = remember(repository) { McpOAuthManager.get(repository) }
        RemoteServicesSection(repository, connections, oauth, selectedId, onSelect, onDetailExit, leadingContent)
    }
}

@Composable
internal fun ConnectorsGoogleServicesHost(
    override: (@Composable (String?, (String, String) -> Unit) -> Unit)?,
    selectedId: String?,
    onSelect: (String, String) -> Unit,
    onDetailExit: () -> Unit = {},
    rowsOnly: Boolean = false,
) {
    if (override != null) override(selectedId, onSelect)
    else FlavorRemoteServicesContent(selectedId, onSelect, onDetailExit, rowsOnly)
}

@Composable
private fun ConnectorsGoogleServicesAvailable(override: (@Composable (String?, (String, String) -> Unit) -> Unit)?): Boolean {
    val context = LocalContext.current
    return override != null || flavorSupportsRemoteService(context, "gmail")
}

@Composable
internal fun ConnectorDeviceDetailPage(
    definition: ConnectorDefinition,
    state: ConnectorState,
    registry: ConnectorRegistry,
    autonomyRevision: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    openSettings: () -> Unit,
    connectionActionEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val connected = state == ConnectorState.CONNECTED
    var auditRetry by remember(definition.id) { mutableStateOf(0) }
    val recentAudit by produceState<Result<List<AutonomyAuditRecord>>?>(null,
        registry, definition.id, autonomyRevision to auditRetry) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { Result.success(registry.recentAutonomyActions(definition.id, 10)) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { Result.failure(IllegalStateException("Audit history unavailable")) }
        }
    }
    val recentActions = recentAudit?.getOrNull().orEmpty()
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
        primaryActionEnabled = connectionActionEnabled,
        showDisconnectWhenUnavailable = true,
        onPrimaryAction = onConnect,
        disconnectLabel = context.getString(R.string.connector_disconnect),
        disconnectExplanation = context.getString(R.string.connector_disconnect_explanation),
        onDisconnect = onDisconnect,
        showIdentityHeader = false,
        content = {
            val reads = definition.operations.filterNot { it.write }
            val writes = definition.operations.filter { it.write }
            if (reads.isNotEmpty()) ConnectorDetailSection(
                stringResource(R.string.remote_service_read_tool), contentPadding = PaddingValues(0.dp)) {
                reads.forEach { operation -> AutonomyPolicyRow(registry, definition, operation) }
            }
            if (writes.isNotEmpty()) ConnectorDetailSection(
                stringResource(R.string.remote_service_write_tool), contentPadding = PaddingValues(0.dp)) {
                writes.forEach { operation -> AutonomyPolicyRow(registry, definition, operation) }
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
            if (recentAudit == null) {
                Text(stringResource(R.string.connector_audit_loading))
            } else if (recentAudit?.isFailure == true) {
                Text(stringResource(R.string.connector_audit_incomplete), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { auditRetry += 1 }) { Text(stringResource(R.string.chat_history_load_retry)) }
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
internal fun ConnectorDetailSection(
    title: String,
    contentPadding: PaddingValues = PaddingValues(JarvysUiTokens.ScreenPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    // Edge-to-edge sections let their rows own the horizontal inset; the card still needs the same
    // inset above the header and below the last row (rows keep their own vertical inset).
    val rowsOwnInset = contentPadding.calculateLeftPadding(layoutDirection) == 0.dp
        && contentPadding.calculateRightPadding(layoutDirection) == 0.dp
    val headerInset = if (rowsOwnInset) JarvysUiTokens.ScreenPadding else 0.dp
    JarvysGroup(contentPadding = contentPadding) {
        if (rowsOwnInset) Spacer(Modifier.height(JarvysUiTokens.ScreenPadding))
        JarvysSectionLabel(title, Modifier.padding(horizontal = headerInset))
        Spacer(Modifier.height(8.dp))
        content()
        if (rowsOwnInset) Spacer(Modifier.height(JarvysUiTokens.ScreenPadding - ConnectorPolicyRowVerticalInset))
    }
}

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
    NotificationsConnector.ID -> LucideIcons.Bell
    LocationConnector.ID -> LucideIcons.MapPin
    else -> LucideIcons.Calendar
}

@Composable
private fun ConnectorDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 72.dp, end = 16.dp), thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
}
