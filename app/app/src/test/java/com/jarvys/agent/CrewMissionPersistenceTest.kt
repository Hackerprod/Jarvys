package com.jarvys.agent

import com.jarvys.agent.crew.CrewBoard
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceTimelineProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class CrewMissionPersistenceTest {
    @get:org.junit.Rule val chatTestStore = ChatTestStore()
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun activeCrewMissionSnapshotsRecoverAsInterrupted() {
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
        val session = "crew-persist-session"
        store.appendConversationMessage(session, "user", "Compare these sources")
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
        val migrated = store.recoverCrewMissions(session).single()
        assertEquals("INTERRUPTED", migrated.status)
        assertEquals("INTERRUPTED", migrated.bots.single().status)
        assertTrue(migrated.bots.single().error.isEmpty())
        assertEquals(listOf("Compare these sources"),
            store.readConversationMessages(session).map { it.optString("content") })
        assertEquals("Primary source notes", board.read("/board/source.md"))

        val timelineMission = store.readConversationTimeline(session).single { it.kind == "crew_mission" }
        assertEquals("mission-1", timelineMission.crewMissionSnapshot?.missionId)
        assertEquals("INTERRUPTED", timelineMission.crewMissionSnapshot?.status)
        assertEquals(CrewMessage.Type.USER, timelineMission.crewMissionSnapshot?.messages?.single()?.type)

        val recoveredAgain = store.recoverCrewMissions(session).single()
        assertEquals("INTERRUPTED", recoveredAgain.status)
        assertFalse(recoveredAgain.bots.single().active())
    }

    @Test
    fun assistantProgressSurvivesCrewSnapshotDatabaseReopen() {
        val workspaceId = "11111111-1111-1111-1111-111111111111"
        val session = WorkspaceIdentity.executionSessionId(workspaceId)
        val store = chatTestStore.localRunStore(chatTestStore.newFilesDirectory())
        store.appendConversationMessage(session, "user", "Implement the requested change")
        val bot = CrewBotSnapshot("bot-1", "coding", "Coding", "Ada", "amber", "Implement the change",
            "COMPLETED", "", "", "", listOf("file_read"), 100L, 150L)
        val progress = CrewMessage("progress-1", session, "bot-1", "user", CrewMessage.Type.PROGRESS,
            "I found the implementation and am updating its caller.", emptyList(), 120L)
        val snapshot = CrewMissionSnapshot("mission-1", session, "process", "Implement the requested change",
            "COMPLETED", "Done", 100L, 150L, listOf(bot), listOf(progress))

        store.appendCrewMissionSnapshot(snapshot)
        val persistedEvents = store.readConversationTimeline(session)
        val persistedRow = WorkspaceTimelineProjection.project(workspaceId, persistedEvents).single {
            it.kind == "assistant" && it.stage == "PROGRESS"
        }
        val combinedLiveAndPersisted = WorkspaceTimelineProjection.project(workspaceId, listOf(
            AgentRunUiEvent.crewMissionEvent(10L, snapshot),
            persistedEvents.single { it.kind == "crew_mission" },
        )).filter { it.kind == "assistant" && it.stage == "PROGRESS" }

        assertEquals(progress.id, persistedRow.messageId)
        assertEquals(progress.text, persistedRow.text)
        assertEquals(persistedRow.timelineKey, combinedLiveAndPersisted.single().timelineKey)
    }
}
