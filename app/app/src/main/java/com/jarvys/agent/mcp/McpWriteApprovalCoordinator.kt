package com.jarvys.agent.mcp

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R
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

/** MCP writes bridge to Jarvys' existing persisted Autonomy policies, notifier and ApprovalGate. */
class McpWriteApprovalCoordinator(
    private val autonomyStore: ConnectorAutonomyStore,
    private val approvalGate: ApprovalGate,
    private val notifier: com.jarvys.agent.connectors.AutonomyActionNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun policy(tool: McpToolDefinition): AutonomyPolicy = autonomyStore.policy(connectorId(tool), tool.wireName)

    fun setPolicy(tool: McpToolDefinition, policy: AutonomyPolicy) {
        require(tool.access == McpToolAccess.WRITE) { "Autonomy policy only applies to MCP write tools" }
        require(policy != AutonomyPolicy.ALLOW || !McpToolSecurity.isDestructive(tool.wireName, tool.annotations)) {
            "Destructive MCP tools cannot use Allow"
        }
        if (policy == AutonomyPolicy.ALLOW) require(notifier.canPost()) {
            "Enable Jarvys notifications before enabling Allow mode"
        }
        autonomyStore.setPolicy(connectorId(tool), tool.wireName, policy)
    }

    /** Clears persistent write grants when a remote server is removed or disconnected. */
    fun forgetServer(serverId: String) = autonomyStore.clearPolicies("mcp:$serverId")

    fun execute(
        tool: McpToolDefinition,
        arguments: JSONObject,
        token: CancellationToken,
        action: () -> JSONObject,
    ): JSONObject = execute(tool, arguments, token, null, action)

    fun execute(
        tool: McpToolDefinition,
        arguments: JSONObject,
        token: CancellationToken,
        requester: String?,
        action: () -> JSONObject,
    ): JSONObject = execute(tool, arguments, token, requester, null, action)

    fun execute(
        tool: McpToolDefinition,
        arguments: JSONObject,
        token: CancellationToken,
        requester: String?,
        requesterColorKey: String?,
        action: () -> JSONObject,
    ): JSONObject {
        if (tool.access == McpToolAccess.READ) return action()
        val key = connectorId(tool)
        val configured = autonomyStore.policy(key, tool.wireName)
        if (configured == AutonomyPolicy.DENY) throw McpWriteDeniedException("Remote write is denied by the saved per-tool policy")

        val destructive = McpToolSecurity.isDestructive(tool.wireName, tool.annotations)
        val automatic = configured == AutonomyPolicy.ALLOW && !destructive && notifier.canPost()
        if (automatic) {
            token.throwIfCancelled()
            val result = action()
            auditAndNotify(tool, result)
            return result
        }

        val title = tool.annotations?.title?.takeIf(String::isNotBlank) ?: tool.wireName
        val args = McpToolSecurity.approvalArgumentSummary(arguments)
        val localizedLines = buildList {
            add(ConnectorUiText(R.string.remote_write_approval_warning,
                fallback = "The remote service controls this tool and its descriptions. Review the target and arguments before approving."))
            args.forEach { (name, value) ->
                add(ConnectorUiText(R.string.remote_write_approval_argument, listOf(name, value), "$name: $value"))
            }
            if (configured == AutonomyPolicy.ALLOW && destructive) {
                add(ConnectorUiText(fallback = "High-impact writes always require confirmation."))
            }
            if (configured == AutonomyPolicy.ALLOW && !notifier.canPost()) {
                add(ConnectorUiText(fallback = "Allow mode is unavailable while Android notifications are disabled."))
            }
        }
        val summary = ApprovalSummary(
            title = "${tool.serverAlias} · $title",
            lines = localizedLines.map { it.fallback },
            allowAlwaysAvailable = false,
            localizedTitle = ConnectorUiText(R.string.remote_write_approval_title,
                listOf(tool.serverAlias, title), "${tool.serverAlias} · $title"),
            localizedLines = localizedLines,
            compactSummary = ConnectorUiText(R.string.remote_write_approval_summary,
                fallback = "Remote service write action"),
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
        token.throwIfCancelled()
        return action()
    }

    private fun auditAndNotify(tool: McpToolDefinition, result: JSONObject) {
        val record = AutonomyAuditRecord(
            timestampMillis = clock(),
            connectorId = connectorId(tool),
            connectorName = tool.serverAlias,
            operationName = tool.wireName,
            operationLabel = tool.annotations?.title ?: tool.wireName,
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

class McpWriteDeniedException(message: String) : IllegalStateException(message)
