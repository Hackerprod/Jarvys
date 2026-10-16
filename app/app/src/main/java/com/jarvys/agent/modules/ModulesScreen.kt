package com.jarvys.agent.modules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysEmptyState
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.connectors.AutonomyPolicyRow
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorState
import java.util.Locale

/** Settings surface for bundled modules. The connector registry remains their execution and policy store. */
@Composable
fun ModulesScreen(moduleRegistry: ModuleRegistry, connectorRegistry: ConnectorRegistry) {
    val context = LocalContext.current
    val manifests = remember(moduleRegistry) {
        moduleRegistry.modules().sortedBy { it.name.lowercase(Locale.ROOT) }
    }
    val definitions by connectorRegistry.definitions.collectAsState()
    val states by connectorRegistry.states.collectAsState()

    LaunchedEffect(moduleRegistry, connectorRegistry) {
        moduleRegistry.installAndConnectUnselected(connectorRegistry, WebViewModuleRuntime(context))
        connectorRegistry.refreshStates()
    }

    Column(Modifier.fillMaxSize().testTag("modules-screen")) {
        if (manifests.isEmpty()) {
            JarvysEmptyState(
                icon = LucideIcons.Layers2,
                title = stringResource(R.string.modules_empty_title),
                body = stringResource(R.string.modules_empty_body),
                modifier = Modifier.fillMaxSize().padding(horizontal = JarvysUiTokens.ScreenPadding),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                manifests.forEach { manifest ->
                    val definition = definitions.firstOrNull { it.id == manifest.id }
                    ModuleSettingsCard(
                        manifest = manifest,
                        definition = definition,
                        state = definition?.let { states[it.id] ?: connectorRegistry.state(it) },
                        connectorRegistry = connectorRegistry,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleSettingsCard(
    manifest: ModuleManifest,
    definition: ConnectorDefinition?,
    state: ConnectorState?,
    connectorRegistry: ConnectorRegistry,
) {
    val context = LocalContext.current
    val noneLabel = stringResource(R.string.modules_none)
    var errorText by remember(manifest.id) { mutableStateOf<String?>(null) }
    val enabled = state == ConnectorState.CONNECTED

    JarvysGroup(
        modifier = Modifier.fillMaxWidth().testTag("module-${manifest.id}"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.modules_version, manifest.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        when (state) {
                            ConnectorState.CONNECTED -> R.string.modules_status_enabled
                            ConnectorState.DISCONNECTED -> R.string.modules_status_disabled
                            ConnectorState.PERMISSION_REVOKED -> R.string.modules_status_unavailable
                            null -> R.string.modules_status_loading
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state == ConnectorState.PERMISSION_REVOKED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("module-${manifest.id}-status"),
                )
            }
            Switch(
                checked = enabled,
                enabled = definition != null,
                onCheckedChange = { turnOn ->
                    definition?.let { target ->
                        errorText = runCatching {
                            if (turnOn) connectorRegistry.connect(target.id) else connectorRegistry.disconnect(target.id)
                            connectorRegistry.refreshStates()
                            null
                        }.getOrElse { failure ->
                            failure.message?.takeIf(String::isNotBlank) ?: context.getString(R.string.modules_toggle_failed)
                        }
                    }
                },
                modifier = Modifier.testTag("module-${manifest.id}-enabled").semantics {
                    contentDescription = context.getString(
                        if (enabled) R.string.modules_toggle_disable_accessibility else R.string.modules_toggle_enable_accessibility,
                        manifest.name,
                    )
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        ModuleMetadataLine(
            title = stringResource(R.string.modules_declared_tools),
            value = manifest.tools.joinToString(", ") { it.name }.ifEmpty { noneLabel },
        )
        ModuleMetadataLine(
            title = stringResource(R.string.modules_artifact_types),
            value = manifest.artifactTypes.joinToString(", ") { it.type }.ifEmpty { noneLabel },
        )

        if (definition != null && manifest.tools.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            JarvysSectionLabel(
                stringResource(R.string.modules_tool_policies),
                Modifier.padding(horizontal = JarvysUiTokens.ScreenPadding),
            )
            manifest.tools.forEach { declaredTool ->
                definition.operations.firstOrNull { it.name == declaredTool.name }?.let { operation ->
                    AutonomyPolicyRow(connectorRegistry, definition, operation)
                }
            }
        }

        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 6.dp)) }
    }
}

@Composable
private fun ModuleMetadataLine(title: String, value: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
