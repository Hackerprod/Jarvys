package com.jarvys.agent

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import com.jarvys.agent.apkfactory.FactoryInstallApplication
import com.jarvys.agent.chatdb.CentralSubmissionSnapshot
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.connectors.ConnectionAccess
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorState
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.proactive.ProactiveActionReceiver
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Host fixture for compatibility tests. It delivers only intents captured from a posted notification;
 * the production receiver owns NativeDelivery, and production service/runtime own claim and completion.
 * Provider response and Android grants are synthetic. No persistence or authority gate is bypassed.
 */
class ProactiveNativeServiceFixture(
    private val context: Application,
    private val storage: ChatTestStore,
) : AutoCloseable {
    private val session = WorkspaceIdentity.CENTRAL_SESSION
    private val store get() = LocalRunStore(context)
    private val calls = CopyOnWriteArrayList<ObservedCall>()
    private val runtimeFailure = AtomicReference<Throwable>()
    private var startId = 0
    private val controller: org.robolectric.android.controller.ServiceController<AgentForegroundService>
    private val service: AgentForegroundService

    data class Accepted(val submission: CentralSubmissionSnapshot, val user: StoredMessage, val serviceIntent: Intent) {
        val userMessageId get() = user.messageId
        val text get() = user.text
    }
    data class ObservedCall(val prompt: String, val transcript: List<ConversationTurn>, val tools: List<String>)
    data class Completed(val call: ObservedCall, val assistant: StoredMessage)

    init {
        check(ProactiveReplyHandoffPolicy.admissionEnabled()) { "Native compatibility requires actual post-D3 activation" }
        check(context is FactoryInstallApplication) { "Compatibility must retain the real Application and startup bootstrap" }
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        db {
            FreshStorageBootstrap.ensure(context)
            val access = requireNotNull(ConnectionAccess.find(context)) { "Real Application connection owner is unavailable" }
            access.initializeAsync().get(30, TimeUnit.SECONDS)
            assertTrue("Real startup bootstrap completed", FreshStorageBootstrap.isReady(context))
            assertTrue("Real application-owned connection ledger is ready", access.isReady())
            val registry = ConnectorRegistry.get(context)
            for (id in listOf("contacts", "calendar")) {
                assertEquals(ConnectorRegistry.ConnectorTransitionResult.Status.COMPLETED, registry.connect(id).status)
                assertEquals(ConnectorState.CONNECTED, registry.states.value[id])
            }
        }
        // Only model metadata is cached. No credentials, network client or external effect is used.
        ProviderSettings(context).apply {
            setProvider(ProviderSettings.Provider.OPENROUTER)
            setOpenRouterModel("offline/proactive-compatibility")
        }
        check(context.getSharedPreferences("provider_context_metadata", 0).edit()
            .putInt("model_offline/proactive-compatibility", 32768)
            .putLong("fetched_offline/proactive-compatibility", System.currentTimeMillis()).commit())
        AgentRunUiState.resetSession(session)
        assertTrue("Fixture starts without unrelated service dispatch", drainStarts().isEmpty())
        controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        service = controller.get()
        val provider = object : ModelProviderClient {
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                error("Real conversation provider path required")

            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                token.throwIfCancelled()
                assertEquals(session, sessionId)
                calls += ObservedCall(userPrompt, history.toList(), tools.map { it.name })
                return ModelReply("Calendar action is ready for review.", emptyList())
            }
        }
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { app, id, skills ->
            CoreAgentRuntime(app, id, skills, CorePromptBudget.standard(), provider)
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { runtime, goal, history, attachments, token, progress ->
            try { runtime.runMainChat(goal, history, attachments, token, progress) }
            catch (failure: Throwable) {
                runtimeFailure.set(failure)
                throw failure
            }
        }
    }

    private fun <T> db(body: () -> T): T = storage.onDatabaseWorker(body)
    private fun submissions() = db { store.readCentralSubmissions(session) }
    private fun drainStarts(): List<Intent> = buildList {
        val application = Shadows.shadowOf(context)
        while (true) add(application.nextStartedService ?: break)
    }
    private fun receiverDiagnostics(): String = ShadowLog.getLogsForTag("JarvysProactive").joinToString("\n") {
        it.msg + (it.throwable?.let { cause -> "\n" + cause.stackTraceToString() } ?: "")
    }

    fun optionIntent(notification: Notification, index: Int): Intent =
        Intent(Shadows.shadowOf(notification.actions.filter { it.remoteInputs.isNullOrEmpty() }[index].actionIntent).savedIntent)

    fun inlineIntent(notification: Notification, text: String): Intent {
        val action = notification.actions.single { !it.remoteInputs.isNullOrEmpty() }
        val intent = Intent(Shadows.shadowOf(action.actionIntent).savedIntent)
        val input = RemoteInput.Builder(ProactiveActionReceiver.EXTRA_INLINE_REPLY).build()
        RemoteInput.addResultsToIntent(arrayOf(input), intent,
            Bundle().apply { putCharSequence(ProactiveActionReceiver.EXTRA_INLINE_REPLY, text) })
        return intent
    }

    /** The barrier observes completion of real receiver tasks, not a fabricated NativeInteraction. */
    private fun deliver(intent: Intent) {
        ProactiveActionReceiver().onReceive(context, Intent(intent))
        val type = Class.forName("com.jarvys.agent.proactive.ProactiveReplyReceiverExecutor")
        val executor = type.getDeclaredField("executor").apply { isAccessible = true }.get(null) as ExecutorService
        executor.submit {}.get(30, TimeUnit.SECONDS)
    }

    fun accept(intent: Intent): Accepted {
        val before = submissions().map { it.submissionId }.toSet()
        deliver(intent)
        val added = submissions().filterNot { it.submissionId in before }
        assertEquals("Native receiver must atomically accept exactly one USER: ${receiverDiagnostics()}", 1, added.size)
        val submission = added.single()
        assertEquals("PREPARED", submission.state)
        val user = db { requireNotNull(store.readStoredMessage(session, submission.userMessageId)) }
        assertEquals("user", user.role)
        val starts = drainStarts()
        assertEquals("Exactly one native service dispatch: ${receiverDiagnostics()}", 1, starts.size)
        val serviceIntent = starts.single()
        assertEquals("com.jarvys.agent.CENTRAL_SUBMISSION", serviceIntent.action)
        assertEquals(submission.submissionId, serviceIntent.getStringExtra("central_submission_id"))
        assertEquals(session, serviceIntent.getStringExtra("chat_session_id"))
        assertNull(serviceIntent.getStringExtra("agent_goal"))
        assertNull(serviceIntent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull("Legacy stored-message route is not accepted authority", serviceIntent.getStringExtra("chat_user_message_id"))
        return Accepted(submission, user, serviceIntent)
    }

    fun assertNoAcceptance(intent: Intent) {
        val before = submissions()
        val beforeUsers = db { store.readConversationMessages(session).filter { it.optString("role") == "user" }.map { it.toString() } }
        deliver(intent)
        assertEquals(before, submissions())
        assertEquals(beforeUsers, db { store.readConversationMessages(session).filter { it.optString("role") == "user" }.map { it.toString() } })
        assertTrue("Invalid or duplicate affordance must not dispatch", drainStarts().isEmpty())
    }

    fun run(accepted: Accepted): Completed {
        val before = calls.size
        service.onStartCommand(accepted.serviceIntent, 0, ++startId)
        service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
        runtimeFailure.get()?.let { throw AssertionError("Real native runtime failed", it) }
        assertEquals("Exactly one provider request for this accepted reply", before + 1, calls.size)
        val completed = submissions().single { it.submissionId == accepted.submission.submissionId }
        assertEquals("COMPLETED", completed.state)
        assertEquals(accepted.userMessageId, completed.userMessageId)
        val assistant = db { store.readProactiveThreadMessages(session, requireNotNull(accepted.user.proactiveThreadKey))
            .single { it.role == "assistant" && it.ownerUserMessageId == accepted.userMessageId && it.status == "COMPLETED" } }
        val call = calls.last()
        assertEquals("The canonical accepted USER reaches the provider exactly once", accepted.text,
            call.transcript.single { it.role == "user" && it.durableMessageId == accepted.userMessageId }.content)
        assertEquals("The current input is not duplicated in the prompt", "", call.prompt)
        assertTrue("Native Central keeps normal read capability", "contacts_search" in call.tools)
        assertTrue("Native Central keeps approval-gated write capability", "calendar_create_event" in call.tools)
        assertFalse("Accepted USER must not re-enter silent review", "proactive_respond" in call.tools)
        return Completed(call, assistant)
    }

    override fun close() {
        try {
            AgentForegroundService.stopRun(session)
            service.awaitStopTasksForTesting().get(30, TimeUnit.SECONDS)
            service.awaitSessionIdleForTesting(session).get(30, TimeUnit.SECONDS)
        } finally {
            service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            controller.destroy()
        }
    }
}
