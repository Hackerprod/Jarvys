package com.jarvys.agent.ui.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ui.motion.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.sin

/** No layout displacement: an original 5-second sine translation is applied to fallback artwork. */
@Composable
internal fun Modifier.jarvysHeaderFloat(active: Boolean): Modifier {
    val allowed = active && !LocalReducedMotion.current
    val amplitude = with(LocalDensity.current) { 3.dp.toPx() }
    val phase = if (allowed) {
        val transition = rememberInfiniteTransition(label = "jarvys-header-float")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart),
            label = "jarvys-header-float-phase",
        )
        value
    } else 0f
    return graphicsLayer { translationY = sin(phase) * amplitude }
}
