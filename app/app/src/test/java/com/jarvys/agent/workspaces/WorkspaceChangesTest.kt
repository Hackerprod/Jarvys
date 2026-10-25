package com.jarvys.agent.workspaces

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class WorkspaceChangesTest {
    @Test fun everyPersistedSnapshotRefreshesTheMissionCards() {
        val sessionId = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString())
        val missionId = UUID.randomUUID().toString()
        val before = WorkspaceChanges.revision.value

        repeat(5) {
            // Crew emits new snapshots for model/tool phases; a card derives its status from each of them.
            WorkspaceChanges.snapshotSaved(sessionId, missionId)
        }

        assertEquals("Every persisted snapshot must refresh the cards that derive from it",
            before + 5, WorkspaceChanges.revision.value)
    }

    @Test fun normalEvidenceSavesDoNotInvalidateTheProjection() {
        val sessionId = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString())
        val before = WorkspaceChanges.revision.value

        repeat(3) {
            WorkspaceChanges.resultEvidenceSaved(sessionId, "mission", "result-$it")
            WorkspaceChanges.coordinationEvidenceSaved(sessionId, "mission", "coordination-$it")
        }

        assertEquals("saving new evidence alone does not change a workspace projection",
            before, WorkspaceChanges.revision.value)
    }
}
