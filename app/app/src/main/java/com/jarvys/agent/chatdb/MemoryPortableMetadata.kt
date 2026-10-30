package com.jarvys.agent.chatdb

/** Fresh-schema portable metadata only. None of these fields admits a runtime source. */
internal object MemoryPortableMetadata {
    val identities = linkedMapOf("memory_entries" to "id", "memory_prompts" to "id",
        "memory_relations" to "relation_id", "memory_deleted_observations" to "old_id",
        "memory_prompt_tombstones" to "prompt_id")
    val timestamps = linkedMapOf(
        "memory_sessions" to listOf("started_at", "ended_at"),
        "memory_entries" to listOf("created_at", "updated_at", "deleted_at", "last_seen_at"),
        "memory_prompts" to listOf("created_at"),
        "memory_relations" to listOf("created_at", "updated_at", "superseded_at"),
        "memory_prompt_tombstones" to listOf("deleted_at"),
        "memory_deleted_observations" to listOf("deleted_at"),
        "memory_session_tombstones" to listOf("deleted_at"))
    fun install(sql: ChatSqlDatabase) {
        identities.forEach { (table, id) ->
            sql.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_${table}_portable_identity ON $table(COALESCE(portable_sync_id,$id))")
        }
        timestamps.forEach { (table, fields) -> fields.forEach { field ->
            // An explicit local clock assignment replaces precision even if its millisecond is unchanged.
            // Import restores its prepared remainder with a metadata-only UPDATE before readback/commit.
            sql.execSQL("CREATE TRIGGER IF NOT EXISTS trg_${table}_${field}_portable_precision AFTER UPDATE OF $field ON $table " +
                "BEGIN UPDATE $table SET ${field}_submillis_nanos=0 WHERE rowid=NEW.rowid; END")
        } }
    }
}
