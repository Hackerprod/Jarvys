package com.jarvys.agent.crew

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.R
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
    private fun profile() = CrewProfile("coding", 1, "Coding", "Text editing", "Inspect before editing.",
        emptyList(), listOf("read"), CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)

    @Test fun openingEditorAndClosingDoesNotSaveOrGrantCapabilities() {
        var saves = 0
        var closes = 0
        compose.setContent { MaterialTheme {
            CrewCodingProfileEditor(profile(), listOf(CrewProfileOption("read", "Read"), CrewProfileOption("write", "Write")),
                emptyList(), onClose = { closes++ }, onSave = { saves++ })
        } }
        assertEquals(0, saves)
        compose.onNodeWithTag("crew-profile-capability-write").performScrollTo().assertIsDisplayed()
        assertEquals(0, saves)
        compose.onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.crew_profile_close)).performScrollTo().performClick()
        assertEquals(1, closes)
        assertEquals(0, saves)
    }

    @Test fun unavailableSelectedCapabilityMustBeRemovedBeforeSaving() {
        var saved: CrewProfile? = null
        compose.setContent { MaterialTheme {
            CrewCodingProfileEditor(profile(), emptyList(), emptyList(), onClose = {}, onSave = { saved = it })
        } }
        compose.onNodeWithTag("crew-profile-save").performClick()
        assertNull(saved)
        compose.onNodeWithTag("crew-profile-capability-read").performScrollTo().performClick()
        compose.onNodeWithTag("crew-profile-save").performClick()
        assertNotNull(saved)
        assertTrue(saved!!.capabilities.isEmpty())
        assertEquals(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT, saved!!.workspaceMode)
    }
}
