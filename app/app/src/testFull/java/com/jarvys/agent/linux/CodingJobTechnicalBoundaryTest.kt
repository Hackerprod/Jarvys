package com.jarvys.agent.linux

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.*
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.coding.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Crew/Registry/Room/journal/manager boundaries, with a local fake backend only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingJobTechnicalBoundaryTest {
    @get:Rule val chat = ChatTestStore(true)
    private class Fixture(val context: Context, val files: File, val database: JarvysChatDatabase) {
        fun <T> read(block: (ChatSqlDatabase) -> T): T = runBlocking {
            JarvysChatDatabaseProvider.onQueryExecutor(database) {
                database.withChatReadTransaction { block(database.chatSql) }
            }
        }
        fun kinds(job: String): List<String> = read { sql ->
            sql.query("SELECT observation_kind FROM coding_job_observations WHERE job_id=? ORDER BY revision", arrayOf(job)).use { c ->
                buildList { while(c.moveToNext()) add(c.getString(0)) }
            }
        }
    }
    private fun fixture(): Fixture {
        val files = chat.newFilesDirectory(); chat.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        return Fixture(context, files, JarvysChatDatabaseProvider.getInstance(context))
    }
    private fun authorization() = CodingJobEvidence.AuthorizationSnapshot(CodingJobEvidence.Authorization.ALLOW,
        "native", "project_exec", "fixture-revision", "local_fixture")

    @Test fun queuedSubmissionWithMemoryOffCommitsBothPreparationProofsBeforeColdBackendAndNeverReplaysDuplicate() {
        val f = fixture(); val policy = MemoryWritePolicy.forContext(f.context)
        policy.setEnabledBlocking(false)
        val ticket = policy.captureTicket() // Captured before the model and toolset run, never at mutation.
        val entriesBefore = f.read { sql -> sql.query("SELECT COUNT(*) FROM memory_entries").use { c -> c.moveToFirst(); c.getLong(0) } }
        val queue = mutableListOf<Runnable>(); val calls = AtomicInteger(); val completions = AtomicInteger()
        val accepted = AtomicReference<CodingJobAcceptance>(); val scopeRef = AtomicReference<ProjectScope>()
        val ownerRef = AtomicReference<String>(); val invocationRef = AtomicReference<EffectInvocation>()
        val manager = CodingJobManager(f.files, CodingJobManager.Backend { scope, _, _, _, _, _, launch ->
            // This is the outermost lazy-backend boundary, before our first synthetic mutation.
            assertEquals(listOf("SUBMISSION_ACCEPTED", "PREPARATION_ADMITTED"), f.kinds(accepted.get().reservation().binding().jobId))
            val bytes = accepted.get().submissionCommit().target().readBytes()
            val journal = CodingJobJournalCodec.decode(bytes, bytes.size)
            assertEquals(CodingJobEvidence.Phase.PREPARATION_INTENT, journal.record.highestEffectPhase)
            val projected = f.read { sql -> sql.query("SELECT payload_bytes FROM coding_job_observations WHERE job_id=? AND observation_kind='PREPARATION_ADMITTED'",
                arrayOf(journal.record.binding.jobId)).use { c -> assertTrue(c.moveToFirst()); c.getBlob(0) } }
            assertArrayEquals(bytes, projected)
            assertEquals(accepted.get().reservation().binding(), journal.record.binding)
            assertEquals(CodingJobEvidence.SemanticCapture.DISABLED, journal.record.binding.semanticCapture)
            assertEquals(0, calls.getAndIncrement())
            File(scope.rootDirectory(), "synthetic-cold-preparation").writeText("local fixture")
            launch(); LinuxExecResult(0)
        }, executor = Executor { queue += it })
        try {
            CodingSubmissionTestSource.runAsync(f.context) { invocation, token ->
                invocationRef.set(invocation)
                val source = invocation.crewSource().source()
                val scope = ProjectScopeStore(f.files).open(source.executionSessionId); scopeRef.set(scope)
                val owner = "${source.executionSessionId}/${source.botId}/fixture"; ownerRef.set(owner)
                val submitted = manager.startInstrumented(scope, owner, "synthetic-command", ".", scope.version(), null,
                    token, invocation, ChatDatabaseBridge.getInstance(f.context), authorization(), ticket,
                    completion = { completions.incrementAndGet() })
                accepted.set(requireNotNull(invocation.acceptedSubmission()))
                assertEquals(submitted.id, accepted.get().reservation().binding().jobId)
                assertNull(accepted.get().submissionCommit().record().terminal)
                assertEquals(0, calls.get())
                assertEquals(listOf("SUBMISSION_ACCEPTED"), f.kinds(submitted.id))
                CoreToolResult.success("Submission accepted, not command completion")
            }
            assertEquals(1, queue.size)
            assertThrows(IllegalStateException::class.java) { invocationRef.get().crewSource().requireInvocationCurrent() }
            val draining = manager.requestQuiesce(); assertFalse(draining.isDone)
            queue.single().run(); queue.single().run()
            draining.get(10, TimeUnit.SECONDS)
            val result = manager.list(ownerRef.get(), scopeRef.get()).single()
            assertEquals(CodingJobManager.State.SUCCEEDED, result.state); assertEquals(0, result.exitCode)
            assertEquals(1, calls.get()); assertEquals(1, completions.get()); assertEquals(1L, scopeRef.get().version())
            assertEquals(listOf("SUBMISSION_ACCEPTED", "PREPARATION_ADMITTED", "JOURNAL_PROJECTION"), f.kinds(result.id))
            assertNull("Administrative acceptance never turns into a terminal receipt", accepted.get().submissionCommit().record().terminal)
            assertFalse(policy.isEnabled())
            assertEquals(entriesBefore, f.read { sql -> sql.query("SELECT COUNT(*) FROM memory_entries").use { c -> c.moveToFirst(); c.getLong(0) } })
        } finally { queue.forEach { it.run() }; manager.requestQuiesce().get(10, TimeUnit.SECONDS) }
    }

    @Test fun inlineWorkerThenExecutorThrowKeepsActualTerminalDistinctFromAcceptanceAndDispatchesOnce() {
        val f = fixture(); val ticket = MemoryWritePolicy.forContext(f.context).captureTicket()
        val calls = AtomicInteger(); val completions = AtomicInteger(); val accepted = AtomicReference<CodingJobAcceptance>()
        val manager = CodingJobManager(f.files, CodingJobManager.Backend { _, _, _, _, _, _, launch ->
            calls.incrementAndGet(); launch(); LinuxExecResult(0)
        }, executor = Executor { it.run(); throw IllegalStateException("Synthetic transport failure after delivery") })
        try {
            CodingSubmissionTestSource.runAsync(f.context) { invocation, token ->
                val source = invocation.crewSource().source(); val scope = ProjectScopeStore(f.files).open(source.executionSessionId)
                val owner = "${source.executionSessionId}/${source.botId}/fixture"
                val failure = assertThrows(CodingJobManager.EnqueueOutcomeUncertain::class.java) {
                    manager.startInstrumented(scope, owner, "synthetic-command", ".", scope.version(), null, token,
                        invocation, ChatDatabaseBridge.getInstance(f.context), authorization(), ticket,
                        completion = { completions.incrementAndGet() })
                }
                accepted.set(requireNotNull(invocation.acceptedSubmission()))
                assertEquals(failure.jobId, accepted.get().reservation().binding().jobId)
                assertEquals(CodingJobManager.State.SUCCEEDED, failure.observedState); assertEquals(0, failure.observedExitCode)
                assertEquals(CodingJobManager.State.SUCCEEDED, manager.list(owner, scope).single().state)
                assertNull(accepted.get().submissionCommit().record().terminal)
                CoreToolResult.failure("Submission transport uncertain; inspect the already accepted job")
            }
            assertEquals(1, calls.get()); assertEquals(1, completions.get())
            assertEquals(listOf("SUBMISSION_ACCEPTED", "PREPARATION_ADMITTED", "JOURNAL_PROJECTION"), f.kinds(accepted.get().reservation().binding().jobId))
        } finally { manager.requestQuiesce().get(10, TimeUnit.SECONDS) }
    }

    @Test fun rejectedRoomPreparationNeverEntersEvenTheColdBackendFactory() {
        val f = fixture(); val ticket = MemoryWritePolicy.forContext(f.context).captureTicket()
        runBlocking { f.database.withChatWriteTransaction {
            f.database.chatSql.execSQL("CREATE TRIGGER fixture_reject_coding_preparation BEFORE INSERT ON coding_job_observations " +
                "WHEN NEW.observation_kind='PREPARATION_ADMITTED' BEGIN SELECT RAISE(ABORT,'synthetic preparation refusal'); END")
        } }
        val queue = mutableListOf<Runnable>(); val factories = AtomicInteger()
        val accepted = AtomicReference<CodingJobAcceptance>(); val scopeRef = AtomicReference<ProjectScope>()
        val ownerRef = AtomicReference<String>()
        val manager = CodingJobManager(f.files, CodingJobManager.Backend { _, _, _, _, _, _, _ ->
            factories.incrementAndGet(); throw AssertionError("Cold factory must not run without Room admission")
        }, executor = Executor { queue += it })
        try {
            CodingSubmissionTestSource.runAsync(f.context) { invocation, token ->
                val source = invocation.crewSource().source(); val scope = ProjectScopeStore(f.files).open(source.executionSessionId)
                scopeRef.set(scope); val owner = "${source.executionSessionId}/${source.botId}/fixture"; ownerRef.set(owner)
                manager.startInstrumented(scope, owner, "synthetic-command", ".", scope.version(), null, token,
                    invocation, ChatDatabaseBridge.getInstance(f.context), authorization(), ticket)
                accepted.set(requireNotNull(invocation.acceptedSubmission()))
                CoreToolResult.success("Administrative submission only")
            }
            assertEquals(1, queue.size); queue.single().run(); queue.single().run()
            assertEquals(0, factories.get()); assertEquals(0L, scopeRef.get().version())
            val terminal = manager.list(ownerRef.get(), scopeRef.get()).single()
            assertTrue(terminal.state.terminal); assertNotEquals(CodingJobManager.State.SUCCEEDED, terminal.state)
            assertFalse(f.kinds(terminal.id).contains("PREPARATION_ADMITTED"))
            val bytes = accepted.get().submissionCommit().target().readBytes()
            val journal = CodingJobJournalCodec.decode(bytes, bytes.size)
            assertEquals(CodingJobEvidence.Phase.PREPARATION_INTENT, journal.record.highestEffectPhase)
            assertNotNull(journal.record.terminal)
            assertEquals(CodingJobEvidence.AbsenceCoverage.NOT_PROVEN, journal.record.terminal.absenceCoverage)
        } finally { queue.forEach { it.run() }; manager.requestQuiesce().get(10, TimeUnit.SECONDS) }
    }


    @Test fun quiescingBeforeFirstLazyOwnerPinRejectsWithoutReservationOrEnqueue() {
        val f = fixture(); val ticket = MemoryWritePolicy.forContext(f.context).captureTicket()
        val enqueues = AtomicInteger(); val factories = AtomicInteger()
        val manager = CodingJobManager(f.files, CodingJobManager.Backend { _, _, _, _, _, _, _ ->
            factories.incrementAndGet(); throw AssertionError("Quiescing manager cannot enter backend")
        }, executor = Executor { enqueues.incrementAndGet(); it.run() })
        manager.requestQuiesce().get(10, TimeUnit.SECONDS)
        CodingSubmissionTestSource.runAsync(f.context) { invocation, token ->
            val source = invocation.crewSource().source(); val scope = ProjectScopeStore(f.files).open(source.executionSessionId)
            val owner = "${source.executionSessionId}/${source.botId}/fixture"
            assertThrows(IllegalStateException::class.java) {
                manager.startInstrumented(scope, owner, "synthetic-command", ".", scope.version(), null, token,
                    invocation, ChatDatabaseBridge.getInstance(f.context), authorization(), ticket)
            }
            assertNull(invocation.acceptedSubmission())
            scope.acquireWriter("cleanup-probe", scope.version()).use { }
            CoreToolResult.failure("Manager was already quiescing")
        }
        assertEquals(0, enqueues.get()); assertEquals(0, factories.get())
        assertEquals(0L, f.read { sql -> sql.query("SELECT COUNT(*) FROM coding_job_bindings").use { c -> c.moveToFirst(); c.getLong(0) } })
        manager.requestQuiesce().get(10, TimeUnit.SECONDS)
    }

}
