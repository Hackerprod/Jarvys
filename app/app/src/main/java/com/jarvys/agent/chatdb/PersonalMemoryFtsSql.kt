package com.jarvys.agent.chatdb

import android.content.Context
import android.database.Cursor
import com.jarvys.agent.AgentErrorReporter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** SQL for each optional memory-search implementation. Never let an FTS failure block chat DB open. */
internal object PersonalMemoryFtsSql {
    const val FTS5_CREATE =
        "CREATE VIRTUAL TABLE memory_fts USING fts5(title,text,tool_name,observation_type,project,topic_key, content='memory_entries', content_rowid='rowid', tokenize='trigram')"
    const val FTS5_INSERT_TRIGGER =
        "CREATE TRIGGER trg_memory_fts_insert AFTER INSERT ON memory_entries WHEN NEW.deleted_at IS NULL BEGIN " +
            "INSERT INTO memory_fts(rowid,title,text,tool_name,observation_type,project,topic_key) SELECT NEW.rowid,NEW.title,NEW.text,NEW.tool_name,NEW.observation_type,NEW.project,NEW.topic_key WHERE NEW.deleted_at IS NULL; END"
    const val FTS5_DELETE_TRIGGER =
        "CREATE TRIGGER trg_memory_fts_delete AFTER DELETE ON memory_entries WHEN OLD.deleted_at IS NULL BEGIN " +
            "INSERT INTO memory_fts(memory_fts,rowid,title,text,tool_name,observation_type,project,topic_key) VALUES('delete',OLD.rowid,OLD.title,OLD.text,OLD.tool_name,OLD.observation_type,OLD.project,OLD.topic_key); END"
    const val FTS5_UPDATE_TRIGGER =
        "CREATE TRIGGER trg_memory_fts_update AFTER UPDATE OF title,text,tool_name,observation_type,project,topic_key,deleted_at ON memory_entries BEGIN " +
            "INSERT INTO memory_fts(memory_fts,rowid,title,text,tool_name,observation_type,project,topic_key) SELECT 'delete',OLD.rowid,OLD.title,OLD.text,OLD.tool_name,OLD.observation_type,OLD.project,OLD.topic_key WHERE OLD.deleted_at IS NULL; " +
            "INSERT INTO memory_fts(rowid,title,text,tool_name,observation_type,project,topic_key) SELECT NEW.rowid,NEW.title,NEW.text,NEW.tool_name,NEW.observation_type,NEW.project,NEW.topic_key WHERE NEW.deleted_at IS NULL; END"
    const val FTS5_REBUILD = "INSERT INTO memory_fts(rowid,title,text,tool_name,observation_type,project,topic_key) SELECT rowid,title,text,tool_name,observation_type,project,topic_key FROM memory_entries WHERE deleted_at IS NULL"

    const val FTS4_CREATE = "CREATE VIRTUAL TABLE memory_fts USING fts4(title, text, topic_key, tokenize=unicode61)"
    const val FTS4_INSERT_TRIGGER =
        "CREATE TRIGGER trg_memory_fts_insert AFTER INSERT ON memory_entries WHEN NEW.deleted_at IS NULL BEGIN " +
            "INSERT INTO memory_fts(docid,title,text,topic_key) SELECT NEW.rowid,NEW.title,NEW.text,NEW.topic_key WHERE NEW.deleted_at IS NULL; END"
    const val FTS4_DELETE_TRIGGER =
        "CREATE TRIGGER trg_memory_fts_delete AFTER DELETE ON memory_entries WHEN OLD.deleted_at IS NULL BEGIN " +
            "DELETE FROM memory_fts WHERE docid=OLD.rowid; END"
    const val FTS4_UPDATE_TRIGGER =
        "CREATE TRIGGER trg_memory_fts_update AFTER UPDATE OF title,text,topic_key,deleted_at ON memory_entries BEGIN " +
            "DELETE FROM memory_fts WHERE docid=OLD.rowid AND OLD.deleted_at IS NULL; " +
            "INSERT INTO memory_fts(docid,title,text,topic_key) SELECT NEW.rowid,NEW.title,NEW.text,NEW.topic_key WHERE NEW.deleted_at IS NULL; END"
    const val FTS4_REBUILD = "INSERT INTO memory_fts(docid,title,text,topic_key) SELECT rowid,title,text,topic_key FROM memory_entries WHERE deleted_at IS NULL"

    /** bm25 column weights: a title or topic key match outranks the same words in a long body. */
    private const val FTS5_WEIGHTS = "5.0,1.0,0.0,0.0,0.0,3.0"

    /**
     * Ranked search; [filters] is an `AND ...` suffix over alias `m` with its own bind arguments, appended after
     * the MATCH argument and before the LIMIT argument.
     */
    fun fts5Search(filters: String = ""): String =
        "SELECT m.id,m.updated_at,bm25(memory_fts,$FTS5_WEIGHTS) AS score FROM memory_fts " +
            "JOIN memory_entries m ON m.rowid=memory_fts.rowid " +
            "WHERE memory_fts MATCH ? AND m.deleted_at IS NULL $filters " +
            "ORDER BY score,m.updated_at DESC LIMIT ?"

    fun fts4Search(filters: String = ""): String =
        "SELECT m.id,m.updated_at,matchinfo(memory_fts,'pcx') FROM memory_fts " +
            "JOIN memory_entries m ON m.rowid=memory_fts.docid " +
            "WHERE memory_fts MATCH ? AND m.deleted_at IS NULL $filters"

    val FTS5_SEARCH = fts5Search()
    val FTS4_SEARCH = fts4Search()

    fun matchExpression(query: String): String? = terms(query).takeIf(List<String>::isNotEmpty)
        ?.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"*" }

    fun exactMatchExpression(query: String): String? = terms(query).takeIf(List<String>::isNotEmpty)
        ?.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }

    fun terms(query: String): List<String> = query.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank).distinctBy { it.lowercase() }

    /** FTS4 matchinfo('pcx') rank: term frequency weighted by inverse document frequency. */
    fun fts4Score(blob: ByteArray, documentCount: Int): Double {
        if (blob.size < 8 || blob.size % Int.SIZE_BYTES != 0) return 0.0
        val values = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val phrases = values.int
        val columns = values.int
        if (phrases <= 0 || columns <= 0 || blob.size < 8 + phrases * columns * 3 * Int.SIZE_BYTES) return 0.0
        var score = 0.0
        repeat(phrases * columns) {
            val documentFrequency = values.int
            val thisDocumentCount = values.int
            values.int // corpus-wide occurrence count
            if (thisDocumentCount > 0) {
                val idf = kotlin.math.ln((documentCount + 1.0) / (documentFrequency + 1.0))
                score += thisDocumentCount * (1.0 + idf)
            }
        }
        return score
    }
}

internal enum class PersonalMemorySearchMode(val storedValue: String) {
    FTS5("fts5"), FTS4("fts4"), LIKE("like");

    companion object {
        fun from(value: String?): PersonalMemorySearchMode = entries.firstOrNull { it.storedValue == value } ?: LIKE
    }
}

/** Installs and migrates the optional search index independently of the critical chat schema. */
internal object PersonalMemorySearchIndex {
    private const val STATE_DOC_KIND = "memory_search_capability"
    private const val TEST_FTS5 = "__jarvys_memory_fts5_probe"
    private const val TEST_FTS4 = "__jarvys_memory_fts4_probe"
    private val failureReported = AtomicBoolean(false)

    @Volatile internal var capabilityOverrideForTests: PersonalMemorySearchMode? = null

    fun install(db: ChatSqlDatabase, context: Context?, reportFailures: Boolean = true) {
        try {
            val previous = readMode(db)
            val wanted = capabilityOverrideForTests ?: detect(db)
            if (hasStoredMode(db) && previous == wanted && indexExists(db, wanted)) return
            rebuild(db, wanted)
            saveMode(db, wanted)
        } catch (failure: Throwable) {
            runCatching { clearIndex(db) }
            runCatching { saveMode(db, PersonalMemorySearchMode.LIKE) }
            if (reportFailures && failureReported.compareAndSet(false, true)) {
                runCatching { AgentErrorReporter.reportChatDatabaseFailure(context, failure) }
            }
        }
    }

    /** Existing auxiliary indexes are not silently upgraded or accepted by name alone. */
    fun requireCanonical(db: ChatSqlDatabase) {
        check(readMode(db) == PersonalMemorySearchMode.FTS5) { "memory_search_index_unavailable" }
        val expected = mapOf(
            "memory_fts" to PersonalMemoryFtsSql.FTS5_CREATE,
            "trg_memory_fts_insert" to PersonalMemoryFtsSql.FTS5_INSERT_TRIGGER,
            "trg_memory_fts_delete" to PersonalMemoryFtsSql.FTS5_DELETE_TRIGGER,
            "trg_memory_fts_update" to PersonalMemoryFtsSql.FTS5_UPDATE_TRIGGER,
        )
        expected.forEach { (name, definition) ->
            val actual = db.query("SELECT sql FROM sqlite_master WHERE name=?", arrayOf(name)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            check(actual == definition) { "memory_search_index_unavailable" }
        }
    }

    fun currentMode(db: ChatSqlDatabase): PersonalMemorySearchMode =
        runCatching { readMode(db) }.getOrDefault(PersonalMemorySearchMode.LIKE)

    private fun detect(db: ChatSqlDatabase): PersonalMemorySearchMode {
        if (probe(db, TEST_FTS5, "fts5(text, tokenize='trigram')")) return PersonalMemorySearchMode.FTS5
        if (probe(db, TEST_FTS4, "fts4(text, tokenize=unicode61)")) return PersonalMemorySearchMode.FTS4
        return PersonalMemorySearchMode.LIKE
    }

    private fun probe(db: ChatSqlDatabase, table: String, module: String): Boolean = try {
        db.execSQL("DROP TABLE IF EXISTS temp.$table")
        db.execSQL("CREATE VIRTUAL TABLE temp.$table USING $module")
        db.execSQL("DROP TABLE temp.$table")
        true
    } catch (_: Throwable) {
        runCatching { db.execSQL("DROP TABLE IF EXISTS temp.$table") }
        false
    }

    private fun rebuild(db: ChatSqlDatabase, mode: PersonalMemorySearchMode) {
        clearIndex(db)
        when (mode) {
            PersonalMemorySearchMode.FTS5 -> {
                db.execSQL(PersonalMemoryFtsSql.FTS5_CREATE)
                db.execSQL(PersonalMemoryFtsSql.FTS5_INSERT_TRIGGER)
                db.execSQL(PersonalMemoryFtsSql.FTS5_DELETE_TRIGGER)
                db.execSQL(PersonalMemoryFtsSql.FTS5_UPDATE_TRIGGER)
                db.execSQL(PersonalMemoryFtsSql.FTS5_REBUILD)
            }
            PersonalMemorySearchMode.FTS4 -> {
                db.execSQL(PersonalMemoryFtsSql.FTS4_CREATE)
                db.execSQL(PersonalMemoryFtsSql.FTS4_INSERT_TRIGGER)
                db.execSQL(PersonalMemoryFtsSql.FTS4_DELETE_TRIGGER)
                db.execSQL(PersonalMemoryFtsSql.FTS4_UPDATE_TRIGGER)
                db.execSQL(PersonalMemoryFtsSql.FTS4_REBUILD)
            }
            PersonalMemorySearchMode.LIKE -> Unit
        }
    }

    private fun clearIndex(db: ChatSqlDatabase) {
        listOf(
            "DROP TRIGGER IF EXISTS trg_memory_fts_insert",
            "DROP TRIGGER IF EXISTS trg_memory_fts_delete",
            "DROP TRIGGER IF EXISTS trg_memory_fts_update",
            "DROP TABLE IF EXISTS memory_fts",
        ).forEach { statement -> runCatching { db.execSQL(statement) } }
    }

    private fun indexExists(db: ChatSqlDatabase, mode: PersonalMemorySearchMode): Boolean {
        if (mode == PersonalMemorySearchMode.LIKE) return true
        return db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE (type='table' AND name='memory_fts') OR " +
                "(type='trigger' AND name IN ('trg_memory_fts_insert','trg_memory_fts_delete','trg_memory_fts_update'))",
        ).use { it.moveToFirst() && it.getInt(0) == 4 }
    }

    private fun readMode(db: ChatSqlDatabase): PersonalMemorySearchMode =
        db.query("SELECT value FROM storage_meta WHERE key=?", arrayOf(STATE_DOC_KIND)).use {
            if (it.moveToFirst()) PersonalMemorySearchMode.from(it.getString(0)) else PersonalMemorySearchMode.LIKE
        }

    private fun hasStoredMode(db: ChatSqlDatabase): Boolean =
        db.query("SELECT 1 FROM storage_meta WHERE key=? LIMIT 1", arrayOf(STATE_DOC_KIND)).use {
            it.moveToFirst()
        }

    private fun saveMode(db: ChatSqlDatabase, mode: PersonalMemorySearchMode) {
        db.execSQL("INSERT OR IGNORE INTO storage_meta(key,value) VALUES(?,?)",
            arrayOf(STATE_DOC_KIND, mode.storedValue))
        db.execSQL("UPDATE storage_meta SET value=? WHERE key=?", arrayOf(mode.storedValue, STATE_DOC_KIND))
    }
}
