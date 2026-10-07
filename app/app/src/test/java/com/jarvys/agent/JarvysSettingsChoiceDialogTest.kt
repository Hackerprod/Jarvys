package com.jarvys.agent

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
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
class JarvysSettingsChoiceDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsLanguageAndThemeSheetsPersistThroughRealPreferenceRuntimes() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("jarvys_app_language", Context.MODE_PRIVATE).edit().clear().commit()
        AppLanguageRuntime.select(app, AppLanguageChoice.ENGLISH)
        val uiPreferences = JarvysUiPreferences(app).apply { setThemeMode(JarvysThemeMode.DARK) }
        val theme = mutableStateOf(uiPreferences.themeMode())
        val language = mutableStateOf(AppLanguageRuntime.current(app))
        val fontScale = mutableStateOf(1f)

        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(language.value) {
                compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                    setLocale(language.value.localeTag?.let(Locale::forLanguageTag) ?: Locale.getDefault())
                })
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                androidx.compose.material3.MaterialTheme {
                    JarvysSettingsScreen(
                        page = JarvysSettingsPage.HOME,
                        themeMode = theme.value,
                        showAgentEvents = true,
                        proactiveEnabled = false,
                        proactiveStatus = com.jarvys.agent.proactive.ProactiveStatus(enabled = false),
                        agentTimeoutSeconds = 0,
                        memoryEnabled = false,
                        memoryUsedCharacters = 0,
                        languageChoice = language.value,
                        onLanguageChange = { selected ->
                            AppLanguageRuntime.select(app, selected)
                            language.value = AppLanguageRuntime.current(app)
                        },
                        onThemeChange = { selected ->
                            uiPreferences.setThemeMode(selected)
                            theme.value = uiPreferences.themeMode()
                        },
                        onShowAgentEventsChange = {}, onProactiveEnabledChange = {},
                        onRefreshProactiveStatus = {}, onAgentTimeoutChange = {}, onNavigateRoute = {},
                        onMcp = {}, onSkills = {}, onConnectors = {}, onMemory = {},
                        onAccessibilitySettings = {}, onNavigate = {},
                    )
                }
            }
        }

        for (localeChoice in listOf(AppLanguageChoice.ENGLISH, AppLanguageChoice.SPANISH)) {
            for (scale in listOf(1f, 2f)) {
                AppLanguageRuntime.select(app, localeChoice)
                language.value = AppLanguageRuntime.current(app)
                val localized = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                    setLocale(localeChoice.localeTag?.let(Locale::forLanguageTag) ?: Locale.getDefault())
                })
                fontScale.value = scale
                for (mode in listOf(JarvysThemeMode.SYSTEM, JarvysThemeMode.LIGHT, JarvysThemeMode.DARK)) {
                    uiPreferences.setThemeMode(mode)
                    theme.value = uiPreferences.themeMode()
                    compose.waitForIdle()
                    val colorRow = compose.onNodeWithTag("settings-color-mode-row").performScrollTo().assertIsDisplayed()
                    colorRow.assertTextContains(localized.getString(R.string.settings_color_mode))
                    colorRow.performClick()
                    compose.waitForIdle()
                    compose.onNodeWithTag("jarvys-choice-sheet-title")
                        .assertTextContains(localized.getString(R.string.settings_color_mode)).assertIsDisplayed()
                    assertEquals(3, compose.onAllNodesWithTag("jarvys-choice-row").fetchSemanticsNodes().size)
                    val modes = listOf(JarvysThemeMode.SYSTEM, JarvysThemeMode.LIGHT, JarvysThemeMode.DARK)
                    val modeIndex = modes.indexOf(mode)
                    compose.onAllNodesWithTag("jarvys-choice-row").get(modeIndex).assertIsSelected()
                    assertTrue(modes.all { choice -> compose.onAllNodesWithText(localized.getString(when (choice) {
                        JarvysThemeMode.SYSTEM -> R.string.settings_system
                        JarvysThemeMode.LIGHT -> R.string.settings_light
                        JarvysThemeMode.DARK -> R.string.settings_dark
                    })).fetchSemanticsNodes().isNotEmpty() })

                    val next = when (mode) {
                        JarvysThemeMode.SYSTEM -> JarvysThemeMode.LIGHT
                        JarvysThemeMode.LIGHT -> JarvysThemeMode.DARK
                        JarvysThemeMode.DARK -> JarvysThemeMode.SYSTEM
                    }
                    compose.onAllNodesWithTag("jarvys-choice-row").get(modes.indexOf(next)).performClick()
                    compose.waitForIdle()
                    assertEquals(next, uiPreferences.themeMode())
                    assertEquals(next, theme.value)
                    assertTrue(compose.onAllNodesWithTag("jarvys-choice-row").fetchSemanticsNodes().isEmpty())
                }

                compose.onNodeWithTag("settings-language-row").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("jarvys-choice-sheet-title")
                    .assertTextContains(localized.getString(R.string.language_dialog_title)).assertIsDisplayed()
                val languageOptions = listOf(AppLanguageChoice.ENGLISH, AppLanguageChoice.SPANISH,
                    AppLanguageChoice.SYSTEM)
                compose.onAllNodesWithTag("jarvys-choice-row").get(languageOptions.indexOf(localeChoice)).assertIsSelected()
                val nextLanguage = if (localeChoice == AppLanguageChoice.ENGLISH) AppLanguageChoice.SPANISH
                    else AppLanguageChoice.ENGLISH
                compose.onAllNodesWithTag("jarvys-choice-row").get(languageOptions.indexOf(nextLanguage)).performClick()
                compose.waitForIdle()
                assertEquals(nextLanguage, AppLanguageRuntime.current(app))
                assertEquals(nextLanguage, language.value)
                assertTrue(compose.onAllNodesWithTag("jarvys-choice-row").fetchSemanticsNodes().isEmpty())
            }
        }
    }
}
