package com.jarvys.agent

import com.jarvys.agent.device.ScreenData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConversationCompactionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun messages(count: Int, chars: Int = 20) = (0 until count).map { index ->
        ConversationTurn(if (index % 2 == 0) "user" else "assistant", "m$index " + "x".repeat(chars), index)
    }

    @Test
    fun providerPressureReserveUsesExactSmall128kAndMillionTokenRules() {
        assertEquals(819, ConversationCompactionPolicy.reserveTokens(4_096))
        assertEquals(3_277, ConversationCompactionPolicy.pressureThreshold(4_096))
        assertEquals(16_384, ConversationCompactionPolicy.reserveTokens(128 * 1_024))
        assertEquals(114_688, ConversationCompactionPolicy.pressureThreshold(128 * 1_024))
        assertEquals(16_384, ConversationCompactionPolicy.reserveTokens(1_000_000))
        assertEquals(983_616, ConversationCompactionPolicy.pressureThreshold(1_000_000))
        assertFalse(ConversationCompactionPolicy.shouldCompact(3_277, 4_096))
        assertTrue(ConversationCompactionPolicy.shouldCompact(3_278, 4_096))
        assertEquals(1, ConversationCompactionPolicy.estimateTokens("áá"))
    }

    @Test
    fun slidingRequiresFourMessagesCutsAtAssistantAndExpandsEvictionWhenNeeded() {
        assertEquals(ConversationCompactionPolicy.Mode.ALL,
            ConversationCompactionPolicy.planSliding(messages(3), 32_768, 0.3).mode)
        val initial = ConversationCompactionPolicy.planSliding(messages(12, 3_000), 20_000, 0.3)
        assertEquals(ConversationCompactionPolicy.Mode.SLIDING_WINDOW, initial.mode)
        assertTrue(initial.cutoffIndex > 0)
        assertEquals("assistant", initial.keep.first().role)

        val largeTranscript = messages(20, 10_000)
        val expanded = ConversationCompactionPolicy.planSliding(largeTranscript, 12_000, 0.3)
        assertEquals(ConversationCompactionPolicy.Mode.SLIDING_WINDOW, expanded.mode)
        assertTrue("Eviction should grow by 0.1 steps until retained context fits", expanded.cutoffIndex > 10)
        assertTrue(ConversationCompactionPolicy.estimateTurnsTokens(expanded.keep) < 12_000)
        assertEquals(ConversationCompactionPolicy.Mode.ALL,
            ConversationCompactionPolicy.planSliding(messages(12, 10_000), 100, 0.3).mode)
    }

    @Test
    fun callResultPairsAndPendingToolCallsAreNeverSplitAcrossPlanBoundaries() {
        val call = ModelReply.Call("call-1", "lookup", mapOf("q" to "x"))
        val transcript = listOf(
            ConversationTurn("user", "start", 0),
            ConversationTurn.toolCalls("", listOf(call)),
            ConversationTurn.toolResult("call-1", "lookup", "result"),
            ConversationTurn("assistant", "done", 1),
            ConversationTurn("user", "next", 2),
            ConversationTurn("assistant", "next answer", 3),
            ConversationTurn("user", "last", 4),
            ConversationTurn("assistant", "last answer", 5),
        )
        val sliding = ConversationCompactionPolicy.planSliding(transcript, 32_768, 0.3)
        val callIsSummarized = sliding.summarize.any { it.kind == ConversationTurn.Kind.TOOL_CALLS }
        val resultIsSummarized = sliding.summarize.any { it.kind == ConversationTurn.Kind.TOOL_RESULT }
        assertEquals(callIsSummarized, resultIsSummarized)

        val pendingCall = ModelReply.Call("pending-call", "lookup", mapOf("q" to "pending"))
        val pending = transcript.dropLast(1) + ConversationTurn.toolCalls("pending", listOf(pendingCall))
        val all = ConversationCompactionPolicy.planAll(pending)
        assertEquals(listOf(ConversationTurn.Kind.TOOL_CALLS), all.keep.map { it.kind })
        assertFalse(all.summarize.any { turn -> turn.toolCalls.any { it.id == "pending-call" } })
    }

    @Test
    fun summaryPromptsKeepRequiredSectionsLimitsAndTrustInstructions() {
        val sections = listOf("High level goals", "What happened", "Important details", "Errors and fixes",
            "Current state", "Optional Next", "Lookup hints")
        sections.forEach {
            assertTrue("all prompt missing $it", ConversationCompactionPolicy.ALL_PROMPT.contains(it))
            assertTrue("sliding prompt missing $it", ConversationCompactionPolicy.SLIDING_PROMPT.contains(it))
        }
        assertTrue(ConversationCompactionPolicy.ALL_PROMPT.contains("under 500 words"))
        assertTrue(ConversationCompactionPolicy.SLIDING_PROMPT.contains("under 300 words"))
        assertEquals(listOf(120_000, 90_000, 60_000, 40_000, 25_000, 15_000, 10_000, 6_000, 4_000, 2_000),
            ConversationCompactionPolicy.TRANSCRIPT_RETRY_CHAR_LIMITS.toList())
        listOf(ConversationCompactionPolicy.ALL_PROMPT, ConversationCompactionPolicy.SLIDING_PROMPT).forEach {
            assertTrue(it.contains("untrusted data"))
            assertTrue(it.contains("Do not include secrets or credentials"))
        }
        assertEquals(50_000 + ConversationCompactionPolicy.SUMMARY_TRUNCATION_SUFFIX.length,
            ConversationCompactionPolicy.truncateSummary("s".repeat(50_100)).length)
    }

    @Test
    fun transcriptFormatterIncludesThinkingImagesToolCallsAndTruncatesToolReturns() {
        val tool = ModelReply.Call("c1", "read", mapOf("path" to "/x"))
        val transcript = listOf(
            ConversationTurn.messageWithRichParts("assistant", "Visible", "reasoning", 0, -1),
            ConversationTurn.messageWithRichParts("user", "look", "", 2, -1),
            ConversationTurn.toolCalls("checking", listOf(tool)),
            ConversationTurn.toolResult("c1", "read", "z".repeat(2_010)),
        )
        val formatted = ConversationCompactionPolicy.formatTranscript(transcript, 2_000)
        assertTrue(formatted.contains("[thinking] reasoning"))
        assertTrue(formatted.contains("[2 images omitted]"))
        assertTrue(formatted.contains("read({path=/x})"))
        assertTrue(formatted.contains("truncated 10 chars"))
        assertTrue(formatted.contains("{call_id=c1, name=read}"))
    }

    @Test
    fun originalHistoryIsAppendOnlyAndLatestOfTwoCompactionsReconstructsContext() {
        val files = temporaryFolder.newFolder("conversation-files")
        val store = LocalRunStore(files)
        val session = "compact-session"
        store.appendConversationMessage(session, "user", "one")
        store.appendConversationMessage(session, "assistant", "two")
        store.appendConversationMessage(session, "user", "three")
        store.appendConversationMessage(session, "assistant", "four")
        assertEquals(4, store.loadConversationContext(session).size)

        store.appendCompaction(session, "summary one", 2, "auto_preflight", "sliding_window", 2)
        assertFalse(File(files, "jarvys/conversations/$session.jsonl").readText().contains("tokensBefore"))
        val afterFirst = store.loadConversationContext(session)
        assertTrue(afterFirst.first().content.contains("summary one"))
        assertEquals(listOf(2, 3), afterFirst.drop(1).map { it.originalMessageIndex })
        store.appendConversationMessage(session, "user", "five")
        store.appendConversationMessage(session, "assistant", "six")
        store.appendCompaction(session, "summary two includes summary one", 4,
            "auto_post_turn", "all", 4)
        val afterSecond = store.loadConversationContext(session)
        assertTrue(afterSecond.first().content.contains("summary two includes summary one"))
        assertEquals(listOf(4, 5), afterSecond.drop(1).map { it.originalMessageIndex })
        assertEquals(6, store.readConversationMessages(session).size)
        assertEquals(8, store.readConversationTimeline(session).size)
        assertTrue(store.hasPendingCompactionReflection(session))
        store.acknowledgePendingCompactionReflection(session)
        assertFalse(store.hasPendingCompactionReflection(session))
        assertFalse(File(files, "jarvys/memory").exists())
    }

    @Test
    fun backCompatibilityAndAtomicAppendLeaveValidJsonlAndManualFields() {
        val store = LocalRunStore(temporaryFolder.newFolder("legacy-files"))
        store.appendConversationMessage("legacy", "user", "kept as before")
        val legacy = store.loadConversationContext("legacy")
        assertEquals(1, legacy.size)
        assertEquals("kept as before", legacy.single().content)
        store.appendCompaction("legacy", "summary", 1, "manual", "all", 1)
        assertTrue(store.loadConversationContext("legacy").single().content.contains("summary"))
        assertEquals(1, store.readConversationMessages("legacy").size)
        val record = store.readConversationTimeline("legacy").last()
        assertEquals("compaction", record.kind)
        assertEquals(1, record.compactionMessageCount)
    }

    @Test
    fun openRouterWindowParserRequiresExactConfiguredModelAndRealMetadata() {
        val catalog = """{"data":[{"id":"vendor/model-a","context_length":128000},{"id":"vendor/no-limit"}]}"""
        assertEquals(128_000, ProviderContextWindowResolver.parseOpenRouterContextWindow(catalog, "vendor/model-a"))
        assertNull(ProviderContextWindowResolver.parseOpenRouterContextWindow(catalog, "vendor/model-b"))
        assertNull(ProviderContextWindowResolver.parseOpenRouterContextWindow(catalog, "vendor/no-limit"))
        assertEquals(32_768, ProviderContextWindowResolver.FALLBACK_CONTEXT_WINDOW)
    }

    @Test
    fun overflowWhileSummarizingRetriesAtSpecifiedTranscriptSizesAndPersistsCappedSummary() {
        val attemptedSizes = mutableListOf<Int>()
        val provider = FakeProvider { _, _, userPrompt, _, _, _ ->
            attemptedSizes += userPrompt.length
            if (userPrompt.length > 70_000) throw IllegalStateException("Provider context window exceeded")
            ModelReply("s".repeat(60_000), emptyList())
        }
        val files = temporaryFolder.newFolder("summary-files")
        val store = LocalRunStore(files)
        val session = "summary-session"
        repeat(4) { index -> store.appendConversationMessage(session, if (index % 2 == 0) "user" else "assistant", "original-$index") }
        val compactor = ConversationCompactor(session, CoreAgentModel(provider, session), store)
        val transcript = messages(4, 50_000)
        val result = compactor.compact(transcript, 1_000_000, "overflow",
            ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null)
        assertNotNull(result)
        assertTrue(attemptedSizes.size >= 3)
        assertTrue(attemptedSizes.zipWithNext().all { (left, right) -> right < left })
        assertTrue(attemptedSizes.drop(1).first() <= 120_000 + 200)
        assertTrue(attemptedSizes.last() < 70_000)
        assertEquals(50_000 + ConversationCompactionPolicy.SUMMARY_TRUNCATION_SUFFIX.length, result!!.summary.length)
        assertEquals(1, store.readConversationTimeline(session).count { it.kind == "compaction" })
        assertEquals(4, store.readConversationMessages(session).size)
    }

    @Test
    fun stopDuringSummaryDoesNotAppendPartialCompactionRecord() {
        val files = temporaryFolder.newFolder("cancel-files")
        val store = LocalRunStore(files)
        val session = "cancel-session"
        repeat(4) { index -> store.appendConversationMessage(session, if (index % 2 == 0) "user" else "assistant", "message-$index") }
        val provider = FakeProvider { _, _, _, _, _, _ ->
            Thread.currentThread().interrupt()
            ModelReply("summary", emptyList())
        }
        val compactor = ConversationCompactor(session, CoreAgentModel(provider, session), store)
        try {
            runCatching {
                compactor.compact(messages(4), 32_768, "manual", ConversationCompactionPolicy.Mode.ALL,
                    CancellationToken.uncancellable(), null)
            }.onSuccess { error("STOP should cancel before the JSONL append") }
            assertEquals(4, store.readConversationMessages(session).size)
            assertTrue(store.readConversationTimeline(session).none { it.kind == "compaction" })
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun automaticPreflightPostTurnOverflowAndManualTriggersUseConfiguredFakeModels() {
        val scenarios = listOf("auto_preflight", "auto_post_turn", "overflow", "manual")
        scenarios.forEach { scenario ->
            val files = temporaryFolder.newFolder("trigger-$scenario")
            val store = LocalRunStore(files)
            val session = "session-$scenario"
            repeat(4) { index ->
                store.appendConversationMessage(session, if (index % 2 == 0) "user" else "assistant",
                    if (scenario == "auto_preflight") "large-" + "x".repeat(12_000) else "message-$index")
            }
            store.appendConversationMessage(session, "user", "current request")
            val summaryModel = CoreAgentModel(FakeProvider { _, _, _, _, _, _ -> ModelReply("summary", emptyList()) }, session)
            val compactor = ConversationCompactor(session, summaryModel, store)
            var requests = 0
            val window = if (scenario == "auto_preflight") 4_096 else 32_768
            val model = object : CoreAgentLoop.Model {
                override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                      tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                    requests++
                    if (scenario == "overflow" && requests == 1) throw IllegalStateException("context window exceeded")
                    return if (scenario == "auto_post_turn") ModelReply("answer", emptyList(), "", null, "fake", 30_000)
                    else ModelReply("answer", emptyList())
                }
                override fun contextWindow(token: CancellationToken) = window
            }
            if (scenario == "manual") {
                compactor.compact(store.loadConversationContext(session), window, "manual",
                    ConversationCompactionPolicy.Mode.SLIDING_WINDOW, CancellationToken.uncancellable(), null)
            } else {
                CoreAgentLoop(model, CoreToolRegistry(emptyList()), "system prompt", session,
                    CorePromptBudget.standard(), compactor).run("current request",
                    store.loadConversationContext(session).dropLast(1), CancellationToken.uncancellable(), null)
            }
            val ledger = File(files, "jarvys/conversations/$session.jsonl")
            val record = ledger.readLines().map(::JSONObject).last { it.optString("type") == "compaction" }
            assertEquals(scenario, record.optString("trigger"))
            assertEquals(1, store.readConversationTimeline(session).count { it.kind == "compaction" })
            if (scenario == "overflow") assertEquals(2, requests)
        }
    }

    @Test
    fun providerOverflowCompactsAndRetriesAtMostThreeTimes() {
        val files = temporaryFolder.newFolder("overflow-limit-files")
        val store = LocalRunStore(files)
        val session = "overflow-limit"
        repeat(4) { index -> store.appendConversationMessage(session, if (index % 2 == 0) "user" else "assistant", "m$index") }
        val compactor = ConversationCompactor(session,
            CoreAgentModel(FakeProvider { _, _, _, _, _, _ -> ModelReply("summary", emptyList()) }, session), store)
        var calls = 0
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                calls++
                throw IllegalStateException("context window exceeded")
            }
            override fun contextWindow(token: CancellationToken) = 32_768
        }
        runCatching {
            CoreAgentLoop(model, CoreToolRegistry(emptyList()), "system", session,
                CorePromptBudget.standard(), compactor).run("next", store.loadConversationContext(session),
                CancellationToken.uncancellable(), null)
        }.onSuccess { error("Persistent provider overflow should fail after the three bounded compactions") }
        assertEquals(4, calls)
        assertEquals(3, store.readConversationTimeline(session).count { it.kind == "compaction" })
    }

    @Test
    fun concurrentCompactionForSameSessionIsRejectedUntilCurrentSummaryCommits() {
        val files = temporaryFolder.newFolder("concurrent-files")
        val store = LocalRunStore(files)
        val session = "same-session"
        repeat(4) { index -> store.appendConversationMessage(session, if (index % 2 == 0) "user" else "assistant", "m$index") }
        val summaryStarted = CountDownLatch(1)
        val releaseSummary = CountDownLatch(1)
        val provider = FakeProvider { _, _, _, _, _, _ ->
            summaryStarted.countDown()
            check(releaseSummary.await(5, TimeUnit.SECONDS))
            ModelReply("summary", emptyList())
        }
        val compactor = ConversationCompactor(session, CoreAgentModel(provider, session), store)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                compactor.compact(store.loadConversationContext(session), 32_768, "manual",
                    ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null)
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        assertTrue(summaryStarted.await(2, TimeUnit.SECONDS))
        val rejected = runCatching {
            compactor.compact(store.loadConversationContext(session), 32_768, "manual",
                ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null)
        }.exceptionOrNull()
        assertTrue(rejected?.message.orEmpty().contains("already running"))
        releaseSummary.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, store.readConversationTimeline(session).count { it.kind == "compaction" })
    }

    private class FakeProvider(
        private val completion: (String, List<ConversationTurn>, String, List<ToolSpec>, String, CancellationToken) -> ModelReply,
    ) : ModelProviderClient {
        override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>, tools: List<ToolSpec>,
                              sessionId: String, token: CancellationToken): ModelReply =
            completion(systemPrompt, emptyList(), userPrompt, tools, sessionId, token)

        override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                                          images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String,
                                          token: CancellationToken): ModelReply =
            completion(systemPrompt, history, userPrompt, tools, sessionId, token)
    }
}
