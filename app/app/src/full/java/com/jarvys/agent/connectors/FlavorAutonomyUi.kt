package com.jarvys.agent.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.R
import com.jarvys.agent.linux.LinuxExecAutonomy

internal val LocalFlavorAutonomy = staticCompositionLocalOf<LinuxExecAutonomy?> { null }

/** Full flavor alone exposes the opt-in Linux command policy. */
object FlavorAutonomyUi {
    fun handlesApproval(event: AgentRunUiEvent): Boolean =
        event.approvalAutonomyConnectorId == LinuxExecAutonomy.CONNECTOR_ID &&
            event.approvalAutonomyOperationName == LinuxExecAutonomy.OPERATION_NAME

    @Composable private fun autonomy(): LinuxExecAutonomy {
        val context = LocalContext.current
        return LocalFlavorAutonomy.current ?: remember(context.applicationContext) { LinuxExecAutonomy.get(context) }
    }

    @Composable fun ApprovalAction(event: AgentRunUiEvent, onStatusDetail: (ConnectorUiText) -> Unit) {
        val autonomy = autonomy()
        Text(stringResource(R.string.full_linux_exec_approval_allow_warning),
            Modifier.fillMaxWidth().testTag("linux-allow-warning"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            ApprovalGate.INSTANCE.resolveFromUi(event.approvalId ?: "") {
                var decision = ApprovalDecision.APPROVED_ALLOW_FAILED
                approveAndAllowAlways({ autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW) }, onStatusDetail) { decision = it }
                decision
            }
        }, modifier = Modifier.fillMaxWidth().testTag("linux-allow-always")) {
            Text(stringResource(R.string.full_linux_exec_allow_always))
        }
    }

    @Composable fun ConnectorControls(): Boolean {
        val autonomy = autonomy()
        val revision by autonomy.revision.collectAsState()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var resumed by remember { mutableIntStateOf(0) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val installed = remember(autonomy, revision, resumed) { autonomy.installed() }
        if (!installed) return false
        val policy = remember(autonomy, revision) { autonomy.policy() }
        var failure by remember { mutableStateOf<ConnectorUiText?>(null) }
        val context = LocalContext.current
        val label = stringResource(R.string.full_linux_autonomy_switch)
        val status = stringResource(when (policy) {
            AutonomyPolicy.ALLOW -> R.string.full_linux_autonomy_allow
            AutonomyPolicy.ASK -> R.string.full_linux_autonomy_ask
            AutonomyPolicy.DENY -> R.string.full_linux_autonomy_deny
        })
        Column(Modifier.fillMaxWidth().testTag("linux-autonomy-controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JarvysSectionLabel(stringResource(R.string.full_linux_autonomy_title), Modifier.padding(start = 4.dp))
            JarvysGroup {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = policy == AutonomyPolicy.ALLOW,
                        onCheckedChange = { failure = autonomy.setPolicyFromUi(if (it) AutonomyPolicy.ALLOW else AutonomyPolicy.ASK) },
                        modifier = Modifier.testTag("linux-autonomy-switch").semantics { contentDescription = label; stateDescription = status })
                }
                failure?.let { Text(it.resolve(context), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
        }
        return true
    }
}
