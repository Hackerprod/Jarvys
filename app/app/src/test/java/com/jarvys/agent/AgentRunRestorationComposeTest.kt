package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.chat.ConversationTimelineItem
import com.jarvys.agent.ui.chat.ConversationVisibility
import com.jarvys.agent.ui.chat.ToolGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRunRestorationComposeTest {
    @get:org.junit.Rule val chatTestStore = ChatTestStore(runTestOnWorker = false)
    @get:Rule val compose = createComposeRule()
    private val context: android.content.Context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [25], shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
    fun restoredDeliveredPreviewHidesOnlyItsToolResultBeforeGrouping() {
        val root = chatTestStore.newFilesDirectory()
        val session = "restored-preview-${System.nanoTime()}"
        val prepared = chatTestStore.withLocalRunStore(root) { store ->
            val userId = store.appendConversationMessage(session, "user", "Create a web page")
            val projectId = WorkspaceStore.projectIdForSession(session)
            val workspace = WorkspaceStore(com.jarvys.agent.StorageGeneration(root), projectId, null, null, session)
            workspace.write("index.html", "<!doctype html><h1>Restored page</h1>")
            val artifacts = DeliveredArtifactStore(root)
            val artifact = artifacts.snapshot(session, workspace, "index.html", null, CancellationToken.uncancellable())
            store.appendDeliveredFile(session, artifact)
            val preview = requireNotNull(artifacts.previewForAttachment(session, artifact))
            store.appendConversationToolPresentation(session, userId, "Build output", "tool_result", "preview-call",
                "{\"artifact_id\":\"delivered:${artifact.id}\"}", preview.token, "")
            store.appendConversationToolPresentation(session, userId, "Read output", "tool_result", "read-call",
                "Read completed", null, "")
            store.appendConversationToolPresentation(session, userId, "Write output", "tool_error", "write-call",
                "Write failed", null, "")
            artifact.id to store.readConversationTimeline(session)
        }
        val timeline = prepared.second
        val matchingResult = timeline.single { it.toolCallId == "preview-call" }
        val deliveredCard = timeline.single { it.kind == "artifact" }
        val projected = ConversationVisibility.project(timeline)
        assertFalse(projected.any { it.id == matchingResult.id })
        assertTrue(projected.any { it.kind == "artifact" && it.deliveredArtifact?.id == prepared.first })
        val toolGroup = ToolGrouping.project(projected).filterIsInstance<ConversationTimelineItem.ToolGroup>().single()
        assertEquals(2, toolGroup.toolCount)
        assertFalse(toolGroup.events.any { it.id == matchingResult.id })

        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AgentRunScreen(conversationKey = session, events = timeline, isRunning = false,
                        emptyReport = null, connectorRegistry = registry, onOpenPreview = {},
                        onOpenSkillFile = {})
                }
            }
        }

        compose.onNodeWithTag("conversation-activity-${matchingResult.id}").assertDoesNotExist()
        compose.onNodeWithTag("delivered-file-${deliveredCard.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tool-group-${toolGroup.firstEventId}").performScrollTo().assertIsDisplayed()
    }

    @Test fun restoredToolCardsAndCompletedAssistantFooterAreVisible() {
        val root = chatTestStore.newFilesDirectory()
        val session = "restored-compose-${System.nanoTime()}"
        val timeline = chatTestStore.withLocalRunStore(root) { store ->
            val userId = store.appendConversationMessage(session, "user", "Send a message")
            store.appendReflectionToolEvent(session, userId, "Send Message", "messaging", "tool_result", "success-call")
            store.appendReflectionToolEvent(session, userId, "Send Message", "messaging", "tool_error", "failure-call")
            store.appendConversationMessage(session, "assistant", "The message was handled.", 100L,
                "restored-run", userId, "COMPLETED")
            store.readConversationTimeline(session)
        }
        assertNull(timeline.last { it.kind == "assistant" }.stage)
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            private val states = mutableMapOf<String, Boolean>()
            override fun isConnected(id: String) = states[id] == true
            override fun setConnected(id: String, connected: Boolean) { states[id] = connected }
        }, { true })
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AgentRunScreen(
                        conversationKey = session,
                        events = timeline,
                        isRunning = false,
                        emptyReport = null,
                        connectorRegistry = registry,
                        onOpenPreview = {},
                        onOpenSkillFile = {},
                    )
                }
            }
        }

        val firstToolId = timeline.first { it.kind == "tool" }.id
        compose.onNodeWithText(context.resources.getQuantityString(
            R.plurals.connector_tool_group_completed_failures, 1, 2, 1,
        )).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tool-group-$firstToolId").performClick()
        val displayedToolName = requireNotNull(timeline.first { it.kind == "tool" }.toolDisplayName)
        compose.onNodeWithText(context.getString(R.string.connector_tool_used, displayedToolName))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connector_tool_failed, displayedToolName))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_footer_copy_description))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_footer_regenerate))
            .performScrollTo().assertIsDisplayed()
    }
}
