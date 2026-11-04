package com.jarvys.agent

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.canvas.ChatCanvasPreferences
import com.jarvys.agent.canvas.ChatCanvasWebView
import com.jarvys.agent.chatdb.CentralSubmissionSnapshot
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ProactiveReplyAcceptance
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.proactive.ProactiveActionReceiver
import com.jarvys.agent.proactive.ProactiveInteractionDispatcher
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mozilla.javascript.Context as RhinoContext
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real typed Room acceptance -> existing rendered retry -> native recovery permit -> Central service.
 * Canvas executes the bundled JS with a test-only DOM adapter and the actual installed WebView action
 * interface. This is host DOM/action evidence, not Chromium rendering, pixels, device or live-provider evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TreeNativeTestApplication::class, shadows = [ArtifactOsShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ProactiveTypedRenderedRecoveryTest {
    private val storage = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule()).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val session = WorkspaceIdentity.CENTRAL_SESSION
    private val thread = "typed-rendered-recovery"
    private val text = "Recover the existing typed thread reply"
    private val store get() = LocalRunStore(context)
    private fun <T> db(body: () -> T): T = storage.onDatabaseWorker(body)
    private val displayed = mutableStateOf<AgentRunUiEvent?>(null, referentialEqualityPolicy())
    private val callbacks = CopyOnWriteArrayList<Pair<AgentRunUiEvent, Boolean>>()
    private val providerCalls = AtomicInteger()
    private val providerHistory = CopyOnWriteArrayList<List<ConversationTurn>>()
    private lateinit var executor: ExecutorService
    private lateinit var drafts: AttachmentDraftViewModel
    private lateinit var viewModels: androidx.lifecycle.ViewModelStore
    private var serviceController: org.robolectric.android.controller.ServiceController<AgentForegroundService>? = null
    private var canvas = false
    private var canvasHost: RenderedCanvasHost? = null
    private var lastCanvasScript: String? = null

    @Before fun setUp() {
        check(ProactiveReplyHandoffPolicy.admissionEnabled()) { "Requires completed native D3 activation; no gate override" }
        db {
            FreshStorageBootstrap.ensure(context)
            (context as TreeNativeTestApplication).connectionAccess().initializeAsync().get(30, TimeUnit.SECONDS)
        }
        AgentRunUiState.resetSession(session)
        AgentRunUiState.showSession(session)
        UserDecisionUiAvailability.setChatVisible(true)
        Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        drainStarts()
        executor = Executors.newSingleThreadExecutor()
        val noLegacyWrites = object : AttachmentMessageSubmitter {
            override fun persistMessage(application: Application, session: String, text: String,
                attachments: List<ChatAttachment>): String = error("Recovery must not append another USER")
            override fun startAgent(application: Application, session: String, messageId: String,
                skills: ArrayList<String>) = error("Recovery must not use legacy dispatch")
        }
        drafts = AttachmentDraftViewModel(context as Application, noLegacyWrites, executor).apply { switchSession(session) }
        viewModels = androidx.lifecycle.ViewModelStore().apply { put("rendered-typed-recovery", drafts) }
    }

    @After fun tearDown() {
        canvasHost?.close()
        if (::executor.isInitialized) executor.submit {}.get(30, TimeUnit.SECONDS)
        if (::viewModels.isInitialized) viewModels.clear()
        if (::executor.isInitialized) executor.shutdownNow()
        serviceController?.let { controller ->
            AgentForegroundService.stopRun(session)
            controller.get().awaitStopTasksForTesting().get(30, TimeUnit.SECONDS)
            controller.get().awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
            controller.destroy()
        }
        ProactiveReplyAdmission.cancelCapturedInteractions(session)
        UserDecisionUiAvailability.setChatVisible(false)
        AgentRunUiState.resetSession(session)
        ChatCanvasPreferences.setEnabled(context, false)
        drainStarts()
    }

    @Test fun typedPreparedComposeRetryRejectsStaleDisplayAndRecoversTheSameUserExactlyOnce() = exercise(false)

    @Test fun typedPreparedCanvasRetryRejectsStaleDisplayAndFenceThenRecoversTheSameUserExactlyOnce() = exercise(true)

    private fun exercise(useCanvas: Boolean) {
        canvas = useCanvas
        val original = acceptThroughActualNotificationThenReopen()
        val originalUser = db { requireNotNull(store.readStoredMessage(session, original.userMessageId)) }
        val proof = ProactiveReplyAcceptance.decode(originalUser.parts.single { it.kind == ProactiveReplyAcceptance.PART_KIND }.payload)
        assertEquals("INLINE", proof.kind)
        assertEquals(thread, proof.threadKey)
        assertEquals(ProactiveReplyAcceptance.submissionId(proof.actionId), original.submissionId)
        assertEquals(ProactiveReplyAcceptance.textDigest(text), proof.textSha256)
        assertEquals(thread, originalUser.proactiveThreadKey)
        assertNull(originalUser.proactiveReplyToMessageId)
        installRealService()
        val service = requireNotNull(serviceController).get()
        db { service.runPendingInputRecoverySafely(null) }
        service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
        assertEquals("Startup must not recover accepted USER on its own", 0, providerCalls.get())
        assertTrue(drainStarts().isEmpty())

        val firstDisplay = canonicalDisplay(original.userMessageId)
        installScreen(firstDisplay)
        clickRenderedRetry()
        assertEquals(1, callbacks.size)
        assertSame(firstDisplay, callbacks.last().first)
        assertTrue(callbacks.last().second)
        val firstRecovery = awaitRecovery(original)
        assertNotNull(CentralSubmissionAdmission.find(session, original.submissionId))
        val firstStart = drainStarts().single()
        assertCentralStart(firstStart, firstRecovery)
        assertEquals("The first dispatch is deliberately not delivered", 0, providerCalls.get())

        // Close/reopen invalidates the exact first recovery permit. The typed PREPARED row remains.
        val firstPermit = requireNotNull(CentralSubmissionAdmission.find(session, original.submissionId))
        reopen()
        assertThrows(IllegalStateException::class.java) { firstPermit.requirePrepared() }
        val freshDisplay = canonicalDisplay(original.userMessageId)
        assertEquals(firstRecovery.revision, freshDisplay.centralAvailability!!.submission!!.revision)
        assertTrue(freshDisplay.centralAvailability!!.canRetry)
        assertNotEquals(firstDisplay.centralAvailability!!.recoveryFence(), freshDisplay.centralAvailability!!.recoveryFence())
        assertTrue(drainStarts().isEmpty())

        // Keep the old button rendered while native timeline evidence has advanced to the real new revision.
        val beforeStale = submissions().single()
        clickRenderedRetry()
        assertEquals(2, callbacks.size)
        assertSame(firstDisplay, callbacks.last().first)
        assertFalse("Actual rendered stale availability must not grant recovery", callbacks.last().second)
        executor.submit {}.get(30, TimeUnit.SECONDS)
        assertEquals(beforeStale, submissions().single())
        assertTrue(drainStarts().isEmpty())

        if (canvas) {
            // Update the native Canvas bridge but leave its old DOM button intact until the new JS patch runs.
            updateScreen(freshDisplay, executeCanvasPatch = false)
            val beforeFence = callbacks.size
            clickRenderedRetry()
            assertEquals("Old rendered fence must fail before the current-event callback", beforeFence, callbacks.size)
            assertEquals(beforeStale, submissions().single())
            assertTrue(drainStarts().isEmpty())
            requireNotNull(canvasHost).evaluate(requireNotNull(lastCanvasScript))
        } else {
            updateScreen(freshDisplay)
        }
        clickRenderedRetry()
        assertSame(freshDisplay, callbacks.last().first)
        assertTrue("Fresh actual rendered event authorizes native PREPARED recovery", callbacks.last().second)
        val recovered = awaitRecovery(firstRecovery)
        val permit = requireNotNull(CentralSubmissionAdmission.find(session, original.submissionId))
        permit.requirePrepared()
        assertEquals(recovered.revision, permit.revision)
        val start = drainStarts().single()
        assertCentralStart(start, recovered)
        service.onStartCommand(start, 0, 1)
        service.onStartCommand(Intent(start), 0, 2)
        service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
        val completed = submissions().single()
        assertEquals("COMPLETED", completed.state)
        assertEquals(1, providerCalls.get())
        assertEquals(original.submissionId, completed.submissionId)
        assertEquals(original.userMessageId, completed.userMessageId)
        assertEquals(original.sourceSha256, completed.sourceSha256)
        assertEquals(original.storageGeneration, completed.storageGeneration)
        assertEquals(listOf(original.userMessageId), users().map { it.messageId })
        val persisted = db { requireNotNull(store.readStoredMessage(session, original.userMessageId)) }
        assertEquals(originalUser.parts, persisted.parts)
        assertEquals(originalUser.proactiveThreadKey, persisted.proactiveThreadKey)
        assertEquals(originalUser.proactiveReplyToMessageId, persisted.proactiveReplyToMessageId)
        assertEquals(text, providerHistory.single().single { it.role == "user" && it.durableMessageId == original.userMessageId }.content)
        val completedDisplay = canonicalDisplay(original.userMessageId)
        updateScreen(completedDisplay)
        if (canvas) assertEquals(0, requireNotNull(canvasHost).retryButtonCount())
        else compose.onNodeWithTag("central-request-retry-${original.userMessageId}").assertDoesNotExist()
        assertTrue(drainStarts().isEmpty())
    }

    private fun acceptThroughActualNotificationThenReopen(): CentralSubmissionSnapshot {
        val noticeId = db { store.appendProactiveAssistantMessageIfAbsent(session, "rendered-recovery-source",
            "A canonical notice for the rendered retry", emptyList(), thread) }
        db { ProactiveInteractionDispatcher.refreshNotification(context, thread, noticeId) }
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        val action = notification.actions.single { !it.remoteInputs.isNullOrEmpty() }
        val intent = Intent(Shadows.shadowOf(action.actionIntent).savedIntent)
        val input = RemoteInput.Builder(ProactiveActionReceiver.EXTRA_INLINE_REPLY).build()
        RemoteInput.addResultsToIntent(arrayOf(input), intent,
            Bundle().apply { putCharSequence(ProactiveActionReceiver.EXTRA_INLINE_REPLY, text) })
        ProactiveActionReceiver().onReceive(context, intent)
        // Observation barrier on real receiver work, not construction of a synthetic native owner.
        val receiverExecutor = Class.forName("com.jarvys.agent.proactive.ProactiveReplyReceiverExecutor")
            .getDeclaredField("executor").apply { isAccessible = true }.get(null) as ExecutorService
        receiverExecutor.submit {}.get(30, TimeUnit.SECONDS)
        val accepted = submissions().single()
        assertEquals("PREPARED", accepted.state)
        assertEquals(1, users().size)
        assertCentralStart(drainStarts().single(), accepted)
        val permit = requireNotNull(CentralSubmissionAdmission.find(session, accepted.submissionId))
        reopen()
        assertThrows(IllegalStateException::class.java) { permit.requirePrepared() }
        assertEquals(accepted, submissions().single())
        assertNull(CentralSubmissionAdmission.find(session, accepted.submissionId))
        assertTrue(drainStarts().isEmpty())
        return accepted
    }

    private fun installScreen(event: AgentRunUiEvent) {
        ChatCanvasPreferences.setEnabled(context, canvas)
        displayed.value = event
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true }, ApprovalGate.INSTANCE)
        compose.setContent {
            MaterialTheme {
                AgentRunScreen(conversationKey = session, generatedImageSessionId = session,
                    events = listOfNotNull(displayed.value), isRunning = false, emptyReport = null,
                    connectorRegistry = registry, onOpenPreview = {}, onOpenSkillFile = {},
                    onRegenerateAssistant = { selected ->
                        callbacks += selected to drafts.recoverPreparedSubmission(selected)
                    })
            }
        }
        compose.waitForIdle()
        if (canvas) {
            compose.onNodeWithTag("chat-canvas-webview").assertIsDisplayed()
            val web = canvasView()
            compose.runOnIdle { web.pageFinished() }
            awaitCanvasScript(null)
            canvasHost = RenderedCanvasHost(context).also { it.evaluate(requireNotNull(lastCanvasScript)) }
            assertEquals(1, requireNotNull(canvasHost).retryButtonCount())
        } else compose.onNodeWithTag("central-request-retry-${event.messageId}").assertIsDisplayed()
    }

    private fun updateScreen(event: AgentRunUiEvent, executeCanvasPatch: Boolean = true) {
        val previousScript = lastCanvasScript
        compose.runOnIdle { displayed.value = event }
        compose.waitForIdle()
        if (canvas) {
            awaitCanvasScript(previousScript)
            if (executeCanvasPatch) requireNotNull(canvasHost).evaluate(requireNotNull(lastCanvasScript))
        }
    }

    private fun clickRenderedRetry() {
        if (canvas) {
            val emitted = requireNotNull(canvasHost).clickRetry()
            assertEquals("retry", emitted.getString(0))
            val web = canvasView()
            val installed = requireNotNull(Shadows.shadowOf(web).getJavascriptInterface("Android"))
            // Invoke only the real public @JavascriptInterface with values emitted by the bundled rendered button.
            installed.javaClass.getDeclaredMethod("action", String::class.java, java.lang.Long.TYPE, String::class.java)
                .apply { isAccessible = true }.invoke(installed, emitted.getString(0), emitted.getLong(1), emitted.getString(2))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        } else compose.onNodeWithTag("central-request-retry-${requireNotNull(displayed.value).messageId}").performClick()
        compose.waitForIdle()
    }

    private fun awaitCanvasScript(previous: String?) {
        compose.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(canvasView()).lastEvaluatedJavascript?.let { it != previous } == true
        }
        lastCanvasScript = Shadows.shadowOf(canvasView()).lastEvaluatedJavascript
    }

    private fun canvasView(): ChatCanvasWebView {
        fun visit(view: View): List<ChatCanvasWebView> = when (view) {
            is ChatCanvasWebView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { visit(view.getChildAt(it)) }
            else -> emptyList()
        }
        return visit(compose.activity.window.decorView).single()
    }

    private fun canonicalDisplay(userId: String): AgentRunUiEvent {
        val events = db { store.readRecentConversationTimeline(session) }
        AgentRunUiState.restoreSession(session, events)
        // Equal StateFlow snapshots may retain the previous availability instance. Select what native
        // UI actually published, never a newly read but un-published DB materialization.
        return AgentRunUiState.eventsForSession(session).single { it.kind == "user" && it.messageId == userId }
            .also { assertEquals(thread, it.proactiveThreadKey) }
    }
    private fun reopen() = db { ChatDatabaseBridge.closeInstanceForTests(context.filesDir); store.readCentralSubmissions(session); Unit }
    private fun submissions() = db { store.readCentralSubmissions(session) }
    private fun users(): List<StoredMessage> = db { store.readProactiveThreadMessages(session, thread).filter { it.role == "user" } }
    private fun awaitRecovery(before: CentralSubmissionSnapshot): CentralSubmissionSnapshot {
        executor.submit {}.get(30, TimeUnit.SECONDS)
        return submissions().single().also {
            assertEquals("PREPARED", it.state)
            assertEquals(before.revision + 1, it.revision)
            assertEquals(before.userMessageId, it.userMessageId)
            assertEquals(before.submissionId, it.submissionId)
            assertEquals(before.sourceSha256, it.sourceSha256)
            assertEquals(before.storageGeneration, it.storageGeneration)
            assertEquals(before.originalAdmissionProcess, it.originalAdmissionProcess)
            assertNotNull(it.recoveryReceipt)
            assertEquals(1, users().size)
        }
    }
    private fun drainStarts(): List<Intent> = buildList {
        val app = Shadows.shadowOf(context as Application)
        while (true) add(app.nextStartedService ?: break)
    }
    private fun assertCentralStart(intent: Intent, row: CentralSubmissionSnapshot) {
        assertEquals("com.jarvys.agent.CENTRAL_SUBMISSION", intent.action)
        assertEquals(row.submissionId, intent.getStringExtra("central_submission_id"))
        assertEquals(session, intent.getStringExtra("chat_session_id"))
        assertNull(intent.getStringExtra("chat_user_message_id"))
        assertNull(intent.getStringExtra("agent_goal"))
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }
    private fun installRealService() {
        serviceController = Robolectric.buildService(AgentForegroundService::class.java).create()
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<com.jarvys.agent.device.ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = error("Conversation provider route required")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<com.jarvys.agent.device.ScreenData>, tools: List<ToolSpec>, sessionId: String,
                token: CancellationToken): ModelReply {
                token.throwIfCancelled()
                providerCalls.incrementAndGet()
                providerHistory += history.toList()
                return ModelReply("The typed reply recovered through the rendered control", emptyList())
            }
        }
        serviceController!!.get().runtimeFactory = AgentForegroundService.RuntimeFactory { app, id, skills ->
            CoreAgentRuntime(app, id, skills, CorePromptBudget.standard(), provider)
        }
    }
}

/** Executes the unmodified bundled JS renderer; supplies only DOM operations, not event data or authority. */
private class RenderedCanvasHost(context: Context) : AutoCloseable {
    private val engine = RhinoContext.enter().also { it.optimizationLevel = -1 }
    private val scope = engine.initStandardObjects()
    init {
        evaluate(DOM)
        evaluate(context.assets.open("chat_canvas/canvas.js").bufferedReader().use { it.readText() })
    }
    fun evaluate(source: String): String = engine.evaluateString(scope, source, "typed-rendered-recovery.js", 1, null).toString()
    fun retryButtonCount(): Int = evaluate("__retryButtons().length").toDouble().toInt()
    fun clickRetry(): JSONArray = JSONArray(evaluate("__clickRetry(); JSON.stringify(__emitted.shift());"))
    override fun close() { RhinoContext.exit() }

    companion object {
        private val DOM = """
            var window = this;
            var performance = {now: function () { return 0; }};
            var requestAnimationFrame = function (callback) { callback(0); };
            var addEventListener = function () {};
            var __emitted = [];
            var Android = { action: function (action, seq, value) { __emitted.push([action, seq, value]); },
                benchmarkFirstPaint: function () {}, requestOlderPage: function () {} };
            function Element(tag) {
                this.tagName = tag; this.children = []; this.childNodes = this.children; this.parentNode = null;
                this.style = {}; this.attributes = {}; this.listeners = {}; this.className = "";
                this.clientHeight = 900; this.clientWidth = 400; this.scrollHeight = 0; this.scrollTop = 0;
            }
            Element.prototype.setAttribute = function (key, value) { this.attributes[key] = String(value); };
            Element.prototype.getAttribute = function (key) { return this.attributes[key]; };
            Element.prototype.addEventListener = function (name, callback) { this.listeners[name] = callback; };
            Element.prototype.removeChild = function (child) { var i = this.children.indexOf(child);
                if (i >= 0) this.children.splice(i, 1); child.parentNode = null; return child; };
            Element.prototype.insertBefore = function (child, before) {
                if (child.parentNode) child.parentNode.removeChild(child);
                var i = before ? this.children.indexOf(before) : this.children.length;
                this.children.splice(i < 0 ? this.children.length : i, 0, child); child.parentNode = this; return child;
            };
            Element.prototype.appendChild = function (child) { return this.insertBefore(child, null); };
            Element.prototype.replaceChild = function (child, old) {
                var i = this.children.indexOf(old); this.removeChild(old);
                return this.insertBefore(child, this.children[i] || null);
            };
            Element.prototype.getBoundingClientRect = function () { return {height: 0}; };
            Element.prototype.querySelectorAll = function (selector) {
                var found = [];
                function walk(node) { node.children.forEach(function (child) {
                    if (selector === ".message" && (" " + child.className + " ").indexOf(" message ") >= 0) found.push(child);
                    walk(child);
                }); }
                walk(this); return found;
            };
            Element.prototype.click = function () { if (this.listeners.click) this.listeners.click(); };
            Element.prototype.scrollTo = function (options) { this.scrollTop = options.top; };
            var __ids = {};
            ["timeline", "items", "top-spacer", "bottom-spacer", "jump-to-end", "history-status"].forEach(function (id) {
                __ids[id] = new Element("div");
            });
            var document = {createElement: function (tag) { return new Element(tag); },
                getElementById: function (id) { return __ids[id]; }, addEventListener: function () {}};
            function __retryButtons() {
                var found = [];
                function walk(node) { node.children.forEach(function (child) {
                    if (child.tagName === "button" && child.textContent === "Reintentar") found.push(child);
                    walk(child);
                }); }
                walk(__ids.items); return found;
            }
            function __clickRetry() { var buttons = __retryButtons();
                if (buttons.length !== 1) throw new Error("Expected exactly one rendered retry button"); buttons[0].click(); }
        """.trimIndent()
    }
}
