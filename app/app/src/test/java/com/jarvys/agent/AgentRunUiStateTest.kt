package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunUiStateTest {
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
        val root = java.nio.file.Files.createTempDirectory(session).toFile()
        val store = LocalRunStore(root)
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
        root.deleteRecursively()
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
}
