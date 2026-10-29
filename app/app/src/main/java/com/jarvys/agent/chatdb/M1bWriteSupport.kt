package com.jarvys.agent.chatdb

import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Shared M1b transaction and sequence primitives. M1a remains the owner of message writes. */
internal abstract class M1bWriteSupport(protected val database: JarvysChatDatabase) {
    protected val sql: ChatSqlDatabase get() = database.chatSql

    /** Each caller runs its complete logical append inside one Room transaction. */
    protected suspend fun <T> transaction(block: suspend () -> ChatDbResult<T>): ChatDbResult<T> = try {
        database.withChatWriteTransaction { block() }
    } catch (failure: M1bAbort) {
        when (failure.reason) {
            "conversation_not_found" -> ChatDbResult.NotFound
            "conversation_tombstoned" -> ChatDbResult.Tombstoned
            "conversation_id_invalid" -> ChatDbResult.Invalid("conversation id and kind are required")
            else -> ChatDbResult.Conflict(failure.reason)
        }
    } catch (failure: android.database.SQLException) {
        if (!isChatSqlConstraint(failure)) throw failure
        ChatDbResult.Conflict(failure.message ?: "sqlite_constraint")
    }

    /**
     * Allocate the next monotonic `conversation_items.seq` and insert its positional row. This
     * replaces the append position of the corresponding JSONL type row; callers must be inside
     * [transaction], which makes the counter and spine insert atomic.
     */
    protected fun insertLedgerItem(
        conversationId: String,
        itemKind: String,
        itemKey: String,
        createdAtSeconds: Double = nowSeconds(),
        turnAnchorSeq: Long? = null,
        sortSeq: Long? = null,
        sortSub: Int = 0,
    ): Long {
        ensureConversationForAppend(conversationId)
        if (sql.query(
                "SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId),
            ).use { it.moveToFirst(); it.getInt(0) != 0 }) throw M1bAbort("conversation_tombstoned")
        val next = sql.query("SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId)).use {
            if (!it.moveToFirst()) throw M1bAbort("conversation_not_found")
            it.getLong(0)
        }
        val sequence = next
        val timestamp = createdAtSeconds
        val effectiveSortSeq = sortSeq ?: sequence
        val effectiveSortSub = sortSub
        val effectiveTurnAnchor = turnAnchorSeq
        sql.execSQL(
            "UPDATE conversations SET next_seq=?,updated_at=? WHERE id=? AND next_seq=?",
            arrayOf(sequence + 1L, System.currentTimeMillis(), conversationId, next),
        )
        sql.execSQL(
            "INSERT INTO conversation_items(conversation_id,seq,item_kind,item_key,sort_seq,sort_sub,turn_anchor_seq,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(conversationId, sequence, itemKind, itemKey, effectiveSortSeq, effectiveSortSub, effectiveTurnAnchor, timestamp),
        )
        return sequence
    }

    protected fun ownerMessageSeq(conversationId: String, ownerMessageId: String?): Long? {
        if (ownerMessageId.isNullOrEmpty()) return null
        return sql.query(
            "SELECT seq FROM messages WHERE conversation_id=? AND message_id=? AND role IN ('user','agent','system') LIMIT 1",
            arrayOf(conversationId, ownerMessageId),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** Validates the target before an append; insertLedgerItem creates it at the commit point. */
    protected fun ensureConversationExists(
        conversationId: String,
        kind: String = conversationKindForSession(conversationId),
    ) {
        if (!conversationId.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || conversationId == "." || conversationId == ".." || kind.isBlank())
            throw M1bAbort("conversation_id_invalid")
        if (sql.query(
                "SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId),
            ).use { it.moveToFirst(); it.getInt(0) != 0 }) throw M1bAbort("conversation_tombstoned")
    }

    /** Creates the conversation in the active transaction only after the append is ready to commit. */
    private fun ensureConversationForAppend(conversationId: String) {
        val kind = conversationKindForSession(conversationId)
        ensureConversationExists(conversationId, kind)
        val exists = sql.query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id=?)", arrayOf(conversationId))
            .use { it.moveToFirst(); it.getInt(0) != 0 }
        if (exists) return
        val now = System.currentTimeMillis()
        sql.execSQL(
            "INSERT INTO conversations(id,kind,pinned,archived,created_at,updated_at,message_count,next_seq) " +
                "SELECT ?,?,0,0,?,?,0,1 WHERE NOT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
            arrayOf(conversationId, kind, now, now, conversationId),
        )
        val created = sql.query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id=?)", arrayOf(conversationId))
            .use { it.moveToFirst(); it.getInt(0) != 0 }
        if (!created) throw M1bAbort("conversation_not_found")
    }

    protected fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    protected fun nowMillis(): Long = System.currentTimeMillis()

    protected class M1bAbort(val reason: String) : RuntimeException(reason)
}

/** Kind used when a non-message ledger row is the first durable write for a session. */
internal fun conversationKindForSession(conversationId: String): String = when {
    WorkspaceIdentity.isCentral(conversationId) -> "central"
    WorkspaceIdentity.isExecutionSession(conversationId) -> "workspace_exec"
    else -> "chat"
}
