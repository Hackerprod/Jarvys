package com.jarvys.agent.chatdb

import android.app.Application
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric's framework SQLite omits FTS5; this runs the production message index and history query on bundled FTS5. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageHistoryFts5Test {
    private fun exec(connection: androidx.sqlite.SQLiteConnection, sql: String, vararg args: Any?) {
        connection.prepare(sql).use { statement ->
            args.forEachIndexed { index, value ->
                when (value) {
                    null -> statement.bindNull(index + 1)
                    is Long -> statement.bindLong(index + 1, value)
                    is Int -> statement.bindLong(index + 1, value.toLong())
                    is Double -> statement.bindDouble(index + 1, value)
                    else -> statement.bindText(index + 1, value.toString())
                }
            }
            statement.step()
        }
    }

    private fun search(connection: androidx.sqlite.SQLiteConnection, query: String, limit: Int, beforeSeq: Long = Long.MAX_VALUE): List<String> =
        connection.prepare(MessageHistorySql.fts5("")).use { statement ->
            statement.bindText(1, checkNotNull(PersonalMemoryFtsSql.matchExpression(query)))
            statement.bindText(2, "central")
            statement.bindLong(3, 100)
            statement.bindLong(4, beforeSeq)
            statement.bindLong(5, limit.toLong())
            buildList { while (statement.step()) add(statement.getText(0)) }
        }

    @Test fun indexedMessagesAreOrderedPagedAndHiddenOnesDisappear() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            exec(connection, "CREATE TABLE messages(conversation_id TEXT, message_id TEXT, role TEXT, seq INTEGER, created_at REAL, hidden_kind TEXT)")
            exec(connection, "CREATE TABLE message_parts(conversation_id TEXT, message_id TEXT, ordinal INTEGER, kind TEXT, payload TEXT)")
            MessageSearchIndex.install(DriverChatSqlDatabase(connection))
            fun message(id: String, seq: Int, role: String, text: String) {
                exec(connection, "INSERT INTO messages VALUES('central',?,?,?,?,NULL)", id, role, seq, seq.toDouble())
                exec(connection, "INSERT INTO message_parts VALUES('central',?,0,'text',?)", id, text)
            }
            message("m1", 1, "user", "The order reference is 4817-XK, keep it")
            message("m2", 2, "assistant", "Noted the reference")
            message("m3", 3, "user", "reference reference reference 4817 again")
            message("m4", 4, "user", "unrelated chatter")

            assertEquals(listOf("m3", "m2", "m1"), search(connection, "4817 reference", 8))
            assertEquals(listOf("m3", "m2"), search(connection, "4817 reference", 2))
            assertEquals(listOf("m1"), search(connection, "4817 reference", 2, 2))
            // A regenerated message leaves the visible set.
            exec(connection, "UPDATE messages SET hidden_kind='assistant_regenerated' WHERE message_id='m2'")
            assertEquals(listOf("m3", "m1"), search(connection, "4817 reference", 8))
            // Editing the stored text keeps the index in step.
            exec(connection, "UPDATE message_parts SET payload='now about gardening' WHERE message_id='m1'")
            assertEquals(listOf("m3"), search(connection, "4817 reference", 8))
        } finally {
            connection.close()
        }
    }

    @Test fun keysetPagesSurviveNewTermsAndVisibilityChangesWithoutScanningTheHistory() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            exec(connection, "CREATE TABLE messages(conversation_id TEXT, message_id TEXT, role TEXT, seq INTEGER, created_at REAL, hidden_kind TEXT)")
            exec(connection, "CREATE TABLE message_parts(conversation_id TEXT, message_id TEXT, ordinal INTEGER, kind TEXT, payload TEXT)")
            exec(connection, "CREATE UNIQUE INDEX idx_messages_conversation_seq ON messages(conversation_id,seq)")
            exec(connection, "CREATE INDEX idx_messages_conversation_role_seq ON messages(conversation_id,role,seq)")
            MessageSearchIndex.install(DriverChatSqlDatabase(connection))
            fun message(id: String, seq: Int, text: String) {
                exec(connection, "INSERT INTO messages VALUES('central',?,'user',?,?,NULL)", id, seq, seq.toDouble())
                exec(connection, "INSERT INTO message_parts VALUES('central',?,0,'text',?)", id, text)
            }
            message("m1", 1, "apple")
            message("m2", 2, "apple")
            message("m3", 3, "banana")
            message("m4", 4, "banana")
            val query = MessageHistorySql.fts5("")
            val plan = connection.prepare("EXPLAIN QUERY PLAN $query").use { statement ->
                statement.bindText(1, checkNotNull(PersonalMemoryFtsSql.matchExpression("apple banana")))
                statement.bindText(2, "central")
                statement.bindLong(3, 4)
                statement.bindLong(4, Long.MAX_VALUE)
                statement.bindLong(5, 2)
                buildList { while (statement.step()) add(statement.getText(3)) }
            }
            assertTrue(plan.joinToString(" "), plan.any { it.contains("VIRTUAL TABLE INDEX") })
            assertTrue(plan.joinToString(" "), plan.any { it.contains("SEARCH m USING INDEX idx_messages_conversation_seq") })
            assertFalse(plan.joinToString(" "), plan.any { it == "SCAN m" || it.startsWith("SCAN m ") || it == "SCAN p" || it.startsWith("SCAN p ") })
            assertTrue(MessageHistorySql.fts5("").contains("m.seq<?"))
            assertFalse(MessageHistorySql.fts5("").contains("OFFSET"))

            val first = search(connection, "apple banana", 2, 5)
            assertEquals(listOf("m4", "m3"), first)
            message("m5", 5, "banana") // New FTS corpus statistics cannot move old keyset results.
            assertEquals(listOf("m2", "m1"), search(connection, "apple banana", 2, 3))

            exec(connection, "UPDATE messages SET hidden_kind='assistant_regenerated' WHERE message_id='m2'")
            val next = search(connection, "apple banana", 2, 2)
            assertEquals(listOf("m1"), next)
        } finally {
            connection.close()
        }
    }
}
