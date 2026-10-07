package com.jarvys.agent.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreAgentLoop
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ModelReply
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.proactive.ProactiveCoreLoopFactory
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveThreadMessage
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskManualRunWorkerTest {
    @Test fun manualWorkUsesTheNormalProcessorAndKeepsManualOccurrenceDistinctFromScheduledRun() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("st2-manual-worker").toFile()
        val tasks = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val clock = Clock.fixed(Instant.parse("2025-03-08T08:00:00Z"), ZoneOffset.UTC)
        val scheduledFor = clock.millis()
        val task = tasks.create(ScheduledTask(name = "Manual test", instruction = "Check once",
            schedule = TaskSchedule.Every(ScheduledTaskValidation.MIN_INTERVAL_MILLIS, scheduledFor),
            nextRunAt = scheduledFor, createdAt = scheduledFor - 1L,
            toolScope = TaskToolScope("LISTED", emptyList()), delivery = TaskDelivery.ALWAYS))
        assertTrue(ledger.appendIfAbsent(TaskRunRecord(task.id, "scheduled", scheduledFor,
            scheduledFor - 1L, scheduledFor, "OK", "CHAT_ONLY")))
        val occurrenceId = "manual:${task.id}:test-run"
        val notifications = FakeNotifications()
        val processor = ScheduledTaskProcessor(context, FakeLoopFactory(),
            com.jarvys.agent.connectors.ConnectorRegistry.createForTests(object :
                com.jarvys.agent.connectors.ConnectorConnectionPreferences {
                override fun isConnected(id: String) = false
                override fun setConnected(id: String, connected: Boolean) = Unit
            }, permissionGranted = { true }, approvalGate = com.jarvys.agent.connectors.ApprovalGate.INSTANCE),
            com.jarvys.agent.LocalRunStore(context), clock, notifications)
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                                      workerParameters: androidx.work.WorkerParameters): ListenableWorker? =
                if (workerClassName == TaskManualRunWorker::class.java.name)
                    TaskManualRunWorker(appContext, workerParameters, processor, tasks, ledger, clock) else null
        }
        val data = androidx.work.Data.Builder().putString(TaskManualRunWorker.KEY_TASK_ID, task.id)
            .putString(TaskManualRunWorker.KEY_OCCURRENCE_ID, occurrenceId)
            .putLong(TaskManualRunWorker.KEY_SCHEDULED_FOR, scheduledFor).build()
        val result = runBlocking {
            TestListenableWorkerBuilder.from(context, TaskManualRunWorker::class.java)
                .setInputData(data).setWorkerFactory(workerFactory).build().doWork()
        }
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, notifications.results)
        val taskRuns = ledger.forTask(task.id)
        assertEquals(2, taskRuns.size)
        assertEquals(setOf("${task.id}:$scheduledFor", occurrenceId), taskRuns.map { it.idempotencyKey }.toSet())
        val delivered = com.jarvys.agent.LocalRunStore(context).readConversationTimeline(ScheduledTaskConversation.SESSION_ID)
            .filter { it.proactiveThreadKey == ScheduledTaskConversation.threadKey(task.id) }
        assertEquals(1, delivered.size)
        assertTrue(delivered.single().text.contains("Manual result"))
    }

    private class FakeLoopFactory : ProactiveCoreLoopFactory {
        override fun providerUnavailableReason(context: Context): String? = null
        override fun createLoop(context: Context, sessionId: String, tools: CoreToolRegistry,
                                systemPrompt: String) = CoreAgentLoop(object : CoreAgentLoop.Model {
            override fun complete(transcript: List<com.jarvys.agent.ConversationTurn>, prompt: String,
                                  declarations: List<ToolSpec>, token: CancellationToken): ModelReply =
                ModelReply("", listOf(ModelReply.Call("manual-response", TaskRespondTool.NAME,
                    mapOf("notify" to true, "title" to "Manual", "body" to "Manual result", "urgency" to "normal"))))
        }, tools, systemPrompt, sessionId)
    }

    private class FakeNotifications : ScheduledTaskNotifications {
        var results = 0
        override fun result(context: Context, store: com.jarvys.agent.LocalRunStore, task: ScheduledTask,
                            messageId: String, title: String, body: String, urgency: String,
                            suggestedReplies: List<ProactiveSuggestedReply>): Boolean { results++; return true }
        override fun attention(context: Context, task: ScheduledTask, reason: String): Boolean = true
        override fun recovered(context: Context, task: ScheduledTask): Boolean = true
    }
}
