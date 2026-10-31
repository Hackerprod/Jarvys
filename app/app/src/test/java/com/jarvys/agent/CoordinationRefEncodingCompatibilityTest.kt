package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.workspaces.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

/** Pre-write D3 proof: new BLOBs are staged only by the synthetic fixture, never by enabled production writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CoordinationRefEncodingCompatibilityTest {
    @get:Rule val storage = ChatTestStore(registerApplicationFilesDirectory = true)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    private lateinit var workspace: WorkspaceRecord
    private lateinit var assignment: WorkspaceAssignment
    private val store get() = LocalRunStore(context)
    private val db get() = JarvysChatDatabaseProvider.getInstance(context)

    @Before fun setup() {
        FreshStorageBootstrap.ensure(context)
        WorkspaceRegistry(context).use { registry ->
            workspace = registry.createWorkspace("refs-${UUID.randomUUID()}", "Exact reference compatibility")
            val prepared = registry.prepareAssignment(workspace.workspaceId, "assignment", "request", "Synthetic question",
                "analyst", "Inspect references", emptyList(), "Reader", com.jarvys.agent.crew.CrewMissionAccess.READ_ONLY)
            val started = registry.markStarted(workspace.workspaceId, prepared.assignmentId, prepared.revision, "fixture")
            assignment = registry.bindBot(workspace.workspaceId, prepared.assignmentId, started.revision, "worker")
        }
        ChatDatabaseBridge.getInstance(context).ensureConversationSync(central, "chat")
        ChatDatabaseBridge.getInstance(context).ensureConversationSync(workspace.executionSessionId, "workspace")
    }

    private fun execute(sql: String, vararg args: Any?) = runBlocking {
        val database = db
        database.withChatWriteTransaction { database.chatSql.execSQL(sql, args) }
    }
    private fun rows(sql: String, vararg args: Any?): List<List<String?>> = runBlocking {
        val database = db
        database.withChatReadTransaction { database.chatSql.query(sql, args).use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        } } }
    }
    private fun evidence(refs: List<String>) = CrewCoordinationEvidence.create(workspace.executionSessionId,
        assignment.missionId, "worker", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "QUESTION",
        "Exact body\u0000\uD800", refs, 1234L).also { store.appendCrewCoordinationEvidence(it) }
    private fun blobRefs(evidence: CrewCoordinationEvidence) = runBlocking {
        val database = db
        database.withChatWriteTransaction {
            evidence.refs.forEachIndexed { ordinal, ref -> database.chatSql.execSQL(
                "UPDATE crew_coordination_evidence_refs SET ref=? WHERE conversation_id=? AND evidence_id=? AND ordinal=?",
                arrayOf(ExactUtf16.encode(ref), evidence.conversationId, evidence.evidenceId, ordinal)) }
        }
    }
    private fun legacyTextRefs(evidence: CrewCoordinationEvidence) = runBlocking {
        val database = db
        database.withChatWriteTransaction {
            evidence.refs.forEachIndexed { ordinal, ref -> database.chatSql.execSQL(
                "UPDATE crew_coordination_evidence_refs SET ref=? WHERE conversation_id=? AND evidence_id=? AND ordinal=?",
                arrayOf(ref, evidence.conversationId, evidence.evidenceId, ordinal)) }
        }
    }
    private fun snapshot() = listOf(
        rows("SELECT name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') ORDER BY name"),
        rows("SELECT conversation_id,evidence_id,seq,schema_version,mission_id,bot_id,cycle_id,message_id,kind,hex(text),produced_at_ms,content_sha256 FROM crew_coordination_evidence ORDER BY evidence_id"),
        rows("SELECT conversation_id,evidence_id,ordinal,typeof(ref),hex(ref) FROM crew_coordination_evidence_refs ORDER BY evidence_id,ordinal"),
        rows("SELECT conversation_id,seq,item_kind,item_key FROM conversation_items ORDER BY conversation_id,seq"))
    private fun assertAllReaders(evidence: CrewCoordinationEvidence) {
        val actual = store.readCrewCoordinationEvidence(evidence.conversationId, evidence.evidenceId)!!
        assertEquals(evidence.toJson().toString(), actual.toJson().toString())
        assertEquals(evidence.refs, actual.refs)
        assertEquals(evidence.contentSha256, actual.contentSha256)
        val ref = WorkspaceCoordinationReference.create(evidence, central, workspace.workspaceId,
            assignment.assignmentId, assignment.requestId, assignment.revision)
        store.appendWorkspaceCoordinationReferenceIfAbsent(ref, Runnable {})
        store.appendWorkspaceCoordinationNoticeIfAbsent(ref, Runnable {})
        assertEquals(ref.referenceId, store.listWorkspaceCoordinationReferences(workspace.executionSessionId, 50, null)
            .single { it.referenceId == ref.referenceId }.referenceId)
        assertEquals(ref.contentSha256, store.listWorkspaceCoordinationNotices(central, 50, null)
            .single { it.referenceId == ref.referenceId }.contentSha256)
        val reply = store.prepareWorkspaceCoordinationReply(com.jarvys.agent.workspaces.WorkspaceCoordinationReply.create(ref,
            "reply-${evidence.messageId}", "Exact answer", "central-request", "Original request", 2000L), Runnable {})
        assertEquals(reply.toJson().toString(), store.readWorkspaceCoordinationReplyById(central, reply.replyId)!!.toJson().toString())
        assertEquals(reply.toJson().toString(), store.readWorkspaceCoordinationReply(central, ref.referenceId)!!.toJson().toString())
    }

    @Test fun oldTextNormalUnicodeAndEmptyRemainReadableWithoutRewrite() {
        val value = evidence(listOf("plain", "", "中文🚀é")); legacyTextRefs(value)
        assertEquals(listOf(listOf("text")), rows("SELECT DISTINCT typeof(ref) FROM crew_coordination_evidence_refs"))
        assertAllReaders(value)
        val before = snapshot()
        assertAllReaders(value)
        assertEquals(before, snapshot())
    }

    @Test fun exactBlobsPreserveEmptyNulLoneSurrogatesAndAllReadPathsAcrossReopen() {
        val value = evidence(listOf("", "\u0000", "a\u0000b", "\uD800", "\uDC00", "中文🚀é", "x\uD800y\uDC00"))
        blobRefs(value)
        assertEquals(listOf(listOf("blob")), rows("SELECT DISTINCT typeof(ref) FROM crew_coordination_evidence_refs"))
        assertAllReaders(value)
        // Old readers call getString irrespective of SQLite type: binary downgrade is unsupported.
        assertNotEquals(value.refs, rows("SELECT ref FROM crew_coordination_evidence_refs WHERE evidence_id=? ORDER BY ordinal",
            value.evidenceId).map { it.single() })
        val before = snapshot()
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        assertAllReaders(value)
        assertEquals(before, snapshot())
        assertEquals(value.evidenceId, store.appendCrewCoordinationEvidence(value).evidenceId)
        assertEquals(before, snapshot())
    }

    @Test fun mixedHistoricalAndExactRowsKeepTheirOriginalTypeAndIdentity() {
        val old = evidence(listOf("historical Unicode 🚀", "")); legacyTextRefs(old)
        val exact = evidence(listOf("new\u0000\uD800"))
        blobRefs(exact)
        assertAllReaders(old); assertAllReaders(exact)
        val before = snapshot()
        ChatDatabaseBridge.closeInstanceForTests(context.filesDir)
        assertAllReaders(exact); assertAllReaders(old)
        assertEquals(before, snapshot())
        assertEquals(setOf("text", "blob"), rows("SELECT DISTINCT typeof(ref) FROM crew_coordination_evidence_refs").map { it.single() }.toSet())
    }

    @Test fun nullIsRejectedWithoutMutationAndNoRefsIsDistinctFromEmptyRef() {
        val absent = evidence(emptyList()); val empty = evidence(listOf("")); blobRefs(empty)
        assertAllReaders(absent); assertAllReaders(empty)
        val before = snapshot()
        assertTrue(runCatching { execute("UPDATE crew_coordination_evidence_refs SET ref=NULL WHERE evidence_id=?", empty.evidenceId) }.isFailure)
        assertEquals(before, snapshot())
        assertEquals(emptyList<String>(), store.readCrewCoordinationEvidence(absent.conversationId, absent.evidenceId)!!.refs)
        assertEquals(listOf(""), store.readCrewCoordinationEvidence(empty.conversationId, empty.evidenceId)!!.refs)
    }

    @Test fun corruptOddBlobAndChangedExactPayloadFailClosedWithoutRepair() {
        val value = evidence(listOf("original")); blobRefs(value); assertAllReaders(value)
        for (corrupt in listOf(byteArrayOf(1), ExactUtf16.encode("different"))) {
            execute("UPDATE crew_coordination_evidence_refs SET ref=? WHERE evidence_id=?", corrupt, value.evidenceId)
            val before = snapshot()
            assertTrue(runCatching { store.readCrewCoordinationEvidence(value.conversationId, value.evidenceId) }.isFailure)
            assertTrue(runCatching { store.listWorkspaceCoordinationReferences(workspace.executionSessionId, 50, null) }.isFailure)
            assertTrue(runCatching { store.listWorkspaceCoordinationNotices(central, 50, null) }.isFailure)
            val ref = WorkspaceCoordinationReference.create(value, central, workspace.workspaceId,
                assignment.assignmentId, assignment.requestId, assignment.revision)
            assertTrue(runCatching { store.readWorkspaceCoordinationReply(central, ref.referenceId) }.isFailure)
            assertEquals(before, snapshot())
        }
    }

    @Test fun disabledWriteGateRetainsLegacyRepresentationBeforeCompatibilityPass() {
        if (StrictJournalRepository.NEW_EXACT_REF_WRITES_ENABLED) {
            // After the recorded gate passes, the same test proves the admitted writer's exact output.
            val value = evidence(listOf("admitted\u0000\uD800"))
            assertAllReaders(value)
            assertEquals(listOf(listOf("blob")), rows("SELECT DISTINCT typeof(ref) FROM crew_coordination_evidence_refs"))
        } else {
            val value = evidence(listOf("pre-write compatible"))
            assertEquals(listOf(listOf("text")), rows("SELECT DISTINCT typeof(ref) FROM crew_coordination_evidence_refs"))
            assertAllReaders(value)
        }
    }
}
