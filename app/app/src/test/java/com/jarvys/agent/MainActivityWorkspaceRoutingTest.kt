package com.jarvys.agent

import android.content.Context
import android.content.Intent
import android.os.Looper
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
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
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

    private fun start(intent: Intent = Intent(context, MainActivity::class.java)) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        activities += activity
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
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
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        assertTrue("Workspace routing did not settle: selected=${selected()}; state=${AgentRunUiState.state.value}", condition())
    }
}
