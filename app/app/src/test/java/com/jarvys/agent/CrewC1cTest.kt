package com.jarvys.agent

import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewTools
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CrewC1cTest {
    private fun inboxEvidence(transcript: List<ConversationTurn>) = transcript.filter {
        it.kind == ConversationTurn.Kind.TOOL_RESULT && it.toolName == CoreAgentLoop.INBOX_TOOL
    }.joinToString("\n") { it.content }

    private val emptyTools get() = CoreToolRegistry(emptyList())

    private fun botLoop(tools: CoreToolRegistry, incoming: CoreAgentLoop.TurnContextProvider,
                        complete: (List<ConversationTurn>, String, CancellationToken) -> ModelReply) =
        CoreAgentLoop(object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  declarations: List<ToolSpec>, token: CancellationToken) =
                complete(transcript, prompt, token)
        }, tools, "Deterministic Crew test", "crew-c1c-bot", CorePromptBudget.standard(), null,
            CoreAgentLoop.Limits.UNBOUNDED, incoming, null)

    private fun call(id: String, name: String, vararg values: Pair<String, Any>) =
        ModelReply.Call(id, name, linkedMapOf(*values))

    @Test
    fun threeRoundDesignerReviewerDebateUsesCaptainToolCallsAndNeverSpawnsNewBots() {
        val designerCycles = AtomicInteger()
        val reviewerCycles = AtomicInteger()
        val reviewerFirstStarted = CountDownLatch(1)
        val releaseReviewerFirst = CountDownLatch(1)
        val designerRevisionTranscript = AtomicReference<List<ConversationTurn>>()
        val reviewerInboxes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val manager = CrewManager("c1c-debate", emptyTools,
            { _, _ -> throw AssertionError("Captain test tools should not create a worker registry") },
            { _, _, _ -> throw AssertionError("Configure worker factory before spawn") }, null)
        val boardRoot = Files.createTempDirectory("crew-c1c-board").toFile()
        val board = com.jarvys.agent.crew.CrewBoard(WorkspaceStore(boardRoot, "abcdef0123456789abcdef01"))
        manager.configure(emptyTools,
            { bot, crew -> CoreToolRegistry(CrewTools.bot(bot, crew, board)) },
            { bot, tools, incoming ->
                botLoop(tools, incoming) { transcript, prompt, _ ->
                    if (bot.name == "Disenador") {
                        when (designerCycles.incrementAndGet()) {
                            1 -> ModelReply("", listOf(call("draft", "report_done", "result" to "Borrador inicial: usar fuente A.")))
                            else -> {
                                designerRevisionTranscript.set(transcript.toList())
                                assertTrue(inboxEvidence(transcript).contains("La fecha de la fuente A está desactualizada"))
                                ModelReply("", listOf(call("revision", "report_done", "result" to "Borrador corregido: usar fuente B.")))
                            }
                        }
                    } else {
                        reviewerInboxes.add(inboxEvidence(transcript))
                        when (reviewerCycles.incrementAndGet()) {
                            1 -> {
                                reviewerFirstStarted.countDown()
                                try { releaseReviewerFirst.await() } catch (_: InterruptedException) { throw java.util.concurrent.CancellationException() }
                                ModelReply("", listOf(call("ready", "report_done", "result" to "Listo para revisar el borrador.")))
                            }
                            2 -> {
                                assertTrue(inboxEvidence(transcript).contains("Borrador inicial: usar fuente A."))
                                ModelReply("", listOf(call("critique", "report_done", "result" to "La fecha de la fuente A está desactualizada; usar B.")))
                            }
                            else -> {
                                assertTrue(inboxEvidence(transcript).contains("Borrador corregido: usar fuente B."))
                                ModelReply("", listOf(call("approve", "report_done", "result" to "Aprobado: fuente B actual y pertinente.")))
                            }
                        }
                    }
                }
            }, null)
        manager.beginMission("mission-c1c", "Diseñar y revisar borrador")

        val captainStep = AtomicInteger()
        lateinit var designerId: String
        lateinit var reviewerId: String
        val captainTools = CoreToolRegistry(CrewTools.captain(manager, emptyTools))
        val captain = CoreAgentLoop(object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                assertTrue(declarations.map { it.name }.containsAll(listOf("crew_spawn", "crew_send", "crew_wait")))
                return when (captainStep.incrementAndGet()) {
                    1 -> ModelReply("", listOf(
                        call("spawn-designer", "crew_spawn", "role" to "custom", "mission" to "Diseñar el borrador", "tools" to listOf("report_done"), "name" to "Disenador"),
                        call("spawn-reviewer", "crew_spawn", "role" to "custom", "mission" to "Revisar el borrador", "tools" to listOf("report_done"), "name" to "Revisor"),
                    ))
                    2 -> {
                        val results = transcript.filter { it.kind == ConversationTurn.Kind.TOOL_RESULT && it.toolName == "crew_spawn" }
                        assertEquals(2, results.size)
                        designerId = results[0].content.substringAfter("(").substringBefore(")")
                        reviewerId = results[1].content.substringAfter("(").substringBefore(")")
                        ModelReply("", listOf(call("wait-draft", "crew_wait", "ids" to listOf(designerId), "mode" to "all")))
                    }
                    3 -> {
                        assertTrue(reviewerFirstStarted.await(30, TimeUnit.SECONDS))
                        ModelReply("", listOf(call("send-draft", "crew_send", "to" to reviewerId,
                            "type" to "FINDING", "text" to "Borrador inicial: usar fuente A.", "refs" to emptyList<String>())))
                    }
                    4 -> {
                        releaseReviewerFirst.countDown()
                        ModelReply("", listOf(call("wait-critique", "crew_wait", "ids" to listOf(reviewerId), "mode" to "all")))
                    }
                    5 -> ModelReply("", listOf(call("send-critique", "crew_send", "to" to designerId,
                        "type" to "CRITIQUE", "text" to "La fecha de la fuente A está desactualizada; usar B.", "refs" to emptyList<String>())))
                    6 -> ModelReply("", listOf(call("wait-revision", "crew_wait", "ids" to listOf(designerId), "mode" to "all")))
                    7 -> ModelReply("", listOf(call("send-correction", "crew_send", "to" to reviewerId,
                        "type" to "FINDING", "text" to "Borrador corregido: usar fuente B.", "refs" to emptyList<String>())))
                    8 -> ModelReply("", listOf(call("wait-approval", "crew_wait", "ids" to listOf(reviewerId), "mode" to "all")))
                    else -> ModelReply("Síntesis: el Diseñador corrigió el borrador según la crítica y el Revisor aprobó la fuente B.", emptyList())
                }
            }
        }, captainTools, "Captain", "c1c-captain", CorePromptBudget.standard(), null,
            CoreAgentLoop.Limits.UNBOUNDED, null, null)
        try {
            val result = captain.run("Diseñar y revisar un borrador", emptyList(), CancellationToken.uncancellable(), null)
            assertTrue(result.text.contains("Revisor aprobó la fuente B"))
            assertEquals(2, manager.bots().size)
            assertEquals(2, designerCycles.get())
            assertEquals(3, reviewerCycles.get())
            assertTrue(designerRevisionTranscript.get().orEmpty().any {
                it.kind == ConversationTurn.Kind.TOOL_CALLS && it.toolCalls.any { tool ->
                    tool.name == "report_done" && tool.arguments["result"] == "Borrador inicial: usar fuente A."
                }
            })
            assertTrue(reviewerInboxes.any { it.contains("Borrador inicial: usar fuente A.") })
            assertTrue(reviewerInboxes.any { it.contains("Borrador corregido: usar fuente B.") })
            val captainToolResults = captain.transcriptSnapshot().filter {
                it.kind == ConversationTurn.Kind.TOOL_RESULT && it.toolName in setOf("crew_send", "crew_spawn", "crew_wait")
            }
            assertTrue(captainToolResults.none { it.content.contains("already finished", ignoreCase = true) })
            assertEquals(2, captainToolResults.count { it.toolName == "crew_spawn" })
            assertEquals(3, captainToolResults.count { it.toolName == "crew_send" })
            val threadMessages = manager.messageBus().snapshot().filter { it.to != "chief" && it.type != CrewMessage.Type.TASK }
            assertEquals(listOf("Borrador inicial: usar fuente A.",
                "La fecha de la fuente A está desactualizada; usar B.", "Borrador corregido: usar fuente B."),
                threadMessages.map { it.text })
            assertEquals(listOf("chief", "chief", "chief"), threadMessages.map { it.from })
            assertEquals(listOf(reviewerId, designerId, reviewerId), threadMessages.map { it.to })
        } finally { releaseReviewerFirst.countDown(); manager.close() }
    }

    @Test
    fun cancelingCaptainTokenStopsReanimatedBotsWithoutHangingThreads() {
        val entered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val reanimatedManager = CrewManager("c1c-cancel-reanimated", emptyTools, { _, _ -> emptyTools },
            { _, tools, incoming ->
                val cycles = AtomicInteger()
                botLoop(tools, incoming) { _, _, token ->
                    if (cycles.incrementAndGet() == 1) {
                        try { releaseFirst.await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                        ModelReply("done", emptyList())
                    } else {
                        entered.countDown()
                        try { CountDownLatch(1).await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                        ModelReply("unexpected", emptyList())
                    }
                }
            }, null)
        val linkedCaptain = CancellationToken.cancellable()
        reanimatedManager.attachCaptain(linkedCaptain)
        try {
            val bot = reanimatedManager.spawn("custom", "Complete then wait", emptyList(), "Reanimated")
            releaseFirst.countDown()
            bot.awaitTermination()
            reanimatedManager.send("chief", bot.id, CrewMessage.Type.ANSWER, "continue", emptyList())
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            linkedCaptain.cancel()
            bot.awaitTermination()
            assertEquals(CrewManager.Status.STOPPED, bot.status())
        } finally { releaseFirst.countDown(); reanimatedManager.close() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("jarvys-crew-") }
            && System.nanoTime() < deadline) Thread.yield()
        assertFalse("crew worker threads remain alive after manager shutdown",
            Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("jarvys-crew-") })
    }

    @Test
    fun crewWaitAnyAndAllTreatReanimatedBotAsRunningUntilItFinishesAgain() {
        val release = CountDownLatch(1)
        val waitingManager = CrewManager("c1c-wait-controlled", emptyTools, { _, _ -> emptyTools },
            { _, tools, incoming ->
                val turns = AtomicInteger()
                botLoop(tools, incoming) { _, _, token ->
                    if (turns.incrementAndGet() == 1) ModelReply("done", emptyList())
                    else {
                        try { release.await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                        ModelReply("done again", emptyList())
                    }
                }
            }, null)
        try {
            val target = waitingManager.spawn("custom", "Wait through reanimation", emptyList(), "Target")
            target.awaitTermination()
            waitingManager.messageBus().drain("chief")
            waitingManager.send("chief", target.id, CrewMessage.Type.ANSWER, "resume", emptyList())
            assertTrue(awaitStatus(target, CrewManager.Status.RUNNING))
            val waitTools = CoreToolRegistry(CrewTools.captain(waitingManager, emptyTools))
            val anyReturned = AtomicReference<CoreToolResult>()
            val allReturned = AtomicReference<CoreToolResult>()
            val anyThread = Thread { anyReturned.set(waitTools.invoke("crew_wait",
                mapOf("ids" to listOf(target.id), "mode" to "any"), CancellationToken.uncancellable())) }
            anyThread.start()
            assertFalse("ANY returned while the reanimated worker was running", waitForThread(anyThread, 150))
            val allThread = Thread { allReturned.set(waitTools.invoke("crew_wait",
                mapOf("ids" to listOf(target.id), "mode" to "all"), CancellationToken.uncancellable())) }
            allThread.start()
            assertFalse("ALL returned while the reanimated worker was running", waitForThread(allThread, 150))
            release.countDown()
            anyThread.join(5000); allThread.join(5000)
            assertFalse(anyThread.isAlive); assertFalse(allThread.isAlive)
            assertTrue(anyReturned.get().success)
            assertTrue(allReturned.get().success)
            assertEquals(CrewManager.Status.DONE, target.status())
        } finally { release.countDown(); waitingManager.close() }
    }

    @Test
    fun userFollowUpToDoneBotIsTrustedAndPersistedWithFromUser() {
        val session = "c1c-user-done"
        val store = LocalRunStore(Files.createTempDirectory("crew-c1c-ledger").toFile())
        val followUpEntered = CountDownLatch(1)
        val releaseFollowUp = CountDownLatch(1)
        val runningSnapshotPersisted = CountDownLatch(1)
        val finalSnapshotPersisted = CountDownLatch(1)
        val observed = AtomicReference<Pair<List<ConversationTurn>, String>>()
        val manager = CrewManager(session, emptyTools, { _, _ -> emptyTools },
            { _, _, _ -> throw AssertionError("Configure persistence listener before spawn") }, null)
        manager.configure(emptyTools, { _, _ -> emptyTools }, { bot, tools, incoming ->
            val cycles = AtomicInteger()
            botLoop(tools, incoming) { transcript, prompt, token ->
                if (cycles.incrementAndGet() == 1) ModelReply("initial", emptyList())
                else {
                    observed.set(transcript.toList() to prompt)
                    followUpEntered.countDown()
                    try { releaseFollowUp.await() } catch (_: InterruptedException) { token.throwIfCancelled() }
                    ModelReply("follow-up done", emptyList())
                }
            }
        }, null, null, { snapshot ->
            store.appendCrewMissionSnapshot(snapshot)
            if (snapshot.bots.singleOrNull()?.status == "RUNNING" && snapshot.messages.any {
                    it.from == "user" && it.text == "Please revisit the conclusion"
                }) runningSnapshotPersisted.countDown()
            if (snapshot.bots.singleOrNull()?.let { it.status == "DONE" && it.result == "follow-up done" } == true && snapshot.messages.any {
                    it.from == "user" && it.text == "Please revisit the conclusion"
                }) finalSnapshotPersisted.countDown()
        })
        try {
            val bot = manager.spawn("custom", "Wait for owner follow-up", emptyList(), "Worker")
            bot.awaitTermination()
            assertEquals(CrewManager.Status.DONE, bot.status())
            manager.sendUserMessage(bot.id, "Please revisit the conclusion")
            assertTrue(followUpEntered.await(30, TimeUnit.SECONDS))
            assertTrue(runningSnapshotPersisted.await(30, TimeUnit.SECONDS))
            val running = store.readCrewMissionSnapshots(session).single()
            assertEquals("RUNNING", running.bots.single().status)
            releaseFollowUp.countDown()
            bot.awaitTermination()
            assertEquals(CrewManager.Status.DONE, bot.status())
            val (transcript, prompt) = observed.get()
            assertTrue(transcript.any { it.role == "user" && it.content == "Please revisit the conclusion" })
            assertFalse(prompt.contains("Please revisit the conclusion"))
            assertTrue(prompt.contains("UNTRUSTED CREW DATA").not())
            val userRow = manager.messageBus().snapshot().single { it.from == "user" }
            assertEquals("user", userRow.from)
            // Worker termination does not join the coalesced snapshot callback drainer.
            // Await the actual durable terminal callback, then retain the exact disk assertions.
            assertTrue(finalSnapshotPersisted.await(30, TimeUnit.SECONDS))
            val persisted = store.readCrewMissionSnapshots(session).single()
            assertEquals("DONE", persisted.bots.single().status)
            assertTrue(persisted.messages.any { it.from == "user" && it.text == "Please revisit the conclusion" })
        } finally { releaseFollowUp.countDown(); manager.close() }
    }

    private fun awaitStatus(bot: CrewManager.Bot, expected: CrewManager.Status): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (bot.status() == expected) return true
            Thread.yield()
        }
        return bot.status() == expected
    }

    private fun waitForThread(thread: Thread, millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
    }
}
