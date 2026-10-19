package com.jarvys.agent.workspaces

import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.ToolActivity
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class WorkspaceChatProjectionTest {
    @Test fun chatProjectionUsesTheRegistryWorkspaceIdentityAndKeepsTimelineOrder() {
        val workspaceId = "11111111-1111-1111-1111-111111111111"
        val events = listOf(
            AgentRunUiEvent(1L, "user", text = "Worker question"),
            AgentRunUiEvent.toolEvent(2L, "tool_result", "Read file", "Read the index", "read-1", null, 2L),
            AgentRunUiEvent(3L, "assistant", text = "Worker answer"),
        )
        val detail = WorkspaceDetailUiState(workspaceId, "Workspace from registry", WorkspaceUiStatus.ACTIVE)

        val projected = workspaceChatUiState(detail, events)

        assertEquals(workspaceId, projected.workspaceId)
        assertEquals(WorkspaceIdentity.executionSessionId(workspaceId), projected.executionSessionId)
        assertEquals(detail.title, projected.title)
        assertEquals(detail.status, projected.status)
        assertEquals(events, projected.events)
        assertNotSame(events, projected.events)
    }

    @Test fun crewSnapshotsBecomeStableConversationRowsAndHideDispatchAndLedgerEvidence() {
        val workspaceId = "11111111-1111-1111-1111-111111111111"
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val bot = CrewBotSnapshot("worker", "explorer", "Explorer", "Scout", "blue", "internal task",
            "RUNNING", "", "", "", emptyList(), 1L, 0L)
        val call = ToolActivity("activity-call", "execution", "call", "read", "Read file", "", "",
            "tool_call", "started", "", "", "", 140L)
        val result = ToolActivity("activity-result", "execution", "call", "read", "Read file", "", "",
            "tool_result", "{\"ok\":true}", "", "", "", 150L)
        val messages = listOf(
            CrewMessage("user-1", sessionId, "user", "worker", CrewMessage.Type.USER, "Please inspect this", emptyList(), 100L),
            CrewMessage("dispatch", sessionId, "chief", "worker", CrewMessage.Type.TASK, "Private dispatch", emptyList(), 110L),
            CrewMessage("private", sessionId, "worker", "chief", CrewMessage.Type.QUESTION, "Worker coordination", emptyList(), 120L)
                .withCoordination(CrewCoordinationEvidence.create(sessionId, "mission", "worker",
                    "00000000-0000-0000-0000-000000000001", "private", "QUESTION", "Worker coordination", emptyList(), 120L)),
            CrewMessage("progress", sessionId, "worker", "user", CrewMessage.Type.PROGRESS,
                "Checking the source documents", emptyList(), 125L),
            CrewMessage("visible-bot", sessionId, "worker", "user", CrewMessage.Type.ANSWER, "Here is the update", emptyList(), 130L),
            CrewMessage("visible-finding-z", sessionId, "worker", "chief", CrewMessage.Type.FINDING, "Useful finding", emptyList(), 135L),
            CrewMessage("visible-finding-a", sessionId, "worker", "chief", CrewMessage.Type.FINDING, "Next finding", emptyList(), 135L),
            CrewMessage("visible-result", sessionId, "worker", "chief", CrewMessage.Type.RESULT, "Worker result", emptyList(), 155L),
            CrewMessage("call-row", sessionId, "worker", "worker", CrewMessage.Type.STATUS, "Read file", emptyList(), 140L, call),
            CrewMessage("result-row", sessionId, "worker", "worker", CrewMessage.Type.STATUS, "Read file", emptyList(), 150L, result),
            CrewMessage("chief-answer", sessionId, "chief", "user", CrewMessage.Type.ANSWER, "Chief update", emptyList(), 157L),
        )
        val snapshot = CrewMissionSnapshot("mission", sessionId, "process", "Workspace task", "RUNNING",
            "Final synthesis", 90L, 170L, listOf(bot), messages)
        val ledgerArtifact = AgentRunUiEvent(3L, "artifact", text = "Deliverable", timestampMillis = 160L)
        val ledgerEvidence = AgentRunUiEvent(4L, "workspace_result", text = "Workspace result recorded", timestampMillis = 165L)
        val agentInput = AgentRunUiEvent(5L, "user", text = "Agent input", timestampMillis = 125L)
            .copy(conversationInputOrigin = ConversationInput.Origin.AGENT)
        val systemInput = AgentRunUiEvent(6L, "assistant", text = "Internal system input", timestampMillis = 126L)
            .copy(conversationInputOrigin = ConversationInput.Origin.SYSTEM)
        val workerAnswer = AgentRunUiEvent(7L, "assistant", text = "Worker final answer", timestampMillis = 127L)
        val workerStream = AgentRunUiEvent(8L, "assistant_stream", text = "Worker partial answer", timestampMillis = 128L)
        val raw = listOf(AgentRunUiEvent.crewMissionEvent(2L, snapshot), agentInput, systemInput,
            workerAnswer, workerStream, ledgerArtifact, ledgerEvidence)

        val projected = WorkspaceTimelineProjection.project(workspaceId, raw)

        assertEquals(listOf("user", "assistant", "tool", "artifact"),
            projected.map { it.kind })
        assertEquals(listOf("Please inspect this", "Checking the source documents", "Read file", "Deliverable"),
            projected.map { it.text })
        assertEquals("PROGRESS", projected[1].stage)
        assertEquals(false, projected[1].canRegenerate)
        assertEquals("Scout", projected[1].workspaceSenderLabel)
        assertEquals("tool_result", projected[2].stage)
        assertEquals("execution", projected[2].toolCallId)
        assertEquals("{\"ok\":true}", projected[2].detail)
        assertEquals("user-1", projected[0].messageId)
        assertEquals("progress", projected[1].messageId)
        assertEquals("call-row", projected[2].messageId)
        assertEquals(false, projected.any { it.kind == "crew_mission" || it.kind == "workspace_result" })
        assertEquals(false, projected.any { it.text in setOf(
            "Private dispatch", "Worker coordination", "Here is the update", "Useful finding", "Next finding",
            "Worker result", "Chief update", "Final synthesis", "Agent input", "Internal system input",
            "Worker final answer", "Worker partial answer",
        ) })

        val resultMatchesSynthesis = CrewMessage("matching-result", sessionId, "worker", "chief",
            CrewMessage.Type.RESULT, "Final synthesis", emptyList(), 160L)
        val duplicateSynthesisSnapshot = CrewMissionSnapshot("mission-dedupe", sessionId, "process",
            "Workspace task", "DONE", "Final synthesis", 90L, 170L, listOf(bot), listOf(resultMatchesSynthesis))
        val deduplicated = WorkspaceTimelineProjection.project(workspaceId,
            listOf(AgentRunUiEvent.crewMissionEvent(1L, duplicateSynthesisSnapshot)))
        assertEquals(emptyList<AgentRunUiEvent>(), deduplicated)

        val refreshed = WorkspaceTimelineProjection.project(workspaceId,
            listOf(AgentRunUiEvent.crewMissionEvent(9L, CrewMissionSnapshot("mission", sessionId, "process",
                "Workspace task", "DONE", "Final synthesis", 90L, 170L, listOf(bot), messages)), ledgerArtifact))
        val stableKeys = projected.filter { it.messageId.isNotBlank() }.associate { it.messageId to it.timelineKey }
        assertEquals(stableKeys, refreshed.filter { it.messageId.isNotBlank() }.associate { it.messageId to it.timelineKey })
    }

    @Test fun unfinishedCrewActivityKeepsRunningStateOnlyForAnActiveBot() {
        val workspaceId = "11111111-1111-1111-1111-111111111111"
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val bot = CrewBotSnapshot("worker", "explorer", "Explorer", "Scout", "blue", "task",
            "RUNNING", "", "", "", emptyList(), 1L, 0L)
        val activity = ToolActivity("activity-call", "execution", "call", "read", "Read file", "", "",
            "tool_call", "started", "", "", "", 140L)
        val row = CrewMessage("call-row", sessionId, "worker", "worker", CrewMessage.Type.STATUS,
            "Read file", emptyList(), 140L, activity)
        val active = CrewMissionSnapshot("mission", sessionId, "process", "Workspace task", "RUNNING", "",
            90L, 0L, listOf(bot), listOf(row))
        val completedBot = CrewBotSnapshot("worker", "explorer", "Explorer", "Scout", "blue", "task",
            "DONE", "", "", "", emptyList(), 1L, 200L)
        val historical = CrewMissionSnapshot("mission", sessionId, "process", "Workspace task", "DONE", "",
            90L, 200L, listOf(completedBot), listOf(row))

        val activeEvent = WorkspaceTimelineProjection.project(workspaceId,
            listOf(AgentRunUiEvent.crewMissionEvent(1L, active))).single()
        val historicalEvent = WorkspaceTimelineProjection.project(workspaceId,
            listOf(AgentRunUiEvent.crewMissionEvent(1L, historical))).single()

        assertEquals("tool_call", activeEvent.stage)
        assertEquals("tool_interrupted", historicalEvent.stage)
        assertEquals(activeEvent.timelineKey, historicalEvent.timelineKey)
    }
}
