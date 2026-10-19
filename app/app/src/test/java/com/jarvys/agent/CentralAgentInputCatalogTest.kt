package com.jarvys.agent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceProjection
import com.jarvys.agent.workspaces.WorkspaceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CentralAgentInputCatalogTest {
    @get:Rule val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)

    @Test fun acknowledgingCentralAgentInputInvalidatesCatalogOnceAndRefreshesPendingCount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = chatTestStore.localRunStore(context.filesDir)
        assertTrue(ChatDatabaseBridge.getInstance(context).ensureConversationSync(
            WorkspaceIdentity.CENTRAL_SESSION, "central") is ChatDbResult.Success)
        val cursorId = store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "Initialize central")
        val scope = WorkspaceRegistry(context).use {
            it.createWorkspace("central-input-${UUID.randomUUID()}", "Central input")
        }
        val sourceMessageId = UUID.randomUUID().toString()
        val messageId = store.appendConversationInput(ConversationInput(
            WorkspaceIdentity.CENTRAL_SESSION,
            sourceMessageId,
            "Worker result awaiting Central",
            ConversationInput.Origin.AGENT,
            ConversationInput.Provenance("worker-session", scope.workspaceId, "assignment", "worker",
                sourceMessageId, ConversationInput.Kind.RESULT,
                ConversationInput.Provenance.PrincipalRole.SUBORDINATE),
        ))
        val projection = { WorkspaceProjection.sidebarPage(context).rows.single { it.id == scope.workspaceId } }
        assertEquals(1, projection().coordination.pendingInputs)

        val catalogBeforeAcknowledgement = com.jarvys.agent.workspaces.WorkspaceChanges.catalogRevision.value
        val provider = MainChatTurnContextProvider(store, WorkspaceIdentity.CENTRAL_SESSION, cursorId)
        val delivered = provider.takeDurableMessages()
        assertEquals(listOf(messageId), delivered.map { it.id })
        provider.durableMessagesIncorporated(delivered)

        assertEquals(catalogBeforeAcknowledgement + 1,
            com.jarvys.agent.workspaces.WorkspaceChanges.catalogRevision.value)
        assertEquals(0, projection().coordination.pendingInputs)
        assertEquals("incorporated", store.readConversationInputState(WorkspaceIdentity.CENTRAL_SESSION, messageId))

        provider.durableMessagesIncorporated(delivered)
        assertEquals("Repeated acknowledgement must not reload the catalog", catalogBeforeAcknowledgement + 1,
            com.jarvys.agent.workspaces.WorkspaceChanges.catalogRevision.value)
    }
}
