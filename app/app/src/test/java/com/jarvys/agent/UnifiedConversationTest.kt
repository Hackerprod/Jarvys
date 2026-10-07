package com.jarvys.agent

import com.jarvys.agent.device.ScreenData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class UnifiedConversationTest {
    @Test
    fun ordinaryReplyUsesTranscriptAndEffectiveToolsWithoutADeviceObservation() {
        val history = listOf(ConversationTurn("user", "My name is Sam"))
        val tools = listOf(
            ToolSpec("click", "test", "tap", "device", ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList()),
            ToolSpec("search", "mcp", "search", "external", ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList()),
        )
        val receivedHistory = AtomicReference<List<ConversationTurn>>()
        val receivedImages = AtomicReference<List<ScreenData>>()
        val receivedTools = AtomicReference<List<ToolSpec>>()
        val provider = object : ModelProviderClient {
            override fun complete(
                systemPrompt: String,
                userPrompt: String,
                images: List<ScreenData>,
                tools: List<ToolSpec>,
                sessionId: String,
                token: CancellationToken,
            ): ModelReply = error("Operator should use the transcript completion method")

            override fun completeConversation(
                systemPrompt: String,
                history: List<ConversationTurn>,
                userPrompt: String,
                images: List<ScreenData>,
                tools: List<ToolSpec>,
                sessionId: String,
                token: CancellationToken,
            ): ModelReply {
                receivedHistory.set(history)
                receivedImages.set(images)
                receivedTools.set(tools)
                assertTrue(userPrompt.contains("What is my name?"))
                return ModelReply("Your name is Sam.", emptyList())
            }
        }
        val state = AgentState("run", "session", "What is my name?", emptyList(), "", history)

        val decision = OperatorAgent(provider, tools).decide(state, CancellationToken.uncancellable())

        assertTrue(decision.finish)
        assertEquals("Your name is Sam.", decision.message)
        assertEquals(history, receivedHistory.get())
        assertTrue(receivedImages.get().isEmpty())
        assertEquals(tools, receivedTools.get())
        assertFalse(state.nodeTrace.contains("perception"))
    }
}
