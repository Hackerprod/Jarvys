package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.R
import com.jarvys.agent.ui.mascot.JarvysMascotProcessConsent
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.shell.JarvysRouteTopBar
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host checks use the existing static fallback and never opt into native rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspaceCentralTopBarComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    @Before fun clearBefore() { JarvysMascotProcessConsent.revoke() }
    @After fun clearAfter() { JarvysMascotProcessConsent.revoke() }

    @Test fun centralMascotIsCenteredAndPreservesMenuActionsWithoutOptingIntoAnimation() {
        val central = mutableStateOf(true)
        var settings = 0
        var newChat = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                JarvysRouteTopBar(AppRouteMeta("Independent title", true, action = AppRouteAction.CHAT), AgentRunUiSnapshot(),
                    {}, {}, { settings++ }, { newChat++ }, {}, {}, false, false, false, false, {}, {}, {},
                    mainMascotMode = 2, mascotVisibleSessionId = WorkspaceIdentity.CENTRAL_SESSION,
                    mascotLiveConversationVisible = true, centralMascotCentered = central.value)
            }
        } }
        val toolbar = compose.onNodeWithTag("jarvys-central-topbar").fetchSemanticsNode().boundsInRoot
        val mascot = compose.onNodeWithTag("jarvys-account-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(toolbar.center.x, mascot.center.x, 1f)
        compose.onAllNodesWithTag("jarvys-account-menu").assertCountEquals(1)
        compose.onNodeWithTag("jarvys-mascot-native").assertDoesNotExist()
        compose.onNodeWithTag("jarvys-account-menu").performClick()
        compose.onNodeWithTag("jarvys-mascot-toggle").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_settings)).performClick()
        assertEquals(1, settings)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_new_chat)).performClick()
        assertEquals(1, newChat)
        assertFalse(JarvysMascotProcessConsent.session.optedIn)
        compose.runOnIdle { central.value = false }
        compose.onNodeWithTag("jarvys-central-topbar").assertDoesNotExist()
        compose.onNodeWithText("Independent title").assertIsDisplayed()
        compose.onAllNodesWithTag("jarvys-account-menu").assertCountEquals(1)
        compose.onNodeWithTag("jarvys-mascot-native").assertDoesNotExist()
    }
}
