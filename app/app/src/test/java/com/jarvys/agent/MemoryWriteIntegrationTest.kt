package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.device.ScreenData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room/bridge boundaries; latches control ordering, timeouts only prevent a hung test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoryWriteIntegrationTest {
    @get:Rule val fixture = ChatTestStore()
    @get:Rule val secrets = TestSecretStoreRule()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private lateinit var store: LocalRunStore
    private lateinit var policy: MemoryWritePolicy
    private val conversation = "memory-write-integration"
    private val token get() = CancellationToken.cancellable()

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        store = fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        runBlocking { SingleSessionReset.awaitReady(context) }
        bridge = ChatDatabaseBridge.getInstance(context)
        assertTrue(bridge.ensureConversationSync(conversation) is ChatDbResult.Success)
        policy = MemoryWritePolicy.forContext(context)
        policy.setEnabledBlocking(true)
    }

    private fun await(latch: CountDownLatch) = assertTrue("Missing concurrency checkpoint", latch.await(10, TimeUnit.SECONDS))
    private fun scalar(query: String): Long = runBlocking {
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatReadTransaction { db.chatSql.query(query).use { assertTrue(it.moveToFirst()); it.getLong(0) } }
    }
    private fun coverage(): CompactionCoverage {
        val id = store.appendConversationMessage(conversation, "user", "Selected durable context")
        return requireNotNull(bridge.prepareCompactionCoverageSync(conversation,
            listOf(ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE, conversation, id, ""))))
    }
    private fun append(candidate: PreparedCompactionFlush) = store.appendCompactionPrepared(
        conversation, "Structural summary", 1, "manual", "all", 1, emptyList(),
        candidate.coverage, candidate, "principal")
    private fun startEpisode(call: String): String = bridge.personalAgentRecordEpisodeToolStartedSync(
        conversation, "request-$call", call, "write_file", null, "allow", "{}", "started")
    private fun finishEpisode(call: String) = bridge.personalAgentFinishEpisodeToolEvidenceSync(conversation, call, "ok", "{}")
    private fun saveAutomatic(ticket: MemoryWriteGate.Ticket, text: String) =
        bridge.personalAgentSaveMemoryAutomatic(ticket, token, text, "curated", null, 0.7, null, MemorySaveOptions())

    @Test fun narrationModelDoesNotBlockOffAndItsLateOutputCannotCommit() {
        val episode = startEpisode("narration")
        finishEpisode("narration")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                error("Conversation completion expected")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                entered.countDown()
                await(release)
                return ModelReply("""{"title":"Late title","narration":"Late generated narration"}""", emptyList())
            }
        }
        val narrator = EpisodeNarrator(context, conversation, CoreAgentModel(context, provider, conversation))
        val ticket = policy.captureTicket()
        try {
            val narration = workers.submit<Boolean> { narrator.narratePending(token, listOf(episode), ticket) }
            await(entered)
            // Must finish while the model is still blocked, not merely after its reply.
            workers.submit { policy.setEnabledBlocking(false) }.get(10, TimeUnit.SECONDS)
            assertFalse(policy.isEnabled())
            assertFalse(narration.isDone)
            release.countDown()
            assertFalse(narration.get(10, TimeUnit.SECONDS))
            val queued = bridge.personalAgentPendingEpisodeNarrationsSync(conversation, listOf(episode)).single()
            assertEquals("pending", queued.narration)
            assertNotEquals("Late title", queued.title)
            assertNotEquals("Late generated narration", queued.text)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun offOnInvalidatesOldToolsetAndNewToolsetCanPersist() {
        val old = PersonalMemoryTools.create(context, "request", conversation).single { it.declaration().name == "memory_save" }
        policy.setEnabledBlocking(false)
        policy.setEnabledBlocking(true)
        val arguments = mapOf<String, Any>("text" to "A new local preference", "layer" to "curated", "kind" to "preference")
        val stale = old.execute(arguments, token)
        assertFalse(stale.success)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE text='A new local preference'"))
        val fresh = PersonalMemoryTools.create(context, "request", conversation).single { it.declaration().name == "memory_save" }
        val saved = fresh.execute(arguments, token)
        assertTrue(saved.content, saved.success)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM memory_entries WHERE text='A new local preference' AND deleted_at IS NULL"))
    }

    @Test fun explicitHumanSaveAndForgetRemainAllowedWhileAutomaticMemoryIsOff() {
        val ticket = policy.captureTicket()
        policy.setEnabledBlocking(false)
        assertThrows(MemoryWriteGate.RejectedException::class.java) { saveAutomatic(ticket, "Blocked automatic entry") }
        val human = bridge.personalAgentSaveMemory("Explicit user entry", "curated", null, 0.7, null)
        assertEquals("saved", human.status)
        assertTrue(bridge.personalAgentBrowse().any { it.id == human.id })
        bridge.personalAgentForget(requireNotNull(human.id))
        assertFalse(bridge.personalAgentBrowse().any { it.id == human.id })
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE text='Blocked automatic entry'"))
    }

    @Test fun staleCursorCannotAdvanceButAlreadyStartedCausalEvidenceCanFinishWhileOff() {
        val episode = startEpisode("causal")
        val before = bridge.personalAgentExtractionCursorSync(conversation)
        val ticket = policy.captureTicket()
        policy.setEnabledBlocking(false)
        assertThrows(MemoryWriteGate.RejectedException::class.java) {
            bridge.personalAgentAdvanceExtractionCursorAutomatic(ticket, token, conversation, before + 10)
        }
        assertEquals(before, bridge.personalAgentExtractionCursorSync(conversation))
        finishEpisode("causal")
        val input = requireNotNull(bridge.personalAgentEpisodeNarrationInputSync(episode))
        assertTrue(input.evidence.any { it.outcome == "ok" })
        assertFalse(input.evidence.any { it.outcome == "started" })
        assertFalse(policy.isEnabled())
    }

    @Test fun staleCompactionCandidateStillAppendsStructuralCoverageWithoutDigest() {
        val snapshot = coverage()
        val candidate = PreparedCompactionFlush(snapshot, "Digest", "Selected context", policy.captureTicket()) { true }
        policy.setEnabledBlocking(false)
        policy.setEnabledBlocking(true)
        val committed = append(candidate)
        assertFalse(store.compactionFlushCompleted(conversation, committed.compaction_id))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM compactions"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM compaction_coverage_sources"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
    }

    @Test fun lifecycleKeepsItsOriginalTicketAcrossCoverageOffOnAndLatePrepare() {
        val id = store.appendConversationMessage(conversation, "user", "Context captured before the preference changed")
        val transcript = listOf(ConversationTurn("user", "Context captured before the preference changed")
            .withDurableMessageId(id).also {
                it.bindSource(ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE, conversation, id, ""))
            })
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                error("Conversation completion expected")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken) =
                if (tools.isEmpty()) ModelReply("""{"title":"Late digest","text":"Old phase context"}""", emptyList())
                else ModelReply("NO_REPLY", emptyList())
        }
        val runtime = CoreAgentRuntime(context, conversation, emptyList(), CorePromptBudget.standard())
        val lifecycle = requireNotNull(runtime.compactionMemoryLifecycle(CoreAgentModel(context, provider, conversation)))
        val phaseToken = token
        val snapshot = requireNotNull(lifecycle.coverage(transcript, phaseToken))
        val cursor = bridge.personalAgentExtractionCursorSync(conversation)
        policy.setEnabledBlocking(false)
        policy.setEnabledBlocking(true)
        // Do not construct another lifecycle: a later stage must not silently mint a fresh ticket.
        val candidate = lifecycle.prepare(transcript, snapshot, phaseToken)
        val committed = store.appendCompactionPrepared(conversation, "Safe structural summary", 1,
            "manual", "all", 1, emptyList(), snapshot, candidate, "principal")
        assertFalse(store.compactionFlushCompleted(conversation, committed.compaction_id))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM compaction_coverage_sources"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
        assertEquals(cursor, bridge.personalAgentExtractionCursorSync(conversation))
    }

    @Test fun admittedCompactionCommitsBeforeConcurrentOffAcknowledgesWithoutDeadlock() {
        val snapshot = coverage()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offAttempt = CountDownLatch(1)
        val offReturned = AtomicBoolean(false)
        val offThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val gateLock = MemoryWriteGate::class.java.getDeclaredField("lock").also { it.isAccessible = true }
            .get(policy.gate()) as java.util.concurrent.locks.ReentrantLock
        val candidate = PreparedCompactionFlush(snapshot, "Digest", "Selected context", policy.captureTicket()) {
            entered.countDown()
            await(release)
            assertFalse(offReturned.get())
            true
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val committing = workers.submit<Compaction> { append(candidate) }
            await(entered)
            val off = workers.submit {
                offThread.set(Thread.currentThread())
                offAttempt.countDown()
                policy.setEnabledBlocking(false)
                offReturned.set(true)
            }
            await(offAttempt)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!gateLock.hasQueuedThread(offThread.get()) && !off.isDone && System.nanoTime() < deadline) {
                Thread.yield()
            }
            if (off.isDone) off.get(10, TimeUnit.SECONDS) // Preserve any worker assertion/exception.
            assertTrue("OFF must wait on the admitted compaction gate", gateLock.hasQueuedThread(offThread.get()))
            assertFalse(offReturned.get())
            release.countDown()
            val committed = committing.get(10, TimeUnit.SECONDS)
            off.get(10, TimeUnit.SECONDS)
            assertTrue(offReturned.get())
            assertFalse(policy.isEnabled())
            assertTrue(store.compactionFlushCompleted(conversation, committed.compaction_id))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
        } finally { release.countDown(); workers.shutdownNow() }
    }
}
