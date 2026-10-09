package com.jarvys.agent.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.jarvys.agent.crew.MascotPilotScreen
import com.jarvys.agent.crew.MascotPilotSession
import com.jarvys.agent.crew.rememberMascotResumed
import com.jarvys.agent.ui.motion.rememberMotionViewport

/**
 * Consent exists only in this visible conversation composition. No preference/saveable value is
 * written, so recreation or a native process crash can never re-enable playback on restart.
 */
@Composable
internal fun JarvysMascotAccountMenu(
    mode: Int?,
    visibleSessionId: String?,
    liveConversationVisible: Boolean,
    surfaceVisible: Boolean,
    content: @Composable (closeMenu: () -> Unit) -> Unit,
) {
    // Drawer coverage, history/new-chat navigation and session replacement all forget consent.
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
    var session by remember { mutableStateOf(MascotPilotSession()) }
    var playbackFailed by remember { mutableStateOf(false) }
    val viewport = rememberMotionViewport()
    fun revoke() {
        session = session.stop()
        warningOpen = false
    }
    val resumed = rememberMascotResumed { revoke() }
    val eligible = liveConversationVisible && !visibleSessionId.isNullOrBlank() && surfaceVisible
    LaunchedEffect(viewport.visible, eligible) {
        if (!viewport.visible || !eligible) revoke()
    }
    DisposableEffect(Unit) { onDispose { session = session.stop() } }
    val canEnable = eligible && resumed && viewport.visible
    val accountDescription = stringResource(R.string.jarvys_account_menu)
    val latestMode by rememberUpdatedState(mode)
    val latestResumed by rememberUpdatedState(resumed)
    val latestVisible by rememberUpdatedState(viewport.visible)
    val renderEpoch = session.generation
    fun isCurrent() = eligible && !menuOpen && !warningOpen && !pilotOpen &&
        jarvysMascotKnownLiveMode(latestMode) && session.accepts(renderEpoch, latestResumed, latestVisible)
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
                        // An old disposed renderer must not revoke a newer explicit opt-in.
                        if (session.optedIn && session.generation == renderEpoch) {
                            playbackFailed = failed
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
                onClick = { menuOpen = false; revoke(); pilotOpen = true },
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
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.jarvys_mascot_warning))
            Text(stringResource(R.string.jarvys_mascot_activity_notice))
        } },
        confirmButton = { TextButton(
            onClick = {
                warningOpen = false
                if (canEnable) { playbackFailed = false; session = session.start(resumed, viewport.visible) }
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
