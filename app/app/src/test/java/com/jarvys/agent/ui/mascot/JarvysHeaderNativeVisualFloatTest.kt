package com.jarvys.agent.ui.mascot

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Wrapper timing with a real AndroidView child; native Rive decoding is a separate device check. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class JarvysHeaderNativeVisualFloatTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun nativeFirstFrameReplacementKeepsFloatAndNeverMovesTheHitTarget() {
        val nativeRendered = mutableStateOf(false)
        val allowed = mutableStateOf(true)
        val reduced = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                Box(Modifier.size(96.dp).testTag("fixed-hit-target")) {
                    Box(Modifier.size(84.dp).testTag("floating-visual").jarvysHeaderFloat(allowed.value)) {
                        if (nativeRendered.value) AndroidView(
                            modifier = Modifier.fillMaxSize().testTag("native-idle-view"),
                            factory = { context -> View(context).apply { setBackgroundColor(android.graphics.Color.BLUE) } },
                        ) else Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        awaitReactionDrawIdle(compose)
        fun visualTop() = compose.onNodeWithTag("floating-visual").fetchSemanticsNode().boundsInRoot.top
        fun hitTop() = compose.onNodeWithTag("fixed-hit-target").fetchSemanticsNode().boundsInRoot.top
        // The fixed parent gives the exact untransformed origin even after draw-sync advances frames.
        val origin = hitTop()
        val hitOrigin = hitTop()
        compose.mainClock.advanceTimeBy(1250)
        awaitReactionDrawIdle(compose)
        assertTrue("fallback floats +3dp", visualTop() - origin in 2.5f..3.5f)
        compose.runOnUiThread { nativeRendered.value = true }
        awaitReactionDrawIdle(compose)
        compose.onNodeWithTag("native-idle-view").assertExists()
        assertTrue("native first frame must not cancel levitation", visualTop() - origin in 2.5f..3.5f)
        compose.mainClock.advanceTimeBy(2500)
        awaitReactionDrawIdle(compose)
        assertTrue("native visual floats -3dp", visualTop() - origin in -3.5f..-2.5f)
        assertEquals("touch target remains stationary", hitOrigin, hitTop(), 0.01f)
        compose.runOnUiThread { reduced.value = true }
        awaitReactionDrawIdle(compose)
        assertEquals("reduced motion centers the native visual", origin, visualTop(), 0.01f)
        compose.runOnUiThread { reduced.value = false; allowed.value = false }
        awaitReactionDrawIdle(compose)
        assertEquals("hidden or paused visual stays centered", origin, visualTop(), 0.01f)
    }
}
