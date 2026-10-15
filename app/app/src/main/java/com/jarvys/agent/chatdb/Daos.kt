package com.jarvys.agent.chatdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Conversation writes preserve tombstones even though deleting the conversation cascades its children. */
@Dao
internal interface ConversationDao {
    /** SOLO para el repositorio (M1); callers must prefer [insertIfNotTombstoned]. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversationRow(conversation: Conversation): Long

    @Query(
        "INSERT INTO tombstones(scope,entity_id,deleted_at,payload) " +
            "SELECT :scope,:id,:deletedAt,:payload " +
            "WHERE NOT EXISTS (SELECT 1 FROM tombstones WHERE scope=:scope AND entity_id=:id)",
    )
    suspend fun insertTombstoneIfAbsent(scope: String, id: String, deletedAt: Long, payload: String? = null): Int

    @Query("SELECT EXISTS(SELECT 1 FROM tombstones WHERE scope = 'conversation' AND entity_id = :id)")
    suspend fun isTombstoned(id: String): Boolean

    /** Must be the only insertion path; transaction closes the check/insert race with deletion. */
    @Transaction
    suspend fun insertIfNotTombstoned(conversation: Conversation): Long {
        check(!isTombstoned(conversation.id)) { "conversation_tombstoned: ${conversation.id}" }
        return insertConversationRow(conversation)
    }

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Conversation?

    /** SOLO para el repositorio (M1); deletion must go through [deleteById] to keep the tombstone transaction. */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationRow(id: String): Int

    /** Deletes all conversation rows by CASCADE and preserves the permanent tombstone atomically. */
    @Transaction
    suspend fun deleteById(id: String, deletedAt: Long = System.currentTimeMillis()): Int {
        insertTombstoneIfAbsent("conversation", id, deletedAt)
        return deleteConversationRow(id)
    }

    /** Compatibility name; marking deleted always removes the content in the same transaction. */
    suspend fun tombstone(id: String, deletedAt: Long): Int = deleteById(id, deletedAt)
}

/** Sequence-keyed timeline spine queries for newest and strictly older pages. */
@Dao
internal interface ConversationItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ConversationItem): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<ConversationItem>): List<Long>

    @Query("SELECT * FROM conversation_items WHERE conversation_id = :conversationId ORDER BY seq DESC LIMIT :limit")
    suspend fun queryTail(conversationId: String, limit: Int): List<ConversationItem>

    @Query("SELECT * FROM conversation_items WHERE conversation_id = :conversationId AND seq < :beforeSeq ORDER BY seq DESC LIMIT :limit")
    suspend fun queryBefore(conversationId: String, beforeSeq: Long, limit: Int): List<ConversationItem>
}

/** Minimal typed message writes/lookups and count for the M0 schema contract. */
@Dao
internal interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: Message): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(messages: List<Message>): List<Long>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND message_id = :messageId LIMIT 1")
    suspend fun getById(conversationId: String, messageId: String): Message?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND stable_id = :stableId LIMIT 1")
    suspend fun getByStableId(conversationId: String, stableId: String): Message?

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun count(conversationId: String): Long
}


