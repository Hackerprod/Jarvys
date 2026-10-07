package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class CoreAgentLoopTest {
    @Test
    fun toolResultReturnsIntoTranscriptBeforeTheModelWritesItsFinalAnswer() {
        val declaration = ToolSpec("lookup", "test", "lookup a fact", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
        val lookup = object : CoreTool {
            override fun declaration() = declaration
            override fun execute(arguments: Map<String, Any>, token: CancellationToken) =
                CoreToolResult.success("The answer is 42")
        }
        val secondTranscript = AtomicReference<List<ConversationTurn>>()
        val secondPrompt = AtomicReference<String>()
        val model = object : CoreAgentLoop.Model {
            private var calls = 0
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                calls++
                assertEquals(listOf("lookup"), tools.map { it.name })
                return if (calls == 1) {
                    ModelReply("I will look that up.", listOf(ModelReply.Call("call-1", "lookup", emptyMap())))
                } else {
                    secondTranscript.set(transcript)
                    secondPrompt.set(prompt)
                    ModelReply("The answer is 42.", emptyList())
                }
            }
        }
        val history = listOf(ConversationTurn("user", "Remember that I asked a question"))
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(lookup)), "You are Jarvys", "session")

        val result = loop.run("What is the answer?", history, CancellationToken.uncancellable(), null)

        assertEquals("The answer is 42.", result.text)
        assertEquals(2, result.turns)
        val transcript = secondTranscript.get()
        assertTrue(transcript.any { it.content == "Remember that I asked a question" })
        assertEquals(1, transcript.count { it.kind == ConversationTurn.Kind.MESSAGE && it.content == "What is the answer?" })
        val calls = transcript.first { it.kind == ConversationTurn.Kind.TOOL_CALLS }
        assertEquals("call-1", calls.toolCalls.single().id)
        val output = transcript.first { it.kind == ConversationTurn.Kind.TOOL_RESULT }
        assertEquals("call-1", output.toolCallId)
        assertEquals("lookup", output.toolName)
        assertEquals("The answer is 42", output.content)
        assertEquals("Continue.", secondPrompt.get())
        assertFalse(transcript.any { it.content.contains("Continue the same user request") })
    }

    @Test
    fun continuesPastTwelveModelTurnsUntilTheModelReturnsFinalText() {
        val declaration = ToolSpec("step", "test", "perform a step", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
        var executed = 0
        val stepTool = object : CoreTool {
            override fun declaration() = declaration
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
                executed++
                return CoreToolResult.success("step $executed done")
            }
        }
        val prompts = mutableListOf<String>()
        val model = object : CoreAgentLoop.Model {
            private var calls = 0
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                calls++
                prompts += prompt
                if (calls <= 15) {
                    assertEquals(listOf("step"), tools.map { it.name })
                    return ModelReply("", listOf(ModelReply.Call("step-$calls", "step", emptyMap())))
                }
                return ModelReply("All requested steps are finished.", emptyList())
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(stepTool)), "Instructions", "session")

        val result = loop.run("Perform fifteen steps", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals(15, executed)
        assertEquals(16, result.turns)
        assertEquals("All requested steps are finished.", result.text)
        assertTrue(prompts.none { it.contains("turns remain") || it.contains("last work turn") })
    }

    @Test
    fun repeatedNoProgressCallsWarnThenGetOneRecoveryBeforeReturningPartial() {
        val declaration = ToolSpec("step", "test", "perform a step", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
        var executed = 0
        val stepTool = object : CoreTool {
            override fun declaration() = declaration
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
                executed++
                return CoreToolResult.success("unchanged")
            }
        }
        val transcripts = mutableListOf<List<ConversationTurn>>()
        val model = object : CoreAgentLoop.Model {
            private var calls = 0
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                calls++
                transcripts += transcript
                if (calls == 1) return ModelReply("", listOf(ModelReply.Call("call-$calls", "step", emptyMap())))
                if (calls <= 20) return ModelReply("", listOf(ModelReply.Call("call-$calls", "step", emptyMap())))
                if (calls == 21 || calls == 22) {
                    return ModelReply("", listOf(ModelReply.Call("call-$calls", "step", emptyMap())))
                }
                throw AssertionError("loop should terminate after the second critical block")
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(stepTool)), "Instructions", "session")

        val result = loop.run("Do work", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("PARTIAL", result.outcome)
        assertTrue(result.text.contains("detectó llamadas repetidas sin avance"))
        assertEquals(20, executed)
        assertTrue(transcripts.any { turns -> turns.any { it.content.contains("Loop warning:") } })
    }

    @Test
    fun timeoutReturnsHonestPartialOutcome() {
        val model = object : CoreAgentLoop.Model {
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                token.cancelForTimeout()
                token.throwIfCancelled()
                throw AssertionError("timed-out model call should not continue")
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "Instructions", "session")

        val result = loop.run("Do work", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("PARTIAL", result.outcome)
        assertTrue(result.text.contains("superó el tiempo máximo"))
        assertTrue(result.text.contains("revisa el workspace"))
    }

    @Test
    fun subagentRejectsToolsOutsideItsGrantedScope() {
        var invoked = false
        val tool = DelegateSubtaskTool(
            { _, _, _, _ -> invoked = true; "child result" },
            listOf("safe_tool"),
            emptyList(),
        )
        val result = tool.execute(mapOf(
            "objective" to "summarize a document",
            "tools" to listOf("ungranted_tool"),
            "skills" to emptyList<String>(),
        ), CancellationToken.uncancellable())

        assertFalse(result.success)
        assertFalse(invoked)
        assertTrue(result.content.contains("outside this agent's capability scope"))
    }
}
