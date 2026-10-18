package com.jarvys.agent.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.R
import com.jarvys.agent.crew.CrewMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatComposerWorkspaceControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pauseRemainsEnabledWhenSelectedAssignmentCannotReceiveCorrections() {
        var pauses = 0
        var sends = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hint = context.getString(R.string.workspace_chat_selected_assignment_cannot_correct_hint)
        val pauseLabel = context.getString(R.string.workspace_pause)
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    goal = "",
                    onGoalChange = {},
                    onSend = { sends++ },
                    onStop = { pauses++ },
                    onSelectModel = {},
                    modelLabel = "Model",
                    running = true,
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
                    sendAllowed = false,
                    inputEnabled = false,
                    disabledHint = hint,
                    separateStopAction = true,
                    stopActionEnabled = true,
                    stopActionLabel = pauseLabel,
                    showModelSelector = false,
                    showContextActions = false,
                    showMicrophoneButton = false,
                )
            }
        }

        compose.onNodeWithTag("chat-composer-input").assertIsNotEnabled()
        compose.onNodeWithTag("chat-send-stop-button").assertIsNotEnabled()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsEnabled().performClick()
        compose.onNodeWithTag("workspace-chat-input-hint").assertIsDisplayed()
        compose.onNodeWithText(hint).assertIsDisplayed()
        assertEquals(1, pauses)
        assertEquals(0, sends)
    }

    @Test fun inputEnabledStillGatesTheMainComposersStopButtonAndTextField() {
        var inputEnabled by mutableStateOf(false)
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ChatComposer(
                    goal = "",
                    onGoalChange = {},
                    onSend = {},
                    onStop = { stops++ },
                    onSelectModel = {},
                    modelLabel = "Model",
                    running = true,
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
                    inputEnabled = inputEnabled,
                    showModelSelector = false,
                    showContextActions = false,
                    showMicrophoneButton = false,
                )
            }
        }

        val input = compose.onNodeWithTag("chat-composer-input")
        val sendStop = compose.onNodeWithTag("chat-send-stop-button")
        input.assertIsNotEnabled()
        sendStop.assertIsNotEnabled()
        compose.runOnIdle { inputEnabled = true }
        compose.waitForIdle()
        input.assertIsEnabled()
        sendStop.assertIsEnabled().performClick()
        assertEquals(1, stops)
    }
}
