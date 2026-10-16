package com.jarvys.agent.chatdb

/** Shared production FTS5 schema/query contract, also exercised on sqlite-bundled in unit tests. */
internal object PersonalMemoryFtsSql {
    const val CREATE_INDEX =
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(text, content='memory_entries', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2')"
    const val INSERT_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS trg_memory_fts_insert AFTER INSERT ON memory_entries BEGIN " +
            "INSERT INTO memory_fts(rowid,text) VALUES(NEW.rowid,NEW.text); END"
    const val DELETE_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS trg_memory_fts_delete AFTER DELETE ON memory_entries BEGIN " +
            "INSERT INTO memory_fts(memory_fts,rowid,text) VALUES('delete',OLD.rowid,OLD.text); END"
    const val UPDATE_TRIGGER =
        "CREATE TRIGGER IF NOT EXISTS trg_memory_fts_update AFTER UPDATE OF text ON memory_entries BEGIN " +
            "INSERT INTO memory_fts(memory_fts,rowid,text) VALUES('delete',OLD.rowid,OLD.text); " +
            "INSERT INTO memory_fts(rowid,text) VALUES(NEW.rowid,NEW.text); END"
    const val SEARCH =
        "SELECT m.id,m.text,m.scope,m.layer,bm25(memory_fts) AS score FROM memory_fts " +
            "JOIN memory_entries m ON m.rowid=memory_fts.rowid " +
            "WHERE memory_fts MATCH ? AND m.deleted_at IS NULL AND m.scope='user' " +
            "ORDER BY score,m.updated_at DESC LIMIT ?"

    fun matchExpression(query: String): String? {
        val terms = query.split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter(String::isNotBlank)
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "\"$it\"*" }
    }
}
