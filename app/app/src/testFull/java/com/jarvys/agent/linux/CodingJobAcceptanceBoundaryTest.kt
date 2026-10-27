package com.jarvys.agent.linux

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.*
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.coding.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lost-ACK boundary uses the real bridge callable result, never a production callback/test hook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingJobAcceptanceBoundaryTest {
    private val bridgeExecutor = AcceptanceAckExecutor()
    @get:Rule val chat = ChatTestStore(true, false) { bridgeExecutor }

    private class AcceptanceAckExecutor : AbstractExecutorService() {
        private val delegate = Executors.newSingleThreadExecutor { work -> Thread(work, "coding-acceptance-ack-fixture").apply { isDaemon = true } }
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicReference<CodingJobAcceptance>()
        override fun <T> submit(task: Callable<T>): Future<T> = delegate.submit(Callable {
            val result = task.call() // Original operation has committed and released TX/anchor/op pin.
            if (result is CodingJobAcceptance) {
                assertTrue("Exactly one acceptance callable", accepted.compareAndSet(null, result))
                committed.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "Acceptance ACK fixture deadline" }
            }
            result
        })
        override fun execute(command: Runnable) = delegate.execute(command)
        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
        override fun isShutdown(): Boolean = delegate.isShutdown
        override fun isTerminated(): Boolean = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = delegate.awaitTermination(timeout, unit)
    }

    @Test fun committedAcceptanceWithInterruptedWaiterProjectsSameJobTerminalWithoutEnqueueAndDrainsPinAfterProjection() {
        val files = chat.newFilesDirectory(); chat.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        val database = JarvysChatDatabaseProvider.getInstance(context)
        fun rows(): List<Pair<String, String>> = runBlocking {
            JarvysChatDatabaseProvider.onQueryExecutor(database) {
                database.withChatReadTransaction {
                    database.chatSql.query("SELECT observation_kind,evidence_json FROM coding_job_observations ORDER BY revision").use { c ->
                        buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                    }
                }
            }
        }
        val ticket = MemoryWritePolicy.forContext(context).captureTicket()
        val enqueues = AtomicInteger(); val backends = AtomicInteger(); val calls = AtomicInteger()
        val manager = CodingJobManager(files, CodingJobManager.Backend { _, _, _, _, _, _, _ ->
            backends.incrementAndGet(); throw AssertionError("Unacknowledged acceptance must not dispatch")
        }, executor = Executor { enqueues.incrementAndGet(); it.run() })
        val caller = AtomicReference<Thread>(); val failedWait = CountDownLatch(1)
        val sourceFailure = AtomicReference<Throwable>(); val invocationRef = AtomicReference<EffectInvocation>()
        val scopeRef = AtomicReference<ProjectScope>(); val ownerRef = AtomicReference<String>()
        val driver = Thread({
            try {
                CodingSubmissionTestSource.runAsync(context) { invocation, token ->
                    calls.incrementAndGet(); invocationRef.set(invocation); caller.set(Thread.currentThread())
                    val source = invocation.crewSource().source()
                    val scope = ProjectScopeStore(files).open(source.executionSessionId)
                    val owner = "${source.executionSessionId}/${source.botId}/fixture"
                    scopeRef.set(scope); ownerRef.set(owner)
                    try {
                        assertThrows(ChatDatabaseWaitInterruptedException::class.java) {
                            manager.startInstrumented(scope, owner, "synthetic-command", ".", scope.version(), null,
                                token, invocation, ChatDatabaseBridge.getInstance(context),
                                CodingJobEvidence.AuthorizationSnapshot(CodingJobEvidence.Authorization.ALLOW,
                                    "native", "project_exec", "fixture", "local_fixture"), ticket)
                        }
                        assertTrue("Caller interruption must remain visible", Thread.currentThread().isInterrupted)
                        assertNull("Lost ACK must not bind acceptance to the call", invocation.acceptedSubmission())
                    } finally {
                        Thread.interrupted() // Test thread can finish the real loop after checking preservation.
                        failedWait.countDown()
                    }
                    CoreToolResult.failure("Submission acknowledgement interrupted; never repeat the job")
                }
            } catch (failure: Throwable) { sourceFailure.set(failure) }
        }, "coding-acceptance-source-fixture")
        driver.isDaemon = true
        try {
            driver.start()
            assertTrue(bridgeExecutor.committed.await(30, TimeUnit.SECONDS))
            val original = requireNotNull(bridgeExecutor.accepted.get())
            assertEquals(1L, original.submissionCommit().record().revision)
            assertEquals(listOf("SUBMISSION_ACCEPTED"), rows().map { it.first })
            assertEquals(0, enqueues.get()); assertEquals(0, backends.get())
            requireNotNull(caller.get()).interrupt()
            assertTrue(failedWait.await(30, TimeUnit.SECONDS))
            val draining = manager.requestQuiesce()
            // Public manager reads validate conversation liveness through the held bridge executor.
            // Observe the actual durable rows independently, not a reentrant status request here.
            assertFalse("Original pin must survive queued terminal projection", draining.isDone)
            val beforeRelease = rows()
            assertEquals(listOf("SUBMISSION_ACCEPTED"), beforeRelease.map { it.first })
            val acceptedRecord = CodingJobEvidenceCodec.decode(beforeRelease.single().second,
                beforeRelease.single().second.length).record
            assertNull("No terminal projection may precede the released ACK worker", acceptedRecord.terminal)
            assertEquals(0, enqueues.get()); assertEquals(0, backends.get())
            bridgeExecutor.release.countDown()
            draining.get(30, TimeUnit.SECONDS)
            driver.join(30_000); assertFalse(driver.isAlive)
            sourceFailure.get()?.let { throw AssertionError(it) }
            val projected = rows()
            assertEquals(listOf("SUBMISSION_ACCEPTED", "JOURNAL_PROJECTION"), projected.map { it.first })
            val terminal = CodingJobEvidenceCodec.decode(projected.last().second, projected.last().second.length).record
            assertEquals(original.reservation().binding(), terminal.binding)
            assertEquals(CodingJobManager.TechnicalProjection.CONFIRMED, manager.technicalProjection(ownerRef.get(), scopeRef.get(), terminal.binding.jobId))
            assertTrue(terminal.revision > 1)
            assertNotNull(terminal.terminal)
            assertEquals(CodingJobEvidence.TerminalState.NOT_STARTED, terminal.terminal.state)
            assertEquals(CodingJobEvidence.AbsenceCoverage.NOT_PROVEN, terminal.terminal.absenceCoverage)
            assertNull(invocationRef.get().acceptedSubmission())
            assertEquals(1, calls.get()); assertEquals(0, enqueues.get()); assertEquals(0, backends.get())
            assertSame(original, original.reservation().submission().acceptanceOutcome().acceptance)
        } finally {
            bridgeExecutor.release.countDown(); driver.join(30_000)
            manager.requestQuiesce().get(30, TimeUnit.SECONDS)
        }
    }

    @Test fun hostInterruptedWriterCannotMintCommitWhenDirectorySyncIsInterrupted() {
        val root = chat.newFilesDirectory()
        val jobId = "00000000-0000-0000-0000-000000000077"
        val target = File(root, "$jobId.json")
        val writer = CodingJobJournalWriter(root)
        val failure = AtomicReference<Throwable>()
        val committed = AtomicReference<CodingJobJournalWriter.Commit>()
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val thread = Thread({
            Thread.currentThread().interrupt()
            try { committed.set(writer.writeEnvelope(target, jobId, "synthetic-envelope".toByteArray())) }
            catch (error: Throwable) { failure.set(error) }
            finally { interrupted.set(Thread.currentThread().isInterrupted); Thread.interrupted() }
        }, "coding-interrupted-journal-diagnostic")
        thread.isDaemon = true; thread.start(); thread.join(10_000)
        assertFalse("Host IO diagnostic must terminate", thread.isAlive)
        assertNull("No commit may be minted after failed directory sync", committed.get())
        val error = failure.get()
        assertTrue("Expected typed writer failure, got ${error?.javaClass?.simpleName}", error is CodingJobJournalWriter.Failure)
        val writeFailure = error as CodingJobJournalWriter.Failure
        assertEquals(CodingJobJournalWriter.Stage.DIRECTORY_SYNC, writeFailure.attempted)
        assertEquals(CodingJobJournalWriter.Stage.RENAME, writeFailure.confirmed)
        assertTrue("Expected host interrupt-sensitive IO cause", writeFailure.cause is java.nio.channels.ClosedByInterruptException)
        assertTrue("Writer must not silently clear caller interruption", interrupted.get())
        // Rename may have happened; no claim that terminal bytes are absent from disk.
    }

}
