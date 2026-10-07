package com.jarvys.agent.connectors

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.JarvysListRow
import com.jarvys.agent.mcp.McpAuthMode
import com.jarvys.agent.mcp.McpConnectionManager
import com.jarvys.agent.mcp.McpConnectionStatus
import com.jarvys.agent.mcp.McpOAuthManager
import com.jarvys.agent.mcp.McpServerConfig
import com.jarvys.agent.mcp.McpServerRepository
import com.jarvys.agent.mcp.McpToolAccess
import com.jarvys.agent.mcp.McpToolDefinition
import com.jarvys.agent.mcp.McpToolSecurity
import com.jarvys.agent.mcp.RemoteMcpDisconnectFlow
import com.jarvys.agent.connectors.AutonomyPolicy
import org.json.JSONObject
import java.util.concurrent.Executors

/** Remote catalog UI is kept separate from device connectors and custom MCP configuration. */
@Composable
internal fun RemoteServicesSection(
    repository: McpServerRepository,
    connections: McpConnectionManager,
    oauthManager: McpOAuthManager,
    selectedId: String? = null,
    onSelect: (String, String) -> Unit = { _, _ -> },
    onDetailExit: () -> Unit = {},
) {
    val servers by repository.servers.collectAsState()
    val states by connections.states.collectAsState()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selectedId == null) {
            Text(stringResource(R.string.remote_services_heading),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            ConnectorRowsGroup {
                RemoteServiceCatalog.services.forEachIndexed { index, service ->
                    val server = servers.firstOrNull { it.catalogServiceId == service.id }
                    RemoteServiceCard(service, server, states[server?.id], repository, connections, oauthManager,
                        selected = false, onSelect = { onSelect(service.id, context.getString(service.nameResourceId)) })
                    if (index != RemoteServiceCatalog.services.lastIndex) ConnectorRowsDivider()
                }
            }
        } else {
            val service = RemoteServiceCatalog.find(selectedId) ?: return@Column
            val server = servers.firstOrNull { it.catalogServiceId == service.id }
            RemoteServiceCard(service, server, states[server?.id], repository, connections, oauthManager,
                selected = true, onSelect = {}, onDetailExit = onDetailExit)
        }
    }
}

@Composable
private fun RemoteServiceCard(
    service: RemoteServiceDefinition,
    server: McpServerConfig?,
    state: com.jarvys.agent.mcp.McpConnectionSnapshot?,
    repository: McpServerRepository,
    connections: McpConnectionManager,
    oauthManager: McpOAuthManager,
    selected: Boolean,
    onSelect: () -> Unit,
    onDetailExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val status = state?.status ?: McpConnectionStatus.DISCONNECTED
    var patDialog by remember(service.id) { mutableStateOf(false) }
    var toolSearch by remember(service.id) { mutableStateOf("") }
    var pat by remember(service.id) { mutableStateOf("") }
    var patError by remember(service.id) { mutableStateOf<String?>(null) }
    var oauthInProgress by remember(service.id) { mutableStateOf(false) }
    var advancedExpanded by remember(service.id) { mutableStateOf(false) }
    var githubRepoScope by remember(service.id) { mutableStateOf(false) }
    var githubDeviceCode by remember(service.id) { mutableStateOf<GitHubDeviceCode?>(null) }
    var githubDeviceDialog by remember(service.id) { mutableStateOf(false) }
    var githubDeviceAttempt by remember(service.id) { mutableStateOf<GitHubDeviceFlowAttempt?>(null) }
    var githubErrorResource by remember(service.id) { mutableStateOf<Int?>(null) }
    val isGitHub = service.id == "github"
    DisposableEffect(githubDeviceAttempt) {
        onDispose { githubDeviceAttempt?.close() }
    }

    fun beginGitHubDeviceFlow() {
        val config = (server ?: McpServerConfig(
            id = service.mcpServerId,
            alias = context.getString(service.nameResourceId),
            endpoint = service.endpoint,
            transport = service.transport,
            authMode = McpAuthMode.OAUTH,
            enabled = true,
            oauthClientId = GitHubDeviceFlowProtocol.CLIENT_ID,
            catalogServiceId = service.id,
        )).copy(authMode = McpAuthMode.OAUTH, enabled = true,
            oauthClientId = GitHubDeviceFlowProtocol.CLIENT_ID, catalogServiceId = service.id)
        runCatching {
            if (server == null || server.authMode != McpAuthMode.OAUTH) connections.disconnect(config.id)
            repository.upsert(config)
            oauthInProgress = true
            githubErrorResource = null
            githubDeviceAttempt?.close()
            githubDeviceAttempt = GitHubDeviceFlowAttempt.start(
                includePrivateRepositories = githubRepoScope,
                onDeviceCode = { code -> githubDeviceCode = code; githubDeviceDialog = true },
                onComplete = { result ->
                    oauthInProgress = false
                    githubDeviceAttempt = null
                    result.fold(onSuccess = { tokens ->
                        runCatching {
                            oauthManager.saveGitHubDeviceGrant(config, tokens.accessToken, tokens.refreshToken,
                                tokens.expiresAtMillis)
                            repository.clearBearerToken(config.id)
                            connections.connect(config.id)
                            githubDeviceDialog = false
                            githubDeviceCode = null
                        }.onFailure { githubErrorResource = R.string.github_device_error_generic }
                    }, onFailure = { error ->
                        if (GitHubDeviceFlowProtocol.mapError(error) != GitHubDeviceFlowError.CANCELLED) {
                            githubErrorResource = when (GitHubDeviceFlowProtocol.mapError(error)) {
                                GitHubDeviceFlowError.EXPIRED -> R.string.github_device_error_expired
                                GitHubDeviceFlowError.DENIED -> R.string.github_device_error_denied
                                GitHubDeviceFlowError.DISABLED -> R.string.github_device_error_disabled
                                else -> R.string.github_device_error_generic
                            }
                        }
                        githubDeviceDialog = false
                        githubDeviceCode = null
                    })
                },
            )
        }.onFailure {
            oauthInProgress = false
            githubErrorResource = R.string.github_device_error_generic
        }
    }

    if (!selected) {
        RemoteServiceListRow(service, status, onSelect)
        return
    }

    val reauthStatus = status in setOf(McpConnectionStatus.AUTH_REQUIRED, McpConnectionStatus.REAUTH_REQUIRED, McpConnectionStatus.PERMISSION_REQUIRED)
    val primaryLabel = when {
        status == McpConnectionStatus.CONNECTING || oauthInProgress -> R.string.remote_service_connecting
        isGitHub -> R.string.github_device_connect
        service.authMode == RemoteServiceAuthMode.PAT && !isGitHub && (server == null || !repository.hasBearerToken(server.id) || reauthStatus) ->
            if (server == null) R.string.remote_service_connect else R.string.remote_service_update_token
        service.authMode == RemoteServiceAuthMode.OAUTH_DCR && (server == null || reauthStatus) ->
            if (server == null) R.string.remote_service_connect else R.string.remote_service_oauth_reauthorize
        else -> if (status == McpConnectionStatus.REAUTH_REQUIRED) R.string.remote_service_update_token else R.string.remote_service_connect
    }
    fun connectPrimary() {
        when {
            isGitHub -> beginGitHubDeviceFlow()
            service.authMode == RemoteServiceAuthMode.PAT && !isGitHub -> patDialog = true
            service.authMode == RemoteServiceAuthMode.OAUTH_DCR && (server == null || reauthStatus) -> {
                if (oauthInProgress) return
                val activity = context.findActivity()
                if (activity == null) patError = context.getString(R.string.remote_service_oauth_activity_missing)
                else {
                    val config = server ?: McpServerConfig(
                        id = service.mcpServerId, alias = context.getString(service.nameResourceId), endpoint = service.endpoint,
                        transport = service.transport, authMode = McpAuthMode.OAUTH, enabled = true, catalogServiceId = service.id,
                    ).also(repository::upsert)
                    oauthInProgress = true
                    oauthManager.authorize(activity, config.id) { result ->
                        oauthInProgress = false
                        result.onSuccess { connections.connect(config.id) }
                            .onFailure { error -> patError = context.getString(R.string.remote_service_oauth_error, error.message.orEmpty().take(200)) }
                    }
                }
            }
            else -> server?.let { connections.connect(it.id) }
        }
    }
    val detailError = listOfNotNull(state?.message?.takeIf { it.isNotBlank() && status != McpConnectionStatus.READY },
        patError, githubErrorResource?.let(context::getString)).joinToString(" · ").ifBlank { null }
    ConnectorDetailScaffold(
        title = stringResource(service.nameResourceId),
        subtitle = stringResource(statusLabel(status)),
        icon = { ServiceBrandIconTile(service.brandIconId, stringResource(service.nameResourceId), size = 42.dp) },
        connected = status == McpConnectionStatus.READY,
        account = server?.alias?.takeIf { status == McpConnectionStatus.READY },
        summary = stringResource(R.string.connector_detail_connect_summary),
        primaryActionLabel = stringResource(primaryLabel),
        primaryActionEnabled = !oauthInProgress && status != McpConnectionStatus.CONNECTING,
        onPrimaryAction = ::connectPrimary,
        disconnectLabel = stringResource(R.string.remote_service_disconnect),
        disconnectExplanation = stringResource(R.string.remote_service_disconnect_body),
        error = detailError,
        onDisconnect = {
            server?.let { config -> RemoteServiceDisconnectExecutor.execute {
                RemoteMcpDisconnectFlow.disconnect(
                    revokeBestEffort = { if (config.authMode == McpAuthMode.OAUTH) oauthManager.revokeAndClear(config) },
                    stopConnection = { connections.disconnect(config.id) },
                    clearConfigurationAndSecrets = { repository.delete(config.id); connections.writeApproval.forgetServer(config.id) },
                )
                android.os.Handler(android.os.Looper.getMainLooper()).post(onDetailExit)
            } }
        },
        showIdentityHeader = false,
        content = {
            if (server != null) {
                val tools = server.tools.filter {
                    toolSearch.isBlank() || it.wireName.contains(toolSearch, true) || it.description.contains(toolSearch, true)
                }
                val reads = tools.filter { McpToolSecurity.classify(service.id, it.wireName, it.annotations) == McpToolAccess.READ }
                val writes = tools.filter { McpToolSecurity.classify(service.id, it.wireName, it.annotations) == McpToolAccess.WRITE }
                val readTools = server.tools.filter { McpToolSecurity.classify(service.id, it.wireName, it.annotations) == McpToolAccess.READ }
                ConnectorDetailSection(stringResource(R.string.remote_service_read_tool)) {
                    if (readTools.isNotEmpty()) {
                        TextButton(onClick = { repository.setAllToolsEnabled(server.id, !readTools.all { it.enabled }) }) {
                            Text(stringResource(if (readTools.all { it.enabled }) R.string.mcp_disable_all_reads else R.string.mcp_enable_all_reads))
                        }
                        if (server.tools.size > 8) JarvysTextField(
                            value = toolSearch, onValueChange = { toolSearch = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.remote_service_tools_search)) }, singleLine = true,
                            leadingIcon = { Icon(LucideIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                        reads.forEach { RemoteMcpToolRow(service, server, it, repository, connections) }
                    }
                }
                if (writes.isNotEmpty()) ConnectorDetailSection(stringResource(R.string.remote_service_write_tool)) {
                    writes.forEach { RemoteMcpToolRow(service, server, it, repository, connections) }
                }
                if (tools.isEmpty()) Text(stringResource(R.string.remote_service_tools_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        about = {
            Text(stringResource(if (isGitHub) R.string.github_device_access_note else service.scopesNoteResourceId))
            Text(stringResource(when (service.risk) {
                RemoteServiceRisk.LOW -> R.string.remote_service_risk_low_explanation
                RemoteServiceRisk.MEDIUM -> R.string.remote_service_risk_medium_explanation
                RemoteServiceRisk.HIGH -> R.string.remote_service_risk_high_explanation
            }))
            Text(stringResource(R.string.remote_service_ai_disclosure))
            Text(stringResource(R.string.remote_service_credential_privacy))
            Text(stringResource(R.string.connector_section_endpoint), fontWeight = FontWeight.SemiBold)
            Text(server?.endpoint ?: service.endpoint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        advanced = {
            if (isGitHub) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.github_device_private_repos), modifier = Modifier.weight(1f), fontSize = 13.sp)
                Switch(checked = githubRepoScope, onCheckedChange = { githubRepoScope = it }, enabled = !oauthInProgress)
            }
            if (service.authMode == RemoteServiceAuthMode.PAT) {
                Text(stringResource(service.patInstructionsResourceId), fontSize = 13.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isGitHub) TextButton(onClick = { patDialog = true }) { Text(stringResource(R.string.github_device_use_pat)) }
            }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.documentationUrl))) } }) {
                Text(stringResource(R.string.remote_service_open_documentation))
            }
        },
    )

    if (patDialog) AlertDialog(
        onDismissRequest = { patDialog = false; patError = null },
        title = { Text(stringResource(R.string.remote_service_pat_title)) },
        text = { ScrollableDialogContent {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(service.patInstructionsResourceId), fontSize = 13.sp, lineHeight = 19.sp)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.patCreationUrl))) }
                }) { Text(stringResource(R.string.remote_service_pat_open_github)) }
                JarvysTextField(
                    value = pat,
                    onValueChange = { pat = it; patError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.remote_service_pat_field)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                patError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        } },
        confirmButton = { TextButton(onClick = {
            val token = pat.trim()
            if (token.isBlank()) {
                patError = context.getString(R.string.remote_service_pat_required)
            } else {
                val config = server ?: McpServerConfig(
                    id = service.mcpServerId,
                    alias = context.getString(service.nameResourceId),
                    endpoint = service.endpoint,
                    transport = service.transport,
                    authMode = McpAuthMode.BEARER,
                    enabled = true,
                    catalogServiceId = service.id,
                )
                runCatching {
                    if (config.authMode == McpAuthMode.OAUTH) oauthManager.clear(config.id)
                    repository.saveBearerToken(config.id, config.endpoint, token)
                    repository.upsert(config.copy(enabled = true, authMode = McpAuthMode.BEARER,
                        transport = service.transport, catalogServiceId = service.id))
                    connections.connect(config.id)
                    pat = ""
                    patDialog = false
                    Toast.makeText(context, R.string.remote_service_token_saved, Toast.LENGTH_SHORT).show()
                }.onFailure { error -> patError = error.message ?: context.getString(R.string.remote_service_connect_error, service.id) }
            }
        }) { Text(stringResource(R.string.remote_service_connect)) } },
        dismissButton = { TextButton(onClick = { patDialog = false; pat = "" }) { Text(stringResource(R.string.connector_cancel)) } },
    )

    val deviceCode = githubDeviceCode
    if (githubDeviceDialog && deviceCode != null) AlertDialog(
        onDismissRequest = {
            githubDeviceAttempt?.close()
            githubDeviceAttempt = null
            githubDeviceDialog = false
            githubDeviceCode = null
        },
        title = { Text(stringResource(R.string.github_device_dialog_title)) },
        text = { ScrollableDialogContent {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.github_device_dialog_instructions))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(deviceCode.userCode, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(deviceCode.verificationUri, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.github_verification_clip_label), deviceCode.userCode))
                    Toast.makeText(context, R.string.github_device_code_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.github_device_copy_code)) }
                Text(stringResource(R.string.github_device_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deviceCode.verificationUri))) }
                .onFailure { githubErrorResource = R.string.github_device_error_generic }
        }) { Text(stringResource(R.string.github_device_open_browser)) } },
        dismissButton = { TextButton(onClick = {
            githubDeviceAttempt?.close()
            githubDeviceAttempt = null
            githubDeviceDialog = false
            githubDeviceCode = null
        }) { Text(stringResource(R.string.connector_cancel)) } },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun RemoteServiceListRow(service: RemoteServiceDefinition, status: McpConnectionStatus, onClick: () -> Unit) {
    val presentationStatus = when (status) {
        McpConnectionStatus.READY -> ConnectorShortStatus.CONNECTED
        McpConnectionStatus.CONNECTING -> ConnectorShortStatus.CONNECTING
        McpConnectionStatus.AUTH_REQUIRED, McpConnectionStatus.REAUTH_REQUIRED -> ConnectorShortStatus.REAUTHORIZE
        McpConnectionStatus.ERROR -> ConnectorShortStatus.ERROR
        McpConnectionStatus.PERMISSION_REQUIRED -> ConnectorShortStatus.PERMISSION_PENDING
        McpConnectionStatus.DISCONNECTED -> null
    }
    val statusText = presentationStatus?.let { value -> stringResource(when (value) {
                ConnectorShortStatus.CONNECTED -> R.string.connector_status_connected
                ConnectorShortStatus.CONNECTING -> R.string.remote_service_connecting_short
                ConnectorShortStatus.REAUTHORIZE -> R.string.connector_status_reauthorize
                ConnectorShortStatus.ERROR -> R.string.connector_status_error
                ConnectorShortStatus.PERMISSION_PENDING -> R.string.connector_status_permission_pending
            }) }
    JarvysListRow(
        title = stringResource(service.nameResourceId),
        subtitle = statusText,
        subtitleColor = if (presentationStatus in setOf(ConnectorShortStatus.ERROR, ConnectorShortStatus.PERMISSION_PENDING))
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        leadingContent = { ServiceBrandIconTile(service.brandIconId, stringResource(service.nameResourceId)) },
        onClick = onClick,
        trailing = {
            Icon(LucideIcons.ChevronRight, contentDescription = stringResource(R.string.connector_details_accessibility),
                modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

private fun statusLabel(status: McpConnectionStatus): Int = when (status) {
    McpConnectionStatus.DISCONNECTED -> R.string.remote_service_disconnected
    McpConnectionStatus.CONNECTING -> R.string.remote_service_connecting
    McpConnectionStatus.READY -> R.string.remote_service_connected
    McpConnectionStatus.ERROR -> R.string.remote_service_error
    McpConnectionStatus.AUTH_REQUIRED, McpConnectionStatus.REAUTH_REQUIRED -> R.string.remote_service_reauth_required
    McpConnectionStatus.PERMISSION_REQUIRED -> R.string.remote_service_permission_required
}

@Composable
private fun RemoteStatusBadge(status: McpConnectionStatus) {
    val (label, color) = when (status) {
        McpConnectionStatus.READY -> R.string.remote_service_connected to MaterialTheme.colorScheme.primary
        McpConnectionStatus.CONNECTING -> R.string.remote_service_connecting to MaterialTheme.colorScheme.secondary
        McpConnectionStatus.REAUTH_REQUIRED, McpConnectionStatus.AUTH_REQUIRED -> R.string.remote_service_reauth_required to MaterialTheme.colorScheme.error
        McpConnectionStatus.PERMISSION_REQUIRED -> R.string.remote_service_permission_required to MaterialTheme.colorScheme.error
        McpConnectionStatus.ERROR -> R.string.remote_service_error to MaterialTheme.colorScheme.error
            McpConnectionStatus.DISCONNECTED -> R.string.remote_service_disconnected to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
        Text(stringResource(label), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun RemoteMcpToolRow(
    service: RemoteServiceDefinition,
    server: McpServerConfig,
    tool: com.jarvys.agent.mcp.McpToolConfig,
    repository: McpServerRepository,
    connections: McpConnectionManager,
) {
    val context = LocalContext.current
    val access = McpToolSecurity.classify(service.id, tool.wireName, tool.annotations)
    val definition = remember(server.id, tool.wireName, tool.annotations) {
        McpToolDefinition(server.id, server.alias, tool.wireName, tool.modelName, tool.description,
            JSONObject(tool.inputSchemaJson), service.id, tool.annotations, access)
    }
    var policy by remember(server.id, tool.wireName) { mutableStateOf(connections.writeApproval.policy(definition)) }
    val label = remember(tool.wireName, tool.annotations?.title) {
        tool.annotations?.title?.let { McpToolSecurity.sanitizeRemoteText(it, 80) }?.takeIf(String::isNotBlank)
            ?: tool.wireName.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("[_-]+"), " ").split(Regex("\\s+")).joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { it.titlecase() }
                }.take(80)
    }
    val displayedPolicy = when {
        !tool.enabled -> AutonomyPolicy.DENY
        access == McpToolAccess.READ -> AutonomyPolicy.ALLOW
        else -> policy
    }
    val selectedResource = when (displayedPolicy) {
        AutonomyPolicy.ASK -> R.string.remote_service_policy_ask
        AutonomyPolicy.ALLOW -> R.string.remote_service_policy_allow
        AutonomyPolicy.DENY -> R.string.remote_service_policy_deny
    }
    val choices = if (access == McpToolAccess.READ) listOf(
        ConnectorPolicyChoice(R.string.remote_service_policy_allow) {
            runCatching { repository.updateToolEnabled(server.id, tool.wireName, true) }.onFailure { showToolLimit(context) }
        },
        ConnectorPolicyChoice(R.string.remote_service_policy_deny) {
            runCatching { repository.updateToolEnabled(server.id, tool.wireName, false) }.onFailure { showToolLimit(context) }
        },
    ) else buildList {
        add(ConnectorPolicyChoice(R.string.remote_service_policy_ask) {
            runCatching {
                connections.writeApproval.setPolicy(definition, AutonomyPolicy.ASK); policy = AutonomyPolicy.ASK
                if (!tool.enabled) repository.updateToolEnabled(server.id, tool.wireName, true)
            }
        })
        if (!McpToolSecurity.isDestructive(tool.wireName, tool.annotations)) add(ConnectorPolicyChoice(R.string.remote_service_policy_allow) {
            runCatching {
                connections.writeApproval.setPolicy(definition, AutonomyPolicy.ALLOW); policy = AutonomyPolicy.ALLOW
                if (!tool.enabled) repository.updateToolEnabled(server.id, tool.wireName, true)
            }.onFailure { Toast.makeText(context, R.string.remote_service_write_allow_error, Toast.LENGTH_LONG).show() }
        })
        add(ConnectorPolicyChoice(R.string.remote_service_policy_deny) {
            runCatching {
                connections.writeApproval.setPolicy(definition, AutonomyPolicy.DENY); policy = AutonomyPolicy.DENY
                if (tool.enabled) repository.updateToolEnabled(server.id, tool.wireName, false)
            }
        })
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp, fontWeight = FontWeight.Medium)
        ConnectorPolicySelector(selectedResource, choices)
    }
}

private fun showToolLimit(context: Context) {
    Toast.makeText(context, context.getString(R.string.remote_service_tool_limit,
        RemoteServiceCatalog.MAX_DEFAULT_READ_TOOLS_PER_SERVER), Toast.LENGTH_LONG).show()
}

private object RemoteServiceDisconnectExecutor {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysRemoteServiceDisconnect").apply { isDaemon = true }
    }
    fun execute(action: () -> Unit) { executor.execute(action) }
}
