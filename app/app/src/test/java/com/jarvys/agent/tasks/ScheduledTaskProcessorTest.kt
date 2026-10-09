package com.jarvys.agent.tasks

import android.content.Context
import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreAgentLoop
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ModelReply
import com.jarvys.agent.ProviderTransportException
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.CalendarConnector
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorOperation
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorRuntime
import com.jarvys.agent.connectors.ConnectorWritePreparation
import com.jarvys.agent.proactive.BackgroundRunController
import com.jarvys.agent.proactive.BackgroundRunKind
import com.jarvys.agent.proactive.ProactiveCoreLoopFactory
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveThreadMessage
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledTaskProcessorTest {
    private lateinit var context: android.app.Application

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        BackgroundRunController.cancelAll()
        BackgroundRunController.interactiveFinished()
        AgentRunUiState.resetSession("st1-processor-test")
    }

    @After fun tearDown() {
        BackgroundRunController.cancelAll()
        BackgroundRunController.interactiveFinished()
        AgentRunUiState.resetSession("st1-processor-test-cleanup")
    }

    @Test fun silentRunGetsExactReadOnlyScopeRedactsToolDataAndDeliversOnceToTaskConversation() {
        val registry = registryWithCalendar(connected = true)
        val calendarName = com.jarvys.agent.CoreConnectorTool.toolName(CalendarConnector.ID, CalendarConnector.SEARCH)
        val transcriptAtSecondTurn = AtomicReference<List<com.jarvys.agent.ConversationTurn>>()
        val factory = FakeLoopFactory { turn, transcript, declarations, _ ->
            if (turn == 1) {
                assertTrue(transcript.isEmpty())
                ModelReply("", listOf(ModelReply.Call("calendar", calendarName, mapOf("query" to "today"))))
            } else {
                transcriptAtSecondTurn.set(transcript.toList())
                assertEquals(setOf(calendarName, TaskRespondTool.NAME), declarations.map { it.name }.toSet())
                assertFalse(declarations.map { it.name }.any { it in TaskManagementTools.TOOL_NAMES })
                respond(notify = true, title = "Calendar update", body = "There is one event.")
            }
        }
        val notifications = FakeNotifications()
        val store = com.jarvys.agent.LocalRunStore(context)
        val processor = processor(factory, registry, store, notifications)
        val task = task(toolScope = TaskToolScope("LISTED", listOf(calendarName)),
            schedule = TaskSchedule.At("2025-01-02T13:00:00", TaskZone.Iana("Europe/Madrid")))
        val before = AgentRunUiState.state.value

        val scheduledFor = Instant.parse("2025-01-02T12:00:00Z").toEpochMilli()
        val result = processor.execute(task, scheduledFor, scheduledFor + 10_000L,
            "run-st1", CancellationToken.cancellable())

        assertEquals("OK", result.status)
        assertEquals("DELIVERED", result.deliveryStatus)
        assertTrue(result.notified)
        assertEquals(2, factory.turns.get())
        assertEquals(setOf(calendarName, TaskRespondTool.NAME), factory.toolNames.single().toSet())
        assertFalse(factory.toolNames.single().any { it in TaskManagementTools.TOOL_NAMES })
        val modelTranscript = transcriptAtSecondTurn.get().joinToString("\n") { it.content }
        assertFalse(modelTranscript.contains("482913"))
        assertFalse(modelTranscript.contains("4532015112830366"))
        assertTrue(modelTranscript.contains("[código oculto]"))
        assertTrue(modelTranscript.contains("[tarjeta oculta]"))
        assertTrue(factory.userPrompts.first().contains("Europe/Madrid"))
        assertEquals(before, AgentRunUiState.state.value)
        assertTrue(AgentRunUiState.state.value.events.none { it.kind == "approval" })
        val taskMessages = store.readConversationTimeline(ScheduledTaskConversation.SESSION_ID)
            .filter { it.kind == "assistant" && it.proactiveThreadKey == ScheduledTaskConversation.threadKey(task.id) }
        assertEquals(1, taskMessages.size)
        assertTrue(taskMessages.single().text.contains("There is one event."))
        assertEquals(1, notifications.resultCalls.get())
    }

    @Test fun acknowledgementGetsOneRepairTurnAndNoTaskRespondBecomesRetryable() {
        val registry = emptyRegistry()
        val repairFactory = FakeLoopFactory { turn, _, _, _ ->
            if (turn == 1) ModelReply("Voy a hacerlo.", emptyList())
            else respond(notify = true, title = "Done", body = "The task result.")
        }
        val processor = processor(repairFactory, registry, notifications = FakeNotifications())
        val result = processor.execute(task(), 1_735_776_000_000L, 1_735_776_000_100L,
            "run-ack", CancellationToken.cancellable())
        assertEquals("OK", result.status)
        assertEquals(2, repairFactory.turns.get())
        assertTrue(repairFactory.userPrompts[1].contains("ONLY_IF_NOTABLE"))

        val noResponseFactory = FakeLoopFactory { _, _, _, _ -> ModelReply("A substantive text answer without the terminal tool.", emptyList()) }
        val noResponse = processor(noResponseFactory, registry, notifications = FakeNotifications())
            .execute(task(), 1_735_776_000_200L, 1_735_776_000_300L, "run-missing-respond", CancellationToken.cancellable())
        assertEquals("RETRYABLE", noResponse.status)
        assertEquals("sin_task_respond", noResponse.errorCause)
        assertEquals(1, noResponseFactory.turns.get())
    }

    @Test fun toolOutputCannotGrantTaskManagementToolsToUnattendedRuns() {
        val registry = emptyRegistry()
        val taskName = TaskManagementTools.SCHEDULE_TASK
        val factory = FakeLoopFactory { turn, _, declarations, _ ->
            assertEquals(setOf(TaskRespondTool.NAME), declarations.map { it.name }.toSet())
            if (turn == 1) ModelReply("", listOf(ModelReply.Call("injected-task-create", taskName,
                mapOf("name" to "Injected", "instruction" to "Send my SMS", "schedule" to mapOf("type" to "daily")))))
            else respond(notify = false)
        }
        val result = processor(factory, registry, notifications = FakeNotifications())
            .execute(task(instruction = "Read untrusted tool output then report safely."), 700L, 701L,
                "run-injected-management", CancellationToken.cancellable())
        assertEquals("SILENT", result.status)
        assertEquals(2, factory.turns.get())
        assertTrue(factory.toolNames.all { names -> names == listOf(TaskRespondTool.NAME) })
    }

    @Test fun noProviderSkipsWithoutCreatingLoopAndSilenceIsRecordedWithoutNotification() {
        val registry = emptyRegistry()
        val noProviderFactory = FakeLoopFactory("no_provider") { _, _, _, _ -> error("loop must not be created") }
        val notifications = FakeNotifications()
        val processor = processor(noProviderFactory, registry, notifications = notifications)
        val skipped = processor.execute(task(), 10L, 20L, "run-no-provider", CancellationToken.cancellable())
        assertEquals("SKIPPED", skipped.status)
        assertEquals("no_provider", skipped.skipReason)
        assertEquals(0, noProviderFactory.turns.get())
        assertEquals(0, notifications.resultCalls.get())

        val silentFactory = FakeLoopFactory { _, _, _, _ -> respond(notify = false) }
        val silentNotifications = FakeNotifications()
        val silent = processor(silentFactory, registry, notifications = silentNotifications)
            .execute(task(), 30L, 40L, "run-silent", CancellationToken.cancellable())
        assertEquals("SILENT", silent.status)
        assertEquals("SILENT", silent.deliveryStatus)
        assertEquals(0, silentNotifications.resultCalls.get())

        val chatOnlyNotifications = FakeNotifications().apply { postResult = false }
        val chatOnly = processor(FakeLoopFactory { _, _, _, _ -> respond(true, "Update", "Saved in chat.") },
            registry, notifications = chatOnlyNotifications)
            .execute(task(), 50L, 60L, "run-chat-only", CancellationToken.cancellable())
        assertEquals("OK", chatOnly.status)
        assertEquals("CHAT_ONLY", chatOnly.deliveryStatus)
        assertFalse(chatOnly.notified)
    }

    @Test fun permanentConnectorLossNeedsAttentionAndTransientProviderFailureRetriesSameClassOfOccurrence() {
        val calendarName = com.jarvys.agent.CoreConnectorTool.toolName(CalendarConnector.ID, CalendarConnector.SEARCH)
        val disconnected = registryWithCalendar(connected = false)
        val notifications = FakeNotifications()
        val factory = FakeLoopFactory { _, _, _, _ -> error("disconnected connector must fail before the model") }
        val processor = processor(factory, disconnected, notifications = notifications)
        val failure = processor.execute(task(toolScope = TaskToolScope("LISTED", listOf(calendarName))),
            100L, 101L, "run-disconnected", CancellationToken.cancellable())
        assertEquals("ERROR", failure.status)
        assertEquals(0, factory.turns.get())
        assertEquals(1, notifications.attentionCalls.get())

        val transientFactory = FakeLoopFactory { _, _, _, _ ->
            throw ProviderTransportException("transport", java.io.IOException("offline"))
        }
        val transient = processor(transientFactory, emptyRegistry(), notifications = FakeNotifications())
            .execute(task(), 200L, 201L, "run-network", CancellationToken.cancellable())
        assertEquals("RETRYABLE", transient.status)
        assertEquals("network", transient.errorCause)
    }

    @Test fun stopObservedAlongsideProviderSetupFailureIsInterruptedRatherThanRetried() {
        val workerToken = CancellationToken.cancellable()
        val calls = AtomicInteger()
        val notifications = FakeNotifications()
        val factory = object : ProactiveCoreLoopFactory {
            override fun providerUnavailableReason(context: Context): String? = null
            override fun createLoop(context: Context, sessionId: String, tools: CoreToolRegistry,
                                    systemPrompt: String): CoreAgentLoop {
                calls.incrementAndGet()
                workerToken.cancel()
                throw ProviderTransportException("synthetic setup failure after Stop", java.io.IOException("closed"))
            }
        }
        val result = ScheduledTaskProcessor(context, factory, emptyRegistry(), clock = fixedClock(),
            notifications = notifications).execute(task(), 210L, 211L, "run-stop-network", workerToken)
        assertEquals("INTERRUPTED", result.status)
        assertEquals("interrupted", result.errorCause)
        assertEquals(1, calls.get())
        assertEquals(0, notifications.resultCalls.get())
        assertEquals(0, notifications.attentionCalls.get())
        assertTrue(result.toolsCalled.isEmpty())
    }

    @Test fun successfulTaskAfterAttentionSendsOneRecoveryNotice() {
        val notifications = FakeNotifications()
        val task = task(state = TaskState.NeedsAttention("connector_unavailable")).copy(
            lastRun = TaskLastRun("previous-run", 100L, 101L, 102L, "ERROR", "CHAT_ONLY"))
        val processor = processor(FakeLoopFactory { _, _, _, _ -> respond(true, "Recovered", "The check works again.") },
            emptyRegistry(), notifications = notifications)
        val result = processor.execute(task, 200L, 201L, "recovery-run", CancellationToken.cancellable())
        assertEquals("OK", result.status)
        assertEquals(1, notifications.recoveryCalls.get())
        assertTrue(result.notified)
    }

    @Test fun foregroundRunBlocksTasksAndTaskRequestPreemptsProactiveWithoutStartingModel() {
        val registry = emptyRegistry()
        val factory = FakeLoopFactory { _, _, _, _ -> respond(true, "Update", "done") }
        val executor = processor(factory, registry, notifications = FakeNotifications())
        val proactive = BackgroundRunController.tryStart(BackgroundRunKind.PROACTIVE)
        assertNotNull(proactive)
        val deferred = executor.execute(task(), 300L, 301L, "run-priority", CancellationToken.cancellable())
        assertEquals("RETRYABLE", deferred.status)
        assertTrue(requireNotNull(proactive).isCancelled)
        assertEquals(0, factory.turns.get())
        BackgroundRunController.finish(BackgroundRunKind.PROACTIVE, requireNotNull(proactive))

        BackgroundRunController.interactiveStarted()
        val blocked = executor.execute(task(), 400L, 401L, "run-interactive", CancellationToken.cancellable())
        assertEquals("RETRYABLE", blocked.status)
        assertEquals(0, factory.turns.get())
        BackgroundRunController.interactiveFinished()
    }

    @Test fun cancellationDuringModelTurnReturnsInterruptedAndStopsBackgroundLease() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factory = FakeLoopFactory { _, _, _, token ->
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            token.throwIfCancelled()
            respond(true, "Finished", "Completed")
        }
        val executor = Executors.newSingleThreadExecutor()
        val taskExecutor = processor(factory, emptyRegistry(), notifications = FakeNotifications())
        val token = CancellationToken.cancellable()
        try {
            val future = executor.submit<TaskExecutionResult> {
                taskExecutor.execute(task(), 500L, 501L, "run-cancel", token)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            BackgroundRunController.interactiveStarted()
            release.countDown()
            val result = future.get(3, TimeUnit.SECONDS)
            assertEquals("INTERRUPTED", result.status)
            assertTrue(BackgroundRunController.isInteractiveActive())
            assertEquals(1, factory.turns.get())
        } finally {
            release.countDown()
            BackgroundRunController.interactiveFinished()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun retryAfterMessageCommitButBeforeLedgerProducesOneMessageNotificationAndOccurrenceRow() {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val registry = emptyRegistry()
        val factory = FakeLoopFactory { _, _, _, _ -> respond(true, "One result", "Stable occurrence output") }
        val taskStoreRoot = Files.createTempDirectory("st1-idempotent-task").toFile()
        val taskStore = TaskStore(java.io.File(taskStoreRoot, "tasks.jsonl"))
        val runLedger = TaskRunLedger(java.io.File(taskStoreRoot, "runs.jsonl"))
        val conversationStore = com.jarvys.agent.LocalRunStore(context)
        val taskId = java.util.UUID.randomUUID().toString()
        val scheduledFor = Instant.parse("2025-01-02T12:00:00Z").toEpochMilli()
        val task = task(schedule = TaskSchedule.At("2025-01-02T12:00:00", TaskZone.Iana("UTC")))
            .copy(id = taskId, nextRunAt = scheduledFor)
        taskStore.create(task)
        val processor = ScheduledTaskProcessor(context, factory, registry, conversationStore,
            Clock.fixed(Instant.ofEpochMilli(scheduledFor), ZoneOffset.UTC), TaskNotifier)

        // Simulate a process death after the idempotent chat/notification delivery but before the run ledger row.
        assertEquals("OK", processor.execute(task, scheduledFor, scheduledFor, "first-attempt",
            CancellationToken.cancellable()).status)
        val firstMessages = conversationStore.readConversationTimeline(ScheduledTaskConversation.SESSION_ID)
            .filter { it.proactiveThreadKey == ScheduledTaskConversation.threadKey(taskId) }
        assertEquals(1, firstMessages.size)
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertTrue(runLedger.read().runs.isEmpty())

        val result = TaskTickEngine(taskStore, runLedger, ScheduleCalculator(Clock.fixed(
            Instant.ofEpochMilli(scheduledFor), ZoneOffset.UTC)), processor,
            Clock.fixed(Instant.ofEpochMilli(scheduledFor), ZoneOffset.UTC)).tick()
        assertEquals(1, result.started)
        assertEquals(2, factory.turns.get()) // a crash retries the safe, idempotently delivered occurrence
        val messages = conversationStore.readConversationTimeline(ScheduledTaskConversation.SESSION_ID)
            .filter { it.proactiveThreadKey == ScheduledTaskConversation.threadKey(taskId) }
        assertEquals(1, messages.size)
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertEquals(1, runLedger.read().runs.size)
        assertEquals(1, java.io.File(taskStoreRoot, "runs.jsonl").readLines().size)
    }

    private fun processor(
        factory: FakeLoopFactory,
        registry: ConnectorRegistry,
        store: com.jarvys.agent.LocalRunStore = com.jarvys.agent.LocalRunStore(context),
        notifications: FakeNotifications,
    ) = ScheduledTaskProcessor(context, factory, registry, store, fixedClock(), notifications)

    private fun task(toolScope: TaskToolScope = TaskToolScope("READ_ONLY", emptyList()),
                     delivery: TaskDelivery = TaskDelivery.ONLY_IF_NOTABLE,
                     state: TaskState = TaskState.Active,
                     schedule: TaskSchedule = TaskSchedule.Every(900_000L, 1L),
                     instruction: String = "Return one safe result") = ScheduledTask(
        name = "Diagnostic task", instruction = instruction,
        schedule = schedule, state = state,
        toolScope = toolScope, delivery = delivery, createdAt = 1L)

    private fun fixedClock() = Clock.fixed(Instant.parse("2025-01-02T12:00:00Z"), ZoneOffset.UTC)

    private fun respond(notify: Boolean, title: String = "", body: String = ""): ModelReply {
        val args = linkedMapOf<String, Any>("notify" to notify)
        if (notify) {
            args["title"] = title
            args["body"] = body
            args["urgency"] = "normal"
            args["suggested_replies"] = listOf(mapOf("label" to "Open", "text" to "Please explain the result"))
        }
        return ModelReply("", listOf(ModelReply.Call("task-response", TaskRespondTool.NAME, args)))
    }

    private fun registryWithCalendar(connected: Boolean): ConnectorRegistry {
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
                ConnectorWritePreparation(ApprovalSummary("Write", emptyList()), arguments)
            override fun invokePrepared(operation: String, arguments: JSONObject,
                                         preparation: ConnectorWritePreparation, token: CancellationToken) = JSONObject()
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) =
                JSONObject().put("items", org.json.JSONArray().put(JSONObject().put("text",
                    "OTP code 482913 and card 4532015112830366")))
        }
        val definition = ConnectorDefinition(id = CalendarConnector.ID, name = "Calendar", version = "1",
            description = "Calendar read tool",
            operations = listOf(ConnectorOperation(name = CalendarConnector.SEARCH, description = "Search calendar",
                inputSchema = JSONObject(), write = false, displayLabel = "Search calendar")),
            runtime = runtime, usageNoteProvider = { "calendar tool" })
        return ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            private var connectedValue = false
            override fun isConnected(id: String) = connectedValue
            override fun setConnected(id: String, connected: Boolean) { connectedValue = connected }
        }, permissionGranted = { true }, approvalGate = ApprovalGate.INSTANCE).apply {
            register(definition)
            if (connected) connect(definition.id)
        }
    }

    private fun emptyRegistry() = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
        override fun isConnected(id: String) = false
        override fun setConnected(id: String, connected: Boolean) = Unit
    }, permissionGranted = { true }, approvalGate = ApprovalGate.INSTANCE)

    private class FakeLoopFactory(
        private val unavailable: String? = null,
        private val reply: (Int, List<com.jarvys.agent.ConversationTurn>, List<ToolSpec>, CancellationToken) -> ModelReply,
    ) : ProactiveCoreLoopFactory {
        val turns = AtomicInteger()
        val toolNames = mutableListOf<List<String>>()
        val systemPrompts = mutableListOf<String>()
        val userPrompts = mutableListOf<String>()
        override fun providerUnavailableReason(context: Context): String? = unavailable
        override fun createLoop(context: Context, sessionId: String, tools: CoreToolRegistry,
                                systemPrompt: String): CoreAgentLoop {
            toolNames += tools.names()
            systemPrompts += systemPrompt
            return CoreAgentLoop(object : CoreAgentLoop.Model {
                override fun complete(transcript: List<com.jarvys.agent.ConversationTurn>, prompt: String,
                                     declarations: List<ToolSpec>, token: CancellationToken): ModelReply {
                    userPrompts += prompt
                    return reply(turns.incrementAndGet(), transcript, declarations, token)
                }
            }, tools, systemPrompt, sessionId)
        }
    }

    private class FakeNotifications : ScheduledTaskNotifications {
        val resultCalls = AtomicInteger()
        val attentionCalls = AtomicInteger()
        val recoveryCalls = AtomicInteger()
        var postResult = true
        override fun result(context: Context, store: com.jarvys.agent.LocalRunStore, task: ScheduledTask,
                            messageId: String, title: String, body: String, urgency: String,
                            suggestedReplies: List<ProactiveSuggestedReply>): Boolean {
            resultCalls.incrementAndGet()
            return postResult
        }
        override fun attention(context: Context, task: ScheduledTask, reason: String): Boolean {
            attentionCalls.incrementAndGet()
            return true
        }
        override fun recovered(context: Context, task: ScheduledTask): Boolean {
            recoveryCalls.incrementAndGet()
            return true
        }
    }
}
