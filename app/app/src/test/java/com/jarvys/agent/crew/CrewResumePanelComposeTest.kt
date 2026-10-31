package com.jarvys.agent.crew

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrewResumePanelComposeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun viewingHistoryNeverResumesAndUnavailableResumeHasNoButton() {
        val canResume = mutableStateOf(false)
        var calls = 0
        compose.setContent { MaterialTheme { CrewResumePanel(canResume.value, "Check pending command") { calls++ } } }
        compose.onNodeWithTag("crew-resume-note").assertIsDisplayed()
        compose.onNodeWithTag("crew-resume-action").assertDoesNotExist()
        assertEquals(0, calls)
        compose.runOnIdle { canResume.value = true }
        compose.onNodeWithTag("crew-resume-action").assertIsDisplayed()
        assertEquals(0, calls)
        compose.onNodeWithTag("crew-resume-action").performClick()
        assertEquals(1, calls)
    }
}
