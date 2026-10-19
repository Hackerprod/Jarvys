package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentForegroundServiceMainThreadAdmissionTest {
    @get:Rule val chatTestStore = ChatTestStore(
        runTestOnWorker = false,
        registerApplicationFilesDirectory = true,
    )

    @After fun cleanup() {
        StopController.stopAllRuns()
        StopController.getInstance().stopRun()
    }

    @Test fun startCommandAdmitsAgentSystemAndUserInputsOffMainThread() {
        val guardProperty = "jarvys.unitTests.mainThreadGuardOff"
        val previousGuardProperty = System.getProperty(guardProperty)
        val previousGuardDisabledForTests = ChatDatabaseBridge.mainThreadGuardDisabledForTests
        val secretStoreField = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        val previousSecretStore = secretStoreField.get(null)
        System.clearProperty(guardProperty)
        ChatDatabaseBridge.mainThreadGuardDisabledForTests = false
        try {
            assertSame("The service start command must be delivered on Robolectric's main looper",
                Looper.getMainLooper(), Looper.myLooper())
            val context = ApplicationProvider.getApplicationContext<Context>()
            secretStoreField.set(null, SecretStore(
                context.getSharedPreferences("main-thread-admission-secrets", Context.MODE_PRIVATE)))
            val store = LocalRunStore(context)
            val suffix = System.nanoTime().toString()
            val agentSession = "main-thread-agent-$suffix"
            val systemSession = "main-thread-system-$suffix"
            val userSession = "main-thread-user-$suffix"
            val agentInputId = chatTestStore.onDatabaseWorker {
                val sourceId = "agent-source-$suffix"
                store.appendConversationInput(ConversationInput(
                    agentSession,
                    sourceId,
                    "Persisted AGENT input",
                    ConversationInput.Origin.AGENT,
                    ConversationInput.Provenance("worker-session", UUID.randomUUID().toString(),
                        "assignment", "worker", sourceId, ConversationInput.Kind.MESSAGE),
                ))
            }
            val systemInputId = chatTestStore.onDatabaseWorker {
                val sourceId = "system-source-$suffix"
                store.appendConversationInput(ConversationInput(
                    systemSession,
                    sourceId,
                    "Persisted SYSTEM input",
                    ConversationInput.Origin.SYSTEM,
                    ConversationInput.Provenance("system-session", UUID.randomUUID().toString(),
                        "assignment", "", sourceId, ConversationInput.Kind.STATUS),
                ))
            }
            val userInputId = chatTestStore.onDatabaseWorker {
                store.appendConversationMessage(userSession, "user", "Persisted USER input")
            }

            val observedOrigins = Collections.synchronizedList(mutableListOf<ConversationInput.Origin?>())
            val observedIds = Collections.synchronizedList(mutableListOf<String>())
            val runnerCalls = AtomicInteger()
            val generationBinds = AtomicInteger()

            val agentController = serviceForTest(context, agentSession, runnerCalls, generationBinds,
                observedOrigins, observedIds, store)
            val agentService = agentController.get()
            agentService.onStartCommand(storedInputIntent(context, agentSession, agentInputId), 0, 1)
            agentService.awaitSessionIdleForTesting(agentSession).get(10, TimeUnit.SECONDS)
            val agentInputState = chatTestStore.onDatabaseWorker {
                store.readConversationInputState(agentSession, agentInputId)
            }
            assertEquals("the persisted AGENT input reaches the worker runner; binds=${generationBinds.get()}, " +
                "state=$agentInputState, pending=${chatTestStore.onDatabaseWorker {
                    store.readPendingInternalConversationInputs(agentSession)
                }}, ui=${AgentRunUiState.snapshotForSession(agentSession)}, " +
                "report=${AgentForegroundService.getLastRunReport()}", 1, runnerCalls.get())
            assertEquals(ConversationInput.Origin.AGENT, observedOrigins.single())
            assertEquals(agentInputId, observedIds.single())
            assertEquals("incorporated", chatTestStore.onDatabaseWorker {
                store.readConversationInputState(agentSession, agentInputId)
            })
            agentController.destroy()

            val callsBeforeSystem = runnerCalls.get()
            val bindsBeforeSystem = generationBinds.get()
            val systemController = serviceForTest(context, systemSession, runnerCalls, generationBinds,
                observedOrigins, observedIds, store)
            val systemService = systemController.get()
            systemService.onStartCommand(storedInputIntent(context, systemSession, systemInputId), 0, 2)
            systemService.awaitSessionIdleForTesting(systemSession).get(10, TimeUnit.SECONDS)
            assertEquals("SYSTEM input does not start an agent turn", callsBeforeSystem, runnerCalls.get())
            assertEquals("SYSTEM input does not bind a run generation", bindsBeforeSystem, generationBinds.get())
            systemController.destroy()

            val userController = serviceForTest(context, userSession, runnerCalls, generationBinds,
                observedOrigins, observedIds, store)
            val userService = userController.get()
            userService.onStartCommand(storedInputIntent(context, userSession, userInputId), 0, 3)
            userService.awaitSessionIdleForTesting(userSession).get(10, TimeUnit.SECONDS)
            assertEquals("the normal USER input is processed", callsBeforeSystem + 1, runnerCalls.get())
            assertEquals("normal USER messages have no internal-input origin metadata", null, observedOrigins.last())
            assertEquals(userInputId, observedIds.last())
            userController.destroy()
        } finally {
            ChatDatabaseBridge.mainThreadGuardDisabledForTests = previousGuardDisabledForTests
            if (previousGuardProperty != null) System.setProperty(guardProperty, previousGuardProperty)
            secretStoreField.set(null, previousSecretStore)
        }
    }

    private fun serviceForTest(
        context: Context,
        sessionId: String,
        runnerCalls: AtomicInteger,
        generationBinds: AtomicInteger,
        observedOrigins: MutableList<ConversationInput.Origin?>,
        observedIds: MutableList<String>,
        store: LocalRunStore,
    ): org.robolectric.android.controller.ServiceController<AgentForegroundService> {
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        service.beforeGenerationBindForTesting = Runnable { generationBinds.incrementAndGet() }
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        }
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, history, _, _, progress ->
            runnerCalls.incrementAndGet()
            val input = history.firstOrNull { it.durableMessageId.isNotEmpty() }
            assertNotNull("the durable message is present in the turn history", input)
            observedOrigins += input!!.incomingMetadata?.origin
            observedIds += input.durableMessageId
            if (input.incomingMetadata?.origin == ConversationInput.Origin.AGENT) {
                store.markConversationMessagesIncorporated(sessionId, listOf(input.durableMessageId))
            } else {
                progress.onInitialUserMessageIncorporated()
            }
            CoreAgentLoop.Result("main-thread-admission", "processed ${input.durableMessageId}", 1, "COMPLETED")
        }
        return controller
    }

    private fun storedInputIntent(context: Context, sessionId: String, messageId: String) =
        Intent(context, AgentForegroundService::class.java)
            .setAction(AgentForegroundService.ACTION_REAL_AGENT)
            .putExtra("chat_session_id", sessionId)
            .putExtra("chat_user_message_recorded", true)
            .putExtra("chat_user_message_id", messageId)
}
