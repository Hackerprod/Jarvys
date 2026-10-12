package com.jarvys.agent

import com.jarvys.agent.ui.chat.ConversationVisibility
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationVisibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun activity(name: String, stage: String, execution: String = name) = ToolActivity(
        "$execution/$stage", execution, execution, name, "display", "", "", stage,
        "evidence", "", "audit", "", 1L,
    )

    @Test fun internalPhasesAndToolPreamblesAreHiddenWithoutMutatingEvidence() {
        val phases = listOf("thinking", "model_wait", "model_response", "answer", "compacting",
            "perception", "operator", "device_use", "validator", "summarizer")
        val evidence = phases.mapIndexed { i, stage -> AgentRunUiEvent(i.toLong(), "progress", stage, "internal") } +
            AgentRunUiEvent(20L, "assistant", "PROGRESS", "private coordination")
        assertTrue(ConversationVisibility.project(evidence).isEmpty())
        assertEquals(11, evidence.size)
        assertEquals("private coordination", evidence.last().text)
    }

    @Test fun errorsFinalAnswersDecisionsAndUsefulToolResultsStayVisible() {
        val events = listOf(
            AgentRunUiEvent(1L, "user", null, "model_wait"),
            AgentRunUiEvent(2L, "assistant", null, "answer"),
            AgentRunUiEvent(3L, "assistant", "PARTIAL", "actionable partial result"),
            AgentRunUiEvent(4L, "progress", "compaction_error", "actionable error"),
            AgentRunUiEvent(5L, "approval", null, "decision"),
            AgentRunUiEvent(6L, "workspace_result", null, "result"),
            AgentRunUiEvent(7L, "delivered_file", null, "file"),
            AgentRunUiEvent.activityEvent(8L, activity("app_diagnostics", "tool_result")),
            AgentRunUiEvent.activityEvent(9L, activity("write_file", "tool_result")),
            AgentRunUiEvent.toolEvent(10L, "tool_result", "workspaces", "legacy unknown identity", null, null, 1L),
        )
        assertEquals(events, ConversationVisibility.project(events))
        for (stage in listOf("tool_error", "tool_interrupted", "tool_not_started")) {
            assertTrue(ConversationVisibility.visible(AgentRunUiEvent.activityEvent(11L, activity("workspaces", stage))))
        }
    }

    @Test fun liveModelPhasesAndSuccessfulCoordinationStayOutOfProjection() {
        val session = "quiet-live"
        AgentRunUiState.resetSession(session)
        try {
            AgentRunUiState.beginRun(session, "Create an app")
            AgentRunUiState.onProgress("model_wait", "internal wait")
            AgentRunUiState.onProgress("model_response", "internal response")
            AgentRunUiState.onToolActivity(activity("workspaces", "tool_call"))
            AgentRunUiState.onToolActivity(activity("workspaces", "tool_result"))
            val evidence = AgentRunUiState.state.value.events
            assertTrue(evidence.any { it.stage == "model_wait" })
            assertTrue(evidence.any { it.toolActivity?.toolName == "workspaces" })
            assertEquals(listOf("user"), ConversationVisibility.project(evidence).map { it.kind })
            AgentRunUiState.complete("run", "COMPLETED", "Your app is ready")
            assertEquals(listOf("user", "assistant"), ConversationVisibility.project(AgentRunUiState.state.value.events).map { it.kind })
        } finally { AgentRunUiState.resetSession("quiet-cleanup") }
    }

    @Test fun reopenFiltersTypedCoordinationButKeepsDurableEvidenceAndErrors() {
        val root = temporary.newFolder()
        val store = LocalRunStore(root)
        val session = "quiet-reopen"
        val user = store.appendConversationMessage(session, "user", "Create an app")
        store.appendToolActivity(session, user, activity("workspaces", "tool_result", "success"))
        store.appendToolActivity(session, user, activity("workspaces", "tool_error", "failure"))
        store.appendToolActivity(session, user, activity("app_diagnostics", "tool_result", "diagnostics"))
        store.appendConversationMessage(session, "assistant", "The workspace needs attention")
        val reopened = LocalRunStore(root).readConversationTimeline(session)
        val storedBefore = root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).path to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        val visible = ConversationVisibility.project(reopened)
        assertEquals(3, reopened.count { it.kind == "tool" })
        assertEquals(2, visible.count { it.kind == "tool" })
        assertTrue(visible.any { it.toolActivity?.toolName == "workspaces" && it.stage == "tool_error" })
        val again = LocalRunStore(root).readConversationTimeline(session)
        assertEquals(reopened.map { it.toolActivity?.toJson()?.toString() }, again.map { it.toolActivity?.toJson()?.toString() })
        assertEquals(reopened.map { Triple(it.kind, it.stage, it.text) }, again.map { Triple(it.kind, it.stage, it.text) })
        assertEquals(storedBefore, root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).path to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        })
    }
}
