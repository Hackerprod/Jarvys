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
import java.util.concurrent.atomic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Post-reservation/pre-journal interruption: the reservation IS delivered, not a lost reserve ACK. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class CodingJobLiveRepairTest {
    private val executor = InterruptAfterReservationExecutor()
    @get:Rule val chat = ChatTestStore(true, false) { executor }
    private class InterruptAfterReservationExecutor : AbstractExecutorService() {
        private val delegate = Executors.newSingleThreadExecutor { r -> Thread(r,"repair-reservation-fixture").apply { isDaemon=true } }
        val reservation = AtomicReference<CodingJobReservation>()
        var beforeInterrupt: () -> Unit = {}
        override fun <T> submit(task: Callable<T>): Future<T> {
            val real = delegate.submit(task)
            fun deliver(value: T): T {
                if(value is CodingJobReservation && reservation.compareAndSet(null,value)) {
                    beforeInterrupt()
                    Thread.currentThread().interrupt()
                }
                return value
            }
            return object : Future<T> {
                override fun get(): T = deliver(real.get())
                override fun get(timeout: Long, unit: TimeUnit): T = deliver(real.get(timeout,unit))
                override fun cancel(interrupt: Boolean)=real.cancel(interrupt)
                override fun isDone()=real.isDone
                override fun isCancelled()=real.isCancelled
            }
        }
        override fun execute(command: Runnable)=delegate.execute(command)
        override fun shutdown()=delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
        override fun isShutdown()=delegate.isShutdown
        override fun isTerminated()=delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit)=delegate.awaitTermination(timeout,unit)
    }
    private fun exercise(quiesceBeforeFailure: Boolean) {
        val files=chat.newFilesDirectory();chat.localRunStore(files)
        val context=object: ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context=this
            override fun getFilesDir(): File=files
        }
        val database=JarvysChatDatabaseProvider.getInstance(context)
        fun rows(): List<Pair<String,String>> = runBlocking {
            JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
                database.chatSql.query("SELECT observation_kind,evidence_json FROM coding_job_observations ORDER BY revision").use { c ->
                    buildList {while(c.moveToNext())add(c.getString(0) to c.getString(1))}
                }
            } }
        }
        val ticket=MemoryWritePolicy.forContext(context).captureTicket()
        val enqueues=AtomicInteger();val backend=AtomicInteger()
        val manager=CodingJobManager(files,CodingJobManager.Backend {_,_,_,_,_,_,_->backend.incrementAndGet();throw AssertionError("No repair dispatch")},
            executor=Executor {enqueues.incrementAndGet();it.run()})
        executor.beforeInterrupt={
            // Prepare only the real parent directory while the flag is clear. The producer still
            // writes all candidate bytes and fails at its own DIRECTORY_SYNC after rename.
            val reservation = requireNotNull(executor.reservation.get())
            val directory = File(StorageGeneration(files).directory("crew-context"),
                ProjectScope.sha256(reservation.binding().source.crew.executionSessionId.toByteArray())+"/coding-jobs")
            assertTrue(directory.parentFile.isDirectory)
            assertTrue(directory.isDirectory || directory.mkdir())
            CodingJobJournalWriter.RealBackend.syncDirectory(directory.parentFile)
            if(quiesceBeforeFailure)manager.requestQuiesce()
            Unit
        }
        val scopeRef=AtomicReference<ProjectScope>();val ownerRef=AtomicReference<String>()
        try {
            CodingSubmissionTestSource.runAsync(context) {invocation,token->
                val source=invocation.crewSource().source();val scope=ProjectScopeStore(files).open(source.executionSessionId)
                val owner="${source.executionSessionId}/${source.botId}/1";scopeRef.set(scope);ownerRef.set(owner)
                try {
                    assertThrows(CodingJobJournalStore.UnconfirmedWrite::class.java) {
                        manager.startInstrumented(scope,owner,"synthetic-command",".",scope.version(),null,token,invocation,
                            ChatDatabaseBridge.getInstance(context),CodingJobEvidence.AuthorizationSnapshot(
                                CodingJobEvidence.Authorization.ALLOW,"native","project_exec","fixture","local_fixture"),ticket)
                    }
                    assertTrue(Thread.currentThread().isInterrupted)
                    assertNull(invocation.acceptedSubmission())
                } finally {Thread.interrupted()}
                CoreToolResult.failure("Journal revision unconfirmed; no dispatch")
            }
            manager.requestQuiesce().get(30,TimeUnit.SECONDS) // Pins, not a timed absence assertion, join the one automatic attempt.
            val reservation=requireNotNull(executor.reservation.get());val binding=reservation.binding()
            val scope=scopeRef.get();val owner=ownerRef.get()
            val target=File(StorageGeneration(files).directory("crew-context"),
                ProjectScope.sha256(binding.source.crew.executionSessionId.toByteArray())+"/coding-jobs/${binding.jobId}.json")
            val before=target.readBytes();val beforeRows=rows()
            assertEquals(0,enqueues.get());assertEquals(0,backend.get());assertEquals(0L,scope.version())
            assertThrows(Exception::class.java){manager.repairTechnical("foreign-owner",scope,binding.jobId)}
            assertArrayEquals(before,target.readBytes());assertEquals(beforeRows,rows())
            if(quiesceBeforeFailure) {
                assertEquals(CodingJobManager.TechnicalProjection.PENDING,manager.technicalProjection(owner,scope,binding.jobId))
                assertTrue(beforeRows.isEmpty())
                val pending=CodingJobJournalCodec.decode(before,before.size)
                assertEquals(1L,pending.record.revision);assertNull(pending.record.terminal)
                assertThrows(IllegalStateException::class.java){manager.repairTechnical(owner,scope,binding.jobId)}
                assertArrayEquals(before,target.readBytes());assertEquals(beforeRows,rows())
            } else {
                assertEquals(CodingJobManager.TechnicalProjection.CONFIRMED,manager.technicalProjection(owner,scope,binding.jobId))
                assertEquals(listOf("RESERVATION_ABANDONED"),beforeRows.map{it.first})
                val terminal=CodingJobEvidenceCodec.decode(beforeRows.single().second,beforeRows.single().second.length).record
                assertEquals(binding,terminal.binding);assertNotNull(terminal.terminal)
                assertEquals(CodingJobEvidence.Phase.SUBMISSION_INTENT,terminal.highestEffectPhase)
                assertEquals(CodingJobEvidence.TerminalState.NOT_STARTED,terminal.terminal.state)
                assertNull(terminal.terminal.exitCode);assertEquals(CodingJobEvidence.AbsenceCoverage.NOT_PROVEN,terminal.terminal.absenceCoverage)
                assertEquals(CodingJobManager.TechnicalProjection.CONFIRMED,manager.repairTechnical(owner,scope,binding.jobId))
                assertArrayEquals(before,target.readBytes());assertEquals(beforeRows,rows())
                val historyOwner=owner.substringBeforeLast('/')+"/2"
                manager.bindRecoveredOwners(historyOwner,scope,listOf(owner))
                assertEquals(binding.jobId,manager.list(historyOwner,scope).single().id)
                assertThrows(Exception::class.java){manager.repairTechnical(historyOwner,scope,binding.jobId)}
                assertArrayEquals(before,target.readBytes());assertEquals(beforeRows,rows())
            }
        } finally {manager.requestQuiesce().get(30,TimeUnit.SECONDS)}
    }
    @Test fun actualPendingFirstJournalRepairsAsReservationAbandonedWithoutInventingAcceptance()=exercise(false)
    @Test fun quiesceBeforeFirstJournalFailureBlocksFreshRepairPinAndPreservesExactPendingBytes()=exercise(true)
}
