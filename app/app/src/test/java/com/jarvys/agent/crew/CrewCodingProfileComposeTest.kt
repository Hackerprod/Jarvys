package com.jarvys.agent.crew

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrewCodingProfileComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directLegacyEntryIsReadOnlyAndClosingNeverSaves() {
        var saves = 0
        var closes = 0
        compose.setContent { MaterialTheme {
            CrewCodingProfileEditor(CrewProfile.codingDefault(), listOf(CrewProfileOption("read", "Read"), CrewProfileOption("write", "Write")),
                emptyList(), onClose = { closes++ }, onSave = { saves++ })
        } }
        compose.onNodeWithTag("crew-profile-read-only").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("crew-profile-save").assertDoesNotExist()
        compose.onNodeWithTag("crew-profile-capability-write").assertDoesNotExist()
        compose.onNodeWithTag("crew-profile-close").performClick()
        assertEquals(1, closes)
        assertEquals(0, saves)
    }

    @Test fun unavailableRuntimeCapabilitiesDoNotTurnLegacyDetailIntoAnEditor() {
        var saves = 0
        compose.setContent { MaterialTheme {
            CrewCodingProfileEditor(CrewProfile.codingDefault(), emptyList(), emptyList(), onClose = {}, onSave = { saves++ })
        } }
        compose.onNodeWithTag("crew-profile-instructions").performScrollTo().assertExists()
        compose.onNodeWithTag("crew-profile-name").assertDoesNotExist()
        compose.onNodeWithTag("crew-profile-prompt").assertDoesNotExist()
        compose.onNodeWithTag("crew-profile-save").assertDoesNotExist()
        assertEquals(0, saves)
    }
}
