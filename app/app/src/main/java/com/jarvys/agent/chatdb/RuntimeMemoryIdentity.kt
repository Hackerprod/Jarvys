package com.jarvys.agent.chatdb

/** Exact runtime identity, including forgotten rows. Never follows an ordinary supersedes edge. */
internal object RuntimeMemoryIdentity {
    data class Entry(val id: String, val text: String, val title: String?, val deleted: Boolean)
    private data class Row(val id: String, val text: String, val title: String?, val deletedAt: Long?, val createdAt: Long)

    fun byTopic(database: JarvysChatDatabase, topic: String, origin: String, kind: String,
        sourceRef: String?, sourceConversationId: String?, sourceMessageId: String?): Entry? =
        resolve(database, "topic_key=?", arrayOf(topic), origin, kind, sourceRef, sourceConversationId, sourceMessageId)

    fun dailyDigest(database: JarvysChatDatabase, sourceRef: String): Entry? =
        resolve(database, "topic_key IS NULL AND category='daily_consolidation' AND layer='curated' AND source_ref=?",
            arrayOf(sourceRef), "daily_consolidation", "digest", sourceRef, null, null)

    private fun resolve(database: JarvysChatDatabase, selector: String, arguments: Array<String>,
        origin: String, kind: String, sourceRef: String?, sourceConversationId: String?, sourceMessageId: String?): Entry? {
        val sql = database.chatSql
        val rows = sql.query("SELECT id,text,title,deleted_at,created_at,provenance,kind,source_ref,source_conversation_id,source_message_id " +
            "FROM memory_entries WHERE project='jarvys-central' AND scope='project' AND $selector", arguments).use { c ->
            buildList {
                while (c.moveToNext()) {
                    fun nullable(index: Int) = if (c.isNull(index)) null else c.getString(index)
                    check(c.getString(5) == origin && c.getString(6) == kind && nullable(7) == sourceRef &&
                        nullable(8) == sourceConversationId && nullable(9) == sourceMessageId) { "memory_runtime_identity_conflict" }
                    add(Row(c.getString(0), c.getString(1), nullable(2), if(c.isNull(3)) null else c.getLong(3), c.getLong(4)))
                }
            }
        }
        if (rows.isEmpty()) return null
        check(rows.size == 1) { "memory_runtime_identity_conflict" }
        val row = rows.single()
        check(!MemoryDeletionMarkers.isDeletedId(database, row.id)) { "memory_runtime_target_hard_deleted" }
        return Entry(row.id,row.text,row.title,row.deletedAt != null)
    }
}
