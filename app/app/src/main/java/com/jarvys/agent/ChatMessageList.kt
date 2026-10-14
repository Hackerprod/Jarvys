package com.jarvys.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Measured full overlay height of the floating chat composer, including system-bar padding. */
val LocalChatComposerInset = compositionLocalOf { 0.dp }

/** Header overlays history while this inset keeps the oldest item below its controls. */
val LocalChatHeaderInset = compositionLocalOf { 0.dp }

/** Reverse-layout conversation timeline: item zero is the stable bottom/tail anchor. */
@Composable
internal fun <T> ChatMessageList(
    conversationKey: Any,
    messages: List<T>,
    isRunning: Boolean,
    messageKey: (T) -> Any,
    isUserMessage: (T) -> Boolean,
    hasOlder: Boolean = false,
    isLoadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    modifier: Modifier = Modifier,
    composerInset: Dp = LocalChatComposerInset.current,
    headerInset: Dp = LocalChatHeaderInset.current,
    listState: LazyListState = rememberLazyListState(),
    targetMessage: ((T) -> Boolean)? = null,
    itemContent: @Composable (T) -> Unit,
    newestItems: LazyListScope.() -> Unit = {},
    messageUpdateKey: (T) -> Any = messageKey,
    fixedItems: LazyListScope.() -> Unit = {},
) {
    var followingEnd by remember(conversationKey) { mutableStateOf(true) }
    var followingBeforeDataChange by remember(conversationKey) { mutableStateOf(true) }
    var openingByReverseLayout by remember(conversationKey) { mutableStateOf(true) }
    var resolvedTargetKey by remember(conversationKey) { mutableStateOf<Any?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val currentLoadOlder by rememberUpdatedState(onLoadOlder)
    val olderEdgeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 240.dp.roundToPx() }
    val newestKey = messages.lastOrNull()?.let(messageUpdateKey)
    val newestIsUserMessage = messages.lastOrNull()?.let(isUserMessage) == true
    val targetIndex = targetMessage?.let(messages::indexOfFirst) ?: -1
    val targetKey = messages.getOrNull(targetIndex)?.let(messageKey)

    LaunchedEffect(conversationKey) {
        val opened = ChatScrollPolicy.decide(ChatScrollTrigger.CONVERSATION_OPENED, false, true)
        followingEnd = opened.followEnd
        followingBeforeDataChange = opened.followEnd
    }
    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollBackward }.distinctUntilChanged().collect { atEnd ->
            val decision = ChatScrollPolicy.decide(ChatScrollTrigger.USER_SCROLLED, followingEnd, atEnd)
            followingEnd = decision.followEnd
            followingBeforeDataChange = decision.followEnd
        }
    }
    LaunchedEffect(conversationKey, newestKey, isRunning) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (openingByReverseLayout) {
            openingByReverseLayout = false
            return@LaunchedEffect
        }
        val trigger = if (newestIsUserMessage) ChatScrollTrigger.USER_MESSAGE_SENT else ChatScrollTrigger.STREAM_UPDATE
        val decision = ChatScrollPolicy.decide(trigger, followingBeforeDataChange, !listState.canScrollBackward)
        followingBeforeDataChange = decision.followEnd
        followingEnd = decision.followEnd
        if (decision.shouldScroll) {
            if (decision.animate) listState.animateScrollToItem(0) else listState.scrollToItem(0)
        }
    }
    LaunchedEffect(conversationKey, targetKey) {
        if (targetIndex >= 0 && targetKey != null && resolvedTargetKey != targetKey) {
            listState.scrollToItem(messages.lastIndex - targetIndex)
            resolvedTargetKey = targetKey
        }
    }
    LaunchedEffect(conversationKey, hasOlder, isLoadingOlder) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val oldestVisible = layout.visibleItemsInfo.maxByOrNull { it.index }
            if (oldestVisible == null || layout.totalItemsCount == 0) false
            else {
                val remainingSlots = layout.totalItemsCount - oldestVisible.index - 1
                val averageSlotHeight = layout.visibleItemsInfo.map { it.size }.average().toInt().coerceAtLeast(1)
                val estimatedDistance = remainingSlots * averageSlotHeight
                estimatedDistance <= olderEdgeThresholdPx
                    || (remainingSlots == 0 && abs(oldestVisible.offset - layout.viewportStartOffset) <= olderEdgeThresholdPx)
            }
        }.distinctUntilChanged().collect { nearOlderEdge ->
            if (!nearOlderEdge || !hasOlder || isLoadingOlder) return@collect
            delay(140)
            val layout = listState.layoutInfo
            val oldestVisible = layout.visibleItemsInfo.maxByOrNull { it.index }
            val remainingSlots = oldestVisible?.let { layout.totalItemsCount - it.index - 1 } ?: Int.MAX_VALUE
            val averageSlotHeight = layout.visibleItemsInfo.map { it.size }.average().toInt().coerceAtLeast(1)
            val stillNear = oldestVisible != null && remainingSlots * averageSlotHeight <= olderEdgeThresholdPx
            if (stillNear && hasOlder && !isLoadingOlder) currentLoadOlder()
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().testTag("chat-message-list"),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp + headerInset, end = 18.dp,
                bottom = 18.dp + composerInset),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        ) {
            newestItems()
            items(messages.asReversed(), key = messageKey) { message -> itemContent(message) }
            fixedItems()
        }
        if (!followingEnd && listState.canScrollBackward) SmallFloatingActionButton(
            onClick = {
                val decision = ChatScrollPolicy.decide(ChatScrollTrigger.JUMP_TO_END, followingEnd,
                    !listState.canScrollBackward)
                followingEnd = decision.followEnd
                followingBeforeDataChange = decision.followEnd
                coroutineScope.launch { listState.animateScrollToItem(0) }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerInset + 12.dp)
                .size(48.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .testTag("chat-jump-to-end"),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(LucideIcons.ChevronDown, contentDescription = stringResource(R.string.chat_scroll_to_latest))
        }
    }
}
