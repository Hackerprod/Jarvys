package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import com.jarvys.agent.ui.mascot.JarvysHeaderOneShotKind
import com.jarvys.agent.ui.mascot.JarvysHeaderOneShotRequest
import com.jarvys.agent.ui.mascot.JarvysHeaderRequestedCompletionTracker

class MascotAnimationToolTest {
    @Test fun animationIsRequiredAndUnknownNamesFailBeforeRequesting() {
        var requests = 0
        val tool = MascotAnimationTool("main-chat") { _, _, _ ->
            requests++
            HeaderMascotAnimationResult("working", "finished")
        }

        val invalidArguments = listOf<Map<String, Any>>(
            emptyMap(), mapOf("animation" to "unknown"), mapOf("animation" to "working", "extra" to true),
        )
        for (arguments in invalidArguments) {
            val result = tool.execute(arguments, CancellationToken.uncancellable())
            assertFalse(result.success)
        }
        assertTrue(tool.execute(mapOf("animation" to 42), CancellationToken.uncancellable()).content.contains("string"))
        assertEquals(0, requests)
    }

    @Test fun validAnimationReturnsFinishedOutcomeAndDeclaresExactEnumSchema() {
        val requested = mutableListOf<Pair<String, String>>()
        val tool = MascotAnimationTool("main-chat") { session, animation, _ ->
            requested += session to animation
            HeaderMascotAnimationResult(animation, "finished", requestId = "request-1")
        }
        val result = tool.execute(mapOf("animation" to "working"), CancellationToken.uncancellable())

        assertTrue(result.success)
        assertEquals("{\"animation\":\"working\",\"outcome\":\"finished\"}", result.content)
        assertEquals(listOf("main-chat" to "working"), requested)
        assertFalse(tool.canDelegate())
        val declaration = tool.declaration()
        assertEquals(MascotAnimationTool.NAME, declaration.name)
        assertEquals(listOf("animation"), declaration.required)
        val schema = declaration.jsonSchema()
        assertEquals(false, schema["additionalProperties"])
        val properties = schema["properties"] as Map<*, *>
        val animation = properties["animation"] as Map<*, *>
        assertEquals("string", animation["type"])
        assertEquals(MascotAnimationTool.ANIMATIONS, animation["enum"])
    }

    @Test fun unavailableMascotOrAssetReturnsTheUiRejectionReasonAsJson() {
        val tool = MascotAnimationTool("main-chat") { _, animation, _ ->
            HeaderMascotAnimationResult(animation, "rejected", "The main chat mascot is not visible.")
        }
        val result = tool.execute(mapOf("animation" to "sad"), CancellationToken.uncancellable())

        assertTrue(result.success)
        assertEquals("{\"animation\":\"sad\",\"outcome\":\"rejected\",\"reason\":\"The main chat mascot is not visible.\"}", result.content)
    }

    @Test fun runCancellationResolvesThePublishedRequestWithoutWaitingForPlayback() {
        val session = "mascot-cancel-test"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Play the working animation")
        AgentRunUiState.bindGeneration(session, 101L)
        AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
            sessionId = session, visible = true, resumed = true, availableAnimationIds = setOf("working"),
        ))
        UserDecisionUiAvailability.setChatVisible(true)
        val token = CancellationToken.cancellable()
        val result = AtomicReference<CoreToolResult>()
        val worker = Thread {
            result.set(MascotAnimationTool(session).execute(mapOf("animation" to "working"), token))
        }
        try {
            worker.start()
            val request = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest?.sessionId == session }
                        .headerMascotAnimationRequest!!
                }
            }
            token.cancel()
            worker.join()

            assertEquals(
                "{\"animation\":\"working\",\"outcome\":\"cancelled\"}",
                result.get().content,
            )
            assertNull(AgentRunUiState.state.value.headerMascotAnimationRequest)
            assertEquals("cancelled", AgentRunUiState.state.value.headerMascotAnimationResult?.outcome)
            assertEquals(request.requestId, AgentRunUiState.state.value.headerMascotAnimationResult?.requestId)
        } finally {
            token.cancel()
            AgentRunUiState.stopPendingGeneration(session, 101L)
            worker.join()
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.resetSession("$session-cleanup")
        }
    }

    @Test fun uiDismissalResolvesThePublishedAnimationRequestWithItsOutcome() {
        val session = "mascot-dismiss-test"
        val generation = 102L
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Play the working animation")
        AgentRunUiState.bindGeneration(session, generation)
        AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
            sessionId = session, visible = true, resumed = true, availableAnimationIds = setOf("working"),
        ))
        UserDecisionUiAvailability.setChatVisible(true)
        val toolResult = AtomicReference<CoreToolResult>()
        val worker = Thread {
            toolResult.set(MascotAnimationTool(session).execute(mapOf("animation" to "working"), CancellationToken.uncancellable()))
        }
        try {
            worker.start()
            val published = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest?.sessionId == session }
                        .headerMascotAnimationRequest!!
                }
            }
            val tracker = JarvysHeaderRequestedCompletionTracker()
            val request = JarvysHeaderOneShotRequest(
                JarvysHeaderOneShotKind.REQUESTED, published.requestId, null, published.animationId,
            )
            tracker.dismiss(request) { eventId, finished, reason ->
                AgentRunUiState.completeHeaderMascotAnimationRequest(
                    published.sessionId, eventId, if (finished) "finished" else "rejected", reason,
                )
            }
            worker.join()

            assertEquals("{\"animation\":\"working\",\"outcome\":\"rejected\",\"reason\":\"The mascot header was removed before playback finished.\"}",
                toolResult.get().content)
            assertNull(AgentRunUiState.state.value.headerMascotAnimationRequest)
            assertEquals("rejected", AgentRunUiState.state.value.headerMascotAnimationResult?.outcome)
        } finally {
            AgentRunUiState.stopPendingGeneration(session, generation)
            worker.join()
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.resetSession("$session-cleanup")
        }
    }

    @Test fun stoppingRunReleasesItsPendingAnimationRequest() {
        val session = "mascot-stop-test"
        val generation = 103L
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Play the working animation")
        AgentRunUiState.bindGeneration(session, generation)
        AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
            sessionId = session, visible = true, resumed = true, availableAnimationIds = setOf("working"),
        ))
        UserDecisionUiAvailability.setChatVisible(true)
        val result = AtomicReference<CoreToolResult>()
        val worker = Thread {
            result.set(MascotAnimationTool(session).execute(
                mapOf("animation" to "working"), CancellationToken.uncancellable(),
            ))
        }
        try {
            worker.start()
            val request = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest?.sessionId == session }
                        .headerMascotAnimationRequest!!
                }
            }
            AgentRunUiState.stopPendingGeneration(session, generation)
            worker.join()

            assertEquals("{\"animation\":\"working\",\"outcome\":\"cancelled\"}", result.get().content)
            assertNull(AgentRunUiState.snapshotForSession(session).headerMascotAnimationRequest)
            assertEquals("cancelled", AgentRunUiState.snapshotForSession(session).headerMascotAnimationResult?.outcome)
            assertEquals(request.requestId,
                AgentRunUiState.snapshotForSession(session).headerMascotAnimationResult?.requestId)
        } finally {
            AgentRunUiState.stopPendingGeneration(session, generation)
            worker.join()
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.resetSession("$session-cleanup")
        }
    }

    @Test fun headerAvailabilityHonorsVisibilityResumeReducedMotionAndLoadedAssets() {
        assertEquals("The main chat mascot is not visible.", HeaderMascotAvailability("chat").failure("sad"))
        assertEquals("The main chat mascot is not active.", HeaderMascotAvailability("chat", true).failure("sad"))
        assertTrue(HeaderMascotAvailability("chat", true, true, reducedMotion = true,
            availableAnimationIds = setOf("sad")).failure("sad")!!.contains("reduced motion"))
        assertTrue(HeaderMascotAvailability("chat", true, true, availableAnimationIds = emptySet())
            .failure("sad")!!.contains("unavailable"))
        assertNull(HeaderMascotAvailability("chat", true, true, availableAnimationIds = setOf("sad")).failure("sad"))
    }

    @Test fun coreRegistryListsTheToolOnceAndDelegatedCatalogExcludesIt() {
        val tool = MascotAnimationTool("main-chat") { _, animation, _ -> HeaderMascotAnimationResult(animation, "finished") }
        val registry = CoreToolRegistry(listOf(tool))

        assertEquals(listOf(MascotAnimationTool.NAME), registry.names())
        assertEquals(1, registry.declarations().count { it.name == MascotAnimationTool.NAME })
        assertTrue(registry.forDelegatedAgent().names().isEmpty())
    }
}
