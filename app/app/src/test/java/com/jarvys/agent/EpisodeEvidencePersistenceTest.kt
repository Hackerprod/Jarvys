package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S6 persistence transitions against Room, without dispatching any external effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpisodeEvidencePersistenceTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var bridge: ChatDatabaseBridge
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    private val initialFacts = """{"classification":"WRITE","policy":"ASK"}"""

    @Before fun prepare() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        runBlocking { FreshStorageBootstrap.awaitReady(context) }
        bridge = ChatDatabaseBridge.getInstance(context)
    }

    private fun record(call: String = "call", outcome: String = "intent", source: String = "request",
                       action: String? = null, attempt: String? = null): String =
        bridge.personalAgentRecordEpisodeToolStartedSync(central, source, call, "write_file", null,
            "ask_approved", initialFacts, outcome, action, attempt, runId = attempt,
            processId = if (attempt == null) null else com.jarvys.agent.crew.CrewProcessIdentity.ID)

    private fun finish(call: String = "call", outcome: String, facts: String = "{}", attempt: String? = null) =
        bridge.personalAgentFinishEpisodeToolEvidenceSync(central, call, outcome, facts, attempt, runId = attempt)

    private fun evidence(id: String) = requireNotNull(bridge.personalAgentEpisodeNarrationInputSync(id)).evidence

    @Test fun intentIsDurableBeforeStartedAndTerminalCompletionPreservesFacts() {
        val id = record()
        assertEquals("intent", evidence(id).single().outcome)
        finish(outcome = "started", facts = """{"state":"approved_dispatch"}""")
        assertEquals("started", evidence(id).single().outcome)
        finish(outcome = "ok")
        val row = evidence(id).single()
        assertEquals("ok", row.outcome)
        assertEquals("ask_approved", row.authorization)
        val facts = JSONObject(row.factsJson)
        assertEquals("WRITE", facts.getString("classification"))
        assertEquals("ASK", facts.getString("policy"))
        assertEquals("approved_dispatch", facts.getString("state"))
    }

    @Test fun everyTerminalOutcomeRejectsLaterStartOrConflictingCompletion() {
        for (terminal in listOf("ok", "error", "uncertain", "not_executed", "deferred")) {
            val id = record(call = terminal, source = terminal)
            finish(call = terminal, outcome = terminal, facts = """{"state":"original"}""")
            val before = evidence(id).single()
            finish(call = terminal, outcome = "started", facts = """{"state":"replayed"}""")
            finish(call = terminal, outcome = if (terminal == "ok") "error" else "ok")
            assertEquals(before, evidence(id).single())
        }
    }

    @Test fun repeatedRecordingDoesNotDuplicateOrReopenSameApprovalAttempt() {
        val id = record(action = "action", attempt = "attempt")
        assertEquals(id, record(action = "action", attempt = "attempt"))
        finish(outcome = "ok", attempt = "attempt")
        val before = evidence(id).single()
        assertEquals(id, record(outcome = "started", action = "action", attempt = "attempt"))
        assertEquals(listOf(before), evidence(id))
    }

    @Test fun repeatedStartedTransitionDoesNotReplaceAdmissionFacts() {
        val id = record()
        finish(outcome = "started", facts = """{"state":"first_dispatch"}""")
        val before = evidence(id).single()
        finish(outcome = "started", facts = """{"state":"second_dispatch"}""")
        assertEquals(before, evidence(id).single())
    }

    @Test fun oneRequestGroupsDifferentCallsWithoutMergingTheirOutcomes() {
        val id = record(call = "one")
        assertEquals(id, record(call = "two"))
        finish(call = "one", outcome = "ok")
        finish(call = "two", outcome = "error")
        assertEquals(listOf("ok", "error"), evidence(id).map { it.outcome })
    }

    @Test fun recoveryCreatesMissingTerminalAttemptAndRemainsIdempotent() {
        for (terminal in listOf("ok", "uncertain", "deferred")) {
            val source = "task:task:$terminal"
            repeat(2) {
                bridge.personalAgentEnsureTaskAttemptEvidenceSync(central, source, "action-$terminal",
                    "attempt-$terminal", "write_file", "ask_approved", terminal, initialFacts)
            }
            val id = record(call = "attempt-$terminal", source = source,
                action = "action-$terminal", attempt = "attempt-$terminal")
            assertEquals(terminal, evidence(id).single().outcome)
        }
    }

    @Test fun recoveryClosesOriginalAttemptWithoutChangingSeparateDuplicateAttempt() {
        val source = "task:task:occurrence"
        val id = record("original", source = source, action = "action", attempt = "original")
        record("duplicate", source = source, action = "action", attempt = "duplicate")
        finish("duplicate", "not_executed", attempt = "duplicate")
        bridge.personalAgentEnsureTaskAttemptEvidenceSync(central, source, "action", "original",
            "write_file", "ask_approved", "ok", """{"state":"store_reconciled"}""")
        assertEquals(listOf("ok", "not_executed"), evidence(id).map { it.outcome })
        val original = evidence(id).first()
        assertEquals("WRITE", JSONObject(original.factsJson).getString("classification"))
        assertEquals("store_reconciled", JSONObject(original.factsJson).getString("state"))
    }

    @Test fun approvalAttemptCannotBeReboundToAnotherRequest() {
        val id = record(action = "action", attempt = "attempt")
        val failure = runCatching {
            record(source = "different-request", action = "action", attempt = "attempt")
        }.exceptionOrNull()
        assertTrue("Rebinding a durable approval attempt must fail", failure != null)
        assertEquals(1, evidence(id).size)
        assertEquals("request", bridge.personalAgentEpisodeNarrationInputSync(id)?.sourceMessageId)
    }

    @Test fun providerCallIdMayRecurAcrossDistinctRequests() {
        val first = record(call = "provider-call", source = "first-request")
        val second = record(call = "provider-call", source = "second-request")
        assertNotEquals(first, second)
        assertEquals("first-request", bridge.personalAgentEpisodeNarrationInputSync(first)?.sourceMessageId)
        assertEquals("second-request", bridge.personalAgentEpisodeNarrationInputSync(second)?.sourceMessageId)
        assertEquals("provider-call", evidence(first).single().refId)
        assertEquals("provider-call", evidence(second).single().refId)
        // Completion still needs batch identity end-to-end; this only guards admission compatibility.
    }


    @Test fun taskAttemptAndProviderCallWithSameIdCompleteIndependently() {
        val task = record(call = "collision", source = "task-request", action = "action", attempt = "collision")
        val chat = record(call = "collision", source = "chat-request")
        finish(call = "collision", outcome = "ok", attempt = "collision")
        assertEquals("ok", evidence(task).single().outcome)
        assertEquals("intent", evidence(chat).single().outcome)
        finish(call = "collision", outcome = "error")
        assertEquals("ok", evidence(task).single().outcome)
        assertEquals("error", evidence(chat).single().outcome)
    }

    @Test fun identicalProviderCallsInDifferentBatchesCompleteIndependently() {
        fun begin(batch: String, run: String) = bridge.personalAgentRecordEpisodeToolStartedSync(
            central, "one-request", "same-call", "write_file", null, "allow", initialFacts, "started",
            sourceBatchId = batch, runId = run, processId = "current-process")
        val entry = begin("batch-one", "run-one")
        assertEquals(entry, begin("batch-two", "run-two"))
        bridge.personalAgentFinishEpisodeToolEvidenceSync(central, "same-call", "ok", "{}",
            sourceBatchId = "batch-two", runId = "run-two")
        assertEquals(listOf("started", "ok"), evidence(entry).map { it.outcome })
        bridge.personalAgentReconcileEpisodeRunSync("run-one")
        assertEquals(listOf("uncertain", "ok"), evidence(entry).map { it.outcome })
        bridge.personalAgentFinishEpisodeToolEvidenceSync(central, "same-call", "ok", "{}",
            sourceBatchId = "batch-one", runId = "run-one")
        assertEquals(listOf("uncertain", "ok"), evidence(entry).map { it.outcome })
    }

    @Test fun reconciliationNeverTouchesAnotherLiveProducerInCentral() {
        fun begin(call: String, run: String, process: String) = bridge.personalAgentRecordEpisodeToolStartedSync(
            central, call, call, "write_file", null, "allow", initialFacts, "started",
            runId = run, processId = process)
        val chat = begin("chat", "chat-run", "current")
        val task = begin("task", "task-run", "current")
        val abandoned = begin("abandoned", "old-run", "old-process")
        bridge.personalAgentReconcileForeignEpisodeProcessesSync("current")
        assertEquals("started", evidence(chat).single().outcome)
        assertEquals("started", evidence(task).single().outcome)
        assertEquals("uncertain", evidence(abandoned).single().outcome)
        bridge.personalAgentReconcileEpisodeRunSync("chat-run")
        assertEquals("uncertain", evidence(chat).single().outcome)
        assertEquals("started", evidence(task).single().outcome)
    }

    @Test fun exactBatchIdentityCannotBeReboundAndFailedWriteKeepsOriginal() {
        val entry = bridge.personalAgentRecordEpisodeToolStartedSync(central, "request", "call", "write_file",
            null, "allow", initialFacts, "started", sourceBatchId = "batch", runId = "run", processId = "process")
        val before = evidence(entry)
        assertTrue(runCatching {
            bridge.personalAgentRecordEpisodeToolStartedSync(central, "different", "call", "write_file",
                null, "allow", initialFacts, "started", sourceBatchId = "batch", runId = "run", processId = "process")
        }.isFailure)
        assertEquals(before, evidence(entry))
    }

    @Test fun strictAdmissionPersistsAskSnapshotBeforeEffectAndExactRepeatWritesOnce() {
        val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, "admission-request", "admission-call",
            "write_file", null, null, """{"classification":"WRITE"}""", "intent",
            sourceBatchId = "admission-batch", runId = "admission-run", processId = "process")
        assertEquals(null, evidence(id).single().authorization)
        var writes = 0
        var effects = 0
        val invocation = EffectInvocation { admission ->
            bridge.personalAgentAdmitEpisodeToolEvidenceSync(central, "admission-call",
                admission.authorization.name.lowercase(java.util.Locale.ROOT),
                """{"admission_revision":"reviewed-revision"}""",
                sourceBatchId = "admission-batch", runId = "admission-run")
            writes++
        }
        val admission = EffectInvocation.Admission(EffectInvocation.Authorization.ASK_APPROVED,
            "test-gate", "reviewed-action", "reviewed-revision", "test")
        repeat(2) { invocation.admit(admission) }
        // This is the effect boundary: the gate callback must already have committed to Room.
        val row = evidence(id).single()
        assertEquals("started", row.outcome)
        assertEquals("ask_approved", row.authorization)
        assertEquals("reviewed-revision", JSONObject(row.factsJson).getString("admission_revision"))
        assertEquals("WRITE", JSONObject(row.factsJson).getString("classification"))
        effects++
        assertEquals(1, writes)
        assertEquals(1, effects)
    }

    @Test fun strictAdmissionRejectsMissingAndWrongIdentityBeforeAnyEffect() {
        val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, "strict-request", "strict-call",
            "write_file", null, null, initialFacts, "intent", "action", "attempt",
            "batch", "run", "process")
        val original = evidence(id)
        val invalidKeys = listOf(
            listOf("missing-call", "attempt", "batch", "run"),
            listOf("strict-call", "attempt", "wrong-batch", "run"),
            listOf("strict-call", "attempt", "batch", "wrong-run"),
            listOf("strict-call", "wrong-attempt", "batch", "run"),
            listOf("strict-call", null, "batch", "run"),
            listOf("strict-call", "attempt", null, "run"),
            listOf("strict-call", "attempt", "batch", null),
        )
        var effects = 0
        invalidKeys.forEach { key ->
            val invocation = EffectInvocation {
                bridge.personalAgentAdmitEpisodeToolEvidenceSync(central, requireNotNull(key[0]),
                    "allow", "{}", key[1], key[2], key[3])
            }
            assertTrue(runCatching {
                invocation.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
                    "test-gate", "action", "revision", "test"))
                effects++
            }.isFailure)
            assertTrue(!invocation.admitted())
            assertEquals(original, evidence(id))
        }
        assertEquals(0, effects)
    }

    @Test fun strictAdmissionCannotReopenStartedOrAnyTerminalEvidence() {
        var effects = 0
        for (state in listOf("started", "ok", "error", "uncertain", "not_executed", "deferred")) {
            val call = "strict-$state"
            val id = bridge.personalAgentRecordEpisodeToolStartedSync(central, call, call, "write_file",
                null, null, initialFacts, "intent", sourceBatchId = call, runId = call, processId = "process")
            bridge.personalAgentFinishEpisodeToolEvidenceSync(central, call, state, "{}",
                sourceBatchId = call, runId = call)
            val original = evidence(id)
            assertTrue(runCatching {
                bridge.personalAgentAdmitEpisodeToolEvidenceSync(central, call, "ask_approved", "{}",
                    sourceBatchId = call, runId = call)
                effects++
            }.isFailure)
            assertEquals(original, evidence(id))
        }
        assertEquals(0, effects)
    }

    @Test fun strictAdmissionRejectsAmbiguousLegacyRowsInsteadOfAdmittingBoth() {
        val first = record(call = "ambiguous", source = "legacy-one")
        val second = record(call = "ambiguous", source = "legacy-two")
        val firstBefore = evidence(first)
        val secondBefore = evidence(second)
        assertTrue(runCatching {
            bridge.personalAgentAdmitEpisodeToolEvidenceSync(central, "ambiguous", "allow", "{}")
        }.isFailure)
        assertEquals(firstBefore, evidence(first))
        assertEquals(secondBefore, evidence(second))
    }

}
