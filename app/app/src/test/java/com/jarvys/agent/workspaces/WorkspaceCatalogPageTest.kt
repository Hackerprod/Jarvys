package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.CoreAgentRuntime
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.crew.CrewMissionAccess
import com.jarvys.agent.crew.CrewCoordinationEvidence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceCatalogPageTest {
    @get:Rule val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @Test fun catalogHasTwoBoundedPagesWithoutPreparingAnyCrewRuntime() {
        val context = RuntimeEnvironment.getApplication()
        WorkspaceRegistry(context).use { store ->
            val expected = List(51) { store.createWorkspace("catalog-$it", "Workspace $it") }
            expected.forEach { assertNull(CoreAgentRuntime.crewManagerForSession(it.executionSessionId)) }
            val first = WorkspaceProjection.sidebarPage(context)
            assertEquals(50, first.rows.size)
            val second = WorkspaceProjection.sidebarPage(context, requireNotNull(first.nextCursor))
            assertEquals(1, second.rows.size)
            assertNull(second.nextCursor)
            assertEquals(expected.map { it.workspaceId }.toSet(), (first.rows + second.rows).map { it.id }.toSet())
            assertEquals(first.rows.map { it.id }, WorkspaceProjection.sidebarPage(context).rows.map { it.id })
            expected.forEach { assertNull("Catalog must not initialize workers", CoreAgentRuntime.crewManagerForSession(it.executionSessionId)) }
        }
    }

    @Test fun sidebarDerivesPendingAndUncertainCountsFromDurableInputsOnly() {
        val context = RuntimeEnvironment.getApplication()
        val ledger = LocalRunStore(context)
        WorkspaceRegistry(context).use { registry ->
            val scope = registry.createWorkspace("coordination-counts-${UUID.randomUUID()}", "Counts workspace")
            val prepared = registry.prepareAssignment(scope.workspaceId, "counts-assignment", "counts-request",
                "Synthetic request", "coding", "Synthetic mission", emptyList(), null, CrewMissionAccess.STANDARD)
            val started = registry.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "counts-process")
            val assignment = registry.bindBot(scope.workspaceId, started.assignmentId, started.revision, "counts-bot")
            for (session in listOf(WorkspaceIdentity.CENTRAL_SESSION, scope.executionSessionId)) {
                assertTrue(ChatDatabaseBridge.getInstance(context).ensureConversationSync(session, "chat") is ChatDbResult.Success)
            }
            ledger.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "Initialize central")
            ledger.appendConversationMessage(scope.executionSessionId, "user", "Initialize worker")

            val workerInputId = UUID.randomUUID().toString()
            ledger.appendConversationInput(ConversationInput(WorkspaceIdentity.CENTRAL_SESSION, workerInputId,
                "PRIVATE worker result body", ConversationInput.Origin.AGENT,
                ConversationInput.Provenance(scope.executionSessionId, scope.workspaceId, assignment.assignmentId,
                    "counts-bot", workerInputId, ConversationInput.Kind.RESULT,
                    ConversationInput.Provenance.PrincipalRole.SUBORDINATE)))
            val taskId = UUID.randomUUID().toString()
            ledger.appendConversationInput(ConversationInput(scope.executionSessionId, taskId,
                "PRIVATE supervisor task body", ConversationInput.Origin.AGENT,
                ConversationInput.Provenance(WorkspaceIdentity.CENTRAL_SESSION, scope.workspaceId,
                    assignment.assignmentId, "", taskId, ConversationInput.Kind.TASK,
                    ConversationInput.Provenance.PrincipalRole.SUPERVISOR)))
            val acknowledgedId = UUID.randomUUID().toString()
            val acknowledgedRowId = ledger.appendConversationInput(ConversationInput(scope.executionSessionId, acknowledgedId,
                "PRIVATE acknowledged body", ConversationInput.Origin.AGENT,
                ConversationInput.Provenance(WorkspaceIdentity.CENTRAL_SESSION, scope.workspaceId,
                    assignment.assignmentId, "", acknowledgedId, ConversationInput.Kind.MESSAGE,
                    ConversationInput.Provenance.PrincipalRole.SUPERVISOR)))
            ledger.markConversationMessagesIncorporated(scope.executionSessionId, listOf(acknowledgedRowId))

            val evidence = CrewCoordinationEvidence.create(scope.executionSessionId, assignment.missionId,
                requireNotNull(assignment.botId), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "QUESTION", "PRIVATE question body", emptyList(), System.currentTimeMillis())
            ledger.appendCrewCoordinationEvidence(evidence, Runnable {})
            val reference = WorkspaceCoordinationReference.create(evidence, scope.ownerCentralSessionId,
                scope.workspaceId, assignment.assignmentId, assignment.requestId, assignment.revision)
            ledger.appendWorkspaceCoordinationReferenceIfAbsent(reference, Runnable {})
            ledger.appendWorkspaceCoordinationNoticeIfAbsent(reference, Runnable {})
            val preparedReply = ledger.prepareWorkspaceCoordinationReply(WorkspaceCoordinationReply.create(
                reference, "counts-reply", "PRIVATE reply payload", "counts-central-request", "Synthetic request",
                System.currentTimeMillis()), Runnable {})
            ledger.markWorkspaceCoordinationReplyStarted(preparedReply, Runnable {})

            val row = WorkspaceProjection.sidebarPage(context).rows.single { it.id == scope.workspaceId }
            assertEquals(2, row.coordination.pendingInputs)
            assertEquals(1, row.coordination.uncertainReplies)
            assertFalse(row.coordination.pageLimited)
            assertFalse(row.coordination.unavailable)
            assertFalse(row.toString().contains("PRIVATE"))
        }
    }
}
