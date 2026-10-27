package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.coding.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real live Crew capture, reservation and journal issuer; no subprocess or fabricated Commit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingJobRepairBoundaryTest {
    @get:Rule val fixture = ChatTestStore(true)
    private fun withTerminal(block: (Context, ChatDatabaseBridge, CodingJobSubmission, CodingJobJournalStore.Commit, CodingJobOwnerLease.Pin) -> Unit) {
        val files = fixture.newFilesDirectory(); fixture.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        val bridge = ChatDatabaseBridge.getInstance(context)
        val owner = CodingJobOwnerLease.create(files)
        val pin = owner.pinSubmission()
        try {
            CodingSubmissionTestSource.runAsync(context) { invocation, _ ->
                val source = invocation.crewSource().source()
                val scope = ProjectScopeStore(files).open(source.executionSessionId)
                val store = CodingJobJournalStore.forFilesRoot(files)
                val submission = CodingJobSubmission.capture(invocation, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "${source.executionSessionId}/${source.botId}/fixture", scope.id(), source.scopeIdentity, pin,
                    MemoryWritePolicy.forContext(context).captureTicket(), store)
                val reservation = bridge.reserveCodingJobSync(submission)
                val binding = reservation.binding()
                fun snapshot(terminal: Boolean) = CodingJobSnapshot(binding.jobId, binding.owner, binding.scopeId, ".", "synthetic-command",
                    scope.rootDirectory().absolutePath, source.executionSessionId,
                    if (terminal) CodingJobSnapshot.State.NOT_STARTED else CodingJobSnapshot.State.LAUNCH_INTENT,
                    1, null, if (terminal) 2L else null, null, "coding-job:${binding.jobId}/log", null, 0, terminal)
                store.write(reservation, 0, CodingJobEvidence.Record(binding, 1, CodingJobEvidence.Phase.SUBMISSION_INTENT, true, null, null), snapshot(false))
                val terminal = store.write(reservation, 1, CodingJobEvidence.Record(binding, 2, CodingJobEvidence.Phase.SUBMISSION_INTENT, true, null,
                    CodingJobEvidence.TerminalFacts(CodingJobEvidence.TerminalState.NOT_STARTED, null, false, false, false,
                        CodingJobEvidence.AbsenceCoverage.NOT_PROVEN)), snapshot(true))
                val repairPin = owner.pinSubmission() // New live-owner permission, never the captured submission pin.
                try { block(context, bridge, submission, terminal, repairPin) } finally { repairPin.close() }
                CoreToolResult.failure("Synthetic unaccepted submission; no execution")
            }
        } finally { pin.close(); owner.requestQuiesce().get(10, TimeUnit.SECONDS) }
    }
    private fun kinds(context: Context): List<String> = runBlocking {
        val db = JarvysChatDatabaseProvider.getInstance(context)
        db.withChatReadTransaction { db.chatSql.query("SELECT observation_kind FROM coding_job_observations ORDER BY revision").use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) }
        } }
    }
    @Test fun genericProjectionCannotAcceptAnUnacceptedReservation() = withTerminal { context, bridge, _, commit, _ ->
        assertThrows(IllegalStateException::class.java) { bridge.projectCodingJobSync(commit) }
        assertTrue(kinds(context).isEmpty())
    }
    @Test fun genericAbandonmentIsNotPreclaimProducerProvenance() = withTerminal { context, bridge, submission, commit, pin ->
        submission.abandonDispatch()
        assertThrows(IllegalStateException::class.java) { bridge.repairCodingJobTerminalSync(commit, pin) }
        assertTrue(kinds(context).isEmpty())
    }
    @Test fun completedRestrictiveRepairIsIdempotentAndDoesNotInventAcceptance() = withTerminal { context, bridge, submission, commit, pin ->
        // Explicit trusted producer fact fixture. The manager CAS that supplies this fact has separate tests.
        submission.abandonBeforeWorkerClaim()
        bridge.repairCodingJobTerminalSync(commit, pin)
        bridge.repairCodingJobTerminalSync(commit, pin)
        bridge.linkCodingJobSemanticRepairSync(commit, pin)
        assertEquals(listOf("RESERVATION_ABANDONED"), kinds(context))
        runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            db.withChatReadTransaction { db.chatSql.query("SELECT admitted,terminal_confirmed FROM coding_job_semantic_links WHERE job_id=?", arrayOf(commit.record().binding.jobId)).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals(1, c.getInt(1))
            } }
        }
        runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            db.withChatReadTransaction { db.chatSql.query("SELECT COUNT(*) FROM memory_evidence WHERE ref_id=? AND ref_kind IN ('coding_job_submission','coding_job_execution')", arrayOf(commit.record().binding.jobId)).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
            } }
        }
        assertNull(submission.acceptanceOutcome())
    }
    @Test fun replacementGenerationCannotAdoptRestrictiveTerminal() = withTerminal { context, bridge, submission, commit, pin ->
        submission.abandonBeforeWorkerClaim()
        runBlocking {
            val db = JarvysChatDatabaseProvider.getInstance(context)
            db.withChatWriteTransaction { db.chatSql.execSQL("UPDATE storage_meta SET value=? WHERE key=?",
                arrayOf(UUID.randomUUID().toString(), ConnectionProjectionRepository.GENERATION_KEY)) }
        }
        assertThrows(IllegalStateException::class.java) { bridge.repairCodingJobTerminalSync(commit, pin) }
        assertTrue(kinds(context).isEmpty())
    }
}
