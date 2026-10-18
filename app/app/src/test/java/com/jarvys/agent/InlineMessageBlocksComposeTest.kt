package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ui.chat.assistantReplyIsGenerating
import com.jarvys.agent.ui.chat.ConversationTimeline
import com.jarvys.agent.ui.chat.ChatMessageArrivalPolicy
import com.jarvys.agent.ui.chat.ConversationTimelineItem
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.JarvysOwnTheme
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineMessageBlocksComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun staticAssistantMarkdownReentryDoesNotShowLoadingOrChangeRowHeight() {
        val svg = """```svg
            |<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><title>Scroll re-entry diagram</title><circle cx="25" cy="25" r="15" fill="currentColor"/></svg>
            |```""".trimMargin()
        val chart = """```chart
            |{"type":"bar","title":"Scroll re-entry totals","labels":["Jan","Feb","Mar"],"series":[{"name":"Total","values":[3,5,4]}]}
            |```""".trimMargin()
        val longMarkdown = buildString {
            append("# Long answer\n\n")
            repeat(36) { index ->
                append("Paragraph ${index + 1}: This is a detailed explanation with enough repeated text to occupy several lines in the conversation viewport and represent a long persisted assistant response.\n\n")
            }
            append("## Summary list\n\n- First point with supporting detail\n- Second point with supporting detail\n- Third point with supporting detail\n\n")
            append("The diagram and chart follow the explanation.\n\n")
            append(svg)
            append("\n\n")
            append(chart)
        }
        val longMessageId = 100
        // The newest message is LazyColumn index zero in ChatMessageList's reversed timeline.
        val messages = (0 until 24).map { ScrollReentryMessage(it, "Earlier conversation message ${it + 1}.") } +
            ScrollReentryMessage(longMessageId, longMarkdown)
        val targetLazyIndex = 0
        val awayLazyIndex = messages.size - 1
        val requestedScrollOffsetPx = 180
        val listState = androidx.compose.foundation.lazy.LazyListState()
        val renderedMarkdown = mutableStateOf<String?>(null)
        val markdownRenderCallbacks = mutableIntStateOf(0)
        val loadingCompositions = mutableIntStateOf(0)
        val rowHeight = mutableIntStateOf(0)
        val reentryHeightSamples = mutableListOf<Int>()
        val captureReentryHeightSamples = mutableStateOf(false)
        val longRowMounts = mutableIntStateOf(0)
        val longRowDisposals = mutableIntStateOf(0)
        val sourceParsesByLanguage = mutableMapOf<String, Int>()
        val blockMountsByLanguage = mutableMapOf<String, Int>()
        val observer = object : AssistantMarkdownRenderObserver {
            override fun onLoadingComposed() { loadingCompositions.intValue++ }
            override fun onMarkdownContentRendered(content: String) {
                renderedMarkdown.value = content
                markdownRenderCallbacks.intValue++
            }
            override fun onPlaceholderComposed(language: String) = Unit
            override fun onStreamingCodeFallbackComposed(language: String) = Unit
            override fun onBlockSourceParsed(language: String) {
                sourceParsesByLanguage[language] = (sourceParsesByLanguage[language] ?: 0) + 1
            }
            override fun onBlockMounted(language: String) {
                blockMountsByLanguage[language] = (blockMountsByLanguage[language] ?: 0) + 1
            }
        }

        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides false,
                LocalAssistantMarkdownRenderObserver provides observer,
            ) {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    ChatMessageList(
                        conversationKey = "static-scroll-reentry",
                        messages = messages,
                        isRunning = false,
                        messageKey = { it.id },
                        isUserMessage = { false },
                        listState = listState,
                        modifier = Modifier.width(360.dp).height(640.dp),
                        itemContent = { message ->
                            if (message.id == longMessageId) {
                                DisposableEffect(message.id) {
                                    longRowMounts.intValue++
                                    onDispose { longRowDisposals.intValue++ }
                                }
                                Column(Modifier.fillMaxWidth()
                                    .testTag("scroll-reentry-long-row")
                                    .onSizeChanged {
                                        rowHeight.intValue = it.height
                                        if (captureReentryHeightSamples.value) reentryHeightSamples += it.height
                                    }) {
                                    AssistantMarkdown(message.text, onOpenSkillFile = {}, streaming = false)
                                }
                            } else {
                                androidx.compose.material3.Text(message.text, Modifier.height(64.dp).fillMaxWidth())
                            }
                        },
                    )
                }
            }
        }

        compose.runOnIdle { listState.requestScrollToItem(targetLazyIndex, requestedScrollOffsetPx) }
        compose.waitUntil(10_000L) { renderedMarkdown.value == longMarkdown && rowHeight.intValue > 0 }
        compose.waitForIdle()
        assertEquals("the requested long reply rendered", longMarkdown, renderedMarkdown.value)
        assertTrue("the requested row was composed", longRowMounts.intValue > 0)
        assertTrue("the reply produced a render callback", markdownRenderCallbacks.intValue > 0)
        assertEquals("one SVG visual is mounted", 1, blockMountsByLanguage["svg"])
        assertEquals("one chart visual is mounted", 1, blockMountsByLanguage["chart"])
        repeat(3) {
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
        }
        val settledHeightBeforeScroll = rowHeight.intValue
        val rowMountsBeforeScroll = longRowMounts.intValue
        val rowDisposalsBeforeScroll = longRowDisposals.intValue
        val scrollOnInitialEntry = "${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}"
        assertEquals("the initial entry uses the requested LazyColumn anchor", targetLazyIndex,
            listState.firstVisibleItemIndex)
        assertEquals("the initial entry uses the requested scroll offset", requestedScrollOffsetPx,
            listState.firstVisibleItemScrollOffset)
        assertTrue("the long answer starts composed", longRowMounts.intValue > 0)
        assertTrue("the long answer has a measured height", settledHeightBeforeScroll > 0)

        compose.runOnIdle { listState.requestScrollToItem(awayLazyIndex) }
        compose.waitForIdle()
        assertTrue("scrolling into history removes the long row from composition",
            longRowDisposals.intValue > rowDisposalsBeforeScroll)
        val loadingWhenOut = loadingCompositions.intValue
        val renderCallbacksBeforeReturn = markdownRenderCallbacks.intValue

        reentryHeightSamples.clear()
        compose.runOnIdle { captureReentryHeightSamples.value = true }
        compose.runOnIdle { listState.requestScrollToItem(targetLazyIndex, requestedScrollOffsetPx) }
        compose.waitForIdle()
        assertTrue("scrolling back mounts the long row again", longRowMounts.intValue > rowMountsBeforeScroll)
        compose.waitUntil(10_000L) {
            markdownRenderCallbacks.intValue > renderCallbacksBeforeReturn &&
                renderedMarkdown.value == longMarkdown && rowHeight.intValue > 0
        }
        val reentryScrollSamples = mutableListOf<String>()
        repeat(3) {
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
            reentryHeightSamples += rowHeight.intValue
            reentryScrollSamples += "${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}"
        }
        compose.runOnIdle { captureReentryHeightSamples.value = false }
        val settledHeightAfterScroll = rowHeight.intValue
        val loadingAfterScroll = loadingCompositions.intValue
        val scrollAfterSettle = "${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}"
        val reentryHeightValues = reentryHeightSamples.distinct()

        assertEquals("re-entering a completed reply does not compose the empty loading slot",
            loadingWhenOut, loadingAfterScroll)
        assertEquals("the same long answer height is restored", settledHeightBeforeScroll, settledHeightAfterScroll)
        assertTrue("re-entry keeps the previous row height in every measured sample: $reentryHeightValues",
            reentryHeightSamples.isNotEmpty() && reentryHeightSamples.all { it == settledHeightBeforeScroll })
        assertEquals("re-entry preserves the original item anchor", scrollOnInitialEntry.substringBefore('/'),
            scrollAfterSettle.substringBefore('/'))
        assertEquals("re-entry preserves the original scroll offset", scrollOnInitialEntry.substringAfter('/'),
            scrollAfterSettle.substringAfter('/'))
        assertTrue("all sampled frames preserve the original anchor and offset: $reentryScrollSamples",
            reentryScrollSamples.all { it == scrollOnInitialEntry })
    }

    @Test fun cachedMarkdownTextChangeAfterReentryKeepsLastRenderedTree() {
        val original = "# Original response\n\nThis paragraph stays visible while a replacement is parsed."
        val translated = "# Respuesta traducida\n\nEste párrafo reemplaza el contenido después de volver a la conversación."
        val cache = AssistantMarkdownSuccessCache()
        val visible = mutableStateOf(true)
        val markdown = mutableStateOf(original)
        val renderedMarkdown = mutableStateOf<String?>(null)
        val loadingCompositions = mutableIntStateOf(0)
        val height = mutableIntStateOf(0)
        val captureHeights = mutableStateOf(false)
        val heightSamples = mutableListOf<Int>()
        val observer = object : AssistantMarkdownRenderObserver {
            override fun onLoadingComposed() { loadingCompositions.intValue++ }
            override fun onMarkdownContentRendered(content: String) { renderedMarkdown.value = content }
            override fun onPlaceholderComposed(language: String) = Unit
            override fun onStreamingCodeFallbackComposed(language: String) = Unit
            override fun onBlockSourceParsed(language: String) = Unit
            override fun onBlockMounted(language: String) = Unit
        }

        compose.setContent {
            CompositionLocalProvider(
                LocalAssistantMarkdownSuccessCache provides cache,
                LocalAssistantMarkdownRenderObserver provides observer,
            ) {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    if (visible.value) {
                        Column(Modifier.fillMaxWidth().onSizeChanged {
                            height.intValue = it.height
                            if (captureHeights.value) heightSamples += it.height
                        }) {
                            AssistantMarkdown(markdown.value, onOpenSkillFile = {})
                        }
                    }
                }
            }
        }

        compose.waitUntil(10_000L) { renderedMarkdown.value == original && height.intValue > 0 }
        compose.waitForIdle()
        val loadingBeforeReentry = loadingCompositions.intValue
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals("a cached re-entry does not show the loading slot", loadingBeforeReentry,
            loadingCompositions.intValue)

        heightSamples.clear()
        compose.runOnIdle {
            captureHeights.value = true
            markdown.value = translated
        }
        compose.waitForIdle()
        val translatedRendered = runCatching {
            compose.waitUntil(10_000L) { renderedMarkdown.value == translated }
        }.isSuccess
        assertTrue(
            "translated Markdown did not render: actual=${renderedMarkdown.value?.take(80)}, " +
                "height=${height.intValue}, loading=${loadingCompositions.intValue}",
            translatedRendered,
        )
        assertTrue("the previous or translated tree remains visible", height.intValue > 0)
        compose.waitForIdle()
        heightSamples += height.intValue
        compose.runOnIdle { captureHeights.value = false }

        assertEquals("changing cached text keeps the loading slot hidden", loadingBeforeReentry,
            loadingCompositions.intValue)
        assertTrue("the row never collapses to zero while replacement Markdown parses: $heightSamples",
            heightSamples.isNotEmpty() && heightSamples.none { it == 0 })
        assertEquals("the translated text eventually renders", translated, renderedMarkdown.value)
    }

    @Test fun cachedSameTextRecompositionKeepsInlineBlockMounted() {
        val markdown = """```svg
            |<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><title>Stable diagram</title><circle cx="25" cy="25" r="15" fill="currentColor"/></svg>
            |```""".trimMargin()
        val cache = AssistantMarkdownSuccessCache()
        val renderedMarkdown = mutableStateOf<String?>(null)
        val blockMountsByLanguage = mutableMapOf<String, Int>()
        val observer = object : AssistantMarkdownRenderObserver {
            override fun onLoadingComposed() = Unit
            override fun onMarkdownContentRendered(content: String) {
                if (renderedMarkdown.value != content) renderedMarkdown.value = content
            }
            override fun onPlaceholderComposed(language: String) = Unit
            override fun onStreamingCodeFallbackComposed(language: String) = Unit
            override fun onBlockSourceParsed(language: String) = Unit
            override fun onBlockMounted(language: String) {
                blockMountsByLanguage[language] = (blockMountsByLanguage[language] ?: 0) + 1
            }
        }

        compose.setContent {
            CompositionLocalProvider(
                LocalAssistantMarkdownSuccessCache provides cache,
                LocalAssistantMarkdownRenderObserver provides observer,
            ) {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    Column(Modifier.testTag(if (renderedMarkdown.value != null) {
                        "markdown-rendered"
                    } else {
                        "markdown-pending"
                    })) {
                        AssistantMarkdown(
                            markdown,
                            onOpenSkillFile = {},
                            textColor = if (renderedMarkdown.value == null) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        compose.waitUntil(10_000L) { renderedMarkdown.value == markdown }
        compose.waitForIdle()
        assertEquals("same-text cache promotion does not remount the SVG subtree", 1,
            blockMountsByLanguage["svg"])
    }

    @Test fun timelineKeepsOpenSvgPlaceholderAfterUserRowRefreshAndSteering() {
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, permissionGranted = { false })
        val markdown = "```svg\n<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50'><title>Live diagram</title>\n"
        val optimisticUser = AgentRunUiEvent(1L, "user", text = "Draw a diagram", messageId = "optimistic-user")
        val stream = AgentRunUiEvent(-1L, "assistant_stream", stage = "STREAMING", text = markdown,
            messageId = "live-run", timelineKey = -1L, streamUserEventId = optimisticUser.id)
        val events = mutableStateOf(listOf(optimisticUser, stream))
        val placeholder = compose.activity.getString(R.string.inline_block_rendering, "svg")

        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                ConversationTimeline(
                    conversationKey = "stream-user-row-refresh",
                    events = events.value,
                    isRunning = true,
                    connectorRegistry = connectors,
                    onOpenPreview = {},
                    onOpenSkillFile = {},
                    chatWithoutMemory = false,
                    benchmarkListState = rememberLazyListState(),
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        compose.onNodeWithText(placeholder).assertIsDisplayed()

        val persistedUser = optimisticUser.copy(id = 2L, messageId = "durable-user")
        compose.runOnIdle { events.value = listOf(persistedUser, stream) }
        compose.waitForIdle()
        compose.onNodeWithText(placeholder).assertIsDisplayed()
        compose.onAllNodesWithText("Copy").assertCountEquals(0)
        compose.onAllNodesWithText("<svg", substring = true).assertCountEquals(0)

        val steering = AgentRunUiEvent(3L, "user", text = "Keep going", messageId = "steering-user")
        compose.runOnIdle { events.value = listOf(persistedUser, stream, steering) }
        compose.waitForIdle()
        compose.onNodeWithText(placeholder).assertIsDisplayed()
        compose.onAllNodesWithText("Copy").assertCountEquals(0)
        compose.onAllNodesWithText("<svg", substring = true).assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun assistantMarkdownRendersInlineVisualsWithoutCopyButtonsAndKeepsCopySourceAccessible() {
        val svg = """```svg
            |<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><title>Circle diagram</title><circle cx="25" cy="25" r="15" fill="currentColor"/></svg>
            |```""".trimMargin()
        val chart = """```chart
            |{"type":"bar","title":"Monthly numbers","labels":["Jan","Feb"],"series":[{"name":"Total","values":[3,5]}]}
            |```""".trimMargin()
        val invalid = """```chart
            |{"type":"line","title":"Broken series","labels":["Jan","Feb"],"series":[{"values":[3]}]}
            |```""".trimMargin()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                AssistantMarkdown("$svg\n\n$chart\n\n$invalid", onOpenSkillFile = {})
            }
        }
        compose.onNodeWithContentDescription("Circle diagram").assertIsDisplayed()
        compose.onNodeWithContentDescription("Monthly numbers. Total: Jan 3.0, Feb 5.0").assertIsDisplayed()
        compose.onAllNodesWithText("Copy").assertCountEquals(1)
        compose.onAllNodesWithText("\"type\":\"line\"", substring = true).assertCountEquals(1)

        compose.onNodeWithContentDescription("Circle diagram")
            .performCustomAccessibilityActionWithLabel(compose.activity.getString(R.string.inline_block_copy_source))
        val clipboard = compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("<circle"))
    }

    @Test fun assistantStreamFenceUpdatesUsePlaceholderThenAnimateWithoutRemountingFollowingContent() {
        compose.mainClock.autoAdvance = false
        val session = "inline-stream-${System.nanoTime()}"
        val generation = System.nanoTime()
        val runId = "inline-run-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Draw a diagram")
        AgentRunUiState.bindGeneration(session, generation)
        AgentRunUiState.assistantTextStreamStarted(session, generation, runId)
        AgentRunUiState.assistantTextStreamDelta(session, generation, runId,
            "Before the diagram.\n\n```svg\n" +
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50'><title>Streaming diagram</title>\n")
        val userId = AgentRunUiState.snapshotForSession(session).events.last { it.kind == "user" }.id
        val reply = mutableStateOf(AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant_stream" })
        assertTrue(reply.value.text.startsWith("Before the diagram."))
        val isRunning = mutableStateOf(true)
        val belowMounts = mutableIntStateOf(0)
        val replyMounts = mutableIntStateOf(0)
        val blockSlotMounts = mutableIntStateOf(0)
        val belowTop = mutableStateOf(Float.NaN)

        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    Column(Modifier.width(360.dp)) {
                        val event = reply.value
                        val generating = assistantReplyIsGenerating(event, isRunning.value, userId)
                        val fenceNode = streamingFenceNode(event.text)
                        key(ConversationTimelineItem.Event(event).key) {
                            DisposableEffect(Unit) {
                                replyMounts.intValue++
                                onDispose { }
                            }
                            DisposableEffect(Unit) {
                                blockSlotMounts.intValue++
                                onDispose { }
                            }
                            RenderAssistantCodeFence(event.text, fenceNode, MaterialTheme.typography.bodySmall, generating)
                        }
                        DisposableEffect(Unit) {
                            belowMounts.intValue++
                            onDispose { }
                        }
                        androidx.compose.material3.Text("Following content", Modifier.testTag("following-content")
                            .onGloballyPositioned { belowTop.value = it.boundsInRoot().top })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
        val openFenceEvent = reply.value
        assertEquals("assistant_stream", openFenceEvent.kind)
        assertEquals("STREAMING", openFenceEvent.stage)
        assertTrue(openFenceEvent.id < 0L)
        assertEquals(userId, openFenceEvent.streamUserEventId)
        assertTrue(assistantReplyIsGenerating(openFenceEvent, isRunning.value, userId))
        assertTrue("a user-row refresh or steering row does not close the active stream gate",
            assistantReplyIsGenerating(openFenceEvent, isRunning.value, userId + 1L))
        compose.onNodeWithText(compose.activity.getString(R.string.inline_block_rendering, "svg")).assertIsDisplayed()
        assertEquals(1, belowMounts.intValue)
        assertEquals(1, replyMounts.intValue)

        val initialTop = belowTop.value
        AgentRunUiState.assistantTextStreamDelta(session, generation, runId,
            "<circle cx='25' cy='25' r='15' fill='currentColor'/>")
        reply.value = AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant_stream" }
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
        AgentRunUiState.assistantTextStreamDelta(session, generation, runId, "</svg>\n```")
        reply.value = AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant_stream" }
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithContentDescription("Streaming diagram").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Streaming diagram").assertIsDisplayed()
        val blockId = compose.onNodeWithContentDescription("Streaming diagram").fetchSemanticsNode().id
        assertEquals("closing the SVG fence keeps its visual slot mounted once", 1, blockSlotMounts.intValue)
        val closeTransitionPositions = mutableListOf<Float>()
        repeat(8) {
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
            closeTransitionPositions += belowTop.value
        }
        compose.mainClock.advanceTimeBy(250L)
        compose.waitForIdle()
        val renderedTop = belowTop.value
        closeTransitionPositions += renderedTop
        assertTrue("the inline block grows into its final view", renderedTop > initialTop)
        assertTrue("the first movement is smaller than the completed transition",
            kotlin.math.abs(closeTransitionPositions.first() - initialTop) < kotlin.math.abs(renderedTop - initialTop))
        assertTrue("the following content moves monotonically during closure",
            closeTransitionPositions.zipWithNext().all { (before, after) -> after >= before })

        AgentRunUiState.assistantTextStreamDelta(session, generation, runId,
            "\n\nThe diagram summarizes the point.")
        reply.value = AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant_stream" }
        compose.waitForIdle()
        assertEquals("streaming a suffix keeps the visual slot composed", 1, blockSlotMounts.intValue)
        assertEquals("streaming a suffix does not recreate the rendered visual", blockId,
            compose.onNodeWithContentDescription("Streaming diagram").fetchSemanticsNode().id)

        val streamEvent = reply.value
        val transientKey = ConversationTimelineItem.Event(streamEvent).key
        AgentRunUiState.completeGeneration(session, generation, runId, "COMPLETED", streamEvent.text,
            "durable-answer-${System.nanoTime()}", 42L)
        val finalEvent = AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant" }
        assertTrue("the durable event keeps its own event ID", finalEvent.id != streamEvent.id)
        assertEquals(streamEvent.timelineKey, finalEvent.timelineKey)
        assertEquals(null, finalEvent.streamUserEventId)
        assertEquals(transientKey, ConversationTimelineItem.Event(finalEvent).key)
        assertEquals(ChatMessageArrivalPolicy.key(streamEvent), ChatMessageArrivalPolicy.key(finalEvent))
        assertFalse(assistantReplyIsGenerating(finalEvent, isRunning = false, latestUserEventId = userId))
        reply.value = finalEvent
        isRunning.value = false
        compose.waitForIdle()
        val promotionPositions = mutableListOf<Float>()
        repeat(8) {
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
            promotionPositions += belowTop.value
        }
        compose.mainClock.advanceTimeBy(250L)
        compose.waitForIdle()
        promotionPositions += belowTop.value
        assertTrue("the promoted answer preserves or smoothly expands its final layout", belowTop.value >= renderedTop)
        assertTrue("promotion does not oscillate content below",
            promotionPositions.zipWithNext().all { (before, after) -> after >= before })
        assertEquals("following content keeps its composition through deltas", 1, belowMounts.intValue)
        assertEquals("the answer row keeps its composition through final promotion", 1, replyMounts.intValue)
        assertEquals("the visual slot keeps its composition through completion", 1, blockSlotMounts.intValue)
        assertEquals("the closed visual keeps its composition through suffix deltas", blockId,
            compose.onNodeWithContentDescription("Streaming diagram").fetchSemanticsNode().id)
        AgentRunUiState.resetSession(session)
    }

    @Test fun assistantMarkdownStreamingRetainsRenderedContentAcrossDeltasAndPromotion() {
        val session = "markdown-stream-${System.nanoTime()}"
        val generation = System.nanoTime()
        val runId = "markdown-run-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Draw a chart and diagram")
        AgentRunUiState.bindGeneration(session, generation)
        AgentRunUiState.assistantTextStreamStarted(session, generation, runId)
        AgentRunUiState.assistantTextStreamDelta(session, generation, runId, "The summary is already visible.")

        val reply = mutableStateOf(AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant_stream" })
        val height = mutableIntStateOf(0)
        val heightSamples = mutableListOf<Int>()
        val rowBottomSamples = mutableListOf<Float>()
        val loadingCompositions = mutableIntStateOf(0)
        val placeholderCompositions = mutableIntStateOf(0)
        val streamingCodeFallbackCompositions = mutableIntStateOf(0)
        val placeholderByLanguage = mutableMapOf<String, Int>()
        val sourceParsesByLanguage = mutableMapOf<String, Int>()
        val blockMountsByLanguage = mutableMapOf<String, Int>()
        val rowMounts = mutableIntStateOf(0)
        val renderedMarkdown = mutableStateOf<String?>(null)
        val observer = object : AssistantMarkdownRenderObserver {
            override fun onLoadingComposed() { loadingCompositions.intValue++ }
            override fun onMarkdownContentRendered(content: String) { renderedMarkdown.value = content }
            override fun onPlaceholderComposed(language: String) {
                placeholderCompositions.intValue++
                placeholderByLanguage[language] = (placeholderByLanguage[language] ?: 0) + 1
            }
            override fun onStreamingCodeFallbackComposed(language: String) { streamingCodeFallbackCompositions.intValue++ }
            override fun onBlockSourceParsed(language: String) {
                sourceParsesByLanguage[language] = (sourceParsesByLanguage[language] ?: 0) + 1
            }
            override fun onBlockMounted(language: String) {
                blockMountsByLanguage[language] = (blockMountsByLanguage[language] ?: 0) + 1
            }
        }

        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides false,
                LocalReducedMotion provides false,
                LocalAssistantMarkdownRenderObserver provides observer,
            ) {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    val event = reply.value
                    ChatMessageList(
                        conversationKey = session,
                        messages = listOf(event),
                        isRunning = event.kind == "assistant_stream",
                        messageKey = { ConversationTimelineItem.Event(it).key },
                        isUserMessage = { false },
                        listState = rememberLazyListState(),
                        modifier = Modifier.width(360.dp).height(640.dp),
                        itemContent = { item ->
                            DisposableEffect(Unit) {
                                rowMounts.intValue++
                                onDispose { }
                            }
                            Column(Modifier.fillMaxWidth()
                                .onSizeChanged {
                                    height.intValue = it.height
                                    heightSamples += it.height
                                }
                                .onGloballyPositioned {
                                    val bottom = it.boundsInRoot().bottom
                                    rowBottomSamples += bottom
                                }) {
                                AssistantMarkdown(
                                    markdown = item.text,
                                    onOpenSkillFile = {},
                                    streaming = item.kind == "assistant_stream" && item.stage == "STREAMING",
                                )
                            }
                        },
                    )
                }
            }
        }

        fun settleContent(expected: String) {
            runCatching { compose.waitUntil(5_000L) { renderedMarkdown.value == expected } }
            compose.waitForIdle()
            assertEquals(
                "rendered Markdown did not catch up (expected=${expected.takeLast(80)}, " +
                    "actual=${renderedMarkdown.value?.takeLast(80)}, loading=${loadingCompositions.intValue}, " +
                    "rows=${rowMounts.intValue})",
                expected,
                renderedMarkdown.value,
            )
        }

        settleContent(reply.value.text)
        assertTrue("the pre-fence message has visible height", height.intValue > 0)
        val loadingAfterInitialParse = loadingCompositions.intValue
        val firstVisibleRowBottomSample = rowBottomSamples.size
        var noIntermediateShrink = true

        fun appendAndSettle(delta: String) {
            val priorHeight = height.intValue
            val sampleStart = heightSamples.size
            compose.runOnIdle {
                AgentRunUiState.assistantTextStreamDelta(session, generation, runId, delta)
                reply.value = AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant_stream" }
            }
            settleContent(reply.value.text)
            val intermediateHeights = heightSamples.drop(sampleStart)
            if (intermediateHeights.any { it < priorHeight } || height.intValue < priorHeight) noIntermediateShrink = false
        }

        appendAndSettle("\n\nQuarterly totals.\n\n```ch")
        appendAndSettle("art\n")
        appendAndSettle("""{"type":"bar","title":"Sales","labels":["Jan","Feb"],"series":[{"name":"Total","values":[3,""")
        appendAndSettle("5]}]")
        appendAndSettle("}\n```")
        appendAndSettle("\n\nTrend diagram.\n\n```svg\n<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50'><title>Trend diagram</title>\n")
        appendAndSettle("<circle cx='25' cy='25' r='15' fill='currentColor'/></svg>\n```")
        appendAndSettle("\n\nThe chart and diagram are complete.")

        val streamEvent = reply.value
        AgentRunUiState.completeGeneration(session, generation, runId, "COMPLETED", streamEvent.text,
            "durable-markdown-${System.nanoTime()}", 43L)
        val finalEvent = AgentRunUiState.snapshotForSession(session).events.single { it.kind == "assistant" }
        assertEquals(ConversationTimelineItem.Event(streamEvent).key, ConversationTimelineItem.Event(finalEvent).key)
        compose.runOnIdle { reply.value = finalEvent }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("Sales. Total: Jan 3.0, Feb 5.0")
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("Trend diagram").fetchSemanticsNodes().isNotEmpty())

        val visibleRowBottoms = rowBottomSamples.drop(firstVisibleRowBottomSample)
        val rowBottomReversals = visibleRowBottoms.zipWithNext().count { (before, after) -> after < before }

        assertTrue("no intermediate frame empties or shrinks already shown content", noIntermediateShrink)
        assertEquals("the row position never moves backward between frames", 0, rowBottomReversals)
        assertEquals("each open fence, including an unknown language prefix, uses the same cheap placeholder",
            setOf("", "chart", "svg"), placeholderByLanguage.keys)
        assertEquals("the loading slot does not return for stream deltas", loadingAfterInitialParse,
            loadingCompositions.intValue)
        assertEquals("one chart visual is mounted", 1, blockMountsByLanguage["chart"])
        assertEquals("one SVG visual is mounted", 1, blockMountsByLanguage["svg"])
        assertEquals("closed chart source is parsed once despite later deltas", 1, sourceParsesByLanguage["chart"])
        assertEquals("closed SVG source is parsed once despite promotion", 1, sourceParsesByLanguage["svg"])
        assertEquals("an incomplete language prefix never flashes as code", 0, streamingCodeFallbackCompositions.intValue)
        assertEquals("stream to persisted promotion keeps one keyed row mount", 1, rowMounts.intValue)
        assertEquals("promotion does not remount the chart", 1, blockMountsByLanguage["chart"])
        assertEquals("promotion does not remount the SVG", 1, blockMountsByLanguage["svg"])
        assertEquals("the final state keeps its full layout", streamEvent.text, renderedMarkdown.value)
        AgentRunUiState.resetSession(session)
    }

    @Test fun unfinishedSvgFallsBackToCopyableCodeWhenStreamingHasEnded() {
        val streamActive = mutableStateOf(true)
        val markdown = """```svg
            |<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50"><title>Waiting diagram</title>
            |<circle cx="25" cy="25" r="15"/>
            |""".trimMargin()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                AssistantMarkdown(markdown, onOpenSkillFile = {}, streaming = streamActive.value)
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.inline_block_rendering, "svg")).assertIsDisplayed()
        compose.onAllNodesWithText("Copy").assertCountEquals(0)

        streamActive.value = false
        compose.waitForIdle()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("<svg", substring = true).assertIsDisplayed()
    }

    @Test fun recursivelyMaskedSvgFallsBackToCopyableCodeWithoutCrashingComposition() {
        val source = "<svg viewBox='0 0 10 10'><mask id='m'><rect width='10' height='10' mask='url(#m)'/></mask><rect width='10' height='10' mask='url(#m)'/></svg>"
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                AssistantMarkdown("```svg\n$source\n```", onOpenSkillFile = {})
            }
        }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("<svg", substring = true).assertIsDisplayed()
    }
}

private fun streamingFenceNode(content: String): ASTNode {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    fun findFence(node: ASTNode): ASTNode? = if (node.type == MarkdownElementTypes.CODE_FENCE) node
        else node.children.firstNotNullOfOrNull(::findFence)
    return requireNotNull(findFence(root))
}

private data class ScrollReentryMessage(val id: Int, val text: String)
