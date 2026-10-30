package com.jarvys.agent

import android.database.Cursor
import com.jarvys.agent.chatdb.ChatSqlDatabase
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Caller holds the real Room read/write transaction. Prefixes are candidates only; complete bytes precede owner hydration. */
internal class MemoryImportLookup(private val sql:ChatSqlDatabase,private val data:MemoryImportData,
    private val owners:List<String>,private val token:CancellationToken) {
    enum class Domain(val table:String,val localKey:String,val effectiveID:String) {
        SESSION("memory_sessions","id","id"),
        OBSERVATION("memory_entries","id","COALESCE(portable_sync_id,id)"),
        PROMPT("memory_prompts","id","COALESCE(portable_sync_id,id)"),
        RELATION("memory_relations","relation_id","COALESCE(portable_sync_id,relation_id)"),
        OBSERVATION_MARKER("memory_deleted_observations","old_id","COALESCE(portable_sync_id,old_id)"),
        PROMPT_MARKER("memory_prompt_tombstones","prompt_id","COALESCE(portable_sync_id,prompt_id)"),
        SESSION_MARKER("memory_session_tombstones","id","id")
    }
    data class OwnedRow internal constructor(val rowId:Long,val project:String,val domain:Domain)
    fun find(domain:Domain,cell:MemoryImportCell):OwnedRow? = find(domain.table,domain.effectiveID,cell)?.let { OwnedRow(it,owner(it,domain.table),domain) }
    fun fingerprint(row:OwnedRow):String = fingerprint(row.rowId,row.domain.table)
    fun localUuid(row:OwnedRow):String {
        require(row.domain!=Domain.SESSION && row.domain!=Domain.SESSION_MARKER) { "memory_import_local_id_invalid" }
        owner(row.rowId,row.domain.table);token.throwIfCancelled()
        val column=row.domain.localKey
        return sql.query("SELECT $column FROM ${row.domain.table} WHERE rowid=? AND typeof($column)='text' AND length(CAST($column AS BLOB))=36",arrayOf(row.rowId)).use { c ->
            check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_STRING) { "memory_import_local_id_invalid" }
            c.getString(0).also { value -> check(runCatching { java.util.UUID.fromString(value).toString()==value }.getOrDefault(false)) { "memory_import_local_id_invalid" } }
        }
    }
    fun smallText(row:OwnedRow,column:String,allowed:Set<String>):String? {
        require(column in columns(row.domain.table) && allowed.isNotEmpty() && allowed.all {it.length<=128}) { "memory_import_lookup_column_invalid" }
        owner(row.rowId,row.domain.table);token.throwIfCancelled()
        val slots=allowed.joinToString(",") { "?" }
        return sql.query("SELECT $column FROM ${row.domain.table} WHERE rowid=? AND (typeof($column)='null' OR (typeof($column)='text' AND $column IN ($slots)))",(listOf<Any?>(row.rowId)+allowed).toTypedArray()).use { c ->
            check(c.moveToFirst()) { "memory_import_local_type_invalid" };if(c.isNull(0))null else {check(c.getType(0)==Cursor.FIELD_TYPE_STRING);c.getString(0)}
        }
    }
    companion object {
        private val fields=mapOf(
            "memory_sessions" to ("id project ownership_mode state started_at ended_at source_generation owner_conversation_id owner_message_id run_id process_id runtime_lease_expires_at runtime_root_id resume_event_key summary maintenance_job_id maintenance_lease_owner compaction_parent_session_id mission_execution_session_id mission_evidence_id mission_assignment_id directory started_at_submillis_nanos ended_at_submillis_nanos".split(' ')),
            "memory_entries" to ("id layer day text category importance triggers provenance source_ref usage_count last_used_at created_at updated_at deleted_at kind topic_key title narration event_at source_conversation_id source_message_id through_seq project scope observation_type revision_count normalized_hash duplicate_count last_seen_at pinned memory_session_id tool_name review_after superseded_by_id portable_sync_id portable_superseded_by_sync_id created_at_submillis_nanos updated_at_submillis_nanos deleted_at_submillis_nanos last_seen_at_submillis_nanos".split(' ')),
            "memory_prompts" to "id session_id project content source_inbox_id created_at portable_sync_id created_at_submillis_nanos".split(' '),
            "memory_relations" to "id relation_id source_id target_id project scope relation status created_at updated_at reason evidence confidence marked_by_actor marked_by_kind marked_by_model memory_session_id portable_sync_id superseded_at superseded_by_relation_id portable_superseded_by_relation_sync_id created_at_submillis_nanos updated_at_submillis_nanos superseded_at_submillis_nanos".split(' '),
            "memory_deleted_observations" to "old_id project scope deleted_at producer producer_key portable_sync_id deleted_at_submillis_nanos".split(' '),
            "memory_prompt_tombstones" to "prompt_id session_id source_inbox_id project deleted_at portable_sync_id deleted_at_submillis_nanos".split(' '),
            "memory_session_tombstones" to "id project deleted_at deleted_at_submillis_nanos".split(' '))
        private val identity=mapOf("memory_sessions" to "id","memory_entries" to "id","memory_prompts" to "id",
            "memory_relations" to "relation_id","memory_deleted_observations" to "old_id","memory_prompt_tombstones" to "prompt_id","memory_session_tombstones" to "id")
    }
    private fun columns(table:String):List<String> = requireNotNull(fields[table]) { "memory_import_lookup_table_invalid" }
    private fun expression(table:String,value:String) {
        val allowed=columns(table)
        require(value in allowed || ("portable_sync_id" in allowed && value=="COALESCE(portable_sync_id,${identity.getValue(table)})")) { "memory_import_lookup_column_invalid" }
    }
    private fun find(table:String,idExpression:String,cell:MemoryImportCell):Long? {
        expression(table,idExpression)
        require(idExpression==identity.getValue(table) || idExpression=="COALESCE(portable_sync_id,${identity.getValue(table)})") { "memory_import_lookup_identity_invalid" }
        require(cell.type==MemoryImportWire.STRING) { "memory_import_field_type" }
        token.throwIfCancelled()
        val prefix=ByteArray(minOf(cell.length,64L).toInt())
        data.open(cell).use { readFully(it,prefix,prefix.size) }
        var offset=0L
        while(true) {
            token.throwIfCancelled()
            // SQLite substr(empty BLOB,1,0) is NULL, not an empty BLOB. Type + byte length
            // already select the empty TEXT candidate; equalCell still verifies the complete value.
            val prefixPredicate=if(prefix.isEmpty()) "" else " AND substr(CAST($idExpression AS BLOB),1,?)=?"
            val arguments=if(prefix.isEmpty()) arrayOf<Any?>(cell.length,offset) else arrayOf<Any?>(cell.length,prefix.size,prefix,offset)
            val page=sql.query("SELECT rowid FROM $table WHERE typeof($idExpression)='text' AND length(CAST($idExpression AS BLOB))=?$prefixPredicate ORDER BY rowid LIMIT 64 OFFSET ?",
                arguments).use { c -> buildList {
                    while(c.moveToNext()) {check(c.getType(0)==Cursor.FIELD_TYPE_INTEGER) { "memory_import_local_type_invalid" };add(c.getLong(0))}
                } }
            if(page.isEmpty())return null
            for(row in page) if(equalCell(table,row,idExpression,cell)) {owner(row,table);return row}
            offset=Math.addExact(offset,page.size.toLong())
        }
    }
    /** Only the authorized SQL predicate can return a project; foreign metadata is never loaded. */
    fun owner(row:Long,table:String):String {
        columns(table);token.throwIfCancelled()
        require(owners.isNotEmpty()) { "memory_import_owner_denied" }
        val slots=owners.joinToString(",") { "?" }
        return sql.query("SELECT project FROM $table WHERE rowid=? AND typeof(project)='text' AND project IN ($slots)",
            (listOf<Any?>(row)+owners).toTypedArray()).use { c ->
            require(c.moveToFirst()) { "memory_import_owner_denied" };check(c.getType(0)==Cursor.FIELD_TYPE_STRING) { "memory_import_local_type_invalid" };c.getString(0)
        }
    }
    class ByteValue(val length:Long,val open:()->InputStream) {
        init { require(length>=0) { "memory_import_byte_length_invalid" } }
    }
    fun bytes(cell:MemoryImportCell):ByteValue? = when(cell.type) {
        MemoryImportWire.NULL,MemoryImportWire.MISSING -> null
        MemoryImportWire.STRING -> ByteValue(cell.length) {data.open(cell)}
        else -> throw IllegalArgumentException("memory_import_field_type")
    }
    fun equalCell(table:String,row:Long,column:String,cell:MemoryImportCell):Boolean {
        require(cell.type==MemoryImportWire.STRING) { "memory_import_field_type" }
        return equalBytes(table,row,column,requireNotNull(bytes(cell)))
    }
    /** null is an explicit SQL NULL predicate, never empty bytes or an omitted predicate. */
    fun equalBytes(table:String,row:Long,column:String,value:ByteValue?):Boolean {
        token.throwIfCancelled();expression(table,column)
        if(value==null)return sql.query("SELECT 1 FROM $table WHERE rowid=? AND typeof($column)='null'",arrayOf(row)).use {it.moveToFirst()}
        val size=sql.query("SELECT typeof($column),length(CAST($column AS BLOB)) FROM $table WHERE rowid=?",arrayOf(row)).use { c ->
            if(!c.moveToFirst() || c.getString(0)!="text" || c.getType(1)!=Cursor.FIELD_TYPE_INTEGER)return false
            c.getLong(1)
        }
        if(size!=value.length)return false
        value.open().use { input ->
            var position=0L;val expected=ByteArray(8192)
            while(position<size) {
                token.throwIfCancelled();val count=minOf(size-position,expected.size.toLong()).toInt()
                readFully(input,expected,count)
                // Only a boolean crosses this boundary: candidate bytes remain in SQLite until authorization.
                val matched=sql.query("SELECT 1 FROM $table WHERE rowid=? AND typeof($column)='text' AND length(CAST($column AS BLOB))=? AND substr(CAST($column AS BLOB),?,?)=?",
                    arrayOf(row,size,Math.addExact(position,1L),count,if(count==expected.size)expected else expected.copyOf(count))).use { it.moveToFirst() }
                if(!matched)return false
                position=Math.addExact(position,count.toLong())
            }
            check(input.read()<0) { "memory_import_staging_changed" }
        }
        token.throwIfCancelled();return true
    }
    /** Only the existing topic and prompt-tuple collision domains; this does not grant an import. */
    fun findByFields(domain:Domain,fields:List<Pair<String,ByteValue?>>,fixed:Map<String,String>,visit:(OwnedRow)->Unit) {
        val expected=when(domain) {
            Domain.OBSERVATION -> setOf("topic_key")
            Domain.PROMPT,Domain.PROMPT_MARKER -> setOf("session_id","source_inbox_id")
            else -> error("memory_import_lookup_tuple_invalid")
        }
        require(fields.size==expected.size && fields.map{it.first}.toSet()==expected &&
            fixed.keys==(if(domain==Domain.OBSERVATION)setOf("project","scope") else setOf("project"))) { "memory_import_lookup_tuple_invalid" }
        require(fixed.getValue("project") in owners) { "memory_import_owner_denied" }
        val predicates=ArrayList<String>();val arguments=ArrayList<Any?>()
        if(domain==Domain.OBSERVATION)predicates+="deleted_at IS NULL" // Existing live-topic uniqueness domain only.
        if(domain==Domain.OBSERVATION)fixed.forEach { (name,value)->predicates+="$name=?";arguments+=value }
        // Prompt tuples are globally unique; do not hide a conflicting tuple behind the destination filter.
        for((column,value) in fields) {
            token.throwIfCancelled()
            if(value==null)predicates+="typeof($column)='null'"
            else {
                val prefix=ByteArray(minOf(value.length,64L).toInt());value.open().use{readFully(it,prefix,prefix.size)}
                predicates+="typeof($column)='text' AND length(CAST($column AS BLOB))=?"
                arguments+=value.length
                if(prefix.isNotEmpty()) {
                    predicates+="substr(CAST($column AS BLOB),1,?)=?"
                    arguments+=prefix.size;arguments.add(prefix)
                }
            }
        }
        var offset=0L
        while(true) {
            token.throwIfCancelled()
            val page=sql.query("SELECT rowid FROM ${domain.table} WHERE "+predicates.joinToString(" AND ")+" ORDER BY rowid LIMIT 64 OFFSET ?",
                (arguments+offset).toTypedArray()).use {c->buildList{while(c.moveToNext()){check(c.getType(0)==Cursor.FIELD_TYPE_INTEGER){"memory_import_local_type_invalid"};add(c.getLong(0))}}}
            if(page.isEmpty())return
            for(row in page)if(fields.all{(column,value)->equalBytes(domain.table,row,column,value)}) {
                token.throwIfCancelled();val project=owner(row,domain.table)
                require(project==fixed.getValue("project")) { "memory_import_owner_denied" }
                visit(OwnedRow(row,project,domain));token.throwIfCancelled()
            }
            offset=Math.addExact(offset,page.size.toLong())
        }
    }
    private fun fingerprint(row:Long,table:String,selectedColumns:List<String> = columns(table)):String {
        val allowed=columns(table);require(selectedColumns.isNotEmpty() && selectedColumns.distinct().size==selectedColumns.size && selectedColumns.all {it in allowed}) { "memory_import_lookup_column_invalid" }
        owner(row,table)
        val hash=MessageDigest.getInstance("SHA-256")
        fun long(value:Long) { hash.update(ByteBuffer.allocate(8).putLong(value).array()) }
        fun text(value:String) {val bytes=value.toByteArray(Charsets.UTF_8);long(bytes.size.toLong());hash.update(bytes)}
        text("memory-import-row-v1");text(table);long(row);long(selectedColumns.size.toLong())
        for(column in selectedColumns) {
            token.throwIfCancelled();text(column)
            val type=sql.query("SELECT typeof($column) FROM $table WHERE rowid=?",arrayOf(row)).use { c ->
                check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_STRING) { "memory_import_source_changed" };c.getString(0)
            }
            text(type)
            when(type) {
                "null" -> Unit
                "integer" -> sql.query("SELECT $column FROM $table WHERE rowid=?",arrayOf(row)).use { c ->
                    check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_INTEGER) { "memory_import_source_changed" };long(c.getLong(0)) }
                "real" -> sql.query("SELECT $column FROM $table WHERE rowid=?",arrayOf(row)).use { c ->
                    check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_FLOAT) { "memory_import_source_changed" };val value=c.getDouble(0)
                    check(value.isFinite()) { "memory_import_local_type_invalid" };long(java.lang.Double.doubleToRawLongBits(value)) }
                "text","blob" -> {
                    val size=sql.query("SELECT length(CAST($column AS BLOB)) FROM $table WHERE rowid=?",arrayOf(row)).use { c ->
                        check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_INTEGER) { "memory_import_source_changed" };c.getLong(0).also {check(it>=0)} }
                    long(size);var position=0L
                    while(position<size) {token.throwIfCancelled();val bytes=chunk(table,row,column,position,size);hash.update(bytes);position=Math.addExact(position,bytes.size.toLong())}
                }
                else -> error("memory_import_local_type_invalid")
            }
        }
        token.throwIfCancelled();return hash.digest().joinToString("") { "%02x".format(it.toInt()and 255) }
    }
    private fun chunk(table:String,row:Long,column:String,position:Long,size:Long):ByteArray =
        sql.query("SELECT substr(CAST($column AS BLOB),?,8192) FROM $table WHERE rowid=?",arrayOf(Math.addExact(position,1L),row)).use { c ->
            check(c.moveToFirst() && c.getType(0)==Cursor.FIELD_TYPE_BLOB) { "memory_import_source_changed" }
            c.getBlob(0).also {check(it.isNotEmpty() && it.size<=8192 && it.size.toLong()<=size-position) { "memory_import_source_changed" }}
        }
    private fun readFully(input:InputStream,bytes:ByteArray,count:Int) {
        var position=0
        while(position<count) {token.throwIfCancelled();val n=input.read(bytes,position,count-position);check(n>0) { "memory_import_staging_changed" };position+=n}
    }
}
