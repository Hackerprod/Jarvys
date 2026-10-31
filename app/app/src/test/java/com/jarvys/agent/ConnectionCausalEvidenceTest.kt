package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.chatdb.withChatWriteTransaction
import com.jarvys.agent.connectors.ConnectionObservationTestSupport
import com.jarvys.agent.connectors.ConnectionStateCoordinator
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionCausalEvidenceTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        bridge = ChatDatabaseBridge.getInstance(context)
    }
    private fun intent(call: String, connector: String? = "mail", batch: String? = null,
        run: String? = null, attempt: String? = null, action: String? = null) =
        bridge.personalAgentRecordEpisodeToolStartedSync(central, "request", call, "synthetic_write", connector,
            null, """{"classification":"WRITE"}""", "intent", action, attempt, batch, run, "process")
    private fun evidence(entry: String) = requireNotNull(bridge.personalAgentEpisodeNarrationInputSync(entry)).evidence
    private fun admit(call: String, observation: ConnectionStateCoordinator.Observation?, batch: String? = null,
        run: String? = null, attempt: String? = null) = bridge.personalAgentAdmitEpisodeToolEvidenceSync(
            central, call, "allow", "{}", attempt, batch, run, observation)

    @Test fun legacyLatestConnectedRowsNeverFabricateAnObservationAtIntentOrAdmission() {
        bridge.recordConnectorStateEventSync("mail", true)
        bridge.recordConnectorStateEventSync("mail", false)
        val id = intent("without-observation")
        assertEquals(listOf("tool_call"), evidence(id).map { it.refKind })
        admit("without-observation", null)
        assertEquals(listOf("tool_call"), evidence(id).map { it.refKind })
        assertEquals("started", evidence(id).single().outcome)
    }

    @Test fun exactUnprojectedObservationInheritsOnlyTheMatchedIntentOwner() = runBlocking {
        val observation = ConnectionObservationTestSupport.observation("mail", true)
        val id = intent("owned", batch = "batch", run = "run", attempt = "attempt", action = "action")
        admit("owned", observation, "batch", "run", "attempt")
        val causal = evidence(id).single { it.refKind == "connector_event" }
        assertEquals(observation.eventId(), causal.refId)
        assertEquals("local_choice", causal.outcome)
        assertNull(causal.authorization)
        assertEquals("mail", causal.connector)
        assertEquals(central, causal.refConversationId)
        assertEquals("batch", causal.sourceBatchId)
        assertEquals("run", causal.runId)
        assertEquals("process", causal.processId)
        val facts = JSONObject(causal.factsJson)
        assertEquals("owned", facts.getString("observed_for_call_id"))
        assertEquals(observation.storeEpoch, facts.getString("store_epoch"))
        assertEquals(observation.sequence, facts.getLong("sequence"))
        assertEquals(observation.operationId, facts.getString("operation_id"))
        assertTrue(facts.getBoolean("selected"))
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatWriteTransaction {
            db.chatSql.query("SELECT action_id,attempt_id FROM memory_evidence WHERE entry_id=? AND ref_kind='connector_event'", arrayOf(id)).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("action", c.getString(0)); assertEquals("attempt", c.getString(1))
                assertFalse(c.moveToNext())
            }
            db.chatSql.query("SELECT COUNT(*) FROM connector_state_events WHERE id=?", arrayOf(observation.eventId())).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test fun sameEventAcrossTwoIntentsGetsTwoCausalRowsButExactAdmissionRepeatWritesOnce() {
        val observation = ConnectionObservationTestSupport.observation("mail", true)
        val id = intent("first", batch = "batch-one", run = "run-one")
        assertEquals(id, intent("second", batch = "batch-two", run = "run-two"))
        val invocation = EffectInvocation { a -> admit("first", a.connectionObservation, "batch-one", "run-one") }
        val admission = EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
            "connector:mail", "write", "1", "test", observation)
        repeat(2) { invocation.admit(admission) }
        admit("second", observation, "batch-two", "run-two")
        val rows = evidence(id).filter { it.refKind == "connector_event" }
        assertEquals(2, rows.size)
        assertEquals(setOf(observation.eventId()), rows.map { it.refId }.toSet())
        assertEquals(setOf("first", "second"), rows.map { JSONObject(it.factsJson).getString("observed_for_call_id") }.toSet())
        assertEquals(setOf("batch-one" to "run-one", "batch-two" to "run-two"), rows.map { it.sourceBatchId to it.runId }.toSet())
        assertEquals(2, rows.map { it.ordinal }.toSet().size)
    }

    @Test fun knownConnectorMismatchAbortsBeforeAdmittingOrChangingIntent() {
        val id = intent("mismatch", "mail")
        val before = evidence(id)
        val invocation = EffectInvocation { a -> admit("mismatch", a.connectionObservation) }
        assertTrue(runCatching { invocation.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
            "connector:other", "write", "1", "test", ConnectionObservationTestSupport.observation("other", true))) }.isFailure)
        assertFalse(invocation.admitted())
        assertEquals(before, evidence(id))
    }

    @Test fun causalInsertFailureRollsBackStartedTransitionAndStopsAdmission() = runBlocking {
        val id = intent("failed-insert")
        val before = evidence(id)
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatWriteTransaction {
            db.chatSql.execSQL("CREATE TRIGGER synthetic_fail_connection_cause BEFORE INSERT ON memory_evidence WHEN NEW.ref_kind='connector_event' BEGIN SELECT RAISE(ABORT,'synthetic causal failure'); END")
        }
        try {
            val invocation = EffectInvocation { a -> admit("failed-insert", a.connectionObservation) }
            assertTrue(runCatching { invocation.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
                "connector:mail", "write", "1", "test", ConnectionObservationTestSupport.observation("mail", true))) }.isFailure)
            assertFalse(invocation.admitted())
            assertEquals(before, evidence(id))
        } finally { db.withChatWriteTransaction { db.chatSql.execSQL("DROP TRIGGER synthetic_fail_connection_cause") } }
    }

    @Test fun unknownConnectorUsesTypedObservationAndFalseDoesNotBecomeAuthorization() {
        val observation = ConnectionObservationTestSupport.observation("mail", false)
        val id = intent("unknown-connector", null)
        admit("unknown-connector", observation)
        val row = evidence(id).single { it.refKind == "connector_event" }
        assertEquals("mail", row.connector)
        assertEquals(observation.eventId(), row.refId)
        assertFalse(JSONObject(row.factsJson).getBoolean("selected"))
        assertNull(row.authorization)
        assertEquals("local_choice", row.outcome)
        assertTrue(bridge.personalAgentPendingEpisodeNarrationsSync(central).isEmpty())
    }

    @Test fun episodeRecorderForwardsTheTypedObservationWithoutInferringConnectorNames() {
        val tool = object : CoreTool {
            override fun declaration() = ToolSpec("synthetic_write", "test", "Test write", "test",
                ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun execute(arguments: Map<String, Any>, token: CancellationToken) = CoreToolResult.success("done")
        }
        val recorder = EpisodeRecorder(context, central, "recorder-request")
        val observation = ConnectionObservationTestSupport.observation("mail", true)
        recorder.beforeDispatch(tool, "recorder-call", emptyMap())
        recorder.onEffectAdmission(tool, "recorder-call", EffectInvocation.Admission(
            EffectInvocation.Authorization.ALLOW, "connector:mail", "write", "1", "test", observation))
        // Browse's top-level kind distinguishes profile vs memory; episode is the memory subtype.
        val entry = bridge.personalAgentBrowse().single { it.memoryKind == "episode" }
        assertEquals("memory", entry.kind)
        assertEquals(entry.id, bridge.personalAgentEpisodesForSourceMessagesSync(central, listOf("recorder-request")).single().id)
        val row = evidence(entry.id).single { it.refKind == "connector_event" }
        assertEquals(observation.eventId(), row.refId)
        assertEquals("recorder-call", JSONObject(row.factsJson).getString("observed_for_call_id"))
        assertNull(row.authorization)
    }
}
