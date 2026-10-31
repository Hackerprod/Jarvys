package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.AppendMessageInput
import com.jarvys.agent.chatdb.MessagePartInput
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.chatdb.withChatWriteTransaction
import kotlinx.coroutines.runBlocking
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Queue and narration state tests; effect-producer integration is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpisodeNarrationTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private val central = WorkspaceIdentity.CENTRAL_SESSION

    @Before fun prepare() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        bridge = ChatDatabaseBridge.getInstance(context)
        assertTrue(bridge.ensureConversationSync(central) is ChatDbResult.Success)
        context.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(MemoryConstants.ENABLED_KEY, true).commit()
    }

    private fun start(call: String, connector: String? = null): String =
        bridge.personalAgentRecordEpisodeToolStartedSync(central, "request", call, "write_file", connector,
            "allow", """{"classification":"WRITE"}""", "started")
    private fun finish(call: String, outcome: String) =
        bridge.personalAgentFinishEpisodeToolEvidenceSync(central, call, outcome, "{}")
    private fun queued() = bridge.personalAgentPendingEpisodeNarrationsSync(central).map { it.id }

    @Test fun scopedQueueKeepsEligibilityConversationAndExactMembership() {
        fun episode(source: String, outcome: String, conversation: String = central): String {
            val id = bridge.personalAgentRecordEpisodeToolStartedSync(conversation, source, source,
                "write_file", null, "allow", "{}", "started")
            if (outcome != "started") bridge.personalAgentFinishEpisodeToolEvidenceSync(conversation, source, outcome, "{}")
            return id
        }
        val eligible = episode("selected", "ok")
        val outside = episode("outside", "ok")
        val active = episode("active", "ok")
        assertEquals(active, bridge.personalAgentRecordEpisodeToolStartedSync(central, "active", "still-active",
            "write_file", null, "allow", "{}", "started"))
        val failed = episode("failed", "error")
        val done = episode("done", "ok")
        bridge.personalAgentSetEpisodeNarrationSync(done, "done", "Done", "Already narrated")
        val forgotten = episode("forgotten", "ok")
        bridge.personalAgentForget(forgotten)
        val foreignConversation = "different-conversation"
        assertTrue(bridge.ensureConversationSync(foreignConversation) is ChatDbResult.Success)
        val foreign = episode("foreign", "ok", foreignConversation)
        assertEquals(listOf(eligible), bridge.personalAgentPendingEpisodeNarrationsSync(central,
            listOf(eligible, eligible, active, failed, done, forgotten, foreign, "missing")).map { it.id })
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central, emptyList()).isEmpty())
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).any { it.id == outside })
        assertEquals("none", bridge.personalAgentPendingEpisodeNarrationsSync(central).single { it.id == outside }.narration)
    }

    @Test fun scopedQueueChunksDoNotTruncateOrChangeGlobalNullAndTieOrdering() = runBlocking {
        val ids = (0..3).map { n ->
            val call = "ordered-$n"
            val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, call, call,
                "write_file", null, "allow", "{}", "started")
            finish(call, "ok")
            id
        }
        val database = JarvysChatDatabaseProvider.getInstance(context)
        database.withChatWriteTransaction {
            ids.forEachIndexed { index, id -> database.chatSql.execSQL(
                "UPDATE memory_entries SET event_at=? WHERE id=?", arrayOf(if (index == 2) null else 10L, id)) }
        }
        // Real IDs straddle chunk boundaries; missing IDs must not broaden to the global queue.
        val scope = listOf(ids[3]) + (0..398).map { "absent-$it" } + listOf(ids[1], ids[2], ids[0], ids[3])
        val expected = listOf(ids[2]) + ids.filterIndexed { index, _ -> index != 2 }.sorted()
        assertEquals(expected, bridge.personalAgentPendingEpisodeNarrationsSync(central, scope).map { it.id })
    }

    @Test fun scopedNarratorNeverCallsModelForEmptyMissingOrOutsideEpisodes() {
        val selected = bridge.personalAgentRecordEpisodeToolStartedSync(central, "selected-request", "selected-call",
            "write_file", null, "allow", "{}", "started")
        finish("selected-call", "ok")
        val outside = bridge.personalAgentRecordEpisodeToolStartedSync(central, "outside-request", "outside-call",
            "write_file", null, "allow", "{}", "started")
        finish("outside-call", "ok")
        var calls = 0
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                throw AssertionError("Must use conversation completion")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                calls++
                return ModelReply("""{"title":"Selected","narration":"One recorded effect."}""", emptyList())
            }
        }
        val narrator = EpisodeNarrator(context, central, CoreAgentModel(context, provider, central))
        val token = CancellationToken.cancellable()
        assertTrue(narrator.narratePending(token, emptyList()))
        assertTrue(narrator.narratePending(token, listOf("missing")))
        assertEquals(0, calls)
        assertTrue(narrator.narratePending(token, listOf(selected, selected)))
        assertEquals(1, calls)
        assertEquals(listOf(outside), queued())
        assertEquals("none", bridge.personalAgentPendingEpisodeNarrationsSync(central).single().narration)
    }

    @Test fun exactConnectionObservationDoesNotMakeFailedOrInFlightWriteNarratable() {
        bridge.recordConnectorStateEventSync("gmail", true)
        val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, "request", "failed", "write_file",
            "gmail", null, """{"classification":"WRITE"}""", "intent")
        assertTrue(bridge.personalAgentEpisodeNarrationInputSync(id)!!.evidence.none { it.refKind == "connector_event" })
        assertTrue(queued().isEmpty())
        val observation = com.jarvys.agent.connectors.ConnectionObservationTestSupport.observation("gmail", true)
        bridge.personalAgentAdmitEpisodeToolEvidenceSync(central, "failed", "allow", "{}",
            connectionObservation = observation)
        val causal = bridge.personalAgentEpisodeNarrationInputSync(id)!!.evidence.single { it.refKind == "connector_event" }
        assertEquals(observation.eventId(), causal.refId)
        assertEquals("local_choice", causal.outcome)
        assertNull(causal.authorization)
        assertTrue(queued().isEmpty())
        finish("failed", "error")
        assertTrue(queued().isEmpty())
    }

    @Test fun successfulWriteWaitsForOtherEffectsOfSameRequestToClose() {
        val id = start("one")
        start("two")
        finish("one", "ok")
        assertTrue(queued().isEmpty())
        finish("two", "uncertain")
        assertEquals(listOf(id), queued())
    }

    @Test fun staleNarrationCannotOverwriteNewEvidenceOrReviveForgottenEpisode() {
        val id = start("one")
        finish("one", "ok")
        val snapshot = bridge.personalAgentEpisodeNarrationInputSync(id)!!
        start("two")
        assertFalse(bridge.personalAgentCommitEpisodeNarrationSync(id, snapshot, "done", "Old", "Old result"))
        finish("two", "ok")
        val latest = bridge.personalAgentEpisodeNarrationInputSync(id)!!
        bridge.personalAgentForget(id)
        assertFalse(bridge.personalAgentCommitEpisodeNarrationSync(id, latest, "done", "Gone", "Gone result"))
    }

    @Test fun staleNarrationCannotOverwriteManualMemoryCorrection() {
        val id = start("one")
        finish("one", "ok")
        val expected = bridge.personalAgentEpisodeNarrationInputSync(id)!!
        val corrected = bridge.personalAgentUpdate(id, com.jarvys.agent.chatdb.MemoryNamespace.CENTRAL,
            com.jarvys.agent.chatdb.MemoryUpdateOptions(content = "User-corrected narrative"))
        assertEquals(id, corrected.id)
        assertFalse(bridge.personalAgentCommitEpisodeNarrationSync(id, expected, "done", "Generated", "Stale narrative"))
        assertEquals("User-corrected narrative", bridge.personalAgentEpisodeNarrationInputSync(corrected.id)!!.text)
        assertEquals(expected.evidence, bridge.personalAgentEpisodeNarrationInputSync(corrected.id)!!.evidence)
    }

    @Test fun changedSourceAndFinalBodiesInvalidateSnapshotEvenWithSameMessageIds() {
        fun append(role: String, text: String, owner: String? = null): String {
            val result = bridge.appendMessageSync(AppendMessageInput(central, role,
                listOf(MessagePartInput("text", text)), status = if (role == "assistant") "COMPLETED" else null,
                ownerUserMessageId = owner)) as ChatDbResult.Success
            return result.value.messageId
        }
        // Simulate a durable correction directly: normal streaming snapshots intentionally reject
        // user rows and ignore completed assistant rows, and must not be relaxed for this CAS test.
        fun update(id: String, text: String) = runBlocking {
            val database = JarvysChatDatabaseProvider.getInstance(context)
            database.withChatWriteTransaction {
                database.chatSql.execSQL(
                    "UPDATE message_parts SET payload=? WHERE conversation_id=? AND message_id=? AND kind='text'",
                    arrayOf(text, central, id))
                database.chatSql.query(
                    "SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind='text'",
                    arrayOf(central, id)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(text, cursor.getString(0))
                }
            }
        }
        val request = append("user", "original request")
        val final = append("assistant", "original final", request)
        val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, request, "call", "write_file", null,
            "allow", "{}", "started")
        finish("call", "ok")
        val first = bridge.personalAgentEpisodeNarrationInputSync(id)!!
        assertEquals(final, first.finalMessageId)
        assertNotNull(first.sourceFingerprint)
        update(request, "corrected request")
        assertFalse(bridge.personalAgentCommitEpisodeNarrationSync(id, first, "done", "Stale", "Stale"))
        val second = bridge.personalAgentEpisodeNarrationInputSync(id)!!
        assertNotEquals(first.sourceFingerprint, second.sourceFingerprint)
        update(final, "corrected final")
        assertFalse(bridge.personalAgentCommitEpisodeNarrationSync(id, second, "done", "Stale", "Stale"))
        assertNotEquals(second.finalFingerprint, bridge.personalAgentEpisodeNarrationInputSync(id)!!.finalFingerprint)
    }

    @Test fun failedModelResponseRetainsEvidenceAndRetryCanFinish() {
        val id = start("one")
        finish("one", "ok")
        val evidence = bridge.personalAgentEpisodeNarrationInputSync(id)!!.evidence
        var calls = 0
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                throw AssertionError("Narration must use the conversation boundary")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                calls++
                assertTrue(tools.isEmpty())
                assertEquals("user", history.single().role)
                assertTrue(systemPrompt.contains("Do not include connector or MCP content"))
                return ModelReply(if (calls == 1) "not json" else
                    """{"title":"Recorded action","narration":"One successful write was recorded."}""", emptyList())
            }
        }
        val narrator = EpisodeNarrator(context, central, CoreAgentModel(provider, "narration-test"))
        assertTrue(runCatching { narrator.narratePending(CancellationToken.cancellable()) }.isFailure)
        assertEquals("failed", bridge.personalAgentEpisodesForSourceMessagesSync(central, listOf("request")).single().narration)
        assertEquals(evidence, bridge.personalAgentEpisodeNarrationInputSync(id)!!.evidence)
        assertTrue(narrator.narratePending(CancellationToken.cancellable()))
        assertEquals("done", bridge.personalAgentEpisodesForSourceMessagesSync(central, listOf("request")).single().narration)
        assertEquals(2, calls)
        assertTrue(queued().isEmpty())
    }
}
