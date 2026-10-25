package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.AgentErrorReporter
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.proactive.refreshVisibleCentral

/**
 * Jarvys reports bot work in the Central thread with messages that carry a [MissionRef]. The text is
 * a plain description for diagnostics and search; the model never reads these messages (they are
 * excluded from its context) and the user sees the mission card the reference resolves to.
 *
 * Both messages are recoverable: the stable id makes each one exist at most once, and callers invoke
 * them whenever the underlying delegation or delivery is reconciled, so a card that was lost to a
 * crash or a write failure is written on the next pass.
 */
internal object WorkspaceMissionCards {
    fun delegated(context: Context, scope: WorkspaceRecord, assignment: WorkspaceAssignment) {
        val ref = MissionRef.of(scope.workspaceId, assignment.assignmentId, assignment.missionId)
        append(context, "Delegated to bot ${assignment.name ?: assignment.role} in workspace \"${scope.title}\" " +
            "(mission ${assignment.missionId}).", ref, "mission_ref:${assignment.assignmentId}")
    }

    fun resultDelivered(context: Context, reference: WorkspaceResultReference) {
        val ref = MissionRef(reference.workspaceId, reference.executionSessionId, reference.missionId,
            reference.assignmentId, reference.referenceId, reference.contentSha256)
        append(context, "A result from bot ${reference.botId} is available to read " +
            "(workspace ${reference.workspaceId}, result ${reference.referenceId}).", ref,
            "mission_result:${reference.referenceId}")
    }

    private fun append(context: Context, text: String, ref: MissionRef, stableId: String) {
        val app = context.applicationContext
        try {
            val store = LocalRunStore(app)
            if (store.readConversationMessageByStableId(WorkspaceIdentity.CENTRAL_SESSION, stableId) != null) return
            store.appendMissionReferenceMessage(WorkspaceIdentity.CENTRAL_SESSION, text, ref.toJson(), stableId)
            store.refreshVisibleCentral()
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            // The delegation or result is already durable elsewhere and reconciliation retries this card.
            AgentErrorReporter.reportChatDatabaseFailure(app, failure)
        }
    }
}
