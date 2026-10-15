package com.jarvys.agent.chatdb


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
            else -> ChatDbResult.Conflict(failure.reason)
        }
    } catch (failure: android.database.sqlite.SQLiteConstraintException) {
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
        val imported = importedPositionFor(conversationId)
        if (sql.query(
                "SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId),
            ).use { it.moveToFirst(); it.getInt(0) != 0 }) throw M1bAbort("conversation_tombstoned")
        val next = sql.query("SELECT next_seq FROM conversations WHERE id=?", arrayOf(conversationId)).use {
            if (!it.moveToFirst()) throw M1bAbort("conversation_not_found")
            it.getLong(0)
        }
        if (imported != null && next != imported.seq) {
            throw M1bAbort("import_sequence_mismatch:expected=$next,source=${imported.seq}")
        }
        val sequence = imported?.seq ?: next
        val timestamp = imported?.createdAtSeconds ?: createdAtSeconds
        val effectiveSortSeq = imported?.sortSeq ?: sortSeq ?: sequence
        val effectiveSortSub = imported?.sortSub ?: sortSub
        val effectiveTurnAnchor = if (imported == null) turnAnchorSeq else imported.turnAnchorSeq
        sql.execSQL(
            "UPDATE conversations SET next_seq=?,updated_at=? WHERE id=? AND next_seq=?",
            arrayOf(sequence + 1L, imported?.conversationUpdatedAtMillis ?: System.currentTimeMillis(), conversationId, next),
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
            "SELECT seq FROM messages WHERE conversation_id=? AND message_id=? AND role='user' LIMIT 1",
            arrayOf(conversationId, ownerMessageId),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    protected fun ensureConversationExists(conversationId: String) {
        if (sql.query(
                "SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope='conversation' AND entity_id=?)",
                arrayOf(conversationId),
            ).use { it.moveToFirst(); it.getInt(0) != 0 }) throw M1bAbort("conversation_tombstoned")
        val exists = sql.query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id=?)", arrayOf(conversationId))
            .use { it.moveToFirst(); it.getInt(0) != 0 }
        if (!exists) throw M1bAbort("conversation_not_found")
    }

    protected fun nowSeconds(): Double = importedNowSeconds() ?: System.currentTimeMillis() / 1000.0

    protected fun nowMillis(): Long = importedNowMillis() ?: System.currentTimeMillis()

    protected class M1bAbort(val reason: String) : RuntimeException(reason)
}
