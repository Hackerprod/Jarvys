package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.ToolActivity
import com.jarvys.agent.R
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewCoordinationEvidence
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.crew.CrewProcessIdentity
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspaceDetailComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    private val workspaceId = "11111111-1111-1111-1111-111111111111"

    @Test fun detailRendersTheWorkspaceSessionAsTheSharedConversationTimeline() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val user = AgentRunUiEvent(1L, "user", text = "Worker question")
        val firstTool = AgentRunUiEvent.toolEvent(2L, "tool_result", "Read file", "Read the index", "read-1", null, 2L)
        val secondTool = AgentRunUiEvent.toolEvent(3L, "tool_result", "Write file", "Updated the index", "write-1", null, 3L)
        val assistant = AgentRunUiEvent(4L, "assistant", text = "Workspace answer")
        val events = listOf(user, firstTool, secondTool, assistant)
        val state = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title", WorkspaceUiStatus.ACTIVE, events)
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })

        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(state = state,
                runSnapshot = AgentRunUiSnapshot(sessionId = sessionId, events = events),
                connectorRegistry = connectors,
                onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {})
        } }

        compose.onNodeWithTag("workspace-chat-timeline").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-list").assertIsDisplayed()
        compose.onNodeWithText("Worker question").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Workspace answer", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Workspace answer", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("tool-group-2").assertIsDisplayed()
        compose.onNodeWithText("Mission").assertDoesNotExist()
        compose.onNodeWithText("Recovery").assertDoesNotExist()
        compose.onNodeWithText("Recorded result").assertDoesNotExist()
    }

    @Test fun workspaceTimelineKeepsLiveToolSheetOpenWhenTheGroupAndRunStateChange() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        fun tool(id: Long) = AgentRunUiEvent.toolEvent(id, "tool_result", "Completed tool $id",
            "Result details for completed tool $id", "workspace-tool-$id", null, id)
        val events = mutableStateOf(listOf(tool(2L)))
        val status = mutableStateOf(WorkspaceUiStatus.ACTIVE)
        val snapshot = mutableStateOf(AgentRunUiSnapshot(sessionId = sessionId, events = events.value, running = true))
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent { MaterialTheme {
            val state = WorkspaceChatUiState(workspaceId, sessionId, "Workspace", status.value, events.value)
            val currentSnapshot = snapshot.value
            WorkspaceDetailScreen(state = state, runSnapshot = currentSnapshot,
                workspaceRunning = workspaceHasActiveWork(state, currentSnapshot), connectorRegistry = connectors,
                onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {})
        } }

        // The new event sorts before the already-seen tool. It becomes the group's first event
        // and is therefore the live arrival key used to animate the new workspace row.
        compose.runOnIdle {
            events.value = listOf(tool(1L), tool(2L))
            snapshot.value = AgentRunUiSnapshot(sessionId = sessionId, events = events.value, running = true)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tool-group-${tool(1L).id}").performClick()
        compose.onNodeWithTag("tool-group-sheet-${tool(1L).id}").assertIsDisplayed()
        val groupId = tool(1L).id

        compose.runOnIdle {
            events.value = (1L..40L).map(::tool)
            snapshot.value = AgentRunUiSnapshot(sessionId = sessionId, events = events.value, running = true)
        }
        compose.waitForIdle()
        repeat(40) { compose.onNodeWithTag("tool-group-list-$groupId").performTouchInput { swipeUp() } }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-activity-${tool(40L).id}").assertIsDisplayed()

        compose.runOnIdle {
            events.value = events.value + tool(41L)
            snapshot.value = AgentRunUiSnapshot(sessionId = sessionId, events = events.value, running = true)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            status.value = WorkspaceUiStatus.COMPLETED
            snapshot.value = AgentRunUiSnapshot(sessionId = sessionId, events = events.value, running = false)
        }
        compose.waitForIdle()

        compose.onNodeWithTag("tool-group-sheet-$groupId").assertIsDisplayed()
        compose.onNodeWithTag("tool-group-list-$groupId").assertIsDisplayed()
        compose.onNodeWithTag("conversation-activity-${tool(40L).id}").assertIsDisplayed()
    }

    @Test fun completedWorkspaceDoesNotKeepThinkingOrAnActivePauseWhenItsRunSnapshotIsStale() {
        val activeWorkspaceId = "22222222-2222-2222-2222-222222222222"
        val activeSessionId = WorkspaceIdentity.executionSessionId(activeWorkspaceId)
        val inactiveSessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val assignmentId = "stale-snapshot-assignment"
        val activeState = WorkspaceChatUiState(activeWorkspaceId, activeSessionId, "Active workspace",
            WorkspaceUiStatus.ACTIVE, assignments = listOf(WorkspaceAssignmentUi(assignmentId, 1L,
                "Worker", "Task", WorkspaceUiStatus.ACTIVE, canPause = true)))
        val inactiveState = WorkspaceChatUiState(workspaceId, inactiveSessionId, "Completed workspace",
            WorkspaceUiStatus.COMPLETED, listOf(AgentRunUiEvent(30L, "assistant", text = "Saved answer")))
        val selected = mutableStateOf(activeState)
        val snapshot = mutableStateOf(AgentRunUiSnapshot(sessionId = activeSessionId, running = true))
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })

        compose.setContent { MaterialTheme {
            val state = selected.value
            val workspaceActive = workspaceHasActiveWork(state, snapshot.value)
            Column(Modifier.fillMaxSize()) {
                WorkspaceDetailScreen(state = state, runSnapshot = snapshot.value,
                    workspaceRunning = workspaceActive,
                    connectorRegistry = connectors,
                    onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {},
                    modifier = Modifier.weight(1f))
                com.jarvys.agent.ui.chat.ChatComposer(
                    goal = "", onGoalChange = {}, onSend = {}, onInterruptAndSend = {},
                    sendAllowed = false, canInterrupt = false, pendingAttachments = emptyList(),
                    attachmentSessionId = state.executionSessionId, attachmentSending = false,
                    onRemoveAttachment = {}, onAttachmentCamera = {}, onAttachmentPhotos = {}, onAttachmentFiles = {},
                    onStop = {}, onSelectModel = {}, modelLabel = "Model", running = workspaceActive,
                    captureContextRequested = false, onCaptureContext = {}, onImportSkill = {},
                    availableSkills = emptyList(), selectedSkillIds = emptySet(), onToggleRunSkill = {},
                    onManageSkills = {}, skillEnabledCount = 0, skillTotalCount = 0,
                    crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {},
                    inputEnabled = true, separateStopAction = true,
                    stopActionEnabled = workspacePauseEnabled(state, assignmentId, workspaceActive),
                    stopActionLabel = "Pause", showModelSelector = false,
                    showContextActions = false, showMicrophoneButton = false,
                )
            }
        } }

        compose.onNodeWithText(compose.activity.getString(R.string.agent_presence_thinking)).assertIsDisplayed()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsEnabled()
        compose.runOnIdle {
            selected.value = inactiveState
            // Model a lingering session snapshot after the mission's durable terminal status landed.
            snapshot.value = AgentRunUiSnapshot(sessionId = inactiveSessionId, events = inactiveState.events,
                running = true)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Saved answer", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.agent_presence_thinking)).assertDoesNotExist()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsNotEnabled()
    }

    @Test fun workspaceActivityUsesProjectedStatusAndOnlyCurrentProcessBots() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        fun mission(processId: String, statuses: List<String>) = CrewMissionSnapshot(
            "mission", sessionId, processId, "Workspace mission", "RUNNING", "", 1L, 0L,
            statuses.mapIndexed { index, status -> CrewBotSnapshot("bot-$index", "worker", "Worker",
                "Worker $index", "blue", "Task", status, "", "", "", emptyList(), 1L, 0L) }, emptyList())
        fun event(id: Long, processId: String, vararg statuses: String) = AgentRunUiEvent.crewMissionEvent(
            id, mission(processId, statuses.toList()))
        val assignmentId = "workspace-assignment"
        val terminal = event(50L, CrewProcessIdentity.ID, "DONE", "FAILED")
        val selected = mutableStateOf(WorkspaceChatUiState(workspaceId, sessionId, "Workspace",
            WorkspaceUiStatus.COMPLETED, listOf(terminal), assignments = listOf(
                WorkspaceAssignmentUi(assignmentId, 1L, "Worker", "Task", WorkspaceUiStatus.COMPLETED,
                    canPause = true))))
        val snapshot = mutableStateOf(AgentRunUiSnapshot(sessionId = sessionId, events = listOf(terminal), running = true))
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })

        compose.setContent { MaterialTheme {
            val state = selected.value
            val current = snapshot.value
            val active = workspaceHasActiveWork(state, current)
            Column(Modifier.fillMaxSize()) {
                WorkspaceDetailScreen(state = state, runSnapshot = current, workspaceRunning = active, connectorRegistry = connectors,
                    onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {}, modifier = Modifier.weight(1f))
                com.jarvys.agent.ui.chat.ChatComposer(
                    goal = "", onGoalChange = {}, onSend = {}, onInterruptAndSend = {},
                    sendAllowed = false, canInterrupt = false, pendingAttachments = emptyList(),
                    attachmentSessionId = sessionId, attachmentSending = false,
                    onRemoveAttachment = {}, onAttachmentCamera = {}, onAttachmentPhotos = {}, onAttachmentFiles = {},
                    onStop = {}, onSelectModel = {}, modelLabel = "Model", running = active,
                    captureContextRequested = false, onCaptureContext = {}, onImportSkill = {},
                    availableSkills = emptyList(), selectedSkillIds = emptySet(), onToggleRunSkill = {},
                    onManageSkills = {}, skillEnabledCount = 0, skillTotalCount = 0,
                    crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {},
                    inputEnabled = true, separateStopAction = true,
                    stopActionEnabled = workspacePauseEnabled(state, assignmentId, active), stopActionLabel = "Pause",
                    showModelSelector = false, showContextActions = false, showMicrophoneButton = false)
            }
        } }

        compose.onNodeWithText(compose.activity.getString(R.string.agent_presence_thinking)).assertDoesNotExist()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsNotEnabled()

        val previousProcess = event(50L, "previous-app-process", "RUNNING", "WAITING")
        compose.runOnIdle {
            selected.value = WorkspaceChatUiState(workspaceId, sessionId, "Workspace",
                WorkspaceUiStatus.INTERRUPTED, listOf(previousProcess), assignments = listOf(
                    WorkspaceAssignmentUi(assignmentId, 2L, "Worker", "Task", WorkspaceUiStatus.INTERRUPTED,
                        canPause = true)))
            snapshot.value = AgentRunUiSnapshot(sessionId = sessionId, events = listOf(previousProcess), running = true)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.agent_presence_thinking)).assertDoesNotExist()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsNotEnabled()

        val live = event(50L, CrewProcessIdentity.ID, "RUNNING")
        compose.runOnIdle {
            selected.value = WorkspaceChatUiState(workspaceId, sessionId, "Workspace",
                WorkspaceUiStatus.UNKNOWN, listOf(live), assignments = listOf(
                    WorkspaceAssignmentUi(assignmentId, 3L, "Worker", "Task", WorkspaceUiStatus.ACTIVE,
                        canPause = true)))
            snapshot.value = AgentRunUiSnapshot(sessionId = sessionId, events = listOf(live), running = true)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.agent_presence_thinking)).assertIsDisplayed()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsEnabled()
    }

    @Test fun loadingErrorAndEmptyStatesRemainDistinctFromAnEmptyConversation() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val state = mutableStateOf(WorkspaceChatUiState(workspaceId, sessionId, "Workspace title",
            WorkspaceUiStatus.UNKNOWN, loading = true))
        var retries = 0
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(state = state.value, runSnapshot = AgentRunUiSnapshot(sessionId = sessionId),
                connectorRegistry = connectors,
                onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {}, onRetry = { retries++ })
        } }
        compose.onNodeWithTag("workspace-chat-loading").assertIsDisplayed()
        compose.onNodeWithTag("workspace-chat-empty").assertDoesNotExist()
        compose.runOnIdle { state.value = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title",
            WorkspaceUiStatus.UNKNOWN, error = "Stored conversation is unavailable") }
        val errorTitle = compose.activity.getString(R.string.workspace_chat_load_error)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(errorTitle, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("workspace-chat-error", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(errorTitle, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workspace-chat-retry").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state.value = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title",
            WorkspaceUiStatus.UNKNOWN) }
        compose.onNodeWithTag("workspace-chat-empty").assertIsDisplayed()
        compose.onNodeWithTag("workspace-chat-loading").assertDoesNotExist()
        compose.onNodeWithTag("workspace-chat-error").assertDoesNotExist()
    }

    @Test fun liveCrewActivityUsesTheChatTimelineAndSeparateWorkspaceComposerControls() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val bot = CrewBotSnapshot("worker", "explorer", "Explorer", "Scout", "blue", "private task",
            "RUNNING", "", "", "", emptyList(), 1L, 0L)
        val user = CrewMessage("user-message", sessionId, "user", "worker", CrewMessage.Type.USER,
            "Inspect this", emptyList(), 100L)
        val dispatch = CrewMessage("task-message", sessionId, "chief", "worker", CrewMessage.Type.TASK,
            "Private task dispatch", emptyList(), 110L)
        val internal = CrewMessage("internal-message", sessionId, "worker", "chief", CrewMessage.Type.FINDING,
            "Private worker finding", emptyList(), 120L).withCoordination(CrewCoordinationEvidence.create(
                sessionId, "mission", "worker", "00000000-0000-0000-0000-000000000001",
                "internal-message", "FINDING", "Private worker finding", emptyList(), 120L))
        val answer = CrewMessage("bot-answer", sessionId, "worker", "user", CrewMessage.Type.ANSWER,
            "The file is ready", emptyList(), 130L)
        val secondBot = CrewBotSnapshot("writer", "writer", "Writer", "Writer", "green", "private task",
            "RUNNING", "", "", "", emptyList(), 1L, 0L)
        val activity = ToolActivity("call", "execution", "call", "read", "Read file", "", "",
            "tool_call", "private result JSON", "", "", "", 140L)
        val tool = CrewMessage("tool-row", sessionId, "worker", "worker", CrewMessage.Type.STATUS,
            "Read file", emptyList(), 140L, activity)
        val mission = CrewMissionSnapshot("mission", sessionId, "process", "Inspect files", "RUNNING", "",
            90L, 0L, listOf(bot, secondBot), listOf(user, dispatch, internal, answer, tool))
        val rawEvents = listOf(AgentRunUiEvent.crewMissionEvent(1L, mission))
        val state = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title", WorkspaceUiStatus.ACTIVE, rawEvents)
        val assignment = WorkspaceAssignmentUi("assignment-1", 7L, "Scout", "Inspect files",
            WorkspaceUiStatus.ACTIVE, canCorrect = true, canPause = true)
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        var draft by mutableStateOf("")
        val controls = mutableListOf<Pair<WorkspaceControlType, String>>()

        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                WorkspaceDetailScreen(state = state,
                    runSnapshot = AgentRunUiSnapshot(sessionId = sessionId, events = rawEvents),
                    connectorRegistry = connectors,
                    onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {},
                    modifier = Modifier.weight(1f))
                com.jarvys.agent.ui.chat.ChatComposer(
                    goal = draft,
                    onGoalChange = { draft = it },
                    onSend = { controls += WorkspaceControlType.CORRECT to draft },
                    onStop = { controls += WorkspaceControlType.PAUSE to assignment.id },
                    onSelectModel = {}, modelLabel = "Model", running = true,
                    captureContextRequested = false, onCaptureContext = {}, onImportSkill = {},
                    availableSkills = emptyList(), selectedSkillIds = emptySet(), onToggleRunSkill = {},
                    onManageSkills = {}, skillEnabledCount = 0, skillTotalCount = 0,
                    crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {},
                    sendAllowed = true, inputEnabled = true, separateStopAction = true,
                    stopActionEnabled = true, stopActionLabel = "Pause",
                    showModelSelector = false, showContextActions = false, showMicrophoneButton = false,
                    recipientContent = {
                        WorkspaceAssignmentSelector(listOf(assignment), assignment.id, onSelect = {})
                    },
                )
            }
        } }

        compose.onNodeWithTag("workspace-chat-timeline").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-list").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer-input").assertIsDisplayed()
        compose.onNodeWithTag("chat-send-stop-button").assertIsDisplayed()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsDisplayed()
        compose.onNodeWithTag("workspace-chat-input-hint").assertDoesNotExist()
        compose.onNodeWithText("Inspect this").assertIsDisplayed()
        compose.onNodeWithText("The file is ready").assertIsDisplayed()
        compose.onNodeWithTag("workspace-message-sender-bot-answer").assertTextEquals("Scout")
        compose.onNodeWithText("Private task dispatch").assertDoesNotExist()
        compose.onNodeWithText("Private worker finding").assertDoesNotExist()
        compose.onNodeWithText("private result JSON").assertDoesNotExist()
        compose.onNodeWithTag("tool-output-toggle-execution", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("workspace-assignment-selector").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer-input").performTextInput("Please continue")
        compose.onNodeWithTag("chat-send-stop-button").performClick()
        compose.onNodeWithTag("chat-separate-stop-button").performClick()
        assertEquals(listOf(WorkspaceControlType.CORRECT to "Please continue",
            WorkspaceControlType.PAUSE to assignment.id), controls)
    }

    @Test fun runningWorkspaceOnlyStreamsItsLastAnswerAndKeepsEarlierReplyFooters() {
        val sessionId = WorkspaceIdentity.executionSessionId(workspaceId)
        val events = listOf(
            AgentRunUiEvent(-80L, "user", text = "Inspect the repository", timestampMillis = 10L, messageId = "workspace-user"),
            AgentRunUiEvent(-12L, "assistant", text = "First bot update", timestampMillis = 20L, messageId = "first-answer"),
            AgentRunUiEvent(-150L, "assistant", text = "Second bot update", timestampMillis = 30L, messageId = "second-answer"),
            AgentRunUiEvent(-2L, "assistant", text = "Current bot update", timestampMillis = 40L, messageId = "current-answer"),
        )
        val state = WorkspaceChatUiState(workspaceId, sessionId, "Workspace title", WorkspaceUiStatus.ACTIVE, events)
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })

        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(state = state,
                runSnapshot = AgentRunUiSnapshot(sessionId = sessionId, events = events, running = true),
                connectorRegistry = connectors,
                onOpenPreview = {}, onOpenSkillFile = {}, onOpenCrewMission = {})
        } }

        val copyDescription = compose.activity.getString(R.string.chat_footer_copy_description)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(copyDescription).fetchSemanticsNodes().size == 2
        }
        compose.onAllNodesWithContentDescription(copyDescription).assertCountEquals(2)
        compose.waitUntil(10_000) {
            listOf("First bot update", "Second bot update", "Current bot update").all { text ->
                compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val timeline = compose.onNodeWithTag("chat-message-list")
        timeline.performScrollToNode(hasText("First bot update"))
        compose.onNodeWithText("First bot update").assertIsDisplayed()
        timeline.performScrollToNode(hasText("Second bot update"))
        compose.onNodeWithText("Second bot update").assertIsDisplayed()
        timeline.performScrollToNode(hasText("Current bot update"))
        compose.onNodeWithText("Current bot update").assertIsDisplayed()
    }
}
