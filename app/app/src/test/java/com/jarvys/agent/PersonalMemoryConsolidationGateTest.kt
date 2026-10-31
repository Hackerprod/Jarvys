package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.device.ScreenData
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalMemoryConsolidationGateTest {
    private val executor = BoundaryExecutor()
    @get:Rule val fixture = ChatTestStore(bridgeExecutorFactory = { executor })
    @get:Rule val secrets = TestSecretStoreRule()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private lateinit var policy: MemoryWritePolicy
    private lateinit var worker: PersonalMemoryConsolidationWorker

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        bridge = ChatDatabaseBridge.getInstance(context)
        bridge.personalAgentPromptContext("", true, "principal")
        policy = MemoryWritePolicy.forContext(context)
        policy.setEnabledBlocking(true)
        worker = TestListenableWorkerBuilder.from(context, PersonalMemoryConsolidationWorker::class.java).build()
    }

    @Test fun phaseTicketPrecedesTheCandidateRead() = verifyBoundary(1)

    @Test fun candidateSnapshotCannotReceiveFreshAuthorityDuringPromptRead() = verifyBoundary(2)

    private fun verifyBoundary(callableNumber: Int) {
        val first = bridge.personalAgentSaveMemory("The user studies Kotlin", "daily", null, .7, null, com.jarvys.agent.chatdb.MemorySaveOptions())
        val second = bridge.personalAgentSaveMemory("The user builds a local assistant", "daily", null, .8, null, com.jarvys.agent.chatdb.MemorySaveOptions())
        val ids = listOf(first.id, second.id)
        val before = bridge.personalAgentGet(ids).associate { it.id to Pair(it.text, it.revision) }
        assertEquals(ids.toSet(), bridge.personalAgentDailyConsolidationCandidates().map { it.id }.toSet())
        val summary = "The user studies Kotlin while building a local assistant."
        val calls = AtomicInteger()
        fun model() = CoreAgentModel(context, object : ModelProviderClient {
            private var invoked = false
            override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
                tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply = error("Conversation expected")
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                calls.incrementAndGet()
                if (invoked) return ModelReply("Finished", emptyList())
                invoked = true
                assertEquals(listOf("memory_consolidate"), tools.map { it.name })
                return ModelReply("", listOf(ModelReply.Call("consolidate", "memory_consolidate",
                    mapOf<String, Any>("source_ids" to ids, "summary" to summary))))
            }
        }, "consolidation-gate-test")
        val phaseThread = Executors.newSingleThreadExecutor()
        try {
            // First callable reads candidates; second reads prompt context before tool construction.
            // Pause BEFORE command.run: no assumption that an already completed Future blocks its caller.
            executor.arm(callableNumber)
            val stale = phaseThread.submit { worker.consolidatePersisted(bridge, CancellationToken.cancellable(), model()) }
            assertTrue("Worker did not reach the selected database boundary", executor.paused.await(10, TimeUnit.SECONDS))
            assertEquals(0, calls.get())
            policy.setEnabledBlocking(false)
            policy.setEnabledBlocking(true)
            executor.release.countDown()
            stale.get(10, TimeUnit.SECONDS)
            assertEquals(2, calls.get()) // The late model reply really attempted the guarded tool.
            assertFalse(bridge.personalAgentBrowse().any { it.text == summary })
            assertEquals(before, bridge.personalAgentGet(ids).associate { it.id to Pair(it.text, it.revision) })

            worker.consolidatePersisted(bridge, CancellationToken.cancellable(), model())
            assertEquals(4, calls.get())
            assertEquals(1, bridge.personalAgentBrowse().count { it.text == summary })
            assertEquals(before, bridge.personalAgentGet(ids).associate { it.id to Pair(it.text, it.revision) })
        } finally {
            executor.release.countDown()
            phaseThread.shutdownNow()
        }
    }

    /** Existing bridge executor seam; no production callbacks or global model replacement. */
    private class BoundaryExecutor : AbstractExecutorService() {
        private val delegate = Executors.newSingleThreadExecutor()
        private val remaining = AtomicInteger(0)
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun arm(number: Int) { check(number > 0); remaining.set(number) }
        override fun execute(command: Runnable) = delegate.execute {
            if (remaining.get() > 0 && remaining.decrementAndGet() == 0) {
                paused.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Database boundary was not released" }
            }
            command.run()
        }
        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
        override fun isShutdown() = delegate.isShutdown
        override fun isTerminated() = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
    }
}
