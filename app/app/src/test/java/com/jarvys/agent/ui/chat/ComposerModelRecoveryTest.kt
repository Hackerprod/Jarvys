package com.jarvys.agent.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jarvys.agent.R
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.shell.ModelEffortAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerModelRecoveryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelPillShowsNameAndAnnouncesEffortWhileMicrophoneIsUnavailable() {
        var modelClicks = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ChatComposer(
                        goal = "", onGoalChange = {}, onSend = {}, onStop = {},
                        onSelectModel = { modelClicks++ }, modelLabel = "GPT-5.4",
                        running = false, captureContextRequested = false, onCaptureContext = {},
                        onImportSkill = {}, availableSkills = emptyList(), selectedSkillIds = emptySet(),
                        onToggleRunSkill = {}, onManageSkills = {}, skillEnabledCount = 0, skillTotalCount = 0,
                        crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {},
                        effortLabel = "Medium", effortAppearance = ModelEffortAppearance(1, 3),
                    )
                }
            }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithTag("chat-model-label", useUnmergedTree = true).assertTextEquals("GPT-5.4")
        compose.onNodeWithTag("chat-model-effort-background", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_select_model_accessibility, "GPT-5.4 · Medium"))
            .performClick()
        assertEquals(1, modelClicks)
        compose.onNodeWithTag("chat-microphone-button").assertIsNotEnabled().assertHasNoClickAction()
        val plus = compose.onNodeWithTag("chat-plus-button").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithTag("chat-model-button").fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithTag("chat-microphone-button").fetchSemanticsNode().boundsInRoot
        assertTrue(plus.right <= model.left && model.right <= mic.left)
    }

    @Test fun attachmentTrayKeepsPickersSkillsAndCrewReachableWithoutLegacyControls() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ContextTrayContent(2, 3,
                    onCamera = { calls += "camera" }, onPhotos = { calls += "photos" }, onFiles = { calls += "files" },
                    onSkills = { calls += "skills" }, onContextManagement = { calls += "capture" },
                    onOpenCrew = { calls += "crew" }, onDismiss = { calls += "dismiss" })
            }
        }
        val context = RuntimeEnvironment.getApplication()
        for (key in listOf("camera", "photos", "files")) {
            compose.onNodeWithTag("attach-tile-$key").performScrollTo().performClick()
            val icon = compose.onNodeWithTag("attach-icon-$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithTag("attach-label-$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(icon.bottom <= label.top)
        }
        compose.onNodeWithText(context.getString(R.string.chat_tray_skills)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.crew_open_workspace)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.chat_tray_capture_screen)).performScrollTo().performClick()
        assertEquals(listOf("camera", "photos", "files", "skills", "crew", "capture"), calls)
        assertTrue(compose.onAllNodesWithText(context.getString(R.string.chat_tray_instruction_injection)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText(context.getString(R.string.crew_mode_always)).fetchSemanticsNodes().isEmpty())
    }
}
