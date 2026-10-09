package com.jarvys.agent

import com.jarvys.agent.crew.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CrewModelPhaseTest {
    @Test fun loopEmitsWaitBeforeCallingModelAndResponseOnlyAfterItReturns() {
        val stages = mutableListOf<String>()
        val loop = CoreAgentLoop(CoreAgentLoop.Model { _, _, _, _ ->
            assertEquals(listOf("model_wait"), stages)
            ModelReply("Complete", emptyList())
        }, CoreToolRegistry(emptyList()), "", "phase-loop")
        val result = loop.run("Work", emptyList(), CancellationToken.crewChild(),
            CoreAgentLoop.ProgressListener { stage, _ -> stages += stage })
        assertEquals("COMPLETED", result.outcome)
        assertTrue(stages.indexOf("model_response") > stages.indexOf("model_wait"))
        assertEquals("answer", stages.last())
    }

    @Test fun modelWaitIsVisibleBeforeCompletionAndStopRejectsLatePhases() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tools = CoreToolRegistry(emptyList())
        val factory = CrewManager.LoopFactory { _, registry, incoming ->
            CoreAgentLoop(CoreAgentLoop.Model { _, _, _, token ->
                entered.countDown()
                try { release.await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                token.throwIfCancelled()
                ModelReply("Done", emptyList())
            }, registry, "", "phase-session", CorePromptBudget.standard(), null,
                CoreAgentLoop.Limits.UNBOUNDED, incoming, null)
        }
        val manager = CrewManager("phase-session", tools, { _, _ -> tools }, factory, null)
        val leaked = AtomicInteger()
        val parent = CoreAgentLoop.ProgressListener { _, _ -> leaked.incrementAndGet() }
        manager.configure(tools, { _, _ -> tools }, factory, null,
            { bot -> CoreAgentRuntime.crewProgress(parent, bot, manager) })
        try {
            val bot = manager.spawn("custom", "Inspect the source", emptyList(), "Inspector")
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fun visible() = manager.missionSnapshots().single().bots.single()
            assertEquals("RUNNING", visible().status)
            assertEquals("model_wait", visible().phase)
            assertTrue(visible().lastProgressAtMillis > 0L)
            assertFalse(bot.token.isCancelled)
            val listener = CoreAgentRuntime.crewProgress(parent, bot, manager)
            listener.onProgress("thinking", "private reasoning")
            assertEquals("model_wait", visible().phase)
            listener.onCompactionStarted("private trigger")
            assertEquals("compacting", visible().phase)
            listener.onCompactionCompleted("private summary", 2, "private mode")
            assertEquals("compacted", visible().lastProgress)
            val stamp = visible().lastProgressAtMillis
            listener.onProgress("model_wait", "private text")
            assertEquals("model_wait", visible().phase)
            assertEquals(stamp, visible().lastProgressAtMillis)
            val row = manager.missionSnapshots().single().toJson()
            assertFalse(row.toString().contains("private"))
            val restored = CrewMissionSnapshot.fromJson(row)!!
            assertEquals("model_wait", restored.bots.single().phase)
            assertEquals(stamp, restored.bots.single().lastProgressAtMillis)
            val interrupted = restored.interrupted().bots.single()
            assertEquals("", interrupted.phase)
            assertEquals("compacted", interrupted.lastProgress)
            assertFalse(interrupted.active())
            listener.onCompactionFailed("private error")
            assertEquals("compaction_error", visible().lastProgress)
            assertEquals(0, leaked.get())
            manager.stop(bot.id)
            bot.awaitTermination()
            assertEquals("", visible().phase)
            val finalProgress = visible().lastProgressAtMillis
            listener.onProgress("model_wait", "late")
            listener.onCompactionStarted("late")
            assertEquals("", visible().phase)
            assertEquals(finalProgress, visible().lastProgressAtMillis)
        } finally {
            release.countDown()
            manager.close()
        }
    }

    @Test fun oldListenerCannotOverwriteThePhaseAfterSameBotReanimation() {
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val calls = AtomicInteger()
        val tools = CoreToolRegistry(emptyList())
        val factory = CrewManager.LoopFactory { _, registry, incoming ->
            CoreAgentLoop(CoreAgentLoop.Model { _, _, _, token ->
                val first = calls.incrementAndGet() == 1
                (if (first) firstEntered else secondEntered).countDown()
                try { (if (first) firstRelease else secondRelease).await() }
                catch (_: InterruptedException) { token.throwIfCancelled() }
                ModelReply("Done", emptyList())
            }, registry, "", "phase-reanimation", CorePromptBudget.standard(), null,
                CoreAgentLoop.Limits.UNBOUNDED, incoming, null)
        }
        val manager = CrewManager("phase-reanimation", tools, { _, _ -> tools }, factory, null)
        manager.configure(tools, { _, _ -> tools }, factory, null,
            { bot -> CoreAgentRuntime.crewProgress(null, bot, manager) })
        try {
            val bot = manager.spawn("custom", "Inspect", emptyList(), "Inspector")
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
            val stale = CoreAgentRuntime.crewProgress(null, bot, manager)
            firstRelease.countDown()
            bot.awaitTermination()
            assertEquals(CrewManager.Status.DONE, bot.status())
            manager.sendUserMessage(bot.id, "Continue the same task")
            assertTrue(secondEntered.await(10, TimeUnit.SECONDS))
            fun visible() = manager.missionSnapshots().single().bots.single()
            assertEquals("model_wait", visible().phase)
            val stamp = visible().lastProgressAtMillis
            stale.onCompactionStarted("old cycle")
            stale.onProgress("model_response", "old response")
            stale.onToolProgress("tool_error", "old-call", "old-tool", "old observation", null, null)
            assertEquals("model_wait", visible().phase)
            assertEquals(stamp, visible().lastProgressAtMillis)
            secondRelease.countDown()
            bot.awaitTermination()
            assertEquals("", visible().phase)
        } finally {
            firstRelease.countDown(); secondRelease.countDown(); manager.close()
        }
    }

    @Test fun phaseSerializationIsAdditiveAndNeverTrustsArbitraryPersistedText() {
        val bot = CrewBotSnapshot("b", "r", "Role", "Bot", "amber", "Task", "RUNNING", "", "", "",
            emptyList(), 1L, 0L, false, "", false, "model_wait", "tool_result", 42L)
        val mission = CrewMissionSnapshot("m", "c", "p", "Task", "RUNNING", "", 1L, 0L, listOf(bot), emptyList())
        val row = mission.toJson()
        val saved = row.getJSONArray("bots").getJSONObject(0)
        saved.put("phase", "private reasoning").put("lastProgress", "private summary")
        val clean = CrewMissionSnapshot.fromJson(row)!!.bots.single()
        assertEquals("", clean.phase)
        assertEquals("", clean.lastProgress)
        saved.remove("phase"); saved.remove("lastProgress"); saved.remove("lastProgressAtMillis")
        val legacy = CrewMissionSnapshot.fromJson(row)!!.bots.single()
        assertEquals("", legacy.phase)
        assertEquals(0L, legacy.lastProgressAtMillis)
        for (status in listOf("DONE", "STOPPED", "FAILED", "PARTIAL", "INTERRUPTED", "WAITING")) {
            saved.put("status", status).put("phase", "model_wait")
            assertEquals("", CrewMissionSnapshot.fromJson(row)!!.bots.single().phase)
        }
    }
}
