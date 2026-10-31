package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exact typed coverage and atomic binding, without model calls or external effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompactionFlushPersistenceTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var store: LocalRunStore
    private lateinit var bridge: ChatDatabaseBridge
    private val conversation = "compaction-coverage-test"

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        store = fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        runBlocking { FreshStorageBootstrap.awaitReady(context) }
        bridge = ChatDatabaseBridge.getInstance(context)
    }

    private fun message(text: String = "A durable preference") = store.appendConversationMessage(conversation, "user", text)
    /** Simulates a durable producer edit after a previously captured coverage snapshot. */
    private fun editSource(id: String, text: String) = runBlocking {
        val database = JarvysChatDatabaseProvider.getInstance(context)
        database.withChatWriteTransaction { database.chatSql.execSQL(
            "UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",
            arrayOf(text, conversation, id)) }
    }
    private fun source(id: String) = ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE, conversation, id, "")
    private fun coverage(vararg ids: String) = requireNotNull(bridge.prepareCompactionCoverageSync(conversation, ids.map(::source)))
    private fun append(coverage: CompactionCoverage?, receipt: PreparedCompactionFlush?) = store.appendCompactionPrepared(
        conversation, "Bounded summary", 1, "manual", "all", 1, emptyList(), coverage, receipt, "principal")
    private fun candidate(coverage: CompactionCoverage, allowed: Boolean = true) = PreparedCompactionFlush(coverage, "Digest", "Context of the selected range", MemoryWritePolicy.forContext(context).captureTicket()) { allowed }
    private fun scalar(query: String): Long = runBlocking {
        JarvysChatDatabaseProvider.getInstance(context).withChatReadTransaction {
            JarvysChatDatabaseProvider.getInstance(context).chatSql.query(query).use { it.moveToFirst(); it.getLong(0) }
        }
    }

    @Test fun exactSelectedMessagesExcludeRetainedSourcesAndBindOnlyReturnedCompaction() {
        val first = message()
        message("Retained outside range")
        val snapshot = coverage(first)
        assertEquals(listOf(first), snapshot.leaves.map { it.source.sourceId })
        val saved = append(snapshot, candidate(snapshot))
        assertTrue(store.compactionFlushCompleted(conversation, saved.compaction_id))
        assertFalse(store.compactionFlushCompleted(conversation, "not-the-returned-id"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM compaction_coverage_sources"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
        assertEquals(0L, scalar("SELECT saved_turn_count FROM agent_session_state WHERE conversation_id='$conversation'"))
    }

    @Test fun changedSourceRejectsDigestButStillAppendsIncompleteSummary() {
        val id = message()
        val snapshot = coverage(id)
        editSource(id, "Changed after candidate")
        val saved = append(snapshot, candidate(snapshot))
        assertFalse(store.compactionFlushCompleted(conversation, saved.compaction_id))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM compactions"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
    }

    @Test fun disabledOrCancelledCandidateDoesNotSaveMemoryOrCompleteFlush() {
        val snapshot = coverage(message())
        val saved = append(snapshot, candidate(snapshot, allowed = false))
        assertFalse(store.compactionFlushCompleted(conversation, saved.compaction_id))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM compaction_coverage_sources"))
    }

    @Test fun repeatedIdenticalLeafRangeReusesDigestWithoutAddingRevision() {
        val id = message()
        val first = coverage(id)
        append(first, candidate(first))
        val next = coverage(id)
        assertEquals(first.coverageHash, next.coverageHash)
        assertNotEquals(first.fingerprint, next.fingerprint) // Digest CAS changed, not topic identity.
        val saved = append(next, candidate(next))
        assertTrue(store.compactionFlushCompleted(conversation, saved.compaction_id))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM memory_revisions WHERE cause='compaction_digest'"))
    }

    @Test fun ancestorWrapperPreservesLeafIdentityButLegacySummaryIsIncomplete() {
        val id = message()
        val first = coverage(id)
        val saved = append(first, candidate(first))
        val parent = ConversationTurn.Source(ConversationTurn.Source.Kind.COMPACTION, conversation, saved.compaction_id, "")
        val inherited = requireNotNull(bridge.prepareCompactionCoverageSync(conversation, listOf(parent)))
        assertEquals(first.coverageHash, inherited.coverageHash)
        assertEquals(first.leaves, inherited.leaves)
        val legacy = bridge.appendCompactionSync(CompactionInput(conversation, "Legacy", 1, summarizedMessages = 1)) as ChatDbResult.Success<Compaction>
        assertNull(bridge.prepareCompactionCoverageSync(conversation, listOf(
            ConversationTurn.Source(ConversationTurn.Source.Kind.COMPACTION, conversation, legacy.value.compaction_id, ""))))
    }

    @Test fun sourceSnapshotUsesCurrentDurableTextAndRejectsHiddenMessages() {
        val id = message("Original text")
        editSource(id, "Current durable text")
        val current = coverage(id)
        assertEquals("Current durable text", current.leaves.single().text)
        assertEquals("user", current.leaves.single().role)
        assertEquals("Current durable text", current.sourceTexts[source(id)])
        val assistant = store.appendConversationMessage(conversation, "assistant", "Later hidden", null, "", id)
        store.appendAssistantDeleted(conversation, assistant)
        assertNull(bridge.prepareCompactionCoverageSync(conversation, listOf(source(assistant))))
    }

    @Test fun sourceSnapshotCarriesTrustAndDurableOrderingInsteadOfUuidOrder() {
        val first = message("First")
        val second = message("Second")
        val snapshot = coverage(second, first)
        val ordered = snapshot.leaves.sortedWith(compareBy({ it.sortSeq }, { it.sortSub }, { it.seq }))
        assertEquals(listOf(first, second), ordered.map { it.source.sourceId })
        assertFalse(ordered.first().untrusted)
        runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction {
                db.chatSql.execSQL("INSERT INTO message_parts(conversation_id,message_id,ordinal,kind,payload) VALUES(?,?,1,?,?)",
                    arrayOf(conversation, first, ConversationInput.PROVENANCE_PART_KIND, "{}"))
            }
        }
        assertTrue(coverage(first).leaves.single().untrusted)
    }

    @Test fun missingForeignSourceNeverBecomesCompleteCoverage() {
        val id = message()
        assertNull(bridge.prepareCompactionCoverageSync(conversation, listOf(source("missing"))))
        assertNull(bridge.prepareCompactionCoverageSync(conversation, listOf(
            ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE, "other", id, ""))))
        assertNull(bridge.prepareCompactionCoverageSync(conversation, emptyList()))
    }

    @Test fun failedAppendRollsBackDigestBindingAndCounters() {
        val snapshot = coverage(message())
        val result = bridge.appendCompactionPreparedSync(CompactionInput(conversation, "summary", 1,
            summarizedMessages = 1, artifactIds = listOf("missing-artifact")), snapshot, candidate(snapshot), "principal")
        assertFalse(result is ChatDbResult.Success)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM compactions"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM compaction_flush"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
    }

    private fun batch(owner: String, batch: String, call: String = "reused-call"): Pair<String, List<ConversationTurn.Source>> {
        assertTrue(bridge.appendToolBatchSync(ModelToolBatchInput(conversation, batch, owner, 0,
            "Progress", calls = listOf(ModelCallInput(call, "write_file", "{}")))) is ChatDbResult.Success)
        val episode = bridge.personalAgentRecordEpisodeToolStartedSync(conversation, owner, call, "write_file",
            null, "allow", "{}", "intent", sourceBatchId = batch, runId = "run-$batch", processId = "fixture")
        bridge.personalAgentFinishEpisodeToolEvidenceSync(conversation, call, "error", "{}", sourceBatchId = batch, runId = "run-$batch")
        assertTrue(bridge.appendToolResultSync(ModelToolResultInput(conversation, batch, call, "write_file", "RESULT", "Failure")) is ChatDbResult.Success)
        return episode to listOf(ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH, conversation, batch, ""),
            ConversationTurn.Source(ConversationTurn.Source.Kind.BATCH_RESULT, conversation, batch, call))
    }

    @Test fun partialEpisodeWithRetainedBatchCannotBecomeCompleteAndReusedCallIdsStayScoped() {
        val owner = message()
        val first = batch(owner, "first-batch")
        val second = batch(owner, "second-batch")
        assertEquals(first.first, second.first)
        val partial = requireNotNull(bridge.prepareCompactionCoverageSync(conversation, listOf(source(owner)) + first.second))
        assertFalse(partial.episodesComplete)
        val partialSaved = append(partial, candidate(partial))
        assertFalse(store.compactionFlushCompleted(conversation, partialSaved.compaction_id))
        assertEquals(3L, scalar("SELECT COUNT(*) FROM compaction_coverage_sources"))
        val full = requireNotNull(bridge.prepareCompactionCoverageSync(conversation,
            listOf(source(owner)) + first.second + second.second))
        assertEquals(1, full.episodes.size)
        assertEquals(2, full.episodes.single().input.evidence.size)
        val saved = append(full, candidate(full))
        assertTrue(store.compactionFlushCompleted(conversation, saved.compaction_id))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM memory_links WHERE relation='part_of'"))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='episode' AND through_seq IS NOT NULL"))
    }

    @Test fun concurrentEpisodeChangeRejectsDigestWithoutErasingSummaryOrEvidence() {
        val owner = message()
        val first = batch(owner, "first-batch")
        val full = requireNotNull(bridge.prepareCompactionCoverageSync(conversation, listOf(source(owner)) + first.second))
        bridge.personalAgentSetEpisodeNarrationSync(first.first, "done", "Human title", "Human edit")
        val saved = append(full, candidate(full))
        assertFalse(store.compactionFlushCompleted(conversation, saved.compaction_id))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memory_entries WHERE kind='digest'"))
        assertEquals("Human edit", bridge.personalAgentEpisodeNarrationInputSync(first.first)!!.text)
    }

    @Test fun restoredProtocolGroupsBindExactBatchAndResultNotBareReusedCall() {
        val owner = message()
        batch(owner, "first-batch")
        batch(owner, "second-batch")
        val restored = store.loadConversationContext(conversation)
        val calls = restored.filter { it.kind == ConversationTurn.Kind.TOOL_CALLS }
        val results = restored.filter { it.kind == ConversationTurn.Kind.TOOL_RESULT }
        assertEquals(setOf("first-batch", "second-batch"), calls.map { it.source().sourceId }.toSet())
        assertEquals(setOf("first-batch", "second-batch"), results.map { it.source().sourceId }.toSet())
        assertTrue(results.all { it.source().kind == ConversationTurn.Source.Kind.BATCH_RESULT && it.source().callId == "reused-call" })
        assertEquals(source(owner), restored.first { it.role == "user" }.source())
    }

    @Test fun syntheticMissingResultNeverReceivesDurableResultProvenance() {
        val owner = message()
        assertTrue(bridge.appendToolBatchSync(ModelToolBatchInput(conversation, "unresolved", owner, 0,
            "Progress", calls = listOf(ModelCallInput("call", "write_file", "{}")))) is ChatDbResult.Success)
        val restored = store.loadConversationContext(conversation)
        assertNotNull(restored.first { it.kind == ConversationTurn.Kind.TOOL_CALLS }.source())
        assertNull(restored.first { it.kind == ConversationTurn.Kind.TOOL_RESULT }.source())
    }

    @Test fun turnCopiesSharePostCommitIdentityAndRejectRebinding() {
        val original = ConversationTurn("user", "text", 0)
        val copy = original.withDurableMessageId("first")
        assertNull(original.source())
        val identity = source("first")
        original.bindSource(identity)
        assertEquals(identity, copy.source())
        assertTrue(runCatching { copy.bindSource(source("second")) }.isFailure)
    }
}
