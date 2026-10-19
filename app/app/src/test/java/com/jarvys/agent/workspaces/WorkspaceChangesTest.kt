package com.jarvys.agent.workspaces

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class WorkspaceChangesTest {
    @Test fun repeatedCrewPhasesKeepCatalogStableUntilVisibleStatusChanges() {
        val sessionId = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString())
        val missionId = UUID.randomUUID().toString()
        val baselineSignature = "ACTIVE:writer=RUNNING"
        val detailBefore = WorkspaceChanges.revision.value
        val catalogBefore = WorkspaceChanges.catalogRevision.value

        WorkspaceChanges.observeCatalogSignature(sessionId, missionId, baselineSignature)
        assertEquals(catalogBefore, WorkspaceChanges.catalogRevision.value)
        WorkspaceChanges.snapshotSaved(sessionId, missionId, baselineSignature)
        repeat(4) {
            // Crew emits new snapshots for model/tool phases while the row still reads ACTIVE.
            WorkspaceChanges.snapshotSaved(sessionId, missionId, baselineSignature)
        }
        val detailAfterRepeatedPhases = WorkspaceChanges.revision.value
        val catalogAfterRepeatedPhases = WorkspaceChanges.catalogRevision.value

        WorkspaceChanges.snapshotSaved(sessionId, missionId, "WAITING:writer=WAITING")
        val detailAfterVisibleChange = WorkspaceChanges.revision.value
        val catalogAfterVisibleChange = WorkspaceChanges.catalogRevision.value

        assertEquals("Every phase snapshot must still refresh the workspace detail",
            detailBefore + 5, detailAfterRepeatedPhases)
        assertEquals("Every persisted snapshot must still refresh the workspace detail",
            detailBefore + 6, detailAfterVisibleChange)
        assertEquals("Repeated phase snapshots must not reload an unchanged catalog row",
            catalogBefore, catalogAfterRepeatedPhases)
        assertEquals("A changed aggregate row status must invalidate the catalog",
            catalogBefore + 1, catalogAfterVisibleChange)
    }

    @Test fun firstLiveSnapshotWithoutPersistedBaselineInvalidatesCatalog() {
        val sessionId = WorkspaceIdentity.executionSessionId(UUID.randomUUID().toString())
        val missionId = UUID.randomUUID().toString()
        val detailBefore = WorkspaceChanges.revision.value
        val catalogBefore = WorkspaceChanges.catalogRevision.value

        WorkspaceChanges.snapshotSaved(sessionId, missionId, "ACTIVE:writer=RUNNING")

        assertEquals(detailBefore + 1, WorkspaceChanges.revision.value)
        assertEquals(catalogBefore + 1, WorkspaceChanges.catalogRevision.value)
    }
}
