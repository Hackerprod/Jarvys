package com.jarvys.agent

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewBoard
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionNotifier
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewNotificationPolicy
import com.jarvys.agent.crew.CrewStopActions
import com.jarvys.agent.crew.CrewTools
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrewC2Test {
    private val emptyTools get() = CoreToolRegistry(emptyList())

    private fun bot(id: String, status: String) = CrewBotSnapshot(id, "analista", "Analyst", id,
        "amber", "mission", status, "", "", "", emptyList(), 1L, 0L)

    private fun snapshot(id: String, status: String, bots: List<CrewBotSnapshot>) = CrewMissionSnapshot(
        id, "session-$id", "process", "Design brief", status, "", 1L, 0L, bots, emptyList())

    @Test
    fun crewNotificationPolicyCountsAllRunningAndWaitingBotsAndUsesLocalizedPlural() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zero = CrewNotificationPolicy.evaluate(listOf(snapshot("zero", "RUNNING", listOf(
            bot("done", "DONE"), bot("stopped", "STOPPED"), bot("failed", "FAILED"), bot("interrupted", "INTERRUPTED")))))
        assertEquals(0, zero.activeBots)
        assertFalse(zero.keepService)
        assertEquals(context.getString(R.string.agent_notification_text), zero.notificationText(context))

        for (count in listOf(1, 3, 12)) {
            val bots = (0 until count).map { bot("active-$it", if (it == 0) "WAITING" else "RUNNING") } +
                listOf(bot("done-$count", "DONE"), bot("stopped-$count", "STOPPED"),
                    bot("failed-$count", "FAILED"), bot("interrupted-$count", "INTERRUPTED"))
            val state = CrewNotificationPolicy.evaluate(listOf(snapshot("count-$count", "RUNNING", bots)))
            assertEquals(count, state.activeBots)
            assertTrue(state.keepService)
            assertEquals(context.resources.getQuantityString(R.plurals.crew_active_bots, count, count),
                state.notificationText(context))
        }
        val spanish = context.createConfigurationContext(android.content.res.Configuration().apply {
            setLocale(Locale("es"))
        })
        assertEquals("Tripulación trabajando: 1 bot activo", CrewNotificationPolicy.evaluate(
            listOf(snapshot("es-one", "RUNNING", listOf(bot("es-one", "WAITING"))))).notificationText(spanish))
        assertEquals("Tripulación trabajando: 3 bots activos", CrewNotificationPolicy.evaluate(
            listOf(snapshot("es-many", "RUNNING", (1..3).map { bot("es-$it", "RUNNING") }))).notificationText(spanish))
    }

    @Test
    fun stopAllCancelsRunningWaitingAndReanimatedBotsWithoutCaptainAndIsIdempotent() {
        val waiting = CountDownLatch(1)
        val running = CountDownLatch(1)
        val reanimated = CountDownLatch(1)
        val releaseInitial = CountDownLatch(1)
        val preexistingCrewThreads = Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name.startsWith("jarvys-crew-") }.toSet()
        val manager = CrewManager("c2-stop-all", emptyTools,
            { bot, crew ->
                val board = CrewBoard(WorkspaceStore(Files.createTempDirectory("c2-board").toFile(),
                    "abcdef0123456789abcdef01"))
                CoreToolRegistry(CrewTools.bot(bot, crew, board))
            },
            { bot, tools, incoming ->
                val calls = AtomicInteger()
                CoreAgentLoop(object : CoreAgentLoop.Model {
                    override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                          declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                        when (bot.name) {
                            "Waiting" -> {
                                waiting.countDown()
                                return ModelReply("", listOf(ModelReply.Call("ask", "ask_chief", mapOf("question" to "Need guidance?"))))
                            }
                            "Running" -> {
                                running.countDown()
                                try { CountDownLatch(1).await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                                return ModelReply("done", emptyList())
                            }
                            else -> if (calls.incrementAndGet() == 1) {
                                try { releaseInitial.await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                                return ModelReply("done", emptyList())
                            } else {
                                reanimated.countDown()
                                try { CountDownLatch(1).await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                                return ModelReply("unexpected", emptyList())
                            }
                        }
                    }
                }, tools, "test", bot.id, CorePromptBudget.standard(), null,
                    CoreAgentLoop.Limits.UNBOUNDED, incoming, null)
            }, null)
        try {
            val waitingBot = manager.spawn("custom", "wait", listOf("ask_chief"), "Waiting")
            val runningBot = manager.spawn("custom", "run", emptyList(), "Running")
            val doneBot = manager.spawn("custom", "finish", emptyList(), "Reanimated")
            assertTrue(waiting.await(30, TimeUnit.SECONDS))
            assertTrue(running.await(30, TimeUnit.SECONDS))
            releaseInitial.countDown()
            doneBot.awaitTermination()
            manager.messageBus().drain("chief")
            manager.send("chief", doneBot.id, CrewMessage.Type.ANSWER, "continue", emptyList())
            assertTrue(reanimated.await(30, TimeUnit.SECONDS))
            val managerThreads = Thread.getAllStackTraces().keys.filter {
                it.isAlive && it.name.startsWith("jarvys-crew-") && it !in preexistingCrewThreads
            }
            assertTrue("Expected this manager to have started worker threads", managerThreads.isNotEmpty())
            CrewStopActions.stopAll(null, manager)
            CrewStopActions.stopAll(null, manager)
            listOf(waitingBot, runningBot, doneBot).forEach { it.awaitTermination() }
            assertTrue(listOf(waitingBot, runningBot, doneBot).all { it.status() == CrewManager.Status.STOPPED })
            manager.close()
            managerThreads.forEach { it.join() }
            assertTrue("A worker owned by this manager survived close", managerThreads.none { it.isAlive })
        } finally { releaseInitial.countDown(); manager.close() }
    }

    @Test
    fun actionStopStopsCaptainReflectionAndConversationCrew() {
        val calls = mutableListOf<String>()
        AgentStopActions.stopAll({ calls.add("captain") }, { calls.add("reflections") }, { calls.add("all-crews") })
        assertEquals(listOf("captain", "reflections", "all-crews"), calls)
        assertEquals("com.jarvys.agent.STOP", AgentForegroundService.ACTION_STOP)
        assertEquals("Stop", ApplicationProvider.getApplicationContext<Context>().getString(R.string.agent_notification_stop))
    }

    @Test
    fun completedMissionNotificationIsDeduplicatedAndRequiresBackgroundAndPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancelAll()
        val id = "notify-${System.nanoTime()}"
        val mission = snapshot(id, "SYNTHESIZED", listOf(bot("finished", "DONE")))
        assertFalse(CrewMissionNotifier.publishForState(context, mission, true, true))
        assertEquals(0, shadowOf(notificationManager).allNotifications.size)
        assertFalse(CrewMissionNotifier.publishForState(context, mission, false, false))
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
        }
        assertFalse(CrewMissionNotifier.publishIfBackground(denied, snapshot("$id-denied", "SYNTHESIZED", mission.bots)))
        assertTrue(CrewMissionNotifier.publishForState(context, mission, false, true))
        assertFalse(CrewMissionNotifier.publishForState(context, mission, false, true))
        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        assertTrue(posted.single().extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            .toString().contains("Design brief"))
        assertEquals(android.app.NotificationManager.IMPORTANCE_DEFAULT,
            shadowOf(notificationManager).notificationChannels.single { it.id == "jarvys_crew_results" }.importance)
    }

    @Test
    fun deniedForegroundStartIsCaughtAndSpanishActionResourceExists() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val denied = object : ContextWrapper(base) {
            override fun startForegroundService(service: Intent): ComponentName {
                throw IllegalStateException("Foreground service start not allowed")
            }
        }
        AgentForegroundService.ensureCrewKeepalive(denied)
        val spanish = base.createConfigurationContext(android.content.res.Configuration().apply {
            setLocale(Locale("es"))
        })
        assertEquals("Detener", spanish.getString(R.string.agent_notification_stop))
    }
}
