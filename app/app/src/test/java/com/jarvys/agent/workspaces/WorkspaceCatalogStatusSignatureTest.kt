package com.jarvys.agent.workspaces

import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewProcessIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WorkspaceCatalogStatusSignatureTest {
    @Test fun signatureIgnoresCrewPhaseButTracksVisibleBotStatus() {
        val waitingOnModel = WorkspaceCatalogStatusSignature.from(snapshot("RUNNING", "model_wait"))
        val receivingModelResponse = WorkspaceCatalogStatusSignature.from(snapshot("RUNNING", "model_response"))
        val waitingForInput = WorkspaceCatalogStatusSignature.from(snapshot("WAITING", ""))

        assertEquals(waitingOnModel, receivingModelResponse)
        assertNotEquals(waitingOnModel, waitingForInput)
        assertNotEquals(waitingOnModel, WorkspaceCatalogStatusSignature.from(
            snapshot("RUNNING", "", processId = "previous-process")))
    }

    private fun snapshot(status: String, phase: String, processId: String = CrewProcessIdentity.ID) =
        CrewMissionSnapshot("mission", "execution-session", processId, "Title", "RUNNING", "", 1L, 0L,
            listOf(bot(status, phase)), emptyList())

    private fun bot(status: String, phase: String) = CrewBotSnapshot(
        "bot-1", "writer", "Writer", "Writer", "blue", "Write", status,
        "", "", "", emptyList(), 1L, 0L, false, "", false, phase, "", 1L,
    )
}
