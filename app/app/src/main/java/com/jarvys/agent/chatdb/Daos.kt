package com.jarvys.agent.chatdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Foundational conversation operations. Plain inserts abort on duplicates and cannot clear tombstones. */
@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: Conversation): Long

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Conversation?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** A tombstone is monotonic; an existing deleted row is never changed back to live. */
    @Query("UPDATE conversations SET deleted_at = :deletedAt WHERE id = :id AND deleted_at IS NULL")
    suspend fun tombstone(id: String, deletedAt: Long): Int
}

/** Sequence-keyed timeline spine queries for newest and strictly older pages. */
@Dao
interface ConversationItemDao {
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
interface MessageDao {
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


