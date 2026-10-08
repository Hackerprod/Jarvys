package com.jarvys.agent.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.JarvysSettingsPage
import com.jarvys.agent.JarvysSettingsScreen
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.RunHistoryItem
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h800dp-port-mdpi")
class ArchivedChatsSettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun resetScale() { RuntimeEnvironment.setFontScale(1f) }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val saved = RunHistoryItem("record", "goal", "CHAT", 0, 1, 1.0, "session", "Saved chat")

    @Test fun settingsArchiveEntryReactsToArchiveRestoreAndLastDeletion() {
        val history = mutableStateListOf(saved)
        var opens = 0
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            Settings(history, onArchivedChats = { opens++ })
        } }
        compose.onNodeWithTag("settings-archived-chats-row").assertDoesNotExist()
        compose.runOnIdle { history[0] = history[0].copy(archived = true) }
        compose.onNodeWithTag("settings-archived-chats-row").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, opens)
        compose.runOnIdle { history[0] = history[0].copy(archived = false) }
        compose.onNodeWithTag("settings-archived-chats-row").assertDoesNotExist()
        compose.runOnIdle { history[0] = history[0].copy(archived = true) }
        compose.onNodeWithTag("settings-archived-chats-row").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { history.clear() }
        compose.onNodeWithTag("settings-archived-chats-row").assertDoesNotExist()
        for (title in listOf(R.string.memory_title, R.string.settings_color_mode, R.string.language_title,
            R.string.settings_preferences, R.string.settings_providers, R.string.settings_mcp,
            R.string.settings_skills, R.string.settings_connectors, R.string.settings_accessibility)) {
            compose.onNodeWithText(context.getString(title)).performScrollTo().assertIsDisplayed()
        }
        assertEquals("History changes never navigate on their own", 1, opens)
    }

    @Test fun archiveEntryAppearsOnlyOnSettingsHome() {
        val page = mutableStateOf(JarvysSettingsPage.HOME)
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) {
            Settings(listOf(saved.copy(archived = true)), page = page.value)
        } }
        compose.onNodeWithTag("settings-archived-chats-row").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { page.value = JarvysSettingsPage.PREFERENCES }
        compose.onNodeWithTag("settings-archived-chats-row").assertDoesNotExist()
        compose.runOnIdle { page.value = JarvysSettingsPage.HOME }
        compose.onNodeWithTag("settings-archived-chats-row").performScrollTo().assertIsDisplayed()
    }

    @Test fun lightArchiveEntryWrapsAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.LIGHT)
    @Test fun darkArchiveEntryWrapsAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.DARK)

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun lightSpanishArchiveEntryWrapsAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.LIGHT)
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun darkSpanishArchiveEntryWrapsAtTwoHundredPercentFont() = checkLargeText(JarvysThemeMode.DARK)

    private fun checkLargeText(theme: JarvysThemeMode) {
        RuntimeEnvironment.setFontScale(2f)
        listOf(RuntimeEnvironment.getApplication().resources, compose.activity.resources).distinct().forEach { resources ->
            val config = android.content.res.Configuration(resources.configuration).apply { fontScale = 2f }
            @Suppress("DEPRECATION") resources.updateConfiguration(config, resources.displayMetrics)
        }
        var opens = 0
        var density = 1f
        var fontScale = 1f
        compose.setContent {
            val originalDensity = LocalDensity.current
            density = originalDensity.density
            fontScale = originalDensity.fontScale
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysOwnTheme(theme) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Settings(listOf(saved.copy(archived = true)), theme = theme, onArchivedChats = { opens++ })
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("The rendered Android content uses 200% font scaling", 2f, fontScale, 0.001f)
        val row = compose.onNodeWithTag("settings-archived-chats-row").performScrollTo().assertIsDisplayed()
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue("Settings archive row is at least 48dp", rowBounds.height >= 48f * density)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(context.getString(R.string.drawer_archived_chats), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        saveCapture(theme)
        val layout = layouts.single()
        val metrics = "size=${layout.size}, lines=${layout.lineCount}, widthOverflow=${layout.didOverflowWidth}, " +
            "heightOverflow=${layout.didOverflowHeight}, paragraphWidth=${layout.multiParagraph.width}, " +
            "row=$rowBounds, constraints=${layout.layoutInput.constraints}"
        println("ARCHIVED_SETTINGS_TEXT_METRICS=$metrics")
        assertFalse("Archive entry grows instead of clipping large text: $metrics", layout.hasVisualOverflow)
        row.performClick()
        assertEquals(1, opens)
    }

    private fun saveCapture(theme: JarvysThemeMode) {
        awaitReactionDrawIdle(compose)
        compose.runOnIdle {
            val output = TestCaptureDirectories.named("ux18-archive-settings-${BuildConfig.FLAVOR}")
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val language = compose.activity.resources.configuration.locales[0].language
                val file = File(output, "${theme.name.lowercase()}_${language}_320dp_font2_settings.png")
                TestCaptureDirectories.assertOwned(output, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("UX18_SETTINGS_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }

    @Composable
    private fun Settings(history: List<RunHistoryItem>, page: JarvysSettingsPage = JarvysSettingsPage.HOME,
        theme: JarvysThemeMode = JarvysThemeMode.LIGHT, onArchivedChats: () -> Unit = {}) {
        JarvysSettingsScreen(
            page = page, themeMode = theme, showAgentEvents = true, proactiveEnabled = false,
            proactiveStatus = ProactiveStatus(enabled = false), agentTimeoutSeconds = 0,
            memoryEnabled = false, memoryUsedCharacters = 0, languageChoice = if (context.resources.configuration.locales[0].language == "es")
                AppLanguageChoice.SPANISH else AppLanguageChoice.ENGLISH,
            onLanguageChange = {}, onThemeChange = {}, onShowAgentEventsChange = {},
            onProactiveEnabledChange = {}, onRefreshProactiveStatus = {}, onAgentTimeoutChange = {},
            onNavigateRoute = {}, onMcp = {}, onSkills = {}, onConnectors = {}, onMemory = {},
            onAccessibilitySettings = {}, onNavigate = {}, archivedChatsAvailable = history.any { it.archived },
            onArchivedChats = onArchivedChats,
        )
    }
}
