package com.jarvys.agent.chatdb

import android.content.Context
import android.database.Cursor
import com.jarvys.agent.CancellationToken
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

class MemorySourceTitleRepairException(val code: String, cause: Throwable? = null) : IllegalStateException(code, cause)

class MemorySourceTitleFinding internal constructor(
    val id: String, missingFields: List<String>, val existingTitle: String?, val existingTitleIsUtf8: Boolean?,
    val proposedTitle: String?, val explanation: String,
) {
    val missingFields: List<String> = Collections.unmodifiableList(ArrayList(missingFields))
}
class MemorySourceTitleRepairPlan internal constructor(
    val project: String, findings: List<MemorySourceTitleFinding>, val liveObservationCount: Long,
    val repairableCount: Long, val fingerprint: String,
    internal val database: JarvysChatDatabase, internal val owners: List<String>,
    internal val generation: String?, internal val requireOriginalFile: () -> Unit,
) {
    val findings: List<MemorySourceTitleFinding> = Collections.unmodifiableList(ArrayList(findings))
}
data class MemorySourceTitleRepairReceipt(val project: String, val repairedCount: Long, val backup: MemoryRepairBackupInfo?)

/** Pinned source-title derivation; deliberately not the normal save/update/history pipeline. */
object MemorySourceTitleRepairText {
    @JvmStatic fun derive(content: String): String? {
        var title = MemoryObservationContent.prepareTitle(content).substringBefore('\n')
        title = MemoryObservationContent.prepareTitle(title)
        var offset = 0
        while (offset < title.length) {
            val cp = title.codePointAt(offset)
            offset += Character.charCount(cp)
            if (cp in intArrayOf('.'.code, '!'.code, '?'.code, '。'.code, '！'.code, '？'.code)) {
                title = title.substring(0, offset)
                break
            }
        }
        if (title.codePointCount(0, title.length) > 300) title = title.substring(0, title.offsetByCodePoints(0, 300)) + "..."
        return title.takeIf { MemoryObservationContent.trimSpace(it).isNotEmpty() }
    }
}

internal object MemorySourceTitleRepairScan {
    data class Cell(val type: String, val bytes: ByteArray?) {
        fun same(other: Cell): Boolean = type == other.type && when {
            bytes == null -> other.bytes == null
            other.bytes == null -> false
            else -> bytes.contentEquals(other.bytes)
        }
        fun text(): String? = bytes?.let {
            try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(it)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { null }
        }
    }
    data class Action(val id: String, val portable: Cell, val title: String)
    data class Census(val generation: String?, val fingerprint: String, val liveCount: Long,
        val findings: List<MemorySourceTitleFinding>, val actions: List<Action>)
    private val columns = listOf("id", "project", "portable_sync_id", "observation_type", "title", "text", "updated_at", "updated_at_submillis_nanos")
    private fun fail(): Nothing = throw MemorySourceTitleRepairException("memory_repair_source_invalid")
    fun generation(sql: ChatSqlDatabase): String? = sql.query(
        "SELECT typeof(value),CAST(value AS BLOB) FROM storage_meta WHERE key=?", arrayOf(ConnectionProjectionRepository.GENERATION_KEY)
    ).use { c ->
        if (!c.moveToFirst()) null else {
            if (c.getString(0) != "text") fail()
            val cell = Cell("text", c.getBlob(1)); val value = cell.text() ?: fail()
            if (value.length != 36 || runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false).not() || c.moveToNext()) fail()
            value
        }
    }
    data class SchemaIdentity(val userVersion: Long, val roomIdentity: String)
    fun schemaIdentity(sql: ChatSqlDatabase, expected: String): SchemaIdentity {
        val version = sql.query("PRAGMA user_version").use { c ->
            if (!c.moveToFirst() || c.getType(0) != Cursor.FIELD_TYPE_INTEGER) fail()
            val value = c.getLong(0)
            if (value < 0 || c.moveToNext()) fail()
            value
        }
        val room = sql.query("SELECT typeof(id),id,typeof(identity_hash),CAST(identity_hash AS BLOB) FROM room_master_table WHERE id=42").use { c ->
            if (!c.moveToFirst() || c.getString(0) != "integer" || c.getLong(1) != 42L || c.getString(2) != "text") fail()
            val value = Cell("text", c.getBlob(3)).text() ?: fail()
            if (value != expected || c.moveToNext()) fail()
            value
        }
        return SchemaIdentity(version, room)
    }
    fun schema(sql: ChatSqlDatabase): List<List<String?>> = sql.query(
        "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"
    ).use { c -> buildList { while (c.moveToNext()) add((0..3).map { if (c.isNull(it)) null else c.getString(it) }) } }
    fun cells(sql: ChatSqlDatabase, id: String, names: List<String>): List<Cell> = sql.query(
        "SELECT " + names.joinToString(",") { "typeof($it),CAST($it AS BLOB)" } + " FROM memory_entries WHERE id=?", arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) fail()
        val result = names.indices.map { i -> Cell(c.getString(i * 2), if (c.isNull(i * 2 + 1)) null else c.getBlob(i * 2 + 1)) }
        if (c.moveToNext()) fail()
        result
    }
    fun scan(sql: ChatSqlDatabase, project: String, owners: List<String>, token: CancellationToken): Census {
        val generation = generation(sql)
        val hash = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray?) {
            if (value == null) { hash.update(0.toByte()); return }
            hash.update(1.toByte()); hash.update(ByteBuffer.allocate(8).putLong(value.size.toLong()).array()); hash.update(value)
        }
        fun string(value: String?) = bytes(value?.toByteArray(Charsets.UTF_8))
        string("jarvys-source-title-repair-v1"); string(project); string(generation)
        hash.update(ByteBuffer.allocate(8).putLong(owners.size.toLong()).array()); owners.forEach(::string)
        val findings = ArrayList<MemorySourceTitleFinding>(); val actions = ArrayList<Action>()
        var offset = 0L; var total = 0L
        while (true) {
            token.throwIfCancelled()
            val ids = sql.query("SELECT id FROM memory_entries WHERE project=? AND deleted_at IS NULL ORDER BY id LIMIT 64 OFFSET ?", arrayOf(project, offset)).use { c ->
                buildList { while (c.moveToNext()) { if (c.getType(0) != Cursor.FIELD_TYPE_STRING) fail(); add(c.getString(0)) } }
            }
            if (ids.isEmpty()) break
            for (id in ids) {
                token.throwIfCancelled()
                val row = cells(sql, id, columns)
                row.forEach { string(it.type); bytes(it.bytes) }
                if (row[0].type != "text" || row[0].text() != id || row[1].type != "text" || row[1].text() != project) fail()
                if (row[2].type !in setOf("null", "text") || row[2].type == "text" && row[2].text() == null) fail()
                if (row[6].type != "integer" || row[6].text()?.toLongOrNull() == null || row[7].type != "integer" || row[7].text()?.toLongOrNull() !in 0L..999999L) fail()
                val type = row[3]; val title = row[4]; val content = row[5]
                val invalid = ArrayList<String>()
                fun required(cell: Cell, name: String, allowBlob: Boolean = false): String? {
                    if (cell.type == "null") return ""
                    if (cell.type != "text" && !(allowBlob && cell.type == "blob")) { invalid.add(name); return null }
                    val text = cell.text()
                    if (text == null && !allowBlob) invalid.add(name)
                    return text
                }
                val typeText = required(type, "type")
                val titleText = required(title, "title", true)
                val contentText = required(content, "content")
                val missing = ArrayList<String>()
                if (typeText != null && MemoryObservationContent.trimSpace(typeText).isEmpty()) missing.add("type")
                // Malformed UTF-8 title bytes are nonblank, as Go's replacement-rune decoding is.
                if (titleText != null && MemoryObservationContent.trimSpace(titleText).isEmpty()) missing.add("title")
                if (contentText != null && MemoryObservationContent.trimSpace(contentText).isEmpty()) missing.add("content")
                val derived = if (invalid.isEmpty() && "title" in missing && contentText != null) MemorySourceTitleRepairText.derive(contentText) else null
                if (missing.isNotEmpty() || invalid.isNotEmpty()) {
                    val reason = when {
                        invalid.isNotEmpty() -> "Unsupported stored field representation: ${invalid.joinToString(", ")}. No repair proposed."
                        derived != null && missing.any { it != "title" } -> "Title can be repaired; missing type or content is not invented."
                        derived != null -> "Title is derived from this observation's own content."
                        else -> "No nonblank title can be derived. Missing type or content is not invented."
                    }
                    findings.add(MemorySourceTitleFinding(id, missing, if (title.type == "null") null else titleText,
                        if (title.type == "null") null else titleText != null, derived, reason))
                    if (derived != null) actions.add(Action(id, row[2], derived))
                }
                total = Math.addExact(total, 1L)
            }
            offset = Math.addExact(offset, ids.size.toLong())
        }
        hash.update(ByteBuffer.allocate(8).putLong(total).array())
        return Census(generation, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, total, findings, actions)
    }
}

internal class MemorySourceTitleRepairRepository(private val database: JarvysChatDatabase, private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val sql get() = database.chatSql
    suspend fun preview(project: String, namespace: MemoryNamespace, token: CancellationToken, verify: () -> Unit): MemorySourceTitleRepairPlan = database.withChatReadTransaction {
        verify(); namespace.requireOwner(project)
        val identity = JarvysChatDatabaseProvider.requireOpenFileIdentity(database, context)
        val owners = namespace.authorizedProjects().sorted()
        val census = MemorySourceTitleRepairScan.scan(sql, project, owners, token)
        identity(); verify()
        MemorySourceTitleRepairPlan(project, census.findings, census.liveCount, census.actions.size.toLong(), census.fingerprint,
            database, Collections.unmodifiableList(owners), census.generation, identity)
    }
    suspend fun apply(plan: MemorySourceTitleRepairPlan, namespace: MemoryNamespace, confirmed: Boolean,
        token: CancellationToken, verify: () -> Unit): MemorySourceTitleRepairReceipt {
        if (plan.database !== database) throw MemorySourceTitleRepairException("memory_repair_source_changed")
        val store = MemoryRepairBackupStore(context)
        var attempt: MemoryRepairBackupStore.Attempt? = null
        try {
            val receipt = database.withChatWriteTransaction {
                verify(); plan.requireOriginalFile(); namespace.requireOwner(plan.project)
                val owners = namespace.authorizedProjects().sorted()
                if (owners != plan.owners) throw MemorySourceTitleRepairException("memory_repair_owner_changed")
                val census = MemorySourceTitleRepairScan.scan(sql, plan.project, owners, token)
                if (census.fingerprint != plan.fingerprint || census.generation != plan.generation) throw MemorySourceTitleRepairException("memory_repair_plan_changed")
                if (census.actions.isEmpty()) return@withChatWriteTransaction MemorySourceTitleRepairReceipt(plan.project, 0, null)
                if (!confirmed) throw MemorySourceTitleRepairException("memory_repair_backup_confirmation_required")
                val schema = MemorySourceTitleRepairScan.schema(sql)
                val expectedRoomIdentity = database.expectedFreshIdentity()
                val schemaIdentity = MemorySourceTitleRepairScan.schemaIdentity(sql, expectedRoomIdentity)
                verify(); plan.requireOriginalFile()
                val created = store.create(token); attempt = created
                val backup = store.copyAndVerify(created, JarvysChatDatabaseProvider.databaseFileForRawQuery(database), token,
                    { verify(); plan.requireOriginalFile() }) { copy ->
                    if (MemorySourceTitleRepairScan.schema(copy) != schema || MemorySourceTitleRepairScan.schemaIdentity(copy, expectedRoomIdentity) != schemaIdentity ||
                        MemorySourceTitleRepairScan.generation(copy) != plan.generation)
                        throw MemorySourceTitleRepairException("memory_repair_backup_verification_failed")
                    if (MemorySourceTitleRepairScan.scan(copy, plan.project, owners, token).fingerprint != census.fingerprint)
                        throw MemorySourceTitleRepairException("memory_repair_backup_verification_failed")
                }
                verify(); plan.requireOriginalFile()
                val now = clock()
                val protectedColumns = MemoryConsolidationRepository.COLUMNS.filter { it !in setOf("title", "updated_at", "updated_at_submillis_nanos") }
                for (action in census.actions) {
                    verify(); plan.requireOriginalFile()
                    val before = MemorySourceTitleRepairScan.cells(sql, action.id, protectedColumns)
                    sql.execSQL("UPDATE memory_entries SET title=?,updated_at=? WHERE id=? AND project=? AND deleted_at IS NULL AND typeof(portable_sync_id)=? AND CAST(portable_sync_id AS BLOB) IS ?",
                        arrayOf(action.title, now, action.id, plan.project, action.portable.type, action.portable.bytes))
                    if (!sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L }) throw MemorySourceTitleRepairException("memory_repair_readback_failed")
                    val after = MemorySourceTitleRepairScan.cells(sql, action.id, protectedColumns)
                    if (before.size != after.size || before.indices.any { !before[it].same(after[it]) }) throw MemorySourceTitleRepairException("memory_repair_readback_failed")
                    val readback = MemorySourceTitleRepairScan.cells(sql, action.id, listOf("title", "updated_at", "updated_at_submillis_nanos"))
                    if (readback[0].type != "text" || readback[0].text() != action.title || readback[1].type != "integer" || readback[1].text() != now.toString() ||
                        readback[2].type != "integer" || readback[2].text() != "0") throw MemorySourceTitleRepairException("memory_repair_readback_failed")
                    token.throwIfCancelled()
                }
                if (MemorySourceTitleRepairScan.generation(sql) != plan.generation) throw MemorySourceTitleRepairException("memory_repair_source_changed")
                verify(); plan.requireOriginalFile(); store.requireVerified(created, token)
                MemorySourceTitleRepairReceipt(plan.project, census.actions.size.toLong(), backup)
            }
            // This is after the real commit. A failed publication/cancellation is an unknown response, never a retry.
            token.throwIfCancelled(); verify(); plan.requireOriginalFile()
            val created = attempt ?: return receipt
            store.outcome(created, "applied", null)
            token.throwIfCancelled(); verify(); plan.requireOriginalFile()
            return receipt.copy(backup = store.info(created, "applied"))
        } catch (failure: Throwable) {
            attempt?.let { created ->
                try { store.outcome(created, "unknown", if (token.isCancellationRequested) "cancelled" else "failed") }
                catch (publication: Throwable) { failure.addSuppressed(publication) }
            }
            throw failure
        }
    }
}
