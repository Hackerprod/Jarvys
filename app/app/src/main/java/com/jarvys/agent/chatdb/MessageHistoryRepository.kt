package com.jarvys.agent.chatdb

import java.security.MessageDigest

/** One message that matched a history search; [excerpt] is the line around the first match. */
data class HistoryHit(
    val messageId: String,
    val role: String,
    val seq: Long,
    val createdAtSeconds: Double,
    val excerpt: String,
)

/** A page of hits; [nextCursor] is null on the last page and is only valid for the query and filters that produced it. */
data class HistoryPage(val hits: List<HistoryHit>, val nextCursor: String?)

/** One page of a message's exact text; [nextOffset] is null when the page reaches the end. */
data class HistoryMessage(
    val messageId: String,
    val role: String,
    val status: String?,
    val seq: Long,
    val createdAtSeconds: Double,
    val offset: Int,
    val totalChars: Int,
    val text: String,
    val nextOffset: Int?,
)

internal object MessageHistorySql {
    /** Visible messages of one conversation in the snapshot and before the keyset position. */
    private const val VISIBLE = "m.conversation_id=? AND m.hidden_kind IS NULL AND m.seq<=? AND m.seq<?"

    fun fts5(filters: String): String =
        "SELECT m.message_id,m.role,m.seq,m.created_at,p.payload FROM message_fts " +
            "JOIN message_parts p ON p.rowid=message_fts.rowid " +
            "JOIN messages m ON m.conversation_id=p.conversation_id AND m.message_id=p.message_id " +
            "WHERE message_fts MATCH ? AND $VISIBLE $filters GROUP BY m.conversation_id,m.message_id " +
            "ORDER BY m.seq DESC LIMIT ?"

    /** Substring fallback for builds without FTS5; results use the same stable sequence order. */
    fun like(termCount: Int, filters: String): String {
        val matches = (1..termCount).joinToString(" OR ") { "p.payload LIKE ? ESCAPE '\\'" }
        return "SELECT m.message_id,m.role,m.seq,m.created_at,p.payload FROM message_parts p " +
            "JOIN messages m ON m.conversation_id=p.conversation_id AND m.message_id=p.message_id " +
                "WHERE p.kind='text' AND ($matches) AND $VISIBLE $filters GROUP BY m.conversation_id,m.message_id " +
                "ORDER BY m.seq DESC LIMIT ?"
    }
}

/** Search and exact reads over the stored messages of one conversation (layer 3 of the memory model). */
internal class MessageHistoryRepository internal constructor(private val database: JarvysChatDatabase) {
    private val sql get() = database.chatSql

    /**
     * Messages whose text matches [query], newest first. The keyset cursor is independent of global FTS ranking,
     * and the high-water sequence keeps messages appended after the first page out of the current search.
     * A cursor made for another query or filter set is rejected instead of returning a mixed page.
     */
    suspend fun search(
        conversationId: String, query: String, role: String?, fromMs: Long?, toMsExclusive: Long?,
        cursor: String?, limit: Int, excerptChars: Int,
    ): HistoryPage {
        val cleaned = query.trim()
        require(cleaned.isNotEmpty()) { "history_query_required" }
        val terms = PersonalMemoryFtsSql.terms(cleaned)
        if (terms.isEmpty()) return HistoryPage(emptyList(), null)
        val max = limit.coerceAtLeast(1)
        val fingerprint = fingerprint(conversationId, cleaned, role, fromMs, toMsExclusive)
        return database.withChatReadTransaction {
            val position = if (cursor == null) {
                val highWater = highWater(conversationId)
                Cursor(highWater, Long.MAX_VALUE, historyRevision(conversationId))
            } else decode(cursor, fingerprint, historyRevision(conversationId))
            val (filterSql, filterArgs) = filters(role, fromMs, toMsExclusive)
            val rows = if (hasFts5()) {
                val match = PersonalMemoryFtsSql.matchExpression(cleaned)!!
                sql.query(
                    MessageHistorySql.fts5(filterSql),
                    (listOf<Any?>(match, conversationId, position.highWater, position.beforeSeq) + filterArgs + (max.toLong() + 1L)).toTypedArray(),
                )
            } else {
                val patterns = terms.map { "%${it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%" }
                sql.query(
                    MessageHistorySql.like(terms.size, filterSql),
                    (patterns + conversationId + position.highWater + position.beforeSeq + filterArgs + (max.toLong() + 1L)).toTypedArray(),
                )
            }.use { c ->
                buildList {
                    while (c.moveToNext()) add(HistoryHit(
                        messageId = c.getString(0), role = c.getString(1), seq = c.getLong(2), createdAtSeconds = c.getDouble(3),
                        excerpt = SearchTerms.excerpt(c.getString(4), terms, excerptChars),
                    ))
                }
            }
            val more = rows.size > max
            val page = rows.take(max)
            HistoryPage(page, if (more) encode(position.copy(beforeSeq = page.last().seq), fingerprint) else null)
        }
    }

    /** The exact text of [messageId], or null when it does not exist, belongs to another conversation or was hidden. */
    suspend fun read(conversationId: String, messageId: String, offset: Int, limitChars: Int): HistoryMessage? =
        database.withChatReadTransaction {
            val header = sql.query(
                "SELECT role,status,seq,created_at FROM messages WHERE conversation_id=? AND message_id=? AND hidden_kind IS NULL",
                arrayOf(conversationId, messageId),
            ).use { c -> if (c.moveToFirst()) listOf(c.getString(0), c.getStringOrNullM1b("status"), c.getLong(2), c.getDouble(3)) else null }
                ?: return@withChatReadTransaction null
            val full = sql.query(
                "SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind='text' ORDER BY ordinal",
                arrayOf(conversationId, messageId),
            ).use { c -> buildString { var first = true; while (c.moveToNext()) { if (!first) append('\n'); first = false; append(c.getString(0)) } } }
            var start = offset.coerceIn(0, full.length)
            if (start > 0 && start < full.length && Character.isLowSurrogate(full[start]) && Character.isHighSurrogate(full[start - 1])) start++
            var end = (start.toLong() + limitChars.coerceAtLeast(1)).coerceAtMost(full.length.toLong()).toInt()
            // Pages advance by Unicode code point, even when one requested UTF-16 unit meets a surrogate pair.
            if (start < full.length && Character.isHighSurrogate(full[start]) && start + 1 < full.length && Character.isLowSurrogate(full[start + 1]))
                end = maxOf(end, start + 2)
            if (end < full.length && end > start && Character.isHighSurrogate(full[end - 1])) end++
            HistoryMessage(
                messageId = messageId, role = header[0] as String, status = header[1] as String?, seq = header[2] as Long,
                createdAtSeconds = header[3] as Double, offset = start, totalChars = full.length,
                text = full.substring(start, end), nextOffset = end.takeIf { it < full.length },
            )
        }

    private fun hasFts5(): Boolean =
        sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='message_fts'").use { it.moveToFirst() }

    private fun highWater(conversationId: String): Long =
        sql.query("SELECT COALESCE(next_seq-1,0) FROM conversations WHERE id=?", arrayOf(conversationId)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun historyRevision(conversationId: String): Long =
        sql.query("SELECT history_revision FROM conversations WHERE id=?", arrayOf(conversationId)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun filters(role: String?, fromMs: Long?, toMsExclusive: Long?): Pair<String, List<Any?>> {
        val parts = ArrayList<String>()
        val args = ArrayList<Any?>()
        role?.let { parts += "m.role=?"; args += it }
        fromMs?.let { parts += "m.created_at>=?"; args += it / 1000.0 }
        toMsExclusive?.let { parts += "m.created_at<?"; args += it / 1000.0 }
        return (if (parts.isEmpty()) "" else " AND ${parts.joinToString(" AND ")}") to args
    }

    private data class Cursor(val highWater: Long, val beforeSeq: Long, val historyRevision: Long)

    private fun fingerprint(conversationId: String, query: String, role: String?, fromMs: Long?, toMsExclusive: Long?): String =
        MessageDigest.getInstance("SHA-256").digest("$conversationId|$query|$role|$fromMs|$toMsExclusive".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun encode(cursor: Cursor, fingerprint: String): String = "$fingerprint.${cursor.highWater}.${cursor.beforeSeq}.${cursor.historyRevision}"

    private fun decode(value: String, fingerprint: String, currentHistoryRevision: Long): Cursor {
        val parts = value.split('.')
        val highWater = parts.getOrNull(1)?.toLongOrNull()
        val beforeSeq = parts.getOrNull(2)?.toLongOrNull()
        val historyRevision = parts.getOrNull(3)?.toLongOrNull()
        require(parts.size == 4 && parts[0] == fingerprint && highWater != null && beforeSeq != null && beforeSeq >= 0 &&
            historyRevision != null && historyRevision == currentHistoryRevision) { "history_cursor_invalid" }
        return Cursor(highWater, beforeSeq, historyRevision)
    }
}
