package com.jarvys.agent.workspaces

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ChatMessageList
import com.jarvys.agent.LocalChatHeaderInset
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.R
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.ui.mascot.JarvysMascotProcessConsent
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.shell.JarvysShellFrame
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual shell/header-inset propagation and hit targets, including compact landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class CentralMascotScaleComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    @Before fun resetBefore() { JarvysMascotProcessConsent.revoke() }
    @After fun resetAfter() { JarvysMascotProcessConsent.revoke() }

    @Test fun portrait() = assertCentralLayout()
    @Test @Config(qualifiers = "en-rUS-w320dp-h480dp-port-mdpi")
    fun compactPortrait() = assertCentralLayout()
    @Test @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun compactLandscape() = assertCentralLayout()

    private fun assertCentralLayout() {
        var drawer = 0
        var newChat = 0
        var settings = 0
        var headerInset = 0.dp
        var insetTarget: View? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                insetTarget = LocalView.current
                JarvysShellFrame(
                    drawerState = rememberDrawerState(DrawerValue.Closed), drawerContent = {},
                    route = AppRouteMeta("Central", true, action = AppRouteAction.CHAT),
                    agentSnapshot = AgentRunUiSnapshot(),
                    onBack = {}, onOpenDrawer = { drawer++ }, onOpenSettings = { settings++ },
                    onNewChat = { newChat++ }, onAddServer = {}, onImportSkill = {},
                    chatWithoutMemory = false, canCompact = false, compacting = false, canReflect = false,
                    onToggleMemory = {}, onCompact = {}, onReflect = {}, bottomBar = {},
                    mascotVisibleSessionId = WorkspaceIdentity.CENTRAL_SESSION,
                    mascotLiveConversationVisible = true, centralMascotCentered = true,
                ) {
                    headerInset = LocalChatHeaderInset.current
                    ChatMessageList(conversationKey = "central", messages = listOf("Hello"),
                        isRunning = false, messageKey = { it }, isUserMessage = { false },
                        itemContent = { Text(it, Modifier.testTag("central-chat-message")) })
                }
            }
        } }
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(insetTarget), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true).build())
        }
        awaitReactionDrawIdle(compose)
        val avatar = compose.onNodeWithTag("jarvys-topbar-mascot", useUnmergedTree = true)
            .assertIsDisplayed().assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)
            .fetchSemanticsNode().boundsInRoot
        val topbar = compose.onNodeWithTag("jarvys-central-topbar").fetchSemanticsNode().boundsInRoot
        val message = compose.onNodeWithTag("central-chat-message").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithTag("jarvys-account-menu")
            .assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)
        val nav = compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_open))
        val create = compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_new_chat))
        val navBounds = nav.fetchSemanticsNode().boundsInRoot
        val createBounds = create.fetchSemanticsNode().boundsInRoot
        assertEquals(topbar.center.x, avatar.center.x, 1f)
        assertTrue(avatar.top >= topbar.top && avatar.bottom <= topbar.bottom)
        assertTrue(navBounds.right <= avatar.left && avatar.right <= createBounds.left)
        assertTrue(message.top >= topbar.bottom)
        with(compose.density) { assertEquals(topbar.height, headerInset.toPx(), 1f) }
        assertEquals(120f, topbar.height, 1f)
        assertTrue(avatar.top >= 24f)
        compose.onNodeWithTag("jarvys-mascot-native").assertDoesNotExist()
        nav.performTouchInput { click() }
        create.performTouchInput { click() }
        menu.performTouchInput { longClick() }
        awaitReactionDrawIdle(compose)
        compose.onNodeWithText(compose.activity.getString(R.string.drawer_settings)).performTouchInput { click() }
        assertEquals(1, drawer)
        assertEquals(1, newChat)
        assertEquals(1, settings)
    }
}
