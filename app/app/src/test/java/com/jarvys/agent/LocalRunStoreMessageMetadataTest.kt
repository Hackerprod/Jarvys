package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalRunStoreMessageMetadataTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    @Test fun messageDurationAndTranslationSurviveTimelineRestoreWithoutEnteringModelContext() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "footer-test-${System.nanoTime()}"
        store.appendConversationMessage(session, "user", "Explain this")
        val id = store.appendConversationMessage(session, "assistant", "A concise answer", 850L, "", "")
        store.appendAssistantTranslation(session, id, "Spanish", "Una respuesta concisa")

        val timeline = store.readConversationTimeline(session)
        val answer = timeline.first { it.kind == "assistant" }
        val translation = timeline.first { it.kind == "assistant_translation" }
        assertEquals(id, answer.messageId)
        assertEquals(850L, answer.durationMs)
        val assistantRow = store.readConversationMessages(session).first { it.optString("role") == "assistant" }
        assertFalse(assistantRow.has("promptTokens"))
        assertFalse(assistantRow.has("completionTokens"))
        assertFalse(assistantRow.has("cachedTokens"))
        assertFalse(assistantRow.has("totalTokens"))
        assertEquals(id, translation.detail)
        assertEquals("Una respuesta concisa", translation.text)
        assertEquals(2, store.loadConversationContext(session).size)
        assertFalse(store.loadConversationContext(session).any { it.content.contains("Una respuesta concisa") })
        assertTrue(store.readConversationMessages(session).all { it.optString("role") != "assistant_translation" })
    }

    @Test fun regenerationReplacesOldAnswerAndTranslationWithoutDuplicatingUserTurn() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "regen-${System.nanoTime()}"
        val userId = store.appendConversationMessage(session, "user", "Do the task")
        val oldId = store.appendConversationMessage(session, "assistant", "Old answer", null, "old-run", userId)
        store.appendAssistantTranslation(session, oldId, "Spanish", "Respuesta anterior")

        store.appendAssistantRegenerated(session, oldId)
        val prompt = store.findUserMessageBeforeAssistant(session, oldId)
        assertEquals("Do the task", prompt?.text)
        assertEquals(userId, prompt?.userMessageId)
        assertTrue(store.loadConversationContext(session).none { it.content == "Old answer" })
        assertEquals(1, store.loadConversationContext(session).count { it.role == "user" })
        assertTrue(store.readConversationTimeline(session).none {
            it.messageId == oldId || it.kind == "assistant_translation"
        })

        store.appendConversationMessage(session, "assistant", "Replacement answer", 100L, "new-run", userId)
        val context = store.loadConversationContext(session)
        assertEquals(1, context.count { it.role == "user" })
        assertFalse(context.any { it.content == "Old answer" })
        assertTrue(context.any { it.content == "Replacement answer" })
    }

    @Test fun regenerationInvalidatesPriorCompactionButKeepsRawIndexBoundaryForNewCheckpoint() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "regen-compact-${System.nanoTime()}"
        store.appendConversationMessage(session, "user", "first request")
        store.appendConversationMessage(session, "assistant", "first answer")
        store.appendConversationMessage(session, "user", "second request")
        val oldId = store.appendConversationMessage(session, "assistant", "old answer in stale summary")
        store.appendCompaction(session, "summary mentions old answer in stale summary", 4, "manual", "all", 4)

        store.appendAssistantRegenerated(session, oldId)
        val rebuilt = store.loadConversationContext(session)
        assertFalse(rebuilt.any { it.content.contains("stale summary") || it.content == "old answer in stale summary" })
        assertEquals(2, rebuilt.count { it.role == "user" })
        assertEquals(1, rebuilt.count { it.content == "first answer" })

        store.appendCompaction(session, "clean summary without old answer", 2, "auto_preflight", "sliding_window", 2)
        val compactedAgain = store.loadConversationContext(session)
        assertTrue(compactedAgain.any { it.content.contains("clean summary without old answer") })
        assertFalse(compactedAgain.any { it.content.contains("old answer in stale summary") })
    }

    @Test fun legacySessionsWithoutMessageIdsOrReplacementRecordsKeepTheirMessages() {
        val root = temporaryFolder.newFolder()
        val store = LocalRunStore(root)
        val file = File(root, "jarvys/conversations/legacy.jsonl")
        file.writeText("""{"role":"user","content":"legacy prompt","timestamp":1}
{"role":"assistant","content":"legacy answer","timestamp":2}
""")

        assertEquals(listOf("legacy prompt", "legacy answer"), store.loadConversationContext("legacy").map { it.content })
        assertEquals(2, store.readConversationTimeline("legacy").size)
    }

    @Test fun legacyAssistantRowsWithTokenFieldsAreReadAsMessagesAndMetricsAreIgnored() {
        val root = temporaryFolder.newFolder()
        val store = LocalRunStore(root)
        val session = "legacy-token-fields"
        val file = File(root, "jarvys/conversations/$session.jsonl")
        file.parentFile?.mkdirs()
        file.writeText("""{"role":"assistant","content":"historic answer","messageId":"old-id","timestamp":2,"promptTokens":6400,"completionTokens":28,"cachedTokens":0,"totalTokens":6428,"durationMs":930,"status":"COMPLETED"}
""")

        val event = store.readConversationTimeline(session).single()
        assertEquals("assistant", event.kind)
        assertEquals("historic answer", event.text)
        assertNull(event.stage)
        assertEquals(930L, event.durationMs)
        assertFalse(event.toString().contains("promptTokens"))
        assertFalse(event.toString().contains("6428"))
        assertEquals(listOf("historic answer"), store.loadConversationContext(session).map { it.content })
    }

    @Test fun deletedAssistantIsTombstonedFromContextAndTimeline() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "delete-${System.nanoTime()}"
        store.appendConversationMessage(session, "user", "Keep the question")
        val answerId = store.appendConversationMessage(session, "assistant", "Hide this answer")
        store.appendAssistantTranslation(session, answerId, "Spanish", "Ocultar esta respuesta")

        store.appendAssistantDeleted(session, answerId)

        assertEquals(listOf("Keep the question"), store.loadConversationContext(session).map { it.content })
        assertFalse(store.readConversationTimeline(session).any { it.messageId == answerId })
        assertFalse(store.readConversationTimeline(session).any { it.kind == "assistant_translation" })
    }

    @Test fun translationCanBeHiddenAndLaterReplacedUsingAppendOnlyRecords() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "translation-hide-${System.nanoTime()}"
        store.appendConversationMessage(session, "user", "Translate this")
        val answerId = store.appendConversationMessage(session, "assistant", "Hello")
        store.appendAssistantTranslation(session, answerId, "Spanish", "Hola")
        store.appendAssistantTranslationHidden(session, answerId)

        assertFalse(store.readConversationTimeline(session).any { it.kind == "assistant_translation" })
        store.appendAssistantTranslation(session, answerId, "French", "Bonjour")
        val visible = store.readConversationTimeline(session).filter { it.kind == "assistant_translation" }
        assertEquals(1, visible.size)
        assertEquals("Bonjour", visible.single().text)
    }

    @Test fun completedOutcomeHasNoStageLiveOrRestoredWhileOtherOutcomesKeepTheirStage() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "assistant-outcome-${System.nanoTime()}"
        val outcomes = listOf("COMPLETED", "STOPPED", "FAILED", "PARTIAL")
        outcomes.forEachIndexed { index, outcome ->
            store.appendConversationMessage(session, "assistant", "answer-$outcome", 0L,
                "run-$index", "user-$index", outcome)
        }
        val restored = store.readConversationTimeline(session).filter { it.kind == "assistant" }
        assertEquals(listOf(null, "STOPPED", "FAILED", "PARTIAL"), restored.map { it.stage })

        outcomes.forEachIndexed { index, outcome ->
            val liveSession = "$session-live-$index"
            AgentRunUiState.resetSession(liveSession)
            AgentRunUiState.beginRun(liveSession, "goal")
            AgentRunUiState.complete("run-$index", outcome, "answer")
            assertEquals(AgentRunUiEvent.assistantStageForOutcome(outcome),
                AgentRunUiState.state.value.events.last { it.kind == "assistant" }.stage)
        }
        AgentRunUiState.resetSession("$session-cleanup")
    }

    @Test fun restoredToolRowsStayBetweenTheirUserAndAssistantAndCarryOnlySafeMetadata() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "restored-tools-${System.nanoTime()}"
        val userId = store.appendConversationMessage(session, "user", "Send this message")
        store.appendReflectionToolEvent(session, userId, "Telegram Send Message", "connector:telegram",
            "tool_result", "telegram-call")
        store.appendReflectionToolEvent(session, userId, "Telegram Send Message", "connector:telegram",
            "tool_error", "telegram-error")
        store.appendConversationMessage(session, "assistant", "Message sent", 0L, "run", userId, "COMPLETED")

        val timeline = store.readConversationTimeline(session)

        assertEquals(listOf("user", "tool", "tool", "assistant"), timeline.map { it.kind })
        assertEquals(listOf("tool_result", "tool_error"), timeline.filter { it.kind == "tool" }.map { it.stage })
        assertEquals(listOf("Telegram Send Message", "Telegram Send Message"),
            timeline.filter { it.kind == "tool" }.map { it.toolDisplayName })
        assertEquals(listOf("telegram-call", "telegram-error"),
            timeline.filter { it.kind == "tool" }.map { it.toolCallId })
        assertEquals(listOf(R.string.connector_tool_used, R.string.connector_tool_failed),
            timeline.filter { it.kind == "tool" }.map { it.toolStatusResourceId })
        assertTrue(timeline.filter { it.kind == "tool" }.all { it.detail == null && it.previewId == null })
    }

    @Test fun regeneratedOrDeletedAssistantTurnsDoNotLeaveTheirToolRowsInTimeline() {
        val store = LocalRunStore(temporaryFolder.newFolder())
        val session = "invalidated-tools-${System.nanoTime()}"
        val regeneratedUser = store.appendConversationMessage(session, "user", "Try again")
        store.appendReflectionToolEvent(session, regeneratedUser, "Old tool", "mcp", "tool_result", "old-call")
        val oldAssistant = store.appendConversationMessage(session, "assistant", "Old response", 0L,
            "old-run", regeneratedUser, "COMPLETED")
        store.appendAssistantRegenerated(session, oldAssistant)
        store.appendReflectionToolEvent(session, regeneratedUser, "Replacement tool", "mcp", "tool_result", "new-call")
        store.appendConversationMessage(session, "assistant", "Replacement response", 0L,
            "new-run", regeneratedUser, "COMPLETED")

        val deletedUser = store.appendConversationMessage(session, "user", "Remove this turn")
        store.appendReflectionToolEvent(session, deletedUser, "Deleted tool", "mcp", "tool_result", "deleted-call")
        val deletedAssistant = store.appendConversationMessage(session, "assistant", "Deleted response", 0L,
            "deleted-run", deletedUser, "COMPLETED")
        store.appendAssistantDeleted(session, deletedAssistant)

        val timeline = store.readConversationTimeline(session)
        val tools = timeline.filter { it.kind == "tool" }
        assertEquals(listOf("new-call"), tools.map { it.toolCallId })
        assertEquals(listOf("Replacement tool"), tools.map { it.toolDisplayName })
        assertTrue(timeline.none { it.messageId == oldAssistant || it.messageId == deletedAssistant })
    }
}
