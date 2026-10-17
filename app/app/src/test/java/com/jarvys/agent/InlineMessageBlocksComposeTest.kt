package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.jarvys.agent.ui.chat.ChatMessageArrivalPolicy
import com.jarvys.agent.ui.chat.ConversationTimelineItem
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
                                onDispose { replyMounts.intValue-- }
                            }
                            DisposableEffect(Unit) {
                                blockSlotMounts.intValue++
                                onDispose { blockSlotMounts.intValue-- }
                            }
                            RenderAssistantCodeFence(event.text, fenceNode, MaterialTheme.typography.bodySmall, generating)
                        }
                        DisposableEffect(Unit) {
                            belowMounts.intValue++
                            onDispose { belowMounts.intValue-- }
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
        assertFalse(assistantReplyIsGenerating(openFenceEvent, isRunning.value, userId + 1L))
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
