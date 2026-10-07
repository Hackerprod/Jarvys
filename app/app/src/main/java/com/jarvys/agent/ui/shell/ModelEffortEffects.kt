package com.jarvys.agent.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ModelVariant
import com.jarvys.agent.ui.motion.rememberMotionEnabled
import com.jarvys.agent.ui.motion.rememberMotionViewport
import kotlin.math.PI
import kotlin.math.sin

data class ModelEffortAppearance(val selectedIndex: Int, val lastIndex: Int)

internal fun modelEffortAppearance(variants: List<ModelVariant>, selectedId: String): ModelEffortAppearance? {
    if (variants.isEmpty()) return null
    val selected = variants.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
        ?: variants.indexOfFirst { it.id == "medium" }.takeIf { it >= 0 }
        ?: 0
    return ModelEffortAppearance(selected, variants.lastIndex)
}

@Composable
internal fun rememberEffortSparklePhase(enabled: Boolean): State<Float>? = if (enabled) {
    rememberInfiniteTransition(label = "effort-sparkles").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "effort-sparkle-phase",
    )
} else null

internal fun DrawScope.drawEffortLightRay(center: Offset, alpha: Float, variation: Int) {
    if (alpha <= 0f) return
    val radius = (3.6f + (variation % 3) * 0.4f).dp.toPx()
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = alpha * 0.34f), Color.Transparent), center, radius),
        radius, center,
    )
    val longRay = (2.6f + (variation % 3) * 0.35f).dp.toPx()
    val shortRay = longRay * 0.56f
    val core = 0.45.dp.toPx()
    drawPath(Path().apply {
        moveTo(center.x, center.y - longRay)
        lineTo(center.x + core, center.y - core)
        lineTo(center.x + shortRay, center.y)
        lineTo(center.x + core, center.y + core)
        lineTo(center.x, center.y + longRay)
        lineTo(center.x - core, center.y + core)
        lineTo(center.x - shortRay, center.y)
        lineTo(center.x - core, center.y - core)
        close()
    }, Color.White.copy(alpha = alpha))
    drawCircle(Color.White.copy(alpha = alpha), 0.55.dp.toPx(), center)
}

@Composable
internal fun ModelEffortBackground(
    appearance: ModelEffortAppearance,
    active: Boolean,
    modifier: Modifier = Modifier,
    verticalTextPadding: Dp = 10.dp,
) {
    val viewport = rememberMotionViewport()
    val phase = rememberEffortSparklePhase(rememberMotionEnabled(active, viewport.visible) && appearance.selectedIndex > 0)
    val colors = effortGradientColors(MaterialTheme.colorScheme.primary, appearance.selectedIndex, appearance.lastIndex)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.then(viewport.modifier)) {
        drawRect(Brush.horizontalGradient(if (rtl) colors.reversed() else colors))
        phase?.value?.let { value ->
            val count = (size.width / 24.dp.toPx()).toInt().coerceAtLeast(1)
            val edge = verticalTextPadding.toPx() / 2f
            val sparkleScale = ((edge - 0.5.dp.toPx()) / 4.4.dp.toPx()).coerceIn(0f, 1f)
            repeat(count) { particle ->
                val fraction = (particle + 0.5f) / count
                val center = Offset(
                    size.width * (if (rtl) 1f - fraction else fraction) +
                        sin((value + fraction) * 2f * PI.toFloat()) * 0.8.dp.toPx(),
                    if (particle % 2 == 0) edge else size.height - edge,
                )
                scale(sparkleScale, sparkleScale, center) { drawEffortLightRay(center, effortSparkleAlpha(value, particle), particle) }
            }
        }
    }
}
