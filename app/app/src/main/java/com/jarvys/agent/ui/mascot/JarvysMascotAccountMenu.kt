package com.jarvys.agent.ui.mascot

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import com.jarvys.agent.JarvysAnimationRepository
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.util.UUID
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarvys.agent.JarvysMascotAssets
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.crew.MascotPilotScreen
import com.jarvys.agent.crew.MascotPilotSession
import com.jarvys.agent.crew.rememberMascotResumed
import com.jarvys.agent.ui.motion.rememberMotionViewport

/** Process-memory only. A new process starts disabled; no preferences or saved state are written. */
internal object JarvysMascotProcessConsent {
    var session by mutableStateOf(MascotPilotSession())
        private set

    fun enable(resumed: Boolean, visible: Boolean) { session = session.start(resumed, visible) }
    fun revoke() { session = session.stop() }
}

/** Consent survives UI navigation/background in this process; native views never do. */
@Composable
internal fun JarvysMascotAccountMenu(
    mode: Int?,
    visibleSessionId: String?,
    liveConversationVisible: Boolean,
    surfaceVisible: Boolean,
    mascotSize: Dp = 30.dp,
    originalHeaderAnimation: Boolean = false,
    failedRunId: String? = null,
    interruptedRunId: String? = null,
    liveActivity: JarvysHeaderActivity? = null,
    liveActivityKey: String? = null,
    levitate: Boolean = false,
    controlledMenuOpen: Boolean? = null,
    onMenuOpenChange: ((Boolean) -> Unit)? = null,
    content: @Composable (closeMenu: () -> Unit) -> Unit,
) {
    // These keys close local menus/dialogs and dispose renderers; process consent is separate.
    key(visibleSessionId, liveConversationVisible, surfaceVisible) {
        JarvysMascotAccountMenuSession(mode, visibleSessionId, liveConversationVisible, surfaceVisible, mascotSize,
            originalHeaderAnimation, failedRunId, interruptedRunId, liveActivity, liveActivityKey, levitate,
            controlledMenuOpen, onMenuOpenChange, content)
    }
}

@Composable
private fun JarvysMascotAccountMenuSession(
    mode: Int?,
    visibleSessionId: String?,
    liveConversationVisible: Boolean,
    surfaceVisible: Boolean,
    mascotSize: Dp,
    originalHeaderAnimation: Boolean,
    failedRunId: String?,
    interruptedRunId: String?,
    liveActivity: JarvysHeaderActivity?,
    liveActivityKey: String?,
    levitate: Boolean,
    controlledMenuOpen: Boolean?,
    onMenuOpenChange: ((Boolean) -> Unit)?,
    content: @Composable (closeMenu: () -> Unit) -> Unit,
) {
    var internalMenuOpen by remember { mutableStateOf(false) }
    val menuOpen = controlledMenuOpen ?: internalMenuOpen
    fun setMenuOpen(open: Boolean) {
        if (controlledMenuOpen == null) internalMenuOpen = open
        onMenuOpenChange?.invoke(open)
    }
    var tauntEventId by remember { mutableStateOf<String?>(null) }
    var warningOpen by remember { mutableStateOf(false) }
    var pilotOpen by remember { mutableStateOf(false) }
    val session = JarvysMascotProcessConsent.session
    val surface = remember { MascotAccountSurface() }
    var playbackFailed by remember { mutableStateOf(false) }
    val viewport = rememberMotionViewport()
    fun revoke() {
        JarvysMascotProcessConsent.revoke()
        warningOpen = false
    }
    val resumed = rememberMascotResumed { warningOpen = false }
    val eligible = liveConversationVisible && !visibleSessionId.isNullOrBlank() && surfaceVisible
    LaunchedEffect(viewport.visible, eligible) {
        if (!viewport.visible || !eligible) warningOpen = false
    }
    DisposableEffect(surface) { onDispose { surface.active = false } }
    val canEnable = jarvysMascotAnimationAvailable() && eligible && resumed && viewport.visible
    val accountDescription = stringResource(R.string.jarvys_account_menu)
    val latestMode by rememberUpdatedState(mode)
    val latestResumed by rememberUpdatedState(resumed)
    val latestVisible by rememberUpdatedState(viewport.visible)
    val renderEpoch = session.generation
    fun isCurrent() = jarvysMascotAnimationAvailable() && surface.active && eligible && !menuOpen && !warningOpen && !pilotOpen &&
        jarvysMascotKnownLiveMode(latestMode) &&
        JarvysMascotProcessConsent.session.accepts(renderEpoch, latestResumed, latestVisible)
    Box {
        val tauntDescription = stringResource(R.string.jarvys_mascot_play_taunt)
        JarvysMascotHeaderButton(
            originalHeaderAnimation = originalHeaderAnimation,
            onTap = { tauntEventId = UUID.randomUUID().toString() },
            onMenu = { setMenuOpen(true) },
            accountDescription = accountDescription,
            modifier = Modifier.size(maxOf(48.dp, mascotSize)).then(viewport.modifier)
                .testTag("jarvys-account-menu").semantics {
                    contentDescription = if (originalHeaderAnimation) tauntDescription else accountDescription
                },
        ) {
            if (originalHeaderAnimation) {
                val context = LocalContext.current.applicationContext
                // Deliberately independent of the disabled legacy experiment/consent gate.
                val motionAllowed = surfaceVisible && resumed && viewport.visible && !menuOpen
                val remoteEligible = motionAllowed && !LocalReducedMotion.current
                val sadAsset = if (remoteEligible) {
                    remember(context) { JarvysAnimationRepository.sad(context) }.collectAsState().value
                } else null
                val interruptedAsset = if (remoteEligible) {
                    remember(context) { JarvysAnimationRepository.animation(context, "interrupted") }.collectAsState().value
                } else null
                val tauntAsset = if (remoteEligible) {
                    remember(context) { JarvysAnimationRepository.animation(context, "taunt") }.collectAsState().value
                } else null
                LaunchedEffect(remoteEligible) { if (!remoteEligible) tauntEventId = null }
                val activityAnimationId = when (liveActivity) {
                    JarvysHeaderActivity.WAITING -> "waiting"
                    JarvysHeaderActivity.WORKING -> "working"
                    JarvysHeaderActivity.THINKING -> "thinking"
                    null -> null
                }
                val activityAsset = if (remoteEligible && activityAnimationId != null && !liveActivityKey.isNullOrBlank()) {
                    remember(context, activityAnimationId) {
                        JarvysAnimationRepository.animation(context, activityAnimationId)
                    }.collectAsState().value
                } else null
                LaunchedEffect(remoteEligible) {
                    if (remoteEligible) JarvysAnimationRepository.requestRefresh(context)
                }
                JarvysHeaderMascot(
                    modifier = Modifier.size(mascotSize).testTag("jarvys-topbar-mascot")
                        .jarvysHeaderFloat(levitate && motionAllowed),
                    failedRunId = failedRunId,
                    motionAllowed = motionAllowed,
                    sadAsset = sadAsset,
                    requestedActivity = liveActivity,
                    activityKey = liveActivityKey,
                    activityAsset = activityAsset,
                    interruptedRunId = interruptedRunId,
                    interruptedAsset = interruptedAsset,
                    tauntEventId = tauntEventId,
                    tauntAsset = tauntAsset,
                )
            } else key(session.generation) {
                JarvysMascotIcon(Modifier.size(mascotSize).testTag("jarvys-topbar-mascot"),
                    mode = mode,
                    animationOptedIn = isCurrent(),
                    isAnimationCurrent = { isCurrent() },
                    onAnimationStopped = { failed ->
                        if (failed && isCurrent()) {
                            playbackFailed = true
                            revoke()
                        }
                    },
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenuOpen(false) }) {
            content { setMenuOpen(false) }
            if (jarvysMascotAnimationAvailable()) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (session.optedIn) R.string.jarvys_mascot_disable else R.string.jarvys_mascot_enable)) },
                    leadingIcon = { Icon(LucideIcons.Bot, contentDescription = null) },
                    enabled = canEnable || session.optedIn,
                    modifier = Modifier.testTag("jarvys-mascot-toggle"),
                    onClick = {
                        setMenuOpen(false)
                        if (session.optedIn) revoke() else warningOpen = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.jarvys_mascot_visual_test)) },
                    enabled = resumed && surfaceVisible,
                    modifier = Modifier.testTag("jarvys-mascot-visual-test"),
                    onClick = { setMenuOpen(false); pilotOpen = true },
                )
            }
            if (playbackFailed) DropdownMenuItem(
                text = { Text(stringResource(R.string.jarvys_mascot_unavailable)) },
                enabled = false, onClick = {},
            )
        }
    }
    if (jarvysMascotAnimationAvailable() && warningOpen) AlertDialog(
        onDismissRequest = { warningOpen = false },
        title = { Text(stringResource(R.string.jarvys_mascot_warning_title)) },
        text = { ScrollableDialogContent {
            Text(stringResource(R.string.jarvys_mascot_warning))
            Text(stringResource(R.string.jarvys_mascot_activity_notice))
        } },
        confirmButton = { TextButton(
            onClick = {
                warningOpen = false
                if (canEnable) {
                    playbackFailed = false
                    JarvysMascotProcessConsent.enable(resumed, viewport.visible)
                }
            }, enabled = canEnable, modifier = Modifier.testTag("jarvys-mascot-confirm-enable"),
        ) { Text(stringResource(R.string.jarvys_mascot_enable_once)) } },
        dismissButton = { TextButton(onClick = { warningOpen = false }) {
            Text(stringResource(R.string.jarvys_mascot_keep_static))
        } },
    )
    if (jarvysMascotAnimationAvailable() && pilotOpen && surfaceVisible) {
        val context = LocalContext.current.applicationContext
        Dialog(onDismissRequest = { pilotOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                MascotPilotScreen(
                    verifiedLoad = { JarvysMascotAssets.load(context) },
                    fallback = { modifier -> JarvysMascotIcon(modifier) },
                    isOpen = { pilotOpen && surfaceVisible },
                    onClose = { pilotOpen = false },
                    titleRes = R.string.jarvys_mascot_visual_test,
                )
            }
        }
    }
}

/** Validity belongs to one surface, independently of the retained consent generation. */
private class MascotAccountSurface(var active: Boolean = true)

/** Single tap replaces the one-shot token; long press retains the account menu accessibility action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JarvysMascotHeaderButton(
    originalHeaderAnimation: Boolean,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    accountDescription: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (originalHeaderAnimation) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClickLabel = stringResource(R.string.jarvys_mascot_play_taunt),
            role = Role.Button,
            onLongClickLabel = accountDescription,
            onLongClick = onMenu,
            onClick = onTap,
        ), contentAlignment = Alignment.Center) { content() }
    } else IconButton(onClick = onMenu, modifier = modifier) { content() }
}
