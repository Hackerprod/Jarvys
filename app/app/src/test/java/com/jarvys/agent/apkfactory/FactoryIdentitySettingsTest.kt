package com.jarvys.agent.apkfactory

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.JarvysSettingsPage
import com.jarvys.agent.JarvysSettingsScreen
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.proactive.ProactiveStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactoryIdentitySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showSettings() {
        compose.setContent {
            MaterialTheme {
                JarvysSettingsScreen(
                    page = JarvysSettingsPage.HOME,
                    themeMode = JarvysThemeMode.LIGHT,
                    showAgentEvents = true,
                    proactiveEnabled = false,
                    proactiveStatus = ProactiveStatus(enabled = false),
                    memoryEnabled = false,
                    memoryUsedCharacters = 0,
                    languageChoice = AppLanguageChoice.ENGLISH,
                    onLanguageChange = {}, onThemeChange = {}, onShowAgentEventsChange = {},
                    onProactiveEnabledChange = {}, onRefreshProactiveStatus = {}, onNavigateRoute = {},
                    onMcp = {}, onSkills = {}, onConnectors = {}, onMemory = {},
                    onAccessibilitySettings = {}, onNavigate = {},
                )
            }
        }
    }

    @Test fun settingsManuallyOpensNativeIdentityActivityWithNoSecretExtras() {
        showSettings()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithTag("settings-factory-identities-row").performScrollTo().assertIsDisplayed().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(FactoryIdentityActivity::class.java.name, intent.component?.className)
        assertNull(intent.extras)
        assertNull(intent.data)
    }
    @Test fun settingsOpensOneShotNativeFileSharingRecoveryWithoutFileAuthority() {
        showSettings()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithTag("settings-factory-file-sharing-row").performScrollTo().assertIsDisplayed().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(FactoryFileShareActivity::class.java.name, intent.component?.className)
        assertEquals(setOf("nativeRecoveryToken"), intent.extras!!.keySet())
        assertNull(intent.data); assertNull(intent.clipData); assertEquals(0, intent.flags)
        assertTrue(FactoryFileShareCoordinator.consumeRecoveryToken(intent))
        assertFalse(FactoryFileShareCoordinator.consumeRecoveryToken(intent))
    }

}
