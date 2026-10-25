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
            exec(connection, "CREATE TABLE memory_entries(id TEXT PRIMARY KEY, layer TEXT, text TEXT, updated_at INTEGER, deleted_at INTEGER, title TEXT, topic_key TEXT)")
            exec(connection, PersonalMemoryFtsSql.FTS5_CREATE)
            exec(connection, PersonalMemoryFtsSql.FTS5_INSERT_TRIGGER)
            exec(connection, PersonalMemoryFtsSql.FTS5_DELETE_TRIGGER)
            exec(connection, PersonalMemoryFtsSql.FTS5_UPDATE_TRIGGER)
            exec(connection, PersonalMemoryFtsSql.FTS5_REBUILD)
            exec(connection, "INSERT INTO memory_entries(id,layer,text,updated_at,deleted_at) VALUES('relevant','curated','Hackerprod develops Android applications',1,NULL)")
            exec(connection, "INSERT INTO memory_entries(id,layer,text,updated_at,deleted_at) VALUES('other','daily','Hackerprod enjoys gardening',2,NULL)")

            val ranked = search(connection, "Hackerprod Android")
            assertEquals(listOf("relevant", "other"), ranked.map { it.first })
            assertTrue(ranked.first().second <= ranked.last().second)

            exec(connection, "UPDATE memory_entries SET text='Hackerprod develops Kotlin applications' WHERE id='relevant'")
            assertTrue(search(connection, "Android").isEmpty())
        } finally {
            connection.close()
        }
    }

    @Test fun reservedWordsAreQuotedBeforeFts5PrefixMatching() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            exec(connection, "CREATE TABLE memory_entries(id TEXT PRIMARY KEY, layer TEXT, text TEXT, updated_at INTEGER, deleted_at INTEGER, title TEXT, topic_key TEXT)")
            exec(connection, PersonalMemoryFtsSql.FTS5_CREATE)
            exec(connection, "INSERT INTO memory_entries(id,layer,text,updated_at,deleted_at) VALUES('reserved','curated','AND OR NOT NEAR are ordinary remembered words',1,NULL)")
            exec(connection, PersonalMemoryFtsSql.FTS5_REBUILD)

            assertEquals("\"AND\"*", PersonalMemoryFtsSql.matchExpression("AND"))
            assertEquals(listOf("reserved"), search(connection, "AND").map { it.first })
            assertEquals(listOf("reserved"), search(connection, "NEAR").map { it.first })
        } finally {
            connection.close()
        }
    }

    @Test fun titleAndTopicKeyOutrankBodyMentionsAndFiltersNarrowTheMatch() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            exec(connection, "CREATE TABLE memory_entries(id TEXT PRIMARY KEY, layer TEXT, text TEXT, updated_at INTEGER, deleted_at INTEGER, title TEXT, topic_key TEXT, kind TEXT)")
            exec(connection, PersonalMemoryFtsSql.FTS5_CREATE)
            exec(connection, PersonalMemoryFtsSql.FTS5_INSERT_TRIGGER)
            exec(connection, PersonalMemoryFtsSql.FTS5_DELETE_TRIGGER)
            exec(connection, PersonalMemoryFtsSql.FTS5_UPDATE_TRIGGER)
            exec(connection, "INSERT INTO memory_entries(id,layer,text,updated_at,deleted_at,title,topic_key,kind) VALUES('body','curated','We talked about kubernetes once, among many other things in a long note',1,NULL,NULL,NULL,'note')")
            exec(connection, "INSERT INTO memory_entries(id,layer,text,updated_at,deleted_at,title,topic_key,kind) VALUES('titled','curated','Cluster notes',2,NULL,'Kubernetes cluster','ops/kubernetes','decision')")
            exec(connection, "INSERT INTO memory_entries(id,layer,text,updated_at,deleted_at,title,topic_key,kind) VALUES('gone','curated','kubernetes removed',3,1,NULL,NULL,'decision')")

            assertEquals(listOf("titled", "body"), search(connection, "kubernetes").map { it.first })
            val onlyDecisions = connection.prepare(PersonalMemoryFtsSql.fts5Search(" AND m.kind=?")).use { statement ->
                statement.bindText(1, checkNotNull(PersonalMemoryFtsSql.matchExpression("kubernetes")))
                statement.bindText(2, "decision")
                statement.bindLong(3, 8)
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
            assertEquals(listOf("titled"), onlyDecisions)
            // Updating the topic key keeps the index in step with the row.
            exec(connection, "UPDATE memory_entries SET topic_key='ops/terraform' WHERE id='titled'")
            assertEquals(listOf("titled"), connection.prepare(PersonalMemoryFtsSql.fts5Search()).use { statement ->
                statement.bindText(1, checkNotNull(PersonalMemoryFtsSql.matchExpression("terraform")))
                statement.bindLong(2, 8)
                buildList { while (statement.step()) add(statement.getText(0)) }
            })
        } finally {
            connection.close()
        }
    }

    private fun exec(connection: androidx.sqlite.SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }

    private fun search(connection: androidx.sqlite.SQLiteConnection, query: String): List<Pair<String, Double>> {
        val match = checkNotNull(PersonalMemoryFtsSql.matchExpression(query))
        return connection.prepare(PersonalMemoryFtsSql.FTS5_SEARCH).use { statement ->
            statement.bindText(1, match)
            statement.bindLong(2, 8)
            buildList {
                while (statement.step()) add(statement.getText(0) to statement.getDouble(2))
            }
        }
    }
}
