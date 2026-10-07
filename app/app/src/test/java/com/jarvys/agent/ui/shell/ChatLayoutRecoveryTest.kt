package com.jarvys.agent.ui.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.ChatMessageList
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ChatComposer
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recovered v28 geometry and native pixels, plus physical pointer events through the shell. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class ChatLayoutRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.named("recovered-chat-layout-${BuildConfig.FLAVOR}")

    @Test fun lightHeaderFadePixelsAndNoBottomFade() = checkFadePixels(false)
    @Test fun darkHeaderFadePixelsAndNoBottomFade() = checkFadePixels(true)
    @Test fun lightControlsHistoryAndFocus() = checkRealComposer(false, 1f)
    @Test fun darkControlsHistoryAndFocus() = checkRealComposer(true, 1f)
    @Test fun lightLargeTextControlsHistoryAndFocus() = checkRealComposer(false, 1.5f)
    @Test fun darkLargeTextControlsHistoryAndFocus() = checkRealComposer(true, 1.5f)

    private fun checkFadePixels(dark: Boolean) {
        val historyColor = Color(0xFF2587D9)
        var background = 0
        var density = 1f
        compose.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                background = MaterialTheme.colorScheme.background.toArgb()
                density = LocalDensity.current.density
                Shell(bottomBar = {
                    Box(Modifier.fillMaxWidth().height(110.dp).background(Color.Red).testTag("pixel-composer"))
                }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).background(historyColor).testTag("pixel-history"))
                }
            }
        }
        compose.waitForIdle()
        val header = compose.onNodeWithTag(CHAT_HEADER_FADE_TAG).fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("pixel-history").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("pixel-composer").fetchSemanticsNode().boundsInRoot
        assertEquals("history starts behind the top bar", header.top, body.top, 1f)
        val bitmap = capture("${if (dark) "dark" else "light"}-fade-probe")
        try {
            val x = (2 * density).toInt()
            fun pixel(y: Float) = bitmap.getPixel(x, y.toInt())
            val first = pixel(header.bottom + 2 * density)
            val middle = pixel(header.bottom + 14 * density)
            val after = pixel(header.bottom + 29 * density)
            fun distance(pixel: Int, expected: Int): Int = listOf(16, 8, 0).sumOf {
                abs(((pixel shr it) and 255) - ((expected shr it) and 255))
            }
            assertTrue("fade continues below header and then exposes the history", distance(first, background) < distance(middle, background))
            assertTrue("recovered 28dp extension ends at transparent", distance(after, historyColor.toArgb()) < 8)
            assertTrue("no bottom fade is painted above composer", distance(pixel(composer.top - 12 * density), historyColor.toArgb()) < 8)
        } finally { bitmap.recycle() }
    }

    private fun checkRealComposer(dark: Boolean, fontScale: Float) {
        var sends = 0
        var stops = 0
        var models = 0
        var behindTaps = 0
        var running by mutableStateOf(false)
        var goal by mutableStateOf("Recovered draft")
        val state = LazyListState()
        var insetTarget: View? = null
        compose.setContent {
            insetTarget = LocalView.current
            val original = LocalDensity.current
            CompositionLocalProvider(LocalReducedMotion provides true,
                LocalDensity provides Density(original.density, fontScale)) {
                JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    Shell(bottomBar = {
                        ChatComposer(goal = goal, onGoalChange = { goal = it }, onSend = { sends++ },
                            onStop = { stops++ }, onSelectModel = { models++ }, modelLabel = "GPT-6 Luna",
                            running = running, captureContextRequested = false, onCaptureContext = {},
                            onImportSkill = {}, availableSkills = emptyList(), selectedSkillIds = emptySet(),
                            onToggleRunSkill = {}, onManageSkills = {}, skillEnabledCount = 0,
                            skillTotalCount = 0, crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {})
                    }) { padding ->
                        ChatMessageList("recovered", (0 until 45).toList(), false, { it }, { false },
                            modifier = Modifier.fillMaxSize().padding(padding), listState = state,
                            itemContent = { index ->
                                Text("Message $index: recovered conversation history stays readable while scrolling behind the floating panels.",
                                    modifier = Modifier.fillMaxWidth().clickable { behindTaps++ }.testTag("history-$index"),
                                    color = MaterialTheme.colorScheme.onBackground)
                            })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTouchInput {
            down(center); moveBy(Offset(1f, 0f)); up()
        }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        repeat(3) { driftTap("chat-send-stop-button") }
        assertEquals("physical send taps with slight movement are not cancelled", 3, sends)
        driftTap("chat-model-button")
        assertEquals(1, models)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        driftTap("chat-plus-button")
        compose.onNodeWithTag("chat-context-tray").assertIsDisplayed()
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.chat_close_tray)) and
            hasAnyAncestor(hasTestTag("chat-context-tray"))).performTouchInput { click() }
        compose.onNodeWithTag("chat-context-tray").assertDoesNotExist()
        compose.runOnIdle { running = true }
        driftTap("chat-send-stop-button")
        assertEquals(1, stops)
        compose.runOnIdle { running = false }
        assertEquals("overlay controls never activate history behind them", 0, behindTaps)
        compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.65f) }
        compose.waitForIdle()
        val button = compose.onNodeWithTag("chat-jump-to-end").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag(CHAT_COMPOSER_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("floating button is centered", panel.center.x, button.center.x, 1f)
        assertTrue("floating button stays above composer", button.bottom < panel.top)
        assertEquals("recovered floating target is circular", button.width, button.height, 1f)
        capture("${if (dark) "dark" else "light"}-font$fontScale-scrolled").recycle()
        driftTap("chat-jump-to-end")
        compose.waitForIdle()
        assertTrue("jump returns to tail", !state.canScrollBackward)
        compose.runOnIdle { goal = "Expanded draft\nSecond line\nThird line\nFourth line" }
        compose.waitForIdle()
        val enlargedPanel = compose.onNodeWithTag(CHAT_COMPOSER_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot
        val tail = compose.onNodeWithTag("history-44").fetchSemanticsNode().boundsInRoot
        assertTrue("tail remains above growing input", tail.bottom <= enlargedPanel.top)
        capture("${if (dark) "dark" else "light"}-font$fontScale-tail").recycle()
        // Inject a real WindowInsetsCompat IME change into the Compose host. This checks layout,
        // without claiming a host-side test launched a physical keyboard or OEM IME animation.
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(insetTarget), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 180))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.waitForIdle()
        val imePanel = compose.onNodeWithTag(CHAT_COMPOSER_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("composer lifts by the actual IME inset", enlargedPanel.bottom - 180f, imePanel.bottom, 1f)
        assertTrue("expanded draft remains below header with keyboard", imePanel.top > 64f)
        driftTap("chat-send-stop-button")
        assertEquals("send is reachable above IME", 4, sends)
        capture("${if (dark) "dark" else "light"}-font$fontScale-ime").recycle()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(insetTarget), WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false).build())
        }
        compose.waitForIdle()
        assertEquals("composer returns after keyboard dismissal", enlargedPanel.bottom,
            compose.onNodeWithTag(CHAT_COMPOSER_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot.bottom, 1f)
    }

    private fun driftTap(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { down(center); moveBy(Offset(1f, 0f)); up() }
        compose.waitForIdle()
    }

    @Composable private fun Shell(bottomBar: @Composable () -> Unit,
                                  content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
        JarvysShellFrame(drawerState = rememberDrawerState(DrawerValue.Closed), drawerContent = {},
            route = AppRouteMeta("Recovered conversation", true, action = AppRouteAction.CHAT),
            agentSnapshot = AgentRunUiSnapshot(), onBack = {}, onOpenDrawer = {}, onOpenSettings = {},
            onNewChat = {}, onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
            canCompact = false, compacting = false, canReflect = false, onToggleMemory = {},
            onCompact = {}, onReflect = {}, bottomBar = bottomBar, content = content)
    }

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        return compose.runOnIdle {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File(output, "$name.png")
            TestCaptureDirectories.assertOwned(output, file)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("RECOVERED_LAYOUT_CAPTURE=${file.absolutePath}")
            bitmap
        }
    }
}
