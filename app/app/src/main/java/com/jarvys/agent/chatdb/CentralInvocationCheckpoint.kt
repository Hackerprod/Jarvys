package com.jarvys.agent.chatdb

import com.jarvys.agent.CentralInvocationSource
import com.jarvys.agent.connectors.ConnectionStateCoordinator

/** Bridge result, not a tool-facing receipt. The invocation provider binds it to its exact owner. */
class CentralInvocationCheckpoint internal constructor(
    val source: CentralInvocationSource,
    val lifecycle: ConnectionStateCoordinator.DestinationEvidence,
)

/** Exact committed call lookup; no latest-call fallback, arguments, content or memory writes. */
internal suspend fun captureCentralInvocationSource(
    database: JarvysChatDatabase, generation: String, conversationId: String, ownerMessageId: String,
    batchId: String, callId: String, toolName: String, runId: String, processId: String,
): CentralInvocationSource = database.withChatReadTransaction {
    check(com.jarvys.agent.workspaces.WorkspaceIdentity.isCentral(conversationId)) { "Central source requires a Central conversation" }
    val matches = database.chatSql.query(
        "SELECT b.owner_user_message_id,c.name,c.lifecycle FROM tool_batches b " +
            "JOIN tool_calls c ON c.conversation_id=b.conversation_id AND c.batch_id=b.batch_id " +
            "JOIN messages m ON m.conversation_id=b.conversation_id AND m.message_id=b.owner_user_message_id " +
            "JOIN conversations v ON v.id=b.conversation_id " +
            "WHERE b.conversation_id=? AND b.batch_id=? AND c.call_id=? " +
            "AND m.hidden_kind IS NULL AND m.role='user' AND b.calls_decode_error IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM tool_results r WHERE r.conversation_id=c.conversation_id " +
            "AND r.batch_id=c.batch_id AND r.call_id=c.call_id)",
        arrayOf(conversationId, batchId, callId),
    ).use { cursor ->
        cursor.moveToFirst() && cursor.getString(0) == ownerMessageId && cursor.getString(1) == toolName &&
            cursor.getString(2) == "STARTED" && !cursor.moveToNext()
    }
    check(matches) { "Central invocation source is absent, hidden or does not match the committed call" }
    CentralInvocationSource(generation, conversationId, ownerMessageId, batchId, callId, runId, processId)
}
