package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContinuousMissionServiceTest {
    @Test fun realServiceRetiresLegacyDeadlineAndKeepsWorkAcrossThreeVirtualDays() {
        exercise(stop = false)
    }

    @Test fun genuineStopStillCancelsServiceAndCrewAfterOldDeadline() {
        exercise(stop = true)
    }

    private fun exercise(stop: Boolean) {
        StopController.getInstance().stopRun()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildService(AgentForegroundService::class.java).create()
        val service = controller.get()
        val session = "ux38-service-${System.nanoTime()}"
        val prefs = context.getSharedPreferences("jarvys_ui_preferences", Context.MODE_PRIVATE)
        prefs.edit().putInt("agent_timeout_seconds", 900).commit()
        val store = LocalRunStore(context)
        val id = store.appendConversationMessage(session, "user", "Continue a long task")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captain = AtomicReference<CancellationToken>()
        val botRef = AtomicReference<CrewManager.Bot>()
        val empty = CoreToolRegistry(emptyList())
        val crew = CrewManager(session, empty, { _, _ -> empty }, { _, tools, incoming ->
            CoreAgentLoop(object : CoreAgentLoop.Model {
                override fun complete(history: List<ConversationTurn>, prompt: String,
                                      declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                    entered.countDown()
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); token.throwIfCancelled() }
                    token.throwIfCancelled()
                    return ModelReply("verified worker result", emptyList())
                }
            }, tools, "English test instructions", session, CorePromptBudget.standard(), null,
                CoreAgentLoop.Limits.UNBOUNDED, incoming, null)
        }, null)
        service.runtimeFactory = AgentForegroundService.RuntimeFactory { _, _, _, _ ->
            CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        }
        AgentRunUiState.beginRun(session, "Continue a long task")
        service.mainChatRunner = AgentForegroundService.MainChatRunner { _, _, _, _, token, _ ->
            captain.set(token)
            crew.attachCaptain(token)
            val bot = crew.spawn("custom", "Long independent work", emptyList(), "Long worker")
            botRef.set(bot)
            crew.waitFor(listOf(bot.id), CrewManager.WaitMode.ALL, token)
            token.throwIfCancelled()
            // A partial enclosing task avoids scheduling unrelated post-answer reflection.
            CoreAgentLoop.Result("ux38-service", "The observed worker finished; further work remains", 1, "PARTIAL")
        }
        try {
            service.onStartCommand(AgentForegroundService.storedChatMessageIntent(context, session, id), 0, 1)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val didEnter = entered.await(5, TimeUnit.SECONDS)
            val stacks = if (didEnter) "" else Thread.getAllStackTraces().entries
                .filter { it.key.name.contains("Jarvys") || it.key.name.contains("jarvys-crew") }
                .joinToString("\n") { "${it.key.name}: ${it.value.joinToString("; ")}" }
            assertTrue("Real service entered the worker; captain=${captain.get()} messages=${store.readConversationMessages(session)} state=${AgentRunUiState.state.value.outcome} $stacks", didEnter)
            assertFalse(prefs.contains("agent_timeout_seconds"))
            assertTrue("No hidden elapsed-time scheduler survives", AgentForegroundService::class.java.declaredFields
                .none { ScheduledExecutorService::class.java.isAssignableFrom(it.type) })
            ShadowSystemClock.advanceBy(Duration.ofDays(3))
            assertFalse(captain.get().isCancellationRequested)
            assertFalse(botRef.get().token.isCancellationRequested)
            assertEquals(CrewManager.Status.RUNNING, botRef.get().status())
            assertTrue(AgentRunUiState.state.value.running)
            if (stop) StopController.getInstance().stopRun() else release.countDown()
            worker(service).submit {}.get(10, TimeUnit.SECONDS)
            botRef.get().awaitTermination()
            assertFalse(AgentRunUiState.state.value.running)
            assertEquals(if (stop) CrewManager.Status.STOPPED else CrewManager.Status.DONE, botRef.get().status())
            assertEquals(if (stop) "STOPPED" else "PARTIAL", AgentRunUiState.state.value.outcome)
            assertTrue(store.readConversationMessages(session).any { it.optString("status") == if (stop) "STOPPED" else "PARTIAL" })
        } finally {
            StopController.getInstance().stopRun(); release.countDown(); crew.close(); controller.destroy()
        }
    }

    private fun worker(service: AgentForegroundService): ExecutorService =
        AgentForegroundService::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(service) as ExecutorService
}
