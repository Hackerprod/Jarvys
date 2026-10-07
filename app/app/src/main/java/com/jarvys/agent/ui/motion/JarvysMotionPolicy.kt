package com.jarvys.agent.ui.motion

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle

/** Single source for system motion preference and lifecycle-aware animation eligibility. */
object JarvysMotionPolicy {
    fun reduced(context: Context): Boolean = reducedByScales(
        animatorDuration = readScale(context) { Settings.Global.ANIMATOR_DURATION_SCALE },
        transition = readScale(context) { Settings.Global.TRANSITION_ANIMATION_SCALE },
        window = readScale(context) { Settings.Global.WINDOW_ANIMATION_SCALE },
    )

    /** Android exposes the reduced-motion choice through its system animation scales. */
    fun reducedByScales(animatorDuration: Float, transition: Float, window: Float): Boolean =
        animatorDuration <= 0f || transition <= 0f || window <= 0f

    fun shouldAnimate(active: Boolean, reduced: Boolean): Boolean = active && !reduced

    private inline fun readScale(context: Context, setting: () -> String): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, setting(), 1f)
    }.getOrDefault(1f)
}

val LocalReducedMotion = staticCompositionLocalOf { false }

data class MotionViewport(val visible: Boolean, val modifier: Modifier)

/** A low-frequency visibility signal; animation values never participate in this state. */
@Composable
fun rememberMotionViewport(): MotionViewport {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val width = with(density) { configuration.screenWidthDp.dp.toPx() }
    val height = with(density) { configuration.screenHeightDp.dp.toPx() }
    val visibility = remember { mutableStateOf(false) }
    val visibilityModifier = remember(width, height, visibility) {
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            val inViewport = bounds.width > 0f && bounds.height > 0f
                && bounds.right > 0f && bounds.bottom > 0f
                && bounds.left < width && bounds.top < height
            if (visibility.value != inViewport) visibility.value = inViewport
        }
    }
    return MotionViewport(visibility.value, visibilityModifier)
}

@Composable
fun rememberLifecycleVisible(visible: Boolean): Boolean = visible && rememberLifecycleStarted()

/** Read system settings once per change, then share the result through the Compose tree. */
@Composable
fun JarvysMotionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    var reduced by remember(context) { mutableStateOf(JarvysMotionPolicy.reduced(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                reduced = JarvysMotionPolicy.reduced(context)
            }
        }
        listOf(
            Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.WINDOW_ANIMATION_SCALE,
        ).forEach { key -> resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer) }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
}

/** Active only while its owner is started and the visual element is in its visible state. */
@Composable
fun rememberMotionEnabled(active: Boolean, visible: Boolean = true): Boolean {
    return visible && !LocalReducedMotion.current && JarvysMotionPolicy.shouldAnimate(active, reduced = false)
        && rememberLifecycleStarted()
}

@Composable
private fun rememberLifecycleStarted(): Boolean {
    val owner = LocalLifecycleOwner.current
    var started by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { lifecycleOwner, _ ->
            started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return started
}
