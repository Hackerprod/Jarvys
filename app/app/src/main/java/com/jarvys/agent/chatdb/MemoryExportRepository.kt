package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryExportOptions
import java.io.OutputStream
import java.security.MessageDigest

internal data class MemoryExportSnapshot(val options: MemoryExportOptions, val exportedAt: String,
    val projects: List<String>, val owners: List<String>, val generation: String?, val database: JarvysChatDatabase,
    val sha256: String, val sizeBytes: Long, val counts: Map<String, Long>, val unboundObservations: Long,
    val requireOriginalFile: () -> Unit, val supportingSessionProjects: List<String>, val supportingSessions: Long, val unresolvedRequiredReferences: Long)

/** Export projection only; no save/upsert, source proof, session registration or database writes. */
internal class MemoryExportRepository(private val database: JarvysChatDatabase, private val context: android.content.Context) {
    private val sql get() = database.chatSql
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
        val ownerLiterals = owners.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        requireEmpty("SELECT 1 FROM memory_entries e WHERE ${scope("e")} AND e.memory_session_id IS NOT NULL AND NOT EXISTS(" +
            "SELECT 1 FROM memory_sessions s WHERE s.id=e.memory_session_id AND s.project IN ($ownerLiterals) AND " +
            "(s.ownership_mode='shared' OR (s.ownership_mode='project_owned' AND s.project=e.project))) LIMIT 1")
        if(options.includePrompts) requireEmpty("SELECT 1 FROM memory_prompts e WHERE ${scope("e")} AND NOT EXISTS(" +
            "SELECT 1 FROM memory_sessions s WHERE s.id=e.session_id AND s.project=e.project AND ${scope("s")} AND s.ownership_mode IN ('shared','project_owned')) LIMIT 1")
        val supportWhere = "project NOT IN ($literals) AND id IN (SELECT memory_session_id FROM memory_entries WHERE ${scope()} AND memory_session_id IS NOT NULL)"
        val sessionWhere = "(${scope()}) OR ($supportWhere)"
        val supportingProjects = sql.query("SELECT DISTINCT project FROM memory_sessions WHERE $supportWhere ORDER BY project").use { c ->
            buildList { while(c.moveToNext()) { check(c.getType(0)==Cursor.FIELD_TYPE_STRING); add(c.getString(0)) } }
        }
        val supportingCount = sql.query("SELECT COUNT(*) FROM memory_sessions WHERE $supportWhere").use { c -> check(c.moveToFirst());c.getLong(0) }
        fun relationScope(alias:String=""):String {
            val prefix=if(alias.isEmpty()) "" else "$alias."
            return "${prefix}source_id IN (SELECT id FROM memory_entries WHERE ${scope()}) AND ${prefix}target_id IN (SELECT id FROM memory_entries WHERE ${scope()})"
        }
        requireEmpty("SELECT 1 FROM memory_relations WHERE ${relationScope()} AND superseded_by_relation_id IS NOT NULL AND portable_superseded_by_relation_sync_id IS NULL LIMIT 1")
        // Count only source references absent from this file's emitted set; never resolve a foreign target.
        val unresolved = sql.query("SELECT COUNT(*) FROM memory_relations r WHERE ${relationScope("r")} AND r.portable_superseded_by_relation_sync_id IS NOT NULL AND NOT EXISTS(" +
            "SELECT 1 FROM memory_relations included WHERE ${relationScope("included")} AND COALESCE(included.portable_sync_id,included.relation_id)=r.portable_superseded_by_relation_sync_id)").use { c -> check(c.moveToFirst());c.getLong(0) }
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
                val remainder=checkNotNull(json.integer(t,r,name+"_submillis_nanos")) { "memory_export_required_null" }
                check(remainder in 0L..999999L) { "memory_export_timestamp_invalid" }
                json.string(MemoryPortableTime.format(millis,remainder.toInt()))
            })
            fun number(t: String, r: Long, name: String) = field(name, { json.raw(checkNotNull(json.integer(t, r, name)).toString()) })
            json.raw("{"); json.key("version"); json.string("0.2.0"); field("exported_at", { json.string(exportedAt) }); json.raw(",")
            rows("sessions", "memory_sessions", sessionWhere, "started_at,id") { r ->
                val t = "memory_sessions"; json.raw("{"); text(t,r,"id",first=true); text(t,r,"project"); text(t,r,"ownership_mode")
                text(t,r,"directory"); time(t,r,"started_at"); time(t,r,"ended_at",true); text(t,r,"summary",nullable=true); json.raw("}")
            }
            json.raw(",")
            rows("observations", "memory_entries", scope(), "rowid") { r ->
                val t="memory_entries"; json.raw("{"); field("id",{check(r>0);json.raw(r.toString())},true); text(t,r,"sync_id","COALESCE(portable_sync_id,id)")
                text(t,r,"session_id","memory_session_id",empty=true); text(t,r,"type","observation_type")
                text(t,r,"title",raw=true,empty=true); text(t,r,"content","text"); text(t,r,"tool_name",nullable=true)
                text(t,r,"project"); text(t,r,"scope"); text(t,r,"topic_key",nullable=true,raw=true)
                number(t,r,"revision_count"); number(t,r,"duplicate_count"); time(t,r,"last_seen_at",true)
                text(t,r,"review_after",nullable=true); text(t,r,"superseded_by_sync_id","COALESCE(portable_superseded_by_sync_id,superseded_by_id)",nullable=true)
                field("pinned", { val pin=checkNotNull(json.integer(t,r,"pinned")); check(pin==0L || pin==1L); json.raw(if(pin==1L) "true" else "false") })
                time(t,r,"created_at"); time(t,r,"updated_at"); time(t,r,"deleted_at",true); json.raw("}")
            }
            json.raw(",")
            rows("prompts","memory_prompts",scope(),"rowid",options.includePrompts) { r ->
                val t="memory_prompts";json.raw("{");field("id",{check(r>0);json.raw(r.toString())},true);text(t,r,"sync_id","COALESCE(portable_sync_id,id)")
                text(t,r,"session_id");text(t,r,"content");text(t,r,"project");time(t,r,"created_at");text(t,r,"source_inbox_id",nullable=true);json.raw("}")
            }
            json.raw(",")
            // Both endpoint rows are the sole authority; audit session/lineage remain descriptive scalars.
            rows("relations","memory_relations", relationScope(), "created_at,relation_id") { r ->
                val t="memory_relations";json.raw("{");text(t,r,"sync_id","COALESCE(portable_sync_id,relation_id)",first=true)
                text(t,r,"source_id","(SELECT COALESCE(e.portable_sync_id,e.id) FROM memory_entries e WHERE e.id=memory_relations.source_id)");text(t,r,"target_id","(SELECT COALESCE(e.portable_sync_id,e.id) FROM memory_entries e WHERE e.id=memory_relations.target_id)");text(t,r,"relation");text(t,r,"reason",nullable=true);text(t,r,"evidence",nullable=true)
                field("confidence",{json.raw(json.real(t,r,"confidence")?.toString()?:"null")});text(t,r,"judgment_status","status")
                for (name in listOf("marked_by_actor","marked_by_kind","marked_by_model")) text(t,r,name,nullable=true)
                text(t,r,"session_id","memory_session_id",nullable=true);time(t,r,"created_at");time(t,r,"updated_at");time(t,r,"superseded_at",true);text(t,r,"superseded_by_relation_sync_id","portable_superseded_by_relation_sync_id",nullable=true);json.raw("}")
            }
            json.raw(",")
            rows("prompt_tombstones","memory_prompt_tombstones",scope(),"prompt_id,session_id,source_inbox_id",options.includePrompts) { r ->
                val t="memory_prompt_tombstones";json.raw("{");text(t,r,"sync_id","COALESCE(portable_sync_id,prompt_id)",first=true)
                text(t,r,"session_id",nullable=true);text(t,r,"project");text(t,r,"source_inbox_id",nullable=true);time(t,r,"deleted_at");json.raw("}")
            }
            json.raw(",");json.key("jarvys_extension");json.raw("{");json.key("version");json.raw("1,");json.key("selection");json.raw("{");json.key("projects");json.raw("[")
            selected.forEachIndexed { index, project -> if(index>0)json.raw(",");json.string(project) };json.raw("],");json.key("include_prompts");json.raw(options.includePrompts.toString());json.raw(",");json.key("supporting_session_projects");json.raw("[")
            supportingProjects.forEachIndexed { index, project -> if(index>0)json.raw(",");json.string(project) }
            json.raw("],");json.key("supporting_sessions");json.raw(supportingCount.toString());json.raw("},")
            rows("observation_fields","memory_entries",scope(),"id") { r ->
                json.raw("{");text("memory_entries",r,"sync_id","COALESCE(portable_sync_id,id)",first=true)
                field("title_bytes_base64",{json.base64("memory_entries",r,"title")});field("topic_key_bytes_base64",{json.base64("memory_entries",r,"topic_key")})
                text("memory_entries",r,"session_id","memory_session_id",nullable=true);json.raw("}")
            }
            counts.remove("observation_fields");json.raw(",")
            rows("deleted_observations","memory_deleted_observations",scope(),"project,old_id") { r ->
                val t="memory_deleted_observations";json.raw("{");text(t,r,"id","COALESCE(portable_sync_id,old_id)",first=true);text(t,r,"project");text(t,r,"scope");time(t,r,"deleted_at");json.raw("}")
            }
            json.raw(",")
            rows("deleted_sessions","memory_session_tombstones",scope(),"project,id") { r ->
                val t="memory_session_tombstones";json.raw("{");text(t,r,"id",first=true);text(t,r,"project");time(t,r,"deleted_at");json.raw("}")
            }
            json.raw("}}");json.flush();token.throwIfCancelled()
        }
        token.throwIfCancelled()
        requireCurrentFile()
        MemoryExportSnapshot(options,exportedAt,selected,owners,generation,database,digest.digest().joinToString(""){"%02x".format(it)},byteCount,counts.toMap(),unbound,requireCurrentFile,supportingProjects,supportingCount,unresolved)
    }
}
