package com.jarvys.agent

import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorInvocationContext
import com.jarvys.agent.connectors.ConnectorOperation
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.modules.ModuleManifest
import com.jarvys.agent.modules.MODULE_TOOL_RESULT_KEY
import com.jarvys.agent.modules.ModuleToolAccess
import com.jarvys.agent.modules.ModuleToolDeclaration
import org.json.JSONObject

/** The single CoreTool adapter for a tool declared by any bundled module. */
class ModuleTool internal constructor(
    private val module: ModuleManifest,
    private val moduleTool: ModuleToolDeclaration,
    private val connectorRegistry: ConnectorRegistry,
    private val connectorDefinition: ConnectorDefinition,
    private val operation: ConnectorOperation,
    private val conversationId: String?,
    private val requester: String? = null,
    private val requesterColorKey: String? = null,
) : PolicyGatedTool {
    private val inputSchema = moduleTool.inputSchema
    private val spec = ToolSpec(
        moduleTool.name,
        "Module manifest ${module.id}",
        moduleTool.description,
        "module:${module.id}",
        ToolSpec.Status.IMPLEMENTED,
        emptyMap(),
        emptyList(),
        inputSchema,
    )

    override fun declaration(): ToolSpec = spec

    override fun executionMode(): CoreTool.ExecutionMode = if (moduleTool.access == ModuleToolAccess.READ)
        CoreTool.ExecutionMode.PARALLEL else CoreTool.ExecutionMode.SEQUENTIAL

    override fun unattendedAccess(): CoreTool.UnattendedAccess = if (moduleTool.access == ModuleToolAccess.READ)
        CoreTool.UnattendedAccess.READ else CoreTool.UnattendedAccess.WRITE

    override fun accessClassification(): CoreTool.AccessClassification = if (moduleTool.access == ModuleToolAccess.READ)
        CoreTool.AccessClassification.READ else CoreTool.AccessClassification.WRITE

    override fun isConnectorTool(): Boolean = true

    override fun effectiveAutonomyPolicy(): AutonomyPolicy =
        connectorRegistry.configuredAutonomyPolicy(connectorDefinition, operation)

    override fun policyGroupName(): String = "connector:${module.id}"

    override fun policyDisplayName(): String = CoreToolRegistry.humanizeToolName(moduleTool.name)

    override fun policyReflectionSource(): String = "connector:${module.id}"

    override fun withRequester(requester: String?, requesterColorKey: String?): ModuleTool = ModuleTool(
        module, moduleTool, connectorRegistry, connectorDefinition, operation,
        conversationId, requester, requesterColorKey,
    )

    override fun execute(arguments: Map<String, Any?>?, token: CancellationToken): CoreToolResult =
        execute(arguments, token, null)

    override fun execute(
        arguments: Map<String, Any?>?,
        token: CancellationToken,
        progress: CoreTool.ProgressListener?,
    ): CoreToolResult {
        val validated = ToolArgumentValidator.coerceAndValidate(
            arguments?.mapValues { it.value as Any? }, inputSchema,
        )
        if (validated.error != null) return CoreToolResult.failure(validated.error)
        val invocationContext = ConnectorInvocationContext(
            requester = requester,
            requesterColorKey = requesterColorKey,
            conversationId = conversationId,
            progress = progress?.let { listener -> { message -> listener.onProgress(message) } },
        )
        val response = connectorRegistry.invoke(
            connectorDefinition,
            operation,
            JSONObject(validated.arguments),
            token,
            invocationContext,
        )
        val envelope = response.optJSONObject(MODULE_TOOL_RESULT_KEY)
            ?: return CoreToolResult.failure(response.toString())
        val content = envelope.optString("content", "")
        if (!envelope.optBoolean("success", false)) return CoreToolResult.failure(content)
        if (envelope.optBoolean("finishRun", false)) return CoreToolResult.finish(content)
        if (envelope.optBoolean("completeContentRequired", false)) return CoreToolResult.complete(content)
        val previewId = envelope.optString("previewId").takeIf(String::isNotBlank)
        return if (previewId != null) CoreToolResult.preview(content, previewId) else CoreToolResult.success(content)
    }

    /** WebView-backed handlers need a foreground host, so scheduled execution is always surfaced. */
    override fun executeScheduledTask(
        arguments: Map<String, Any?>?,
        token: CancellationToken,
        approvedPendingAction: Boolean,
        deferAskWrite: java.util.function.Supplier<JSONObject>?,
    ): CoreToolResult {
        token.throwIfCancelled()
        if (effectiveAutonomyPolicy() == AutonomyPolicy.DENY) {
            return CoreToolResult.failure("This action is unavailable under its current policy.")
        }
        return CoreToolResult.success(JSONObject()
            .put("status", VISIBLE_APP_REQUIRED_STATUS)
            .put("executed", false)
            .put("message", "Open the app to run this action; it was deferred for visible execution.")
            .toString())
    }

    companion object {
        const val VISIBLE_APP_REQUIRED_STATUS = "deferred_visible_app_required"
    }
}
