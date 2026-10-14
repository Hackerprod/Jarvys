package com.jarvys.agent.connectors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/** Full-only principal and Coding policies remain separate, visible and reversible. */
object FlavorAutonomyUi {
    fun handlesApproval(event: AgentRunUiEvent): Boolean =
        isProjectApproval(event) || (event.approvalAutonomyConnectorId == LinuxExecAutonomy.CONNECTOR_ID &&
            event.approvalAutonomyOperationName == LinuxExecAutonomy.OPERATION_NAME)

    private fun isProjectApproval(event: AgentRunUiEvent): Boolean =
        event.approvalAutonomyConnectorId == LinuxExecAutonomy.PROJECT_CONNECTOR_ID &&
            event.approvalAutonomyOperationName == LinuxExecAutonomy.PROJECT_OPERATION_NAME

    @Composable private fun autonomy(): LinuxExecAutonomy {
        val context = LocalContext.current
        return LocalFlavorAutonomy.current ?: remember(context.applicationContext) { LinuxExecAutonomy.get(context) }
    }

    @Composable fun ApprovalAction(event: AgentRunUiEvent, onStatusDetail: (ConnectorUiText) -> Unit) {
        val autonomy = autonomy()
        val project = isProjectApproval(event)
        Text(stringResource(if (project) R.string.full_coding_exec_approval_allow_warning else R.string.full_linux_exec_approval_allow_warning),
            Modifier.fillMaxWidth().testTag(if (project) "coding-allow-warning" else "linux-allow-warning"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            ApprovalGate.INSTANCE.resolveFromUi(event.approvalId ?: "") {
                var decision = ApprovalDecision.APPROVED_ALLOW_FAILED
                approveAndAllowAlways({
                    if (ApprovalGate.INSTANCE.enableAlwaysFromUi(event.approvalId ?: "")) null
                    else if (project) autonomy.setProjectPolicyFromUi(AutonomyPolicy.ALLOW)
                    else autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
                }, onStatusDetail) { decision = it }
                decision
            }
        }, modifier = Modifier.fillMaxWidth().testTag(if (project) "coding-allow-always" else "linux-allow-always")) {
            Text(stringResource(if (project) R.string.full_coding_exec_allow_always else R.string.full_linux_exec_allow_always))
        }
    }

    @Composable fun DeveloperControls(): Boolean {
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
        val policy = remember(autonomy, revision, resumed) { autonomy.policy() }
        val projectPolicy = remember(autonomy, revision, resumed) { autonomy.projectPolicy() }
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
                }
                ActionPolicyControl(policy, "linux-policy-control", autonomy.authorization().snapshot.origin) { failure = autonomy.setPolicyFromUi(it) }
                if (!installed) Text(stringResource(R.string.full_linux_exec_not_installed),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("linux-policy-unavailable"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                failure?.let { Text(it.resolve(context), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
            MaintenanceControls(autonomy, LinuxExecAutonomy.SETUP_OPERATION_NAME, R.string.full_linux_setup_policy_label)
            MaintenanceControls(autonomy, LinuxExecAutonomy.UNINSTALL_OPERATION_NAME, R.string.full_linux_uninstall_policy_label)
            CodingControls(autonomy, projectPolicy)
        }
        return true
    }

    @Composable private fun MaintenanceControls(autonomy: LinuxExecAutonomy, operation: String, label: Int) {
        val context = LocalContext.current
        var failure by remember(operation) { mutableStateOf<ConnectorUiText?>(null) }
        var refresh by remember(operation) { mutableIntStateOf(0) }
        val authorization = refresh.let { autonomy.maintenanceAuthorization(operation) }
        JarvysGroup {
            Column(Modifier.fillMaxWidth().testTag("$operation-policy-controls")) {
                Text(stringResource(label), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
                ActionPolicyControl(authorization.policy, "$operation-policy-control", authorization.snapshot.origin) {
                    failure = autonomy.setMaintenancePolicyFromUi(operation, it)
                    refresh++
                }
                failure?.let { Text(it.resolve(context), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    @Composable private fun CodingControls(autonomy: LinuxExecAutonomy, policy: AutonomyPolicy) {
        val context = LocalContext.current
        var failure by remember { mutableStateOf<ConnectorUiText?>(null) }
        JarvysSectionLabel(stringResource(R.string.full_coding_autonomy_title), Modifier.padding(start = 4.dp))
        JarvysGroup {
            Column(Modifier.fillMaxWidth().testTag("coding-autonomy-controls")) {
                Text(stringResource(R.string.full_coding_autonomy_description),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionPolicyControl(policy, "coding-policy-control", autonomy.projectAuthorization().snapshot.origin) { failure = autonomy.setProjectPolicyFromUi(it) }
                failure?.let { Text(it.resolve(context), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    @Composable private fun ActionPolicyControl(policy: AutonomyPolicy, tag: String, origin: ActionPolicyOrigin, onSelect: (AutonomyPolicy) -> Unit) {
        fun label(choice: AutonomyPolicy): Int = when (choice) {
            AutonomyPolicy.ALLOW -> R.string.connector_policy_allow_automatically
            AutonomyPolicy.ASK -> R.string.connector_policy_ask_each_time
            AutonomyPolicy.DENY -> R.string.connector_policy_deny_action
        }
        ConnectorPolicySelector(label(policy), AutonomyPolicy.entries.map { choice ->
            ConnectorPolicyChoice(label(choice)) { onSelect(choice) }
        }, subjectKey = tag, origin = origin, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag))
    }

}
