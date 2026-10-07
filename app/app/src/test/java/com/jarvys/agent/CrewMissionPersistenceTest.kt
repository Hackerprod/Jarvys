package com.jarvys.agent

import com.jarvys.agent.crew.CrewBoard
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrewMissionPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun legacyConversationMigratesAdditivelyAndActiveBotsRecoverAsInterrupted() {
        val store = LocalRunStore(temp.root)
        val session = "crew-persist-session"
        store.appendConversationMessage(session, "user", "Compare these sources")
        val oldMessages = store.readConversationMessages(session).map { it.optString("content") }
        assertTrue(store.readCrewMissionSnapshots(session).isEmpty())

        val projectId = WorkspaceStore.projectIdForSession(session)
        val board = CrewBoard(WorkspaceStore(temp.newFolder("workspace"), projectId))
        board.post("/board/source.md", "Primary source notes")
        val bot = CrewBotSnapshot("bot-1", "analista", "Analista", "Ada", "amber",
            "Check source dates", "WAITING", "", "", "respuesta del capitán",
            listOf("board_read", "board_post"), 100L, 0L)
        val question = CrewMessage("message-1", session, "user", "bot-1", CrewMessage.Type.USER,
            "Use the current source", listOf("/board/source.md"), 200L)
        val snapshot = CrewMissionSnapshot("mission-1", session, "dead-process", "Compare these sources",
            "RUNNING", "", 100L, 0L, listOf(bot), listOf(question))
        store.appendCrewMissionSnapshot(snapshot)
        val ledger = File(temp.root, "jarvys/conversations/$session.jsonl").readText()
        assertTrue(ledger.contains("\"crewSchemaVersion\":1"))
        assertFalse(ledger.contains("tokensUsed"))

        val migrated = store.recoverCrewMissions(session).single()
        assertEquals("INTERRUPTED", migrated.status)
        assertEquals("INTERRUPTED", migrated.bots.single().status)
        assertTrue(migrated.bots.single().error.isEmpty())
        assertEquals(oldMessages, store.readConversationMessages(session).map { it.optString("content") })
        assertEquals("Primary source notes", board.read("/board/source.md"))

        val timelineMission = store.readConversationTimeline(session).single { it.kind == "crew_mission" }
        assertEquals("mission-1", timelineMission.crewMissionSnapshot?.missionId)
        assertEquals("INTERRUPTED", timelineMission.crewMissionSnapshot?.status)
        assertEquals(CrewMessage.Type.USER, timelineMission.crewMissionSnapshot?.messages?.single()?.type)

        val recoveredAgain = store.recoverCrewMissions(session).single()
        assertEquals("INTERRUPTED", recoveredAgain.status)
        assertFalse(recoveredAgain.bots.single().active())
    }
}
