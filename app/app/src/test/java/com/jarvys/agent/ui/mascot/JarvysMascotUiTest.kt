package com.jarvys.agent.ui.mascot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.jarvys.agent.R
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The current identity stays static even with legacy consent and known live modes; no JNI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class JarvysMascotUiTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Before fun resetProcessConsent() { JarvysMascotProcessConsent.revoke() }
    @After fun clearProcessConsent() { JarvysMascotProcessConsent.revoke() }

    private fun noNative() {
        compose.onNodeWithTag("jarvys-mascot-native").assertDoesNotExist()
        compose.onNodeWithTag("mascot-pilot-native-0").assertDoesNotExist()
        compose.onNodeWithTag("mascot-pilot-native-1").assertDoesNotExist()
    }

    private fun openMenu() {
        // Complete Android traversal after a route key replaces the button before injecting touch.
        awaitReactionDrawIdle(compose)
        compose.onNodeWithTag("jarvys-account-menu").performClick()
        compose.waitForIdle()
    }

    private fun seedLegacyConsent() {
        compose.runOnIdle { JarvysMascotProcessConsent.enable(true, true) }
        compose.waitForIdle()
        noNative()
    }

    @Test fun knownActivityDefaultsToOriginalStaticArtworkAndKeepsMenuTarget() {
        var existingActionCalls = 0
        compose.setContent { MaterialTheme {
            JarvysMascotAccountMenu(2, "session", true, true) { close ->
                DropdownMenuItem(text = { Text("Existing action") }, onClick = { close(); existingActionCalls++ })
            }
        } }
        compose.onNodeWithTag("jarvys-account-menu").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.jarvys_account_menu)).assertHasClickAction()
        compose.onNodeWithTag("jarvys-topbar-mascot", useUnmergedTree = true)
            .assertWidthIsEqualTo(30.dp).assertHeightIsEqualTo(30.dp)
            .assert(SemanticsMatcher.expectValue(JarvysMascotSourceKey, "principal:jarvys-mascot"))
            .assert(SemanticsMatcher.expectValue(JarvysMascotPresentationKey, "static"))
        noNative()
        openMenu()
        compose.onNodeWithText("Existing action").performClick()
        assertEquals(1, existingActionCalls)
        noNative()
    }

    @Test fun defaultStaticSharedRendererNeverTurnsOtherModesIntoIdle() {
        var mode by mutableStateOf<Int?>(null)
        compose.setContent { MaterialTheme {
            JarvysMascotIcon(Modifier.size(72.dp).testTag("shared-identity"), mode = mode)
        } }
        for (candidate in listOf(null, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)) {
            compose.runOnIdle { mode = candidate }
            compose.onNodeWithTag("shared-identity")
                .assert(SemanticsMatcher.expectValue(JarvysMascotPresentationKey, "static"))
            noNative()
        }
    }

    @Test fun legacyKnownModeConsentIsNonNativeAndBackgroundRetainsData() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysMascotAccountMenu(2, "session", true, true) {}
            }
        } }
        seedLegacyConsent()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        noNative()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        openMenu()
        assertEquals(true, JarvysMascotProcessConsent.session.optedIn)
        compose.onNodeWithTag("jarvys-mascot-toggle").assertDoesNotExist()
        compose.onNodeWithTag("jarvys-mascot-visual-test").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_enable)).assertDoesNotExist()
        noNative()
    }

    @Test fun chatDrawerOffscreenAndRouteRecreationStayStaticWithLegacyConsent() {
        var sessionId by mutableStateOf("one")
        var liveVisible by mutableStateOf(true)
        var surfaceVisible by mutableStateOf(true)
        var onScreen by mutableStateOf(true)
        var showAccount by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (showAccount) Box(Modifier.offset {
                IntOffset(0, (if (onScreen) 0.dp else 2000.dp).roundToPx())
            }) {
                JarvysMascotAccountMenu(7, sessionId, liveVisible, surfaceVisible) { close ->
                    DropdownMenuItem(text = { Text("Close test menu") }, onClick = close)
                }
            }
        } }
        fun assertRetained() {
            openMenu()
            assertEquals(true, JarvysMascotProcessConsent.session.optedIn)
            compose.onNodeWithTag("jarvys-mascot-toggle").assertDoesNotExist()
            compose.onNodeWithTag("jarvys-mascot-visual-test").assertDoesNotExist()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("Close test menu").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("Close test menu").performClick()
            noNative()
        }
        seedLegacyConsent()
        compose.runOnIdle { sessionId = "two" }
        assertRetained()
        compose.runOnIdle { liveVisible = false }
        noNative()
        compose.runOnIdle { liveVisible = true }
        assertRetained()
        compose.runOnIdle { surfaceVisible = false }
        noNative()
        compose.runOnIdle { surfaceVisible = true }
        assertRetained()
        compose.runOnIdle { onScreen = false }
        compose.waitForIdle()
        noNative()
        compose.runOnIdle { onScreen = true }
        compose.waitForIdle()
        assertRetained()
        compose.runOnIdle { showAccount = false }
        noNative()
        compose.runOnIdle { showAccount = true }
        assertRetained()
        compose.runOnIdle { JarvysMascotProcessConsent.revoke() }
        openMenu()
        compose.onNodeWithTag("jarvys-mascot-toggle").assertDoesNotExist()
        compose.onNodeWithTag("jarvys-mascot-visual-test").assertDoesNotExist()
        noNative()
    }

    @Test fun historyDoesNotExposeLegacyToggleOrVisualTest() {
        compose.setContent { MaterialTheme {
            JarvysMascotAccountMenu(null, "history", false, true) {
                DropdownMenuItem(text = { Text("Existing history action") }, onClick = {})
            }
        } }
        openMenu()
        compose.onNodeWithText("Existing history action").assertExists()
        compose.onNodeWithTag("jarvys-mascot-toggle").assertDoesNotExist()
        compose.onNodeWithTag("jarvys-mascot-visual-test").assertDoesNotExist()
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        noNative()
    }

    @Test fun directSharedRendererIgnoresLegacyOptInAcrossAllModes() {
        var mode by mutableStateOf<Int?>(null)
        compose.setContent { MaterialTheme {
            JarvysMascotIcon(Modifier.size(72.dp).testTag("shared-identity"),
                mode = mode, animationOptedIn = true, isAnimationCurrent = { true })
        } }
        for (candidate in listOf(null, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)) {
            compose.runOnIdle { mode = candidate }
            compose.onNodeWithTag("shared-identity")
                .assert(SemanticsMatcher.expectValue(JarvysMascotPresentationKey, "static"))
            noNative()
        }
    }
}
