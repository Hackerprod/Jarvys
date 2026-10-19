package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry

/** Writes every worker-to-chief message into Central's durable conversation input pipeline. */
internal object WorkspaceAgentInputDelivery {
    @JvmStatic
    fun deliver(context: Context, bot: CrewManager.Bot, message: CrewMessage) {
        require(message.from == bot.id && message.to == "chief" && message.activity == null)
        require(WorkspaceIdentity.isExecutionSession(message.conversationId))
        require(WorkspaceCoordinationDelivery.isCanonicalWorkspace(context, message.conversationId))
        val workspaceId = WorkspaceIdentity.workspaceId(message.conversationId)
        val assignment = WorkspaceRegistry(context).use { registry ->
            requireNotNull(registry.getAssignment(workspaceId, bot.assignmentId())) {
                "Worker message has no bound workspace assignment"
            }.also {
                check(it.missionId == bot.missionId && it.botId == bot.id) {
                    "Worker message does not match its bound workspace assignment"
                }
            }
        }
        val kind = try { ConversationInput.Kind.valueOf(message.type.name) }
        catch (_: IllegalArgumentException) { ConversationInput.Kind.OTHER }
        val input = ConversationInput(
            WorkspaceIdentity.CENTRAL_SESSION,
            message.id,
            message.conversationContent(),
            ConversationInput.Origin.AGENT,
            ConversationInput.Provenance(message.conversationId, workspaceId, assignment.assignmentId,
                bot.id, message.id, kind),
        )
        val storedId = LocalRunStore(context).appendConversationInput(input)
        AgentForegroundService.startRealAgentFromStoredChatMessage(
            context.applicationContext, WorkspaceIdentity.CENTRAL_SESSION, storedId,
        )
    }
}
