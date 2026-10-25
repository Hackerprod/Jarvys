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
            listOf("historyExecutor", "chatExecutor", "memoryExecutor", "skillImportExecutor")
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

    @Test fun B10_startIntentAndRestoredPickerAlwaysUseCentral() {
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
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, selected())
        val picker = MainActivity::class.java.getDeclaredField("attachmentPickerSession").apply { isAccessible = true }
        picker.set(activity, stale)
        controller!!.recreate()
        activities += activity
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION }
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, picker.get(activity))
    }

    @Test fun legacySelectionIsIgnoredAndCentralNeverBorrowsItsTranscript() {
        val original = transcript(legacy)
        start()
        await { selected() == WorkspaceIdentity.CENTRAL_SESSION && AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION }
        assertEquals(original, transcript(legacy))
        awaitCentralEmpty()
        assertEquals(original, transcript(legacy))
        assertArrayEquals(bytes, AttachmentStore(context).resolve(legacy, attachment).readBytes())
        assertFalse(db { store.readConversationMetadata(legacy).deleted })
        assertFalse(db { store.readConversationMetadata(legacy).archived })
        WorkspaceRegistry(context).use { assertTrue("Selecting central cannot create a worker workspace", it.listWorkspaces().isEmpty()) }
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
