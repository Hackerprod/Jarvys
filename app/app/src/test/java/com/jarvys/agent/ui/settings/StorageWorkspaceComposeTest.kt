package com.jarvys.agent.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.JarvysSettingsPage
import com.jarvys.agent.JarvysSettingsScreen
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.RetentionSummary
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.ui.JarvysOwnTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings > Storage section: reachable from Settings home and honest about low device storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
class StorageWorkspaceComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun summary(warning: Boolean) = RetentionSummary(
        retained = 4, bounded = 1, expelled = 2, unavailable = 3, retainedBytes = 2048, protectedCount = 5, storageWarning = warning,
    )

    @Test fun lowStorageNoticeAndTheFourStatesAreShown() {
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) { StorageContent(summary(warning = true), failed = false) { "$it B" } } }
        compose.onNodeWithTag("storage-low-warning").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_storage_low_title)).assertIsDisplayed()
        compose.onNodeWithTag("storage-retained").assertIsDisplayed()
        compose.onNodeWithTag("storage-bounded").assertIsDisplayed()
        compose.onNodeWithTag("storage-expelled").assertIsDisplayed()
        compose.onNodeWithTag("storage-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("storage-protected").assertIsDisplayed()
    }

    @Test fun noNoticeWhileThereIsRoom() {
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) { StorageContent(summary(warning = false), failed = false) { "$it B" } } }
        compose.onNodeWithTag("storage-retained").assertIsDisplayed()
        compose.onNodeWithTag("storage-low-warning").assertDoesNotExist()
    }

    @Test fun storageIsReachableFromSettingsHome() {
        val opened = mutableListOf<JarvysSettingsPage>()
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            JarvysSettingsScreen(
                page = JarvysSettingsPage.HOME, themeMode = JarvysThemeMode.LIGHT, showAgentEvents = true, proactiveEnabled = false,
                proactiveStatus = ProactiveStatus(enabled = false), memoryEnabled = false, memoryUsedCharacters = 0,
                languageChoice = AppLanguageChoice.ENGLISH, onLanguageChange = {}, onThemeChange = {}, onShowAgentEventsChange = {},
                onProactiveEnabledChange = {}, onRefreshProactiveStatus = {}, onNavigateRoute = {}, onMcp = {}, onSkills = {},
                onConnectors = {}, onMemory = {}, onAccessibilitySettings = {}, onNavigate = { opened += it },
            )
        } }
        compose.onNodeWithTag("settings-storage-row").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(JarvysSettingsPage.STORAGE), opened)
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi") fun theNoticeIsLocalized() {
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) { StorageContent(summary(warning = true), failed = false) { "$it B" } } }
        compose.onNodeWithText("El almacenamiento del dispositivo está bajo").assertIsDisplayed()
    }
}
