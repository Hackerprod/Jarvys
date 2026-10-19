package com.jarvys.agent.connectors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.R
import java.util.Locale

/** Vertical inset each policy row keeps; the enclosing card adds the rest of its edge margin. */
internal val ConnectorPolicyRowVerticalInset = 4.dp

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
    var errorText by remember(definition.id, operation.name) { mutableStateOf<String?>(null) }

    val tagPrefix = "connector-policy-${operation.name}"
    val optionLabels = AutonomyPolicy.entries.associateWith { it.labelResource() }
    val choices = AutonomyPolicy.entries.map { policy ->
        ConnectorPolicyChoice(optionLabels.getValue(policy)) {
            errorText = runCatching { registry.setAutonomyPolicy(definition, operation, policy); null }
                .getOrElse { it.message?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.connector_allow_failed) }
        }
    }
    val selector: @Composable () -> Unit = {
        ConnectorPolicySelector(
            subjectKey = "${definition.id}/${operation.name}",
            selectedLabelResource = configured.labelResource(),
            choices = choices,
            modifier = Modifier.testTag("$tagPrefix-control"),
            optionTags = AutonomyPolicy.entries.associate { policy ->
                optionLabels.getValue(policy) to "$tagPrefix-option-${policy.name.lowercase(Locale.ROOT)}"
            },
        )
    }

    Column(Modifier.fillMaxWidth().padding(
        horizontal = JarvysUiTokens.ScreenPadding, vertical = ConnectorPolicyRowVerticalInset)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            // ConnectorPermissionRow gives the selector at most half of a narrow row. At large
            // text sizes, keep the label readable above a full-width selector on compact screens.
            val stackForLargeText = fontScale >= 1.6f && maxWidth <= 224.dp * fontScale
            if (stackForLargeText) {
                var labelHeightPx by remember(definition.id, operation.name) { mutableIntStateOf(0) }
                val labelHeight = with(LocalDensity.current) { labelHeightPx.toDp() }
                Column(Modifier.fillMaxWidth().testTag("$tagPrefix-row")) {
                    AutonomyPolicyLabel(operation,
                        Modifier.fillMaxWidth().onSizeChanged { labelHeightPx = it.height })
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tagPrefix-selector-row"),
                        contentAlignment = Alignment.CenterEnd) { selector() }
                    // Balance the label's height below the selector so it stays centered in the row.
                    Spacer(Modifier.height(labelHeight))
                }
            } else {
                ConnectorPermissionRow(modifier = Modifier.testTag("$tagPrefix-row"), content = {
                    AutonomyPolicyLabel(operation, Modifier)
                }, permission = {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$tagPrefix-selector-row"),
                        contentAlignment = Alignment.CenterEnd) { selector() }
                })
            }
        }
        ConnectorPolicyOriginCaption(origin,
            Modifier.fillMaxWidth().testTag("$tagPrefix-origin"))
        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
            modifier = Modifier.testTag("$tagPrefix-error")) }
    }
}

@Composable
private fun AutonomyPolicyLabel(operation: ConnectorOperation, modifier: Modifier) {
    val context = LocalContext.current
    Text(operation.localizedDisplayText().resolve(context),
        modifier = modifier.testTag("connector-policy-${operation.name}-label"),
        fontWeight = FontWeight.Medium,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun ConnectorOperation.localizedDisplayText() = ConnectorUiText(displayLabelResourceId, fallback = displayLabel)

private fun AutonomyPolicy.labelResource(): Int = when (this) {
    AutonomyPolicy.ASK -> R.string.connector_policy_ask
    AutonomyPolicy.ALLOW -> R.string.connector_policy_allow
    AutonomyPolicy.DENY -> R.string.connector_policy_deny
}
