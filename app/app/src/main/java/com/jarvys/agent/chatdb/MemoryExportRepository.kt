package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryExportOptions
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

internal data class MemoryExportSnapshot(val options: MemoryExportOptions, val exportedAt: String,
    val projects: List<String>, val owners: List<String>, val generation: String?, val database: JarvysChatDatabase,
    val sha256: String, val sizeBytes: Long, val counts: Map<String, Long>, val unboundObservations: Long,
    val requireOriginalFile: () -> Unit)

/** Export projection only; no save/upsert, source proof, session registration or database writes. */
internal class MemoryExportRepository(private val database: JarvysChatDatabase, private val context: android.content.Context) {
    private val sql get() = database.chatSql
    private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    suspend fun render(namespace: MemoryNamespace, options: MemoryExportOptions, exportedAt: String,
        token: CancellationToken, openOutput: () -> OutputStream): MemoryExportSnapshot = database.withChatReadTransaction {
        token.throwIfCancelled()
        val requireCurrentFile = JarvysChatDatabaseProvider.requireOpenFileIdentity(database, context)
        val selected = (if (options.project == null) namespace.authorizedProjects() else listOf(namespace.project)).sorted()
        val owners = namespace.authorizedProjects().sorted()
        val literals = selected.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        fun scope(alias: String = "") = (if (alias.isEmpty()) "" else "$alias.") + "project IN ($literals)"
        fun requireEmpty(query: String) { sql.query(query).use { check(!it.moveToFirst()) { "memory_export_reference_invalid" } } }
        requireEmpty("SELECT 1 FROM memory_sessions WHERE ${scope()} AND (typeof(ownership_mode)!='text' OR ownership_mode NOT IN ('shared','project_owned')) LIMIT 1")
        for ((table, reference) in listOf("memory_entries" to "memory_session_id", "memory_prompts" to "session_id")) {
            if (table == "memory_prompts" && !options.includePrompts) continue
            requireEmpty("SELECT 1 FROM $table e WHERE ${scope("e")} AND e.$reference IS NOT NULL AND NOT EXISTS(" +
                "SELECT 1 FROM memory_sessions s WHERE s.id=e.$reference AND s.project=e.project AND ${scope("s")}) LIMIT 1")
        }
        val generationType = sql.query("SELECT typeof(value),length(CAST(value AS BLOB)) FROM storage_meta WHERE key=?", arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { c ->
            if(!c.moveToFirst()) false else {
                check(c.getString(0)=="text" && c.getType(1)==Cursor.FIELD_TYPE_INTEGER && c.getLong(1)==36L) { "memory_export_generation_invalid" };true
            }
        }
        val generation = if(!generationType) null else sql.query("SELECT value FROM storage_meta WHERE key=?", arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { c ->
            check(c.moveToFirst());c.getString(0).also { check(runCatching {java.util.UUID.fromString(it).toString()==it}.getOrDefault(false)) { "memory_export_generation_invalid" } }
        }
        val unbound = sql.query("SELECT COUNT(*) FROM memory_entries WHERE ${scope()} AND memory_session_id IS NULL").use { c ->
            check(c.moveToFirst()); c.getLong(0)
        }
        val counts = linkedMapOf<String, Long>()
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        openOutput().use { destination ->
            val output = object : OutputStream() {
                override fun write(b: Int) { token.throwIfCancelled(); destination.write(b); digest.update(b.toByte()); byteCount = Math.addExact(byteCount, 1L) }
                override fun write(b: ByteArray, off: Int, len: Int) { token.throwIfCancelled(); destination.write(b, off, len); digest.update(b, off, len); byteCount = Math.addExact(byteCount, len.toLong()) }
                override fun flush() = destination.flush()
            }
            val json = MemoryExportJson(output, sql, token)
            fun rows(name: String, table: String, where: String, order: String, enabled: Boolean = true, body: (Long) -> Unit) {
                json.key(name); json.raw("[")
                var offset = 0L; var count = 0L
                if (enabled) while (true) {
                    token.throwIfCancelled()
                    // Fixed-size metadata page, not a materialized dataset or payload cursor.
                    val page = sql.query("SELECT rowid FROM $table WHERE $where ORDER BY $order LIMIT 64 OFFSET ?", arrayOf(offset)).use { c ->
                        buildList { while (c.moveToNext()) { check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER); add(c.getLong(0)) } }
                    }
                    if (page.isEmpty()) break
                    for (row in page) { if (count > 0) json.raw(","); body(row); count = Math.addExact(count, 1L) }
                    offset = Math.addExact(offset, page.size.toLong())
                }
                json.raw("]"); counts[name] = count
            }
            fun field(name: String, block: () -> Unit, first: Boolean = false) { if (!first) json.raw(","); json.key(name); block() }
            fun text(t: String, r: Long, name: String, column: String = name, nullable: Boolean = false, raw: Boolean = false, empty: Boolean = false, first: Boolean = false) =
                field(name, { json.text(t, r, column, nullable, raw, empty) }, first)
            fun time(t: String, r: Long, name: String, nullable: Boolean = false) = field(name, {
                val millis = json.integer(t, r, name); check(nullable || millis != null) { "memory_export_required_null" }
                json.string(millis?.let {
                    check(it in -62167219200000L..253402300799999L) { "memory_export_timestamp_invalid" }
                    timestamp.format(Instant.ofEpochMilli(it))
                })
            })
            fun number(t: String, r: Long, name: String) = field(name, { json.raw(checkNotNull(json.integer(t, r, name)).toString()) })
            json.raw("{"); json.key("version"); json.string("0.2.0"); field("exported_at", { json.string(exportedAt) }); json.raw(",")
            rows("sessions", "memory_sessions", scope(), "started_at,id") { r ->
                val t = "memory_sessions"; json.raw("{"); text(t,r,"id",first=true); text(t,r,"project"); text(t,r,"ownership_mode")
                field("directory", { json.string("") }); time(t,r,"started_at"); time(t,r,"ended_at",true); text(t,r,"summary",nullable=true); json.raw("}")
            }
            json.raw(",")
            rows("observations", "memory_entries", scope(), "rowid") { r ->
                val t="memory_entries"; json.raw("{"); field("id",{check(r>0);json.raw(r.toString())},true); text(t,r,"sync_id","id")
                text(t,r,"session_id","memory_session_id",empty=true); text(t,r,"type","observation_type")
                text(t,r,"title",raw=true,empty=true); text(t,r,"content","text"); text(t,r,"tool_name",nullable=true)
                text(t,r,"project"); text(t,r,"scope"); text(t,r,"topic_key",nullable=true,raw=true)
                number(t,r,"revision_count"); number(t,r,"duplicate_count"); time(t,r,"last_seen_at",true)
                text(t,r,"review_after",nullable=true); text(t,r,"superseded_by_sync_id","superseded_by_id",nullable=true)
                field("pinned", { val pin=checkNotNull(json.integer(t,r,"pinned")); check(pin==0L || pin==1L); json.raw(if(pin==1L) "true" else "false") })
                time(t,r,"created_at"); time(t,r,"updated_at"); time(t,r,"deleted_at",true); json.raw("}")
            }
            json.raw(",")
            rows("prompts","memory_prompts",scope(),"rowid",options.includePrompts) { r ->
                val t="memory_prompts";json.raw("{");field("id",{check(r>0);json.raw(r.toString())},true);text(t,r,"sync_id","id")
                text(t,r,"session_id");text(t,r,"content");text(t,r,"project");time(t,r,"created_at");text(t,r,"source_inbox_id",nullable=true);json.raw("}")
            }
            json.raw(",")
            // Both endpoint rows are the sole authority; audit session/lineage remain descriptive scalars.
            rows("relations","memory_relations", "source_id IN (SELECT id FROM memory_entries WHERE ${scope()}) AND target_id IN (SELECT id FROM memory_entries WHERE ${scope()})", "created_at,relation_id") { r ->
                val t="memory_relations";json.raw("{");text(t,r,"sync_id","relation_id",first=true)
                text(t,r,"source_id");text(t,r,"target_id");text(t,r,"relation");text(t,r,"reason",nullable=true);text(t,r,"evidence",nullable=true)
                field("confidence",{json.raw(json.real(t,r,"confidence")?.toString()?:"null")});text(t,r,"judgment_status","status")
                for (name in listOf("marked_by_actor","marked_by_kind","marked_by_model")) text(t,r,name,nullable=true)
                text(t,r,"session_id","memory_session_id",nullable=true);time(t,r,"created_at");time(t,r,"updated_at");json.raw("}")
            }
            json.raw(",")
            rows("prompt_tombstones","memory_prompt_tombstones",scope(),"prompt_id,session_id,source_inbox_id",options.includePrompts) { r ->
                val t="memory_prompt_tombstones";json.raw("{");text(t,r,"sync_id","prompt_id",first=true)
                text(t,r,"session_id");text(t,r,"project");text(t,r,"source_inbox_id");time(t,r,"deleted_at");json.raw("}")
            }
            json.raw(",");json.key("jarvys_extension");json.raw("{");json.key("version");json.raw("1,");json.key("selection");json.raw("{");json.key("projects");json.raw("[")
            selected.forEachIndexed { index, project -> if(index>0)json.raw(",");json.string(project) };json.raw("],");json.key("include_prompts");json.raw(options.includePrompts.toString());json.raw("},")
            rows("observation_fields","memory_entries",scope(),"id") { r ->
                json.raw("{");text("memory_entries",r,"sync_id","id",first=true)
                field("title_bytes_base64",{json.base64("memory_entries",r,"title")});field("topic_key_bytes_base64",{json.base64("memory_entries",r,"topic_key")})
                text("memory_entries",r,"session_id","memory_session_id",nullable=true);json.raw("}")
            }
            counts.remove("observation_fields");json.raw(",")
            rows("deleted_observations","memory_deleted_observations",scope(),"project,old_id") { r ->
                val t="memory_deleted_observations";json.raw("{");text(t,r,"id","old_id",first=true);text(t,r,"project");text(t,r,"scope");time(t,r,"deleted_at");json.raw("}")
            }
            json.raw(",")
            rows("deleted_sessions","memory_session_tombstones",scope(),"project,id") { r ->
                val t="memory_session_tombstones";json.raw("{");text(t,r,"id",first=true);text(t,r,"project");time(t,r,"deleted_at");json.raw("}")
            }
            json.raw("}}");json.flush();token.throwIfCancelled()
        }
        token.throwIfCancelled()
        requireCurrentFile()
        MemoryExportSnapshot(options,exportedAt,selected,owners,generation,database,digest.digest().joinToString(""){"%02x".format(it)},byteCount,counts.toMap(),unbound,requireCurrentFile)
    }
}
