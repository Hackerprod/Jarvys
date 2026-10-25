package com.jarvys.agent.chatdb

/** A retained artifact that no episode evidence depends on, so storage pressure may remove it. */
data class ExpellableArtifact(val artifactId: String, val path: String, val bytes: Long)

/** What Settings > Storage shows about the conversation's tool-output artifacts. */
data class RetentionSummary(
    val retained: Int,
    val bounded: Int,
    val expelled: Int,
    val unavailable: Int,
    val retainedBytes: Long,
    /** Artifacts that belong to the closure of an episode's evidence and are never expelled. */
    val protectedCount: Int,
    /** Set when a new artifact could not be kept and nothing was left to expel. */
    val storageWarning: Boolean,
)

internal object RetentionSql {
    /**
     * Artifact ids of the protected closure for one conversation (bind: conversation id). Every memory
     * entry's tool evidence protects the result and argument artifacts of its call, the text artifact of the
     * batch that made it and, once that batch was summarized, the compaction artifact that covers it.
     */
    const val PROTECTED_IDS =
        "WITH p AS (SELECT DISTINCT e.ref_conversation_id AS conv, COALESCE(c.batch_id,e.ref_id) AS batch, c.call_id AS call " +
            "FROM memory_evidence e " +
            "LEFT JOIN tool_calls c ON c.conversation_id=e.ref_conversation_id AND c.call_id=e.ref_id " +
            "WHERE e.ref_conversation_id=? AND e.ref_kind IN ('tool_call','tool_result')) " +
            "SELECT r.artifact_id FROM transcript_artifact_refs r JOIN p ON r.conversation_id=p.conv AND r.batch_id=p.batch " +
            "AND (r.call_id IS NULL OR p.call IS NULL OR r.call_id=p.call) WHERE r.owner_kind<>'compaction' " +
            "UNION " +
            "SELECT r.artifact_id FROM transcript_artifact_refs r " +
            "JOIN compactions k ON k.conversation_id=r.conversation_id AND k.compaction_id=r.batch_id " +
            "WHERE r.owner_kind='compaction' AND EXISTS(SELECT 1 FROM p JOIN tool_batches b " +
            "ON b.conversation_id=p.conv AND b.batch_id=p.batch WHERE p.conv=r.conversation_id AND b.message_index<k.first_kept " +
            "AND NOT EXISTS(SELECT 1 FROM compactions earlier WHERE earlier.conversation_id=k.conversation_id " +
            "AND earlier.first_kept>b.message_index AND (earlier.first_kept<k.first_kept OR " +
            "(earlier.first_kept=k.first_kept AND earlier.seq>k.seq))))"
}

/** Retention states and storage policy of the conversation's tool-output artifacts (SINGLE_SESSION 7.10). */
internal class ArtifactRetentionRepository internal constructor(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql

    /** Ids of the artifacts that evidence still depends on; none of them may be expelled. */
    suspend fun protectedArtifactIds(conversationId: String): Set<String> = database.withChatReadTransaction {
        sql.query(RetentionSql.PROTECTED_IDS, arrayOf(conversationId)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    /** The oldest artifact on disk that is outside the protected closure, or null when nothing may be expelled. */
    suspend fun nextExpellable(conversationId: String): ExpellableArtifact? = database.withChatReadTransaction {
        sql.query(
            "SELECT a.artifact_id,a.path,a.bytes FROM model_artifacts a WHERE a.conversation_id=? AND a.state IN ('retained','bounded') " +
                "AND a.artifact_id NOT IN (${RetentionSql.PROTECTED_IDS}) ORDER BY a.seq LIMIT 1",
            arrayOf(conversationId, conversationId),
        ).use { c -> if (c.moveToFirst()) ExpellableArtifact(c.getString(0), c.getString(1), c.getLong(2)) else null }
    }

    /**
     * Marks one artifact expelled. The protected closure is evaluated inside the same statement, so evidence
     * that appeared after [nextExpellable] still wins; returns false when the artifact is protected or gone.
     */
    suspend fun expel(conversationId: String, artifactId: String, reason: String): Boolean = database.withChatWriteTransaction {
        sql.execSQL(
            "UPDATE model_artifacts SET state='expelled',state_reason=? WHERE conversation_id=? AND artifact_id=? " +
                "AND state IN ('retained','bounded') AND artifact_id NOT IN (${RetentionSql.PROTECTED_IDS})",
            arrayOf(reason, conversationId, artifactId, conversationId),
        )
        sql.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) == 1 }
    }

    suspend fun summary(conversationId: String): RetentionSummary = database.withChatReadTransaction {
        val counts = HashMap<String, Int>()
        var retainedBytes = 0L
        sql.query(
            "SELECT state,COUNT(*),COALESCE(SUM(bytes),0) FROM model_artifacts WHERE conversation_id=? GROUP BY state",
            arrayOf(conversationId),
        ).use { c ->
            while (c.moveToNext()) {
                counts[c.getString(0)] = c.getInt(1)
                if (ModelArtifactState.hasContent(c.getString(0))) retainedBytes += c.getLong(2)
            }
        }
        val protectedCount = sql.query(RetentionSql.PROTECTED_IDS, arrayOf(conversationId)).use { c ->
            var count = 0
            while (c.moveToNext()) count++
            count
        }
        RetentionSummary(
            retained = counts[ModelArtifactState.RETAINED] ?: 0, bounded = counts[ModelArtifactState.BOUNDED] ?: 0,
            expelled = counts[ModelArtifactState.EXPELLED] ?: 0, unavailable = counts[ModelArtifactState.UNAVAILABLE] ?: 0,
            retainedBytes = retainedBytes, protectedCount = protectedCount, storageWarning = storageWarningLocked(),
        )
    }

    suspend fun storageWarning(): Boolean = database.withChatReadTransaction { storageWarningLocked() }

    suspend fun setStorageWarning(active: Boolean) {
        database.withChatWriteTransaction {
            if (active) {
                sql.execSQL("INSERT OR IGNORE INTO storage_meta(key,value) VALUES(?,?)", arrayOf(WARNING_KEY, "1"))
                sql.execSQL("UPDATE storage_meta SET value='1' WHERE key=?", arrayOf(WARNING_KEY))
            } else sql.execSQL("DELETE FROM storage_meta WHERE key=?", arrayOf(WARNING_KEY))
        }
    }

    private fun storageWarningLocked(): Boolean =
        sql.query("SELECT 1 FROM storage_meta WHERE key=? AND value='1'", arrayOf(WARNING_KEY)).use { it.moveToFirst() }

    private companion object {
        const val WARNING_KEY = "storage_low_warning"
    }
}
