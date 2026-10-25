package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.WorkspaceResultDelivery
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionAccess
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewProcessIdentity
import com.jarvys.agent.crew.CrewResultEvidence
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

/** B12: a Central message carries a durable pointer to the bot mission that its card resolves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MissionRefTest {
    @Rule @JvmField val chatTestStore = ChatTestStore(registerApplicationFilesDirectory = true)

    private lateinit var app: Application
    private lateinit var registry: WorkspaceRegistry
    private lateinit var ledger: LocalRunStore
    private lateinit var scope: WorkspaceRecord
    private lateinit var assignment: WorkspaceAssignment
    private val noAdmission = Runnable {}

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)
        registry = WorkspaceRegistry(app)
        ledger = LocalRunStore(app)
        assertTrue(ChatDatabaseBridge.getInstance(app)
            .ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION, "chat") is ChatDbResult.Success)
        scope = registry.createWorkspace("scope", "Synthetic scope")
        val prepared = registry.prepareAssignment(scope.workspaceId, "assignment", "request", "Synthetic request",
            "coding", "Synthetic mission", emptyList(), null, CrewMissionAccess.STANDARD)
        val started = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "process")
        assignment = registry.bindBot(scope.workspaceId, started.assignmentId, started.revision, "bot")
        ledger.appendConversationMessage(scope.executionSessionId, "user", "Synthetic source initialization")
    }

    @After fun close() { registry.close(); app.deleteDatabase(WorkspaceRegistry.DATABASE_NAME) }

    private fun cards() = ledger.readConversationTimeline(WorkspaceIdentity.CENTRAL_SESSION).filter { it.kind == "mission_ref" }

    private fun recordMission(processId: String = CrewProcessIdentity.ID, status: String = "RUNNING") {
        val bot = CrewBotSnapshot("bot", "coding", "Coding", "Coder", "blue", "Task", status, "", "", "",
            emptyList(), 1L, 0L)
        ledger.appendCrewMissionSnapshot(CrewMissionSnapshot(assignment.missionId, scope.executionSessionId,
            processId, "Synthetic mission", status, "", 1L, 0L, listOf(bot), emptyList()))
    }

    @Test fun delegationAppendsOneCardThatNamesTheExecutionSessionAndMission() {
        WorkspaceMissionCards.delegated(app, scope, assignment)
        WorkspaceMissionCards.delegated(app, scope, assignment)

        val card = cards().single().missionRef!!
        assertEquals(scope.workspaceId, card.workspaceId)
        assertEquals(scope.executionSessionId, card.executionSessionId)
        assertEquals(assignment.missionId, card.missionId)
        assertEquals(assignment.assignmentId, card.assignmentId)
        assertNull(card.resultId)
        assertFalse("a card is not a regenerable answer", cards().single().canRegenerate)
    }

    @Test fun theCardIsATimelineObjectOnlyAndNeverModelHistory() {
        WorkspaceMissionCards.delegated(app, scope, assignment)

        assertTrue(ledger.loadConversationContext(WorkspaceIdentity.CENTRAL_SESSION).isEmpty())
        assertTrue(ledger.readActiveConversationMessages(WorkspaceIdentity.CENTRAL_SESSION).isEmpty())
        assertEquals("mission_ref", ledger.readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION).single().optString("type"))
        assertFalse(ledger.isLatestActiveAssistant(WorkspaceIdentity.CENTRAL_SESSION,
            ledger.readConversationMessages(WorkspaceIdentity.CENTRAL_SESSION).single().optString("messageId")))
    }

    @Test fun aDeliveredResultAddsACardCarryingTheResultIdentityOnce() {
        val evidence = CrewResultEvidence.create(scope.executionSessionId, assignment.missionId, "bot",
            UUID.randomUUID().toString(), "DONE", "UNTRUSTED synthetic result", 1234L)
        ledger.appendCrewResultEvidence(evidence, noAdmission)
        val delivery = WorkspaceResultDelivery(app, ledger)

        delivery.reconcile(scope.workspaceId, CancellationToken.uncancellable())
        delivery.reconcile(scope.workspaceId, CancellationToken.uncancellable())

        val resultCards = cards().filter { it.missionRef!!.resultId != null }
        val card = resultCards.single().missionRef!!
        val notice = ledger.listWorkspaceResultNotices(WorkspaceIdentity.CENTRAL_SESSION, 50, null).single()
        assertEquals(notice.referenceId, card.resultId)
        assertEquals(notice.contentSha256, card.resultSha256)
        assertEquals(scope.executionSessionId, card.executionSessionId)
        assertFalse("the worker text never reaches the thread", resultCards.single().text.contains(evidence.resultText))
    }

    @Test fun reconciliationRecoversCardsLostAfterTheDelegationAndTheNotice() {
        // A crash after binding the bot and after the notice, before any card was written.
        val evidence = CrewResultEvidence.create(scope.executionSessionId, assignment.missionId, "bot",
            UUID.randomUUID().toString(), "DONE", "UNTRUSTED synthetic result", 1234L)
        ledger.appendCrewResultEvidence(evidence, noAdmission)
        val reference = WorkspaceResultReference.create(evidence, scope.ownerCentralSessionId, scope.workspaceId,
            assignment.assignmentId, assignment.requestId, assignment.revision)
        ledger.appendWorkspaceResultReferenceIfAbsent(reference, noAdmission)
        ledger.appendWorkspaceResultNoticeIfAbsent(reference, noAdmission)
        assertTrue(cards().isEmpty())

        val delivery = WorkspaceResultDelivery(app, ledger)
        delivery.reconcile(scope.workspaceId, CancellationToken.uncancellable())
        delivery.reconcile(scope.workspaceId, CancellationToken.uncancellable())

        val refs = cards().map { it.missionRef!! }
        assertEquals("one delegation card and one result card, each exactly once", 2, refs.size)
        assertEquals(1, refs.count { it.resultId == null })
        assertEquals(listOf(reference.referenceId), refs.mapNotNull { it.resultId })
    }
    @Test fun resolutionDerivesTheMissionFromTheRegistryAndTheRecordedSnapshots() {
        val ref = MissionRef.of(scope.workspaceId, assignment.assignmentId, assignment.missionId)
        assertNull("no snapshot yet", MissionRefResolver.resolve(app, ref))

        recordMission()
        assertEquals("RUNNING", MissionRefResolver.resolve(app, ref)!!.status)

        assertNull(MissionRefResolver.resolve(app, ref.copy(missionId = "another-mission")))
        assertNull(MissionRefResolver.resolve(app, ref.copy(assignmentId = UUID.randomUUID().toString())))
        val other = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString())
        assertNull("an unregistered workspace never resolves",
            MissionRefResolver.resolve(app, ref.copy(workspaceId = WorkspaceIdentity.workspaceId(other), executionSessionId = other)))
    }

    @Test fun aSnapshotRecordedByAnotherProcessIsShownAsInterrupted() {
        recordMission(processId = "previous-process", status = "RUNNING")

        val resolved = MissionRefResolver.resolve(app, MissionRef.of(scope.workspaceId, assignment.assignmentId, assignment.missionId))
        assertNotNull(resolved)
        assertEquals("INTERRUPTED", resolved!!.status)
    }

    @Test fun aReferenceThatIsNotCoherentIsNotAMissionCard() {
        val ref = MissionRef.of(scope.workspaceId, assignment.assignmentId, assignment.missionId)
        assertEquals(ref, MissionRef.fromJson(ref.toJson()))
        assertNull(MissionRef.fromJson("not json"))
        assertNull("execution session of another workspace", MissionRef.fromJson(JSONObject(ref.toJson())
            .put("workspace_id", UUID.randomUUID().toString()).toString()))
        assertNull("blank mission", MissionRef.fromJson(ref.copy(missionId = "").toJson()))
    }
}
