package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.chatdb.MemorySearchFilter
import com.jarvys.agent.chatdb.JarvysChatDatabaseProvider
import com.jarvys.agent.chatdb.MissionEpisodeRepository
import com.jarvys.agent.chatdb.withChatReadTransaction
import com.jarvys.agent.chatdb.withChatWriteTransaction
import com.jarvys.agent.chatdb.chatSql
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionAccess
import com.jarvys.agent.crew.CrewResultEvidence
import com.jarvys.agent.workspaces.WorkspaceAssignment
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRecord
import com.jarvys.agent.workspaces.WorkspaceRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

/** B15: real Crew publication and Central acknowledgement, with no model or external dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MissionEpisodeCaptureTest {
    @get:Rule val fixture = ChatTestStore(registerApplicationFilesDirectory = true)
    private lateinit var context: Context
    private lateinit var store: LocalRunStore
    private lateinit var bridge: ChatDatabaseBridge
    private lateinit var registry: WorkspaceRegistry
    private lateinit var manager: CrewManager
    private lateinit var bot: CrewManager.Bot
    private lateinit var scope: WorkspaceRecord
    private lateinit var assignment: WorkspaceAssignment
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    private val resultText = "Private worker details must stay in the source ledger."

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        runBlocking { SingleSessionReset.awaitReady(context) }
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        registry = WorkspaceRegistry(context)
        store = LocalRunStore(context)
        bridge = ChatDatabaseBridge.getInstance(context)
        assertTrue(bridge.ensureConversationSync(central, "central") is ChatDbResult.Success)
        scope = registry.createWorkspace("episode-${UUID.randomUUID()}", "Mission episode test")
        val prepared = registry.prepareAssignment(scope.workspaceId, "assignment", "request", "Owner request",
            "custom", "Synthetic mission", emptyList(), "Worker", CrewMissionAccess.STANDARD)
        val started = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "test-process")
        val empty = CoreToolRegistry(emptyList())
        manager = CrewManager(scope.executionSessionId, empty, { _, _ -> empty },
            { _, _, _ -> throw AssertionError("This fixture must not start a worker model") }, null)
        manager.configureConversationMessageListener { sender, message ->
            WorkspaceAgentInputDelivery.deliver(context, sender, message)
        }
        bot = manager.spawnForMissionDeferred(prepared.missionId, "Owner request", "custom",
            "Synthetic mission", emptyList(), "Worker", CrewMissionAccess.STANDARD, null, started)
        assignment = registry.bindBot(scope.workspaceId, prepared.assignmentId, started.revision, bot.id)
    }

    @After fun close() {
        if (::manager.isInitialized) manager.close()
        if (::registry.isInitialized) registry.close()
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
    }

    private fun publish(text: String = resultText): Pair<CrewMessage, String> {
        val message = manager.send(bot.id, "chief", CrewMessage.Type.RESULT, text, emptyList())
        assertEquals(bot.cycleId(), message.resultCycleId)
        val input = store.readPendingInternalConversationInputs(central).single {
            store.readConversationInput(central, it.getString("messageId"))?.provenance?.messageId == message.id
        }
        return message to input.getString("messageId")
    }

    private fun evidence(text: String = resultText, cycle: String = bot.cycleId(), status: String = "DONE") =
        CrewResultEvidence.create(scope.executionSessionId, bot.missionId, bot.id, cycle, status, text, 1234L)

    private fun persist(value: CrewResultEvidence) = store.appendCrewResultEvidence(value, Runnable {})
    private fun incorporate(id: String) = CentralAgentInputCatalog.acknowledge(store, central, listOf(id), setOf(id))
    private fun episodes() = bridge.personalAgentSearch("", 50, MemorySearchFilter(kind = "episode"))

    private fun assertExactReceipt(value: CrewResultEvidence) {
        val episode = episodes().single()
        assertEquals("", episode.text)
        val row = bridge.personalAgentEpisodeNarrationInputSync(episode.id)!!.evidence.single()
        assertEquals("crew_result", row.refKind)
        assertEquals(value.conversationId, row.refConversationId)
        assertEquals(value.evidenceId, row.refId)
        val facts = JSONObject(row.factsJson)
        assertEquals(value.contentSha256, facts.getString("content_sha256"))
        assertEquals(scope.workspaceId, facts.getString("workspace_id"))
        assertEquals(assignment.assignmentId, facts.getString("assignment_id"))
        assertEquals(value.reportedStatus, facts.getString("reported_status"))
        assertEquals("central_input_incorporated", facts.getString("receipt"))
        assertFalse(facts.getBoolean("result_verified"))
        assertFalse(row.factsJson.contains(resultText))
    }

    @Test fun incorporationBeforeDurableEvidenceWaitsThenCreatesExactEpisode() {
        val (_, id) = publish()
        assertTrue(episodes().isEmpty())
        incorporate(id)
        assertTrue(episodes().isEmpty())
        val value = evidence()
        persist(value)
        assertExactReceipt(value)
    }

    @Test fun durableEvidenceBeforeIncorporationDoesNotPromoteDeliveryToConsumption() {
        val (_, id) = publish()
        val value = evidence(status = "FAILED")
        persist(value)
        assertTrue(episodes().isEmpty())
        incorporate(id)
        assertExactReceipt(value)
    }

    @Test fun retriesAndAnotherCopyOfSameCycleDoNotDuplicateEpisode() {
        val (message, id) = publish()
        val value = evidence()
        persist(value)
        incorporate(id)
        val firstId = episodes().single().id
        WorkspaceAgentInputDelivery.deliver(context, bot, CrewMessage.fromJson(message.toJson()))
        persist(value)
        incorporate(id)
        val (_, second) = publish()
        incorporate(second)
        assertEquals(listOf(firstId), episodes().map { it.id })
    }

    @Test fun differentCycleWithIdenticalTextDoesNotSatisfyPendingInput() {
        val (_, id) = publish()
        incorporate(id)
        persist(evidence(cycle = UUID.randomUUID().toString()))
        assertTrue(episodes().isEmpty())
        val exact = evidence()
        persist(exact)
        assertExactReceipt(exact)
    }

    @Test fun sameCycleWithDifferentResultTextDoesNotCreateEpisode() {
        val (_, id) = publish()
        incorporate(id)
        persist(evidence(text = "Different final result"))
        assertTrue(episodes().isEmpty())
    }

    @Test fun resolvedButUnconsumedResultDoesNotCreateEpisode() {
        val (_, id) = publish()
        store.markConversationMessagesResolved(central, listOf(id))
        persist(evidence())
        incorporate(id)
        assertTrue(episodes().isEmpty())
    }

    @Test fun legacyResultWithoutCycleIsNotBoundToCurrentBotGeneration() {
        val legacy = CrewMessage(scope.executionSessionId, bot.id, "chief", CrewMessage.Type.RESULT,
            resultText, emptyList(), 1000L)
        WorkspaceAgentInputDelivery.deliver(context, bot, legacy)
        val id = store.readPendingInternalConversationInputs(central).single().getString("messageId")
        incorporate(id)
        persist(evidence())
        assertTrue(episodes().isEmpty())
        assertEquals("", CrewMessage.fromJson(legacy.toJson()).resultCycleId)
    }

    @Test fun modelCheckpointMaterializesBeforeServiceAcknowledgementAndQueuesReceipt() {
        val (_, id) = publish()
        val value = evidence()
        persist(value)
        val result = bridge.appendModelTurnSync(com.jarvys.agent.chatdb.ConversationModelTurnInput(
            conversationId = central, inputMessageIds = listOf(id), inputState = "incorporated"))
        assertTrue(result is ChatDbResult.Success)
        assertExactReceipt(value)
        val db = JarvysChatDatabaseProvider.getInstance(context)
        val jobId = runBlocking { db.withChatReadTransaction {
            db.chatSql.query("SELECT id FROM memory_jobs WHERE job_type='narrate' AND source_conversation_id=? AND state='queued'", arrayOf(central)).use {
                assertTrue("Materialization must atomically persist its wake receipt", it.moveToFirst())
                it.getString(0)
            }
        } }
        val job = bridge.personalAgentClaimJob(jobId, "test-owner", System.currentTimeMillis(),
            System.currentTimeMillis() + 60000)
        assertNotNull(job)
        assertEquals("narrate", job!!.type)
        // A replay does not create a replacement receipt or another episode.
        bridge.appendModelTurnSync(com.jarvys.agent.chatdb.ConversationModelTurnInput(
            conversationId = central, inputMessageIds = listOf(id), inputState = "incorporated"))
        assertEquals(jobId, bridge.personalAgentEnqueueJob(central, "narrate"))
        assertEquals(1, episodes().size)
    }

    @Test fun startupRecoveryRepairsCommittedInputBeforeEpisodeAndIsIdempotent() {
        val (_, id) = publish()
        val value = evidence()
        persist(value)
        val db = JarvysChatDatabaseProvider.getInstance(context)
        runBlocking { db.withChatWriteTransaction {
            // Simulate the previous checkpoint crash window: input durable, episode not yet created.
            db.chatSql.execSQL("UPDATE messages SET input_state='incorporated' WHERE conversation_id=? AND message_id=?", arrayOf(central, id))
        } }
        assertTrue(episodes().isEmpty())
        assertTrue(runBlocking { MissionEpisodeRepository(db).recoverPending() })
        assertExactReceipt(value)
        val entryId = episodes().single().id
        assertTrue(runBlocking { MissionEpisodeRepository(db).recoverPending() })
        assertEquals(listOf(entryId), episodes().map { it.id })
    }

    @Test fun recoverySelectsOnlyIncorporatedUnmaterializedReceiptsIncludingForgetProtection() {
        val (_, id) = publish()
        val db = JarvysChatDatabaseProvider.getInstance(context)
        val missions = MissionEpisodeRepository(db)
        assertTrue(runBlocking { missions.recoverableInputs() }.isEmpty())
        persist(evidence())
        assertTrue(runBlocking { missions.recoverableInputs() }.isEmpty())
        runBlocking { db.withChatWriteTransaction {
            db.chatSql.execSQL("UPDATE messages SET input_state='incorporated' WHERE conversation_id=? AND message_id=?", arrayOf(central, id))
        } }
        assertEquals(listOf(id), runBlocking { missions.recoverableInputs() }.map { it.message_id })
        runBlocking { missions.recoverPending() }
        assertTrue(runBlocking { missions.recoverableInputs() }.isEmpty())
        runBlocking { db.withChatWriteTransaction {
            db.chatSql.execSQL("UPDATE memory_entries SET deleted_at=1 WHERE kind='episode'")
        } }
        assertTrue(runBlocking { missions.recoverableInputs() }.isEmpty())
        assertFalse(runBlocking { missions.recoverPending() })
    }

    @Test fun failedStartupRecoveryDoesNotPublishRepositoriesThroughFastPath() {
        val (_, id) = publish()
        persist(evidence())
        val db = JarvysChatDatabaseProvider.getInstance(context)
        runBlocking { db.withChatWriteTransaction {
            db.chatSql.execSQL("UPDATE messages SET input_state='incorporated' WHERE conversation_id=? AND message_id=?", arrayOf(central, id))
            db.chatSql.execSQL("UPDATE crew_result_evidence SET content_sha256=?", arrayOf("0".repeat(64)))
        } }
        // Re-enter the real lazy-open path with the same provider to inject a recovery failure.
        val field = ChatDatabaseBridge::class.java.getDeclaredField("repositories").apply { isAccessible = true }
        val original = field.get(bridge)
        field.set(bridge, null)
        try {
            assertTrue(runCatching { bridge.ensureConversationSync(central, "central") }.isFailure)
            assertNull("Failed recovery must not expose a partially initialized fast path", field.get(bridge))
            assertTrue(runCatching { bridge.ensureConversationSync(central, "central") }.isFailure)
        } finally {
            // Preserve fixture-owned provider cleanup even after intentionally failed initialization.
            field.set(bridge, original)
        }
    }

    @Test fun resultCycleSurvivesCheckpointRoundTrip() {
        val (message, _) = publish()
        assertEquals(message.resultCycleId, CrewMessage.fromJson(message.toJson()).resultCycleId)
        val invalid = message.toJson().put("type", "MESSAGE")
        assertTrue(runCatching { CrewMessage.fromJson(invalid) }.isFailure)
    }
}
