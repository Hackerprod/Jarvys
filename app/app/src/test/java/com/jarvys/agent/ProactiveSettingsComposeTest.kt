package com.jarvys.agent

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.jarvys.agent.proactive.ProactiveStatus
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactiveSettingsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun masterSwitchUsesEnglishAndSpanishCopyWithoutTextSwitchOverlapAtFontScaleOneAndTwo() {
        val base = RuntimeEnvironment.getApplication()
        val language = mutableStateOf("en")
        val fontScale = mutableStateOf(1f)
        val status = mutableStateOf(ProactiveStatus(enabled = true, pendingCount = 7))
        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(language.value) {
                base.createConfigurationContext(
                    Configuration(base.resources.configuration).apply { setLocale(Locale(language.value)) },
                )
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                MaterialTheme {
                    JarvysSettingsScreen(
                        page = JarvysSettingsPage.PREFERENCES,
                        themeMode = JarvysThemeMode.SYSTEM,
                        showAgentEvents = false,
                        proactiveEnabled = true,
                        proactiveStatus = status.value,
                        agentTimeoutSeconds = 900,
                        memoryEnabled = false,
                        memoryUsedCharacters = 0,
                        languageChoice = if (language.value == "es") AppLanguageChoice.SPANISH else AppLanguageChoice.ENGLISH,
                        onLanguageChange = {},
                        onThemeChange = {},
                        onShowAgentEventsChange = {},
                        onProactiveEnabledChange = {},
                        onRefreshProactiveStatus = {},
                        onAgentTimeoutChange = {},
                        onNavigateRoute = {},
                        onMcp = {},
                        onSkills = {},
                        onConnectors = {},
                        onMemory = {},
                        onAccessibilitySettings = {},
                        onNavigate = {},
                    )
                }
            }
        }

        for (locale in listOf("en", "es")) {
            language.value = locale
            val configured = base.createConfigurationContext(
                Configuration(base.resources.configuration).apply { setLocale(Locale(locale)) },
            )
            val title = configured.getString(R.string.settings_proactive_enabled)
            val description = configured.getString(R.string.settings_proactive_description)
            for (scale in listOf(1f, 2f)) {
                fontScale.value = scale
                val cases = listOf(0L to true, 1_700_000_000_000L to true, 0L to false)
                for ((lastCheck, permissionGranted) in cases) {
                    status.value = ProactiveStatus(
                        enabled = true,
                        lastCheckMillis = lastCheck,
                        pendingCount = 7,
                        notificationPermissionGranted = permissionGranted,
                    )
                    compose.waitForIdle()
                    compose.onNodeWithText(description).performScrollTo().assertIsDisplayed()
                    val reviewText = if (lastCheck == 0L) configured.getString(R.string.settings_proactive_status_never)
                    else configured.getString(
                        R.string.settings_proactive_status_last,
                        android.text.format.DateFormat.getTimeFormat(configured).format(Date(lastCheck)),
                        7,
                    )
                    val statusText = listOfNotNull(
                        reviewText,
                        if (permissionGranted) null else configured.getString(R.string.settings_proactive_notifications_denied),
                    ).joinToString(" · ")
                    compose.onNodeWithText(statusText).performScrollTo().assertIsDisplayed()
                    val titleNode = compose.onNodeWithText(title).fetchSemanticsNode()
                    val descriptionNode = compose.onNodeWithText(description).fetchSemanticsNode()
                    val statusNode = compose.onNodeWithText(statusText).fetchSemanticsNode()
                    val switchNode = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()
                        .minBy { node -> abs(node.boundsInRoot.center.y - titleNode.boundsInRoot.center.y) }
                    assertTrue("description overlaps master switch at locale=$locale fontScale=$scale",
                        descriptionNode.boundsInRoot.right <= switchNode.boundsInRoot.left)
                    assertTrue("status overlaps master switch at locale=$locale fontScale=$scale",
                        statusNode.boundsInRoot.right <= switchNode.boundsInRoot.left)
                }
            }
        }
    }
}
