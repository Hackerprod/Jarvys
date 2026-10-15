package com.jarvys.agent.chatdb

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewResultEvidence
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation
import com.jarvys.agent.workspaces.WorkspaceResultReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StrictJournalRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val databaseExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "strict-journal-test").apply { isDaemon = true } }
    private lateinit var database: JarvysChatDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var repository: StrictJournalRepository
    private lateinit var artifacts: ConversationArtifactsRepository

    @Before fun openDatabase() {
        database = JarvysChatDatabaseProvider.openDatabaseFileForTests(context, File(temporary.root, "strict-journal.db"))
        conversations = ConversationRepository(database)
        repository = StrictJournalRepository(database)
        artifacts = ConversationArtifactsRepository(database)
    }

    @After fun closeDatabase() {
        if (::database.isInitialized) JarvysChatDatabaseProvider.closeDatabaseFileForTests(database)
        databaseExecutor.shutdownNow()
    }

    private fun <T> onDatabaseThread(block: suspend () -> T): T =
        databaseExecutor.submit<T> { runBlocking { block() } }.get(45, TimeUnit.SECONDS)

    @Test fun resultEvidenceIsIdempotentImmutableExactUtf16AndConversationScoped() = onDatabaseThread {
        val workspace = workspaceId(1)
        val execution = WorkspaceIdentity.executionSessionId(workspace)
        ensure(execution)
        ensure(WorkspaceIdentity.executionSessionId(workspaceId(2)))
        val mission = missionId(workspace, "request-a")
        val first = CrewResultEvidence.create(execution, mission, "bot-a", uuid(3), "DONE", "result\uD800tail", 10_001L)

        assertEquals(first.evidenceId, success(repository.appendCrewResultEvidence(first)).evidenceId)
        assertEquals(first.evidenceId, success(repository.appendCrewResultEvidence(first)).evidenceId)
        val conflictingPayload = CrewResultEvidence.create(execution, mission, "bot-a", first.cycleId, "DONE", "different", 10_001L)
        assertTrue(repository.appendCrewResultEvidence(conflictingPayload) is ChatDbResult.Conflict)

        val stored = database.testChatSql.query(
            "SELECT result_text FROM crew_result_evidence WHERE conversation_id=? AND evidence_id=?", arrayOf(execution, first.evidenceId),
        ).use { c -> assertTrue(c.moveToFirst()); c.getBlob(0) }
        assertArrayEquals(ExactUtf16.encode(first.resultText), stored)

        val otherSession = WorkspaceIdentity.executionSessionId(workspaceId(2))
        val sameLogicalFields = CrewResultEvidence.create(otherSession, missionId(workspaceId(2), "request-a"), "bot-a", uuid(3), "DONE", "result\uD800tail", 10_001L)
        assertEquals(sameLogicalFields.evidenceId, success(repository.appendCrewResultEvidence(sameLogicalFields)).evidenceId)
        assertEquals(1L, count("SELECT COUNT(*) FROM crew_result_evidence WHERE evidence_id=?", arrayOf(sameLogicalFields.evidenceId)))
    }

    @Test fun resultReferenceAndCentralNoticeRequireExactExecutionEvidence() = onDatabaseThread {
        val fixture = resultFixture()
        val noSource = WorkspaceResultReference.create(
            CrewResultEvidence.create(fixture.execution, fixture.mission, "bot-a", fixture.cycle, "DONE", "result", 11_001L),
            WorkspaceIdentity.CENTRAL_SESSION, fixture.workspace, uuid(12), "request-a", 1L,
        )
        assertTrue(repository.appendWorkspaceResultNotice(noSource) is ChatDbResult.Conflict)
        assertEquals(0L, count("SELECT COUNT(*) FROM workspace_result_refs WHERE reference_id=?", arrayOf(noSource.referenceId)))

        assertTrue(repository.appendCrewResultEvidence(fixture.result) is ChatDbResult.Success)
        val ref = WorkspaceResultReference.create(fixture.result, WorkspaceIdentity.CENTRAL_SESSION, fixture.workspace, uuid(12), "request-a", 1L)
        assertTrue(ref.samePayload(success(repository.appendWorkspaceResultReference(ref))))
        assertTrue(ref.samePayload(success(repository.appendWorkspaceResultReference(ref))))
        val changedObservation = WorkspaceResultReference.create(fixture.result, WorkspaceIdentity.CENTRAL_SESSION, fixture.workspace, uuid(12), "request-a", 2L)
        assertTrue(repository.appendWorkspaceResultReference(changedObservation) is ChatDbResult.Conflict)

        assertTrue(ref.samePayload(success(repository.appendWorkspaceResultNotice(ref))))
        assertTrue(ref.samePayload(successValue(repository.readWorkspaceResultReference(fixture.execution, ref.referenceId, false))))
        assertTrue(ref.samePayload(successValue(repository.readWorkspaceResultReference(WorkspaceIdentity.CENTRAL_SESSION, ref.referenceId, true))))
        assertEquals(2L, count("SELECT COUNT(*) FROM workspace_result_refs WHERE reference_id=?", arrayOf(ref.referenceId)))
    }

    @Test fun coordinationRowsKeepUtf16RefsAndReplyTransitionsAreMonotonic() = onDatabaseThread {
        val fixture = coordinationFixture()
        assertEquals(fixture.evidence.evidenceId, success(repository.appendCrewCoordinationEvidence(fixture.evidence)).evidenceId)
        assertEquals(fixture.evidence.evidenceId, success(repository.appendCrewCoordinationEvidence(fixture.evidence)).evidenceId)
        val altered = CrewCoordinationEvidence.create(fixture.execution, fixture.mission, "bot-a", fixture.cycle,
            "message-a", "QUESTION", "other text", listOf("ref-a"), fixture.evidence.producedAtMillis)
        assertTrue(repository.appendCrewCoordinationEvidence(altered) is ChatDbResult.Conflict)

        val storedText = database.testChatSql.query(
            "SELECT text FROM crew_coordination_evidence WHERE conversation_id=? AND evidence_id=?",
            arrayOf(fixture.execution, fixture.evidence.evidenceId),
        ).use { c -> assertTrue(c.moveToFirst()); c.getBlob(0) }
        assertArrayEquals(ExactUtf16.encode(fixture.evidence.text), storedText)
        val storedRef = database.testChatSql.query(
            "SELECT ref FROM crew_coordination_evidence_refs WHERE conversation_id=? AND evidence_id=? ORDER BY ordinal",
            arrayOf(fixture.execution, fixture.evidence.evidenceId),
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        assertEquals("ref-a", storedRef)

        val ref = WorkspaceCoordinationReference.create(fixture.evidence, WorkspaceIdentity.CENTRAL_SESSION,
            fixture.workspace, uuid(12), "request-a", 1L)
        assertTrue(ref.samePayload(success(repository.appendWorkspaceCoordinationReference(ref))))
        assertTrue(ref.samePayload(success(repository.appendWorkspaceCoordinationNotice(ref))))
        assertTrue(ref.samePayload(successValue(repository.readWorkspaceCoordinationReference(fixture.execution, ref.referenceId, false))))
        assertTrue(ref.samePayload(successValue(repository.readWorkspaceCoordinationReference(WorkspaceIdentity.CENTRAL_SESSION, ref.referenceId, true))))

        val prepared = WorkspaceCoordinationReply.create(ref, "reply-key", "answer", "central-request", "question", 20_000L)
        assertEquals(prepared.replyId, success(repository.prepareWorkspaceCoordinationReply(prepared)).replyId)
        assertTrue(repository.markWorkspaceCoordinationReplyAccepted(
            prepared.withState(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL, 20_002L),
        ) is ChatDbResult.Conflict)
        val started = prepared.withState(WorkspaceCoordinationReply.State.STARTED, 20_001L)
        assertEquals(started.state, success(repository.markWorkspaceCoordinationReplyStarted(started)).state)
        assertTrue(repository.markWorkspaceCoordinationReplyStarted(started) is ChatDbResult.Conflict)
        val accepted = prepared.withState(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL, 20_002L)
        assertEquals(accepted.state, success(repository.markWorkspaceCoordinationReplyAccepted(accepted)).state)
        assertEquals(accepted.state, successValue(repository.readWorkspaceCoordinationReply(WorkspaceIdentity.CENTRAL_SESSION, ref.referenceId)).state)
        assertEquals(2L, count("SELECT COUNT(*) FROM workspace_coordination_reply_transitions WHERE conversation_id=? AND reply_id=?", arrayOf(WorkspaceIdentity.CENTRAL_SESSION, accepted.replyId)))

        val answerBlob = database.testChatSql.query(
            "SELECT text,original_user_request FROM workspace_coordination_replies WHERE conversation_id=? AND reference_id=?",
            arrayOf(WorkspaceIdentity.CENTRAL_SESSION, ref.referenceId),
        ).use { c -> assertTrue(c.moveToFirst()); c.getBlob(0) to c.getBlob(1) }
        assertArrayEquals(ExactUtf16.encode(prepared.text), answerBlob.first)
        assertArrayEquals(ExactUtf16.encode(prepared.originalUserRequest), answerBlob.second)
    }

    @Test fun incorporationKeepsFirstObservationAndConversationScopedReceiptIdentity() = onDatabaseThread {
        val fixture = coordinationFixture()
        success(repository.appendCrewCoordinationEvidence(fixture.evidence))
        val ref = WorkspaceCoordinationReference.create(fixture.evidence, WorkspaceIdentity.CENTRAL_SESSION,
            fixture.workspace, uuid(12), "request-a", 1L)
        success(repository.appendWorkspaceCoordinationReference(ref))
        success(repository.appendWorkspaceCoordinationNotice(ref))
        val prepared = WorkspaceCoordinationReply.create(ref, "reply-key", "answer", "central-request", "question", 30_000L)
        success(repository.prepareWorkspaceCoordinationReply(prepared))
        success(repository.markWorkspaceCoordinationReplyStarted(prepared.withState(WorkspaceCoordinationReply.State.STARTED, 30_001L)))
        val accepted = prepared.withState(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL, 30_002L)
        success(repository.markWorkspaceCoordinationReplyAccepted(accepted))

        val receipt = WorkspaceInputIncorporation.create(
            WorkspaceInputIncorporation.SourceKind.REPLY, accepted.replyId, ref.referenceId, accepted.mailboxMessageId,
            WorkspaceIdentity.CENTRAL_SESSION, fixture.workspace, fixture.execution, uuid(12), "request-a", fixture.mission,
            "bot-a", fixture.cycle, uuid(14), accepted.payloadSha256, "a".repeat(64), "INBOX", "b".repeat(64),
            "c".repeat(64), 30_010L, 30_011L,
        )
        assertTrue(receipt.samePayload(success(repository.appendWorkspaceInputIncorporation(receipt))))
        val laterCheckpoint = WorkspaceInputIncorporation.create(
            WorkspaceInputIncorporation.SourceKind.REPLY, accepted.replyId, ref.referenceId, accepted.mailboxMessageId,
            WorkspaceIdentity.CENTRAL_SESSION, fixture.workspace, fixture.execution, uuid(12), "request-a", fixture.mission,
            "bot-a", fixture.cycle, uuid(14), accepted.payloadSha256, "a".repeat(64), "INBOX", "b".repeat(64),
            "d".repeat(64), 30_020L, 30_021L,
        )
        assertTrue(receipt.samePayload(success(repository.appendWorkspaceInputIncorporation(laterCheckpoint))))
        assertTrue(receipt.samePayload(successValue(repository.readWorkspaceInputIncorporation(
            WorkspaceIdentity.CENTRAL_SESSION, WorkspaceInputIncorporation.SourceKind.REPLY, accepted.replyId,
        ))))
        assertEquals(receipt.checkpointPayloadSha256, success(repository.listWorkspaceInputIncorporations(
            WorkspaceIdentity.CENTRAL_SESSION, 10,
        )).single().checkpointPayloadSha256)
        assertNull(success(repository.readWorkspaceInputIncorporation(
            WorkspaceIdentity.CENTRAL_SESSION, WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT, "another-source",
        )))

        database.testChatSql.execSQL(
            "UPDATE workspace_input_incorporations SET source_reference_id=NULL WHERE conversation_id=? AND receipt_id=?",
            arrayOf(WorkspaceIdentity.CENTRAL_SESSION, receipt.receiptId),
        )
        val corruption = runCatching {
            repository.readWorkspaceInputIncorporation(
                WorkspaceIdentity.CENTRAL_SESSION, WorkspaceInputIncorporation.SourceKind.REPLY, accepted.replyId,
            )
        }.exceptionOrNull()
        assertTrue(corruption is IllegalStateException)
        assertEquals("Corrupt workspace_input_incorporations row: source_reference_id is null", corruption?.message)
    }

    @Test fun failedInsertRollsBackAllocatedSequenceAndSpineItem() = onDatabaseThread {
        val fixture = resultFixture()
        success(repository.appendCrewResultEvidence(fixture.result))
        val ref = WorkspaceResultReference.create(fixture.result, WorkspaceIdentity.CENTRAL_SESSION,
            fixture.workspace, uuid(12), "request-a", 1L)
        val beforeSeq = conversationNextSeq(fixture.execution)
        val beforeItems = count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=?", arrayOf(fixture.execution))
        database.testChatSql.execSQL(
            "CREATE TRIGGER injected_workspace_ref_failure BEFORE INSERT ON workspace_result_refs BEGIN SELECT RAISE(ABORT,'injected failure'); END",
        )

        assertTrue(repository.appendWorkspaceResultReference(ref) is ChatDbResult.Conflict)
        assertEquals(beforeSeq, conversationNextSeq(fixture.execution))
        assertEquals(beforeItems, count("SELECT COUNT(*) FROM conversation_items WHERE conversation_id=?", arrayOf(fixture.execution)))
        assertEquals(0L, count("SELECT COUNT(*) FROM workspace_result_refs WHERE reference_id=?", arrayOf(ref.referenceId)))
        database.testChatSql.execSQL("DROP TRIGGER injected_workspace_ref_failure")
    }

    @Test fun strictAndArtifactReadSnapshotsRunWhileWriterTransactionIsHeld() {
        val fixture = onDatabaseThread {
            val value = resultFixture()
            success(repository.appendCrewResultEvidence(value.result))
            ensure("artifact-read")
            success(artifacts.appendDeliveredArtifact(DeliveredArtifactInput(
                conversationId = "artifact-read", artifactId = "artifact-read-1", name = "report.txt", mimeType = "text/plain",
                sizeBytes = 4L, kind = "FILE", relativePath = "chats/artifact-read/report.txt", sha256 = "d".repeat(64),
            )))
            value
        }
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val writerExecutor = Executors.newSingleThreadExecutor()
        val writer = writerExecutor.submit {
            runBlocking {
                database.withChatWriteTransaction {
                    success(repository.appendCrewResultEvidence(CrewResultEvidence.create(
                        fixture.execution, fixture.result.missionId, "bot-uncommitted", UUID.randomUUID().toString(),
                        "DONE", "uncommitted evidence", 1_800_000_000_001L,
                    )))
                    success(artifacts.appendDeliveredArtifact(DeliveredArtifactInput(
                        conversationId = "artifact-read", artifactId = "artifact-uncommitted", name = "pending.txt", mimeType = "text/plain",
                        sizeBytes = 7L, kind = "FILE", relativePath = "chats/artifact-read/pending.txt", sha256 = "e".repeat(64),
                    )))
                    writerEntered.countDown()
                    check(releaseWriter.await(10, TimeUnit.SECONDS)) { "timed out waiting to release the Room writer" }
                }
            }
        }
        try {
            assertTrue("the real Room writer has uncommitted strict and artifact changes", writerEntered.await(10, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val snapshots = onDatabaseThread {
                val evidence = success(repository.readCrewResultEvidence(fixture.execution, fixture.result.evidenceId))
                val counts = conversations.messageCounts(fixture.execution)
                val transcript = ModelTranscriptRepository(database).readModelTranscript(fixture.execution)
                val pendingCompactions = MemoryTimelineRepository(database).pendingCompactionIds(fixture.execution)
                val artifactIds = artifacts.artifactsForConversation("artifact-read").map { it.artifact_id }
                listOf<Any>(evidence!!.contentSha256, counts.total, transcript.entries.size, pendingCompactions.size, artifactIds)
            }
            assertTrue("the real projections finish with the writer held", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000L)
            assertEquals(fixture.result.contentSha256, snapshots[0])
            assertEquals(0L, snapshots[1])
            assertEquals(0, snapshots[2])
            assertEquals(0, snapshots[3])
            assertEquals(listOf("artifact-read-1"), snapshots[4])
        } finally {
            releaseWriter.countDown()
            runCatching { writer.get(10, TimeUnit.SECONDS) }
            writerExecutor.shutdownNow()
        }
    }

    @Test fun emptyFileDigestMeansUnknownAndCanBeCompletedLater() = onDatabaseThread {
        ensure("digest-compat")
        val path = "chats/digest-compat/report.bin"
        val unknown = success(artifacts.appendDeliveredArtifactImported(DeliveredArtifactInput(
            conversationId = "digest-compat", artifactId = "unknown-digest", name = "report.bin", mimeType = null,
            sizeBytes = 8L, kind = "FILE", relativePath = path, sha256 = "",
        )))
        val knownSha = "e".repeat(64)
        val completed = success(artifacts.appendDeliveredArtifact(DeliveredArtifactInput(
            conversationId = "digest-compat", artifactId = "known-digest", name = "report.bin", mimeType = null,
            sizeBytes = 8L, kind = "FILE", relativePath = path, sha256 = knownSha,
        )))
        assertEquals(unknown.file_ref_id, completed.file_ref_id)
        assertEquals(knownSha, database.testChatSql.query(
            "SELECT sha256 FROM file_refs WHERE id=?", arrayOf(completed.file_ref_id),
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })

        val laterUnknown = success(artifacts.appendDeliveredArtifactImported(DeliveredArtifactInput(
            conversationId = "digest-compat", artifactId = "later-unknown", name = "report.bin", mimeType = null,
            sizeBytes = 8L, kind = "FILE", relativePath = path, sha256 = "",
        )))
        assertEquals(completed.file_ref_id, laterUnknown.file_ref_id)
        assertEquals(knownSha, database.testChatSql.query(
            "SELECT sha256 FROM file_refs WHERE id=?", arrayOf(laterUnknown.file_ref_id),
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
    }

    @Test fun strictAndArtifactReadQueriesStayOnQueryExecutorAndUseBoundedStatements() {
        val fixtures = onDatabaseThread {
            val result = resultFixture()
            success(repository.appendCrewResultEvidence(result.result))
            val resultRef = WorkspaceResultReference.create(result.result, WorkspaceIdentity.CENTRAL_SESSION,
                result.workspace, uuid(40), "request-a", 1L)
            success(repository.appendWorkspaceResultReference(resultRef))
            success(repository.appendWorkspaceResultNotice(resultRef))

            val coordination = coordinationFixture()
            success(repository.appendCrewCoordinationEvidence(coordination.evidence))
            val coordinationRef = WorkspaceCoordinationReference.create(coordination.evidence, WorkspaceIdentity.CENTRAL_SESSION,
                coordination.workspace, uuid(41), "request-a", 1L)
            success(repository.appendWorkspaceCoordinationReference(coordinationRef))
            success(repository.appendWorkspaceCoordinationNotice(coordinationRef))

            ensure("artifact-batch")
            success(artifacts.appendDeliveredArtifact(DeliveredArtifactInput(
                conversationId = "artifact-batch", artifactId = "artifact-batch-1", name = "one.txt", mimeType = "text/plain",
                sizeBytes = 1L, kind = "FILE", relativePath = "chats/artifact-batch/one.txt", sha256 = "f".repeat(64),
            )))
            val nativeWorkspace = workspaceId(42)
            val nativeExecution = WorkspaceIdentity.executionSessionId(nativeWorkspace)
            ensure(nativeExecution)
            val nativeMission = missionId(nativeWorkspace, "request-a")
            val sourceId = uuid(43)
            val incorporation = WorkspaceInputIncorporation.create(
                WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT, sourceId, sourceId, uuid(44), WorkspaceIdentity.CENTRAL_SESSION,
                nativeWorkspace, nativeExecution, uuid(45), "request-a", nativeMission, "bot-a", uuid(46), uuid(47),
                "a".repeat(64), "b".repeat(64), "USER", "c".repeat(64), "d".repeat(64), 48_000L, 48_001L,
            )
            success(repository.appendWorkspaceInputIncorporation(incorporation))
            Triple(result, coordination, incorporation)
        }
        val counts = mutableListOf<Int>()
        fun recordRead(block: suspend () -> Unit) {
            val count = AtomicInteger()
            runBlocking { ChatSqlInstrumentation.observeStatements({ count.incrementAndGet() }, block) }
            counts += count.get()
        }

        recordRead { repository.listWorkspaceResultReferences(fixtures.first.execution, isNotice = false, limit = 100) }
        recordRead { repository.listWorkspaceResultReferences(WorkspaceIdentity.CENTRAL_SESSION, isNotice = true, limit = 100) }
        recordRead { repository.listWorkspaceCoordinationReferences(fixtures.second.execution, isNotice = false, limit = 100) }
        recordRead { repository.listWorkspaceCoordinationReferences(WorkspaceIdentity.CENTRAL_SESSION, isNotice = true, limit = 100) }
        recordRead { repository.listWorkspaceInputIncorporations(WorkspaceIdentity.CENTRAL_SESSION, limit = 100) }
        recordRead { artifacts.artifactsForConversation("artifact-batch") }
        assertEquals("each list hydrates rows with a bounded SQL statement count", listOf(3, 3, 3, 3, 3, 1), counts)

        val ranOffMainLooper = AtomicReference<Boolean?>()
        val result = AtomicReference<CrewResultEvidence?>()
        Handler(Looper.getMainLooper()).post {
            result.set(runBlocking {
                ChatSqlInstrumentation.observeStatements({ ranOffMainLooper.set(Looper.myLooper() != Looper.getMainLooper()) }) {
                    success(repository.readCrewResultEvidence(fixtures.first.execution, fixtures.first.result.evidenceId))
                }
            })
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertCrewResultEvidenceEquals(fixtures.first.result, requireNotNull(result.get()))
        assertEquals("suspend repository reads run off the main looper", true, ranOffMainLooper.get())
    }

    @Test fun strictReadInsideWriterTransactionReusesWriterLeaseForReadYourWrites() = onDatabaseThread {
        val fixture = resultFixture()
        val readBack = database.withChatWriteTransaction {
            success(repository.appendCrewResultEvidence(fixture.result))
            successValue(repository.readCrewResultEvidence(fixture.execution, fixture.result.evidenceId))
        }
        assertCrewResultEvidenceEquals(fixture.result, readBack)
    }

    @Test fun beforeWriteCallbackCannotReenterAnotherRepository() {
        val fixture = onDatabaseThread { resultFixture() }
        val nestedConversationId = uuid(98)
        val readFailure = databaseExecutor.submit<Throwable?> {
            runBlocking {
                runCatching {
                    repository.appendCrewResultEvidence(fixture.result) {
                        runBlocking { conversations.isTombstoned(fixture.execution) }
                    }
                }.exceptionOrNull()
            }
        }.get(5, TimeUnit.SECONDS)
        val secondResult = CrewResultEvidence.create(
            fixture.execution, fixture.mission, "bot-a", uuid(99), "DONE", "second result", 21_002L,
        )
        val writeFailure = databaseExecutor.submit<Throwable?> {
            runBlocking {
                runCatching {
                    repository.appendCrewResultEvidence(secondResult) {
                        runBlocking { conversations.ensureConversation(nestedConversationId) }
                    }
                }.exceptionOrNull()
            }
        }.get(5, TimeUnit.SECONDS)

        assertTrue("beforeWrite must reject repository reads before changing dispatcher", readFailure is IllegalStateException)
        assertEquals("beforeWrite callbacks cannot call chat repositories or blocking facades", readFailure?.message)
        assertTrue("beforeWrite must reject repository writes before waiting for their writer lease", writeFailure is IllegalStateException)
        assertEquals("beforeWrite callbacks cannot call chat repositories or blocking facades", writeFailure?.message)
        assertEquals(0L, onDatabaseThread {
            count("SELECT COUNT(*) FROM crew_result_evidence WHERE conversation_id=? AND evidence_id IN (?,?)",
                arrayOf(fixture.execution, fixture.result.evidenceId, secondResult.evidenceId))
        })
        assertEquals(0L, onDatabaseThread {
            count("SELECT COUNT(*) FROM conversations WHERE id=?", arrayOf(nestedConversationId))
        })
    }

    private suspend fun resultFixture(): ResultFixture {
        val workspace = workspaceId(20)
        val execution = WorkspaceIdentity.executionSessionId(workspace)
        ensure(execution)
        ensure(WorkspaceIdentity.CENTRAL_SESSION)
        val mission = missionId(workspace, "request-a")
        val cycle = uuid(21)
        val result = CrewResultEvidence.create(execution, mission, "bot-a", cycle, "DONE", "result", 21_001L)
        return ResultFixture(workspace, execution, mission, cycle, result)
    }

    private suspend fun coordinationFixture(): CoordinationFixture {
        val workspace = workspaceId(30)
        val execution = WorkspaceIdentity.executionSessionId(workspace)
        ensure(execution)
        ensure(WorkspaceIdentity.CENTRAL_SESSION)
        val mission = missionId(workspace, "request-a")
        val cycle = uuid(31)
        val evidence = CrewCoordinationEvidence.create(execution, mission, "bot-a", cycle, "message-a", "QUESTION",
            "coord\uD800text", listOf("ref-a"), 31_001L)
        return CoordinationFixture(workspace, execution, mission, cycle, evidence)
    }

    private suspend fun ensure(conversationId: String) {
        assertTrue(conversations.ensureConversation(conversationId) is ChatDbResult.Success)
    }

    private fun conversationNextSeq(id: String): Long = database.testChatSql.query(
        "SELECT next_seq FROM conversations WHERE id=?", arrayOf(id),
    ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }

    private fun assertCrewResultEvidenceEquals(expected: CrewResultEvidence, actual: CrewResultEvidence) {
        assertEquals("evidenceId", expected.evidenceId, actual.evidenceId)
        assertEquals("conversationId", expected.conversationId, actual.conversationId)
        assertEquals("missionId", expected.missionId, actual.missionId)
        assertEquals("botId", expected.botId, actual.botId)
        assertEquals("cycleId", expected.cycleId, actual.cycleId)
        assertEquals("reportedStatus", expected.reportedStatus, actual.reportedStatus)
        assertEquals("resultText", expected.resultText, actual.resultText)
        assertEquals("producedAtMillis", expected.producedAtMillis, actual.producedAtMillis)
        assertEquals("contentSha256", expected.contentSha256, actual.contentSha256)
    }

    private fun count(query: String, args: Array<out Any?>): Long = database.testChatSql.query(query, args)
        .use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }

    private fun <T> success(result: ChatDbResult<T>): T = when (result) {
        is ChatDbResult.Success -> result.value
        else -> throw AssertionError("Expected ChatDbResult.Success; got $result")
    }

    private fun <T : Any> successValue(result: ChatDbResult<T?>): T =
        requireNotNull(success(result)) { "Expected a stored strict journal row" }

    private fun workspaceId(seed: Int): String = uuid(seed)
    private fun uuid(seed: Int): String = UUID.nameUUIDFromBytes("strict-journal-$seed".toByteArray(StandardCharsets.UTF_8)).toString()
    private fun missionId(workspaceId: String, requestId: String): String = UUID.nameUUIDFromBytes(
        "jarvys-workspace-mission-v1\u0000$workspaceId\u0000$requestId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

    private data class ResultFixture(
        val workspace: String,
        val execution: String,
        val mission: String,
        val cycle: String,
        val result: CrewResultEvidence,
    )

    private data class CoordinationFixture(
        val workspace: String,
        val execution: String,
        val mission: String,
        val cycle: String,
        val evidence: CrewCoordinationEvidence,
    )
}
