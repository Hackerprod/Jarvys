package com.jarvys.agent

import com.jarvys.agent.ui.chat.ConversationVisibility
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class ConversationVisibilityTest {
    @get:org.junit.Rule val chatTestStore = ChatTestStore()

    private fun activity(name: String, stage: String, execution: String = name) = ToolActivity(
        "$execution/$stage", execution, execution, name, "display", "", "", stage,
        "evidence", "", "audit", "", 1L,
    )

    @Test fun internalPhasesStayHiddenAndAssistantPreamblesAreOptedIntoByMainChat() {
        val phases = listOf("thinking", "model_wait", "model_response", "answer", "compacting",
            "perception", "operator", "device_use", "validator", "summarizer")
        val interim = AgentRunUiEvent(20L, "assistant", "PROGRESS", "interim update")
        val evidence = phases.mapIndexed { i, stage -> AgentRunUiEvent(i.toLong(), "progress", stage, "internal") } + interim

        // The default projection hides interim updates; callers opt in per timeline.
        assertTrue(ConversationVisibility.project(evidence).isEmpty())
        assertEquals(listOf(interim), ConversationVisibility.project(evidence, showAssistantProgress = true))
        assertEquals(11, evidence.size)
        assertEquals("interim update", evidence.last().text)
    }

    @Test fun onlyMemorySeedIsHiddenWhileExistingMemoryAndOtherSeedKindsRemain() {
        val seed = AgentRunUiEvent(1L, "memory", "SEED", "Internal initial seed")
        val existing = listOf(
            AgentRunUiEvent(2L, "memory", "WRITE", "Saved preference"),
            AgentRunUiEvent(3L, "memory", "EDIT", "Updated preference"),
            AgentRunUiEvent(4L, "memory", "DELETE", "Removed preference"),
            AgentRunUiEvent(5L, "memory", null, "Memory note"),
            AgentRunUiEvent(6L, "memory", "seed", "Non-SEED stage"),
            AgentRunUiEvent(7L, "user", "SEED", "User text stays"),
            AgentRunUiEvent(8L, "assistant", "SEED", "Answer stays"),
        )
        val evidence = listOf(seed) + existing
        assertEquals(existing, ConversationVisibility.project(evidence))
        assertEquals(seed, evidence.first())
        assertEquals(8, evidence.size)
    }

    @Test fun errorsFinalAnswersDecisionsAndUsefulToolResultsStayVisible() {
        val events = listOf(
            AgentRunUiEvent(1L, "user", null, "model_wait"),
            AgentRunUiEvent(2L, "assistant", null, "answer"),
            AgentRunUiEvent(3L, "assistant", "PARTIAL", "actionable partial result"),
            AgentRunUiEvent(4L, "progress", "compaction_error", "actionable error"),
            AgentRunUiEvent(5L, "approval", null, "decision"),
            AgentRunUiEvent(6L, "workspace_result", null, "result"),
            AgentRunUiEvent.deliveredFileEvent(7L,
                ChatAttachment("11111111-2222-3333-4444-555555555555", "file.txt", "text/plain", 4L,
                    ChatAttachment.Kind.FILE, "11111111-2222-3333-4444-555555555555-file.txt"), 0L),
            AgentRunUiEvent.activityEvent(8L, activity("app_diagnostics", "tool_result")),
            AgentRunUiEvent.activityEvent(9L, activity("write_file", "tool_result")),
            AgentRunUiEvent.toolEvent(10L, "tool_result", "workspaces", "legacy unknown identity", null, null, 1L),
        )
        assertEquals(events.filterNot { it.kind == "workspace_result" }, ConversationVisibility.project(events))
        for (stage in listOf("tool_error", "tool_interrupted", "tool_not_started")) {
            assertTrue(ConversationVisibility.visible(AgentRunUiEvent.activityEvent(11L, activity("workspaces", stage))))
        }
    }

    @Test fun persistedAgentAndSystemInputsCarryOriginAndTheChatProjectionHidesThem() {
        val session = "durable-internal-origin"
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
        val userId = store.appendConversationInput(ConversationInput(
            session, "source-user", "Human request", ConversationInput.Origin.USER, null,
        ))
        val agentId = store.appendConversationInput(ConversationInput(
            session, "source-agent", "Worker asks to approve X", ConversationInput.Origin.AGENT,
            ConversationInput.Provenance("worker-session", "workspace-1", "assignment-1", "bot-1",
                "source-agent", ConversationInput.Kind.QUESTION),
        ))
        val systemId = store.appendConversationInput(ConversationInput(
            session, "source-system", "Internal delivery state", ConversationInput.Origin.SYSTEM,
            ConversationInput.Provenance("system-session", "workspace-1", "assignment-1", "", "source-system",
                ConversationInput.Kind.STATUS),
        ))
        val answerId = store.appendConversationMessage(session, "assistant", "I checked the worker's report")

        assertEquals(ConversationInput.Origin.AGENT, store.readConversationInput(session, agentId)!!.origin)
        assertEquals(ConversationInput.Origin.SYSTEM, store.readConversationInput(session, systemId)!!.origin)
        val timeline = store.readConversationTimeline(session)
        val agent = timeline.single { it.messageId == agentId }
        val system = timeline.single { it.messageId == systemId }
        assertEquals("agent", agent.kind)
        assertEquals(ConversationInput.Origin.AGENT, agent.conversationInputOrigin)
        assertEquals("system", system.kind)
        assertEquals(ConversationInput.Origin.SYSTEM, system.conversationInputOrigin)

        val mainChat = ConversationVisibility.project(timeline)
        assertFalse(mainChat.any { it.messageId == agentId || it.messageId == systemId })
        assertTrue(mainChat.any { it.messageId == userId })
        assertTrue("a Central assistant response is a separate visible event", mainChat.any { it.messageId == answerId })
        assertFalse(ConversationVisibility.visible(agent))
        assertFalse(ConversationVisibility.visible(system))
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
        val root = chatTestStore.newFilesDirectory()
        val store = chatTestStore.localRunStore(root)
        val session = "quiet-reopen"
        val user = store.appendConversationMessage(session, "user", "Create an app")
        store.appendToolActivity(session, user, activity("workspaces", "tool_result", "success"))
        store.appendToolActivity(session, user, activity("workspaces", "tool_error", "failure"))
        store.appendToolActivity(session, user, activity("app_diagnostics", "tool_result", "diagnostics"))
        store.appendConversationMessage(session, "assistant", "The workspace needs attention")
        val reopened = chatTestStore.localRunStore(root).readConversationTimeline(session)
        val storedBefore = root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).path to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        val visible = ConversationVisibility.project(reopened)
        assertEquals(3, reopened.count { it.kind == "tool" })
        assertEquals(2, visible.count { it.kind == "tool" })
        assertTrue(visible.any { it.toolActivity?.toolName == "workspaces" && it.stage == "tool_error" })
        val again = chatTestStore.localRunStore(root).readConversationTimeline(session)
        assertEquals(reopened.map { it.toolActivity?.toJson()?.toString() }, again.map { it.toolActivity?.toJson()?.toString() })
        assertEquals(reopened.map { Triple(it.kind, it.stage, it.text) }, again.map { Triple(it.kind, it.stage, it.text) })
        assertEquals(storedBefore, root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).path to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        })
    }

    @Test fun successfulResultWithMatchingDeliveredCardIsHiddenFromProjection() {
        val artifactId = "11111111-2222-3333-4444-555555555555"
        val artifact = ChatAttachment(artifactId, "index.html", "text/html", 42L,
            ChatAttachment.Kind.FILE, "$artifactId-index.html")
        val card = AgentRunUiEvent.deliveredFileEvent(12L, artifact, 2L)
        val previewResult = AgentRunUiEvent.toolEvent(11L, "tool_result", "Build output",
            null, "call-11",
            HtmlPreviewDescriptor.TOKEN_PREFIX + artifactId, 1L)
        val artifactResult = AgentRunUiEvent.toolEvent(13L, "tool_result", "Build output",
            "{\"artifact_id\":\"delivered:$artifactId\"}", "call-13", null, 1L)

        assertEquals(listOf(card), ConversationVisibility.project(listOf(previewResult, artifactResult, card)))
    }

    @Test fun successfulResultWithoutMatchingCardStaysVisible() {
        val artifactId = "11111111-2222-3333-4444-555555555555"
        val result = AgentRunUiEvent.toolEvent(11L, "tool_result", "Build output",
            "{\"artifact_id\":\"delivered:$artifactId\"}", "call-11",
            HtmlPreviewDescriptor.TOKEN_PREFIX + artifactId, 1L)
        val unrelatedCard = AgentRunUiEvent.deliveredFileEvent(12L, ChatAttachment(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "other.html", "text/html", 42L,
            ChatAttachment.Kind.FILE, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-other.html"), 2L)

        assertEquals(listOf(result, unrelatedCard), ConversationVisibility.project(listOf(result, unrelatedCard)))
    }

    @Test fun failuresAndInFlightToolRowsStayVisibleEvenWhenMatchingCardExists() {
        val artifactId = "11111111-2222-3333-4444-555555555555"
        val artifact = ChatAttachment(artifactId, "index.html", "text/html", 42L,
            ChatAttachment.Kind.FILE, "$artifactId-index.html")
        val card = AgentRunUiEvent.deliveredFileEvent(13L, artifact, 3L)
        val matchingPreview = HtmlPreviewDescriptor.TOKEN_PREFIX + artifactId
        val error = AgentRunUiEvent.toolEvent(10L, "tool_error", "Build output",
            "{\"artifact_id\":\"delivered:$artifactId\"}", "call-error", matchingPreview, 1L)
        val starting = AgentRunUiEvent.toolEvent(11L, "tool_call", "Build output",
            null, "call-starting", matchingPreview, 2L)
        val inFlight = AgentRunUiEvent.toolEvent(12L, "tool_progress", "Build output",
            null, "call-progress", matchingPreview, 2L)

        assertEquals(listOf(error, starting, inFlight, card),
            ConversationVisibility.project(listOf(error, starting, inFlight, card)))
        for (stage in listOf("tool_interrupted", "tool_not_started")) {
            val terminalUncertainty = AgentRunUiEvent.toolEvent(12L, stage, "Build output",
                "{\"artifact_id\":\"delivered:$artifactId\"}", "call-$stage", matchingPreview, 2L)
            assertTrue(ConversationVisibility.project(listOf(terminalUncertainty, card)).contains(terminalUncertainty))
        }
    }

    @Test fun generatedImageResultIsSuppressedOnlyWhenItsMatchingCardExists() {
        val imageId = "11111111-2222-3333-4444-555555555555"
        val card = AgentRunUiEvent.generatedImageEvent(12L, "$imageId.png", "prompt", null,
            "image/png", null, "COMPLETED", null, 2L)
        val result = AgentRunUiEvent.toolEvent(11L, "tool_result", "Image operation",
            "Image added. image_ref: generated:$imageId; format: PNG", "call-image", null, 1L)

        assertEquals(listOf(card), ConversationVisibility.project(listOf(result, card)))
    }

    @Test fun successfulScheduledTaskResultProjectsAsTaskCardAndErrorRemainsToolRow() {
        val result = AgentRunUiEvent.toolEvent(1L, "tool_result", "Task management",
            "Task management · " + """{"status":"scheduled","task":{"kind":"scheduled_task","id":"task-1","name":"Morning brief","schedule_human":"Every morning","next_run_local":"8:00 AM","status":"scheduled"}}""",
            "task-call", null, 1L)
        val error = AgentRunUiEvent.toolEvent(2L, "tool_error", "Task management", "task was rejected",
            "task-error", null, 1L)

        val projectedResult = ConversationVisibility.project(listOf(result))
        assertEquals(1, projectedResult.size)
        assertEquals("scheduled_task", projectedResult.single().kind)
        assertEquals("task-1", projectedResult.single().scheduledTaskArtifact?.taskId)
        assertEquals(listOf(error), ConversationVisibility.project(listOf(error)))
    }

    @Test fun toolResultDetailIsAnalyzedOncePerEventAcrossProjectionPasses() {
        val parsedDetails = AtomicInteger()
        AgentRunUiEventVisibilityDetail.parseObserver = { parsedDetails.incrementAndGet() }
        try {
            val events = (1L..120L).map { id ->
                AgentRunUiEvent.toolEvent(id, "tool_result", "tool-$id", "{\"status\":\"ok\"}",
                    "call-$id", null, id)
            }

            ConversationVisibility.project(events)
            assertEquals(events.size, parsedDetails.get())
            ConversationVisibility.project(events)
            assertEquals("reprojecting identical events must reuse their analysis", events.size, parsedDetails.get())

            val changed = events.toMutableList().also { list ->
                list[57] = list[57].copy(detail = "{\"status\":\"updated\"}")
            }
            ConversationVisibility.project(changed)
            assertEquals("a copied event with changed detail is analyzed once", events.size + 1, parsedDetails.get())
        } finally {
            AgentRunUiEventVisibilityDetail.parseObserver = null
        }
    }
}
