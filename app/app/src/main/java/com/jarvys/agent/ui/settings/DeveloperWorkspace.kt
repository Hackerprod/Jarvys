package com.jarvys.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.R
import com.jarvys.agent.connectors.AutonomyPolicyRow
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.FlavorAutonomyUi
import com.jarvys.agent.mcp.McpConnectionManager
import com.jarvys.agent.mcp.McpServerRepository
import com.jarvys.agent.mcp.RemoteMcpPolicySettings

@Composable
internal fun DeveloperWorkspace(registryOverride: ConnectorRegistry? = null) {
    val context = LocalContext.current
    val registry = registryOverride ?: remember(context.applicationContext) { ConnectorRegistry.get(context) }
    val definitions by registry.definitions.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("developer-settings")) {
        ConnectorAutonomyPolicies(definitions, registry)
        val repository = remember(context.applicationContext) { McpServerRepository.get(context) }
        val connections = remember(context.applicationContext) { McpConnectionManager.get(context.applicationContext) }
        RemoteMcpPolicySettings(repository, connections)
        FlavorAutonomyUi.DeveloperControls()
    }
}

@Composable
private fun ConnectorAutonomyPolicies(definitions: List<ConnectorDefinition>, registry: ConnectorRegistry) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        JarvysSectionLabel(stringResource(R.string.settings_developer_action_policies), Modifier.padding(start = 3.dp))
        definitions.filter { it.operations.isNotEmpty() }.forEach { definition ->
            val displayName = if (definition.displayNameResourceId != 0)
                stringResource(definition.displayNameResourceId) else definition.name
            JarvysGroup(modifier = Modifier.fillMaxWidth().testTag("developer-connector-${definition.id}")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(displayName, fontWeight = FontWeight.SemiBold)
                    definition.operations.forEach { operation -> AutonomyPolicyRow(registry, definition, operation) }
                }
            }
        }
    }
}
