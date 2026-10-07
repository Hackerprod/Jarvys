package com.jarvys.agent.crew

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ChatMessageList
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysListRow
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.JarvysPrimaryButton
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysTag
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.JarvysTopAppBar
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ui.readableThemeInk
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.motion.rememberLifecycleVisible
import com.jarvys.agent.ui.motion.rememberMotionViewport
import com.jarvys.agent.ui.motion.rememberMotionEnabled
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class CrewTab { DEBATE, BOARD, BOTS }

object CrewOrbPalette {
    val Captain = Color(0xFFB8C1FF)
    val Explorer = Color(0xFF4FD1C0)
    val Analyst = Color(0xFFF2B84B)
    val Critic = Color(0xFFFF7B8C)
    val Writer = Color(0xFF91C979)
    val Operator = Color(0xFFC99AFF)
    fun color(key: String?): Color = when (key) {
        "blue" -> Explorer
        "teal" -> Explorer
        "violet" -> Analyst
        "amber" -> Analyst
        "rose" -> Critic
        "green" -> Writer
        "operator" -> Operator
        "periwinkle" -> Captain
        else -> {
            val hash = key?.hashCode()?.toLong()?.and(0xffffffffL) ?: 0L
            Color.hsv((hash % 360L).toFloat(), 0.55f, 0.82f)
        }
    }
}

@Composable
fun CrewRoleLabel(roleId: String, roleName: String): String {
    val resource = when (roleId) {
        CrewRoleTemplates.EXPLORER -> R.string.crew_role_explorer
        CrewRoleTemplates.ANALYST -> R.string.crew_role_analyst
        CrewRoleTemplates.CRITIC -> R.string.crew_role_critic
        CrewRoleTemplates.WRITER -> R.string.crew_role_writer
        CrewRoleTemplates.OPERATOR -> R.string.crew_role_operator
        else -> null
    }
    return if (resource == null) roleName else stringResource(resource)
}

@Composable
fun CrewBotAvatar(
    name: String,
    roleId: String?,
    colorKey: String?,
    status: String?,
    waitingReason: String? = null,
    reducedMotionOverride: Boolean? = null,
    modifier: Modifier = Modifier.size(42.dp),
    testTag: String? = null,
    background: Color = MaterialTheme.colorScheme.background,
) {
    val visual = remember(status, waitingReason) { BotVisualState.from(status, waitingReason) }
    val design = remember(roleId) { BotAvatarDesign.forRole(roleId) }
    val viewport = rememberMotionViewport()
    val motion = rememberMotionEnabled(active = visual.active && reducedMotionOverride != true,
        visible = viewport.visible)
    val ink = CrewOrbPalette.color(colorKey)
    val baseColor = botBaseColor(visual.mode, ink)
    val stateText = botVisualStatus(visual)
    val description = stringResource(R.string.crew_bot_avatar_accessibility, name, stateText)
    val avatarModifier = modifier.then(viewport.modifier)
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = stateText
            }
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    if (motion) AnimatedCrewBotGlyph(avatarModifier, design, visual, baseColor, surface, background, onSurface)
    else Canvas(avatarModifier) {
        drawBotFace(design, visual, baseColor, surface, background, onSurface, phase = 0f, animated = false)
    }
}

@Composable
private fun botBaseColor(mode: BotVisualState.Mode, ink: Color) = when (mode) {
    BotVisualState.Mode.WAITING_PROVIDER -> MaterialTheme.colorScheme.tertiary
    BotVisualState.Mode.ERROR -> MaterialTheme.colorScheme.error
    BotVisualState.Mode.INTERRUPTED, BotVisualState.Mode.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> ink
}

@Composable
private fun botVisualStatus(state: BotVisualState): String = stringResource(when (state.mode) {
    BotVisualState.Mode.IDLE -> R.string.crew_status_idle
    BotVisualState.Mode.QUEUED -> R.string.crew_status_queued
    BotVisualState.Mode.RUNNING -> R.string.crew_status_working
    BotVisualState.Mode.WAITING_PROVIDER -> R.string.crew_waiting_provider
    BotVisualState.Mode.WAITING_USER -> R.string.crew_waiting_captain
    BotVisualState.Mode.DONE -> R.string.crew_status_done
    BotVisualState.Mode.ERROR -> R.string.crew_status_failed
    BotVisualState.Mode.INTERRUPTED -> R.string.crew_status_interrupted
})

@Composable
private fun AnimatedCrewBotGlyph(
    modifier: Modifier,
    design: BotAvatarDesign,
    state: BotVisualState,
    baseColor: Color,
    surface: Color,
    background: Color,
    onSurface: Color,
) {
    val cycleMillis = if (state.mode == BotVisualState.Mode.RUNNING) JarvysMotion.BotPulseCycleMillis
        else JarvysMotion.AntennaCycleMillis
    val phase = rememberInfiniteTransition(label = "crew-bot-animation").animateFloat(
        0f, 1f, infiniteRepeatable(tween(cycleMillis, easing = androidx.compose.animation.core.LinearEasing),
            RepeatMode.Restart), label = "crew-bot-phase")
    Canvas(modifier) { drawBotFace(design, state, baseColor, surface, background, onSurface, phase.value, animated = true) }
}

internal fun crewAvatarStrokeInk(identity: Color, face: Color, background: Color, onSurface: Color): Color =
    readableThemeInk(readableThemeInk(identity, face, onSurface, 3.2), background, onSurface, 3.2)

private fun DrawScope.drawBotFace(
    design: BotAvatarDesign,
    state: BotVisualState,
    base: Color,
    surface: Color,
    background: Color,
    onSurface: Color,
    phase: Float,
    animated: Boolean,
) {
    val center = Offset(size.width / 2f, size.height * 0.58f)
    val radius = size.minDimension * 0.31f
    val line = 1.5.dp.toPx()
    val alpha = if (state.mode == BotVisualState.Mode.INTERRUPTED) 0.52f else 1f
    val faceColor = surface.copy(alpha = 0.98f).compositeOver(background)
    val stroke = crewAvatarStrokeInk(base.copy(alpha = alpha), faceColor, background, onSurface)
    fun faceInk(opacity: Float = 1f) = readableThemeInk(base.copy(alpha = opacity), faceColor, onSurface, 3.2)
    val head = Path().apply {
        repeat(design.sides) { index ->
            val angle = ((design.rotationDegrees + index * 360f / design.sides) * (PI / 180f)).toFloat()
            val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
    drawPath(head, surface, alpha = 0.98f)
    drawPath(head, stroke, style = Stroke(line, cap = StrokeCap.Round))

    val top = Offset(center.x, center.y - radius)
    val antennaTip = Offset(center.x + (design.antennaStyle - 1) * radius * 0.3f,
        top.y - size.height * 0.17f)
    drawLine(stroke, top, antennaTip, line, cap = StrokeCap.Round)
    if (design.antennaStyle == 1) {
        drawLine(stroke, antennaTip, Offset(antennaTip.x - radius * 0.26f, antennaTip.y - radius * 0.22f),
            line, cap = StrokeCap.Round)
        drawLine(stroke, antennaTip, Offset(antennaTip.x + radius * 0.26f, antennaTip.y - radius * 0.22f),
            line, cap = StrokeCap.Round)
    }
    val pulse = if (animated) (0.72f + 0.28f * sin(phase * 2f * PI.toFloat())).coerceIn(0.42f, 1f) else 0.72f
    drawCircle(base.copy(alpha = alpha * pulse), if (state.mode == BotVisualState.Mode.QUEUED) 2.2.dp.toPx() else 2.7.dp.toPx(), antennaTip)

    val eyeY = center.y - radius * 0.08f
    val eyeDistance = radius * design.eyeSpacing
    val eyeShift = if (animated && state.mode == BotVisualState.Mode.RUNNING)
        sin(phase * 2f * PI.toFloat()) * 1.2.dp.toPx() else 0f
    drawCircle(stroke, 1.75.dp.toPx(), Offset(center.x - eyeDistance + eyeShift, eyeY))
    drawCircle(stroke, 1.75.dp.toPx(), Offset(center.x + eyeDistance + eyeShift, eyeY))

    when (state.mode) {
        BotVisualState.Mode.ERROR -> {
            drawLine(faceInk(), Offset(center.x - 3.dp.toPx(), center.y + radius * 0.42f),
                Offset(center.x + 3.dp.toPx(), center.y + radius * 0.42f), line, cap = StrokeCap.Round)
        }
        BotVisualState.Mode.INTERRUPTED -> drawLine(faceInk(0.8f),
            Offset(center.x - radius * 0.55f, center.y + radius * 0.5f),
            Offset(center.x + radius * 0.55f, center.y - radius * 0.5f), line, cap = StrokeCap.Round)
        BotVisualState.Mode.DONE -> {
            drawLine(faceInk(), Offset(center.x - 3.dp.toPx(), center.y + radius * 0.38f),
                Offset(center.x - 0.3.dp.toPx(), center.y + radius * 0.64f), line, cap = StrokeCap.Round)
            drawLine(faceInk(), Offset(center.x - 0.3.dp.toPx(), center.y + radius * 0.64f),
                Offset(center.x + 4.dp.toPx(), center.y + radius * 0.1f), line, cap = StrokeCap.Round)
        }
        BotVisualState.Mode.WAITING_PROVIDER, BotVisualState.Mode.WAITING_USER ->
            drawLine(faceInk(if (animated) pulse else 0.82f),
                Offset(center.x - 3.dp.toPx(), center.y + radius * 0.5f),
                Offset(center.x + 3.dp.toPx(), center.y + radius * 0.5f), line, cap = StrokeCap.Round)
        else -> drawLine(faceInk(0.85f),
            Offset(center.x - 2.6.dp.toPx(), center.y + radius * 0.45f),
            Offset(center.x + 2.6.dp.toPx(), center.y + radius * 0.45f), line, cap = StrokeCap.Round)
    }
}

@Composable
fun CrewApprovalAttribution(requester: String, colorKey: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val background = colors.tertiaryContainer.copy(alpha = 0.46f).compositeOver(colors.background)
    Row(modifier.fillMaxWidth().testTag("crew-approval-attribution"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CrewBotAvatar(requester, roleId = colorKey ?: requester, colorKey = colorKey,
            status = "WAITING", waitingReason = "human approval",
            modifier = Modifier.size(28.dp), testTag = "crew-approval-requester-orb", background = background)
        Text(stringResource(R.string.approval_requested_by_crew_bot, requester),
            color = readableThemeInk(CrewOrbPalette.color(colorKey), background, colors.onSurface),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CrewMissionCard(
    snapshot: CrewMissionSnapshot,
    onOpen: () -> Unit,
    reducedMotionOverride: Boolean? = null,
) {
    val reducedMotion = reducedMotionOverride ?: LocalReducedMotion.current
    val complete = snapshot.status == "SYNTHESIZED"
    val viewport = rememberMotionViewport()
    val elapsedUpdatesAllowed = rememberLifecycleVisible(viewport.visible)
    var now by remember(snapshot.missionId, snapshot.finishedAtMillis) {
        mutableLongStateOf(snapshot.finishedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis())
    }
    LaunchedEffect(snapshot.missionId, snapshot.status, elapsedUpdatesAllowed) {
        if (snapshot.status == "RUNNING" && elapsedUpdatesAllowed) while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val duration = ((if (snapshot.finishedAtMillis > 0) snapshot.finishedAtMillis else now)
        - snapshot.startedAtMillis).coerceAtLeast(0L)
    val elapsed = stringResource(R.string.crew_elapsed_time, duration / 60000, (duration / 1000) % 60)
    AnimatedContent(
        targetState = complete,
        modifier = viewport.modifier,
        transitionSpec = {
            val enter = if (reducedMotion) EnterTransition.None
                else scaleIn(JarvysMotion.placeChange(), initialScale = 0.96f) + fadeIn(JarvysMotion.placeChange())
            val exit = if (reducedMotion) ExitTransition.None
                else scaleOut(JarvysMotion.placeChange(), targetScale = 0.98f) + fadeOut(JarvysMotion.placeChange())
            enter togetherWith exit
        },
        label = "crew-mission-synthesis-transition",
    ) { synthesized ->
    if (synthesized) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("crew-mission-pill").clickable(onClick = onOpen),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CrewOrbPalette.Captain.copy(alpha = 0.3f)),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CrewBotAvatar(stringResource(R.string.crew_captain_name), "captain", "periwinkle", "DONE",
                    modifier = Modifier.size(28.dp), testTag = "crew-orb-captain",
                    background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f)
                        .compositeOver(MaterialTheme.colorScheme.background))
                Text(stringResource(R.string.crew_synthesized_by, snapshot.bots.size, elapsed),
                    Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onOpen) { Text(stringResource(R.string.crew_view_how)) }
            }
        }
    } else {

    JarvysGroup(Modifier.fillMaxWidth().testTag("crew-mission-card").clickable(onClick = onOpen),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(JarvysUiTokens.ScreenPadding),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.crew_mission_in_progress), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(elapsed, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("crew-elapsed"))
            }
            Text(snapshot.title, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.testTag("crew-orb-row")) {
                CrewBotAvatar(stringResource(R.string.crew_captain_name), "captain", "periwinkle", snapshot.status,
                    reducedMotionOverride = reducedMotion,
                    testTag = "crew-orb-captain", background = MaterialTheme.colorScheme.surfaceContainerHigh)
                snapshot.bots.forEach { bot ->
                    CrewBotAvatar(bot.name, bot.roleId, bot.colorKey, bot.status, bot.waitingReason,
                        reducedMotionOverride = reducedMotion,
                        testTag = "crew-orb-${bot.id}", background = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
            snapshot.bots.forEach { bot ->
                val activity = snapshot.messages.lastOrNull { it.from == bot.id && it.type == CrewMessage.Type.STATUS }?.text
                val status = when {
                    bot.waitingReason == "limite del proveedor" -> stringResource(R.string.crew_waiting_provider)
                    bot.waitingReason.isNotBlank() -> stringResource(R.string.crew_waiting_captain)
                    activity != null -> localizeCrewActivity(activity)
                    else -> crewBotStatus(bot.status)
                }
                Row(Modifier.fillMaxWidth().testTag("crew-status-${bot.id}"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(CrewOrbPalette.color(bot.colorKey)))
                    Text(bot.name, color = readableThemeInk(CrewOrbPalette.color(bot.colorKey),
                        MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            if (snapshot.bots.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("crew-progress"))
            else {
                val terminal = snapshot.bots.count { !it.active() }
            if (terminal == snapshot.bots.size) LinearProgressIndicator(progress = { 1f },
                modifier = Modifier.fillMaxWidth().testTag("crew-progress"))
                else LinearProgressIndicator(Modifier.fillMaxWidth().testTag("crew-progress"))
            }
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End).testTag("crew-open-debate")) {
                Text(stringResource(R.string.crew_view_debate_count, snapshot.messageCount()))
            }
        }
    }
    }
}
}

private enum class CrewScreenTab { DEBATE, BOARD, BOTS }

@Composable
fun CrewMissionScreen(
    snapshot: CrewMissionSnapshot?,
    board: CrewBoard,
    readOnly: Boolean,
    onOpenBot: (String) -> Unit,
    onAskBot: (String, String) -> Unit,
    onStopAll: () -> Unit,
    initialBoardReference: String? = null,
    onBoardReferenceConsumed: () -> Unit = {},
    crewMode: CrewMode = CrewMode.AUTO,
    onCrewModeChange: (CrewMode) -> Unit = {},
) {
    var configuringCoding by remember { mutableStateOf(false) }
    if (configuringCoding) {
        CrewCodingProfileSettings(onClose = { configuringCoding = false }, conversationId = snapshot?.conversationId)
        return
    }
    var tab by remember(snapshot?.missionId) { mutableStateOf(CrewScreenTab.DEBATE) }
    var selectedBoardFile by remember(snapshot?.missionId) { mutableStateOf<String?>(null) }
    var boardFiles by remember(snapshot?.missionId) { mutableStateOf<List<String>>(emptyList()) }
    var boardText by remember(snapshot?.missionId) { mutableStateOf("") }
    var showAskPicker by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot?.missionId, tab, selectedBoardFile) {
        if (tab != CrewScreenTab.BOARD || snapshot == null) return@LaunchedEffect
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            board.list() to selectedBoardFile?.let { runCatching { board.read(it) }.getOrDefault("") }.orEmpty()
        }
        boardFiles = loaded.first
        boardText = loaded.second
    }
    LaunchedEffect(snapshot?.missionId, initialBoardReference) {
        if (!initialBoardReference.isNullOrBlank()) {
            tab = CrewScreenTab.BOARD
            selectedBoardFile = initialBoardReference
            onBoardReferenceConsumed()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("crew-mission-screen")) {
        val headerMaxHeight = if (snapshot == null) maxHeight * 0.6f else maxHeight / 3
        Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().heightIn(max = headerMaxHeight)
            .verticalScroll(rememberScrollState()).testTag("crew-mission-header")) {
        CrewModePicker(crewMode, onCrewModeChange, modifier = Modifier.padding(horizontal = JarvysUiTokens.ScreenPadding))
        TextButton(onClick = { configuringCoding = true }, modifier = Modifier.fillMaxWidth().testTag("crew-configure-coding")) {
            Text(stringResource(R.string.crew_profile_configure))
        }
        if (snapshot != null) Row(Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CrewBotAvatar(stringResource(R.string.crew_captain_name), "captain", "periwinkle", snapshot.status,
                testTag = "crew-orb-captain")
            Column(Modifier.weight(1f)) {
                Text(snapshot.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(crewMissionStatus(snapshot.status), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (readOnly) JarvysTag(stringResource(R.string.crew_read_only))
        }
        }
        if (snapshot == null) {
            CrewEmptyState()
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CrewTabChip(stringResource(R.string.crew_tab_debate), tab == CrewScreenTab.DEBATE,
                Modifier.testTag("crew-tab-debate")) { tab = CrewScreenTab.DEBATE }
            CrewTabChip(stringResource(R.string.crew_tab_board), tab == CrewScreenTab.BOARD,
                Modifier.testTag("crew-tab-board")) { tab = CrewScreenTab.BOARD }
            CrewTabChip(stringResource(R.string.crew_tab_bots), tab == CrewScreenTab.BOTS,
                Modifier.testTag("crew-tab-bots")) { tab = CrewScreenTab.BOTS }
        }
        Spacer(Modifier.height(8.dp))
        when {
            tab == CrewScreenTab.DEBATE -> {
                if (snapshot.messages.isEmpty()) CrewEmptyTab(Modifier.weight(1f), R.string.crew_debate_empty)
                else ChatMessageList(
                    conversationKey = snapshot.missionId,
                    messages = snapshot.messages,
                    isRunning = snapshot.active(),
                    messageKey = { it.id },
                    isUserMessage = { it.from == "user" },
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("crew-debate-list"),
                    itemContent = { message -> CrewMessageRow(message, snapshot.bots, onBoardReference = { ref ->
                        selectedBoardFile = ref
                        tab = CrewScreenTab.BOARD
                    }) },
                )
            }
            tab == CrewScreenTab.BOARD -> {
                if (selectedBoardFile == null) {
                    if (boardFiles.isEmpty()) CrewEmptyTab(Modifier.weight(1f), R.string.crew_board_empty)
                    else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("crew-board-files")) {
                        items(boardFiles, key = { it }) { entry ->
                            val path = entry.removePrefix("FILE ").substringBefore(" (")
                            val name = path.substringAfterLast('/')
                            JarvysListRow(name, stringResource(R.string.crew_board_file),
                                icon = LucideIcons.FileText,
                                onClick = { selectedBoardFile = path })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().testTag("crew-board-viewer")) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selectedBoardFile = null }) { Text(stringResource(R.string.crew_board_back)) }
                            Text(selectedBoardFile.orEmpty(), modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = JarvysUiTokens.ScreenPadding)
                            .testTag("crew-board-content")) {
                            item { SelectionContainer { Text(boardText, color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium) } }
                        }
                    }
                }
            }
            else -> {
                if (snapshot.bots.isEmpty()) CrewEmptyTab(Modifier.weight(1f), R.string.crew_bots_empty)
                else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("crew-bot-list")) {
                    items(snapshot.bots, key = { it.id }) { bot ->
                        JarvysListRow(bot.name, CrewRoleLabel(bot.roleId, bot.roleName),
                            leadingContent = { CrewBotAvatar(bot.name, bot.roleId, bot.colorKey, bot.status,
                                bot.waitingReason) },
                            trailing = { JarvysTag(crewBotStatus(bot.status)) },
                            modifier = Modifier.testTag("crew-bot-row-${bot.id}"),
                            onClick = { onOpenBot(bot.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                    }
                }
            }
        }
        if (!readOnly) Row(Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { showAskPicker = !showAskPicker }, modifier = Modifier.weight(1f)
                .testTag("crew-ask-bot")) { Text(stringResource(R.string.crew_ask_bot)) }
            OutlinedButton(onClick = onStopAll, modifier = Modifier.weight(1f).testTag("crew-stop-all")) {
                Text(stringResource(R.string.crew_stop_all))
            }
        }
        if (showAskPicker && !readOnly) Column(Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                snapshot.bots.filter { it.status != "INTERRUPTED" && !it.resumeRequired }.forEach { bot ->
                TextButton(onClick = { showAskPicker = false; onAskBot(bot.id, "") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.crew_ask_named_bot, bot.name))
                }
            }
        }
    }
}
}

@Composable
fun CrewBotDetailScreen(
    snapshot: CrewMissionSnapshot?,
    botId: String,
    board: CrewBoard,
    readOnly: Boolean,
    onSendMessage: (String, String) -> Unit,
    onRedirect: (String, String) -> Unit,
    onStopBot: (String) -> Unit,
    onBoardReference: (String) -> Unit,
    onResume: (String) -> Unit = {},
) {
    val bot = snapshot?.bots?.firstOrNull { it.id == botId }
    var message by remember(botId) { mutableStateOf("") }
    val conversation = remember(snapshot?.missionId, botId, snapshot?.messages) {
        snapshot?.messages.orEmpty().filter { it.from == botId || it.to == botId }
    }
    Column(Modifier.fillMaxSize().testTag("crew-bot-detail")) {
        if (bot == null) {
            CrewEmptyState()
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CrewBotAvatar(bot.name, bot.roleId, bot.colorKey, bot.status, bot.waitingReason,
                testTag = "crew-bot-detail-orb")
            Column(Modifier.weight(1f)) {
                Text(bot.name, color = readableThemeInk(CrewOrbPalette.color(bot.colorKey),
                    MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onSurface),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(CrewRoleLabel(bot.roleId, bot.roleName), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            JarvysTag(crewBotStatus(bot.status))
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (bot.roleId == CrewRoleTemplates.EXPLORER || bot.roleId == CrewRoleTemplates.CRITIC) {
                item { JarvysTag(stringResource(R.string.crew_read_only)) }
            }
            items(bot.tools) { JarvysTag(it) }
        }
        Text(stringResource(R.string.crew_mission_label), Modifier.padding(horizontal = JarvysUiTokens.ScreenPadding, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(bot.mission, Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        if (bot.status == "FAILED") JarvysGroup(
            modifier = Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            Text(stringResource(R.string.crew_bot_error, bot.error), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall)
        }
        if (bot.status == "INTERRUPTED") Text(stringResource(R.string.crew_bot_interrupted_description),
            Modifier.fillMaxWidth().padding(horizontal = JarvysUiTokens.ScreenPadding),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (bot.status == "WAITING" && bot.waitingReason.isNotBlank()) {
            Text(crewWaitingReason(bot.waitingReason), modifier = Modifier.padding(horizontal = JarvysUiTokens.ScreenPadding),
                color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        if (conversation.isEmpty()) CrewEmptyTab(Modifier.weight(1f), R.string.crew_bot_thread_empty)
        else ChatMessageList(
            conversationKey = "${snapshot.missionId}-$botId",
            messages = conversation,
            isRunning = bot.active(),
            messageKey = { it.id },
            isUserMessage = { it.from == "user" || it.from == "chief" },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("crew-bot-thread"),
            itemContent = { CrewMessageRow(it, snapshot.bots, onBoardReference) },
        )
        if (bot.resumeRequired || bot.status == "INTERRUPTED") CrewResumePanel(
            canResume = !readOnly && bot.canResume, note = bot.recoveryNote, onResume = { onResume(botId) })
        if (!readOnly && !bot.resumeRequired && bot.status != "INTERRUPTED") Column(Modifier.fillMaxWidth().padding(JarvysUiTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JarvysTextField(value = message, onValueChange = { message = it },
                label = { Text(stringResource(R.string.crew_message_placeholder)) },
                modifier = Modifier.testTag("crew-user-message"), singleLine = false, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JarvysPrimaryButton(stringResource(R.string.crew_send_message), {
                    if (message.isNotBlank()) { onSendMessage(botId, message); message = "" }
                }, Modifier.weight(1f).testTag("crew-user-send"), enabled = message.isNotBlank())
                OutlinedButton(onClick = {
                    if (message.isNotBlank()) { onRedirect(botId, message); message = "" }
                }, enabled = message.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = JarvysUiTokens.PrimaryButtonHeight)) {
                    Text(stringResource(R.string.crew_redirect))
                }
            }
            if (bot.active()) OutlinedButton(onClick = { onStopBot(botId) },
                modifier = Modifier.fillMaxWidth().testTag("crew-stop-bot")) {
                Text(stringResource(R.string.crew_stop_bot))
            }
        }
    }
}

@Composable
fun CrewResumePanel(canResume: Boolean, note: String, onResume: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
        .padding(JarvysUiTokens.ScreenPadding).testTag("crew-resume-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (canResume) R.string.crew_resume_description else R.string.crew_history_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (note.isNotBlank()) Text(note, Modifier.testTag("crew-resume-note"), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (canResume) JarvysPrimaryButton(stringResource(R.string.crew_resume_action), onResume,
            Modifier.fillMaxWidth().testTag("crew-resume-action"))
    }
}

@Composable
fun CrewModePicker(mode: CrewMode, onChange: (CrewMode) -> Unit, onOpenCrew: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    JarvysGroup(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                JarvysSectionLabel(stringResource(R.string.crew_mode_title), Modifier.weight(1f))
                if (onOpenCrew != null) TextButton(onClick = onOpenCrew) { Text(stringResource(R.string.crew_open_workspace)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(CrewMode.OFF to R.string.crew_mode_off, CrewMode.AUTO to R.string.crew_mode_auto,
                    CrewMode.ALWAYS to R.string.crew_mode_always).forEach { (value, label) ->
                    FilterChip(selected = mode == value, onClick = { onChange(value) },
                        label = { Text(stringResource(label), maxLines = 1) }, modifier = Modifier.weight(1f)
                            .testTag("crew-mode-${value.name.lowercase()}"))
                }
            }
        }
    }
}

@Composable
private fun CrewMessageRow(message: CrewMessage, bots: List<CrewBotSnapshot>, onBoardReference: (String) -> Unit) {
    val sender = bots.firstOrNull { it.id == message.from }
    val senderName = when {
        "chief" == message.from -> stringResource(R.string.crew_captain_name)
        "user" == message.from -> stringResource(R.string.crew_type_user)
        sender != null -> sender.name
        else -> message.from
    }
    val recipient = bots.firstOrNull { it.id == message.to }
    val recipientName = when {
        "chief" == message.to -> stringResource(R.string.crew_captain_name)
        "user" == message.to -> stringResource(R.string.crew_type_user)
        recipient != null -> recipient.name
        else -> message.to
    }
    val identity = when {
        "chief" == message.from -> CrewOrbPalette.Captain
        "user" == message.from -> MaterialTheme.colorScheme.primary
        sender != null -> CrewOrbPalette.color(sender.colorKey)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val color = readableThemeInk(identity,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f).compositeOver(MaterialTheme.colorScheme.background),
        MaterialTheme.colorScheme.onSurface)
    Row(Modifier.fillMaxWidth().testTag("crew-message-${message.id}"),
        horizontalArrangement = if (message.from == "user") Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top) {
        if (message.from != "user") CrewBotAvatar(senderName,
            sender?.roleId ?: if (message.from == "chief") "captain" else message.from,
            sender?.colorKey ?: if (message.from == "chief") "periwinkle" else null,
            sender?.status ?: "DONE", sender?.waitingReason,
            modifier = Modifier.size(28.dp), testTag = "crew-message-orb-${message.id}")
        Spacer(Modifier.width(8.dp))
        Card(Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
            shape = RoundedCornerShape(15.dp)) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(senderName, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.crew_message_route, senderName, recipientName),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    JarvysTag(crewMessageType(message.type))
                }
                if (message.type == CrewMessage.Type.CRITIQUE) {
                    Surface(color = CrewOrbPalette.Critic, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().height(3.dp)) { }
                }
                Text(if (message.type == CrewMessage.Type.STATUS) localizeCrewActivity(message.text) else message.text,
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                message.refs.forEach { ref ->
                    TextButton(onClick = { onBoardReference(ref) }, modifier = Modifier.testTag("crew-ref-${message.id}")) {
                        Text(ref, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun CrewTabChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label, maxLines = 1) }, modifier = modifier)
}

@Composable
private fun CrewEmptyState() {
    Column(Modifier.fillMaxSize().padding(32.dp).testTag("crew-empty-state"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CrewBotAvatar(stringResource(R.string.crew_captain_name), "captain", "periwinkle", "IDLE")
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.crew_empty_title), color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.crew_empty_body), Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CrewEmptyTab(modifier: Modifier = Modifier, messageResource: Int) {
    Box(modifier.fillMaxWidth().testTag("crew-empty-tab"), contentAlignment = Alignment.Center) {
        Text(stringResource(messageResource), modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun crewBotStatus(status: String): String = stringResource(when (status) {
    "QUEUED" -> R.string.crew_status_queued
    "RUNNING" -> R.string.crew_status_working
    "WAITING" -> R.string.crew_status_waiting
    "DONE" -> R.string.crew_status_done
    "FAILED" -> R.string.crew_status_failed
    "STOPPED" -> R.string.crew_status_stopped
    "INTERRUPTED" -> R.string.crew_status_interrupted
    else -> R.string.crew_status_working
})

@Composable
private fun crewWaitingReason(reason: String): String = when (reason) {
    "limite del proveedor" -> stringResource(R.string.crew_waiting_provider)
    else -> stringResource(R.string.crew_waiting_captain)
}

@Composable
private fun crewMissionStatus(status: String): String = stringResource(when (status) {
    "RUNNING" -> R.string.crew_status_working
    "SYNTHESIZED" -> R.string.crew_status_synthesized
    "STOPPED" -> R.string.crew_status_stopped
    "FAILED" -> R.string.crew_status_failed
    "INTERRUPTED" -> R.string.crew_status_interrupted
    else -> R.string.crew_status_working
})

@Composable
private fun crewMessageType(type: CrewMessage.Type): String = stringResource(when (type) {
    CrewMessage.Type.TASK -> R.string.crew_type_task
    CrewMessage.Type.FINDING -> R.string.crew_type_finding
    CrewMessage.Type.CRITIQUE -> R.string.crew_type_critique
    CrewMessage.Type.QUESTION -> R.string.crew_type_question
    CrewMessage.Type.ANSWER -> R.string.crew_type_answer
    CrewMessage.Type.RESULT -> R.string.crew_type_result
    CrewMessage.Type.STATUS -> R.string.crew_type_status
    CrewMessage.Type.USER -> R.string.crew_type_user
})

@Composable
private fun localizeCrewActivity(activity: String): String = when {
    activity.startsWith("Using ") -> stringResource(R.string.crew_status_using_tool, activity.removePrefix("Using "))
    activity.startsWith("Completed ") -> stringResource(R.string.crew_status_completed_tool, activity.removePrefix("Completed "))
    activity.startsWith("Failed ") -> stringResource(R.string.crew_status_failed_tool, activity.removePrefix("Failed "))
    else -> activity
}
