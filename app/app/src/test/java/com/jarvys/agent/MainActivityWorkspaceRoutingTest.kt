package com.jarvys.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.runtime.MutableState
import androidx.navigation.NavHostController
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.workspaces.WORKSPACE_PAGE_SIZE
import org.junit.Rule
import org.junit.rules.RuleChain
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import com.jarvys.agent.workspaces.WorkspaceChanges
import com.jarvys.agent.workspaces.WorkspaceControlType
import com.jarvys.agent.workspaces.WorkspaceAssignmentUi
import com.jarvys.agent.workspaces.WorkspaceChatUiState
import com.jarvys.agent.workspaces.WorkspaceUiStatus
import com.jarvys.agent.workspaces.workspaceChatCorrectionDisabledHintResource
import com.jarvys.agent.workspaces.workspaceChatSelectedAssignment
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewProcessIdentity
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Small Activity integration slice: no model turns, service launches, workers or device effects. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class MainActivityWorkspaceRoutingTest {
    // Install Compose's clock before any real Activity is launched. Infinite visual animations stay
    // present, while tests advance finite frames instead of draining Robolectric's Choreographer.
    private val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    private val compose = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ExistingUserSetupWizardRule()).around(chatTestStore).around(compose)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store: LocalRunStore get() = LocalRunStore(context)
    private val legacy = "ux43-legacy-${UUID.randomUUID()}"
    private val execution = WorkspaceIdentity.executionSessionId("11111111-1111-1111-1111-111111111111")
    private val secretSingleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var controller: ActivityController<MainActivity>? = null
    private val activities = mutableListOf<MainActivity>()
    private val bytes = "Exact independent attachment bytes".toByteArray()
    private lateinit var attachment: ChatAttachment
    private val activity: MainActivity get() = requireNotNull(controller).get()

    @Test fun mainActivityWorkspaceThinkingAndPauseButtonUseProjectedCurrentProcessActivity() {
        start()
        val assignmentId = "activity-assignment"
        fun state(status: WorkspaceUiStatus, processId: String, botStatus: String): WorkspaceChatUiState {
            val bot = CrewBotSnapshot("activity-bot", "worker", "Worker", "Worker", "blue", "Task",
                botStatus, "", "", "", emptyList(), 1L, 0L)
            val mission = CrewMissionSnapshot("activity-mission", execution, processId,
                "Workspace mission", "RUNNING", "", 1L, 0L, listOf(bot), emptyList())
            val event = AgentRunUiEvent.crewMissionEvent(61L, mission)
            return WorkspaceChatUiState("11111111-1111-1111-1111-111111111111", execution,
                "Workspace", status, listOf(event), assignments = listOf(WorkspaceAssignmentUi(
                    assignmentId, 1L, "Worker", "Task", status, canPause = true)))
        }
        fun active(state: WorkspaceChatUiState) = invoke("workspaceHasActiveWorkForUi",
            arrayOf(WorkspaceChatUiState::class.java), arrayOf(state)) as Boolean
        fun pauseEnabled(state: WorkspaceChatUiState) = invoke("workspacePauseEnabledForUi",
            arrayOf(WorkspaceChatUiState::class.java, String::class.java), arrayOf(state, assignmentId)) as Boolean

        val terminal = state(WorkspaceUiStatus.COMPLETED, CrewProcessIdentity.ID, "DONE")
        assertFalse("mission status RUNNING cannot override terminal bot projections", active(terminal))
        assertFalse("terminal workspace keeps the wired stop button disabled", pauseEnabled(terminal))

        val previousProcess = state(WorkspaceUiStatus.INTERRUPTED, "previous-install-process", "WAITING")
        assertFalse("a previous process snapshot is recovered as interrupted", active(previousProcess))
        assertFalse("the previous process cannot enable the pause button", pauseEnabled(previousProcess))

        val live = state(WorkspaceUiStatus.UNKNOWN, CrewProcessIdentity.ID, "RUNNING")
        assertTrue("a current-process active bot drives workspace thinking", active(live))
        assertTrue("the real MainActivity pause-button calculation follows current active work", pauseEnabled(live))
    }

    @Test fun mainActivityUsesTheMatchingLiveSessionSnapshotForThinkingAndPauseTogether() {
        start()
        val assignmentId = "live-snapshot-assignment"
        val terminal = AgentRunUiEvent.crewMissionEvent(61L, CrewMissionSnapshot(
            "live-snapshot-mission", execution, CrewProcessIdentity.ID, "Workspace mission", "RUNNING", "", 1L, 0L,
            listOf(CrewBotSnapshot("live-snapshot-bot", "worker", "Worker", "Worker", "blue", "Task",
                "DONE", "", "", "", emptyList(), 1L, 0L)), emptyList()))
        val live = AgentRunUiEvent.crewMissionEvent(62L, CrewMissionSnapshot(
            "live-snapshot-mission", execution, CrewProcessIdentity.ID, "Workspace mission", "RUNNING", "", 2L, 0L,
            listOf(CrewBotSnapshot("live-snapshot-bot", "worker", "Worker", "Worker", "blue", "Task",
                "RUNNING", "", "", "", emptyList(), 2L, 0L)), emptyList()))
        val state = WorkspaceChatUiState("11111111-1111-1111-1111-111111111111", execution,
            "Workspace", WorkspaceUiStatus.UNKNOWN, listOf(terminal), assignments = listOf(WorkspaceAssignmentUi(
                assignmentId, 1L, "Worker", "Task", WorkspaceUiStatus.ACTIVE, canPause = true)))
        val snapshot = AgentRunUiSnapshot(sessionId = execution, events = listOf(live))
        val active = invoke("workspaceHasActiveWorkForUi",
            arrayOf(WorkspaceChatUiState::class.java, AgentRunUiSnapshot::class.java), arrayOf(state, snapshot)) as Boolean
        val pauseEnabled = invoke("workspacePauseEnabledForUi",
            arrayOf(WorkspaceChatUiState::class.java, String::class.java, Boolean::class.javaPrimitiveType!!),
            arrayOf(state, assignmentId, active)) as Boolean

        assertTrue("the matching live workspace snapshot controls Thinking", active)
        assertTrue("the pause button consumes that exact activity result", pauseEnabled)
        val unrelatedSnapshot = AgentRunUiSnapshot(sessionId = legacy, events = listOf(live))
        val unrelatedActive = invoke("workspaceHasActiveWorkForUi",
            arrayOf(WorkspaceChatUiState::class.java, AgentRunUiSnapshot::class.java), arrayOf(state, unrelatedSnapshot)) as Boolean
        assertFalse("a snapshot from another session cannot override the workspace state", unrelatedActive)
    }

    @Test fun selectedPauseOnlyBotKeepsItsPauseAndGetsAnAccurateCorrectionHint() {
        val correctable = WorkspaceAssignmentUi("correctable", 4L, "Writer", "Write",
            WorkspaceUiStatus.ACTIVE, canCorrect = true)
        val pauseOnly = WorkspaceAssignmentUi("pause-only", 7L, "Scout", "Inspect",
            WorkspaceUiStatus.ACTIVE, canPause = true)
        val assignments = listOf(correctable, pauseOnly)

        val selected = workspaceChatSelectedAssignment(assignments, pauseOnly.id)
        assertEquals(pauseOnly, selected)
        assertFalse(requireNotNull(selected).canCorrect)
        assertTrue(selected.canPause)
        assertEquals(R.string.workspace_chat_selected_assignment_cannot_correct_hint,
            workspaceChatCorrectionDisabledHintResource(assignments, selected))
        assertNull(workspaceChatCorrectionDisabledHintResource(assignments, correctable))
        assertEquals(R.string.workspace_chat_input_hint,
            workspaceChatCorrectionDisabledHintResource(listOf(pauseOnly), pauseOnly))
        assertNotEquals(context.getString(R.string.workspace_chat_selected_assignment_cannot_correct_hint),
            context.getString(R.string.workspace_chat_input_hint))
    }

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        secretSingleton.set(null, SecretStore(context.getSharedPreferences("ux43-route-test-secrets", 0)))
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        chatTestStore.onDatabaseWorker { SingleSessionReset.awaitReadyBlocking(context.filesDir) }
        MemoryStore(context).markDisclosureShown()
        MemoryReflectionPreferences(context).markDisclosureShown()
        AgentRunUiState.bindGeneration(legacy, Long.MAX_VALUE)
        AgentRunUiState.stopPendingGeneration(legacy, Long.MAX_VALUE)
        AgentRunUiState.resetSession(legacy)
        AgentRunUiState.bindGeneration(WorkspaceIdentity.CENTRAL_SESSION, Long.MAX_VALUE)
        AgentRunUiState.stopPendingGeneration(WorkspaceIdentity.CENTRAL_SESSION, Long.MAX_VALUE)
        AgentRunUiState.resetSession(WorkspaceIdentity.CENTRAL_SESSION)
        attachment = AttachmentStore(context).copyFromStream(legacy, "kept.txt", "text/plain",
            ChatAttachment.Kind.FILE, ByteArrayInputStream(bytes))
        db {
            val question = store.appendConversationMessage(legacy, "user", "Independent question", listOf(attachment))
            store.appendConversationMessage(legacy, "assistant", "Independent answer", 0L, "", question, "COMPLETED")
            store.renameConversation(legacy, "Independent title")
        }
        context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE).edit()
            .putString("active_session_id", legacy).commit()
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        // MainActivity owns daemon executors; drain already-admitted reads before this sandbox closes.
        activities.forEach { value ->
            listOf("historyExecutor", "chatExecutor", "sessionTitleExecutor", "memoryExecutor", "skillImportExecutor")
                .forEach { field -> runCatching {
                    executor(value, field).apply { shutdown(); awaitTermination(10, TimeUnit.SECONDS) }
                } }
        }
        compose.waitForIdle()
        AgentRunUiState.bindGeneration(legacy, Long.MAX_VALUE)
        AgentRunUiState.stopPendingGeneration(legacy, Long.MAX_VALUE)
        AgentRunUiState.resetSession("ux43-route-cleanup")
        secretSingleton.set(null, null)
        WorkManagerTestCleanup.close(context)
    }

    @Test fun B10_startIntentNewChatAndRestoredPickerAlwaysUseCentral() {
        val stale = UUID.randomUUID().toString()
        start(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, stale)
            .putExtra(MainActivity.EXTRA_OPEN_PROACTIVE_MESSAGE_ID, "missing-message"))
        awaitCentralEmpty()
        await { activityState<String?>("proactiveTargetMessageId").value == null }
        assertFalse(context.getSharedPreferences("jarvys_chat", 0).contains("active_session_id"))
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, WorkspaceIdentity.serviceSession(stale))
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, WorkspaceIdentity.serviceSession(null))
        assertEquals(execution, WorkspaceIdentity.serviceSession(execution))
        db { store.appendConversationMessage(WorkspaceIdentity.CENTRAL_SESSION, "user", "kept across newChat") }
        invoke("newChat")
        await { AgentRunUiState.state.value.events.any { it.text == "kept across newChat" } }
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, selected())
        val picker = MainActivity::class.java.getDeclaredField("attachmentPickerSession").apply { isAccessible = true }
        picker.set(activity, stale)
        controller!!.recreate()
        activities += activity
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION }
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, picker.get(activity))
        assertTrue(db { store.readConversationTimeline(WorkspaceIdentity.CENTRAL_SESSION).any { it.text == "kept across newChat" } })
    }

    @Test fun legacySelectionIsIgnoredAndCentralNeverBorrowsItsTranscript() {
        val original = transcript(legacy)
        start()
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION && AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        assertEquals(original, transcript(legacy))
        invoke("openCentralWorkspaceChat")
        awaitCentralEmpty()
        assertEquals(original, transcript(legacy))
        assertArrayEquals(bytes, AttachmentStore(context).resolve(legacy, attachment).readBytes())
        assertFalse(db { store.readConversationMetadata(legacy).deleted })
        assertFalse(db { store.readConversationMetadata(legacy).archived })
        WorkspaceRegistry(context).use { assertTrue("Selecting central cannot create a worker workspace", it.listWorkspaces().isEmpty()) }
    }

    @Test fun deletingAnOldConversationKeepsCentralSelected() {
        start()
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION && AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        invoke("applyConversationAction",
            arrayOf(String::class.java, ConversationAction::class.java, String::class.java),
            arrayOf(legacy, ConversationAction.DELETE, ""))
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION &&
            AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION &&
            db { store.readConversationMetadata(legacy).deleted } }
        awaitCentralEmpty()
        assertEquals(AppNavigationBackPolicy.CHAT_ROOT, navigation().currentDestination?.route)
        assertTrue(db { store.readConversationTimeline(legacy).isEmpty() })
    }

    @Test fun warmWorkerIntentRedirectsToCentralWithoutOpeningTheWorkerAsAChat() {
        db { store.appendConversationMessage(execution, "user", "Worker-only ledger fixture") }
        val workerBefore = transcript(execution)
        val legacyBefore = transcript(legacy)
        start()
        await { AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        invoke("onNewIntent", arrayOf(Intent::class.java), arrayOf(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, execution)))
        awaitCentralEmpty()
        assertEquals(workerBefore, transcript(execution))
        assertEquals(legacyBefore, transcript(legacy))
    }

    @Test fun coldWorkerIntentRedirectsToCentralWithoutConsumingIndependentSelection() {
        val legacyBefore = transcript(legacy)
        start(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, execution))
        awaitCentralEmpty()
        assertEquals(legacyBefore, transcript(legacy))
        assertArrayEquals(bytes, AttachmentStore(context).resolve(legacy, attachment).readBytes())
    }

    @Test fun recreationKeepsCentralSelectionEvenWhenOriginalIntentStillPointsAtLegacy() {
        start(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, legacy))
        await { AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        invoke("openCentralWorkspaceChat")
        awaitCentralEmpty()
        assertEquals(legacy, activity.intent.getStringExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION))
        controller!!.recreate()
        activities += activity
        awaitCentralEmpty()
    }

    @Test fun sendingAndPausingWorkspaceControlsPreservesTheCurrentChatDraftAndSkills() {
        start()
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION && activityState<String>("conversationSessionId").value == WorkspaceIdentity.CENTRAL_SESSION }
        val draft = "A draft unrelated to the workspace control"
        val selectedSkills = setOf("selected-skill")
        compose.runOnIdle {
            activityState<String>("goalInput").value = draft
            activityState<Set<String>>("selectedSkillIds").value = selectedSkills
        }
        val revision = WorkspaceChanges.revision.value
        val parameterTypes = arrayOf<Class<*>>(String::class.java, String::class.java, Long::class.javaPrimitiveType!!,
            WorkspaceControlType::class.java, String::class.java)
        val workspaceId = UUID.randomUUID().toString()
        invoke("applyWorkspaceControl", parameterTypes,
            arrayOf(workspaceId, UUID.randomUUID().toString(), 1L, WorkspaceControlType.CORRECT, "Continue the worker"))
        invoke("applyWorkspaceControl", parameterTypes,
            arrayOf(workspaceId, UUID.randomUUID().toString(), 1L, WorkspaceControlType.PAUSE, ""))

        await { WorkspaceChanges.revision.value >= revision + 2 }
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, selected())
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, activityState<String>("conversationSessionId").value)
        assertEquals(draft, activityState<String>("goalInput").value)
        assertEquals(selectedSkills, activityState<Set<String>>("selectedSkillIds").value)
        assertFalse(activityState<Boolean>("conversationActionPending").value)
    }

    @Test fun reservedSessionTitleGuardReturnsBeforeSchedulingAnyTitleWork() {
        start()
        val titleExecutor = executor(activity, "sessionTitleExecutor")
        titleExecutor.shutdown()
        assertTrue(titleExecutor.awaitTermination(10, TimeUnit.SECONDS))
        // The shut-down executor is an admission trap. Any title task submission throws synchronously;
        // this proves the guard runs before async work and avoids making a network/model test call.
        val parameterTypes = arrayOf<Class<*>>(String::class.java, String::class.java)
        invoke("requestConversationTitle", parameterTypes, arrayOf(WorkspaceIdentity.CENTRAL_SESSION, "Simple question"))
        invoke("requestConversationTitle", parameterTypes, arrayOf(execution, "Worker question"))
        try {
            invoke("requestConversationTitle", parameterTypes, arrayOf(legacy, "Ordinary question"))
            fail("Ordinary title path must reach the executor trap so the negative assertion is meaningful")
        } catch (expected: InvocationTargetException) {
            assertTrue(expected.cause is RejectedExecutionException)
        }
    }

    @Test fun realDrawerCatalogDetailFilesAndBackPreserveIndependentDraftAndBothAttachmentOwners() {
        val workspace = WorkspaceRegistry(context).use {
            it.createWorkspace("navigation-detail", "Recorded navigation workspace")
        }
        val workerToolEvent = db {
            val workerMessageId = store.appendConversationMessage(workspace.executionSessionId, "user",
                "Worker transcript question")
            store.appendConversationToolPresentation(workspace.executionSessionId, workerMessageId,
                "Workspace test tool", "tool_result", "workspace-test-call", "Tool output for this workspace", null, "")
            store.appendConversationMessage(workspace.executionSessionId, "assistant", "Worker transcript answer")
            store.readConversationTimeline(workspace.executionSessionId).single { it.kind == "tool" }
        }
        val original = transcript(legacy)
        start()
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION && AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        settleNativeFrame()
        val draftText = "Unsent independent draft survives workspace browsing"
        compose.onNode(hasSetTextAction()).performTextInput(draftText)
        val draftBytes = "Unsent attachment retains its owner and bytes".toByteArray()
        val draftUri = Uri.parse("content://navigation-fixture/unsent.txt")
        Shadows.shadowOf(context.contentResolver).registerInputStream(draftUri, ByteArrayInputStream(draftBytes))
        val draftOwner = ViewModelProvider(activity)[AttachmentDraftViewModel::class.java]
        compose.runOnIdle { draftOwner.addUris(WorkspaceIdentity.CENTRAL_SESSION, listOf(draftUri), ChatAttachment.Kind.FILE) }
        await { draftOwner.drafts.value.singleOrNull()?.attachment != null }
        val draftBefore = draftOwner.drafts.value.single()
        val pendingFile = requireNotNull(draftBefore.attachment)
        assertArrayEquals(draftBytes, AttachmentStore(context).resolve(WorkspaceIdentity.CENTRAL_SESSION, pendingFile).readBytes())

        openDrawer()
        compose.onAllNodesWithTag("drawer-open-workspaces").assertCountEquals(1)
        compose.onNodeWithText(workspace.title).assertDoesNotExist()
        compose.onNodeWithTag("drawer-open-central").assertIsSelected()
        // Native rapid taps must still create exactly one catalog destination.
        compose.onNodeWithTag("drawer-open-workspaces").performTouchInput {
            click(); advanceEventTime(20); click()
        }
        awaitRoute(AppNavigationBackPolicy.WORKSPACES)
        await { compose.onAllNodesWithTag("workspace-list-row-${workspace.workspaceId}")
            .fetchSemanticsNodes().isNotEmpty() }
        settleNativeFrame()
        compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        compose.onNodeWithTag("workspace-list").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_open)).assertIsDisplayed()
        compose.onNodeWithTag("jarvys-back").assertDoesNotExist()
        assertEquals(1, navigation().currentBackStack.value.count { it.destination.route == AppNavigationBackPolicy.WORKSPACES })
        assertNull("Catalog must not create a Crew manager", CoreAgentRuntime.crewManagerForSession(workspace.executionSessionId))
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, selected())
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, AgentRunUiState.state.value.sessionId)
        assertEquals(listOf(draftBefore), draftOwner.drafts.value)

        openDrawer()
        compose.onNodeWithTag("drawer-open-workspaces").assertIsSelected()
        compose.onNodeWithTag("drawer-open-central").assertIsNotSelected()
        systemBack()
        compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        assertEquals(AppNavigationBackPolicy.WORKSPACES, navigation().currentDestination?.route)
        tapTag("workspace-list-row-${workspace.workspaceId}")
        awaitRoute(AppNavigationBackPolicy.COORDINATED_WORKSPACE)
        await { AgentRunUiState.state.value.sessionId == workspace.executionSessionId
            && compose.onAllNodesWithTag("workspace-chat-timeline").fetchSemanticsNodes().isNotEmpty() }
        await {
            val title = compose.onAllNodesWithText(workspace.title, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            title &&
                compose.onAllNodesWithText("Worker transcript question").fetchSemanticsNodes().isNotEmpty()
        }
        settleNativeFrame()
        compose.onNodeWithTag("jarvys-back").assertIsDisplayed()
        compose.onNodeWithText(workspace.title, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat-message-list").assertIsDisplayed()
        compose.onNodeWithText("Worker transcript question").assertIsDisplayed()
        compose.onNodeWithText("Worker transcript answer", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("conversation-activity-${workerToolEvent.id}").assertIsDisplayed()
        compose.onNodeWithTag("workspace-assignment-selector").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer-input").assertIsNotEnabled()
        compose.onNodeWithTag("chat-send-stop-button").assertIsNotEnabled()
        compose.onNodeWithTag("chat-separate-stop-button").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-chat-input-hint").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.workspace_chat_input_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.workspace_read_only_notice)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.workspace_recovery_unknown)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.workspace_result_unknown)).assertDoesNotExist()
        tapTag("jarvys-topbar-more-options")
        compose.onNodeWithTag("workspace/topbar-action").assertIsDisplayed()
        compose.onNodeWithTag("workspace/topbar-bots-action").assertDoesNotExist()
        compose.onNodeWithTag("topbar-rename-chat").assertDoesNotExist()
        compose.onNodeWithTag("topbar-delete-chat").assertDoesNotExist()
        tapTag("workspace/topbar-action")
        awaitRoute(AppNavigationBackPolicy.COORDINATED_FILES)
        compose.onNodeWithText(context.getString(R.string.workspace_file_read_only)).assertIsDisplayed()
        tapTag("jarvys-back")
        awaitRoute(AppNavigationBackPolicy.COORDINATED_WORKSPACE)
        await { AgentRunUiState.state.value.sessionId == workspace.executionSessionId }
        val mission = CrewMissionSnapshot("workspace-secondary-mission", workspace.executionSessionId,
            "workspace-test-process", "Secondary menu mission", "RUNNING", "", System.currentTimeMillis(), 0L,
            listOf(CrewBotSnapshot("workspace-bot", "writer", "Writer", "Writer", "green", "Inspect files",
                "RUNNING", "", "", "", emptyList(), System.currentTimeMillis(), 0L)), emptyList())
        db { store.appendCrewMissionSnapshot(mission) }
        WorkspaceChanges.changed()
        await { AgentRunUiState.state.value.events.any {
            it.crewMissionSnapshot?.missionId == mission.missionId
        } }
        tapTag("jarvys-topbar-more-options")
        compose.onNodeWithTag("workspace/topbar-bots-action").assertIsDisplayed()
        tapTag("workspace/topbar-bots-action")
        awaitRoute(AppNavigationBackPolicy.CREW)
        systemBack()
        awaitRoute(AppNavigationBackPolicy.COORDINATED_WORKSPACE)
        systemBack()
        awaitRoute(AppNavigationBackPolicy.WORKSPACES)
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_open)).assertIsDisplayed()
        compose.onNodeWithTag("jarvys-back").assertDoesNotExist()
        systemBack()
        awaitRoute(AppNavigationBackPolicy.CHAT_ROOT)
        await { AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        compose.onNode(hasSetTextAction()).assertTextEquals(draftText)
        compose.onNodeWithTag("pending-attachment-${draftBefore.id}").assertIsDisplayed()
        assertSame(draftOwner, ViewModelProvider(activity)[AttachmentDraftViewModel::class.java])
        assertEquals(listOf(draftBefore), draftOwner.drafts.value)
        assertEquals(original, transcript(legacy))
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, selected())
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, AgentRunUiState.state.value.sessionId)
        assertArrayEquals(bytes, AttachmentStore(context).resolve(legacy, attachment).readBytes())
        assertArrayEquals(draftBytes, AttachmentStore(context).resolve(WorkspaceIdentity.CENTRAL_SESSION, pendingFile).readBytes())
        WorkspaceRegistry(context).use { registry ->
            assertEquals(listOf(workspace), registry.listWorkspaces())
            assertTrue(registry.listAssignments(workspace.workspaceId).isEmpty())
        }
    }

    @Test fun realCatalogNextPreviousAndFirstBrowseBoundedPagesWithoutStartingCrew() {
        val rows = WorkspaceRegistry(context).use { registry ->
            repeat(WORKSPACE_PAGE_SIZE + 1) { registry.createWorkspace("navigation-page-$it", "Catalog project $it") }
            registry.listWorkspaces(100)
        }
        start()
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION && AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        openDrawer()
        tapTag("drawer-open-workspaces")
        awaitRoute(AppNavigationBackPolicy.WORKSPACES)
        val firstId = rows.first().workspaceId
        val lastId = rows.last().workspaceId
        await { compose.onAllNodesWithTag("workspace-list-row-$firstId").fetchSemanticsNodes().isNotEmpty() }
        scrollWorkspaceListUntil("workspace-next-page")
        tapTag("workspace-next-page")
        await { compose.onAllNodesWithTag("workspace-list-row-$lastId").fetchSemanticsNodes().isNotEmpty() }
        settleNativeFrame()
        compose.onNodeWithTag("workspace-list-row-$lastId").assertIsDisplayed()
        compose.onNodeWithTag("workspace-list-row-$firstId").assertDoesNotExist()
        compose.onNodeWithTag("workspace-next-page").assertDoesNotExist()
        tapTag("workspace-previous-page")
        await { compose.onAllNodesWithTag("workspace-list-row-$lastId").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("workspace-list-loading").fetchSemanticsNodes().isEmpty() }
        scrollWorkspaceListUntil("workspace-list-row-$firstId", towardTop = true)
        compose.onNodeWithTag("workspace-list-row-$firstId").assertIsDisplayed()
        scrollWorkspaceListUntil("workspace-next-page")
        tapTag("workspace-next-page")
        await { compose.onAllNodesWithTag("workspace-list-row-$lastId").fetchSemanticsNodes().isNotEmpty() }
        settleNativeFrame()
        tapTag("workspace-first-page")
        await { compose.onAllNodesWithTag("workspace-list-row-$lastId").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("workspace-list-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-list-row-$firstId"))
        settleNativeFrame()
        compose.onNodeWithTag("workspace-list-row-$firstId").assertIsDisplayed()
        rows.forEach { assertNull("Catalog browsing started ${it.workspaceId}", CoreAgentRuntime.crewManagerForSession(it.executionSessionId)) }
        WorkspaceRegistry(context).use { registry ->
            assertEquals(rows, registry.listWorkspaces(100))
            rows.forEach { assertTrue(registry.listAssignments(it.workspaceId).isEmpty()) }
        }
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, selected())
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, AgentRunUiState.state.value.sessionId)
    }

    private fun navigation(): NavHostController = MainActivity::class.java.getDeclaredField("activeNavController")
        .apply { isAccessible = true }.get(activity) as NavHostController

    private fun awaitRoute(route: String) {
        await { navigation().currentDestination?.route == route }
        settleNativeFrame()
    }

    private fun scrollWorkspaceListUntil(tag: String, towardTop: Boolean = false) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < end) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            compose.onNodeWithTag("workspace-list").performTouchInput {
                if (towardTop) swipeDown() else swipeUp()
            }
            compose.mainClock.advanceTimeBy(300)
            awaitReactionDrawIdle(compose, activity)
            Thread.sleep(10)
        }
        assertTrue("Workspace catalog control did not become visible: $tag",
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty())
    }

    private fun openDrawer() {
        settleNativeFrame()
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_open)).performTouchInput { click() }
        settleNativeFrame()
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
    }

    private fun tapTag(tag: String) {
        settleNativeFrame()
        compose.onNodeWithTag(tag).assertIsDisplayed().performTouchInput { click() }
        settleNativeFrame()
    }

    private fun systemBack() {
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        settleNativeFrame()
    }

    private fun settleNativeFrame() {
        compose.mainClock.advanceTimeBy(600)
        awaitReactionDrawIdle(compose, activity)
    }

    private fun start(intent: Intent = Intent(context, MainActivity::class.java)) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        activities += activity
        settleNativeFrame()
    }

    private fun invoke(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any> = emptyArray()): Any? =
        MainActivity::class.java.declaredMethods.single {
            (it.name == name || it.name.startsWith("$name\$")) && it.parameterTypes.contentEquals(types)
        }.apply { isAccessible = true }.invoke(activity, *args)

    @Suppress("UNCHECKED_CAST")
    private fun <T> activityState(name: String): MutableState<T> = MainActivity::class.java
        .getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(activity) as MutableState<T>

    private fun executor(value: MainActivity, name: String): ExecutorService =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(value) as ExecutorService

    private fun selected(): String = activityState<String>("conversationSessionId").value

    private fun transcript(session: String): List<Pair<String, String>> = db {
        store.readConversationTimeline(session).map { it.messageId to "${it.kind}:${it.text}" }
    }

    private fun <T> db(operation: () -> T): T = chatTestStore.onDatabaseWorker(operation)

    private fun awaitCentralEmpty(expectedInteractiveOwnerSessionId: String? = null) = await {
        selected() == WorkspaceIdentity.CENTRAL_SESSION &&
            AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION &&
            AgentRunUiState.state.value.events.isEmpty() &&
            (expectedInteractiveOwnerSessionId == null ||
                AgentRunUiState.state.value.interactiveOwnerSessionId == expectedInteractiveOwnerSessionId)
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            compose.mainClock.advanceTimeBy(48)
            // IO-backed catalog updates can arrive after the last explicit native frame.
            // Flush View layout/draw before inspecting lazy-list semantics with a paused clock.
            awaitReactionDrawIdle(compose, activity)
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("Workspace routing did not settle: selected=${selected()}; state=${AgentRunUiState.state.value}" +
            "\nCatalog semantics:\n${compose.onRoot(useUnmergedTree = true).printToString()}", condition())
    }
}
