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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.jarvys.agent.R
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Never enables a known live mode or taps pilot Start: host tests must not invoke JNI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class JarvysMascotUiTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    private fun noNative() {
        compose.onNodeWithTag("jarvys-mascot-native").assertDoesNotExist()
        compose.onNodeWithTag("mascot-pilot-native-0").assertDoesNotExist()
        compose.onNodeWithTag("mascot-pilot-native-1").assertDoesNotExist()
    }

    private fun openMenu() {
        compose.onNodeWithTag("jarvys-account-menu").performClick()
        compose.waitForIdle()
    }

    private fun enableUnknownMode() {
        openMenu()
        compose.onNodeWithTag("jarvys-mascot-toggle").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_warning)).assertExists()
        compose.onNodeWithTag("jarvys-mascot-confirm-enable").performClick()
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

    @Test fun explicitUnknownModeOptInIsNonNativeAndBackgroundRevokesIt() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysMascotAccountMenu(null, "session", true, true) {}
            }
        } }
        enableUnknownMode()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_enable)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_disable)).assertDoesNotExist()
        noNative()
    }

    @Test fun sessionHistoryDrawerAndOffscreenChangesForgetTemporaryConsent() {
        var sessionId by mutableStateOf("one")
        var liveVisible by mutableStateOf(true)
        var surfaceVisible by mutableStateOf(true)
        var onScreen by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            Box(Modifier.offset(y = if (onScreen) 0.dp else 2000.dp)) {
                JarvysMascotAccountMenu(null, sessionId, liveVisible, surfaceVisible) {}
            }
        } }
        enableUnknownMode()
        compose.runOnIdle { sessionId = "two" }
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_enable)).assertExists()
        compose.onNodeWithTag("jarvys-mascot-toggle").performClick()
        compose.onNodeWithTag("jarvys-mascot-confirm-enable").performClick()
        compose.runOnIdle { liveVisible = false }
        compose.runOnIdle { liveVisible = true }
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_enable)).assertExists()
        compose.onNodeWithTag("jarvys-mascot-toggle").performClick()
        compose.onNodeWithTag("jarvys-mascot-confirm-enable").performClick()
        compose.runOnIdle { surfaceVisible = false }
        compose.runOnIdle { surfaceVisible = true }
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_enable)).assertExists()
        compose.onNodeWithTag("jarvys-mascot-toggle").performClick()
        compose.onNodeWithTag("jarvys-mascot-confirm-enable").performClick()
        compose.runOnIdle { onScreen = false }
        compose.waitForIdle()
        compose.runOnIdle { onScreen = true }
        compose.waitForIdle()
        openMenu()
        compose.onNodeWithText(compose.activity.getString(R.string.jarvys_mascot_enable)).assertExists()
        noNative()
    }

    @Test fun historyCannotOptInAndOriginalJarvysVisualTestOpensStatic() {
        compose.setContent { MaterialTheme {
            JarvysMascotAccountMenu(null, "history", false, true) {}
        } }
        openMenu()
        compose.onNodeWithTag("jarvys-mascot-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("jarvys-mascot-visual-test").performClick()
        compose.onNodeWithTag("mascot-pilot-screen").assertExists()
        compose.onNodeWithTag("mascot-pilot-phase")
            .assertTextEquals(compose.activity.getString(R.string.mascot_pilot_phase, "STATIC"))
        compose.onNodeWithTag("mascot-pilot-stop").assertIsNotEnabled()
        noNative()
        compose.onNodeWithTag("mascot-pilot-close").performClick()
        compose.onNodeWithTag("mascot-pilot-screen").assertDoesNotExist()
        noNative()
    }
}
