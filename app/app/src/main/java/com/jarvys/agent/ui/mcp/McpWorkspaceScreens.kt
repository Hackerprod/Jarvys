package com.jarvys.agent.ui.mcp

import android.widget.Toast
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import org.json.JSONObject
import com.jarvys.agent.mcp.*
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysListRow
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.JarvysChoice
import com.jarvys.agent.JarvysChoiceGroup
import com.jarvys.agent.JarvysSwitchRow
import com.jarvys.agent.JarvysPrimaryButton
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.R
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorDetailScaffold
import com.jarvys.agent.connectors.ConnectorPolicyChoice
import com.jarvys.agent.connectors.ConnectorPolicySelector
import com.jarvys.agent.connectors.ConnectorPermissionRow
import com.jarvys.agent.connectors.ConnectorPolicyButton
import com.jarvys.agent.connectors.DeviceConnectorIconTile

@Composable
fun McpServersScreen(
    repository: McpServerRepository,
    connectionManager: McpConnectionManager,
    onOpenServer: (String) -> Unit,
) {
    val context = LocalContext.current
    val servers by repository.servers.collectAsState()
    val customServers = remember(servers) { servers.filter { it.catalogServiceId == null } }
    val states by connectionManager.states.collectAsState()
    var pendingConnectNotices by remember { mutableStateOf(emptyMap<String, String>()) }
    var serverQuery by remember { mutableStateOf("") }
    LaunchedEffect(states, pendingConnectNotices) {
        val remaining = pendingConnectNotices.toMutableMap()
        pendingConnectNotices.forEach { (serverId, alias) ->
            when (val status = states[serverId]?.status) {
                McpConnectionStatus.READY -> remaining.remove(serverId)
                McpConnectionStatus.ERROR, McpConnectionStatus.AUTH_REQUIRED,
                McpConnectionStatus.REAUTH_REQUIRED, McpConnectionStatus.PERMISSION_REQUIRED -> {
                    val detail = states[serverId]?.message?.takeIf(String::isNotBlank) ?: context.getString(statusLabel(status))
                    Toast.makeText(context, context.getString(R.string.mcp_connection_failed, alias, detail), Toast.LENGTH_LONG).show()
                    remaining.remove(serverId)
                }
                else -> Unit
            }
        }
        if (remaining != pendingConnectNotices) pendingConnectNotices = remaining
    }

    fun connectWithNotice(serverId: String, alias: String) {
        pendingConnectNotices = pendingConnectNotices + (serverId to alias)
        connectionManager.connect(serverId)
    }
    LaunchedEffect(servers) {
        customServers.filter { it.enabled }.forEach { server ->
            if (connectionManager.state(server.id).status == McpConnectionStatus.DISCONNECTED) {
                connectWithNotice(server.id, server.alias)
            }
        }
    }
    val visibleServers = remember(customServers, serverQuery) {
        customServers.filter { server -> serverQuery.isBlank()
            || server.alias.contains(serverQuery.trim(), true)
            || McpToolPresentation.endpointHost(server.endpoint).contains(serverQuery.trim(), true) }
    }
    Column(Modifier.fillMaxSize().testTag("mcp-server-list")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.mcp_add_endpoint_help), Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (customServers.size >= 4) JarvysTextField(
                value = serverQuery,
                onValueChange = { serverQuery = it },
                label = { Text(stringResource(R.string.mcp_server_search_hint)) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(LucideIcons.Search, null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
            )
        }
        if (customServers.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(LucideIcons.Network, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Text(stringResource(R.string.mcp_no_servers), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.mcp_empty_directory_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (visibleServers.isEmpty()) {
            Text(stringResource(R.string.mcp_server_search_empty),
                Modifier.fillMaxWidth().padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(visibleServers, key = { it.id }) { server ->
                val state = states[server.id] ?: McpConnectionSnapshot()
                EndpointRecordRow(server, state.status, onClick = { onOpenServer(server.id) })
            }
        }
    }
}

@Composable
fun McpServerDetailScreen(
    repository: McpServerRepository,
    connectionManager: McpConnectionManager,
    oauthManager: McpOAuthManager,
    serverId: String,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
    onMissingServer: () -> Unit,
) {
    val context = LocalContext.current
    val servers by repository.servers.collectAsState()
    val states by connectionManager.states.collectAsState()
    val server = servers.firstOrNull { it.id == serverId && it.catalogServiceId == null }
    var pendingDelete by remember(serverId) { mutableStateOf<McpServerConfig?>(null) }
    LaunchedEffect(serverId, server) { if (server == null) onMissingServer() }
    if (server != null) {
        val target = server
        Box(Modifier.fillMaxSize().testTag("mcp-server-detail")) {
            McpServerDetailPage(
                server = target,
                status = states[target.id] ?: McpConnectionSnapshot(),
                writeApproval = connectionManager.writeApproval,
                repository = repository,
                onConnect = { connectionManager.connect(target.id) },
                onDisconnect = { connectionManager.disconnect(target.id) },
                onAuthorize = {
                    val activity = context.findActivity()
                    if (activity == null) Toast.makeText(context, R.string.mcp_oauth_activity_missing, Toast.LENGTH_LONG).show()
                    else oauthManager.authorize(activity, target.id) { result ->
                        result.onSuccess { Toast.makeText(context, R.string.mcp_oauth_authorized_connect, Toast.LENGTH_LONG).show() }
                            .onFailure { error -> Toast.makeText(context, context.getString(R.string.mcp_oauth_failed, error.message.orEmpty()), Toast.LENGTH_LONG).show() }
                    }
                },
                onEnable = { isEnabled ->
                    repository.upsert(target.copy(enabled = isEnabled))
                    if (isEnabled) connectionManager.connect(target.id) else connectionManager.disconnect(target.id)
                },
                onEdit = { onEdit(target.id) },
                onDelete = { pendingDelete = target },
                onToolEnabled = { toolName, isEnabled -> repository.updateToolEnabled(target.id, toolName, isEnabled) },
            )
        }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.mcp_delete_server_title)) },
            text = { ScrollableDialogContent { Text(stringResource(R.string.mcp_delete_server_body, target.alias)) } },
            confirmButton = {
                TextButton(onClick = {
                    connectionManager.disconnect(target.id)
                    repository.delete(target.id)
                    connectionManager.writeApproval.forgetServer(target.id)
                    oauthManager.clear(target.id)
                    pendingDelete = null
                    onDeleted()
                }) { Text(stringResource(R.string.mcp_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.mcp_cancel)) } },
        )
    }
}

@Composable
fun McpServerEditorScreen(
    initial: McpServerConfig?,
    repository: McpServerRepository,
    onNavigateBack: () -> Unit,
    onSave: (McpServerConfig, String) -> Unit,
    onSaved: () -> Unit,
) {
    McpServerEditor(initial = initial, repository = repository, onNavigateBack = onNavigateBack,
        onSave = onSave, onSaved = onSaved)
}

@Composable
private fun EndpointRecordRow(server: McpServerConfig, status: McpConnectionStatus, onClick: () -> Unit) {
    val row = McpToolPresentation.listRow(server, status)
    Column {
        Row(Modifier.fillMaxWidth().testTag("mcp-server-row-${server.id}")
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).height(42.dp)
                .background(statusColor(status), RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(McpToolPresentation.endpointHost(server.endpoint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(statusLabel(status)), style = MaterialTheme.typography.labelMedium,
                    color = statusColor(status), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.mcp_tool_count_short, row.toolCount),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(LucideIcons.ChevronRight, contentDescription = stringResource(R.string.mcp_open_server_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        McpIndentedDivider()
    }
}

@Composable
internal fun McpServerDetailPage(
    server: McpServerConfig,
    status: McpConnectionSnapshot,
    writeApproval: McpWriteApprovalCoordinator,
    repository: McpServerRepository,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAuthorize: () -> Unit,
    onEnable: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToolEnabled: (String, Boolean) -> Unit,
) {
    var toolSearch by remember(server.id) { mutableStateOf("") }
    val connected = status.status == McpConnectionStatus.READY
    val reauthorize = status.status in setOf(McpConnectionStatus.AUTH_REQUIRED, McpConnectionStatus.REAUTH_REQUIRED,
        McpConnectionStatus.PERMISSION_REQUIRED) && server.authMode == McpAuthMode.OAUTH
    val catalogTools = McpToolPresentation.detailTools(server)
    val selectedTools = catalogTools.filter { tool ->
        val title = McpToolPresentation.toolTitle(tool.wireName, tool.annotations?.title)
        toolSearch.isBlank() || title.contains(toolSearch, true) || tool.description.contains(toolSearch, true)
    }
    val reads = selectedTools.filter { McpToolSecurity.classify(server.catalogServiceId, it.wireName, it.annotations) == McpToolAccess.READ }
    val writes = selectedTools.filter { McpToolSecurity.classify(server.catalogServiceId, it.wireName, it.annotations) == McpToolAccess.WRITE }
    val readCount = catalogTools.count { McpToolSecurity.classify(server.catalogServiceId, it.wireName, it.annotations) == McpToolAccess.READ }
    ConnectorDetailScaffold(
        title = server.alias,
        subtitle = stringResource(statusLabel(status.status)),
        icon = { DeviceConnectorIconTile(LucideIcons.Network, size = 42.dp) },
        connected = connected,
        account = McpToolPresentation.endpointHost(server.endpoint),
        summary = stringResource(R.string.mcp_detail_connect_summary),
        primaryActionLabel = stringResource(when {
            status.status == McpConnectionStatus.CONNECTING -> R.string.remote_service_connecting
            reauthorize -> R.string.mcp_authorize_oauth
            else -> R.string.mcp_connect
        }),
        primaryActionEnabled = server.enabled && status.status != McpConnectionStatus.CONNECTING,
        onPrimaryAction = { if (reauthorize) onAuthorize() else onConnect() },
        disconnectLabel = stringResource(R.string.mcp_disconnect),
        disconnectExplanation = stringResource(R.string.mcp_disconnect_explanation),
        onDisconnect = onDisconnect,
        error = status.message.takeIf { it.isNotBlank() && !connected },
        content = {
            if (server.tools.size > 12) JarvysTextField(
                value = toolSearch,
                onValueChange = { toolSearch = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.remote_service_tools_search)) },
                singleLine = true,
                leadingIcon = { Icon(LucideIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
    if (readCount > 0) McpPermissionCard(stringResource(R.string.mcp_permission_reads)) {
                val allReadsEnabled = catalogTools.filter {
                    McpToolSecurity.classify(server.catalogServiceId, it.wireName, it.annotations) == McpToolAccess.READ
                }.all { it.enabled }
                TextButton(enabled = connected, onClick = { repository.setAllToolsEnabled(server.id, !allReadsEnabled) }) {
                    Text(stringResource(if (allReadsEnabled) R.string.mcp_disable_all_reads else R.string.mcp_enable_all_reads))
                }
                if (reads.isEmpty() && toolSearch.isNotBlank()) Text(stringResource(R.string.remote_service_tools_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                reads.forEachIndexed { index, tool ->
                    if (index > 0) McpIndentedDivider()
                    McpToolPolicyRow(server, tool, writeApproval, onToolEnabled, enabled = connected)
                }
            }
            if (catalogTools.any { McpToolSecurity.classify(server.catalogServiceId, it.wireName, it.annotations) == McpToolAccess.WRITE })
                McpPermissionCard(stringResource(R.string.mcp_permission_writes)) {
                    if (writes.isEmpty() && toolSearch.isNotBlank()) Text(stringResource(R.string.remote_service_tools_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    writes.forEachIndexed { index, tool ->
                        if (index > 0) McpIndentedDivider()
                        McpToolPolicyRow(server, tool, writeApproval, onToolEnabled, enabled = connected)
                    }
                }
            if (catalogTools.isEmpty()) Text(stringResource(R.string.mcp_connect_to_discover_tools),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        about = {
            Text(stringResource(R.string.mcp_server_version, status.serverInfo?.name ?: server.alias,
                status.serverInfo?.version.orEmpty()), fontWeight = FontWeight.SemiBold)
            status.serverInfo?.instructions?.takeIf(String::isNotBlank)?.let { instructions ->
                Text(stringResource(R.string.mcp_remote_untrusted_metadata), color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelSmall)
                Text(McpToolSecurity.sanitizeRemoteText(instructions, 600), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.connector_section_endpoint), fontWeight = FontWeight.SemiBold)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(server.endpoint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (server.trustInsecureServer && server.endpoint.startsWith("http://", true)) {
                Text(stringResource(R.string.mcp_insecure_transport_warning), color = MaterialTheme.colorScheme.error)
            }
        },
        advanced = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mcp_server_enabled), Modifier.weight(1f))
                Switch(checked = server.enabled, onCheckedChange = onEnable)
            }
            if (server.trustInsecureServer) Text(stringResource(R.string.mcp_manual_trust_active),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mcp_configure)) }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mcp_delete), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun McpPermissionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    JarvysGroup(contentPadding = PaddingValues(horizontal = JarvysUiTokens.ScreenPadding, vertical = 12.dp)) {
        JarvysSectionLabel(title)
        content()
    }
}

@Composable
private fun McpIndentedDivider() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 56.dp, end = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
}

@Composable
private fun McpToolPolicyRow(
    server: McpServerConfig,
    tool: McpToolConfig,
    writeApproval: McpWriteApprovalCoordinator,
    onEnabled: (String, Boolean) -> Unit,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val access = McpToolSecurity.classify(server.catalogServiceId, tool.wireName, tool.annotations)
    val title = remember(tool.wireName, tool.annotations?.title) {
        McpToolPresentation.toolTitle(tool.wireName, tool.annotations?.title)
    }
    val description = remember(tool.wireName, tool.description) { McpToolPresentation.description(tool.description) }
    val definition = remember(server.id, tool.wireName, tool.annotations) {
        McpToolDefinition(server.id, server.alias, tool.wireName, tool.modelName, tool.description,
            JSONObject(tool.inputSchemaJson), server.catalogServiceId, tool.annotations, access)
    }
    var policy by remember(server.id, tool.wireName) { mutableStateOf(writeApproval.policy(definition)) }
    var expanded by remember(server.id, tool.wireName) { mutableStateOf(false) }
    val denied = if (access == McpToolAccess.READ) !tool.enabled else !tool.enabled || policy == AutonomyPolicy.DENY
    val selected = if (access == McpToolAccess.READ) {
        if (tool.enabled) R.string.remote_service_policy_allow else R.string.remote_service_policy_deny
    } else when {
        denied -> R.string.remote_service_policy_deny
        policy == AutonomyPolicy.ALLOW -> R.string.remote_service_policy_allow
        else -> R.string.remote_service_policy_ask
    }
    fun setEnabled(enabled: Boolean) {
        runCatching { onEnabled(tool.wireName, enabled) }.onFailure {
            Toast.makeText(context, context.getString(R.string.remote_service_tool_limit,
                McpToolSecurity.MAX_EXPOSED_TOOLS_PER_SERVER), Toast.LENGTH_LONG).show()
        }
    }
    val choices = if (access == McpToolAccess.READ) listOf(
        ConnectorPolicyChoice(R.string.remote_service_policy_allow) { setEnabled(true) },
        ConnectorPolicyChoice(R.string.remote_service_policy_deny) { setEnabled(false) },
    ) else buildList {
        add(ConnectorPolicyChoice(R.string.remote_service_policy_ask) {
            runCatching { writeApproval.setPolicy(definition, AutonomyPolicy.ASK); policy = AutonomyPolicy.ASK; setEnabled(true) }
        })
        if (!McpToolSecurity.isDestructive(tool.wireName, tool.annotations)) add(
            ConnectorPolicyChoice(R.string.remote_service_policy_allow) {
                runCatching { writeApproval.setPolicy(definition, AutonomyPolicy.ALLOW); policy = AutonomyPolicy.ALLOW; setEnabled(true) }
                    .onFailure { Toast.makeText(context, R.string.remote_service_write_allow_error, Toast.LENGTH_LONG).show() }
            },
        )
        add(ConnectorPolicyChoice(R.string.remote_service_policy_deny) {
            runCatching { writeApproval.setPolicy(definition, AutonomyPolicy.DENY); policy = AutonomyPolicy.DENY; setEnabled(false) }
        })
    }
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.62f).padding(vertical = 5.dp)) {
        ConnectorPermissionRow(modifier = Modifier.testTag("mcp-policy-${tool.wireName}-row"), content = {
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description.shortText.isNotBlank()) Text(description.shortText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }, permission = { ConnectorPolicySelector(selected, choices, enabled = enabled,
            modifier = Modifier.testTag("mcp-policy-${tool.wireName}-control")) })
        if (description.isRemoteUntrusted) Text(stringResource(R.string.mcp_remote_untrusted_metadata),
            modifier = Modifier.padding(top = 3.dp), color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelSmall)
        if (expanded && description.fullText.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.mcp_remote_untrusted_metadata), color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelSmall)
                Text(description.fullText, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun McpServerEditor(
    initial: McpServerConfig?,
    repository: McpServerRepository,
    onNavigateBack: () -> Unit,
    onSave: (McpServerConfig, String) -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val id = rememberSaveable(initial?.id) { initial?.id ?: repository.createId() }
    var alias by rememberSaveable(initial?.id) { mutableStateOf(initial?.alias.orEmpty()) }
    var endpoint by rememberSaveable(initial?.id) { mutableStateOf(initial?.endpoint.orEmpty()) }
    var transport by rememberSaveable(initial?.id) { mutableStateOf(initial?.transport ?: McpTransport.AUTO) }
    var authMode by rememberSaveable(initial?.id) { mutableStateOf(initial?.authMode ?: McpAuthMode.NONE) }
    var trusted by rememberSaveable(initial?.id) { mutableStateOf(initial?.trustInsecureServer ?: false) }
    var enabled by rememberSaveable(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    var clientId by rememberSaveable(initial?.id) { mutableStateOf(initial?.oauthClientId.orEmpty()) }
    var initialToolPolicy by rememberSaveable(initial?.id) {
        mutableStateOf(initial?.initialToolPolicy ?: McpInitialToolPolicy.ASK)
    }
    var bearer by rememberSaveable(initial?.id) { mutableStateOf("") }
    var error by rememberSaveable(initial?.id) { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable(initial?.id) { mutableStateOf(false) }
    val dirty = if (initial == null) {
        alias.isNotBlank() || endpoint.isNotBlank() || transport != McpTransport.AUTO || authMode != McpAuthMode.NONE ||
            trusted || !enabled || clientId.isNotBlank() || bearer.isNotBlank() || initialToolPolicy != McpInitialToolPolicy.ASK
    } else {
        alias != initial.alias || endpoint != initial.endpoint || transport != initial.transport || authMode != initial.authMode ||
            trusted != initial.trustInsecureServer || enabled != initial.enabled || clientId != initial.oauthClientId || bearer.isNotBlank()
    }
    fun requestClose() {
        val decision = McpEditorNavigationPolicy.back(dirty, editingExistingServer = initial != null)
        if (decision.confirmDiscard) confirmDiscard = true else onNavigateBack()
    }
    fun save() {
        error = null
        error = null
        val trimmedAlias = alias.trim()
        val trimmedEndpoint = endpoint.trim()
        val validation = McpServerEditorValidation.validate(
            alias = trimmedAlias,
            endpoint = trimmedEndpoint,
            transport = transport,
            authMode = authMode,
            bearerProvided = bearer.isNotBlank(),
            savedBearerAvailable = initial?.authMode == McpAuthMode.BEARER && repository.hasBearerToken(id),
            endpointChanged = initial?.endpoint != null && initial.endpoint != trimmedEndpoint,
        )
        if (validation != null) {
            error = context.getString(when (validation) {
                McpServerEditorValidationError.ALIAS_REQUIRED -> R.string.mcp_alias_required
                McpServerEditorValidationError.URL_REQUIRED -> R.string.mcp_url_required
                McpServerEditorValidationError.URL_INVALID -> R.string.mcp_url_invalid
                McpServerEditorValidationError.CONFIGURATION_INVALID -> R.string.mcp_save_failed_generic
                McpServerEditorValidationError.BEARER_REQUIRED -> R.string.mcp_bearer_required
                McpServerEditorValidationError.TOKEN_REENTER -> R.string.mcp_url_changed_reenter_token
            })
            return
        }
        try {
            onSave(
                McpServerConfig(
                    id = id,
                    alias = trimmedAlias,
                    endpoint = trimmedEndpoint,
                    transport = transport,
                    authMode = authMode,
                    trustInsecureServer = trusted,
                    enabled = enabled,
                    oauthClientId = clientId.trim(),
                    tools = initial?.tools.orEmpty(),
                    toolSelectionMode = initial?.toolSelectionMode ?: McpToolSelectionMode.DEFAULT_ALL,
                    initialToolPolicy = initial?.initialToolPolicy ?: initialToolPolicy,
                ),
                bearer,
            )
            onSaved()
        } catch (failure: RuntimeException) {
            error = failure.message ?: context.getString(R.string.mcp_save_failed_generic)
        }
    }
    BackHandler(enabled = !confirmDiscard) { requestClose() }
    Column(
            Modifier.fillMaxSize().imePadding().padding(horizontal = JarvysUiTokens.ScreenPadding)
                .testTag("mcp-server-editor")
                .verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JarvysTextField(value = alias, onValueChange = { alias = it }, modifier = Modifier.testTag("mcp-editor-alias"),
                label = { Text(stringResource(R.string.mcp_alias)) })
            JarvysTextField(value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.testTag("mcp-editor-endpoint"),
                label = { Text(stringResource(R.string.mcp_url)) })
            JarvysChoiceGroup(
                title = stringResource(R.string.mcp_transport_section),
                choices = McpServerEditorValidation.transports.map { transport -> JarvysChoice(transport,
                    stringResource(when (transport) {
                        McpTransport.AUTO -> R.string.mcp_transport_auto
                        McpTransport.STREAMABLE_HTTP -> R.string.mcp_transport_streamable_http
                        McpTransport.LEGACY_SSE -> R.string.mcp_transport_legacy_sse
                    })) },
                selected = transport,
                onSelect = { transport = it },
            )
            JarvysChoiceGroup(
                title = stringResource(R.string.mcp_authentication_section),
                choices = McpServerEditorValidation.authModes.map { mode -> JarvysChoice(mode,
                    stringResource(when (mode) {
                        McpAuthMode.NONE -> R.string.mcp_auth_none
                        McpAuthMode.BEARER -> R.string.mcp_auth_bearer
                        McpAuthMode.OAUTH -> R.string.mcp_auth_oauth
                    })) },
                selected = authMode,
                onSelect = { authMode = it },
            )
            when (authMode) {
                McpAuthMode.BEARER -> JarvysTextField(
                    value = bearer,
                    onValueChange = { bearer = it },
                    label = { Text(stringResource(R.string.mcp_bearer_token)) },
                    placeholder = { Text(stringResource(if (initial != null) R.string.mcp_saved_token_keep_hint else R.string.mcp_token_placeholder)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                McpAuthMode.OAUTH -> {
                    JarvysTextField(value = clientId, onValueChange = { clientId = it },
                        label = { Text(stringResource(R.string.mcp_oauth_client_id_hint)) })
                    Text(stringResource(R.string.mcp_pkce_callback, McpOAuthManager.REDIRECT_URI),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                McpAuthMode.NONE -> Unit
            }
            JarvysSwitchRow(
                title = stringResource(R.string.mcp_trust_insecure_title),
                description = stringResource(R.string.mcp_trust_insecure_explanation),
                checked = trusted,
                onCheckedChange = { trusted = it },
                warning = true,
            )
            if (trusted) Text(stringResource(R.string.mcp_manual_trust_active), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            JarvysSwitchRow(
                title = stringResource(R.string.mcp_server_enabled), description = "",
                checked = enabled, onCheckedChange = { enabled = it },
                switchModifier = Modifier.testTag("mcp-editor-enabled-switch"),
            )
            if (initial == null) {
                JarvysGroup(modifier = Modifier.testTag("mcp-editor-tool-policy"),
                    contentPadding = PaddingValues(JarvysUiTokens.ScreenPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConnectorPermissionRow(content = {
                            JarvysSectionLabel(stringResource(R.string.mcp_initial_tool_permissions))
                        }, permission = {
                            McpInitialToolPolicySelector(initialToolPolicy, { initialToolPolicy = it })
                        })
                        Text(stringResource(R.string.mcp_initial_tool_permissions_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { Text(it, Modifier.testTag("mcp-editor-error"), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
            JarvysPrimaryButton(stringResource(R.string.mcp_save), ::save, Modifier.testTag("mcp-editor-save"))
        }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.mcp_discard_changes_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.mcp_discard_changes_body)) } },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onNavigateBack() }) { Text(stringResource(R.string.mcp_discard_changes_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.mcp_cancel)) } },
    )
}

@Composable
private fun McpInitialToolPolicySelector(
    selected: McpInitialToolPolicy,
    onSelect: (McpInitialToolPolicy) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf(
        McpInitialToolPolicy.ASK to R.string.remote_service_policy_ask,
        McpInitialToolPolicy.ALLOW to R.string.remote_service_policy_allow,
        McpInitialToolPolicy.DENY to R.string.remote_service_policy_deny,
    )
    val selectedLabel = choices.first { it.first == selected }.second
    Box(Modifier.fillMaxWidth()) {
        ConnectorPolicyButton(selectedLabel, onClick = { expanded = true },
            modifier = Modifier.testTag("mcp-editor-tool-policy-selector"))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (policy, label) ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("mcp-editor-tool-policy-option-${policy.name.lowercase(java.util.Locale.ROOT)}"),
                    text = { Text(stringResource(label)) },
                    onClick = { expanded = false; onSelect(policy) },
                )
            }
        }
    }
}

@Composable
private fun McpCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    JarvysGroup(contentPadding = PaddingValues(0.dp), content = content)

@Composable
private fun transportLabel(transport: McpTransport): String = stringResource(when (transport) {
    McpTransport.AUTO -> R.string.mcp_transport_auto_detail
    McpTransport.STREAMABLE_HTTP -> R.string.mcp_transport_http_detail
    McpTransport.LEGACY_SSE -> R.string.mcp_transport_sse_detail
})

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun statusLabel(status: McpConnectionStatus): Int = when (status) {
    McpConnectionStatus.DISCONNECTED -> R.string.mcp_status_disconnected
    McpConnectionStatus.CONNECTING -> R.string.mcp_status_connecting
    McpConnectionStatus.READY -> R.string.mcp_status_connected
    McpConnectionStatus.ERROR -> R.string.mcp_status_error
    McpConnectionStatus.AUTH_REQUIRED -> R.string.mcp_status_auth_required
    McpConnectionStatus.REAUTH_REQUIRED -> R.string.mcp_status_reauth_required
    McpConnectionStatus.PERMISSION_REQUIRED -> R.string.mcp_status_permission_required
}

@Composable
private fun statusColor(status: McpConnectionStatus): Color = when (status) {
    McpConnectionStatus.READY -> Color(0xFF2E7D32)
    McpConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.primary
    McpConnectionStatus.ERROR, McpConnectionStatus.AUTH_REQUIRED, McpConnectionStatus.REAUTH_REQUIRED,
    McpConnectionStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.error
    McpConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}
