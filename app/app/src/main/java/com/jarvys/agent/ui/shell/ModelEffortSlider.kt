package com.jarvys.agent.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.ModelVariant
import com.jarvys.agent.R
import com.jarvys.agent.ui.motion.rememberMotionEnabled
import com.jarvys.agent.ui.motion.rememberMotionViewport
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelEffortSlider(
    variants: List<ModelVariant>,
    selectedVariantId: String,
    onVariantSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (variants.isEmpty()) return
    val appearance = modelEffortAppearance(variants, selectedVariantId) ?: return
    val selectedIndex = appearance.selectedIndex
    val lastIndex = appearance.lastIndex
    val labels = variants.map { effortDisplayLabel(it.id) }
    val title = stringResource(R.string.quick_model_effort)
    val viewport = rememberMotionViewport()
    val motionEnabled = rememberMotionEnabled(true, viewport.visible)
    val sparklePhase = rememberEffortSparklePhase(motionEnabled && selectedIndex > 0)
    val haptics = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var lastDispatchedIndex by remember(variants) { mutableIntStateOf(selectedIndex) }
    SideEffect { lastDispatchedIndex = selectedIndex }
    val selectIndex by rememberUpdatedState<(Int) -> Boolean> { proposed ->
        val next = proposed.coerceIn(0, lastIndex)
        if (next == lastDispatchedIndex) false else {
            lastDispatchedIndex = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onVariantSelected(variants[next].id)
            true
        }
    }
    val animatedIndex by animateFloatAsState(
        selectedIndex.toFloat(),
        if (motionEnabled) JarvysMotion.feedback() else snap(),
        label = "quick-model-effort-position",
    )
    val colors = MaterialTheme.colorScheme
    val trackColor = remember(colors.surfaceVariant) { effortTrackColor(colors.surfaceVariant) }
    val gradientColors = remember(colors.primary, selectedIndex, lastIndex) {
        effortGradientColors(colors.primary, selectedIndex, lastIndex)
    }
    Column(modifier.fillMaxWidth().testTag("quick-model-effort"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium)
            Text(labels[selectedIndex], Modifier.testTag("quick-model-effort-value"),
                color = colors.primary, style = MaterialTheme.typography.titleMedium)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp).then(viewport.modifier)
            .testTag("quick-model-effort-slider")
            .semantics {
                contentDescription = title
                stateDescription = labels[selectedIndex]
                progressBarRangeInfo = ProgressBarRangeInfo(selectedIndex.toFloat(), 0f..lastIndex.toFloat(), (variants.size - 2).coerceAtLeast(0))
                setProgress { value -> value.isFinite() && selectIndex(value.roundToInt()) }
                customActions = buildList {
                    if (selectedIndex > 0) add(CustomAccessibilityAction(labels[selectedIndex - 1]) { selectIndex(selectedIndex - 1) })
                    if (selectedIndex < lastIndex) add(CustomAccessibilityAction(labels[selectedIndex + 1]) { selectIndex(selectedIndex + 1) })
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> selectIndex(selectedIndex + 1)
                    Key.DirectionDown -> selectIndex(selectedIndex - 1)
                    Key.DirectionRight -> selectIndex(selectedIndex + if (isRtl) -1 else 1)
                    Key.DirectionLeft -> selectIndex(selectedIndex + if (isRtl) 1 else -1)
                    Key.MoveHome -> selectIndex(0)
                    Key.MoveEnd -> selectIndex(lastIndex)
                    else -> false
                }
            }
            .focusable()
            .pointerInput(lastIndex, isRtl) {
                detectTapGestures { position ->
                    selectIndex(effortIndexAt(position.x, size.width.toFloat(), 18.dp.toPx(), lastIndex, isRtl))
                }
            }
            .pointerInput(lastIndex, isRtl) {
                detectHorizontalDragGestures(
                    onDragStart = { position -> selectIndex(effortIndexAt(position.x, size.width.toFloat(), 18.dp.toPx(), lastIndex, isRtl)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        selectIndex(effortIndexAt(change.position.x, size.width.toFloat(), 18.dp.toPx(), lastIndex, isRtl))
                    },
                )
            },
        ) {
            val inset = 18.dp.toPx()
            val width = (size.width - inset * 2f).coerceAtLeast(0f)
            val trackHeight = 32.dp.toPx()
            val y = size.height / 2f
            fun x(index: Float): Float {
                val fraction = if (lastIndex == 0) 0f else index / lastIndex
                return inset + (if (isRtl) 1f - fraction else fraction) * width
            }
            drawRoundRect(trackColor, Offset(0f, y - trackHeight / 2f), Size(size.width, trackHeight), CornerRadius(trackHeight / 2f))
            val startX = if (isRtl) size.width - trackHeight / 2f else trackHeight / 2f
            val thumbX = x(animatedIndex)
            val activeBrush = Brush.linearGradient(gradientColors, Offset(startX, y), Offset(thumbX, y))
            drawLine(activeBrush, Offset(startX, y), Offset(thumbX, y), trackHeight, StrokeCap.Round)
            sparklePhase?.value?.let { phase ->
                val clip = Path().apply { addRoundRect(RoundRect(0f, y - trackHeight / 2f, size.width, y + trackHeight / 2f, CornerRadius(trackHeight / 2f))) }
                clipPath(clip) {
                    repeat(18) { particle ->
                        val fraction = (particle + 0.5f) / 18f
                        val position = inset + width * fraction
                        val px = if (isRtl) size.width - position else position
                        val py = y + (if (particle % 2 == 0) -1 else 1) * (8 + particle % 3).dp.toPx()
                        val active = if (isRtl) px > thumbX else px < thumbX
                        if (active && abs(px - thumbX) > 20.dp.toPx()) {
                            val alpha = effortSparkleAlpha(phase, particle)
                            if (alpha > 0f) drawEffortLightRay(
                                Offset(px + sin((phase + fraction) * 2f * PI.toFloat()) * 1.dp.toPx(), py), alpha, particle,
                            )
                        }
                    }
                }
            }
            variants.indices.forEach { drawCircle(Color.White, 3.dp.toPx(), Offset(x(it.toFloat()), y)) }
            drawCircle(Color.White, 18.dp.toPx(), Offset(thumbX, y))
            drawCircle(gradientColors.last(), 18.dp.toPx(), Offset(thumbX, y), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
internal fun effortDisplayLabel(id: String): String {
    val resource = when (id) {
        "none" -> R.string.quick_model_effort_none
        "minimal" -> R.string.quick_model_effort_minimal
        "low" -> R.string.quick_model_effort_low
        "medium" -> R.string.quick_model_effort_medium
        "high" -> R.string.quick_model_effort_high
        "xhigh" -> R.string.quick_model_effort_xhigh
        "max" -> R.string.quick_model_effort_max
        "ultra" -> R.string.quick_model_effort_ultra
        else -> null
    }
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
    return resource?.let { stringResource(it) } ?: id.replaceFirstChar { it.titlecase(locale) }
}

internal fun effortGradientColors(primary: Color, selectedIndex: Int, lastIndex: Int): List<Color> {
    val fraction = if (lastIndex > 0) (selectedIndex.toFloat() / lastIndex).coerceIn(0f, 1f) else 0f
    val blue = effortTrackColor(primary)
    val indigo = Color(0xFF505AC5)
    val violet = Color(0xFF7954B6)
    val end = if (fraction <= 0.5f) lerp(blue, indigo, 2f * fraction) else lerp(indigo, violet, (fraction - 0.5f) * 2f)
    return listOf(blue, effortTrackColor(end))
}

internal fun effortSparkleAlpha(phase: Float, particle: Int): Float {
    val cycle = (particle * 0.381966f + phase) % 1f
    if (cycle >= 0.62f) return 0f
    val wave = sin(cycle / 0.62f * PI.toFloat())
    return 0.76f * wave * wave
}

internal fun effortIndexAt(x: Float, width: Float, inset: Float, lastIndex: Int, isRtl: Boolean): Int {
    if (lastIndex <= 0 || width <= inset * 2f) return 0
    val fraction = ((x - inset) / (width - 2f * inset)).coerceIn(0f, 1f)
    return ((if (isRtl) 1f - fraction else fraction) * lastIndex).roundToInt().coerceIn(0, lastIndex)
}

internal fun effortTrackColor(color: Color): Color {
    if (effortContrastRatio(Color.White, color) >= 4.5f) return color
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (effortContrastRatio(Color.White, lerp(color, Color.Black, middle)) >= 4.5f) high = middle else low = middle
    }
    return lerp(color, Color.Black, high)
}

internal fun effortContrastRatio(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (max(firstLuminance, secondLuminance) + 0.05f) / (min(firstLuminance, secondLuminance) + 0.05f)
}
