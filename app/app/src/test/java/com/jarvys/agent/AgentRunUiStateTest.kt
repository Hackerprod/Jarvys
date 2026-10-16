package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class AgentRunUiStateTest {
    @get:org.junit.Rule val chatTestStore = ChatTestStore()
    @Test
    fun selectingUncachedSessionPublishesItsEmptyStateInsteadOfPriorConversation() {
        val firstSession = "uncached-selection-a-${System.nanoTime()}"
        val secondSession = "uncached-selection-b-${System.nanoTime()}"
        val firstMessage = AgentRunUiEvent(1L, "assistant", text = "first session streaming text", messageId = "a-message")
        AgentRunUiState.restoreSession(firstSession, listOf(
            firstMessage,
        ))
        AgentRunUiState.showSession(firstSession)

        AgentRunUiState.showSession(secondSession)

        assertEquals(secondSession, AgentRunUiState.state.value.sessionId)
        assertTrue(AgentRunUiState.eventsForSession(secondSession).isEmpty())
        assertFalse(AgentRunUiState.containsEvent(secondSession, firstMessage))
        assertEquals(listOf("first session streaming text"),
            AgentRunUiState.snapshotForSession(firstSession).events.map { it.text })
        AgentRunUiState.resetSession(firstSession)
        AgentRunUiState.resetSession(secondSession)
    }

    @Test
    fun timelineEditsPreserveOlderLoadedPagesAndAllowTranslationAfterHide() {
        val session = "timeline-edit-pages-${System.nanoTime()}"
        val original = AgentRunUiEvent(10L, "assistant", text = "answer", messageId = "answer-id")
        val older = AgentRunUiEvent(5L, "user", text = "older loaded page", messageId = "older-id")
        AgentRunUiState.restoreSession(session, listOf(older, original))

        AgentRunUiState.assistantTranslationAdded(session,
            AgentRunUiEvent(11L, "assistant_translation", stage = "fr", text = "première", detail = "answer-id"))
        AgentRunUiState.assistantTranslationHidden(session, "answer-id")
        AgentRunUiState.assistantTranslationAdded(session,
            AgentRunUiEvent(12L, "assistant_translation", stage = "fr", text = "dernière", detail = "answer-id"))

        assertEquals(listOf("older loaded page", "answer", "dernière"),
            AgentRunUiState.snapshotForSession(session).events.map { it.text })
        AgentRunUiState.assistantMessageDeleted(session, "answer-id")
        assertEquals(listOf("older loaded page"), AgentRunUiState.snapshotForSession(session).events.map { it.text })
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun olderTimelinePagePrependsByStableIdWithoutTouchingAnotherSession() {
        val firstSession = "timeline-page-a-${System.nanoTime()}"
        val secondSession = "timeline-page-b-${System.nanoTime()}"
        val old = AgentRunUiEvent(10L, "user", text = "older")
        val newest = AgentRunUiEvent(80_000_001L, "assistant", text = "newest", messageId = "newest")
        val other = AgentRunUiEvent(30L, "user", text = "other session")
        AgentRunUiState.restoreSession(firstSession, listOf(newest))
        AgentRunUiState.restoreSession(secondSession, listOf(other))

        AgentRunUiState.prependPersistedSession(firstSession, listOf(old, newest))
        AgentRunUiState.refreshPersistedSession(firstSession, listOf(newest))

        assertEquals(listOf("older", "newest"), AgentRunUiState.snapshotForSession(firstSession).events.map { it.text })
        assertEquals(listOf("other session"), AgentRunUiState.snapshotForSession(secondSession).events.map { it.text })
        AgentRunUiState.resetSession(firstSession)
        AgentRunUiState.resetSession(secondSession)
    }

    @Test
    fun olderTimelinePageHonorsNewerHideMarkersAndTranslations() {
        val session = "timeline-markers-${System.nanoTime()}"
        val newest = AgentRunUiEvent(80_000_001L, "assistant_translation", text = "new translation",
            detail = "message-newer-translation")
        val older = listOf(
            AgentRunUiEvent(10L, "assistant", text = "deleted", messageId = "message-deleted"),
            AgentRunUiEvent(20L, "assistant_translation", text = "hidden translation", detail = "message-hidden-translation"),
            AgentRunUiEvent(30L, "assistant_translation", text = "stale translation", detail = "message-newer-translation"),
            AgentRunUiEvent(40L, "user", text = "visible older message"),
        )
        AgentRunUiState.restoreSession(session, listOf(newest))

        AgentRunUiState.prependPersistedSession(
            session,
            older,
            newerHiddenAssistantMessageIds = setOf("message-deleted"),
            newerHiddenTranslationMessageIds = setOf("message-hidden-translation"),
            newerTranslationMessageIds = setOf("message-newer-translation"),
        )

        assertEquals(listOf("visible older message", "new translation"),
            AgentRunUiState.snapshotForSession(session).events.map { it.text })
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun olderTimelinePageDeduplicatesDispatchedSteeringAndStableMessageIdentity() {
        val session = "timeline-steering-dedupe-${System.nanoTime()}"
        val dispatched = AgentRunUiEvent(90L, "user", text = "steering message", messageId = "request-id")
        val pending = AgentRunUiEvent(10L, "user", stage = "QUEUED", text = "steering message",
            messageId = "pending-request-id")
        val duplicateDispatched = dispatched.copy(id = 11L)
        val older = AgentRunUiEvent(5L, "assistant", text = "older reply", messageId = "older-reply")
        AgentRunUiState.restoreSession(session, listOf(dispatched))

        AgentRunUiState.prependPersistedSession(session, listOf(pending, duplicateDispatched, older))

        assertEquals(listOf("older reply", "steering message"),
            AgentRunUiState.snapshotForSession(session).events.map { it.text })
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun sameLegacyIdFromDifferentWindowsIsKeptWhenRowOriginsDiffer() {
        val session = "legacy-window-origin-${System.nanoTime()}"
        val recent = AgentRunUiEvent(80_000_001L, "user", text = "recent", messageId = "legacy-collision")
        val older = AgentRunUiEvent(10L, "user", text = "older", messageId = "legacy-collision")
        AgentRunUiState.restoreSession(session, listOf(recent))

        AgentRunUiState.prependPersistedSession(session, listOf(older))

        assertEquals(listOf("older", "recent"),
            AgentRunUiState.snapshotForSession(session).events.map { it.text })
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun refreshKeepsSameProviderCallIdFromDifferentTurns() {
        val session = "tool-call-id-turn-scope-${System.nanoTime()}"
        val oldUser = AgentRunUiEvent(10L, "user", text = "old turn", messageId = "old-user")
        val oldTool = AgentRunUiEvent(20L, "tool", stage = "tool_result", text = "Old Tool",
            toolCallId = "call_0", toolIdentity = "batch-old/call_0", toolTurnMessageId = "old-user")
        val newUser = AgentRunUiEvent(80_000_001L, "user", text = "new turn", messageId = "new-user")
        val newTool = AgentRunUiEvent(80_000_002L, "tool", stage = "tool_result", text = "New Tool",
            toolCallId = "call_0", toolIdentity = "batch-new/call_0", toolTurnMessageId = "new-user")
        AgentRunUiState.restoreSession(session, listOf(oldUser, oldTool))

        AgentRunUiState.refreshPersistedSession(session, listOf(newUser, newTool))

        val calls = AgentRunUiState.snapshotForSession(session).events.filter {
            it.kind == "tool" && it.toolCallId == "call_0"
        }
        assertEquals(listOf("Old Tool", "New Tool"), calls.map { it.text })
        assertEquals(listOf("batch-old/call_0", "batch-new/call_0"), calls.map { it.toolIdentity })
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun deletingAssistantHidesItsTurnToolsAndPriorCompactionsInLoadedWindow() {
        val session = "assistant-delete-turn-effects-${System.nanoTime()}"
        val events = listOf(
            AgentRunUiEvent(1L, "user", text = "first turn", messageId = "first-user"),
            AgentRunUiEvent(2L, "tool", stage = "tool_result", text = "First Tool", toolCallId = "same",
                toolIdentity = "batch-first/same", toolTurnMessageId = "first-user"),
            AgentRunUiEvent(3L, "compaction", text = "old summary"),
            AgentRunUiEvent(4L, "assistant", text = "deleted answer", messageId = "deleted-answer"),
            AgentRunUiEvent(5L, "user", text = "second turn", messageId = "second-user"),
            AgentRunUiEvent(6L, "tool", stage = "tool_result", text = "Second Tool", toolCallId = "same",
                toolIdentity = "batch-second/same", toolTurnMessageId = "second-user"),
        )
        AgentRunUiState.restoreSession(session, events)

        AgentRunUiState.assistantMessageDeleted(session, "deleted-answer", "first-user")

        assertEquals(listOf("first turn", "second turn", "Second Tool"),
            AgentRunUiState.snapshotForSession(session).events.map { it.text })
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun toolResultUsesLatestSharedSessionSnapshotAfterConcurrentSessionWrites() {
        val session = "shared-snapshot-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Original run")
        AgentRunUiState.bindGeneration(session, 901L)

        val workerEnteredSession = CountDownLatch(1)
        val allowToolResult = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val workerSnapshot = AtomicReference<AgentRunUiSnapshot?>()
        val worker = Thread {
            try {
                AgentRunUiState.withSession(session, Runnable {
                    workerEnteredSession.countDown()
                    allowToolResult.await()
                    AgentRunUiState.onToolActivity(ToolActivity(
                        "result-event", "$session:901", "call-1", "deliver_file", "Deliver File",
                        "", "", "tool_result", "Delivered", "", "", "", 1L,
                    ))
                    workerSnapshot.set(AgentRunUiState.state.value)
                })
            } catch (failure: Throwable) {
                workerFailure.set(failure)
                workerEnteredSession.countDown()
            }
        }
        worker.start()
        try {
            workerEnteredSession.await()
            val artifact = ChatAttachment(
                "00000000-0000-0000-0000-000000000901", "delivered.txt", "text/plain", 1L,
                ChatAttachment.Kind.FILE, "00000000-0000-0000-0000-000000000901-delivered.txt",
            )
            AgentRunUiState.deliveredFileAdded(session, artifact)
            AgentRunUiState.beginChatSubmission(session, "Steering message", emptyList())
            AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
                sessionId = session,
                visible = true,
                resumed = true,
                availableAnimationIds = setOf("working"),
            ))
            allowToolResult.countDown()
            worker.join()

            assertNull(workerFailure.get())
            val live = AgentRunUiState.state.value
            val sessionSnapshot = AgentRunUiState.snapshotForSession(session)
            listOf(live, sessionSnapshot, requireNotNull(workerSnapshot.get())).forEach { snapshot ->
                assertTrue(snapshot.events.any { it.kind == "artifact"
                    && it.deliveredArtifact?.id == artifact.id })
                assertTrue(snapshot.events.any { it.kind == "user" && it.text == "Steering message" })
                assertEquals(session, snapshot.headerMascotAvailability.sessionId)
                assertTrue(snapshot.headerMascotAvailability.visible)
            }
        } finally {
            allowToolResult.countDown()
            worker.join()
            AgentRunUiState.stopPendingGeneration(session, 901L)
            AgentRunUiState.finishPendingGeneration(session, 901L)
            AgentRunUiState.resetSession("$session-cleanup")
        }
    }

    @Test
    fun stoppedCompactionClearsBusyIndicatorWithoutAddingACompactionRow() {
        AgentRunUiState.resetSession("compact-stop")
        AgentRunUiState.compactionStarted("compact-stop", "Compacting…")
        assertTrue(AgentRunUiState.state.value.compacting)
        AgentRunUiState.compactionCancelled("compact-stop")
        assertFalse(AgentRunUiState.state.value.compacting)
        assertFalse(AgentRunUiState.state.value.events.any { it.kind == "compaction" })
        AgentRunUiState.resetSession("compact-stop-cleanup")
    }

    @Test
    fun visibleProjectionKeepsRunOwnershipAndStopFinalizationPerSession() {
        AgentRunUiState.resetSession("session-a")
        AgentRunUiState.beginRun("session-a", "first chat")
        AgentRunUiState.bindGeneration("session-a", 101L)
        AgentRunUiState.resetSession("session-b")
        AgentRunUiState.beginRun("session-b", "second chat")
        AgentRunUiState.bindGeneration("session-b", 202L)

        AgentRunUiState.completeGeneration("session-a", 101L, "a-run", "COMPLETED", "A finished", "a-message", 1L)
        assertEquals("session-b", AgentRunUiState.state.value.sessionId)
        assertTrue(AgentRunUiState.state.value.running)
        assertEquals("COMPLETED", AgentRunUiState.snapshotForSession("session-a").outcome)

        AgentRunUiState.stopPendingGeneration("session-b", 202L)
        assertFalse(AgentRunUiState.state.value.running)
        assertTrue(AgentRunUiState.state.value.stopping)
        assertNull(AgentRunUiState.state.value.outcome)
        AgentRunUiState.completeGeneration("session-b", 202L, "b-run", "STOPPED", "B stopped", "b-message", 2L)
        assertTrue(AgentRunUiState.state.value.stopping)
        assertFalse(AgentRunUiState.state.value.events.any { it.text == "B stopped" })

        AgentRunUiState.finishPendingGeneration("session-b", 202L)
        assertFalse(AgentRunUiState.state.value.stopping)
        assertEquals("STOPPED", AgentRunUiState.state.value.outcome)
        assertEquals("B stopped", AgentRunUiState.state.value.events.last().text)
        AgentRunUiState.resetSession("session-b-cleanup")
    }

    @Test
    fun stoppedGenerationCompletionIsPublishedForVisibleAndNonVisibleSessions() {
        val visibleSession = "stopped-visible-${System.nanoTime()}"
        val nonVisibleSession = "stopped-hidden-${System.nanoTime()}"

        fun completeStoppedGenerationWhileWorkerSessionIsOpen(sessionId: String, generation: Long) {
            val workerEnteredSession = CountDownLatch(1)
            val allowWorkerToComplete = CountDownLatch(1)
            val workerFailure = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    AgentRunUiState.withSession(sessionId, Runnable {
                        workerEnteredSession.countDown()
                        allowWorkerToComplete.await()
                        AgentRunUiState.completeGeneration(
                            sessionId, generation, "$sessionId-run", "STOPPED", "Run stopped",
                            "$sessionId-message", 1L,
                        )
                    })
                } catch (failure: Throwable) {
                    workerFailure.set(failure)
                    workerEnteredSession.countDown()
                }
            }
            worker.start()
            workerEnteredSession.await()
            try {
                AgentRunUiState.stopPendingGeneration(sessionId, generation)
            } finally {
                allowWorkerToComplete.countDown()
            }
            worker.join()
            assertNull(workerFailure.get())
            AgentRunUiState.finishPendingGeneration(sessionId, generation)
        }

        AgentRunUiState.resetSession(visibleSession)
        AgentRunUiState.beginRun(visibleSession, "Stop this run")
        AgentRunUiState.bindGeneration(visibleSession, 301L)
        completeStoppedGenerationWhileWorkerSessionIsOpen(visibleSession, 301L)
        assertFalse(AgentRunUiState.state.value.running)
        assertEquals("STOPPED", AgentRunUiState.state.value.outcome)

        AgentRunUiState.resetSession(nonVisibleSession)
        AgentRunUiState.beginRun(nonVisibleSession, "Stop this run")
        AgentRunUiState.bindGeneration(nonVisibleSession, 302L)
        AgentRunUiState.showSession(visibleSession)
        completeStoppedGenerationWhileWorkerSessionIsOpen(nonVisibleSession, 302L)

        val visible = AgentRunUiState.state.value
        val nonVisible = AgentRunUiState.snapshotForSession(nonVisibleSession)
        assertEquals(visibleSession, visible.sessionId)
        assertFalse(visible.running)
        assertEquals("STOPPED", visible.outcome)
        assertEquals(nonVisibleSession, nonVisible.sessionId)
        assertFalse(nonVisible.running)
        assertEquals("STOPPED", nonVisible.outcome)
        assertEquals("Run stopped", nonVisible.events.last().text)

        AgentRunUiState.resetSession("$visibleSession-cleanup")
    }

    @Test
    fun mascotAnimationResultIsCorrelatedByRequestIdAndAvailabilityCleanupResolvesWaiter() {
        val session = "mascot-result-test"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Play working")
        AgentRunUiState.bindGeneration(session, 201L)
        AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
            sessionId = session, visible = true, resumed = true, availableAnimationIds = setOf("working"),
        ))
        UserDecisionUiAvailability.setChatVisible(true)
        val firstResult = AtomicReference<HeaderMascotAnimationResult>()
        val first = Thread {
            firstResult.set(AgentRunUiState.requestHeaderMascotAnimation(
                session, "working", CancellationToken.uncancellable(),
            ))
        }
        try {
            first.start()
            val firstRequest = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest != null }.headerMascotAnimationRequest!!
                }
            }
            AgentRunUiState.completeHeaderMascotAnimationRequest(session, firstRequest.requestId, "finished")
            first.join()
            assertEquals(firstRequest.requestId, firstResult.get().requestId)
            assertEquals("finished", firstResult.get().outcome)
            assertEquals(firstRequest.requestId, AgentRunUiState.state.value.headerMascotAnimationResult?.requestId)

            val cleanupResult = AtomicReference<HeaderMascotAnimationResult>()
            val second = Thread {
                cleanupResult.set(AgentRunUiState.requestHeaderMascotAnimation(
                    session, "working", CancellationToken.uncancellable(),
                ))
            }
            second.start()
            val secondRequest = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest != null }.headerMascotAnimationRequest!!
                }
            }
            AgentRunUiState.clearHeaderMascotAvailability(session)
            second.join()
            assertEquals(secondRequest.requestId, cleanupResult.get().requestId)
            assertEquals("rejected", cleanupResult.get().outcome)
            assertTrue(cleanupResult.get().reason!!.contains("no longer available"))
        } finally {
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.stopPendingGeneration(session, 201L)
            AgentRunUiState.resetSession("$session-cleanup")
        }
    }

    @Test
    fun discardedMascotAnimationResolvesOnlyItsRequestAndStaleGenerationCannotResolveIt() {
        val session = "mascot-discard-test"
        val generation = 202L
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Play working")
        AgentRunUiState.bindGeneration(session, generation)
        AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
            sessionId = session, visible = true, resumed = true, availableAnimationIds = setOf("working"),
        ))
        UserDecisionUiAvailability.setChatVisible(true)
        val result = AtomicReference<HeaderMascotAnimationResult>()
        val waiter = Thread {
            result.set(AgentRunUiState.requestHeaderMascotAnimation(
                session, "working", CancellationToken.uncancellable(),
            ))
        }
        try {
            waiter.start()
            val request = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest != null }.headerMascotAnimationRequest!!
                }
            }

            AgentRunUiState.completeGeneration(session, generation - 1, "stale", "COMPLETED", "stale", "", 0L)
            assertEquals(request.requestId, AgentRunUiState.state.value.headerMascotAnimationRequest?.requestId)
            assertEquals(null, result.get())

            AgentRunUiState.clearHeaderMascotAnimationRequest(session, request.requestId)
            waiter.join()
            assertEquals(request.requestId, result.get().requestId)
            assertEquals("rejected", result.get().outcome)
            assertEquals(null, AgentRunUiState.state.value.headerMascotAnimationRequest)
        } finally {
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.stopPendingGeneration(session, generation)
            AgentRunUiState.resetSession("$session-cleanup")
        }
    }

    @Test
    fun mascotAnimationRequestsStayWithTheirSessionAcrossVisibleProjectionChanges() {
        val sessionA = "mascot-session-a"
        val sessionB = "mascot-session-b"
        val generationA = 211L
        val generationB = 212L
        AgentRunUiState.resetSession(sessionA)
        AgentRunUiState.beginRun(sessionA, "Play for A")
        AgentRunUiState.bindGeneration(sessionA, generationA)
        AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
            sessionId = sessionA, visible = true, resumed = true, availableAnimationIds = setOf("working"),
        ))
        AgentRunUiState.resetSession(sessionB)
        AgentRunUiState.beginRun(sessionB, "Play for B")
        AgentRunUiState.bindGeneration(sessionB, generationB)
        AgentRunUiState.headerMascotAvailabilityChanged(HeaderMascotAvailability(
            sessionId = sessionB, visible = true, resumed = true, availableAnimationIds = setOf("working"),
        ))
        UserDecisionUiAvailability.setChatVisible(true)

        val resultA = AtomicReference<HeaderMascotAnimationResult>()
        val waiterA = Thread {
            resultA.set(AgentRunUiState.requestHeaderMascotAnimation(
                sessionA, "working", CancellationToken.uncancellable(),
            ))
        }
        val resultB = AtomicReference<HeaderMascotAnimationResult>()
        val waiterB = Thread {
            resultB.set(AgentRunUiState.requestHeaderMascotAnimation(
                sessionB, "working", CancellationToken.uncancellable(),
            ))
        }
        try {
            AgentRunUiState.showSession(sessionA)
            waiterA.start()
            val requestA = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest?.sessionId == sessionA }
                        .headerMascotAnimationRequest!!
                }
            }
            assertNull(AgentRunUiState.snapshotForSession(sessionB).headerMascotAnimationRequest)
            AgentRunUiState.completeHeaderMascotAnimationRequest(sessionA, requestA.requestId, "finished")
            waiterA.join()
            assertEquals(requestA.requestId, resultA.get().requestId)
            assertEquals("finished", resultA.get().outcome)

            waiterB.start()
            val requestB = runBlocking {
                withTimeout(10_000) {
                    var request: HeaderMascotAnimationRequest? = null
                    while (request == null) {
                        request = AgentRunUiState.snapshotForSession(sessionB).headerMascotAnimationRequest
                        if (request == null) delay(1)
                    }
                    request!!
                }
            }
            assertNull(AgentRunUiState.snapshotForSession(sessionA).headerMascotAnimationRequest)
            assertEquals(sessionA, AgentRunUiState.state.value.sessionId)
            assertNull(AgentRunUiState.state.value.headerMascotAnimationRequest)
            AgentRunUiState.showSession(sessionB)
            val publishedRequestB = runBlocking {
                withTimeout(10_000) {
                    AgentRunUiState.state.first { it.headerMascotAnimationRequest?.requestId == requestB.requestId }
                        .headerMascotAnimationRequest!!
                }
            }
            assertEquals(requestB, publishedRequestB)
            AgentRunUiState.completeHeaderMascotAnimationRequest(sessionB, requestB.requestId, "finished")
            waiterB.join()
            assertEquals(requestB.requestId, resultB.get().requestId)
            assertEquals("finished", resultB.get().outcome)
        } finally {
            AgentRunUiState.stopPendingGeneration(sessionA, generationA)
            AgentRunUiState.stopPendingGeneration(sessionB, generationB)
            waiterA.join()
            waiterB.join()
            UserDecisionUiAvailability.setChatVisible(false)
            AgentRunUiState.resetSession("mascot-session-cleanup")
        }
    }

    @Test
    fun simpleConversationShowsOneAssistantBubbleWithoutInternalProgress() {
        AgentRunUiState.resetSession("plain-chat")
        AgentRunUiState.beginRun("plain-chat", "How are you called?")
        AgentRunUiState.onProgress("thinking", "Model turn 1")
        AgentRunUiState.onProgress("answer", "I am Jarvys.")
        AgentRunUiState.complete("plain-run", "COMPLETED", "I am Jarvys.")

        val events = AgentRunUiState.state.value.events
        assertEquals(listOf("user", "assistant"), events.map { it.kind })
        assertEquals("I am Jarvys.", events.last().text)
        AgentRunUiState.resetSession("plain-chat-cleanup")
    }

    @Test
    fun toolRunFlushesProgressAndKeepsToolEventsBeforeOneFinalAssistantBubble() {
        AgentRunUiState.resetSession("tool-chat")
        AgentRunUiState.beginRun("tool-chat", "Search for a file")
        AgentRunUiState.onProgress("thinking", "Model turn 1")
        AgentRunUiState.onToolProgress("tool_call", "call-1", "delegate_subtask", null)
        AgentRunUiState.onToolProgress("tool_result", "call-1", "delegate_subtask", "found it")
        AgentRunUiState.onProgress("answer", "Found the file.")
        AgentRunUiState.complete("tool-run", "COMPLETED", "Found the file.")

        val events = AgentRunUiState.state.value.events
        assertFalse(events.any { it.text.startsWith("Model turn") })
        assertEquals(1, events.count { it.kind == "tool" })
        val tool = events.first { it.kind == "tool" }
        assertEquals("delegate_subtask", tool.text)
        assertEquals("delegate_subtask", tool.toolDisplayName)
        assertEquals(R.string.connector_tool_used, tool.toolStatusResourceId)
        assertEquals("found it", tool.detail)
        assertEquals(1, events.count { it.kind == "assistant" })
        assertEquals("Found the file.", events.last().text)
        AgentRunUiState.resetSession("tool-chat-cleanup")
    }

    @Test
    fun toolPayloadIsKeptOutOfTheCollapsedLabelAndAvailableAsExpandableDetail() {
        AgentRunUiState.resetSession("tool-output")
        AgentRunUiState.beginRun("tool-output", "Check server metrics")
        val rawOutput = "{\"cpu\":12,\"memory\":8192}"
        AgentRunUiState.onToolProgress("tool_call", "metrics-1", "System Metrics", null)
        val runningRowId = AgentRunUiState.state.value.events.single { it.kind == "tool" }.id
        AgentRunUiState.onToolProgress("tool_result", "metrics-1", "System Metrics", rawOutput)

        val toolEvents = AgentRunUiState.state.value.events.filter { it.kind == "tool" }
        assertEquals(1, toolEvents.size)
        assertEquals(runningRowId, toolEvents.single().id)
        assertEquals("System Metrics", toolEvents.single().text)
        assertEquals(R.string.connector_tool_used, toolEvents.single().toolStatusResourceId)
        assertFalse(toolEvents.single().text.contains(rawOutput))
        assertEquals(rawOutput, toolEvents.single().detail)
        AgentRunUiState.resetSession("tool-output-cleanup")
    }

    @Test
    fun completedPreviewToolCarriesAnOpenActionOnItsCorrelatedToolRow() {
        AgentRunUiState.resetSession("preview-chat")
        AgentRunUiState.beginRun("preview-chat", "Make a page")
        AgentRunUiState.onToolProgress("tool_call", "preview-1", "preview_workspace", null)
        val runningRow = AgentRunUiState.state.value.events.single { it.kind == "tool" }
        assertEquals(R.string.connector_tool_using, runningRow.toolStatusResourceId)
        AgentRunUiState.onToolProgress("tool_result", "preview-1", "preview_workspace", "Ready", "a1b2c3")

        val completedRow = AgentRunUiState.state.value.events.single { it.kind == "tool" }
        assertEquals(runningRow.id, completedRow.id)
        assertEquals("a1b2c3", completedRow.previewId)
        assertEquals("preview_workspace", completedRow.text)
        assertEquals(R.string.connector_tool_used, completedRow.toolStatusResourceId)
        AgentRunUiState.resetSession("preview-chat-cleanup")
    }

    @Test fun persistedToolEventsAreDurableAcrossRefreshWithoutDuplicatingLiveResults() {
        val session = "refresh-tools-${System.nanoTime()}"
        val root = chatTestStore.newFilesDirectory()
        val store = chatTestStore.localRunStore(root)
        val userId = store.appendConversationMessage(session, "user", "Send a message")
        store.appendReflectionToolEvent(session, userId, "Telegram Send Message", "connector:telegram",
            "tool_result", "persisted-call")
        val persisted = store.readConversationTimeline(session)

        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Send a message")
        AgentRunUiState.onToolProgress("tool_result", "persisted-call", "Telegram Send Message", "private result")
        AgentRunUiState.onToolProgress("tool_call", "pending-call", "Pending tool", null)
        AgentRunUiState.refreshPersistedSession(session, persisted)

        val tools = AgentRunUiState.state.value.events.filter { it.kind == "tool" }
        assertEquals(listOf("persisted-call", "pending-call"), tools.map { it.toolCallId })
        assertEquals(R.string.connector_tool_used, tools.first().toolStatusResourceId)
        assertEquals("Telegram Send Message", tools.first().toolDisplayName)
        assertEquals("tool_call", tools.last().stage)
        AgentRunUiState.resetSession("$session-cleanup")
    }

    @Test fun completedAssistantStageIsSharedForLiveOutcomes() {
        listOf("COMPLETED" to null, "STOPPED" to "STOPPED", "FAILED" to "FAILED", "PARTIAL" to "PARTIAL")
            .forEachIndexed { index, (outcome, expectedStage) ->
                val session = "stage-live-$index-${System.nanoTime()}"
                AgentRunUiState.resetSession(session)
                AgentRunUiState.beginRun(session, "Task")
                AgentRunUiState.complete("run-$index", outcome, "Answer")
                assertEquals(expectedStage, AgentRunUiState.state.value.events.last { it.kind == "assistant" }.stage)
            }
        AgentRunUiState.resetSession("stage-live-cleanup")
    }

    @Test
    fun multipleTaskTurnsStayInOneSessionAndServiceStartDoesNotDuplicateUserMessage() {
        AgentRunUiState.resetSession("session-test")
        AgentRunUiState.beginRun("session-test", "open settings")
        AgentRunUiState.beginRun("session-test", "open settings")
        AgentRunUiState.onProgress("planner", "Planning")
        AgentRunUiState.complete("audit-run-1", "COMPLETED", "Settings opened.")

        AgentRunUiState.beginRun("session-test", "turn on Wi-Fi")
        val secondTurn = AgentRunUiState.state.value
        assertEquals("session-test", secondTurn.sessionId)
        assertTrue(secondTurn.running)
        assertEquals(2, secondTurn.events.count { it.kind == "user" })
        assertTrue(secondTurn.events.any { it.text == "Settings opened." })

        AgentRunUiState.complete("audit-run-2", "COMPLETED", "Wi-Fi enabled.")
        val complete = AgentRunUiState.state.value
        assertFalse(complete.running)
        assertEquals(2, complete.events.count { it.kind == "user" })
        assertTrue(complete.events.any { it.text == "Wi-Fi enabled." })
        AgentRunUiState.resetSession("session-test-cleanup")
    }

    @Test
    fun approvalCardMutatesInPlaceWhenResolved() {
        AgentRunUiState.resetSession("approval-chat")
        AgentRunUiState.beginRun("approval-chat", "Create a meeting")
        AgentRunUiState.showApproval("approval-1", "Create Calendar Event", listOf("Design review", "Calendar: Work"), "WRITE_CALENDAR")
        val pending = AgentRunUiState.state.value.events.single { it.kind == "approval" }
        AgentRunUiState.updateApproval("approval-1", "APPROVED")
        val resolved = AgentRunUiState.state.value.events.single { it.kind == "approval" }
        assertEquals(pending.id, resolved.id)
        assertEquals(1, AgentRunUiState.state.value.events.count { it.approvalId == "approval-1" })
        assertEquals("APPROVED", resolved.approvalStatus)
        assertEquals("WRITE_CALENDAR", resolved.approvalPermission)
        AgentRunUiState.resetSession("approval-chat-cleanup")
    }

    @Test
    fun approvalCardRetainsAllowTargetAndHighImpactConfirmationReason() {
        AgentRunUiState.resetSession("approval-detail-chat")
        AgentRunUiState.beginRun("approval-detail-chat", "Place a call")
        val reason = "High-impact writes always require confirmation."
        AgentRunUiState.showApproval(
            "approval-high-impact", "Place Phone Call", listOf(reason, "Call 5550100"), null,
            allowAlwaysAvailable = false, autonomyConnectorId = "phone", autonomyOperationName = "place_call",
        )

        val card = AgentRunUiState.state.value.events.single { it.approvalId == "approval-high-impact" }
        assertTrue(card.approvalLines.contains(reason))
        assertFalse(card.approvalAllowAlwaysAvailable)
        assertEquals("phone", card.approvalAutonomyConnectorId)
        assertEquals("place_call", card.approvalAutonomyOperationName)
        AgentRunUiState.updateApprovalDetail("approval-high-impact", "This action is approved, but Allow mode was not enabled: Enable Jarvys notifications.")
        assertTrue(AgentRunUiState.state.value.events.single { it.approvalId == "approval-high-impact" }
            .approvalStatusDetail.orEmpty().contains("Allow mode was not enabled"))
        AgentRunUiState.resetSession("approval-detail-chat-cleanup")
    }

    @Test
    fun pendingApprovalMarksEarlierToolRowAsWaitingAndKeepsItBeforeCard() {
        AgentRunUiState.resetSession("approval-order-chat")
        AgentRunUiState.beginRun("approval-order-chat", "Send a text")
        AgentRunUiState.onToolProgress("tool_call", "call-sms", "Send SMS", null)
        AgentRunUiState.showApproval("approval-sms", "Send SMS", listOf("To: 9714842828"), null)

        val events = AgentRunUiState.state.value.events
        val toolIndex = events.indexOfFirst { it.toolCallId == "call-sms" }
        val approvalIndex = events.indexOfFirst { it.approvalId == "approval-sms" }
        assertTrue(toolIndex >= 0)
        assertTrue(approvalIndex > toolIndex)
        assertEquals("approval_waiting", events[toolIndex].stage)
        assertEquals(com.jarvys.agent.R.string.connector_tool_waiting_for_approval, events[toolIndex].toolStatusResourceId)
        AgentRunUiState.resetSession("approval-order-chat-cleanup")
    }

    @Test
    fun concurrentPendingApprovalCardsAreNotEvictedByProgressHistoryRetention() {
        AgentRunUiState.resetSession("approval-queue-chat")
        AgentRunUiState.beginRun("approval-queue-chat", "Coordinate independent device actions")
        val requestIds = (1..301).map { "approval-$it" }
        requestIds.forEach { id ->
            AgentRunUiState.showApproval(id, "Confirm action", listOf("Requested by Crew bot: $id"), null)
        }

        val pending = AgentRunUiState.state.value.events.filter { it.kind == "approval" && it.approvalStatus == "PENDING" }
        assertEquals(requestIds.toSet(), pending.mapNotNull { it.approvalId }.toSet())
        AgentRunUiState.updateApproval("approval-1", "APPROVED")
        AgentRunUiState.resetSession("approval-queue-chat-cleanup")
    }

    @Test
    fun failedChatStartIsMarkedForTextOnlyErrorPresentation() {
        AgentRunUiState.resetSession("failed-chat-cleanup")
        AgentRunUiState.beginRun("failed-chat-cleanup", "Start a task")
        AgentRunUiState.failChatTurn("failed-chat-cleanup", "The task could not be started.")

        val failure = AgentRunUiState.state.value.events.last()
        assertEquals("assistant", failure.kind)
        assertEquals("FAILED", failure.stage)
        AgentRunUiState.resetSession("failed-chat-cleanup-done")
    }

    @Test
    fun queuedOptimisticMessageSurvivesStopFinalizationAndStaleOldWorkerExit() {
        val session = "optimistic-stop-race-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Old task")
        AgentRunUiState.bindGeneration(session, 701L)
        AgentRunUiState.stopPendingGeneration(session, 701L)

        val optimisticId = AgentRunUiState.beginChatSubmission(session, "New message", emptyList())
        val messageId = "persisted-new-message"
        AgentRunUiState.bindCurrentUserMessage(session, messageId, optimisticId)
        assertTrue(AgentRunUiState.state.value.stopping)
        assertFalse(AgentRunUiState.state.value.running)
        assertEquals("New message", AgentRunUiState.state.value.events.single { it.id == optimisticId }.text)

        AgentRunUiState.finishPendingGeneration(session, 701L)
        assertFalse(AgentRunUiState.state.value.stopping)
        assertFalse(AgentRunUiState.state.value.running)
        assertEquals("New message", AgentRunUiState.state.value.goal)
        assertTrue(AgentRunUiState.state.value.events.any { it.id == optimisticId })

        val userEventIdsBeforeServiceStart = AgentRunUiState.state.value.events
            .filter { it.kind == "user" }.map { it.id }
        AgentRunUiState.beginServiceRun(session, "New message", emptyList(), false, 702L, messageId)
        assertEquals(userEventIdsBeforeServiceStart, AgentRunUiState.state.value.events
            .filter { it.kind == "user" }.map { it.id })
        assertEquals(1, AgentRunUiState.state.value.events.count {
            it.kind == "user" && it.messageId == messageId
        })
        AgentRunUiState.finishPendingGeneration(session, 701L)
        AgentRunUiState.completeGeneration(session, 701L, "old-run", "STOPPED", "Late old result", "old-result", 1L)
        assertTrue(AgentRunUiState.state.value.running)
        assertFalse(AgentRunUiState.state.value.stopping)
        assertEquals("New message", AgentRunUiState.state.value.goal)
        assertEquals("$session:702", AgentRunUiState.state.value.liveRunIdentity)
        assertTrue(AgentRunUiState.state.value.events.any { it.id == optimisticId })
        assertFalse(AgentRunUiState.state.value.events.any { it.text == "Late old result" })

        AgentRunUiState.completeGeneration(session, 702L, "new-run", "COMPLETED", "Done", "new-result", 1L)
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun serviceStartWithoutAnExistingDurableRowStillCreatesOneUserRow() {
        val session = "service-start-row-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginServiceRun(session, "Fresh request", emptyList(), false, 703L)

        assertEquals(1, AgentRunUiState.state.value.events.count { it.kind == "user" })
        assertEquals("Fresh request", AgentRunUiState.state.value.events.single { it.kind == "user" }.text)
        AgentRunUiState.completeGeneration(session, 703L, "fresh-run", "COMPLETED", "Done", "fresh-result", 1L)
        AgentRunUiState.resetSession(session)
    }

    @Test
    fun optimisticSubmissionFailureIsAttachedToItsExactUserRow() {
        val session = "optimistic-failure-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        val firstId = AgentRunUiState.beginChatSubmission(session, "Repeated request", emptyList())
        val secondId = AgentRunUiState.beginChatSubmission(session, "Repeated request", emptyList())
        assertNotEquals(firstId, secondId)

        AgentRunUiState.failOptimisticUserMessage(session, firstId, "First send was stopped.")

        val afterFirstFailure = AgentRunUiState.state.value.events.filter { it.kind == "user" }
        assertEquals("First send was stopped.", afterFirstFailure.single { it.id == firstId }.submissionError)
        assertNull(afterFirstFailure.single { it.id == secondId }.submissionError)
        assertTrue("another optimistic row still represents pending work", AgentRunUiState.state.value.running)
        assertFalse(AgentRunUiState.state.value.events.any { it.kind == "assistant" })

        AgentRunUiState.bindCurrentUserMessage(session, "second-message-id", secondId)
        val failedRows = AgentRunUiState.state.value.events.filter { it.kind == "user" }
        assertEquals("First send was stopped.", failedRows.single { it.id == firstId }.submissionError)
        assertEquals("second-message-id", failedRows.single { it.id == secondId }.messageId)
        assertNull(failedRows.single { it.id == secondId }.submissionError)
        assertFalse(AgentRunUiState.state.value.events.any { it.kind == "assistant" })
        AgentRunUiState.resetSession("$session-cleanup")
    }

    @Test
    fun failedOptimisticSubmissionDoesNotConsumeAnOlderPendingStop() {
        val session = "optimistic-stop-${System.nanoTime()}"
        AgentRunUiState.resetSession(session)
        AgentRunUiState.beginRun(session, "Existing work")
        AgentRunUiState.bindGeneration(session, 44L)
        AgentRunUiState.stopPendingGeneration(session, 44L)
        val optimisticId = AgentRunUiState.beginChatSubmission(session, "Next message", emptyList())

        assertFalse(AgentRunUiState.state.value.running)
        assertTrue(AgentRunUiState.state.value.stopping)
        AgentRunUiState.failOptimisticUserMessage(session, optimisticId, "Message stopped before start.")
        assertFalse(AgentRunUiState.state.value.running)
        assertTrue(AgentRunUiState.state.value.stopping)

        AgentRunUiState.completeGeneration(session, 44L, "old-run", "STOPPED", "Old run stopped", "old-message", 1L)
        AgentRunUiState.finishPendingGeneration(session, 44L)
        assertFalse(AgentRunUiState.state.value.stopping)
        assertEquals("STOPPED", AgentRunUiState.state.value.outcome)
        assertEquals("Message stopped before start.",
            AgentRunUiState.state.value.events.single { it.id == optimisticId }.submissionError)
        assertEquals("Old run stopped", AgentRunUiState.state.value.events.last().text)
        AgentRunUiState.resetSession("$session-cleanup")
    }
}
