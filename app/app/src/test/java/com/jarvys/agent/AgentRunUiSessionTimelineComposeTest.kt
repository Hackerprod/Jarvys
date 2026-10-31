package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class AgentRunUiSessionTimelineComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun firstChatCompositionReadsItsSessionWhileWorkspaceRemainsVisible() {
        val workspaceSession = "visible-workspace-${System.nanoTime()}"
        val chatSession = "requested-chat-${System.nanoTime()}"
        AgentRunUiState.resetSession(workspaceSession)
        AgentRunUiState.restoreSession(chatSession, listOf(
            AgentRunUiEvent.messageEvent(1L, "user", "Chat session event", 1L),
        ))
        AgentRunUiState.showSession(workspaceSession)
        try {
            val requestedSession = mutableStateOf(workspaceSession)
            compose.setContent {
                val sessionId by requestedSession
                val timelineFlow = remember(sessionId) { AgentRunUiState.timelineEventsForSession(sessionId) }
                val timeline by timelineFlow.collectAsState()
                MaterialTheme {
                    Text(timeline.events.joinToString("|") { it.text }.ifEmpty { "empty" },
                        modifier = Modifier.testTag("chat-session-events"))
                }
            }
            compose.onNodeWithTag("chat-session-events").assertIsDisplayed()
            compose.onNodeWithText("empty").assertIsDisplayed()

            compose.runOnIdle { requestedSession.value = chatSession }

            // The per-session flow starts with the already-published chat snapshot on this
            // first composition, even though the compatibility StateFlow still shows workspace.
            compose.onNodeWithText("Chat session event").assertIsDisplayed()
            compose.onNodeWithTag("chat-session-events").assertIsDisplayed()
            assertEquals(workspaceSession, AgentRunUiState.state.value.sessionId)
        } finally {
            AgentRunUiState.resetSession(chatSession)
            AgentRunUiState.resetSession(workspaceSession)
        }
    }
}
