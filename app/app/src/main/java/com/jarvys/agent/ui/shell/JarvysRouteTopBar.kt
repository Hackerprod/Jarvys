package com.jarvys.agent.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.jarvys.agent.ui.motion.LocalReducedMotion

@Composable
fun JarvysRouteTopBar(
    route: AppRouteMeta,
    agentSnapshot: AgentRunUiSnapshot,
    onBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onAddServer: () -> Unit,
    onImportSkill: () -> Unit,
    chatWithoutMemory: Boolean,
    canCompact: Boolean,
    compacting: Boolean,
    canReflect: Boolean,
    onToggleMemory: () -> Unit,
    onCompact: () -> Unit,
    onReflect: () -> Unit,
    mainMascotMode: Int? = null,
    mascotVisibleSessionId: String? = null,
    mascotLiveConversationVisible: Boolean = false,
    mascotSurfaceVisible: Boolean = true,
) {
    val reducedMotion = LocalReducedMotion.current
    if (!route.showTopBar) return
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
        navigationIcon = {
            if (route.isRoot) IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
                Icon(LucideIcons.Menu, contentDescription = stringResource(R.string.drawer_open), modifier = Modifier.size(20.dp))
            } else com.jarvys.agent.AppRouteBackButton(onFallback = onBack)
        },
        actions = {
            when (route.action) {
                AppRouteAction.CHAT -> {
                    JarvysMascotAccountMenu(
                        mode = mainMascotMode,
                        visibleSessionId = mascotVisibleSessionId,
                        liveConversationVisible = mascotLiveConversationVisible,
                        surfaceVisible = mascotSurfaceVisible,
                    ) { closeMenu ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.drawer_settings)) },
                            leadingIcon = { Icon(LucideIcons.Settings, contentDescription = null) },
                            onClick = { closeMenu(); onOpenSettings() })
                        DropdownMenuItem(
                            text = { Text(stringResource(if (chatWithoutMemory) R.string.memory_chat_without_off else R.string.memory_chat_without)) },
                            leadingIcon = { Icon(LucideIcons.Eye, contentDescription = null) },
                            onClick = { closeMenu(); onToggleMemory() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (compacting) R.string.compaction_running else R.string.compaction_menu)) },
                            leadingIcon = { Icon(LucideIcons.Layers2, contentDescription = null) },
                            enabled = canCompact,
                            onClick = { closeMenu(); onCompact() },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.reflection_menu)) },
                            leadingIcon = { Icon(LucideIcons.Brain, contentDescription = null) },
                            enabled = canReflect,
                            onClick = { closeMenu(); onReflect() })
                    }
                    IconButton(onClick = onNewChat, modifier = Modifier.size(48.dp)) {
                        Icon(LucideIcons.MessageCirclePlus, contentDescription = stringResource(R.string.drawer_new_chat),
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
