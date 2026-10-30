package com.jarvys.agent.chatdb

import android.content.Context
import android.database.Cursor
import com.jarvys.agent.CancellationToken
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

class MemoryOrphanRelationRepairException(val code: String) : IllegalStateException(code)
data class MemoryOrphanRelationCandidate(val relationId: String, val sourceId: String, val targetId: String,
    val sourceProject: String, val targetProject: String)
class MemoryOrphanRelationRepairPlan internal constructor(candidates: List<MemoryOrphanRelationCandidate>,
    val candidateCount: Long, val oneRetiredCount: Long, val livePendingCount: Long,
    internal val database: JarvysChatDatabase, internal val owners: List<String>, internal val generation: String?,
    internal val requireOriginalFile: () -> Unit) {
    val candidates: List<MemoryOrphanRelationCandidate> = Collections.unmodifiableList(ArrayList(candidates))
}
data class MemoryOrphanRelationRepairReceipt(val reclassifiedCount: Long, val candidatesAtApply: Long,
    val oneRetiredCount: Long, val livePendingCount: Long, val backup: MemoryRepairBackupInfo)

/** Only retained endpoints establish authority. Audit relation.project and portable labels do not. */
internal object MemoryOrphanRelationRepairScan {
    val columns = listOf("id", "relation_id", "source_id", "target_id", "project", "scope", "relation", "status",
        "created_at", "updated_at", "reason", "evidence", "confidence", "marked_by_actor", "marked_by_kind",
        "marked_by_model", "memory_session_id", "portable_sync_id", "superseded_at", "superseded_by_relation_id",
        "portable_superseded_by_relation_sync_id", "created_at_submillis_nanos", "updated_at_submillis_nanos", "superseded_at_submillis_nanos")
    private val endpointColumns = listOf("s.id", "s.project", "s.deleted_at", "t.id", "t.project", "t.deleted_at")
    data class Row(val id: Long, val values: List<MemorySourceTitleRepairScan.Cell>,
        val candidate: MemoryOrphanRelationCandidate?, val pending: Boolean, val retiredCount: Int)
    data class Census(val rows: List<Row>, val fingerprint: String) {
        val candidates get() = rows.mapNotNull { it.candidate }
        val oneRetired get() = rows.count { it.pending && it.retiredCount == 1 }.toLong()
        val livePending get() = rows.count { it.pending && it.retiredCount == 0 }.toLong()
    }
    private fun fail(): Nothing = throw MemoryOrphanRelationRepairException("memory_orphan_source_invalid")
    fun guard(owners: List<String>): String {
        check(owners.isNotEmpty())
        val placeholders = owners.joinToString(",") { "?" }
        return "s.project IN ($placeholders) AND t.project IN ($placeholders)"
    }
    fun scan(sql: ChatSqlDatabase, owners: List<String>, token: CancellationToken, metadataOnly: Boolean = false): Census {
        // Preview does not select or hydrate protected payload fields. NULL slots retain the decoder layout.
        val previewColumns = setOf("id", "relation_id", "source_id", "target_id", "status")
        val names = columns.map { if (metadataOnly && it !in previewColumns) "NULL" else "r.$it" } + endpointColumns
        val rows = ArrayList<Row>(); var offset = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        fun hash(bytes: ByteArray?) {
            digest.update(ByteBuffer.allocate(8).putLong(bytes?.size?.toLong() ?: -1L).array())
            if (bytes != null) digest.update(bytes)
        }
        hash("jarvys-orphan-relations-v1".toByteArray())
        owners.forEach { hash(it.toByteArray(Charsets.UTF_8)) }
        while (true) {
            token.throwIfCancelled()
            // Authorization precedes decoding/validation: excluded rows cannot cause diagnostic disclosure.
            val page = sql.query("SELECT " + names.joinToString(",") { "typeof($it),$it,CAST($it AS BLOB)" } +
                " FROM memory_relations r JOIN memory_entries s ON s.id=r.source_id JOIN memory_entries t ON t.id=r.target_id" +
                " WHERE ${guard(owners)} ORDER BY r.id LIMIT 64 OFFSET ?", (owners + owners + listOf(offset)).toTypedArray()).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        token.throwIfCancelled()
                        val values = names.indices.map { index ->
                            val col = index * 3; val type = c.getString(col)
                            val bytes = when (type) {
                                "null" -> null
                                "real" -> ByteBuffer.allocate(8).putLong(java.lang.Double.doubleToRawLongBits(c.getDouble(col + 1))).array()
                                else -> c.getBlob(col + 2)
                            }
                            MemorySourceTitleRepairScan.Cell(type, bytes)
                        }
                        fun text(index: Int): String {
                            val cell = values[index]
                            if (cell.type != "text") fail()
                            return cell.text() ?: fail()
                        }
                        fun retired(index: Int): Boolean {
                            val cell = values[index]
                            if (cell.type == "null") return false
                            if (cell.type != "integer" || cell.text()?.toLongOrNull() == null) fail()
                            return true
                        }
                        val id = if (values[0].type == "integer") values[0].text()?.toLongOrNull() ?: fail() else fail()
                        val relation = text(1); val source = text(2); val target = text(3); val status = text(7)
                        val base = columns.size
                        if (text(base) != source || text(base + 3) != target) fail()
                        val sourceProject = text(base + 1); val targetProject = text(base + 4)
                        if (sourceProject !in owners || targetProject !in owners) fail()
                        val count = (if (retired(base + 2)) 1 else 0) + (if (retired(base + 5)) 1 else 0)
                        val pending = status == "pending"
                        val candidate = if (pending && count == 2) MemoryOrphanRelationCandidate(relation, source, target, sourceProject, targetProject) else null
                        values.forEach { hash(it.type.toByteArray()); hash(it.bytes) }
                        add(Row(id, values, candidate, pending, count))
                    }
                }
            }
            if (page.isEmpty()) break
            rows.addAll(page); offset = Math.addExact(offset, page.size.toLong())
        }
        hash(ByteBuffer.allocate(8).putLong(rows.size.toLong()).array())
        return Census(rows, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    fun verifyAfter(before: Census, after: Census, now: Long) {
        if (before.rows.size != after.rows.size) throw MemoryOrphanRelationRepairException("memory_orphan_readback_failed")
        for (index in before.rows.indices) {
            val old = before.rows[index]; val current = after.rows[index]
            if (old.id != current.id || old.values.size != current.values.size) throw MemoryOrphanRelationRepairException("memory_orphan_readback_failed")
            for (column in old.values.indices) {
                val expected = if (old.candidate == null) null else when (columns.getOrNull(column)) {
                    "status" -> "text" to "orphaned"
                    "updated_at" -> "integer" to now.toString()
                    "updated_at_submillis_nanos" -> "integer" to "0"
                    else -> null
                }
                val actual = current.values[column]
                if (if (expected == null) !old.values[column].same(actual) else actual.type != expected.first || actual.text() != expected.second)
                    throw MemoryOrphanRelationRepairException("memory_orphan_readback_failed")
            }
        }
    }
}

internal class MemoryOrphanRelationRepairRepository(private val database: JarvysChatDatabase, private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val sql get() = database.chatSql
    suspend fun preview(namespace: MemoryNamespace, token: CancellationToken, verify: () -> Unit): MemoryOrphanRelationRepairPlan = database.withChatReadTransaction {
        verify()
        val identity = JarvysChatDatabaseProvider.requireOpenFileIdentity(database, context)
        val owners = namespace.authorizedProjects().sorted()
        val generation = MemorySourceTitleRepairScan.generation(sql)
        val census = MemoryOrphanRelationRepairScan.scan(sql, owners, token, metadataOnly = true)
        identity(); verify()
        MemoryOrphanRelationRepairPlan(census.candidates, census.candidates.size.toLong(), census.oneRetired, census.livePending,
            database, Collections.unmodifiableList(owners), generation, identity)
    }
    suspend fun apply(plan: MemoryOrphanRelationRepairPlan, namespace: MemoryNamespace, confirmed: Boolean,
        token: CancellationToken, verify: () -> Unit): MemoryOrphanRelationRepairReceipt {
        if (plan.database !== database) throw MemoryOrphanRelationRepairException("memory_orphan_source_changed")
        if (!confirmed) throw MemoryOrphanRelationRepairException("memory_repair_backup_confirmation_required")
        val store = MemoryRepairBackupStore(context); var attempt: MemoryRepairBackupStore.Attempt? = null
        try {
            val receipt = database.withChatWriteTransaction {
                verify(); plan.requireOriginalFile()
                val owners = namespace.authorizedProjects().sorted()
                if (owners != plan.owners) throw MemoryOrphanRelationRepairException("memory_orphan_owner_changed")
                if (MemorySourceTitleRepairScan.generation(sql) != plan.generation) throw MemoryOrphanRelationRepairException("memory_orphan_source_changed")
                val before = MemoryOrphanRelationRepairScan.scan(sql, owners, token)
                val schema = MemorySourceTitleRepairScan.schema(sql)
                val expected = database.expectedFreshIdentity()
                val identity = MemorySourceTitleRepairScan.schemaIdentity(sql, expected)
                verify(); plan.requireOriginalFile()
                val created = store.create(token, "orphan_pending_relations"); attempt = created
                val backup = store.copyAndVerify(created, JarvysChatDatabaseProvider.databaseFileForRawQuery(database), token,
                    { verify(); plan.requireOriginalFile() }) { copy ->
                    if (MemorySourceTitleRepairScan.schema(copy) != schema || MemorySourceTitleRepairScan.schemaIdentity(copy, expected) != identity ||
                        MemorySourceTitleRepairScan.generation(copy) != plan.generation ||
                        MemoryOrphanRelationRepairScan.scan(copy, owners, token).fingerprint != before.fingerprint)
                        throw MemoryOrphanRelationRepairException("memory_orphan_backup_verification_failed")
                }
                verify(); plan.requireOriginalFile()
                val current = MemoryOrphanRelationRepairScan.scan(sql, owners, token)
                if (current.fingerprint != before.fingerprint) throw MemoryOrphanRelationRepairException("memory_orphan_source_changed")
                val now = Math.floorDiv(clock(), 1000L) * 1000L
                // Re-derive in SQL, never substitute estimated candidate IDs. Every endpoint remains retained and owned.
                sql.execSQL("UPDATE memory_relations AS r SET status='orphaned',updated_at=? WHERE r.status='pending' AND EXISTS (" +
                    "SELECT 1 FROM memory_entries s JOIN memory_entries t ON t.id=r.target_id WHERE s.id=r.source_id" +
                    " AND ${MemoryOrphanRelationRepairScan.guard(owners)} AND s.deleted_at IS NOT NULL AND t.deleted_at IS NOT NULL)",
                    (listOf(now) + owners + owners).toTypedArray())
                val changed = sql.query("SELECT changes()").use { c ->
                    if (!c.moveToFirst() || c.getType(0) != Cursor.FIELD_TYPE_INTEGER) throw MemoryOrphanRelationRepairException("memory_orphan_readback_failed")
                    c.getLong(0)
                }
                token.throwIfCancelled()
                if (changed != current.candidates.size.toLong()) throw MemoryOrphanRelationRepairException("memory_orphan_readback_failed")
                val after = MemoryOrphanRelationRepairScan.scan(sql, owners, token)
                MemoryOrphanRelationRepairScan.verifyAfter(current, after, now)
                if (MemorySourceTitleRepairScan.generation(sql) != plan.generation) throw MemoryOrphanRelationRepairException("memory_orphan_source_changed")
                verify(); plan.requireOriginalFile(); store.requireVerified(created, token)
                MemoryOrphanRelationRepairReceipt(changed, current.candidates.size.toLong(), after.oneRetired, after.livePending, backup)
            }
            token.throwIfCancelled(); verify(); plan.requireOriginalFile()
            val created = checkNotNull(attempt)
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
