package com.jarvys.agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.CodingJobReservation
import com.jarvys.agent.coding.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real issuer/bytes and real Crew reservation. No decoded record or fake backend mints a Commit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodingJobJournalRepairTest {
    @get:Rule val chat = ChatTestStore(true)
    private class F(val files: File, val owner: CodingJobOwnerLease, val initialPin: CodingJobOwnerLease.Pin,
        val submission: CodingJobSubmission, val reservation: CodingJobReservation, val scope: ProjectScope) {
        val store = submission.journal()
        val binding = reservation.binding()
        fun record(revision: Long, terminal: Boolean = false) = CodingJobEvidence.Record(binding, revision,
            CodingJobEvidence.Phase.SUBMISSION_INTENT, true, null,
            if (!terminal) null else CodingJobEvidence.TerminalFacts(CodingJobEvidence.TerminalState.NOT_STARTED,
                null, false, false, false, CodingJobEvidence.AbsenceCoverage.NOT_PROVEN))
        fun snapshot(terminal: Boolean = false) = CodingJobSnapshot(binding.jobId,binding.owner,binding.scopeId,
            ".","synthetic-command",scope.rootDirectory().canonicalPath,binding.source.crew.executionSessionId,
            if(terminal) CodingJobSnapshot.State.NOT_STARTED else CodingJobSnapshot.State.LAUNCH_INTENT,
            1L,null,if(terminal) 2L else null,null,"coding-job:${binding.jobId}/log",null,0L,terminal)
        fun write(revision: Long, terminal: Boolean = false) = store.write(reservation,revision-1,record(revision,terminal),snapshot(terminal))
        fun uncertainWrite(revision: Long, terminal: Boolean = false) {
            Thread.currentThread().interrupt()
            try {
                assertThrows(CodingJobJournalStore.UnconfirmedWrite::class.java) { write(revision,terminal) }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
        }
        fun abandon() { submission.abandonBeforeWorkerClaim(); initialPin.close() }
    }
    private fun withReservation(action: (F) -> Unit) {
        val files=chat.newFilesDirectory(); chat.localRunStore(files)
        val context=object: ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        val owner=CodingJobOwnerLease.create(files); val pin=owner.pinSubmission()
        try {
            CodingSubmissionTestSource.runAsync(context) { invocation, _ ->
                val source=invocation.crewSource().source()
                val scope=ProjectScopeStore(files).open(source.executionSessionId)
                val submission=CodingJobSubmission.capture(invocation,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                    "${source.executionSessionId}/${source.botId}/1",scope.id(),source.scopeIdentity,pin,
                    MemoryWritePolicy.forContext(context).captureTicket(),CodingJobJournalStore.forFilesRoot(files))
                val reservation=ChatDatabaseBridge.getInstance(context).reserveCodingJobSync(submission)
                action(F(files,owner,pin,submission,reservation,scope))
                CoreToolResult.failure("Synthetic audit-only fixture, never a command dispatch")
            }
        } finally { pin.close(); owner.requestQuiesce().get(10,TimeUnit.SECONDS) }
    }
    @Test fun uncertainInitialHeadRepairsExactBytesThenAppendsOneTerminalWithIdempotentCas() = withReservation { f ->
        f.uncertainWrite(1); f.abandon()
        f.owner.pinSubmission().use { repair ->
            val first=requireNotNull(f.store.repairHead(f.reservation,repair))
            assertEquals(1L,first.record().revision)
            assertArrayEquals(CodingJobJournalCodec.encode(f.snapshot(),f.record(1)),first.copyPayload())
            assertSame(first,f.store.repairHead(f.reservation,repair))
            val terminal=f.store.writeTerminalRepair(f.reservation,1,f.record(2,true),f.snapshot(true),repair)
            assertEquals(2L,terminal.record().revision)
            assertSame(terminal,f.store.writeTerminalRepair(f.reservation,2,f.record(2,true),f.snapshot(true),repair))
            assertThrows(IllegalArgumentException::class.java) {
                f.store.writeTerminalRepair(f.reservation,2,f.record(3,true),f.snapshot(true),repair)
            }
            assertArrayEquals(terminal.copyPayload(),terminal.target().readBytes())
        }
    }
    @Test fun uncertainTerminalRepairsSameRevisionWithoutInventingAnotherTransition() = withReservation { f ->
        f.write(1); f.uncertainWrite(2,true); f.abandon()
        f.owner.pinSubmission().use { repair ->
            val terminal=requireNotNull(f.store.repairHead(f.reservation,repair))
            assertEquals(2L,terminal.record().revision)
            assertArrayEquals(CodingJobJournalCodec.encode(f.snapshot(true),f.record(2,true)),terminal.copyPayload())
            assertSame(terminal,f.store.repairHead(f.reservation,repair))
        }
    }
    @Test fun newPinMustBeFromExactOwnerAndClosedOriginalCannotAuthorizeRepair() = withReservation { f ->
        val initial=f.write(1); f.abandon()
        val other=CodingJobOwnerLease.create(f.files)
        try {
            other.pinSubmission().use { foreign ->
                assertThrows(IllegalStateException::class.java) { f.store.repairHead(f.reservation,foreign) }
            }
            assertThrows(IllegalStateException::class.java) { f.store.repairHead(f.reservation,f.initialPin) }
            assertArrayEquals(initial.copyPayload(),initial.target().readBytes())
        } finally { other.requestQuiesce().get(10,TimeUnit.SECONDS) }
    }
    @Test fun admittedRepairPinSurvivesQuiesceButNewPinCannotBeIssued() = withReservation { f ->
        val first=f.write(1); f.abandon()
        val repair=f.owner.pinSubmission()
        val draining=f.owner.requestQuiesce()
        try {
            assertFalse(draining.isDone)
            assertThrows(IllegalStateException::class.java) { f.owner.pinSubmission() }
            repair.fork().use { child ->
                assertThrows(IllegalStateException::class.java) { f.store.repairHead(f.reservation,child) }
            }
            assertSame(first,f.store.repairHead(f.reservation,repair))
            assertFalse(draining.isDone)
        } finally { repair.close() }
        draining.get(10,TimeUnit.SECONDS)
    }
    @Test fun thirdBytesRejectRepairWithoutOverwriteAndExactCandidateRemainsRepairable() = withReservation { f ->
        val first=f.write(1); f.uncertainWrite(2,true); f.abandon()
        val pendingBytes=first.target().readBytes()
        val third="unrelated synthetic bytes".toByteArray()
        first.target().writeBytes(third)
        f.owner.pinSubmission().use { repair ->
            assertThrows(IllegalStateException::class.java) { f.store.repairHead(f.reservation,repair) }
            assertArrayEquals(third,first.target().readBytes())
            first.target().writeBytes(pendingBytes)
            val terminal=requireNotNull(f.store.repairHead(f.reservation,repair))
            assertEquals(2L,terminal.record().revision)
            assertArrayEquals(pendingBytes,terminal.copyPayload())
        }
    }
    @Test fun replacedProjectDirectoryRejectsConfirmedHeadEvenWhenPayloadIsUnchanged() = withReservation { f ->
        val first=f.write(1); f.abandon()
        val root=f.scope.rootDirectory(); val moved=File(root.parentFile,root.name+"-fixture-original")
        assertTrue(root.renameTo(moved)); assertTrue(root.mkdir())
        try {
            f.owner.pinSubmission().use { repair ->
                assertThrows(java.io.IOException::class.java) { f.store.repairHead(f.reservation,repair) }
                assertArrayEquals(first.copyPayload(),first.target().readBytes())
            }
        } finally { assertTrue(root.delete()); assertTrue(moved.renameTo(root)) }
    }
    @Test fun noHeadInitializationRequiresAbandonmentAndOriginalOperationsToHaveDrained() = withReservation { f ->
        f.owner.pinSubmission().use { premature ->
            assertThrows(IllegalStateException::class.java) { f.store.repairHead(f.reservation,premature) }
        }
        val operation=f.submission.beginRoomOperation()
        f.submission.abandonBeforeWorkerClaim(); f.initialPin.close()
        f.owner.pinSubmission().use { repair ->
            try { assertThrows(IllegalStateException::class.java) { f.store.repairHead(f.reservation,repair) } }
            finally { operation.close() }
            assertNull(f.store.repairHead(f.reservation,repair))
            val first=f.store.initializeAbandoned(f.reservation,f.record(1),f.snapshot(),repair)
            assertEquals(1L,first.record().revision)
            val terminal=f.store.writeTerminalRepair(f.reservation,1,f.record(2,true),f.snapshot(true),repair)
            assertEquals(2L,terminal.record().revision)
            assertNull(f.submission.acceptanceOutcome())
            assertThrows(IllegalStateException::class.java) { f.submission.beginRoomOperation() }
            assertThrows(IllegalStateException::class.java) { f.submission.beginAcceptanceOperation() }
        }
    }
}
