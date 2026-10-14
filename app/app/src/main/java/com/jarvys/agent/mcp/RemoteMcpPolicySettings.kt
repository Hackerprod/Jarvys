package com.jarvys.agent.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.R
import com.jarvys.agent.connectors.ActionPolicyOrigin
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorPermissionRow
import com.jarvys.agent.connectors.ConnectorPolicyChoice
import com.jarvys.agent.connectors.ConnectorPolicyOriginCaption
import com.jarvys.agent.connectors.ConnectorPolicySelector
import org.json.JSONObject

@Composable
internal fun RemoteMcpPolicySettings(repository: McpServerRepository, connections: McpConnectionManager) {
    val servers by repository.servers.collectAsState()
    val configuredServers = servers.filter { it.tools.isNotEmpty() }
    if (configuredServers.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        JarvysSectionLabel(stringResource(R.string.settings_developer_mcp_policies), Modifier.padding(start = 3.dp))
        configuredServers.forEach { server ->
            JarvysGroup(modifier = Modifier.fillMaxWidth().testTag("developer-mcp-${server.id}")) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(McpToolSecurity.sanitizeRemoteText(server.alias, 100), fontWeight = FontWeight.SemiBold)
                    server.tools.forEach { tool -> RemoteMcpPolicyRow(server, tool, connections) }
                }
            }
        }
    }
}

@Composable
private fun RemoteMcpPolicyRow(server: McpServerConfig, tool: McpToolConfig, connections: McpConnectionManager) {
    val context = LocalContext.current
    val access = remember(server.catalogServiceId, tool.wireName, tool.annotations) {
        McpToolSecurity.classify(server.catalogServiceId, tool.wireName, tool.annotations)
    }
    val definition = remember(server, tool) {
        McpToolDefinition(server.id, server.alias, tool.wireName, tool.modelName, tool.description,
            JSONObject(tool.inputSchemaJson), server.catalogServiceId, tool.annotations, access, server.inheritedActionPolicy())
    }
    var snapshot by remember(definition) { mutableStateOf(connections.writeApproval.snapshot(definition)) }
    var showPolicyInfo by remember(server.id, tool.wireName) { mutableStateOf(true) }
    val label = remember(tool.wireName, tool.annotations?.title) {
        tool.annotations?.title?.let { McpToolSecurity.sanitizeRemoteText(it, 128) }?.takeIf(String::isNotBlank)
            ?: tool.wireName.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("[_-]+"), " ").split(Regex("\\s+")).joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { it.titlecase() }
                }.take(80)
    }
    val selectedResource = when (snapshot.policy) {
        AutonomyPolicy.ASK -> R.string.remote_service_policy_ask
        AutonomyPolicy.ALLOW -> R.string.remote_service_policy_allow
        AutonomyPolicy.DENY -> R.string.remote_service_policy_deny
    }
    val choices = listOf(
        AutonomyPolicy.ASK to R.string.remote_service_policy_ask,
        AutonomyPolicy.ALLOW to R.string.remote_service_policy_allow,
        AutonomyPolicy.DENY to R.string.remote_service_policy_deny,
    ).map { (policy, resource) -> ConnectorPolicyChoice(resource) {
        runCatching {
            connections.writeApproval.setPolicy(definition, policy)
            snapshot = connections.writeApproval.snapshot(definition)
        }.onFailure { Toast.makeText(context, R.string.ux44_mcp_policy_save_error, Toast.LENGTH_LONG).show() }
    } }

    Column {
        ConnectorPermissionRow(modifier = Modifier.padding(vertical = 3.dp).testTag("remote-policy-${tool.wireName}-row"), content = {
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }, permission = {
            ConnectorPolicySelector(selectedResource, choices, subjectKey = "${server.id}/${tool.wireName}",
                modifier = Modifier.testTag("remote-policy-${tool.wireName}-control"))
        })
        ConnectorPolicyOriginCaption(snapshot.origin,
            Modifier.fillMaxWidth().testTag("remote-policy-${tool.wireName}-origin"))
        val origin = snapshot.origin
        if (showPolicyInfo && origin in setOf(ActionPolicyOrigin.LEGACY_UNKNOWN, ActionPolicyOrigin.INVALID)) {
            Text(stringResource(if (origin == ActionPolicyOrigin.INVALID) R.string.ux44_mcp_invalid_policy else R.string.ux44_mcp_retained_policy),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showPolicyInfo = false }) { Text(stringResource(R.string.ux44_mcp_dismiss)) }
        }
    }
}
