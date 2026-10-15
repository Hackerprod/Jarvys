package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.util.concurrent.atomic.AtomicInteger

/** Local-only migration acceptance test; it reports identities and sizes, never ledger payloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenLedgerImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-golden-import-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "golden.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    @Test fun importsAllLocalGoldenLedgersAndValidatesLargeCrewCompression() {
        val directory = File(System.getProperty("jarvys.goldenLedgers") ?: DEFAULT_GOLDEN_DIRECTORY)
        if (!directory.isDirectory) {
            if (System.getProperty("jarvys.goldenLedgers.optional")?.equals("true", ignoreCase = true) == true) {
                assumeTrue("SKIPPED GoldenLedgerImportTest: optional local golden-ledger directory is unavailable.", false)
                return
            }
            throw AssertionError("Golden-ledger directory is unavailable. Set -Djarvys.goldenLedgers=<directory>, or explicitly set -Djarvys.goldenLedgers.optional=true to skip this local-only test.")
        }

        val ledgers = directory.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }?.toList().orEmpty()
        assertTrue("the local golden corpus must contain at least one JSONL ledger", ledgers.isNotEmpty())

        val progressEvents = AtomicInteger()
        val reports = databaseExecutor.submit<List<ImportReport>> {
            runBlocking {
                ImportCoordinator(database).importAll(directory) { progress ->
                    progressEvents.incrementAndGet()
                    assertTrue(progress.bytesProcessed >= 0L)
                    assertTrue(progress.totalBytes >= progress.bytesProcessed)
                }
            }
        }.get(10, TimeUnit.MINUTES)

        val ledgerIds = ledgers.map { it.name.removeSuffix(".jsonl") }.sorted()
        val ledgerReports = reports.filter { it.conversationId in ledgerIds }
        assertEquals("every JSONL ledger, including ledgers without metadata sidecars, must be imported",
            ledgerIds, ledgerReports.map { it.conversationId }.sorted())
        val metadataOnlyReports = reports.filter { it.conversationId !in ledgerIds }
        assertTrue("reports without a JSONL ledger must be valid tombstone imports",
            metadataOnlyReports.all { it.state == "tombstone" && it.validations.all { validation -> validation.passed } })
        assertTrue("coordinator should emit progress for source rows and conversation completion",
            progressEvents.get() >= ledgers.size)
        ledgerReports.forEach { report ->
            assertEquals("a local golden conversation must reach validated done state", "done", report.state)
            assertEquals("golden corpus must not contain unrecognized row types", 0L, report.unknownLines)
            assertEquals("every tolerated damaged line must be described in the diagnostic report", report.skippedLines, report.issues.size.toLong())
            assertTrue(report.validations.all { it.passed })
            assertTrue("diagnostics must not contain JSONL message bodies", report.toDiagnosticText().length < 100_000)
        }

        val largeCrewLedgers = ledgerReports.filter { report ->
            val rows = report.ledgerRowsByType.values.sum()
            val crewSnapshots = report.ledgerRowsByType["crew_snapshot"] ?: 0L
            report.ledgerBytes >= 30L * 1024L * 1024L && rows > 0 && crewSnapshots.toDouble() / rows >= 0.5
        }
        assertTrue("the corpus should include a large ledger dominated by crew snapshots", largeCrewLedgers.isNotEmpty())
        largeCrewLedgers.forEach { report ->
            val retainedConversationBytes =
                (report.conversationPayloadBytesAfter - report.conversationPayloadBytesBefore).coerceAtLeast(0L)
            assertTrue("large repeated crew snapshots should retain less than ten percent of source bytes",
                retainedConversationBytes.toDouble() < report.ledgerBytes.toDouble() * 0.10)
        }
    }

    private companion object {
        const val DEFAULT_GOLDEN_DIRECTORY = "C:\\Projects\\Jarvys-out\\golden-ledgers"
    }
}
