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

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class CodingJobDesiredTerminalRepairTest {
    @get:Rule val chat=ChatTestStore(true)
    @Test fun interruptedTerminalWriteRepairsOriginalSuccessfulFactsWithoutRepeatingCommand() {
        val files=chat.newFilesDirectory();chat.localRunStore(files)
        val context=object:ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=files
        }
        val database=JarvysChatDatabaseProvider.getInstance(context)
        val ticket=MemoryWritePolicy.forContext(context).captureTicket()
        val queued=AtomicReference<Runnable>();val executions=AtomicInteger();val enqueues=AtomicInteger()
        val callback=AtomicReference<CodingJobManager.Snapshot>();val callbackInterrupted=AtomicBoolean()
        val workerInterrupted=AtomicBoolean();val workerFailure=AtomicReference<Throwable>()
        val preTerminalRevision=AtomicLong()
        val accepted=AtomicReference<CodingJobAcceptance>();val owner=AtomicReference<String>();val scope=AtomicReference<ProjectScope>()
        val manager=CodingJobManager(files,CodingJobManager.Backend { actualScope,_,_,_,_,_,launch ->
            launch();assertEquals(0,executions.getAndIncrement())
            File(actualScope.rootDirectory(),"synthetic-once").writeText("local fixture")
            val bytes=accepted.get().submissionCommit().target().readBytes()
            val beforeTerminal=CodingJobJournalCodec.decode(bytes,bytes.size).record
            assertEquals(3L,beforeTerminal.revision)
            assertEquals(CodingJobEvidence.Phase.LAUNCH_INTENT,beforeTerminal.highestEffectPhase)
            preTerminalRevision.set(beforeTerminal.revision)
            // Only the synthetic worker is interrupted; no test clears it to help production IO.
            Thread.currentThread().interrupt()
            LinuxExecResult(0)
        },executor=Executor {assertEquals(0,enqueues.getAndIncrement());assertTrue(queued.compareAndSet(null,it))})
        try {
            CodingSubmissionTestSource.runAsync(context) { invocation,token ->
                val source=invocation.crewSource().source()
                scope.set(ProjectScopeStore(files).open(source.executionSessionId))
                owner.set("${source.executionSessionId}/${source.botId}/1")
                manager.startInstrumented(scope.get(),owner.get(),"synthetic-command",".",scope.get().version(),null,
                    token,invocation,ChatDatabaseBridge.getInstance(context),CodingJobEvidence.AuthorizationSnapshot(
                        CodingJobEvidence.Authorization.ALLOW,"native","project_exec","fixture","local_fixture"),ticket,
                    completion={ snapshot ->
                        assertTrue(callback.compareAndSet(null,snapshot))
                        callbackInterrupted.set(Thread.currentThread().isInterrupted)
                    })
                accepted.set(requireNotNull(invocation.acceptedSubmission()))
                CoreToolResult.success("Accepted, not command completion")
            }
            val worker=Thread({
                try {requireNotNull(queued.get()).run()}
                catch(failure:Throwable){workerFailure.set(failure)}
                finally {workerInterrupted.set(Thread.currentThread().isInterrupted)}
            },"desired-terminal-fixture").apply {isDaemon=true}
            worker.start();worker.join(30_000);assertFalse(worker.isAlive)
            workerFailure.get()?.let {throw AssertionError(it)}
            // finish captures this immutable snapshot before scheduling repair. It is not a racy
            // reread of manager state: UNCERTAIN proves the original terminal persist failed.
            assertEquals(CodingJobManager.State.UNCERTAIN,requireNotNull(callback.get()).state)
            assertNull(callback.get().exitCode)
            assertTrue(callbackInterrupted.get());assertTrue(workerInterrupted.get())
            manager.requestQuiesce().get(30,TimeUnit.SECONDS)
            val binding=accepted.get().reservation().binding()
            val projected=runBlocking {JarvysChatDatabaseProvider.onQueryExecutor(database) {
                database.withChatReadTransaction {
                    database.chatSql.query("SELECT evidence_json FROM coding_job_observations WHERE job_id=? AND observation_kind='JOURNAL_PROJECTION'",arrayOf(binding.jobId)).use {c ->
                        assertTrue(c.moveToFirst());val result=c.getString(0);assertFalse(c.moveToNext());result
                    }
                }
            }}
            val terminal=CodingJobEvidenceCodec.decode(projected,projected.length).record
            assertEquals(binding,terminal.binding)
            assertEquals(preTerminalRevision.get()+1,terminal.revision)
            assertEquals(CodingJobEvidence.TerminalState.SUCCEEDED,terminal.terminal.state)
            assertEquals(0,terminal.terminal.exitCode)
            val bytes=accepted.get().submissionCommit().target().readBytes()
            val journal=CodingJobJournalCodec.decode(bytes,bytes.size)
            assertEquals(terminal,journal.record)
            assertEquals(terminal.revision,journal.record.revision)
            assertEquals(CodingJobEvidence.TerminalState.SUCCEEDED,journal.record.terminal.state)
            assertEquals(0,journal.record.terminal.exitCode)
            assertEquals(CodingJobManager.TechnicalProjection.CONFIRMED,manager.technicalProjection(owner.get(),scope.get(),binding.jobId))
            assertEquals(CodingJobManager.State.SUCCEEDED,manager.list(owner.get(),scope.get()).single().state)
            assertEquals(1,enqueues.get());assertEquals(1,executions.get());assertEquals(1L,scope.get().version())
            assertNull(accepted.get().submissionCommit().record().terminal)
        } finally {manager.requestQuiesce().get(30,TimeUnit.SECONDS)}
    }
}
