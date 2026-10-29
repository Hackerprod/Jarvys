package com.jarvys.agent.chatdb

/** Auxiliary current-store prompt index. No capture/backfill of transcript and no legacy search fallback. */
internal object MemoryPromptSearchIndex {
    const val CREATE = "CREATE VIRTUAL TABLE memory_prompts_fts USING fts5(content,project, content='memory_prompts', content_rowid='rowid', tokenize='trigram')"
    const val INSERT = "CREATE TRIGGER trg_memory_prompts_fts_insert AFTER INSERT ON memory_prompts BEGIN INSERT INTO memory_prompts_fts(rowid,content,project) VALUES(NEW.rowid,NEW.content,NEW.project); END"
    const val DELETE = "CREATE TRIGGER trg_memory_prompts_fts_delete AFTER DELETE ON memory_prompts BEGIN INSERT INTO memory_prompts_fts(memory_prompts_fts,rowid,content,project) VALUES('delete',OLD.rowid,OLD.content,OLD.project); END"
    const val UPDATE = "CREATE TRIGGER trg_memory_prompts_fts_update AFTER UPDATE OF content,project ON memory_prompts BEGIN INSERT INTO memory_prompts_fts(memory_prompts_fts,rowid,content,project) VALUES('delete',OLD.rowid,OLD.content,OLD.project); INSERT INTO memory_prompts_fts(rowid,content,project) VALUES(NEW.rowid,NEW.content,NEW.project); END"
    private val definitions = linkedMapOf("memory_prompts_fts" to CREATE,
        "trg_memory_prompts_fts_insert" to INSERT,"trg_memory_prompts_fts_delete" to DELETE,"trg_memory_prompts_fts_update" to UPDATE)

    fun install(db: ChatSqlDatabase) {
        val present=definitions.keys.any { name -> db.query("SELECT 1 FROM sqlite_master WHERE name=?",arrayOf(name)).use { it.moveToFirst() } }
        // Never silently repair/adopt a partial or differently defined preexisting index.
        if(present) return
        db.execSQL("SAVEPOINT memory_prompt_index_install")
        try {
            definitions.values.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO memory_prompts_fts(rowid,content,project) SELECT rowid,content,project FROM memory_prompts")
            db.execSQL("RELEASE SAVEPOINT memory_prompt_index_install")
        } catch(failure: android.database.SQLException) {
            // Optional-index failure must not leave half-installed triggers or block unrelated chat open.
            db.execSQL("ROLLBACK TO SAVEPOINT memory_prompt_index_install")
            db.execSQL("RELEASE SAVEPOINT memory_prompt_index_install")
        }
    }

    fun requireCanonical(db: ChatSqlDatabase) {
        definitions.forEach { (name, expected) ->
            val actual=db.query("SELECT sql FROM sqlite_master WHERE name=?",arrayOf(name)).use { if(it.moveToFirst()) it.getString(0) else null }
            check(actual==expected) { "memory_search_prompts_index_unavailable" }
        }
    }
}
