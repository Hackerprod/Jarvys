package com.jarvys.agent.ui.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    content: @Composable (PaddingValues) -> Unit,
) {
    val chatRoute = route.action == AppRouteAction.CHAT
    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        drawerContent = drawerContent,
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
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
                )
            },
            bottomBar = { if (!chatRoute) bottomBar() },
            content = { padding ->
                if (!chatRoute) {
                    content(padding)
                } else {
                    var composerInset by remember { mutableStateOf(0.dp) }
                    val density = LocalDensity.current
                    val layoutDirection = LocalLayoutDirection.current
                    val bodyPadding = PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = 0.dp,
                    )
                    CompositionLocalProvider(LocalChatComposerInset provides composerInset) {
                    Box(Modifier.fillMaxSize()) {
                        content(bodyPadding)
                        Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { measured ->
                                    val measuredDp = with(density) { measured.height.toDp() }
                                    if (composerInset != measuredDp) composerInset = measuredDp
                                }
                                .testTag(CHAT_COMPOSER_OVERLAY_TAG)) {
                                Box(Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth()
                                    .height(COMPOSER_FADE_HEIGHT).offset(y = -COMPOSER_FADE_HEIGHT)
                                    .background(Brush.verticalGradient(listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background.copy(alpha = COMPOSER_FADE_OPACITY),
                                    ))))
                                Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Final)
                                                event.changes.forEach { if (it.pressed) it.consume() }
                                            } while (event.changes.any { it.pressed })
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
private val COMPOSER_FADE_HEIGHT = 24.dp
private const val COMPOSER_FADE_OPACITY = 0.96f
