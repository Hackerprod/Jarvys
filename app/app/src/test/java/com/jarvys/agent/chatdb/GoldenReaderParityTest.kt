package com.jarvys.agent.chatdb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.ConversationTurn
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.crew.CrewMissionSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local-only read parity. Assertions and metrics never include ledger bodies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenReaderParityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "chatdb-golden-reader-test").apply { isDaemon = true }
    }
    private lateinit var database: JarvysChatDatabase

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "golden-reader.db"))
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(5, TimeUnit.MINUTES)

    @Test fun localGoldenLedgersMatchCoveredReadersAndMeasureTheFortyEightMegabyteLedger() = onDatabaseThread {
        val directory = File(System.getProperty("jarvys.goldenLedgers") ?: DEFAULT_GOLDEN_DIRECTORY)
        if (!directory.isDirectory) {
            if (System.getProperty("jarvys.goldenLedgers.optional")?.equals("true", ignoreCase = true) == true) {
                assumeTrue("SKIPPED GoldenReaderParityTest: optional local golden-ledger directory is unavailable.", false)
                return@onDatabaseThread
            }
            throw AssertionError("Golden-ledger directory is unavailable; set jarvys.goldenLedgers or explicitly enable its optional skip.")
        }
        val ledgers = directory.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("local golden corpus must contain at least one ledger", ledgers.isNotEmpty())

        val importReports = ImportCoordinator(database).importAll(directory)
        val importedIds = importReports.filter { it.state == "done" }.map { it.conversationId }.toSet()
        val legacyFiles = File(temporary.root, "legacy-files")
        val legacyConversations = File(legacyFiles, "jarvys/conversations").apply { mkdirs() }
        ledgers.forEach { source ->
            source.copyTo(File(legacyConversations, source.name), overwrite = true)
            File(directory, source.name.removeSuffix(".jsonl") + ".meta.json")
                .takeIf(File::isFile)?.copyTo(File(legacyConversations, source.name.removeSuffix(".jsonl") + ".meta.json"), overwrite = true)
        }

        val reader = ConversationReader(database)
        val corpusCount = ledgers.size
        val largeLedgerIndexes = mutableListOf<Int>()
        ledgers.forEachIndexed { ledgerIndex, ledger ->
            val sessionId = ledger.name.removeSuffix(".jsonl")
            assertTrue("ledger index=$ledgerIndex importState=done", sessionId in importedIds)
            val legacy = legacyStore(legacyFiles)

            val legacyContext = measure { legacy.loadConversationContext(sessionId) }
            val databaseContext = measure { reader.loadConversationContext(sessionId) }
            assertListDigestParity(
                "ledgerIndex=$ledgerIndex kind=modelContext",
                legacyContext.value.map(::turnShape), databaseContext.value.map(::turnShape),
            )

            val legacyMessages = measure { legacy.readConversationMessages(sessionId) }
            val databaseMessages = measure { reader.readConversationMessages(sessionId) }
            assertListDigestParity(
                "ledgerIndex=$ledgerIndex kind=messages",
                legacyMessages.value.map(::legacyMessageShape), databaseMessages.value.map(::databaseMessageShape),
            )

            val legacyCrew = measure { legacy.readCrewMissionSnapshots(sessionId) }
            val databaseCrew = measure { reader.readCrewMissionSnapshots(sessionId) }
            assertListDigestParity(
                "ledgerIndex=$ledgerIndex kind=crewSnapshots",
                legacyCrew.value.map { it.toJson().toString() }, databaseCrew.value.map { it.toJson().toString() },
            )

            val legacyInterrupts = measure { legacy.readPendingInterruptRequests(sessionId) }
            val databaseInterrupts = measure { reader.pendingInterruptRequests(sessionId) }
            assertListDigestParity(
                "ledgerIndex=$ledgerIndex kind=pendingInterrupts",
                legacyInterrupts.value.map { listOf(it.optString("requestId"), it.optString("content"),
                    it.optBoolean("memoryDisabled"), it.optJSONArray("skills").toString(), it.optBoolean("alreadyDispatched")).toString() },
                databaseInterrupts.value.map { listOf(it.requestId, it.content, it.memoryDisabled, org.json.JSONArray(it.skills).toString(), it.alreadyDispatched).toString() },
            )

            val legacyReflection = measure { legacy.buildReflectionPayload(sessionId) }
            val databaseReflection = measure { reader.buildReflectionPayload(sessionId) }
            assertDigestParity("ledgerIndex=$ledgerIndex kind=reflection", legacyReflection.value.text, databaseReflection.value.text)
            assertDigestParity("ledgerIndex=$ledgerIndex kind=reflectionEndId", legacyReflection.value.endMessageId, databaseReflection.value.endMessageId)
            assertDigestParity("ledgerIndex=$ledgerIndex kind=reflectionCompactions", legacyReflection.value.compactionIds.toString(), databaseReflection.value.compactionIds.toString())
            assertTrue("ledgerIndex=$ledgerIndex kind=reflectionCounts", listOf(
                legacyReflection.value.completedAssistantSteps, legacyReflection.value.userCharacters,
                legacyReflection.value.messageCount, legacyReflection.value.hasMoreMessages,
            ) == listOf(
                databaseReflection.value.completedAssistantSteps, databaseReflection.value.userCharacters,
                databaseReflection.value.messageCount, databaseReflection.value.hasMoreMessages,
            ))

            if (ledger.length() >= LARGE_LEDGER_BYTES) {
                largeLedgerIndexes += ledgerIndex
                println(
                    "golden-reader ledgerIndex=$ledgerIndex bytes=${ledger.length()} " +
                        "legacyContextMs=${legacyContext.millis} legacyContextHeapDelta=${legacyContext.heapDelta} " +
                        "databaseContextMs=${databaseContext.millis} databaseContextHeapDelta=${databaseContext.heapDelta} " +
                        "legacyMessagesMs=${legacyMessages.millis} legacyMessagesHeapDelta=${legacyMessages.heapDelta} " +
                        "databaseMessagesMs=${databaseMessages.millis} databaseMessagesHeapDelta=${databaseMessages.heapDelta} " +
                        "legacyCrewMs=${legacyCrew.millis} legacyCrewHeapDelta=${legacyCrew.heapDelta} " +
                        "databaseCrewMs=${databaseCrew.millis} databaseCrewHeapDelta=${databaseCrew.heapDelta} " +
                        "messageCount=${databaseMessages.value.size} crewCount=${databaseCrew.value.size}",
                )
            }
        }
        println("golden-reader corpusLedgers=$corpusCount largeLedgers=${largeLedgerIndexes.size}")
        assertTrue("corpus should include a large ledger for memory and timing comparison", largeLedgerIndexes.isNotEmpty())
    }

    private fun legacyStore(filesDirectory: File): LocalRunStore = LocalRunStore::class.java
        .getDeclaredConstructor(File::class.java)
        .apply { isAccessible = true }
        .newInstance(filesDirectory)

    private fun <T> measure(block: () -> T): Measurement<T> {
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        val value = block()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val after = runtime.totalMemory() - runtime.freeMemory()
        return Measurement(value, elapsed, after - before)
    }

    private fun assertListDigestParity(label: String, expected: List<String>, actual: List<String>) {
        val expectedDigests = expected.map(::sha256)
        val actualDigests = actual.map(::sha256)
        val firstDifference = (0 until maxOf(expectedDigests.size, actualDigests.size))
            .firstOrNull { expectedDigests.getOrNull(it) != actualDigests.getOrNull(it) }
        assertTrue("$label firstDifferenceIndex=${firstDifference ?: -1} expectedCount=${expected.size} actualCount=${actual.size}",
            firstDifference == null)
    }

    private fun assertDigestParity(label: String, expected: String, actual: String) =
        assertTrue("$label firstDifferenceIndex=0 type=scalar", sha256(expected) == sha256(actual))

    private fun turnShape(turn: ConversationTurn): String = listOf(
        turn.kind.name, turn.role, turn.content, turn.originalMessageIndex, turn.summarizedMessageCount,
        turn.thinking, turn.imageCount,
        turn.attachments.map { listOf(it.id, it.name, it.mimeType, it.sizeBytes, it.kind.name, it.relativePath) },
        turn.toolCallId, turn.toolName,
        turn.toolCalls.map { listOf(it.id, it.name, it.arguments, it.invalidArgumentsReason) },
        turn.providerOutputItems.map { listOf(it.kind.name, it.value, it.provider, it.api, it.model) },
        turn.modelErrorCode,
    ).toString()

    private fun legacyMessageShape(row: JSONObject): String = listOf(
        row.optString("messageId"), row.optString("role"), row.optString("content"),
        row.optString("status").takeIf(String::isNotEmpty), row.optString("runId").takeIf(String::isNotEmpty),
        row.optString("userMessageId").takeIf(String::isNotEmpty), row.optLong("durationMs").takeIf { row.has("durationMs") },
        row.optString("beforeMessageId").takeIf(String::isNotEmpty), row.optString("proactiveThreadKey").takeIf(String::isNotEmpty),
        row.optString("proactiveReplyToMessageId").takeIf(String::isNotEmpty), row.optString("interruptRequestId").takeIf(String::isNotEmpty),
        row.optBoolean("mainChatAcknowledgementRequired"), row.optDouble("timestamp"),
        ChatAttachment.fromJsonArray(row.optJSONArray("attachments")),
    ).toString()

    private fun databaseMessageShape(message: StoredMessage): String = listOf(
        message.messageId, message.role, message.text, message.status, message.runId, message.ownerUserMessageId,
        message.durationMillis, message.beforeMessageId, message.proactiveThreadKey, message.proactiveReplyToMessageId,
        message.interruptRequestId, message.acknowledgementRequired, message.createdAtSeconds,
        message.attachments.map { ChatAttachment(it.attachmentId, it.name, it.mimeType, it.sizeBytes, ChatAttachment.Kind.valueOf(it.kind), it.relativePath) },
    ).toString()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class Measurement<T>(val value: T, val millis: Long, val heapDelta: Long)

    private companion object {
        const val DEFAULT_GOLDEN_DIRECTORY = "C:\\Projects\\Jarvys-out\\golden-ledgers"
        const val LARGE_LEDGER_BYTES = 48_000_000L
    }
}
