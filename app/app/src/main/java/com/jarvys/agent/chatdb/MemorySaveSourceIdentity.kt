package com.jarvys.agent.chatdb

import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.security.MessageDigest
import java.util.UUID

/** Read-only committed call metadata, never an invocation or write capability. */
internal data class MemorySaveDurableSource(
    val generation: String,
    val sessionId: String,
    val ownerMessageId: String,
    val batchId: String,
    val callId: String,
    val argumentsJson: String,
    val argumentsDigest: String,
    val inputDigest: String,
    val memorySessionId: String,
)

internal object MemorySaveSourceIdentity {
    class InvalidSource : IllegalStateException("memory_save_source_invalid")
    private fun requireShape(valid: Boolean) { if (!valid) throw InvalidSource() }
    fun existingGeneration(database: JarvysChatDatabase): String? = database.chatSql.query(
        "SELECT value FROM storage_meta WHERE key=?", arrayOf(ConnectionProjectionRepository.GENERATION_KEY),
    ).use { c ->
        if (!c.moveToFirst()) null else {
            requireShape(c.getType(0) == android.database.Cursor.FIELD_TYPE_STRING)
            val value = c.getString(0)
            requireShape(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false) && !c.moveToNext())
            value
        }
    }

    fun argumentsDigest(argumentsJson: String): String = "source-args-utf16le-v1:" +
        MessageDigest.getInstance("SHA-256").digest(ExactUtf16.encode(argumentsJson))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Caller already holds the Room read/write transaction; no generation creation or external IO. */
    fun read(database: JarvysChatDatabase, generation: String, session: String, batch: String, call: String, action: String = "memory_save"): MemorySaveDurableSource? {
        if (session != WorkspaceIdentity.CENTRAL_SESSION || existingGeneration(database) != generation) return null
        return database.chatSql.query(
            "SELECT b.owner_user_message_id,c.arguments_json,c.memory_save_input_digest,b.memory_session_id FROM tool_batches b " +
                "JOIN tool_calls c ON c.conversation_id=b.conversation_id AND c.batch_id=b.batch_id " +
                "JOIN messages m ON m.conversation_id=b.conversation_id AND m.message_id=b.owner_user_message_id " +
                "JOIN conversations v ON v.id=b.conversation_id " +
                "WHERE b.conversation_id=? AND b.batch_id=? AND c.call_id=? AND c.name=? AND c.lifecycle='STARTED' " +
                "AND m.hidden_kind IS NULL AND m.role='user' AND b.calls_decode_error IS NULL " +
                "AND NOT EXISTS(SELECT 1 FROM tool_results r WHERE r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id)",
            arrayOf(session, batch, call, action),
        ).use { c ->
            if (!c.moveToFirst()) null else {
                requireShape(c.getType(0) == android.database.Cursor.FIELD_TYPE_STRING &&
                    c.getType(1) == android.database.Cursor.FIELD_TYPE_STRING &&
                    c.getType(2) == android.database.Cursor.FIELD_TYPE_STRING && c.getType(3) == android.database.Cursor.FIELD_TYPE_STRING)
                val owner = c.getString(0)
                val arguments = c.getString(1)
                val inputDigest = c.getString(2)
                val memorySessionId = c.getString(3)
                requireShape(memorySessionId.isNotBlank())
                requireShape(if(action=="memory_save") MemorySaveOperations.isInputDigest(inputDigest)
                    else MemorySessionCommands.isCommand(action) && MemorySessionCommands.isInputDigest(inputDigest))
                requireShape(!c.moveToNext())
                MemorySaveDurableSource(generation, session, owner, batch, call, arguments, argumentsDigest(arguments), inputDigest, memorySessionId)
            }
        }
    }
}
