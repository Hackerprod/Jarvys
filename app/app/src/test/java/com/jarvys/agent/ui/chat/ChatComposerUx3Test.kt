package com.jarvys.agent.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.jarvys.agent.R
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.ui.motion.LocalReducedMotion
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatComposerUx3Test {
    @get:Rule val compose = createComposeRule()

    @Test fun runningComposerUsesSolidRoundedStopSquareAndStopsExactlyOnceAtReducedMotion() {
        var stopCalls = 0
        var running by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme {
                    ChatComposer(
                        goal = "",
                        onGoalChange = {},
                        onSend = {},
                        onStop = { stopCalls++ },
                        onSelectModel = {},
                        modelLabel = "Model",
                        running = running,
                        captureContextRequested = false,
                        onCaptureContext = {},
                        onImportSkill = {},
                        availableSkills = emptyList(),
                        selectedSkillIds = emptySet(),
                        onToggleRunSkill = {},
                        onManageSkills = {},
                        skillEnabledCount = 0,
                        skillTotalCount = 0,
                        crewMode = CrewMode.OFF,
                        onCrewModeChange = {},
                        onOpenCrew = {},
                    )
                }
            }
        }
        running = true
        compose.waitForIdle()
        compose.onNodeWithTag("chat-stop-run-glyph", useUnmergedTree = true).assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_stop_run), useUnmergedTree = true).assertIsDisplayed()
        val square = compose.onNodeWithTag("chat-stop-run-glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("chat-send-stop-button").fetchSemanticsNode().boundsInRoot
        assertEquals(square.width, square.height, 0.5f)
        assertTrue("stop glyph fits inside the 48dp target", square.width < button.width)
        compose.onNodeWithTag("chat-send-stop-button").performClick()
        assertEquals(1, stopCalls)
    }
}
