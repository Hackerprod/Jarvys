package com.jarvys.agent.ui.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.layout.onSizeChanged
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.LocalChatComposerInset
import com.jarvys.agent.LocalChatHeaderInset

@Composable
fun JarvysShellFrame(
    drawerState: DrawerState,
    drawerContent: @Composable () -> Unit,
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
    bottomBar: @Composable () -> Unit,
    drawerGesturesEnabled: Boolean = true,
    mainMascotMode: Int? = null,
    mascotVisibleSessionId: String? = null,
    mascotLiveConversationVisible: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val chatRoute = route.action == AppRouteAction.CHAT
    val background = MaterialTheme.colorScheme.background
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerGesturesEnabled,
        scrimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        drawerContent = drawerContent,
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                // Recovered v28: draw past the toolbar without reserving extra layout height.
                Box(if (chatRoute && route.showTopBar) Modifier.testTag(CHAT_HEADER_FADE_TAG)
                    .drawWithCache {
                        val fadeHeight = size.height + CHAT_HEADER_FADE_EXTENSION.toPx()
                        val toolbarEnd = size.height / fadeHeight
                        val brush = Brush.verticalGradient(
                            0f to background.copy(alpha = 0.99f),
                            toolbarEnd * 0.65f to background.copy(alpha = 0.96f),
                            toolbarEnd to background.copy(alpha = 0.74f),
                            1f to background.copy(alpha = 0f),
                            endY = fadeHeight,
                        )
                        onDrawBehind { drawRect(brush, size = Size(size.width, fadeHeight)) }
                    } else Modifier) {
                JarvysRouteTopBar(
                    route = route,
                    agentSnapshot = agentSnapshot,
                    onBack = onBack,
                    onOpenDrawer = onOpenDrawer,
                    onOpenSettings = onOpenSettings,
                    onNewChat = onNewChat,
                    onAddServer = onAddServer,
                    onImportSkill = onImportSkill,
                    chatWithoutMemory = chatWithoutMemory,
                    canCompact = canCompact,
                    compacting = compacting,
                    canReflect = canReflect,
                    onToggleMemory = onToggleMemory,
                    onCompact = onCompact,
                    onReflect = onReflect,
                    mainMascotMode = mainMascotMode,
                    mascotVisibleSessionId = mascotVisibleSessionId,
                    mascotLiveConversationVisible = mascotLiveConversationVisible,
                    mascotSurfaceVisible = drawerState.isClosed && drawerState.targetValue == DrawerValue.Closed
                        && !drawerState.isAnimationRunning,
                )
                }
            },
            bottomBar = { if (!chatRoute) bottomBar() },
            content = { padding ->
                var composerInset by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current
                val layoutDirection = LocalLayoutDirection.current
                val bodyPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = 0.dp,
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = 0.dp,
                )
                // Keep the NavHost at one stable composition position across chat/settings routes.
                // Moving it between branches discarded its saveable state holder and chat scroll.
                CompositionLocalProvider(
                    LocalChatComposerInset provides if (chatRoute) composerInset else 0.dp,
                    LocalChatHeaderInset provides if (chatRoute) padding.calculateTopPadding() else 0.dp,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        content(if (chatRoute) bodyPadding else padding)
                        if (chatRoute) {
                            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { measured ->
                                    val measuredDp = with(density) { measured.height.toDp() }
                                    if (composerInset != measuredDp) composerInset = measuredDp
                                }
                                .testTag(CHAT_COMPOSER_OVERLAY_TAG)) {
                                Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                                    .pointerInput(Unit) {
                                        // A hit-test boundary protects messages underneath. Do not consume:
                                        // even a 1px move consumed at Final cancels child buttons and text focus.
                                        awaitPointerEventScope {
                                            while (true) awaitPointerEvent()
                                        }
                                    }) {
                                    bottomBar()
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

internal const val CHAT_COMPOSER_OVERLAY_TAG = "chat-composer-overlay"
internal const val CHAT_HEADER_FADE_TAG = "chat-header-fade"
internal val CHAT_HEADER_FADE_EXTENSION = 28.dp
