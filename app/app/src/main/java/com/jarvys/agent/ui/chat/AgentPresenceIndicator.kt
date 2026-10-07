package com.jarvys.agent.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.R
import com.jarvys.agent.ui.motion.rememberMotionEnabled
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.motion.rememberMotionViewport
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AgentPresenceIndicator(
    snapshot: com.jarvys.agent.AgentRunUiSnapshot,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    showLabel: Boolean = true,
) {
    val presence = remember(snapshot) { AgentPresence.from(snapshot) }
    val reducedMotion = LocalReducedMotion.current
    val animate = rememberMotionEnabled(active = presence.active, visible = visible)
    val label = presenceLabel(presence)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = label
        }.testTag("agent-presence-${presence.state.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(34.dp).testTag("agent-presence-glyph"), contentAlignment = Alignment.Center) {
            AnimatedContent(targetState = presence.state, transitionSpec = {
                if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(JarvysMotion.presence()) togetherWith fadeOut(JarvysMotion.presence())
            }, label = "agent-presence-state") { state ->
                if (animate && (state == AgentPresence.State.THINKING || state == AgentPresence.State.WORKING)) {
                    OrbitalPresenceGlyph(state)
                } else StillPresenceGlyph(state)
            }
        }
        if (showLabel) Text(label, Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = presenceColor(presence.state), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StreamingTextIndicator(modifier: Modifier = Modifier) {
    val viewport = rememberMotionViewport()
    val animate = rememberMotionEnabled(active = true, visible = viewport.visible)
    val description = stringResource(R.string.chat_streaming_status)
    val semanticsModifier = viewport.modifier.semantics(mergeDescendants = true) {
        contentDescription = description
        stateDescription = description
    }
    val caretColor = MaterialTheme.colorScheme.primary
    if (animate) AnimatedStreamingCaret(modifier.then(semanticsModifier))
    else Canvas(modifier.then(semanticsModifier).size(width = 7.dp, height = 18.dp)) {
        drawLine(caretColor, Offset(size.width / 2f, 1.dp.toPx()),
            Offset(size.width / 2f, size.height - 1.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun AnimatedStreamingCaret(modifier: Modifier) {
    val phase = rememberInfiniteTransition(label = "streaming-caret-cycle").animateFloat(
        0f, 1f, infiniteRepeatable(tween(JarvysMotion.StreamCursorCycleMillis, easing = LinearEasing), RepeatMode.Reverse),
        label = "streaming-caret-phase")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(width = 7.dp, height = 18.dp)) {
        val alpha = 0.48f + 0.52f * (0.5f + 0.5f * sin(phase.value * 2f * PI.toFloat()))
        drawLine(color.copy(alpha = alpha), Offset(size.width / 2f, 1.dp.toPx()),
            Offset(size.width / 2f, size.height - 1.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun presenceLabel(presence: AgentPresence): String = when (presence.state) {
    AgentPresence.State.IDLE -> stringResource(R.string.agent_presence_idle)
    AgentPresence.State.THINKING -> stringResource(R.string.agent_presence_thinking)
    AgentPresence.State.WORKING -> presence.toolName?.let { stringResource(R.string.agent_presence_using_tool, it) }
        ?: stringResource(R.string.chat_agent_working)
    AgentPresence.State.WAITING_USER -> stringResource(R.string.agent_presence_waiting_user)
    AgentPresence.State.ERROR -> stringResource(R.string.agent_presence_error)
    AgentPresence.State.DONE -> stringResource(R.string.agent_presence_done)
}

@Composable
private fun presenceColor(state: AgentPresence.State) = when (state) {
    AgentPresence.State.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    AgentPresence.State.THINKING, AgentPresence.State.DONE -> MaterialTheme.colorScheme.primary
    AgentPresence.State.WORKING, AgentPresence.State.WAITING_USER -> MaterialTheme.colorScheme.tertiary
    AgentPresence.State.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun OrbitalPresenceGlyph(state: AgentPresence.State) {
    val cycle = if (state == AgentPresence.State.THINKING) JarvysMotion.BotPulseCycleMillis else JarvysMotion.AntennaCycleMillis
    val rotation = rememberInfiniteTransition(label = "agent-presence-cycle").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(cycle, easing = LinearEasing), RepeatMode.Restart),
        label = "agent-presence-phase",
    )
    val color = presenceColor(state)
    val surface = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(34.dp).testTag("agent-presence-motion-active")) {
        drawOrbit(state, color, surface, rotation.value)
    }
}

@Composable
private fun StillPresenceGlyph(state: AgentPresence.State) {
    val color = presenceColor(state)
    val surface = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(34.dp).testTag("agent-presence-motion-static")) {
        drawStill(state, color, surface)
    }
}

private fun DrawScope.drawOrbit(state: AgentPresence.State, color: androidx.compose.ui.graphics.Color,
                               surface: androidx.compose.ui.graphics.Color, phase: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val outer = size.minDimension * 0.39f
    val stroke = 1.5.dp.toPx()
    drawCircle(color.copy(alpha = 0.24f), outer, center, style = Stroke(stroke))
    when (state) {
        AgentPresence.State.THINKING -> {
            drawArc(color.copy(alpha = 0.78f), phase * 360f, 88f, false,
                topLeft = Offset(center.x - outer, center.y - outer),
                size = androidx.compose.ui.geometry.Size(outer * 2, outer * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = phase * (2f * PI.toFloat())
            drawCircle(color, 2.8.dp.toPx(), Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer))
            drawCircle(color.copy(alpha = 0.92f), 3.7.dp.toPx(), center)
        }
        AgentPresence.State.WORKING -> {
            drawArc(color.copy(alpha = 0.8f), phase * 360f, 132f, false,
                topLeft = Offset(center.x - outer, center.y - outer),
                size = androidx.compose.ui.geometry.Size(outer * 2, outer * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = phase * (2f * PI.toFloat())
            val opposite = angle + PI.toFloat()
            drawCircle(color, 2.6.dp.toPx(), Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer))
            drawCircle(color.copy(alpha = 0.58f), 2.2.dp.toPx(), Offset(center.x + cos(opposite) * outer, center.y + sin(opposite) * outer))
            drawCircle(color, 3.2.dp.toPx(), center)
        }
        else -> drawStill(state, color, surface)
    }
}

private fun DrawScope.drawStill(state: AgentPresence.State, color: androidx.compose.ui.graphics.Color,
                                surface: androidx.compose.ui.graphics.Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val outer = size.minDimension * 0.39f
    val stroke = 1.7.dp.toPx()
    when (state) {
        AgentPresence.State.IDLE -> {
            drawCircle(color.copy(alpha = 0.58f), outer, center, style = Stroke(stroke))
            drawCircle(color, 3.dp.toPx(), center)
        }
        AgentPresence.State.THINKING, AgentPresence.State.WORKING -> {
            drawCircle(color.copy(alpha = 0.45f), outer, center, style = Stroke(stroke))
            drawCircle(color.copy(alpha = 0.75f), 3.6.dp.toPx(), center)
        }
        AgentPresence.State.WAITING_USER -> {
            drawCircle(color.copy(alpha = 0.6f), outer, center, style = Stroke(stroke))
            drawCircle(color, 4.dp.toPx(), center)
            drawCircle(surface, 1.5.dp.toPx(), center)
        }
        AgentPresence.State.ERROR -> {
            drawCircle(color.copy(alpha = 0.18f), outer, center)
            drawCircle(color, outer, center, style = Stroke(stroke))
            drawLine(color, Offset(center.x, center.y - 5.dp.toPx()), Offset(center.x, center.y + 1.dp.toPx()), stroke)
            drawCircle(color, 1.1.dp.toPx(), Offset(center.x, center.y + 5.dp.toPx()))
        }
        AgentPresence.State.DONE -> {
            drawCircle(color, outer, center, style = Stroke(stroke))
            drawLine(color, Offset(center.x - 5.dp.toPx(), center.y), Offset(center.x - 1.dp.toPx(), center.y + 4.dp.toPx()), stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(center.x - 1.dp.toPx(), center.y + 4.dp.toPx()), Offset(center.x + 6.dp.toPx(), center.y - 4.dp.toPx()), stroke, cap = StrokeCap.Round)
        }
    }
}
