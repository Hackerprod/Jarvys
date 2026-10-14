package com.jarvys.agent.mcp

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R
import com.jarvys.agent.connectors.ActionPolicySnapshot
import com.jarvys.agent.connectors.AndroidAutonomyActionNotifier
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyAuditRecord
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorUiText
import com.jarvys.agent.connectors.ConnectorAutonomyStore
import com.jarvys.agent.connectors.SharedPreferencesConnectorAutonomyStore
import org.json.JSONObject

/** All remote actions use the same persisted decision; grants and tool exposure stay independent. */
class McpWriteApprovalCoordinator(
    private val autonomyStore: ConnectorAutonomyStore,
    private val approvalGate: ApprovalGate,
    private val notifier: com.jarvys.agent.connectors.AutonomyActionNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun snapshot(tool: McpToolDefinition): ActionPolicySnapshot = tool.inheritedPolicy?.let {
        autonomyStore.snapshot(connectorId(tool), tool.wireName, it)
    } ?: autonomyStore.snapshot(connectorId(tool), tool.wireName)

    fun policy(tool: McpToolDefinition): AutonomyPolicy = snapshot(tool).policy

    fun setPolicy(tool: McpToolDefinition, policy: AutonomyPolicy) {
        autonomyStore.setPolicyChecked(connectorId(tool), tool.wireName, policy)
    }

    /** Explicit server removal clears its policies; discovery never clears or overwrites tombstones. */
    fun forgetServer(serverId: String) = autonomyStore.clearPolicies("mcp:$serverId")

    fun execute(tool: McpToolDefinition, arguments: JSONObject, token: CancellationToken,
                action: () -> JSONObject): JSONObject = execute(tool, arguments, token, null, null, action)

    fun execute(tool: McpToolDefinition, arguments: JSONObject, token: CancellationToken,
                requester: String?, action: () -> JSONObject): JSONObject =
        execute(tool, arguments, token, requester, null, action)

    fun execute(tool: McpToolDefinition, arguments: JSONObject, token: CancellationToken,
                requester: String?, requesterColorKey: String?, action: () -> JSONObject): JSONObject =
        executeWithAdmission(tool, arguments, token, requester, requesterColorKey) { admission ->
            admission.admit()
            action()
        }

    /**
     * The request owner must admit immediately before EACH dispatch, including a definitive-401 retry
     * or a GitHub preflight. The reviewed request stays frozen in that owner. Network/UI never runs
     * under the policy or STOP lock. A later policy/config revision invalidates the whole request.
     */
    internal fun executeWithAdmission(
        tool: McpToolDefinition,
        arguments: JSONObject,
        token: CancellationToken,
        requester: String?,
        requesterColorKey: String?,
        action: (McpActionAdmission) -> JSONObject,
    ): JSONObject {
        token.throwIfCancelled()
        val decision = snapshot(tool)
        if (decision.policy == AutonomyPolicy.DENY) throw McpWriteDeniedException("Remote action is denied by its saved policy")
        val approvedAsk = decision.policy == AutonomyPolicy.ASK
        if (approvedAsk) requestApproval(tool, arguments, token, requester, requesterColorKey)
        val admission = McpActionAdmission(autonomyStore, decision, token, approvedAsk)
        val result = action(admission)
        if (decision.policy == AutonomyPolicy.ALLOW && tool.access == McpToolAccess.WRITE) auditAndNotify(tool, result)
        return result
    }

    /** Task calls use the same persisted policy but never request chat approval UI. */
    internal fun executeForScheduledTask(
        tool: McpToolDefinition,
        arguments: JSONObject,
        token: CancellationToken,
        approvedPendingAction: Boolean,
        deferAskWrite: java.util.function.Supplier<JSONObject>?,
        action: (McpActionAdmission) -> JSONObject,
    ): JSONObject {
        token.throwIfCancelled()
        val decision = snapshot(tool)
        if (decision.policy == AutonomyPolicy.DENY) throw McpWriteDeniedException("Remote action is denied by its saved policy")
        if (decision.policy == AutonomyPolicy.ASK && !approvedPendingAction) {
            return requireNotNull(deferAskWrite) {
                "A pending-action queue is required for ASK operations in a scheduled task"
            }.get()
        }
        val admission = McpActionAdmission(autonomyStore, decision, token,
            approvedAsk = decision.policy == AutonomyPolicy.ASK)
        val result = action(admission)
        if (decision.policy == AutonomyPolicy.ALLOW && tool.access == McpToolAccess.WRITE) auditAndNotify(tool, result)
        return result
    }

    private fun requestApproval(tool: McpToolDefinition, arguments: JSONObject, token: CancellationToken,
                                requester: String?, requesterColorKey: String?) {
        val title = tool.annotations?.title?.takeIf(String::isNotBlank) ?: tool.wireName
        val args = if (tool.catalogServiceId == "github") emptyList() else McpToolSecurity.approvalArgumentSummary(arguments)
        val localizedLines = buildList {
            add(ConnectorUiText(fallback = "The remote service controls this tool and its descriptions. Review the exact target and arguments."))
            if (tool.catalogServiceId == "github") com.jarvys.agent.connectors.GitHubOperationPolicy.approvalLines(tool.wireName, arguments).forEach {
                add(ConnectorUiText(fallback = it))
            }
            args.forEach { (name, value) ->
                add(ConnectorUiText(R.string.remote_write_approval_argument, listOf(name, value), "$name: $value"))
            }
        }
        val summary = ApprovalSummary(
            title = "${tool.serverAlias} · $title",
            lines = localizedLines.map { it.fallback },
            allowAlwaysAvailable = false,
            localizedLines = localizedLines,
            compactSummary = ConnectorUiText(fallback = if (tool.access == McpToolAccess.READ) "Remote service read action" else "Remote service write action"),
            requester = requester,
            requesterColorKey = requesterColorKey,
        )
        when (approvalGate.request(summary, token)) {
            ApprovalDecision.APPROVED, ApprovalDecision.APPROVED_ALLOW_ALWAYS, ApprovalDecision.APPROVED_ALLOW_FAILED -> Unit
            ApprovalDecision.DENIED -> throw McpWriteDeniedException("The user denied this remote service action; do not retry without a new explicit request")
            ApprovalDecision.EXPIRED -> throw McpWriteDeniedException("Remote service approval expired")
            ApprovalDecision.CANCELLED -> throw java.util.concurrent.CancellationException("Remote service approval was cancelled")
            else -> throw McpWriteDeniedException("Remote service action was not approved")
        }
    }

    private fun auditAndNotify(tool: McpToolDefinition, result: JSONObject) {
        val record = AutonomyAuditRecord(
            timestampMillis = clock(), connectorId = connectorId(tool), connectorName = tool.serverAlias,
            operationName = tool.wireName, operationLabel = tool.annotations?.title ?: tool.wireName,
            summary = "Remote MCP write completed; result omitted from audit",
        )
        if (runCatching { autonomyStore.appendAudit(record) }.isFailure) {
            result.put("auditWarning", "The remote action ran, but its local audit could not be saved; check the provider before retrying.")
        }
        if (runCatching { notifier.post(record) }.isFailure) {
            result.put("notificationWarning", "The remote action ran, but Android did not show the Allow-mode notice.")
        }
    }

    private fun connectorId(tool: McpToolDefinition) = "mcp:${tool.serverId}"

    companion object {
        @Volatile private var instance: McpWriteApprovalCoordinator? = null
        fun get(context: Context): McpWriteApprovalCoordinator = instance ?: synchronized(this) {
            instance ?: SharedPreferencesConnectorAutonomyStore(context.applicationContext).let { store ->
                McpWriteApprovalCoordinator(store, ApprovalGate.INSTANCE,
                    AndroidAutonomyActionNotifier(context.applicationContext)).also { instance = it }
            }
        }
    }
}

/** A logical request's approval is reusable only for its verified subrequests/definitive retries. */
internal class McpActionAdmission(
    private val store: ConnectorAutonomyStore,
    private val baseline: ActionPolicySnapshot,
    private val token: CancellationToken,
    private val approvedAsk: Boolean,
) {
    fun checkCurrent() { store.snapshotMatching(baseline) }

    fun admit(validate: () -> Unit = {}) {
        val candidate = store.snapshotMatching(baseline)
        if (!token.runIfActive {
            store.admit(candidate, approvedAsk = approvedAsk) {
                token.throwIfCancelled()
                validate()
            }
        }) {
            token.throwIfCancelled()
            throw java.util.concurrent.CancellationException("Remote action was stopped before admission")
        }
    }
}

class McpWriteDeniedException(message: String) : IllegalStateException(message)
