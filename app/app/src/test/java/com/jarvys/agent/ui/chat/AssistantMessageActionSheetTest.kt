package com.jarvys.agent.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.R
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.JarvysThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantMessageActionSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actionSheetSelectionStillOpensTheExistingTextSelectionSurface() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = "Assistant response available for selection"
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                ConversationTimeline(
                    conversationKey = "ux2-action-sheet",
                    events = listOf(AgentRunUiEvent.messageEvent(1L, "assistant", text, 1L)),
                    isRunning = false, connectorRegistry = connectors,
                    onOpenPreview = {}, onOpenSkillFile = {}, chatWithoutMemory = false,
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_footer_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("jarvys-choice-sheet-title").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_footer_select_copy)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.chat_footer_select_copy)).assertIsDisplayed()
        compose.onAllNodesWithText(text).assertCountEquals(2)
    }
}
