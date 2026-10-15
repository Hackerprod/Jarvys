package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class CoreAgentLoopTest {
    @get:org.junit.Rule val chatTestStore = ChatTestStore()

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
        val firstPrompt = AtomicReference<String>()
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
                    firstPrompt.set(prompt)
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
        assertEquals("What is the answer?", firstPrompt.get())
        val transcript = secondTranscript.get()
        assertTrue(transcript.any { it.content == "Remember that I asked a question" })
        assertEquals(1, transcript.count { it.kind == ConversationTurn.Kind.MESSAGE && it.content == "What is the answer?" })
        val calls = transcript.first { it.kind == ConversationTurn.Kind.TOOL_CALLS }
        assertEquals("call-1", calls.toolCalls.single().id)
        val output = transcript.first { it.kind == ConversationTurn.Kind.TOOL_RESULT }
        assertEquals("call-1", output.toolCallId)
        assertEquals("lookup", output.toolName)
        assertEquals("The answer is 42", output.content)
        assertEquals(ConversationTurn.Kind.TOOL_RESULT, transcript.last().kind)
        assertEquals("", secondPrompt.get())
        assertFalse(transcript.any { it.content.contains("Continue the same user request") })
    }

    @Test
    fun emptyCrewContinuationUsesRealIncomingMessagesAfterPriorToolResults() {
        val previous = listOf(
            ConversationTurn("user", "Original mission"),
            ConversationTurn.toolCalls("", listOf(ModelReply.Call("lookup-call", "lookup", emptyMap()))),
            ConversationTurn.toolResult("lookup-call", "lookup", "Observed fact"),
        )
        var pending = listOf(CoreAgentLoop.IncomingMessage("follow-up-1", "chief", "Check the latest source", false))
        val provider = object : CoreAgentLoop.TurnContextProvider {
            override fun takeUntrustedContext() = ""
            override fun takeDurableMessages(): List<CoreAgentLoop.IncomingMessage> = pending.also { pending = emptyList() }
        }
        val observedTranscript = AtomicReference<List<ConversationTurn>>()
        val observedPrompt = AtomicReference<String>()
        val model = object : CoreAgentLoop.Model {
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                observedTranscript.set(transcript)
                observedPrompt.set(prompt)
                return ModelReply("Updated answer", emptyList())
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "Instructions", "crew-session",
            CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, provider, null)

        val result = loop.run("", previous, CancellationToken.uncancellable(), null)

        assertEquals("Updated answer", result.text)
        assertEquals(1, result.turns)
        assertEquals("", observedPrompt.get())
        val transcript = observedTranscript.get()
        assertTrue(transcript.any { it.kind == ConversationTurn.Kind.TOOL_RESULT && it.content == "Observed fact" })
        assertTrue(transcript.any { it.content.contains("Check the latest source") })
        assertFalse(transcript.any { it.role == "user" && it.content.isEmpty() })
    }

    @Test
    fun discardedEmptyDurableMessageIsResolvedWithoutTranscriptAcknowledgement() {
        val message = CoreAgentLoop.IncomingMessage.mainChat("empty-durable", "")
        var pending = listOf(message)
        val provider = object : CoreAgentLoop.TurnContextProvider {
            override fun takeUntrustedContext() = ""
            override fun takeDurableMessages(): List<CoreAgentLoop.IncomingMessage> =
                pending.also { pending = emptyList() }
        }
        val discarded = mutableListOf<String>()
        var incorporatedCallbacks = 0
        val listener = object : CoreAgentLoop.ProgressListener {
            override fun onProgress(node: String, message: String) { }
            override fun onDurableMessagesIncorporated(messages: List<CoreAgentLoop.IncomingMessage>) {
                incorporatedCallbacks += messages.size
            }
            override fun onDurableMessagesDiscarded(messages: List<CoreAgentLoop.IncomingMessage>) {
                discarded.addAll(messages.map { it.id })
            }
        }
        val model = object : CoreAgentLoop.Model {
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ) = ModelReply("Finished", emptyList())
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "Instructions", "empty-durable-session",
            CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, provider, null)

        val result = loop.run("initial request", emptyList(), CancellationToken.uncancellable(), listener)

        assertEquals("COMPLETED", result.outcome)
        assertEquals(listOf("empty-durable"), discarded)
        assertEquals(0, incorporatedCallbacks)
        assertFalse(loop.checkpointSnapshot().appliedIncomingIds.contains("empty-durable"))
        assertFalse(loop.transcriptSnapshot().any { it.content.isEmpty() })
    }

    @Test
    fun emptyCrewContinuationWithoutRealMessagesEndsWithoutCallingModel() {
        val provider = object : CoreAgentLoop.TurnContextProvider {
            override fun takeUntrustedContext() = ""
        }
        var modelCalls = 0
        val model = object : CoreAgentLoop.Model {
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                modelCalls++
                throw AssertionError("an empty continuation without incoming messages must not call the provider")
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "Instructions", "crew-empty",
            CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, provider, null)

        val result = loop.run("", emptyList(),
            CancellationToken.uncancellable(), null)

        assertEquals("COMPLETED", result.outcome)
        assertEquals(0, result.turns)
        assertEquals(0, modelCalls)
    }

    @Test
    fun emptyPromptContinuesWhenTranscriptEndsWithToolResult() {
        val previous = listOf(
            ConversationTurn("user", "Original request"),
            ConversationTurn.toolCalls("", listOf(ModelReply.Call("old-call", "lookup", emptyMap()))),
            ConversationTurn.toolResult("old-call", "lookup", "Earlier observation"),
        )
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                assertEquals("", prompt)
                assertEquals("Earlier observation", transcript.last().content)
                return ModelReply("Continued from the tool result.", emptyList())
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "Instructions", "continue")

        val result = loop.run("", previous, CancellationToken.uncancellable(), null)

        assertEquals("COMPLETED", result.outcome)
        assertEquals("Continued from the tool result.", result.text)
        assertEquals(1, result.turns)
    }

    @Test
    fun emptyProviderReplyCompletesAndRetainsEmptyReplyDiagnostic() {
        var emptyReplyObserved = 0
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken) = ModelReply("", emptyList())
            override fun onEmptyReply(reply: ModelReply) { emptyReplyObserved++ }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "Instructions", "empty-reply")

        val result = loop.run("Request", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("COMPLETED", result.outcome)
        assertEquals("", result.text)
        assertEquals(1, emptyReplyObserved)
        assertEquals("assistant", loop.transcriptSnapshot().last().role)
        assertEquals("", loop.transcriptSnapshot().last().content)
    }

    @Test
    fun emptyProviderReplySurvivesPreRequestPlanAllCompaction() {
        val files = chatTestStore.newFilesDirectory("empty-reply-compaction")
        val store = chatTestStore.localRunStore(files)
        val session = "empty-reply-compaction"
        val summaryProvider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String,
                                  images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>,
                                  sessionId: String, token: CancellationToken) = ModelReply("Summary", emptyList())

            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>,
                                              userPrompt: String,
                                              images: List<com.jarvys.agent.device.ScreenData>,
                                              tools: List<ToolSpec>, sessionId: String,
                                              token: CancellationToken) = ModelReply("Summary", emptyList())
        }
        val compactor = ConversationCompactor(session, CoreAgentModel(summaryProvider, session), store)
        val earlierHistory = listOf(
            ConversationTurn("user", "Earlier request"),
            ConversationTurn("assistant", "Earlier answer"),
        )
        val compacted = requireNotNull(compactor.compact(earlierHistory, 32_768, "before_next_request",
            ConversationCompactionPolicy.Mode.ALL, CancellationToken.uncancellable(), null))
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken) =
                ModelReply("", emptyList())

            override fun contextWindow(token: CancellationToken) = 32_768
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(emptyList()), "Instructions", session,
            CorePromptBudget.standard(), compactor)

        val result = loop.run("Request", compacted.context, CancellationToken.uncancellable(), null)

        assertEquals("COMPLETED", result.outcome)
        assertEquals("", result.text)
        val transcript = loop.transcriptSnapshot()
        assertEquals(1, transcript.count { it.kind == ConversationTurn.Kind.MESSAGE
            && it.role == "assistant" && it.content.isEmpty() })
        assertTrue(transcript.any { it.kind == ConversationTurn.Kind.COMPACTION_SUMMARY })
        assertTrue(transcript.any { it.kind == ConversationTurn.Kind.MESSAGE && it.role == "user"
            && it.content == "Request" })
    }

    @Test
    fun mixedFinishAndOrdinaryBatchReturnsBothResultsToModel() {
        val finishSpec = ToolSpec("finish_one", "test", "finish", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
        val normalSpec = ToolSpec("ordinary", "test", "continue", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
        val finish = object : CoreTool {
            override fun declaration() = finishSpec
            override fun execute(arguments: Map<String, Any>, token: CancellationToken) = CoreToolResult.finish("finished one")
        }
        val ordinary = object : CoreTool {
            override fun declaration() = normalSpec
            override fun execute(arguments: Map<String, Any>, token: CancellationToken) = CoreToolResult.success("ordinary result")
        }
        val followUp = AtomicReference<List<ConversationTurn>>()
        var modelCalls = 0
        val model = object : CoreAgentLoop.Model {
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                modelCalls++
                if (modelCalls == 1) return ModelReply("", listOf(
                    ModelReply.Call("finish-call", "finish_one", emptyMap()),
                    ModelReply.Call("normal-call", "ordinary", emptyMap()),
                ))
                followUp.set(transcript)
                return ModelReply("Model continued after the mixed batch", emptyList())
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(finish, ordinary)), "Instructions", "mixed-finish")

        val result = loop.run("Run both", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("Model continued after the mixed batch", result.text)
        assertEquals(2, modelCalls)
        assertTrue(followUp.get().any { it.toolCallId == "finish-call" && it.content == "finished one" })
        assertTrue(followUp.get().any { it.toolCallId == "normal-call" && it.content == "ordinary result" })
    }

    @Test
    fun batchOfOnlySuccessfulFinishToolsEndsTheTurn() {
        val firstSpec = ToolSpec("finish_one", "test", "finish", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
        val secondSpec = ToolSpec("finish_two", "test", "finish", "test",
            ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
        val first = object : CoreTool {
            override fun declaration() = firstSpec
            override fun execute(arguments: Map<String, Any>, token: CancellationToken) = CoreToolResult.finish("first terminal")
        }
        val second = object : CoreTool {
            override fun declaration() = secondSpec
            override fun execute(arguments: Map<String, Any>, token: CancellationToken) = CoreToolResult.finish("second terminal")
        }
        var modelCalls = 0
        val model = object : CoreAgentLoop.Model {
            override fun complete(
                transcript: List<ConversationTurn>,
                prompt: String,
                tools: List<ToolSpec>,
                token: CancellationToken,
            ): ModelReply {
                modelCalls++
                return ModelReply("", listOf(
                    ModelReply.Call("first-call", "finish_one", emptyMap()),
                    ModelReply.Call("second-call", "finish_two", emptyMap()),
                ))
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(first, second)), "Instructions", "all-finish")

        val result = loop.run("Finish", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("COMPLETED", result.outcome)
        assertEquals(1, modelCalls)
        assertEquals("second terminal", result.text)
    }

    @Test
    fun continuesPast128ModelTurnsUntilTheModelReturnsFinalText() {
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
                if (calls <= 130) {
                    assertEquals(listOf("step"), tools.map { it.name })
                    return ModelReply("", listOf(ModelReply.Call("step-$calls", "step", emptyMap())))
                }
                return ModelReply("All requested steps are finished.", emptyList())
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(stepTool)), "Instructions", "session")

        val result = loop.run("Perform 130 steps", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals(130, executed)
        assertEquals(131, result.turns)
        assertEquals("All requested steps are finished.", result.text)
        assertTrue(prompts.none { it.contains("turns remain") || it.contains("last work turn") })
    }

    @Test
    fun identicalSuccessfulCallsDoNotTriggerContinuousLoopDetection() {
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
                if (calls <= 40) return ModelReply("", listOf(ModelReply.Call("call-$calls", "step", emptyMap())))
                return ModelReply("Finished after repeated reads.", emptyList())
            }
        }
        val loop = CoreAgentLoop(model, CoreToolRegistry(listOf(stepTool)), "Instructions", "session")

        val result = loop.run("Do work", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("COMPLETED", result.outcome)
        assertEquals(40, executed)
        assertEquals("Finished after repeated reads.", result.text)
        assertTrue(transcripts.none { turns -> turns.any { it.content.contains("Loop warning:") } })
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
        assertEquals(CoreAgentLoop.InterruptionReason.DEADLINE, result.interruptionReason)
        assertTrue(result.text.contains("requested deadline"))
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
