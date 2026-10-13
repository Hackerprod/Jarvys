package com.jarvys.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavHostController
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import com.jarvys.agent.workspaces.WORKSPACE_PAGE_SIZE
import org.junit.Rule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
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
    @get:Rule val compose = createEmptyComposeRule()

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

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        secretSingleton.set(null, SecretStore(context.getSharedPreferences("ux43-route-test-secrets", 0)))
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        MemoryStore(context).markDisclosureShown()
        MemoryReflectionPreferences(context).markDisclosureShown()
        AgentRunUiState.bindGeneration(legacy, Long.MAX_VALUE)
        AgentRunUiState.stopPendingGeneration(legacy, Long.MAX_VALUE)
        AgentRunUiState.resetSession(legacy)
        attachment = AttachmentStore(context).copyFromStream(legacy, "kept.txt", "text/plain",
            ChatAttachment.Kind.FILE, ByteArrayInputStream(bytes))
        val question = store.appendConversationMessage(legacy, "user", "Independent question", listOf(attachment))
        store.appendConversationMessage(legacy, "assistant", "Independent answer", 0L, "", question, "COMPLETED")
        store.renameConversation(legacy, "Independent title")
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

    @Test fun existingLegacySelectionSurvivesStartupAndEmptyCentralNeverBorrowsItsTranscript() {
        val original = transcript(legacy)
        start()
        await { selected() == legacy && AgentRunUiState.state.value.sessionId == legacy && AgentRunUiState.state.value.events.isNotEmpty() }
        assertEquals(original, transcript(legacy))
        invoke("openCentralWorkspaceChat")
        awaitCentralEmpty()
        assertEquals(original, transcript(legacy))
        assertArrayEquals(bytes, AttachmentStore(context).resolve(legacy, attachment).readBytes())
        assertFalse(store.readConversationMetadata(legacy).deleted)
        assertFalse(store.readConversationMetadata(legacy).archived)
        WorkspaceRegistry(context).use { assertTrue("Selecting central cannot create a worker workspace", it.listWorkspaces().isEmpty()) }
    }

    @Test fun warmWorkerIntentRedirectsToCentralWithoutOpeningTheWorkerAsAChat() {
        store.appendConversationMessage(execution, "user", "Worker-only ledger fixture")
        val workerBefore = transcript(execution)
        val legacyBefore = transcript(legacy)
        start()
        await { AgentRunUiState.state.value.sessionId == legacy }
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
        await { AgentRunUiState.state.value.sessionId == legacy }
        invoke("openCentralWorkspaceChat")
        awaitCentralEmpty()
        assertEquals(legacy, activity.intent.getStringExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION))
        controller!!.recreate()
        activities += activity
        awaitCentralEmpty()
    }

    @Test fun emptyCentralSelectionPreservesHiddenLiveOwnerAndReturningRestoresItsRun() {
        start()
        await { AgentRunUiState.state.value.sessionId == legacy && AgentRunUiState.state.value.events.isNotEmpty() }
        val generation = 501L
        AgentRunUiState.beginServiceRun(legacy, "Synthetic live owner", emptyList(), false, generation)
        invoke("openCentralWorkspaceChat")
        awaitCentralEmpty()
        assertEquals(legacy, AgentRunUiState.state.value.interactiveOwnerSessionId)
        assertFalse(AgentRunUiState.state.value.running)
        assertNull(mainJarvysMascotMode(AgentRunUiState.state.value, WorkspaceIdentity.CENTRAL_SESSION, true))
        AgentRunUiState.restoreSession(legacy, store.readConversationTimeline(legacy))
        assertTrue(AgentRunUiState.state.value.running)
        assertEquals(legacy, AgentRunUiState.state.value.interactiveOwnerSessionId)
        AgentRunUiState.stopPendingGeneration(legacy, generation)
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
        val original = transcript(legacy)
        start()
        await { selected() == legacy && AgentRunUiState.state.value.sessionId == legacy &&
            AgentRunUiState.state.value.events.isNotEmpty() }
        settleNativeFrame()
        val draftText = "Unsent independent draft survives workspace browsing"
        compose.onNode(hasSetTextAction()).performTextInput(draftText)
        val draftBytes = "Unsent attachment retains its owner and bytes".toByteArray()
        val draftUri = Uri.parse("content://navigation-fixture/unsent.txt")
        Shadows.shadowOf(context.contentResolver).registerInputStream(draftUri, ByteArrayInputStream(draftBytes))
        val draftOwner = ViewModelProvider(activity)[AttachmentDraftViewModel::class.java]
        compose.runOnIdle { draftOwner.addUris(legacy, listOf(draftUri), ChatAttachment.Kind.FILE) }
        await { draftOwner.drafts.value.singleOrNull()?.attachment != null }
        val draftBefore = draftOwner.drafts.value.single()
        val pendingFile = requireNotNull(draftBefore.attachment)
        assertArrayEquals(draftBytes, AttachmentStore(context).resolve(legacy, pendingFile).readBytes())

        openDrawer()
        compose.onAllNodesWithTag("drawer-open-workspaces").assertCountEquals(1)
        compose.onNodeWithText(workspace.title).assertDoesNotExist()
        compose.onNodeWithTag("drawer-open-central").assertIsNotSelected()
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
        assertEquals(legacy, selected())
        assertEquals(legacy, AgentRunUiState.state.value.sessionId)
        assertEquals(listOf(draftBefore), draftOwner.drafts.value)

        openDrawer()
        compose.onNodeWithTag("drawer-open-workspaces").assertIsSelected()
        compose.onNodeWithTag("drawer-open-central").assertIsNotSelected()
        systemBack()
        compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        assertEquals(AppNavigationBackPolicy.WORKSPACES, navigation().currentDestination?.route)
        tapTag("workspace-list-row-${workspace.workspaceId}")
        awaitRoute(AppNavigationBackPolicy.COORDINATED_WORKSPACE)
        await { compose.onAllNodesWithTag("workspace-open-files").fetchSemanticsNodes().isNotEmpty() }
        settleNativeFrame()
        compose.onNodeWithTag("jarvys-back").assertIsDisplayed()
        compose.onNodeWithText(workspace.title).assertIsDisplayed()
        tapTag("workspace-open-files")
        awaitRoute(AppNavigationBackPolicy.COORDINATED_FILES)
        compose.onNodeWithText(context.getString(R.string.workspace_file_read_only)).assertIsDisplayed()
        tapTag("jarvys-back")
        awaitRoute(AppNavigationBackPolicy.COORDINATED_WORKSPACE)
        systemBack()
        awaitRoute(AppNavigationBackPolicy.WORKSPACES)
        compose.onNodeWithContentDescription(context.getString(R.string.drawer_open)).assertIsDisplayed()
        compose.onNodeWithTag("jarvys-back").assertDoesNotExist()
        systemBack()
        awaitRoute(AppNavigationBackPolicy.CHAT_ROOT)
        compose.onNode(hasSetTextAction()).assertTextEquals(draftText)
        compose.onNodeWithTag("pending-attachment-${draftBefore.id}").assertIsDisplayed()
        assertSame(draftOwner, ViewModelProvider(activity)[AttachmentDraftViewModel::class.java])
        assertEquals(listOf(draftBefore), draftOwner.drafts.value)
        assertEquals(original, transcript(legacy))
        assertEquals(legacy, selected())
        assertEquals(legacy, AgentRunUiState.state.value.sessionId)
        assertArrayEquals(bytes, AttachmentStore(context).resolve(legacy, attachment).readBytes())
        assertArrayEquals(draftBytes, AttachmentStore(context).resolve(legacy, pendingFile).readBytes())
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
        await { selected() == legacy && AgentRunUiState.state.value.sessionId == legacy }
        openDrawer()
        tapTag("drawer-open-workspaces")
        awaitRoute(AppNavigationBackPolicy.WORKSPACES)
        val firstId = rows.first().workspaceId
        val lastId = rows.last().workspaceId
        await { compose.onAllNodesWithTag("workspace-list-row-$firstId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-next-page"))
        settleNativeFrame()
        tapTag("workspace-next-page")
        await { compose.onAllNodesWithTag("workspace-list-row-$lastId").fetchSemanticsNodes().isNotEmpty() }
        settleNativeFrame()
        compose.onNodeWithTag("workspace-list-row-$lastId").assertIsDisplayed()
        compose.onNodeWithTag("workspace-list-row-$firstId").assertDoesNotExist()
        compose.onNodeWithTag("workspace-next-page").assertDoesNotExist()
        tapTag("workspace-previous-page")
        await { compose.onAllNodesWithTag("workspace-list-row-$lastId").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("workspace-list-loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-list-row-$firstId"))
        settleNativeFrame()
        compose.onNodeWithTag("workspace-list-row-$firstId").assertIsDisplayed()
        compose.onNodeWithTag("workspace-list").performScrollToNode(hasTestTag("workspace-next-page"))
        settleNativeFrame()
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
        assertEquals(legacy, selected())
        assertEquals(legacy, AgentRunUiState.state.value.sessionId)
    }

    private fun navigation(): NavHostController = MainActivity::class.java.getDeclaredField("activeNavController")
        .apply { isAccessible = true }.get(activity) as NavHostController

    private fun awaitRoute(route: String) {
        await { navigation().currentDestination?.route == route }
        settleNativeFrame()
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
        MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *args)

    private fun executor(value: MainActivity, name: String): ExecutorService =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(value) as ExecutorService

    private fun selected(): String? = context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
        .getString("active_session_id", null)

    private fun transcript(session: String): List<Pair<String, String>> = store.readConversationTimeline(session)
        .map { it.messageId to "${it.kind}:${it.text}" }

    private fun awaitCentralEmpty() = await {
        selected() == WorkspaceIdentity.CENTRAL_SESSION &&
            AgentRunUiState.state.value.sessionId == WorkspaceIdentity.CENTRAL_SESSION &&
            AgentRunUiState.state.value.events.isEmpty()
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            compose.mainClock.advanceTimeBy(48)
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("Workspace routing did not settle: selected=${selected()}; state=${AgentRunUiState.state.value}", condition())
    }
}
