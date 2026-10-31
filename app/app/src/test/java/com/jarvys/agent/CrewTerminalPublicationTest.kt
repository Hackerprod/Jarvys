package com.jarvys.agent

import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMessage
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Terminal status alone cannot let ALL overtake native result publication. */
class CrewTerminalPublicationTest {
    private fun checkPublication(failModel: Boolean, cancelWait: Boolean = false) {
        val tools = CoreToolRegistry(emptyList())
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val waitToken = CancellationToken.cancellable()
        val manager = CrewManager("terminal-publication", tools, { _, _ -> tools },
            { _, workerTools, incoming ->
                CoreAgentLoop(object : CoreAgentLoop.Model {
                    override fun complete(transcript: List<ConversationTurn>, prompt: String,
                        declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                        if (failModel) throw IllegalStateException("Controlled worker failure")
                        return ModelReply("Exact completed result", emptyList())
                    }
                }, workerTools, "Worker", "worker", CorePromptBudget.standard(), null,
                    CoreAgentLoop.ToolResultBudget.UNBOUNDED, incoming, null)
            }, null)
        manager.configureConversationMessageListener { _, message ->
            if (message.type == CrewMessage.Type.RESULT) {
                publishing.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Publication barrier not released" }
            }
        }
        try {
            val bot = manager.spawn("custom", "Finish bounded work", emptyList(), "Worker")
            assertTrue(publishing.await(10, TimeUnit.SECONDS))
            assertEquals(if (failModel) CrewManager.Status.FAILED else CrewManager.Status.DONE, bot.status())
            // Query the very completion predicate used under the bus monitor. This assertion is
            // deterministic at the publication barrier; it does not infer a race from elapsed time.
            val terminal = CrewManager::class.java.getDeclaredMethod("terminal", List::class.java,
                CrewManager.WaitMode::class.java).apply { isAccessible = true }
            assertEquals(false, terminal.invoke(manager, listOf(bot.id), CrewManager.WaitMode.ALL))
            val entered = CountDownLatch(1)
            val waiting = executor.submit<List<CrewMessage>> {
                entered.countDown()
                manager.waitFor(listOf(bot.id), CrewManager.WaitMode.ALL, waitToken)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            manager.messageBus().signalWaiters() // A sibling completion can produce this wake.
            if (cancelWait) {
                waitToken.cancel()
                val failure = assertThrows(ExecutionException::class.java) { waiting.get(10, TimeUnit.SECONDS) }
                assertTrue(failure.cause is CancellationException)
                assertEquals(1L, release.count) // Cancellation did not await the blocked callback.
            } else {
                assertThrows(TimeoutException::class.java) { waiting.get(100, TimeUnit.MILLISECONDS) }
                release.countDown()
                val messages = waiting.get(10, TimeUnit.SECONDS)
                val results = messages.filter { it.from == bot.id && it.type == CrewMessage.Type.RESULT }
                assertEquals(1, results.size)
                assertTrue(results.single().text.contains(if (failModel) "Controlled worker failure" else "Exact completed result"))
                bot.awaitTermination()
                assertEquals(true, terminal.invoke(manager, listOf(bot.id), CrewManager.WaitMode.ALL))
                assertTrue(manager.messageBus().drain("chief").isEmpty())
            }
        } finally {
            release.countDown()
            waitToken.cancel()
            executor.shutdownNow()
            manager.close()
        }
    }

    @Test fun allWaitCannotOvertakeDoneResultPublication() = checkPublication(failModel = false)
    @Test fun allWaitCannotOvertakeFailedResultPublication() = checkPublication(failModel = true)
    @Test fun cancellationDoesNotWaitForTerminalResultPublication() = checkPublication(failModel = false, cancelWait = true)
}
