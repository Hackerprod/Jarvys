package com.jarvys.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.runtime.MutableState
import androidx.navigation.NavHostController
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import org.junit.Rule
import org.junit.rules.RuleChain
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewNavigationRoutes
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

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        secretSingleton.set(null, SecretStore(context.getSharedPreferences("ux43-route-test-secrets", 0)))
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        chatTestStore.onDatabaseWorker { FreshStorageBootstrap.awaitReadyBlocking(context.filesDir) }

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

    @Test fun B12_missionNotificationOpensTheMissionWithItsSessionAndRecreationKeepsItFromMissionToBot() {
        val (session, missionId) = registeredMission()
        start()
        awaitCentralEmpty()
        invoke("onNewIntent", arrayOf(Intent::class.java), arrayOf(missionIntent(missionId, session)))
        await { route() == AppNavigationBackPolicy.CREW }
        assertEquals(session, routeSession())
        await { hasTag("crew-mission-screen") }

        compose.runOnUiThread { nav().navigate(CrewNavigationRoutes.bot(missionId, "b12-bot", requireNotNull(routeSession()))) }
        await { route() == AppNavigationBackPolicy.CREW_BOT && hasTag("crew-bot-detail") }
        assertEquals(session, routeSession())

        controller!!.recreate()
        activities += activity
        await { route() == AppNavigationBackPolicy.CREW_BOT && hasTag("crew-bot-detail") }
        assertEquals("the bot route keeps the execution session through recreation", session, routeSession())
    }

    @Test fun B12_launchIntentOpensTheMissionOnceAndRecreationDoesNotReplayIt() {
        val (session, missionId) = registeredMission()
        start(missionIntent(missionId, session))
        await { route() == AppNavigationBackPolicy.CREW && hasTag("crew-mission-screen") }
        compose.runOnUiThread { nav().popBackStack() }
        await { route() == AppNavigationBackPolicy.CHAT_ROOT }

        controller!!.recreate()
        activities += activity
        awaitCentralEmpty()
        assertEquals("the launch intent was already handled", AppNavigationBackPolicy.CHAT_ROOT, route())
    }

    @Test fun B12_unresolvedMissionReferencesShowUnavailableAndNeverFallBackToCentral() {
        val (registered, _) = registeredMission()
        start()
        awaitCentralEmpty()
        val unregistered = WorkspaceIdentity.executionSessionId("22222222-2222-2222-2222-222222222222")
        listOf(
            missionIntent("ghost", unregistered),                 // workspace that was never registered
            missionIntent("ghost", ""),                            // reference that lost its session
            missionIntent("ghost", registered),                    // registered session, mission that does not exist
            missionIntent("ghost", UUID.randomUUID().toString()),  // a conversation id is not an execution session
        ).forEach { intent ->
            invoke("onNewIntent", arrayOf(Intent::class.java), arrayOf(intent))
            await { route() == AppNavigationBackPolicy.CREW && hasTag("crew-mission-unavailable") }
            assertFalse(hasTag("crew-mission-screen"))
            compose.runOnUiThread { nav().popBackStack() }
            await { route() == AppNavigationBackPolicy.CHAT_ROOT }
        }
    }

    @Test fun B12_aLaterIntentReplacesAMissionTargetThatIsStillWaitingForTheGraph() {
        start()
        awaitCentralEmpty()
        val pending = MainActivity::class.java.getDeclaredField("pendingMission").apply { isAccessible = true }
        val target = Class.forName("com.jarvys.agent.MissionTarget")
            .getDeclaredConstructor(String::class.java, String::class.java).apply { isAccessible = true }
            .newInstance("stale-mission", "stale-session")
        compose.runOnUiThread { pending.set(activity, target) }

        invoke("onNewIntent", arrayOf(Intent::class.java), arrayOf(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PROACTIVE_MESSAGE_ID, "later-message")))

        assertNull("the stale target must not capture a later intent", pending.get(activity))
        assertEquals(AppNavigationBackPolicy.CHAT_ROOT, route())
    }
    /** A workspace in the registry whose execution session records one running mission with one bot. */
    private fun registeredMission(): Pair<String, String> {
        val scope = WorkspaceRegistry(context).use { it.createWorkspace("b12-${UUID.randomUUID()}", "B12 workspace") }
        db { store.appendConversationMessage(scope.executionSessionId, "user", "Mission fixture") }
        val bot = CrewBotSnapshot("b12-bot", "worker", "Worker", "Worker", "blue", "Task", "RUNNING", "", "", "",
            emptyList(), 1L, 0L)
        db {
            store.appendCrewMissionSnapshot(CrewMissionSnapshot("b12-mission", scope.executionSessionId,
                CrewProcessIdentity.ID, "Bot mission", "RUNNING", "", 1L, 0L, listOf(bot), emptyList()))
        }
        return scope.executionSessionId to "b12-mission"
    }

    private fun missionIntent(missionId: String, session: String) = Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_OPEN_MISSION, missionId)
        .putExtra(MainActivity.EXTRA_OPEN_MISSION_SESSION, session)

    private fun nav(): NavHostController = MainActivity::class.java.getDeclaredField("activeNavController")
        .apply { isAccessible = true }.get(activity) as NavHostController

    private fun route(): String? = nav().currentDestination?.route

    private fun routeSession(): String? = nav().currentBackStackEntry?.arguments?.getString(CrewNavigationRoutes.SESSION)

    private fun hasTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun settleNativeFrame() {        compose.mainClock.advanceTimeBy(600)
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
