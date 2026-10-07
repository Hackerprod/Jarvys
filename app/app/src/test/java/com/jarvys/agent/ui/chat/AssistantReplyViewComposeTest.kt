package com.jarvys.agent.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.JarvysPalette
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantReplyViewComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun assistantReplyHasNoVisibleJarvysHeaderAndKeepsAccessibilityAndErrorTone() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val normalText = "A concise assistant response without a repeated brand label."
        val errorText = "The provider could not complete this response."
        val state = mutableStateOf(AgentRunUiEvent.messageEvent(1L, "assistant", normalText, 1L) to false)
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AssistantReplyView(state.value.first, onOpenSkillFile = {}, isError = state.value.second)
                }
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(normalText, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(normalText, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Jarvys", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.chat_assistant_message_accessibility)).assertIsDisplayed()

        state.value = AgentRunUiEvent.messageEvent(2L, "assistant", errorText, 2L).copy(stage = "FAILED") to true
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(errorText, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(errorText, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Jarvys", substring = true).assertCountEquals(0)
        val errorNode = compose.onNodeWithContentDescription(context.getString(R.string.chat_assistant_error_accessibility),
            useUnmergedTree = true)
        errorNode.assertIsDisplayed()
        val textNode = compose.onNodeWithText(errorText, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        textNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertTrue("assistant error markdown keeps the Material error color",
            results.any { it.layoutInput.style.color == JarvysPalette.ClayLight })
    }
}
