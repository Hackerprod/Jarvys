package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.CompactionCoverage
import com.jarvys.agent.chatdb.PreparedCompactionFlush
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.chatdb.withChatWriteTransaction
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompactionMemoryFlushTest {
    @get:Rule val fixture = ChatTestStore()
    @get:Rule val secrets = TestSecretStoreRule()

    private fun context(files: File) = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = files
    }

    private fun provider(reply: (List<ConversationTurn>, List<ToolSpec>) -> ModelReply) = object : ModelProviderClient {
        override fun complete(systemPrompt: String, userPrompt: String,
            images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>, sessionId: String,
            token: CancellationToken): ModelReply = error("Conversation request expected")
        override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
            images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>, sessionId: String,
            token: CancellationToken) = reply(history, tools)
    }

    private fun turn(store: LocalRunStore, session: String, role: String, text: String): ConversationTurn {
        val id = store.appendConversationMessage(session, role, text)
        return ConversationTurn(role, text, store.conversationMessageIndex(session, id).toInt())
            .withDurableMessageId(id).also {
                it.bindSource(ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE, session, id, null))
            }
    }

    @Test fun candidateDoesNotCompleteUntilExactCompactionCommitsAndNeverAdvancesExtractionCursor() {
        val files = fixture.newFilesDirectory("digest-commit")
        fixture.withLocalRunStore(files) { store ->
            val context = context(files)
            val bridge = ChatDatabaseBridge.getInstance(context)
            val session = "digest-commit-session"
            val summarized = listOf(turn(store, session, "user", "Remember the chosen local design"),
                turn(store, session, "assistant", "The local design is recorded"))
            val outside = turn(store, session, "user", "This message remains outside the summarized range")
            var factPasses = 0
            val model = CoreAgentModel(context, provider { _, tools ->
                if (tools.isEmpty()) ModelReply("{\"title\":\"Local design\",\"text\":\"The user chose a local design.\"}", emptyList())
                else { factPasses++; ModelReply("NO_REPLY", emptyList()) }
            }, session)
            val flush = PersonalMemoryFlush(context, session, "principal", model)
            val token = CancellationToken.cancellable()
            val coverage = checkNotNull(flush.coverage(summarized, token))
            assertFalse(coverage.sources.any { it.sourceId == outside.durableMessageId })
            val cursor = bridge.personalAgentExtractionCursorSync(session)
            val prepared = checkNotNull(flush.prepare(summarized, coverage, token))
            assertEquals(1, factPasses)
            assertTrue(bridge.personalAgentNeedsMemoryFlush(session))
            val committed = store.appendCompactionPrepared(session, "Local design summary", 2,
                "test", "sliding_window", 2, emptyList(), prepared.coverage, prepared, "principal")
            assertTrue(store.compactionFlushCompleted(session, committed.compaction_id))
            // Equal compaction/flush counts means the next compaction still requires a new scoped flush.
            assertEquals(cursor, bridge.personalAgentExtractionCursorSync(session))
            assertNotNull(flush.coverage(listOf(ConversationTurn.compactionSummary("Local design summary", 2).also {
                it.bindSource(ConversationTurn.Source(ConversationTurn.Source.Kind.COMPACTION,
                    session, committed.compaction_id, null))
            }), token))
        }
    }

    @Test fun missingSourceAndToggleDuringModelCannotProduceCompletionCandidate() {
        val files = fixture.newFilesDirectory("digest-incomplete")
        fixture.withLocalRunStore(files) { store ->
            val context = context(files)
            val session = "digest-incomplete-session"
            val summarized = listOf(turn(store, session, "user", "A durable request"))
            var modelCalls = 0
            val flush = PersonalMemoryFlush(context, session, "principal", CoreAgentModel(context, provider { _, _ ->
                modelCalls++
                context.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(MemoryConstants.ENABLED_KEY, false).commit()
                ModelReply("{\"title\":\"Title\",\"text\":\"Text\"}", emptyList())
            }, session))
            val token = CancellationToken.cancellable()
            assertNull(flush.coverage(listOf(ConversationTurn("user", "Unbound synthetic request")), token))
            assertEquals(0, modelCalls)
            val coverage = checkNotNull(flush.coverage(summarized, token))
            assertNull(flush.prepare(summarized, coverage, token))
            assertEquals(1, modelCalls)
            val committed = store.appendCompactionPrepared(session, "Summary without memory completion", 1,
                "test", "all", 1, emptyList(), coverage, null, "principal")
            assertFalse(store.compactionFlushCompleted(session, committed.compaction_id))
            assertTrue(ChatDatabaseBridge.getInstance(context).personalAgentNeedsMemoryFlush(session))
        }
    }

    @Test fun incompleteDigestStillExtractsAccreditedUserFactsExactlyOncePerPass() {
        val files = fixture.newFilesDirectory("digest-facts-fallback")
        fixture.withLocalRunStore(files) { store ->
            val context = context(files)
            val session = "digest-facts-fallback-session"
            val genuine = turn(store, session, "user", "An independently stored preference")
            val transcript = listOf(ConversationTurn.compactionSummary("Legacy summary without source identity", 0), genuine)
            var facts = 0
            val flush = PersonalMemoryFlush(context, session, "principal", CoreAgentModel(context, provider { history, tools ->
                assertFalse("No digest call is permitted without coverage", tools.isEmpty())
                assertEquals(listOf(genuine.content), history.map { it.content })
                facts++
                ModelReply("NO_REPLY", emptyList())
            }, session))
            val token = CancellationToken.cancellable()
            assertNull(flush.coverage(transcript, token))
            assertNull(flush.prepare(transcript, null, token))
            assertEquals(1, facts)
            assertTrue(ChatDatabaseBridge.getInstance(context).personalAgentNeedsMemoryFlush(session))
        }
    }

    @Test fun memoryOffCompactionRetainsOnlyStructuralCoverageForLaterEnabledPass() {
        val files = fixture.newFilesDirectory("digest-memory-toggle")
        fixture.withLocalRunStore(files) { store ->
            val context = context(files)
            val session = "digest-memory-toggle-session"
            val transcript = listOf(turn(store, session, "user", "A stored conversation input"))
            val preferences = context.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)
            preferences.edit().putBoolean(MemoryConstants.ENABLED_KEY, false).commit()
            val model = CoreAgentModel(context, provider { _, _ -> error("Memory off must not call the model") }, session)
            val runtime = CoreAgentRuntime(context, session, emptyList(), CorePromptBudget.standard())
            val lifecycle = checkNotNull(runtime.compactionMemoryLifecycle(model))
            val token = CancellationToken.cancellable()
            val coverage = checkNotNull(lifecycle.coverage(transcript, token))
            assertNull(lifecycle.prepare(transcript, coverage, token))
            val committed = store.appendCompactionPrepared(session, "Conversation summary", 1, "test", "all", 1,
                emptyList(), coverage, null, "principal")
            assertFalse(store.compactionFlushCompleted(session, committed.compaction_id))
            preferences.edit().putBoolean(MemoryConstants.ENABLED_KEY, true).commit()
            val summary = ConversationTurn.compactionSummary("Conversation summary", 1).also {
                it.bindSource(ConversationTurn.Source(ConversationTurn.Source.Kind.COMPACTION,
                    session, committed.compaction_id, null))
            }
            val inherited = checkNotNull(lifecycle.coverage(listOf(summary), token))
            assertEquals(coverage.coverageHash, inherited.coverageHash)
        }
    }

    @Test fun editedOrHiddenDurableInputNeverFeedsStaleTextToDigestOrFactExtraction() {
        val files = fixture.newFilesDirectory("digest-stale-input")
        fixture.withLocalRunStore(files) { store ->
            val context = context(files)
            val session = "digest-stale-input-session"
            val stale = turn(store, session, "user", "Old withdrawn preference")
            var facts = 0
            val flush = PersonalMemoryFlush(context, session, "principal", CoreAgentModel(context, provider { history, tools ->
                assertFalse("Stale transcript must not produce a digest", tools.isEmpty())
                assertEquals(listOf("Updated durable preference"), history.map { it.content })
                facts++
                ModelReply("NO_REPLY", emptyList())
            }, session))
            val database = com.jarvys.agent.chatdb.JarvysChatDatabaseProvider.getInstance(context)
            // Simulate a producer changing the durable source before this old model transcript is compacted.
            runBlocking { database.withChatWriteTransaction { database.chatSql.execSQL(
                "UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",
                arrayOf("Updated durable preference", session, stale.durableMessageId)) } }
            val token = CancellationToken.cancellable()
            assertNull(flush.coverage(listOf(stale), token))
            assertNull(flush.prepare(listOf(stale), null, token))
            assertEquals(1, facts)
            runBlocking { database.withChatWriteTransaction { database.chatSql.execSQL(
                "UPDATE messages SET hidden_kind=? WHERE conversation_id=? AND message_id=?",
                arrayOf("deleted", session, stale.durableMessageId)) } }
            assertNull(flush.coverage(listOf(stale), token))
            assertNull(flush.prepare(listOf(stale), null, token))
            assertEquals("Hidden source must not reach another model pass", 1, facts)
        }
    }

    @Test fun slidingFallbackExtractsOnlyOnceForTheFinalPlanAndReturnsBoundSummary() {
        val files = fixture.newFilesDirectory("digest-fallback")
        fixture.withLocalRunStore(files) { store ->
            val context = context(files)
            val session = "digest-fallback-session"
            val transcript = (0 until 8).map { index ->
                turn(store, session, if (index % 2 == 0) "user" else "assistant", "Local turn $index")
            }
            var summaries = 0
            var factPasses = 0
            val model = CoreAgentModel(context, provider { history, tools ->
                when {
                    history.isEmpty() -> ModelReply(if (summaries++ == 0)
                        "x".repeat(ConversationCompactionPolicy.MAX_SUMMARY_CHARS) else "Final summary", emptyList())
                    tools.isEmpty() -> ModelReply("{\"title\":\"Local discussion\",\"text\":\"A local discussion was summarized.\"}", emptyList())
                    else -> { factPasses++; ModelReply("NO_REPLY", emptyList()) }
                }
            }, session)
            val flush = PersonalMemoryFlush(context, session, "principal", model)
            var preparedSourceIds = emptySet<String>()
            val lifecycle = object : ConversationCompactor.MemoryFlush {
                override fun beforeCompact(transcript: List<ConversationTurn>, token: CancellationToken) = error("Typed prepare expected")
                override fun coverage(transcript: List<ConversationTurn>, token: CancellationToken) = flush.coverage(transcript, token)
                override fun prepare(transcript: List<ConversationTurn>, coverage: CompactionCoverage?, token: CancellationToken): PreparedCompactionFlush? {
                    preparedSourceIds = transcript.map { it.source().sourceId }.toSet()
                    return flush.prepare(transcript, coverage, token)
                }
            }
            val compacted = checkNotNull(ConversationCompactor(session, model, store, false, lifecycle)
                .compact(transcript, 4096, "test", ConversationCompactionPolicy.Mode.SLIDING_WINDOW,
                    CancellationToken.cancellable(), null))
            assertEquals(ConversationCompactionPolicy.Mode.ALL, compacted.mode)
            assertEquals(2, summaries)
            assertEquals(1, factPasses)
            assertEquals(transcript.map { it.source().sourceId }.toSet(), preparedSourceIds)
            val source = compacted.context.first().source()
            assertEquals(ConversationTurn.Source.Kind.COMPACTION, source.kind)
            assertTrue(store.compactionFlushCompleted(session, source.sourceId))
        }
    }
}
