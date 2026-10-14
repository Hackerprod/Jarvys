package com.jarvys.agent.connectors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.jarvys.agent.R

/** Shared per-operation autonomy control used by Developer settings across connector families. */
@Composable
internal fun AutonomyPolicyRow(
    registry: ConnectorRegistry,
    definition: ConnectorDefinition,
    operation: ConnectorOperation,
) {
    val context = LocalContext.current
    val revision by registry.autonomyRevision.collectAsState()
    val configured = remember(registry, definition.id, operation.name, revision) {
        registry.configuredAutonomyPolicy(definition, operation)
    }
    val origin = remember(registry, definition.id, operation.name, revision) {
        registry.configuredAutonomyOrigin(definition, operation)
    }
    val effective = remember(registry, definition.id, operation.name, revision) {
        registry.autonomyPolicy(definition, operation)
    }
    val unavailable = remember(registry, definition.id, operation.name, revision) {
        registry.autonomyPolicyUnavailableUiReason(definition, operation)
    }
    var errorText by remember(definition.id, operation.name) { mutableStateOf<String?>(null) }

    Column {
        ConnectorPermissionRow(modifier = Modifier.testTag("connector-policy-${operation.name}-row"), content = {
            Text(operation.localizedDisplayText().resolve(context), fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }, permission = {
            ConnectorPolicySelector(
                modifier = Modifier.testTag("connector-policy-${operation.name}-control"),
                subjectKey = "${definition.id}/${operation.name}",
                selectedLabelResource = when (configured) {
                    AutonomyPolicy.ASK -> R.string.connector_policy_ask
                    AutonomyPolicy.ALLOW -> R.string.connector_policy_allow
                    AutonomyPolicy.DENY -> R.string.connector_policy_deny
                },
                choices = AutonomyPolicy.entries.map { policy ->
                    val label = when (policy) {
                        AutonomyPolicy.ASK -> R.string.connector_policy_ask
                        AutonomyPolicy.ALLOW -> R.string.connector_policy_allow
                        AutonomyPolicy.DENY -> R.string.connector_policy_deny
                    }
                    ConnectorPolicyChoice(label) {
                        errorText = runCatching { registry.setAutonomyPolicy(definition, operation, policy); null }
                            .getOrElse { it.message?.takeIf(String::isNotBlank)
                                ?: context.getString(R.string.connector_allow_failed) }
                    }
                },
            )
        })
        ConnectorPolicyOriginCaption(origin,
            Modifier.fillMaxWidth().testTag("connector-policy-${operation.name}-origin"))
        if (unavailable != null) Text(
            context.getString(R.string.connector_active_policy, effective.label(context), unavailable.resolve(context)),
            color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
        )
        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
            modifier = Modifier.testTag("connector-policy-${operation.name}-error")) }
    }
}

private fun ConnectorOperation.localizedDisplayText() = ConnectorUiText(displayLabelResourceId, fallback = displayLabel)

private fun AutonomyPolicy.label(context: android.content.Context): String = context.getString(when (this) {
    AutonomyPolicy.ASK -> R.string.connector_policy_ask
    AutonomyPolicy.ALLOW -> R.string.connector_policy_allow
    AutonomyPolicy.DENY -> R.string.connector_policy_deny
})
