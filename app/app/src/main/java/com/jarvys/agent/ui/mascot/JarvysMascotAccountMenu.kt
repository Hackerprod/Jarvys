package com.jarvys.agent.ui.mascot

import androidx.compose.foundation.layout.Box
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
    content: @Composable (closeMenu: () -> Unit) -> Unit,
) {
    // These keys close local menus/dialogs and dispose renderers; process consent is separate.
    key(visibleSessionId, liveConversationVisible, surfaceVisible) {
        JarvysMascotAccountMenuSession(mode, visibleSessionId, liveConversationVisible, surfaceVisible, content)
    }
}

@Composable
private fun JarvysMascotAccountMenuSession(
    mode: Int?,
    visibleSessionId: String?,
    liveConversationVisible: Boolean,
    surfaceVisible: Boolean,
    content: @Composable (closeMenu: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
    val canEnable = eligible && resumed && viewport.visible
    val accountDescription = stringResource(R.string.jarvys_account_menu)
    val latestMode by rememberUpdatedState(mode)
    val latestResumed by rememberUpdatedState(resumed)
    val latestVisible by rememberUpdatedState(viewport.visible)
    val renderEpoch = session.generation
    fun isCurrent() = surface.active && eligible && !menuOpen && !warningOpen && !pilotOpen &&
        jarvysMascotKnownLiveMode(latestMode) &&
        JarvysMascotProcessConsent.session.accepts(renderEpoch, latestResumed, latestVisible)
    Box {
        IconButton(onClick = { menuOpen = true },
            modifier = Modifier.size(48.dp).then(viewport.modifier).testTag("jarvys-account-menu")
                .semantics { contentDescription = accountDescription }) {
            key(session.generation) {
                JarvysMascotIcon(Modifier.size(30.dp).testTag("jarvys-topbar-mascot"),
                    mode = mode,
                    animationOptedIn = isCurrent(),
                    isAnimationCurrent = { isCurrent() },
                    onAnimationStopped = { failed ->
                        // Background/coverage only pauses. Old surfaces cannot revoke current consent.
                        if (failed && isCurrent()) {
                            playbackFailed = true
                            revoke()
                        }
                    },
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            content { menuOpen = false }
            DropdownMenuItem(
                text = { Text(stringResource(if (session.optedIn) R.string.jarvys_mascot_disable else R.string.jarvys_mascot_enable)) },
                leadingIcon = { Icon(LucideIcons.Bot, contentDescription = null) },
                enabled = canEnable || session.optedIn,
                modifier = Modifier.testTag("jarvys-mascot-toggle"),
                onClick = {
                    menuOpen = false
                    if (session.optedIn) revoke() else warningOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.jarvys_mascot_visual_test)) },
                enabled = resumed && surfaceVisible,
                modifier = Modifier.testTag("jarvys-mascot-visual-test"),
                onClick = { menuOpen = false; pilotOpen = true },
            )
            if (playbackFailed) DropdownMenuItem(
                text = { Text(stringResource(R.string.jarvys_mascot_unavailable)) },
                enabled = false, onClick = {},
            )
        }
    }
    if (warningOpen) AlertDialog(
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
    if (pilotOpen && surfaceVisible) {
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
