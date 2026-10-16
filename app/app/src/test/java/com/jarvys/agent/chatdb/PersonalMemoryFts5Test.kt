package com.jarvys.agent.chatdb

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Robolectric's framework SQLite omits FTS5; this exercises the production FTS SQL with SQLite's bundled FTS5. */
class PersonalMemoryFts5Test {
    @Test fun externalIndexTracksWritesAndSearchUsesBm25Ranking() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            exec(connection, "CREATE TABLE memory_entries(id TEXT PRIMARY KEY, scope TEXT, layer TEXT, text TEXT, updated_at INTEGER, deleted_at INTEGER)")
            exec(connection, PersonalMemoryFtsSql.CREATE_INDEX)
            exec(connection, PersonalMemoryFtsSql.INSERT_TRIGGER)
            exec(connection, PersonalMemoryFtsSql.DELETE_TRIGGER)
            exec(connection, PersonalMemoryFtsSql.UPDATE_TRIGGER)
            exec(connection, "INSERT INTO memory_entries VALUES('relevant','user','curated','Hackerprod develops Android applications',1,NULL)")
            exec(connection, "INSERT INTO memory_entries VALUES('other','user','daily','Hackerprod enjoys gardening',2,NULL)")

            val ranked = search(connection, "Hackerprod Android")
            assertEquals(listOf("relevant", "other"), ranked.map { it.first })
            assertTrue(ranked.first().second <= ranked.last().second)

            exec(connection, "UPDATE memory_entries SET text='Hackerprod develops Kotlin applications' WHERE id='relevant'")
            assertTrue(search(connection, "Android").isEmpty())
        } finally {
            connection.close()
        }
    }

    private fun exec(connection: androidx.sqlite.SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }

    private fun search(connection: androidx.sqlite.SQLiteConnection, query: String): List<Pair<String, Double>> {
        val match = checkNotNull(PersonalMemoryFtsSql.matchExpression(query))
        return connection.prepare(PersonalMemoryFtsSql.SEARCH).use { statement ->
            statement.bindText(1, match)
            statement.bindLong(2, 8)
            buildList {
                while (statement.step()) add(statement.getText(0) to statement.getDouble(4))
            }
        }
    }
}
