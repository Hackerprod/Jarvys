package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CoreAgentLoopArchitectureTest {
    private fun await(latch: CountDownLatch) {
        check(latch.await(10, TimeUnit.SECONDS)) { "fixture latch was not reached" }
    }

    private fun spec(name: String) = ToolSpec(
        name, "test", name, "test", ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList(),
    )

    private fun tool(
        name: String,
        mode: CoreTool.ExecutionMode = CoreTool.ExecutionMode.PARALLEL,
        action: (CancellationToken) -> CoreToolResult,
    ) = object : CoreTool {
        override fun declaration() = spec(name)
        override fun executionMode() = mode
        override fun execute(arguments: Map<String, Any>, token: CancellationToken) = action(token)
    }

    private fun call(id: String, name: String) = ModelReply.Call(id, name, emptyMap())

    private fun loop(
        model: CoreAgentLoop.Model,
        tools: List<CoreTool>,
        provider: CoreAgentLoop.TurnContextProvider? = null,
    ) = CoreAgentLoop(
        model, CoreToolRegistry(tools), "fixture", "architecture",
        CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.UNBOUNDED, provider, null,
    )

    @Test fun parallelBatchRunsTogetherAndCommitsResultsInOriginalCallOrder() {
        val bothStarted = CountDownLatch(2)
        val secondReturning = CountDownLatch(1)
        val first = tool("first") {
            bothStarted.countDown()
            await(bothStarted)
            await(secondReturning)
            CoreToolResult.success("first result")
        }
        val second = tool("second") {
            bothStarted.countDown()
            await(bothStarted)
            try {
                CoreToolResult.success("second result")
            } finally {
                secondReturning.countDown()
            }
        }
        val orderedResults = AtomicReference<List<String>>()
        val model = object : CoreAgentLoop.Model {
            private var turn = 0
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                if (turn++ == 0) return ModelReply("", listOf(call("first-id", "first"), call("second-id", "second")))
                orderedResults.set(transcript.filter { it.kind == ConversationTurn.Kind.TOOL_RESULT }.map { it.toolCallId })
                return ModelReply("done", emptyList())
            }
        }

        val result = loop(model, listOf(first, second)).run("request", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("done", result.text)
        assertEquals(listOf("first-id", "second-id"), orderedResults.get())
    }

    @Test fun aSequentialToolMakesTheWholeBatchRunSerially() {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        fun enter(name: String) {
            val now = active.incrementAndGet()
            maximumActive.updateAndGet { prior -> maxOf(prior, now) }
            order.add(name)
        }
        val first = tool("first", action = {
            enter("first")
            firstStarted.countDown()
            await(releaseFirst)
            active.decrementAndGet()
            CoreToolResult.success("first")
        })
        val second = tool("second", CoreTool.ExecutionMode.SEQUENTIAL, action = {
            enter("second")
            active.decrementAndGet()
            CoreToolResult.success("second")
        })
        val model = object : CoreAgentLoop.Model {
            private var turn = 0
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken) =
                if (turn++ == 0) ModelReply("", listOf(call("one", "first"), call("two", "second")))
                else ModelReply("done", emptyList())
        }
        val pool = Executors.newSingleThreadExecutor()
        val run = pool.submit<CoreAgentLoop.Result> {
            loop(model, listOf(first, second)).run("request", emptyList(), CancellationToken.uncancellable(), null)
        }
        try {
            await(firstStarted)
            assertEquals(1, active.get())
            releaseFirst.countDown()
            run.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("first", "second"), order)
            assertEquals(1, maximumActive.get())
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun cancellationMarksStartedParallelCallsUncertainAndCancelsTheirWork() {
        val bothStarted = CountDownLatch(2)
        val release = CountDownLatch(1)
        val token = CancellationToken.cancellable()
        val interruptedCalls = AtomicInteger()
        val interruptedBoth = CountDownLatch(2)
        val blocking: (CancellationToken) -> CoreToolResult = { _ ->
            bothStarted.countDown()
            try {
                await(release)
                CoreToolResult.success("unexpected completion")
            } catch (interrupted: InterruptedException) {
                interruptedCalls.incrementAndGet()
                interruptedBoth.countDown()
                throw java.util.concurrent.CancellationException("tool task interrupted")
            }
        }
        val first = tool("first", action = blocking)
        val second = tool("second", action = blocking)
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken) =
                ModelReply("", listOf(call("first-id", "first"), call("second-id", "second")))
        }
        val loop = loop(model, listOf(first, second))
        val pool = Executors.newSingleThreadExecutor()
        val run = pool.submit<CoreAgentLoop.Result> {
            loop.run("request", emptyList(), token, null)
        }
        try {
            await(bothStarted)
            assertTrue(token.cancel())
            try {
                run.get(10, TimeUnit.SECONDS)
                throw AssertionError("cancelled run should not complete")
            } catch (expected: ExecutionException) {
                assertTrue(expected.cause is java.util.concurrent.CancellationException)
            }
            await(interruptedBoth)
            val checkpoint = loop.checkpointSnapshot()
            assertEquals("INTERRUPTED_UNCERTAIN", checkpoint.toolLifecycle["first-id"])
            assertEquals("INTERRUPTED_UNCERTAIN", checkpoint.toolLifecycle["second-id"])
            assertEquals(2, interruptedCalls.get())
            assertEquals(listOf("first-id", "second-id"),
                checkpoint.transcript.filter { it.kind == ConversationTurn.Kind.TOOL_RESULT }.map { it.toolCallId })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun steeringSkipsSequentialCallsNotYetLaunchedAndFinalReplyDrainsFollowUp() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondExecuted = AtomicInteger()
        val pending = AtomicReference<List<CoreAgentLoop.IncomingMessage>>(emptyList())
        val provider = object : CoreAgentLoop.TurnContextProvider {
            override fun takeUntrustedContext() = ""
            override fun takeDurableMessages(): List<CoreAgentLoop.IncomingMessage> =
                pending.getAndSet(emptyList())
        }
        val first = tool("first", CoreTool.ExecutionMode.SEQUENTIAL) {
            firstStarted.countDown()
            await(releaseFirst)
            CoreToolResult.success("first result")
        }
        val second = tool("second", CoreTool.ExecutionMode.SEQUENTIAL) {
            secondExecuted.incrementAndGet()
            CoreToolResult.success("second result")
        }
        val secondModelTranscript = AtomicReference<List<ConversationTurn>>()
        val model = object : CoreAgentLoop.Model {
            private var turn = 0
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                if (turn++ == 0) return ModelReply("", listOf(call("first-id", "first"), call("second-id", "second")))
                secondModelTranscript.set(transcript)
                return ModelReply("continued with new message", emptyList())
            }
        }
        val loop = loop(model, listOf(first, second), provider)
        val pool = Executors.newSingleThreadExecutor()
        val run = pool.submit<CoreAgentLoop.Result> {
            loop.run("initial", emptyList(), CancellationToken.cancellable(), null)
        }
        try {
            await(firstStarted)
            pending.set(listOf(CoreAgentLoop.IncomingMessage.mainChat("steer-1", "Please check this too")))
            releaseFirst.countDown()
            assertEquals("continued with new message", run.get(10, TimeUnit.SECONDS).text)
            assertEquals(0, secondExecuted.get())
            val checkpoint = loop.checkpointSnapshot()
            assertEquals("RESULT", checkpoint.toolLifecycle["first-id"])
            assertEquals("NEVER_LAUNCHED", checkpoint.toolLifecycle["second-id"])
            val skipped = checkpoint.transcript.single { it.kind == ConversationTurn.Kind.TOOL_RESULT
                && it.toolCallId == "second-id" }
            assertTrue(skipped.content.contains("Skipped because new user input arrived before launch"))
            assertTrue(secondModelTranscript.get().any { it.role == "user" && it.content == "Please check this too" })
            assertFalse(secondModelTranscript.get().any { it.incomingMetadata != null })
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun terminalCallsMayRunInParallelAndEndOnlyAfterTheirResultsAreCommitted() {
        val bothStarted = CountDownLatch(2)
        val first = tool("finish_first") {
            bothStarted.countDown()
            await(bothStarted)
            CoreToolResult.finish("first terminal")
        }
        val second = tool("finish_second") {
            bothStarted.countDown()
            await(bothStarted)
            CoreToolResult.finish("second terminal")
        }
        var modelCalls = 0
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                modelCalls++
                return ModelReply("", listOf(call("finish-one", "finish_first"), call("finish-two", "finish_second")))
            }
        }

        val result = loop(model, listOf(first, second)).run("finish", emptyList(), CancellationToken.uncancellable(), null)

        assertEquals("COMPLETED", result.outcome)
        assertEquals("second terminal", result.text)
        assertEquals(1, modelCalls)
    }

    @Test fun anOrdinaryFinalAnswerDrainsDurableInputBeforeEnding() {
        val modelEntered = CountDownLatch(1)
        val releaseModel = CountDownLatch(1)
        val pending = AtomicReference<List<CoreAgentLoop.IncomingMessage>>(emptyList())
        val provider = object : CoreAgentLoop.TurnContextProvider {
            override fun takeUntrustedContext() = ""
            override fun takeDurableMessages(): List<CoreAgentLoop.IncomingMessage> =
                pending.getAndSet(emptyList())
        }
        val secondTranscript = AtomicReference<List<ConversationTurn>>()
        val model = object : CoreAgentLoop.Model {
            private var turn = 0
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                if (turn++ == 0) {
                    modelEntered.countDown()
                    await(releaseModel)
                    return ModelReply("first answer", emptyList())
                }
                secondTranscript.set(transcript)
                return ModelReply("answer after steering", emptyList())
            }
        }
        val loop = loop(model, emptyList(), provider)
        val token = CancellationToken.cancellable()
        val pool = Executors.newSingleThreadExecutor()
        val run = pool.submit<CoreAgentLoop.Result> {
            loop.run("initial", emptyList(), token, null)
        }
        try {
            await(modelEntered)
            pending.set(listOf(CoreAgentLoop.IncomingMessage.mainChat("steer-2", "Follow-up during generation")))
            releaseModel.countDown()
            assertEquals("answer after steering", run.get(10, TimeUnit.SECONDS).text)
            assertFalse(token.isCancellationRequested)
            val userMessages = secondTranscript.get().filter { it.kind == ConversationTurn.Kind.MESSAGE && it.role == "user" }
            assertTrue(userMessages.any { it.content == "initial" })
            assertTrue(userMessages.any { it.content == "Follow-up during generation" })
        } finally {
            releaseModel.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun cancellingTheRunWhileTheModelIsActiveStillAbortsIt() {
        val modelEntered = CountDownLatch(1)
        val releaseModel = CountDownLatch(1)
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                modelEntered.countDown()
                await(releaseModel)
                return ModelReply("must not be accepted", emptyList())
            }
        }
        val token = CancellationToken.cancellable()
        val pool = Executors.newSingleThreadExecutor()
        val run = pool.submit<CoreAgentLoop.Result> {
            loop(model, emptyList()).run("request", emptyList(), token, null)
        }
        try {
            await(modelEntered)
            assertTrue(token.cancel())
            releaseModel.countDown()
            try {
                run.get(10, TimeUnit.SECONDS)
                throw AssertionError("a user stop must abort the active run")
            } catch (expected: ExecutionException) {
                assertTrue(expected.cause is java.util.concurrent.CancellationException)
            }
        } finally {
            releaseModel.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun anInactiveChatStartsWithAnOrdinaryUserRequest() {
        val prompt = AtomicReference<String>()
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, promptText: String,
                                  tools: List<ToolSpec>, token: CancellationToken): ModelReply {
                prompt.set(promptText)
                return ModelReply("normal answer", emptyList())
            }
        }
        val result = loop(model, emptyList()).run("ordinary request", emptyList(), CancellationToken.uncancellable(), null)
        assertEquals("ordinary request", prompt.get())
        assertEquals("normal answer", result.text)
    }
}
