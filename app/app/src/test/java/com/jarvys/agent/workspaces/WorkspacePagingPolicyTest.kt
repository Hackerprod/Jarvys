package com.jarvys.agent.workspaces

import org.junit.Assert.*
import org.junit.Test

class WorkspacePagingPolicyTest {
    @Test fun aggregatePriorityKeepsCleanupActivityAndUncertaintyBeforeTerminalClaims() {
        assertEquals(WorkspaceUiStatus.UNKNOWN, workspaceAggregateStatus(emptyList()))
        val priority = listOf(WorkspaceUiStatus.STOPPING, WorkspaceUiStatus.CANCEL_REQUESTED,
            WorkspaceUiStatus.ACTIVE, WorkspaceUiStatus.WAITING, WorkspaceUiStatus.INTERRUPTED,
            WorkspaceUiStatus.UNKNOWN, WorkspaceUiStatus.PARTIAL, WorkspaceUiStatus.PAUSED,
            WorkspaceUiStatus.COMPLETED)
        priority.indices.forEach { index ->
            assertEquals(priority[index], workspaceAggregateStatus(priority.drop(index).reversed()))
        }
        assertEquals(WorkspaceUiStatus.UNKNOWN, workspaceAggregateStatus(listOf(WorkspaceUiStatus.COMPLETED), true))
        assertEquals(WorkspaceUiStatus.UNKNOWN, workspaceAggregateStatus(listOf(WorkspaceUiStatus.PAUSED), true))
        assertEquals(WorkspaceUiStatus.ACTIVE, workspaceAggregateStatus(listOf(WorkspaceUiStatus.ACTIVE), true))
    }

    @Test fun cursorHistoryRemainsBoundedWhileRememberingFirstPageAsAnEmptyToken() {
        var history = workspacePushCursor(emptyList(), null)
        assertEquals(listOf(""), history)
        repeat(1000) { history = workspacePushCursor(history, "cursor-$it") }
        assertEquals(WORKSPACE_CURSOR_HISTORY_LIMIT, history.size)
        assertEquals("cursor-999", history.last())
        assertEquals("cursor-968", history.first())
    }

    @Test fun sharedFilePagesRoundTripWithoutTruncationOrSplittingUnicodePairs() {
        val original = "abc😀".repeat(7000)
        var cursor: String? = null
        val output = StringBuilder()
        var pages = 0
        do {
            val page = workspaceSharedTextPage("owner", "board/file.txt", original, cursor)
            assertTrue(page.text.length <= 16000)
            assertFalse(page.text.isNotEmpty() && Character.isHighSurrogate(page.text.last()))
            assertFalse(page.text.isNotEmpty() && Character.isLowSurrogate(page.text.first()))
            output.append(page.text)
            cursor = page.nextCursor
            pages++
        } while (cursor != null)
        assertTrue(pages > 1)
        assertEquals(original, output.toString())
        assertEquals(WorkspaceSharedTextPage("", null), workspaceSharedTextPage("owner", "empty", "", null))
    }

    @Test fun sharedFileCursorRejectsDifferentOwnerPathBytesAndMalformedOffsets() {
        val original = "0123456789"
        val cursor = requireNotNull(workspaceSharedTextPage("one", "file", original, null, 4).nextCursor)
        listOf(
            { workspaceSharedTextPage("two", "file", original, cursor, 4) },
            { workspaceSharedTextPage("one", "other", original, cursor, 4) },
            { workspaceSharedTextPage("one", "file", "changed", cursor, 4) },
            { workspaceSharedTextPage("one", "file", original, cursor.substringBeforeLast('.') + ".99999", 4) },
            { workspaceSharedTextPage("one", "file", original, "bad", 4) },
        ).forEach { operation -> assertTrue(runCatching(operation).exceptionOrNull() is IllegalArgumentException) }
        assertEquals("4567", workspaceSharedTextPage("one", "file", original, cursor, 4).text)
    }

    @Test fun sidebarPageCopiesRowsAndRejectsOversizedVisiblePages() {
        val input = mutableListOf(WorkspaceSidebarRow("one", "One", WorkspaceUiStatus.ACTIVE))
        val page = WorkspaceSidebarPage(input, "cursor")
        input.clear()
        assertEquals(1, page.rows.size)
        assertTrue(runCatching { WorkspaceSidebarPage(List(51) { WorkspaceSidebarRow("$it", "$it", WorkspaceUiStatus.UNKNOWN) }) }
            .exceptionOrNull() is IllegalArgumentException)
    }
}
