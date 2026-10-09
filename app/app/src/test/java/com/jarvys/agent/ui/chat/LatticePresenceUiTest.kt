package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native host pixels and real Compose lifecycle/scroll tests, not physical-device validation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h900dp-port-mdpi")
class LatticePresenceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun thinkingPixelsMoveOnlyInsideTheFixedNineDotFootprint() = movingPixels(false)
    @Test fun workingPixelsKeepTheirOwnThemeColorAndFixedFootprint() = movingPixels(true)

    private fun movingPixels(working: Boolean) {
        compose.mainClock.autoAdvance = false
        val snapshot = if (working) working() else AgentRunUiSnapshot(running = true)
        compose.setContent { Scene { AgentPresenceIndicator(snapshot, Modifier.fillMaxWidth()) } }
        settle()
        active(true)
        val a = capture(if (working) "working-phase-a" else "thinking-phase-a")
        val bounds = glyphBounds()
        assertEquals(34f, bounds.width, 0.1f)
        assertEquals(34f, bounds.height, 0.1f)
        compose.mainClock.advanceTimeBy(216)
        val b = capture(if (working) "working-phase-b" else "thinking-phase-b")
        assertEquals(bounds, glyphBounds())
        assertNineFixedDots(a, bounds)
        assertNineFixedDots(b, bounds)
        assertTrue("Wave must visibly advance", differentPixels(a, b, bounds) > 8)
        assertEquals("Animation cannot change label/background pixels", 0, differentPixels(a, b, bounds, outside = true))
        val centerX = bounds.center.x.toInt()
        val centerY = bounds.center.y.toInt()
        assertEquals("Central dot must stay faint and fixed", a.getPixel(centerX, centerY), b.getPixel(centerX, centerY))
        a.recycle(); b.recycle()
    }

    @Test fun reducedMotionToggledMidCycleRemovesTheClockAndKeepsStaticPixels() {
        compose.mainClock.autoAdvance = false
        val reduced = mutableStateOf(false)
        compose.setContent { Scene(reduced = reduced.value) {
            AgentPresenceIndicator(AgentRunUiSnapshot(running = true), Modifier.fillMaxWidth())
        } }
        settle(); active(true)
        compose.runOnIdle { reduced.value = true }
        settle(); active(false)
        val a = capture("reduced-motion-static")
        compose.mainClock.advanceTimeBy(3000)
        val b = capture()
        assertEquals(0, differentPixels(a, b))
        assertNineFixedDots(a, glyphBounds())
        a.recycle(); b.recycle()
        compose.runOnIdle { reduced.value = false }
        settle(); active(true)
    }

    @Test fun stoppedLifecycleRemovesAllMotionAndStartRestoresIt() {
        compose.mainClock.autoAdvance = false
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val snapshot = mutableStateOf(AgentRunUiSnapshot(running = true))
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            Scene { AgentPresenceIndicator(snapshot.value, Modifier.fillMaxWidth()) }
        } }
        settle(); active(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        settle(); active(false)
        compose.runOnIdle { snapshot.value = working() }
        settle()
        val a = capture()
        compose.mainClock.advanceTimeBy(3000)
        val b = capture()
        assertEquals(0, differentPixels(a, b)); a.recycle(); b.recycle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        settle(); active(true)
    }

    @Test fun realClippedScrollStopsTheGlyphEvenWhenTheCallerLeavesVisibleTrue() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Scene {
            Column(Modifier.height(60.dp).verticalScroll(rememberScrollState()).testTag("viewport")) {
                AgentPresenceIndicator(AgentRunUiSnapshot(running = true), Modifier.fillMaxWidth())
                Spacer(Modifier.height(500.dp))
                Box(Modifier.size(40.dp).testTag("bottom"))
            }
        } }
        settle(); active(true)
        compose.onNodeWithTag("bottom").performScrollTo()
        settle(); active(false)
        compose.onNodeWithTag("agent-presence-thinking").performScrollTo()
        settle(); active(true)
    }

    @Test fun explicitHiddenFlagRemovesMotionWithoutLosingTheAccessibleLabel() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Scene {
            AgentPresenceIndicator(AgentRunUiSnapshot(running = true), Modifier.fillMaxWidth(), visible = false,
                showLabel = false)
        } }
        settle(); active(false)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.agent_presence_thinking)).assertExists()
        compose.onAllNodesWithText(compose.activity.getString(R.string.agent_presence_thinking), useUnmergedTree = true)
            .assertCountEquals(0)
        val a = capture(); compose.mainClock.advanceTimeBy(2000); val b = capture()
        assertEquals(0, differentPixels(a, b)); a.recycle(); b.recycle()
    }

    @Test fun rapidRunAndTerminalChangesKeepTheCorrectTextAndStopTheLoop() {
        compose.mainClock.autoAdvance = false
        val snapshot = mutableStateOf(AgentRunUiSnapshot(running = true))
        compose.setContent { Scene { AgentPresenceIndicator(snapshot.value, Modifier.fillMaxWidth()) } }
        settle(); active(true)
        for (next in listOf(working(), AgentRunUiSnapshot(running = true), waiting(),
            AgentRunUiSnapshot(outcome = "FAILED"), AgentRunUiSnapshot(outcome = "COMPLETED"))) {
            compose.runOnIdle { snapshot.value = next }
            settle()
        }
        compose.mainClock.advanceTimeBy(300)
        settle(); active(false)
        compose.onNodeWithText(compose.activity.getString(R.string.agent_presence_done), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("agent-presence-done").assertContentDescriptionEquals(
            compose.activity.getString(R.string.agent_presence_done))
        val a = capture("done-static"); compose.mainClock.advanceTimeBy(2000); val b = capture()
        assertEquals(0, differentPixels(a, b)); a.recycle(); b.recycle()
    }

    @Test fun englishLightNormalTextKeepsStateCopyAndLayout() = stateGallery(false, 1f, "en-light")
    @Test fun englishDarkDoubleTextKeepsStateCopyAndLayout() = stateGallery(true, 2f, "en-dark-font200")
    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun spanishLightDoubleTextKeepsStateCopyAndLayout() = stateGallery(false, 2f, "es-light-font200")
    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun spanishDarkNormalTextKeepsStateCopyAndLayout() = stateGallery(true, 1f, "es-dark")

    private fun stateGallery(dark: Boolean, scale: Float, name: String) {
        val states = listOf(AgentRunUiSnapshot(), AgentRunUiSnapshot(running = true), working(), waiting(),
            AgentRunUiSnapshot(outcome = "FAILED"), AgentRunUiSnapshot(outcome = "COMPLETED"))
        var thinkingInk = 0
        var workingInk = 0
        compose.setContent { Scene(dark, reduced = true, scale = scale) {
            thinkingInk = MaterialTheme.colorScheme.primary.copy(alpha = LatticePresence.StaticOpacity)
                .compositeOver(MaterialTheme.colorScheme.background).toArgb()
            workingInk = MaterialTheme.colorScheme.tertiary.copy(alpha = LatticePresence.StaticOpacity)
                .compositeOver(MaterialTheme.colorScheme.background).toArgb()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                states.forEach { AgentPresenceIndicator(it, Modifier.fillMaxWidth()) }
                StreamingTextIndicator()
            }
        } }
        settle()
        val capture = capture(name)
        for (state in states) {
            val presence = AgentPresence.from(state)
            val node = compose.onNodeWithTag("agent-presence-${presence.state.name.lowercase()}")
            node.assertIsDisplayed()
            val semantics = node.fetchSemanticsNode()
            val label = semantics.config[SemanticsProperties.ContentDescription].single()
            assertEquals(label, semantics.config[SemanticsProperties.StateDescription])
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Existing single-line label contract is preserved", 1, layouts.single().layoutInput.maxLines)
            val textBounds = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(textBounds.right <= semantics.boundsInRoot.right + 1f)
            if (presence.state == AgentPresence.State.THINKING) {
                assertFalse("Thinking copy must fit at 320dp/$scale", layouts.single().hasVisualOverflow)
            }
            // Other long labels may keep their pre-existing ellipsis; full text stays in semantics.
        }
        compose.onAllNodesWithTag("agent-presence-motion-active", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("agent-presence-motion-static", useUnmergedTree = true).assertCountEquals(6)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chat_streaming_status)).assertIsDisplayed()
        val glyphs = compose.onAllNodesWithTag("agent-presence-glyph", useUnmergedTree = true).fetchSemanticsNodes()
        for ((index, expected) in listOf(1 to thinkingInk, 2 to workingInk)) {
            val b = glyphs[index].layoutInfo.coordinates.boundsInWindow()
            val actual = capture.getPixel((b.center.x - 8).toInt(), (b.center.y - 8).toInt())
            for (shift in listOf(0, 8, 16)) {
                assertTrue("State retains its existing theme ink", kotlin.math.abs(((actual shr shift) and 255) -
                    ((expected shr shift) and 255)) <= 2)
            }
        }
        capture.recycle()
    }

    @Composable private fun Scene(dark: Boolean = false, reduced: Boolean = false, scale: Float = 1f,
        content: @Composable () -> Unit) {
        JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalReducedMotion provides reduced,
                LocalDensity provides Density(density.density, scale)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) { content() }
            }
        }
    }

    private fun working() = AgentRunUiSnapshot(running = true, events = listOf(
        AgentRunUiEvent.toolEvent(1, "tool_call", "Search", null, "tool-1", null, 1)))
    private fun waiting() = AgentRunUiSnapshot(events = listOf(
        AgentRunUiEvent(1, "approval", "PENDING", "Confirm", approvalStatus = "PENDING")))
    private fun active(expected: Boolean) {
        compose.onAllNodesWithTag("agent-presence-motion-active", useUnmergedTree = true)
            .assertCountEquals(if (expected) 1 else 0)
        compose.onAllNodesWithTag("agent-presence-motion-static", useUnmergedTree = true)
            .assertCountEquals(if (expected) 0 else 1)
    }
    private fun settle() { repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
    private fun glyphBounds(): Rect = compose.onNodeWithTag("agent-presence-glyph", useUnmergedTree = true)
        .fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()

    private fun capture(name: String? = null): Bitmap {
        compose.waitForIdle()
        var result: Bitmap? = null
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val width = (compose.activity.resources.configuration.screenWidthDp *
                compose.activity.resources.displayMetrics.density).toInt()
            val height = (compose.activity.resources.configuration.screenHeightDp *
                compose.activity.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        compose.waitForIdle()
        return requireNotNull(result).also { bitmap ->
            if (name != null) {
                val directory = TestCaptureDirectories.named("ux29-lattice-${BuildConfig.FLAVOR}")
                val file = File(directory, "$name.png")
                TestCaptureDirectories.assertOwned(directory, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("UX29_CAPTURE=${file.absolutePath}")
            }
        }
    }

    private fun assertNineFixedDots(bitmap: Bitmap, bounds: Rect) {
        val background = bitmap.getPixel(bounds.left.toInt(), bounds.top.toInt())
        for (row in 0..2) for (column in 0..2) {
            val x = (bounds.center.x + (column - 1) * 8f).toInt()
            val y = (bounds.center.y + (row - 1) * 8f).toInt()
            assertNotEquals("Dot $row,$column is drawn at its fixed center", background, bitmap.getPixel(x, y))
            assertEquals("Dots retain the 2dp clear horizontal gap", background, bitmap.getPixel(x + 4, y))
            assertEquals("Dots retain the 2dp clear vertical gap", background, bitmap.getPixel(x, y + 4))
        }
    }

    private fun differentPixels(a: Bitmap, b: Bitmap, bounds: Rect? = null, outside: Boolean = false): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val inside = bounds == null || (x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom)
            if ((if (outside) !inside else inside) && a.getPixel(x, y) != b.getPixel(x, y)) count++
        }
        return count
    }
}
