package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConversationStoreSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-store-source-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "source.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(60, TimeUnit.SECONDS)

    @Test fun nonImportedConversationUsesJsonl() = onDatabaseThread {
        val coordinator = ImportCoordinator(database)
        assertEquals(ConversationStoreSource.Source.JSONL, ConversationStoreSource(database, coordinator).sourceFor("not-imported"))
    }

    @Test fun completedImportWithoutCutoverStillUsesJsonl() = onDatabaseThread {
        val (sessionId, ledger) = importOneConversation("done-without-cutover")
        val coordinator = ImportCoordinator(database)
        assertEquals("done", ConversationRepository(database).readImportConversation(sessionId)?.import_state)
        assertEquals(ConversationStoreSource.Source.JSONL, ConversationStoreSource(database, coordinator).sourceFor(sessionId))
        check(ledger.isFile)
    }

    @Test fun activeValidatedCutoverUsesDatabaseUntilReleased() = onDatabaseThread {
        val (sessionId, ledger) = importOneConversation("active-cutover")
        val coordinator = ImportCoordinator(database)
        val source = ConversationStoreSource(database, coordinator)

        coordinator.beginCutover(sessionId, ledger)
        try {
            assertEquals(ConversationStoreSource.Source.DATABASE, source.sourceFor(sessionId))
        } finally {
            coordinator.endCutover(sessionId)
        }
        assertEquals(ConversationStoreSource.Source.JSONL, source.sourceFor(sessionId))
    }

    @Test fun absentAndTombstonedSessionsUseJsonl() = onDatabaseThread {
        val coordinator = ImportCoordinator(database)
        val source = ConversationStoreSource(database, coordinator)
        assertEquals(ConversationStoreSource.Source.JSONL, source.sourceFor("absent-session"))

        val (sessionId, _) = importOneConversation("tombstoned-session")
        assertEquals(ChatDbResult.Success(true), ConversationRepository(database).deleteConversation(sessionId))
        assertEquals(ConversationStoreSource.Source.JSONL, source.sourceFor(sessionId))
    }

    private suspend fun importOneConversation(sessionId: String): Pair<String, File> {
        val ledger = File(temporary.root, "$sessionId.jsonl")
        ledger.writeText(
            """{"role":"user","content":"fixture","messageId":"$sessionId-message"}""" + "\n",
            Charsets.UTF_8,
        )
        val metadata = File(temporary.root, "$sessionId.meta.json").apply { writeText("{}", Charsets.UTF_8) }
        val report = LedgerImporter(database).importConversation(sessionId, ledger, metadata)
        check(report.state == "done") { report.toDiagnosticText() }
        return sessionId to ledger
    }
}
