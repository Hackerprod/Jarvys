package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.mutableStateOf
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.ui.JarvysOwnTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineMessageBlocksComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun assistantMarkdownRendersRealSvgAndChartWithAccessibleSummariesAndCopyFallback() {
        val svg = """```svg
            |<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><title>Circle diagram</title><circle cx="25" cy="25" r="15" fill="#1769aa"/></svg>
            |```""".trimMargin()
        val chart = """```chart
            |{"type":"bar","title":"Monthly numbers","labels":["Jan","Feb"],"series":[{"name":"Total","values":[3,5]}]}
            |```""".trimMargin()
        val invalid = """```chart
            |{"type":"line","title":"Broken series","labels":["Jan","Feb"],"series":[{"values":[3]}]}
            |```""".trimMargin()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                AssistantMarkdown("$svg\n\n$chart\n\n$invalid", onOpenSkillFile = {})
            }
        }
        compose.onNodeWithContentDescription("Circle diagram").assertIsDisplayed()
        compose.onNodeWithContentDescription("Monthly numbers. Total: Jan 3.0, Feb 5.0").assertIsDisplayed()
        compose.onAllNodesWithText("Copy").assertCountEquals(3)
        compose.onAllNodesWithText("\"type\":\"line\"", substring = true).assertCountEquals(1)
    }

    @Test fun unfinishedSvgReservesPlaceholderDuringStreamingThenFallsBackToCopyableCode() {
        val streamActive = mutableStateOf(true)
        val markdown = """```svg
            |<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><title>Waiting diagram</title>
            |<circle cx="25" cy="25" r="15"/>
            |""".trimMargin()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                AssistantMarkdown(markdown, onOpenSkillFile = {}, streaming = streamActive.value)
            }
        }
        compose.onNodeWithText("Rendering svg...").assertIsDisplayed()
        compose.onAllNodesWithText("Copy").assertCountEquals(0)

        streamActive.value = false
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("<svg", substring = true).assertIsDisplayed()
    }
}
