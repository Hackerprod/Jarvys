package com.jarvys.agent.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.Configuration
import androidx.work.WorkerFactory
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskManualRunWorkerTest {
    @get:Rule val chatTestStore = com.jarvys.agent.ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @get:Rule val secretStoreTest = com.jarvys.agent.TestSecretStoreRule()
    private lateinit var context: Context

    @Before fun initializeWorkManager() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor { command -> command.run() }.build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS)
        chatTestStore.onDatabaseWorker { com.jarvys.agent.SingleSessionReset.awaitReadyBlocking(context.filesDir) }
    }

    @After fun closeWorkManager() {
        com.jarvys.agent.WorkManagerTestCleanup.close(context)
    }

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

    @Test fun manualWorkWaitsForPendingApprovalWithoutDeletingTheReviewedTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("manual-pending-approval").toFile()
        val tasks = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val pendingActions = TaskPendingActionStore(File(root, "pending-actions.jsonl"))
        val clock = Clock.fixed(Instant.parse("2025-03-08T08:00:00Z"), ZoneOffset.UTC)
        val scheduledFor = clock.millis()
        val task = tasks.create(ScheduledTask(name = "Delete after approval", instruction = "Send once",
            schedule = TaskSchedule.Every(ScheduledTaskValidation.MIN_INTERVAL_MILLIS, scheduledFor),
            state = TaskState.Done, deleteAfterRun = true, createdAt = scheduledFor - 1L,
            toolScope = TaskToolScope("LISTED", listOf("gmail_send_message"), writes = "ALLOW"),
            creatorToolNames = listOf("gmail_send_message")))
        val pending = pendingActions.enqueue(task, "prior-run", "gmail_send_message",
            """{"to":"person@example.test"}""")
        val loop = FakeLoopFactory()
        val notifications = FakeNotifications()
        val registry = com.jarvys.agent.connectors.ConnectorRegistry.createForTests(object :
            com.jarvys.agent.connectors.ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, permissionGranted = { true }, approvalGate = com.jarvys.agent.connectors.ApprovalGate.INSTANCE)
        val processor = ScheduledTaskProcessor(context, loop, registry,
            com.jarvys.agent.LocalRunStore(context), clock, notifications)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                                      workerParameters: androidx.work.WorkerParameters): ListenableWorker? =
                if (workerClassName == TaskManualRunWorker::class.java.name)
                    TaskManualRunWorker(appContext, workerParameters, processor, tasks, ledger, clock, pendingActions)
                else null
        }
        val occurrenceId = "manual:${task.id}:pending-approval"
        val input = androidx.work.Data.Builder().putString(TaskManualRunWorker.KEY_TASK_ID, task.id)
            .putString(TaskManualRunWorker.KEY_OCCURRENCE_ID, occurrenceId)
            .putLong(TaskManualRunWorker.KEY_SCHEDULED_FOR, scheduledFor).build()

        val result = runBlocking {
            TestListenableWorkerBuilder.from(context, TaskManualRunWorker::class.java)
                .setInputData(input).setWorkerFactory(factory).build().doWork()
        }

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, loop.calls)
        assertEquals(task, tasks.get(task.id))
        assertEquals(TaskState.AwaitingUser,
            pendingActions.stateForTask(task.id, task.revision, task.state))
        val rejected = ledger.forTask(task.id).single()
        assertEquals("REJECTED", rejected.status)
        assertEquals(TaskRunCause.AWAITING_USER, rejected.causeCode)
        assertEquals(TaskPendingActionStatus.PENDING, pendingActions.get(pending.id)?.status)
    }

    @Test fun boundManualWorkRejectsChangedRevisionPayloadOrIncompleteBindingBeforeProcessor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clock = Clock.fixed(Instant.parse("2025-03-08T08:00:00Z"), ZoneOffset.UTC)
        listOf("revision", "same_revision_content", "scope", "incomplete", "exact").forEach { change ->
            val root = Files.createTempDirectory("manual-bound-task").toFile()
            val taskFile = File(root, "tasks.jsonl")
            val tasks = TaskStore(taskFile)
            val ledger = TaskRunLedger(File(root, "runs.jsonl"))
            val saved = tasks.create(ScheduledTask(name = "Manual binding", instruction = "Original exact request",
                schedule = TaskSchedule.Every(ScheduledTaskValidation.MIN_INTERVAL_MILLIS, clock.millis()),
                createdAt = clock.millis(), toolScope = TaskToolScope("LISTED", emptyList())))
            val expectedBinding = TaskActionBinding.task("", "manual", saved)
            when (change) {
                "revision" -> tasks.update(saved.copy(name = "Later edit"), saved.revision)
                "same_revision_content" -> taskFile.writeText(org.json.JSONObject(taskFile.readLines().single())
                    .put("instruction", "Restored changed task").toString() + "\n")
                "scope" -> tasks.update(saved.copy(toolScope = saved.toolScope.copy(web = true)), saved.revision)
            }
            val notifications = FakeNotifications()
            val loop = FakeLoopFactory()
            val registry = com.jarvys.agent.connectors.ConnectorRegistry.createForTests(object :
                com.jarvys.agent.connectors.ConnectorConnectionPreferences {
                override fun isConnected(id: String) = false
                override fun setConnected(id: String, connected: Boolean) = Unit
            }, permissionGranted = { true }, approvalGate = com.jarvys.agent.connectors.ApprovalGate.INSTANCE)
            val processor = ScheduledTaskProcessor(context, loop, registry, com.jarvys.agent.LocalRunStore(context), clock, notifications)
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String,
                                          workerParameters: androidx.work.WorkerParameters): ListenableWorker? =
                    if (workerClassName == TaskManualRunWorker::class.java.name)
                        TaskManualRunWorker(appContext, workerParameters, processor, tasks, ledger, clock) else null
            }
            val data = androidx.work.Data.Builder().putString(TaskManualRunWorker.KEY_TASK_ID, saved.id)
                .putString(TaskManualRunWorker.KEY_OCCURRENCE_ID, "manual:${saved.id}:bound")
                .putLong(TaskManualRunWorker.KEY_SCHEDULED_FOR, clock.millis())
                .putLong(TaskManualRunWorker.KEY_EXPECTED_REVISION, saved.revision)
                .apply { if (change != "incomplete") putString(TaskManualRunWorker.KEY_EXPECTED_BINDING, expectedBinding) }
                .build()
            val result = runBlocking { TestListenableWorkerBuilder.from(context, TaskManualRunWorker::class.java)
                .setInputData(data).setWorkerFactory(factory).build().doWork() }
            if (change == "exact") {
                assertTrue(result is ListenableWorker.Result.Success)
                assertEquals(1, loop.calls)
                assertEquals(1, notifications.results)
            } else {
                assertTrue(change, result is ListenableWorker.Result.Failure)
                assertEquals(0, loop.calls)
                assertEquals(0, notifications.results)
                val rejected = ledger.forTask(saved.id).single()
                assertEquals("REJECTED", rejected.status)
                assertEquals(if (change == "incomplete") TaskRunCause.EXECUTION_FAILED else TaskRunCause.TASK_CHANGED,
                    rejected.causeCode)
            }
        }
    }

    private class FakeLoopFactory : ProactiveCoreLoopFactory {
        var calls = 0
        override fun providerUnavailableReason(context: Context): String? = null
        override fun createLoop(context: Context, sessionId: String, tools: CoreToolRegistry,
                                systemPrompt: String) = CoreAgentLoop(object : CoreAgentLoop.Model {
            override fun complete(transcript: List<com.jarvys.agent.ConversationTurn>, prompt: String,
                                  declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                calls++
                return ModelReply("Manual result", emptyList())
            }
        }, tools, systemPrompt, sessionId)
    }

    private class FakeNotifications : ScheduledTaskNotifications {
        var results = 0
        override fun result(context: Context, store: com.jarvys.agent.LocalRunStore, task: ScheduledTask,
                            messageId: String, title: String, body: String, urgency: String,
                            suggestedReplies: List<ProactiveSuggestedReply>): Boolean { results++; return true }
        override fun attention(context: Context, task: ScheduledTask, cause: TaskRunCause): Boolean = true
        override fun recovered(context: Context, task: ScheduledTask): Boolean = true
    }
}
