package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jarvys.agent.ui.chat.ScheduledTaskArtifactCard
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationVisibilityUiTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun renderedChatHidesInternalRowsButShowsFinalAndActionableError() {
        val events = mutableStateOf(listOf(
            AgentRunUiEvent(1L, "progress", "model_wait", "Internal wait sentinel"),
            AgentRunUiEvent(2L, "assistant", "PROGRESS", "Internal coordination sentinel"),
            AgentRunUiEvent(3L, "assistant", null, "Useful final answer"),
        ))
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
            MaterialTheme {
                AgentRunScreen(conversationKey = "quiet-ui", events = events.value, isRunning = false,
                    emptyReport = null, connectorRegistry = registry, onOpenPreview = {},
                    onOpenSkillFile = {}, chatWithoutMemory = false)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Useful final answer").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("Useful final answer").assertIsDisplayed()
        compose.onNodeWithText("model_wait").assertDoesNotExist()
        compose.onNodeWithText("Internal wait sentinel").assertDoesNotExist()
        compose.onNodeWithText("Internal coordination sentinel").assertDoesNotExist()
        compose.runOnIdle {
            events.value = listOf(AgentRunUiEvent(4L, "progress", "compaction_error", "Actionable error sentinel"))
        }
        compose.onNodeWithText("Actionable error sentinel").assertIsDisplayed()
        compose.onNodeWithText("compaction_error").assertDoesNotExist()
    }

    @Test fun toolAuditDetailErrorAndPreviewAreHiddenUntilExpanded() {
        val event = AgentRunUiEvent.toolEvent(4L, "tool_error", "Read", "Tool error details",
            "audit-preview-call", "preview-id", 4L).copy(toolAuditDetail = "Audit evidence sentinel")
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
            MaterialTheme {
                AgentRunScreen(conversationKey = "tool-disclosure", events = listOf(event), isRunning = false,
                    emptyReport = null, connectorRegistry = registry, onOpenPreview = {},
                    onOpenSkillFile = {}, chatWithoutMemory = false)
            }
        }
        compose.onNodeWithText("Audit evidence sentinel").assertDoesNotExist()
        compose.onNodeWithText("Tool error details").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_open_preview)).assertDoesNotExist()
        compose.onNodeWithTag("tool-output-toggle-audit-preview-call").performClick()
        compose.onNodeWithTag("tool-audit-4").assertIsDisplayed()
        compose.onNodeWithText("Tool error details").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_open_preview)).assertIsDisplayed()
    }

    @Test fun successfulToolRowWithDeliveredCardProjectsAsOneTimelineRepresentation() {
        val artifactId = "11111111-2222-3333-4444-555555555555"
        val artifact = ChatAttachment(artifactId, "index.html", "text/html", 42L,
            ChatAttachment.Kind.FILE, "$artifactId-index.html")
        val result = AgentRunUiEvent.toolEvent(1L, "tool_result", "Build output",
            "{\"artifact_id\":\"delivered:$artifactId\"}", "call-1",
            HtmlPreviewDescriptor.TOKEN_PREFIX + artifactId, 1L)
        val card = AgentRunUiEvent.deliveredFileEvent(2L, artifact, 2L)
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
            MaterialTheme {
                AgentRunScreen(conversationKey = "artifact-card-ui", events = listOf(result, card), isRunning = false,
                    emptyReport = null, connectorRegistry = registry, onOpenPreview = {},
                    onOpenSkillFile = {}, chatWithoutMemory = false)
            }
        }

        compose.onNodeWithTag("conversation-activity-${result.id}").assertDoesNotExist()
        compose.onNodeWithTag("delivered-file-${card.id}").assertIsDisplayed()
    }

    @Test fun scheduledTaskArtifactCardShowsScheduleAndOpensTask() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                ScheduledTaskArtifactCard(
                    ScheduledTaskArtifact("task-1", "Morning brief", "Every morning", "8:00 AM", "scheduled"),
                    onOpen = { opened = true },
                )
            }
        }

        compose.onNodeWithTag("scheduled-task-card-task-1").assertIsDisplayed()
        compose.onNodeWithText("Morning brief").assertIsDisplayed()
        compose.onNodeWithText("Schedule: Every morning").assertIsDisplayed()
        compose.onNodeWithText("Next run: 8:00 AM").assertIsDisplayed()
        compose.onNodeWithTag("scheduled-task-open-task-1").performClick()
        compose.runOnIdle { assert(opened) }
    }
}
