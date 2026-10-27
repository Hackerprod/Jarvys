package com.jarvys.agent.chatdb

import android.content.Context
import com.jarvys.agent.coding.CodingJobEvidence
import com.jarvys.agent.coding.CodingJobSubmission
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry

/** Exact assignment history only. Run outside the Room anchor/lease; never guess a latest root. */
internal fun resolveCodingJobSource(context: Context, submission: CodingJobSubmission): CodingJobEvidence.Source {
    val source = submission.crew()
    val crew = CodingJobEvidence.CrewSource(source.executionSessionId, source.botId, source.missionId,
        source.cycleId, source.scopeIdentity, source.checkpointPayloadSha256, source.callId, source.toolName, source.inputContext)
    if (!WorkspaceIdentity.isExecutionSession(source.executionSessionId)) return CodingJobEvidence.Source(crew, null, null)
    val workspaceId = WorkspaceIdentity.workspaceId(source.executionSessionId)
    return WorkspaceRegistry(context).use { registry ->
        val workspace = registry.getWorkspace(workspaceId)
        if (workspace == null) return@use CodingJobEvidence.Source(crew, null, null)
        check(workspace.executionSessionId == source.executionSessionId) { "Coding workspace execution identity mismatch" }
        val assignment = registry.findBoundAssignment(workspaceId, source.missionId, source.botId)
            ?: return@use CodingJobEvidence.Source(crew, null, null)
        CodingJobEvidence.Source(crew,
            CodingJobEvidence.WorkspaceBinding(workspaceId, assignment.assignmentId, assignment.requestId), assignment.centralSource)
    }
}
