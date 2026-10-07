package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRunRestorationComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoredToolCardsAndCompletedAssistantFooterAreVisible() {
        val root = Files.createTempDirectory("restored-chat-tools").toFile()
        val store = LocalRunStore(root)
        val session = "restored-compose-${System.nanoTime()}"
        val userId = store.appendConversationMessage(session, "user", "Send a Telegram message")
        store.appendReflectionToolEvent(session, userId, "Telegram Send Message", "mcp", "tool_result", "success-call")
        store.appendReflectionToolEvent(session, userId, "Telegram Send Message", "mcp", "tool_error", "failure-call")
        store.appendConversationMessage(session, "assistant", "The message was handled.", 100L,
            "restored-run", userId, "COMPLETED")
        val timeline = store.readConversationTimeline(session)
        assertNull(timeline.last { it.kind == "assistant" }.stage)
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            private val states = mutableMapOf<String, Boolean>()
            override fun isConnected(id: String) = states[id] == true
            override fun setConnected(id: String, connected: Boolean) { states[id] = connected }
        }, { true })
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

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
                        chatWithoutMemory = false,
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.connector_tool_used, "Telegram Send Message"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connector_tool_failed, "Telegram Send Message"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_footer_copy_description))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_footer_regenerate))
            .performScrollTo().assertIsDisplayed()
        root.deleteRecursively()
    }
}
