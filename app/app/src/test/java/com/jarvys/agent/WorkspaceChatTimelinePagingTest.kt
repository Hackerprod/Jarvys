package com.jarvys.agent

import android.app.Application
import com.jarvys.agent.workspaces.WorkspaceChatUiState
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceProjection
import com.jarvys.agent.workspaces.WorkspaceUiStatus
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceChatTimelinePagingTest {
    @Test fun chatUsesCachedTailPageAndOlderWorkspacePagesJoinWithoutGapsOrDuplicates() {
        val context = RuntimeEnvironment.getApplication()
        val workspaceId = UUID.randomUUID().toString()
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val directory = File(File(context.filesDir, "jarvys"), "conversations").apply { mkdirs() }
        val ledger = File(directory, "$sessionId.jsonl")
        val filler = "x".repeat(900)
        val totalTurns = 30_000
        BufferedWriter(OutputStreamWriter(ledger.outputStream(), StandardCharsets.UTF_8)).use { writer ->
            repeat(totalTurns) { index ->
                writer.write("{\"role\":\"user\",\"messageId\":\"user-$index\",\"content\":\"$filler\"}\n")
            }
        }
        assertTrue("fixture must model a large on-device conversation", ledger.length() >= 20L * 1024L * 1024L)
        val cached = WorkspaceChatUiState(workspaceId, sessionId, "Cached workspace", WorkspaceUiStatus.ACTIVE)
        LocalRunStore.resetHotPathMetricsForTests()
        val pagerCallsBefore = LocalRunStore.timelinePagePagerCallCountForTests()

        val newest = WorkspaceProjection.chat(context, workspaceId, cached)

        assertEquals("Cached workspace", newest.title)
        assertTrue(newest.hasOlderHistory)
        assertEquals(LocalRunStore.INITIAL_TIMELINE_TURNS, newest.events.size)
        assertEquals(pagerCallsBefore + 1L, LocalRunStore.timelinePagePagerCallCountForTests())
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests())
        assertEquals(0L, LocalRunStore.crewSnapshotRowsParsedCountForTests())

        val refreshed = WorkspaceProjection.chat(context, workspaceId, newest)
        assertEquals("an identical refresh reuses the cached timeline page", pagerCallsBefore + 1L,
            LocalRunStore.timelinePagePagerCallCountForTests())

        val older = LocalRunStore(context).readConversationTimelinePage(
            sessionId, refreshed.olderHistoryCursor, LocalRunStore.OLDER_TIMELINE_TURNS,
            refreshed.invalidatedToolTurnMessageIds, refreshed.projectionMarkers)
        assertTrue(older.hasOlder)
        assertEquals(LocalRunStore.OLDER_TIMELINE_TURNS, older.events.size)
        val combined = older.events + refreshed.events
        val ids = combined.map { it.messageId }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals((totalTurns - combined.size until totalTurns).map { "user-$it" },
            combined.map { it.messageId })
        assertFalse(combined.zipWithNext().any { (first, second) -> first.messageId == second.messageId })
    }
}
