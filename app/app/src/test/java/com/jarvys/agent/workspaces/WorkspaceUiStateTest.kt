package com.jarvys.agent.workspaces

import org.junit.Assert.*
import org.junit.Test

class WorkspaceUiStateTest {
    private val assignment = WorkspaceAssignmentUi("assignment", 7, "Research", "Review evidence", WorkspaceUiStatus.WAITING)

    @Test fun stateOwnsAnImmutableAssignmentSnapshot() {
        val source = mutableListOf(assignment)
        val state = WorkspaceDetailUiState("workspace", "Research", WorkspaceUiStatus.WAITING, source)
        source.clear()
        assertEquals(listOf(assignment), state.assignments)
        assertThrows(UnsupportedOperationException::class.java) {
            (state.assignments as MutableList<WorkspaceAssignmentUi>).clear()
        }
        assertFalse(assignment.canCorrect)
        assertFalse(assignment.canPause)
        assertFalse(assignment.canResume)
    }

    @Test fun duplicateIdsAndInvalidTargetsCannotBecomeLazyRows() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceDetailUiState("workspace", "Research", WorkspaceUiStatus.ACTIVE, listOf(assignment, assignment.copy(revision = 8)))
        }
        assertThrows(IllegalArgumentException::class.java) { assignment.copy(id = "") }
        assertThrows(IllegalArgumentException::class.java) { assignment.copy(revision = -1) }
    }

    @Test fun centralAndExecutionSessionsAreReservedButLegacySystemChatsStayIndependent() {
        assertTrue(isWorkspaceCoordinationSession(WorkspaceIdentity.CENTRAL_SESSION))
        assertTrue(isWorkspaceCoordinationSession(WorkspaceIdentity.executionSessionId("11111111-1111-1111-1111-111111111111")))
        listOf("ordinary", "jarvys-proactive", "jarvys-tasks", "jarvys-workspace-not-an-id").forEach {
            assertFalse(it, isWorkspaceCoordinationSession(it))
        }
    }

    @Test fun missingEvidenceHasAFallbackAndUntrustedTextHasABound() {
        assertEquals("Unknown", workspaceDisplayText(null, "Unknown"))
        assertEquals("Unknown", workspaceDisplayText("   ", "Unknown"))
        assertEquals("<b>Raw</b>", workspaceDisplayText("<b>Raw</b>", "Unknown"))
        assertEquals(4097, workspaceDisplayText("a".repeat(10000), "Unknown").length)
    }
}
