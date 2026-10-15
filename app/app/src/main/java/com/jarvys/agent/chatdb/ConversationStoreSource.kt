package com.jarvys.agent.chatdb

/**
 * M4 read routing contract. A conversation becomes eligible for SQLite reads only after its
 * import is complete and the coordinator currently owns that conversation's cutover lock.
 * Callers remain responsible for routing reads; this class is deliberately not wired to the app.
 */
internal class ConversationStoreSource(
    private val database: JarvysChatDatabase,
    private val importCoordinator: ImportCoordinator,
) {
    internal enum class Source { JSONL, DATABASE }

    suspend fun sourceFor(sessionId: String): Source {
        val importedAndLive = JarvysChatDatabaseProvider.onQueryExecutor(database) {
            database.withChatReadTransaction {
                database.chatSql.query(
                    "SELECT EXISTS(" +
                        "SELECT 1 FROM conversations c WHERE c.id=? AND c.import_state='done' " +
                        "AND NOT EXISTS(SELECT 1 FROM tombstones t WHERE t.scope='conversation' AND t.entity_id=c.id)" +
                        ")",
                    arrayOf(sessionId),
                ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }
            }
        }
        return if (importedAndLive && importCoordinator.hasActiveCutover(sessionId)) {
            Source.DATABASE
        } else {
            Source.JSONL
        }
    }
}
