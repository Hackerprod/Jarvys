package com.jarvys.agent.modules

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult

/** Injectable execution boundary implemented by the isolated module host. */
fun interface ModuleRuntime {
    fun execute(
        request: ModuleExecutionRequest,
        token: CancellationToken,
        progress: CoreTool.ProgressListener?,
    ): CoreToolResult
}

data class ModuleExecutionRequest(
    val module: ModuleManifest,
    val tool: ModuleToolDeclaration,
    val arguments: Map<String, Any?>,
    val conversationId: String?,
    val requester: String?,
    val requesterColorKey: String?,
)
