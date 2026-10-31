package com.jarvys.agent.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.JarvysSettingsPage
import com.jarvys.agent.JarvysSettingsScreen
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.ui.JarvysOwnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings is the only way to Bots, Scheduled tasks and Memory once the drawer lists no conversations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
class SettingsEntriesComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun botsScheduledTasksAndMemoryAreReachableFromSettingsHome() {
        val calls = mutableListOf<String>()
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            Settings(onBots = { calls += "bots" }, onScheduledTasks = { calls += "tasks" }, onMemory = { calls += "memory" })
        } }
        compose.onNodeWithTag("settings-bots-row").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-scheduled-tasks-row").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.memory_title)).performScrollTo().performClick()
        assertEquals(listOf("bots", "tasks", "memory"), calls)
        compose.onNodeWithTag("settings-archived-chats-row").assertDoesNotExist()
    }

    @Test fun entriesAppearOnlyOnSettingsHome() {
        val page = mutableStateOf(JarvysSettingsPage.HOME)
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) { Settings(page = page.value) } }
        compose.onNodeWithTag("settings-bots-row").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { page.value = JarvysSettingsPage.PREFERENCES }
        compose.onNodeWithTag("settings-bots-row").assertDoesNotExist()
        compose.onNodeWithTag("settings-scheduled-tasks-row").assertDoesNotExist()
    }

    @Composable
    private fun Settings(page: JarvysSettingsPage = JarvysSettingsPage.HOME, onBots: () -> Unit = {},
        onScheduledTasks: () -> Unit = {}, onMemory: () -> Unit = {}) {
        JarvysSettingsScreen(
            page = page, themeMode = JarvysThemeMode.LIGHT, showAgentEvents = true, proactiveEnabled = false,
            proactiveStatus = ProactiveStatus(enabled = false),
            memoryEnabled = false, memoryUsedCharacters = 0, languageChoice = AppLanguageChoice.ENGLISH,
            onLanguageChange = {}, onThemeChange = {}, onShowAgentEventsChange = {},
            onProactiveEnabledChange = {}, onRefreshProactiveStatus = {},
            onNavigateRoute = {}, onMcp = {}, onSkills = {}, onConnectors = {}, onMemory = onMemory,
            onAccessibilitySettings = {}, onNavigate = {}, onBots = onBots, onScheduledTasks = onScheduledTasks,
        )
    }
}
