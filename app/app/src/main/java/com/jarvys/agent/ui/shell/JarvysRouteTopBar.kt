package com.jarvys.agent.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.JarvysTopAppBar
import com.jarvys.agent.JarvysMotion
import com.jarvys.agent.JarvysUiTokens
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import com.jarvys.agent.ui.mascot.JarvysMascotAccountMenu
import com.jarvys.agent.ui.mascot.JarvysHeaderActivity
import com.jarvys.agent.ui.mascot.JarvysHeaderFailureTracker
import com.jarvys.agent.ui.motion.LocalReducedMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvysRouteTopBar(
    route: AppRouteMeta,
    agentSnapshot: AgentRunUiSnapshot,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    onImportSkill: () -> Unit,
    canCompact: Boolean,
    compacting: Boolean,
    canReflect: Boolean,
    onCompact: () -> Unit,
    onReflect: () -> Unit,
    mainMascotMode: Int? = null,
    mascotVisibleSessionId: String? = null,
    mascotLiveConversationVisible: Boolean = false,
    mascotSurfaceVisible: Boolean = true,
    centralMascotCentered: Boolean = false,
) {
    val reducedMotion = LocalReducedMotion.current
    if (!route.showTopBar) return
    val centeredMascot = centralMascotCentered && route.action == AppRouteAction.CHAT
    var accountMenuOpen by remember(route.action, mascotVisibleSessionId, mascotLiveConversationVisible, mascotSurfaceVisible) {
        mutableStateOf(false)
    }
    // Fresh terminal events require this mounted header to have observed the same generation running.
    val terminalTracker = remember(mascotVisibleSessionId) { JarvysHeaderFailureTracker() }
    var failedRunId by remember(mascotVisibleSessionId) { mutableStateOf<String?>(null) }
    var interruptedRunId by remember(mascotVisibleSessionId) { mutableStateOf<String?>(null) }
    val liveVisible = mascotLiveConversationVisible && mascotSurfaceVisible
        && agentSnapshot.sessionId == mascotVisibleSessionId
    LaunchedEffect(agentSnapshot.runId, agentSnapshot.liveRunIdentity, agentSnapshot.running,
        agentSnapshot.outcome, liveVisible) {
        failedRunId = terminalTracker.observe(agentSnapshot.runId, agentSnapshot.running,
            agentSnapshot.outcome, liveVisible, agentSnapshot.liveRunIdentity)
        interruptedRunId = terminalTracker.interruptedRunId
    }
    val liveActivity = if (liveVisible && agentSnapshot.running && !agentSnapshot.compacting
        && agentSnapshot.interactiveOwnerSessionId == mascotVisibleSessionId) when {
        mainMascotMode == 5 && agentSnapshot.liveUserWaitId != null -> JarvysHeaderActivity.WAITING
        mainMascotMode == 2 && agentSnapshot.liveMascotTool?.eligibleToAnimate == true -> JarvysHeaderActivity.WORKING
        mainMascotMode == 1 && agentSnapshot.liveModelWaitId != null -> JarvysHeaderActivity.THINKING
        else -> null
    } else null
    val liveActivityKey = when (liveActivity) {
        JarvysHeaderActivity.WAITING -> agentSnapshot.liveUserWaitId
        JarvysHeaderActivity.WORKING -> agentSnapshot.liveMascotTool?.executionId
        JarvysHeaderActivity.THINKING -> agentSnapshot.liveModelWaitId?.toString()
        null -> null
    }?.let { "$mascotVisibleSessionId:$it" }
    val accountMenu: @Composable () -> Unit = {
        JarvysMascotAccountMenu(
            mode = mainMascotMode,
            visibleSessionId = mascotVisibleSessionId,
            liveConversationVisible = mascotLiveConversationVisible,
            surfaceVisible = mascotSurfaceVisible,
            mascotSize = if (centeredMascot) 84.dp else 30.dp,
            originalHeaderAnimation = true,
            failedRunId = failedRunId,
            interruptedRunId = interruptedRunId,
            liveActivity = liveActivity,
            liveActivityKey = liveActivityKey,
            headerAnimationRequest = agentSnapshot.headerMascotAnimationRequest,
            levitate = centeredMascot,
            controlledMenuOpen = accountMenuOpen,
            onMenuOpenChange = { accountMenuOpen = it },
        ) { closeMenu ->
            DropdownMenuItem(
                text = { Text(stringResource(if (compacting) R.string.compaction_running else R.string.compaction_menu)) },
                leadingIcon = { Icon(LucideIcons.Layers2, contentDescription = null,
                    modifier = Modifier.testTag("topbar-compaction-action-icon")) },
                enabled = canCompact,
                modifier = Modifier.testTag("topbar-compaction-action"),
                onClick = { closeMenu(); onCompact() },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.reflection_menu)) },
                leadingIcon = { Icon(LucideIcons.Brain, contentDescription = null,
                    modifier = Modifier.testTag("topbar-reflection-action-icon")) },
                enabled = canReflect,
                modifier = Modifier.testTag("topbar-reflection-action"),
                onClick = { closeMenu(); onReflect() })
        }
    }
    val navigationIcon: @Composable () -> Unit = {
        if (route.isRoot) IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
            Icon(LucideIcons.Menu, contentDescription = stringResource(R.string.drawer_open), modifier = Modifier.size(20.dp))
        } else com.jarvys.agent.AppRouteBackButton(onFallback = onBack)
    }
    if (centeredMascot) {
        CenterAlignedTopAppBar(
            // Reserve layout height; scaling the image alone would clip or overlap the chat.
            expandedHeight = 96.dp,
            title = { Box(Modifier.testTag("jarvys-topbar-title")) { accountMenu() } },
            navigationIcon = navigationIcon,
            actions = {
                IconButton(onClick = { accountMenuOpen = true }, modifier = Modifier.size(48.dp)
                    .testTag("jarvys-topbar-more-options")) {
                    Icon(LucideIcons.EllipsisVertical, contentDescription = stringResource(R.string.topbar_more_options),
                        modifier = Modifier.size(21.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent),
            modifier = Modifier.testTag("jarvys-central-topbar"),
        )
        return
    }
    JarvysTopAppBar(
        title = {
            AnimatedContent(targetState = route.title, modifier = Modifier.fillMaxWidth(), transitionSpec = {
                if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(JarvysMotion.placeChange()) togetherWith fadeOut(JarvysMotion.placeChange())
            }, label = "place-title") { title ->
                Text(title, maxLines = 1, softWrap = false, fontSize = if (route.action == AppRouteAction.CHAT) JarvysUiTokens.ChatToolbarTitleSize else JarvysUiTokens.ToolbarTitleSize,
                    fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = navigationIcon,
        actions = {
            when (route.action) {
                AppRouteAction.CHAT -> {
                    accountMenu()
                    IconButton(onClick = { accountMenuOpen = true }, modifier = Modifier.size(48.dp)
                        .testTag("jarvys-topbar-more-options")) {
                        Icon(LucideIcons.EllipsisVertical, contentDescription = stringResource(R.string.topbar_more_options),
                            modifier = Modifier.size(21.dp))
                    }
                }
                AppRouteAction.MCP_ADD -> IconButton(onClick = onAddServer, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.Plus, contentDescription = stringResource(R.string.drawer_add_mcp_server),
                        modifier = Modifier.size(22.dp))
                }
                AppRouteAction.SKILL_IMPORT -> IconButton(onClick = onImportSkill, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.Plus, contentDescription = stringResource(R.string.drawer_import_skill),
                        modifier = Modifier.size(22.dp))
                }
                AppRouteAction.HOME_FALLBACK -> IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.House, contentDescription = stringResource(R.string.drawer_chat_fallback),
                        modifier = Modifier.size(20.dp))
                }
                AppRouteAction.NONE -> Unit
            }
        },
        transparent = true,
    )
}
