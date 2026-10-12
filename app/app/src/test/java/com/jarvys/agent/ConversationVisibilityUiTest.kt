package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
}
