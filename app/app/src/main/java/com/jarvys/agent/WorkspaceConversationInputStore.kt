package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Durable conversation evidence and exact incorporation receipt for workspace worker inputs. */
internal object WorkspaceConversationInputStore {
    @JvmStatic
    fun append(context: Context, executionSessionId: String, bot: CrewManager.Bot,
               message: CrewMessage): String {
        if (message.activity != null || "user" == message.from) return ""
        val input = toConversationInput(executionSessionId, bot, message)
        return LocalRunStore(context).appendConversationInput(input)
    }

    private fun toConversationInput(executionSessionId: String, bot: CrewManager.Bot,
                                    message: CrewMessage): ConversationInput {
        require(message.activity == null && "user" != message.from)
        val workspaceId = WorkspaceIdentity.workspaceId(executionSessionId)
        val origin = if ("recovery" == message.from) ConversationInput.Origin.SYSTEM
            else ConversationInput.Origin.AGENT
        val sourceSession = if ("chief" == message.from || "recovery" == message.from)
            WorkspaceIdentity.CENTRAL_SESSION else executionSessionId
        val assignmentId = message.inputBinding?.assignmentId ?: bot.assignmentId()
        val sourceBotId = if ("chief" == message.from || "recovery" == message.from) "" else message.from
        val kind = try { ConversationInput.Kind.valueOf(message.type.name) }
            catch (_: IllegalArgumentException) { ConversationInput.Kind.OTHER }
        return ConversationInput(
            executionSessionId, message.id, message.conversationContent(), origin,
            ConversationInput.Provenance(sourceSession, workspaceId, assignmentId, sourceBotId,
                message.id, kind),
        )
    }

    @JvmStatic
    fun acknowledge(context: Context, executionSessionId: String, messageIds: List<String>) {
        LocalRunStore(context).markConversationMessagesIncorporated(executionSessionId, messageIds)
    }

    /** Repairs the only crash window: a worker checkpoint committed its input before the Room ack. */
    @JvmStatic
    fun acknowledgeApplied(context: Context, executionSessionId: String, appliedSourceIds: Set<String>) {
        if (appliedSourceIds.isEmpty()) return
        val store = LocalRunStore(context)
        val rows = store.readConversationMessages(executionSessionId).filter { row ->
            if (row.optString("role") != "agent" && row.optString("role") != "system") return@filter false
            val provenance = row.optJSONObject("provenance") ?: return@filter false
            provenance.optString("messageId") in appliedSourceIds && row.optString("inputState").isEmpty()
        }.mapNotNull { row -> row.optString("messageId").takeIf(String::isNotBlank) }
        store.markConversationMessagesIncorporated(executionSessionId, rows)
    }
}
