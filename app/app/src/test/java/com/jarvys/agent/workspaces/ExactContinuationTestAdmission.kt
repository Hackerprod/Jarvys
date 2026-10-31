package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CentralInvocationTestSource
import java.util.UUID

/** Real latest USER command + committed live invocation; never fabricates admission metadata. */
internal object ExactContinuationTestAdmission {
    fun admit(context: Context, workspaceId: String, resultId: String, sha: String, revision: Long) {
        val command = "/continue_result $resultId"
        CentralInvocationTestSource.runWithWitness(context, command) { invocation, _ ->
            val tool = CentralWorkspaceTool(WorkspaceIdentity.CENTRAL_SESSION, UUID.randomUUID().toString(),
                command, WorkspaceCoordinator(context))
            val result = tool.execute(mapOf("action" to "continue_result", "workspace_id" to workspaceId,
                "result_id" to resultId, "expected_sha256" to sha, "expected_assignment_revision" to revision),
                CancellationToken.cancellable(), null, invocation)
            if (!result.success) throw AssertionError("Exact explicit continuation admission failed: ${result.content}")
        }
    }
}
