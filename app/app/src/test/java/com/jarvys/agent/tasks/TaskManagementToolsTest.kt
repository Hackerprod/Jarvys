package com.jarvys.agent.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.WebSearchTools
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskManagementToolsTest {
    @get:Rule val chatTestStore = com.jarvys.agent.ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @get:Rule val secretStoreTest = com.jarvys.agent.TestSecretStoreRule()
    private lateinit var context: Context
    private val clock = Clock.fixed(Instant.parse("2025-03-07T23:30:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("UTC")

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext() }

    @Test fun declarationsExposeSeparateTaskToolsWithoutCronOrAnActionDiscriminator() {
        val tools = fixture().tools.tools()
        assertEquals(TaskManagementTools.TOOL_NAMES, tools.map { it.declaration().name }.toSet())
        tools.forEach { assertFalse((it.declaration().jsonSchema()["properties"] as Map<*, *>).containsKey("action")) }
        val create = tools.single { it.declaration().name == TaskManagementTools.SCHEDULE_TASK }
        val properties = create.declaration().jsonSchema()["properties"] as Map<*, *>
        assertEquals(listOf("ALLOW", "DEFER", "DENY"),
            ((properties["writes"] as Map<*, *>)["enum"] as List<*>))
        assertEquals(listOf("name", "instruction", "schedule"), create.declaration().required)
        val schedule = properties["schedule"] as Map<*, *>
        val scheduleProperties = schedule["properties"] as Map<*, *>
        assertEquals(listOf("at", "every", "daily", "weekly", "monthly"),
            (scheduleProperties["type"] as Map<*, *>) ["enum"])
    }

    @Test fun creationRunsDirectlyAndEquivalentRequestIsIdempotent() {
        val f = fixture()
        val tool = f.tools.tool(TaskManagementTools.SCHEDULE_TASK)
        val first = payload(tool.execute(scheduleArgs("Read status"), CancellationToken.uncancellable()))
        val second = payload(tool.execute(scheduleArgs("Read status"), CancellationToken.uncancellable()))
        assertEquals("created", first.getString("status"))
        assertEquals("existing", second.getString("status"))
        assertEquals(first.getString("task_id"), second.getString("task_id"))
        assertEquals(1, f.repository.list().size)
    }

    @Test fun taskScopeIsCappedByCreatorCapabilitiesAndLegacyWritesDoNotGrant() {
        val creatorNames = listOf("mail_read", "mail_send", "read_skill", "delegate_subtask")
        val taskCapabilities = listOf("mail_read", "mail_send", "read_skill", "not_in_creator_turn")
        val f = fixture(creatorNames = creatorNames, taskCapabilityNames = taskCapabilities)
        val defaultCreated = payload(f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Read email"), CancellationToken.uncancellable()))
        val defaultTask = requireNotNull(f.repository.get(defaultCreated.getString("task_id")))
        assertEquals(listOf("mail_read", "mail_send", "read_skill"), defaultTask.toolScope.tools)
        assertEquals(defaultTask.toolScope.tools, defaultTask.creatorToolNames)
        assertEquals("DEFER", defaultTask.toolScope.writes)
        assertFalse(defaultTask.toolScope.writesAllowed)

        val explicitCreated = payload(f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Write a draft").plus("writes" to "ALLOW"), CancellationToken.uncancellable()))
        val explicitTask = requireNotNull(f.repository.get(explicitCreated.getString("task_id")))
        assertEquals(TaskWriteScope.ALLOW.name, explicitTask.toolScope.writes)
        assertTrue(explicitTask.toolScope.writesAllowed)
    }

    @Test fun memoryFileCapabilitiesAreSavedOnlyWhenTheCreatorHadMemoryEnabled() {
        val capabilities = listOf("read", "write", "search_files")
        val withoutMemory = fixture(creatorNames = capabilities, taskCapabilityNames = listOf("search_files"))
        val noMemoryRow = payload(withoutMemory.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Search available files"), CancellationToken.uncancellable()))
        val noMemoryTask = withoutMemory.repository.get(noMemoryRow.getString("task_id"))!!
        assertFalse(noMemoryTask.toolScope.memory)
        assertEquals(listOf("search_files"), noMemoryTask.toolScope.tools)
        assertFalse(noMemoryTask.creatorToolNames.contains("read"))
        assertFalse(noMemoryTask.creatorToolNames.contains("write"))

        val withMemory = fixture(creatorNames = capabilities, taskCapabilityNames = capabilities,
            creatorMemoryAllowed = true)
        val memoryRow = payload(withMemory.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Review saved notes"), CancellationToken.uncancellable()))
        val memoryTask = withMemory.repository.get(memoryRow.getString("task_id"))!!
        assertTrue(memoryTask.toolScope.memory)
        assertEquals(capabilities.sorted(), memoryTask.toolScope.tools.sorted())
        assertTrue(memoryTask.creatorToolNames.containsAll(listOf("read", "write")))
    }

    @Test fun taskSkillScopeStoresOnlyTheCreatorVisibleSkillIds() {
        val f = fixture(creatorNames = listOf("read_skill"), taskCapabilityNames = listOf("read_skill"),
            creatorSkillIds = listOf("skill-a", "skill-b"))
        val row = payload(f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Use the available skill"), CancellationToken.uncancellable()))
        val task = f.repository.get(row.getString("task_id"))!!
        assertEquals(listOf("skill-a", "skill-b"), task.toolScope.skillIds)
        assertEquals(listOf("read_skill"), task.toolScope.tools)
    }

    @Test fun runTaskEnqueuesOneRevisionBoundRunAndTaskRunsCanReadItsQueuedStatus() {
        val f = fixture()
        val created = payload(f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Run the diagnostic task"), CancellationToken.uncancellable()))
        assertEquals("created", created.getString("status"))
        val task = requireNotNull(f.repository.get(created.getString("task_id")))
        val result = payload(f.tools.tool(TaskManagementTools.RUN_TASK).execute(
            mapOf("task_id" to task.id, "revision" to task.revision), CancellationToken.uncancellable()))

        assertEquals("queued", result.getString("status"))
        assertTrue(result.getString("run_id").isNotBlank())
        assertEquals(1, f.enqueued.size)
        assertEquals(task.id, f.enqueued.single().first)
        assertEquals(task.revision, f.enqueued.single().second?.revision)
        val history = payload(f.tools.tool(TaskManagementTools.TASK_RUNS).execute(
            mapOf("task_id" to task.id), CancellationToken.uncancellable())).getJSONArray("runs")
        assertEquals(result.getString("run_id"), history.getJSONObject(0).getString("run_id"))
        assertEquals("QUEUED", history.getJSONObject(0).getString("status"))
    }

    @Test fun runTaskRejectsChangedRevisionAndMissingTaskWithoutEnqueueing() {
        val f = fixture()
        val task = f.createTask()
        f.repository.update(task.copy(name = "Reviewed again"), task.revision)
        val tool = f.tools.tool(TaskManagementTools.RUN_TASK)

        assertTrue(runCatching { tool.execute(mapOf("task_id" to task.id, "revision" to task.revision),
            CancellationToken.uncancellable()) }.exceptionOrNull() is StaleTaskRevisionException)
        assertTrue(runCatching { tool.execute(mapOf("task_id" to "missing", "revision" to 1),
            CancellationToken.uncancellable()) }.isFailure)
        assertTrue(f.enqueued.isEmpty())
    }

    @Test fun runTaskCannotBypassAnUnresolvedPendingAction() {
        val f = fixture()
        val task = f.createTask()
        f.pendingActions.enqueue(task, "background-run", "mail_send", "{}")
        val result = runCatching { f.tools.tool(TaskManagementTools.RUN_TASK).execute(
            mapOf("task_id" to task.id, "revision" to task.revision), CancellationToken.uncancellable()) }
            .exceptionOrNull()
        assertNotNull(result)
        assertTrue(result!!.message.orEmpty().contains("awaiting approval"))
        assertTrue(f.enqueued.isEmpty())
    }

    @Test fun deleteAfterRunIsExposedForCreateAndRevisionCheckedUpdate() {
        val f = fixture()
        val created = payload(f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Remove after success").plus("delete_after_run" to true), CancellationToken.uncancellable()))
        val task = requireNotNull(f.repository.get(created.getString("task_id")))
        assertTrue(task.deleteAfterRun)
        val updated = payload(f.tools.tool(TaskManagementTools.UPDATE_TASK).execute(mapOf("task_id" to task.id,
            "patch" to mapOf("revision" to task.revision, "delete_after_run" to false)), CancellationToken.uncancellable()))
        assertEquals("updated", updated.getString("status"))
        assertFalse(f.repository.get(task.id)!!.deleteAfterRun)
    }

    @Test fun scheduleValidationRejectsInvalidIntervalsZonesWeekdaysAndPastAt() {
        val invalid = listOf(
            scheduleArgs("x", cadence = "every", interval = 14),
            scheduleArgs("x", cadence = "daily", time = "25:00"),
            scheduleArgs("x", cadence = "weekly", days = listOf(0)),
            scheduleArgs("x", cadence = "daily", zone = "Not/AZone"),
            scheduleArgs("x", cadence = "at", localDateTime = "2025-03-07T08:00:00"),
        )
        val f = fixture()
        val validatingTool = f.tools.tool(TaskManagementTools.SCHEDULE_TASK)
        invalid.forEach { arguments ->
            assertTrue(runCatching { validatingTool.execute(arguments, CancellationToken.uncancellable()) }.isFailure)
        }
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun listProvidesLocalClockAndTaskRunsAreStructuredAndPaginated() {
        val f = fixture()
        val task = f.createTask()
        repeat(4) { index -> f.addRun(task.id, startedAt = index + 1L) }

        val listed = payload(f.tools.tool(TaskManagementTools.LIST_TASKS).execute(emptyMap(), CancellationToken.uncancellable()))
        val data = listed
        assertEquals("UTC", data.getString("zone"))
        assertTrue(data.getString("now_local"), data.getString("now_local").contains("23:30"))
        assertTrue(data.getString("now_local"), data.getString("now_local").contains("2025"))
        assertEquals(1, data.getJSONArray("tasks").length())

        val runsTool = f.tools.tool(TaskManagementTools.TASK_RUNS)
        val all = runsTool.execute(mapOf("task_id" to task.id), CancellationToken.uncancellable())
        assertFalse(all.content.contains("UNTRUSTED TASK DATA"))
        assertEquals(4, payload(all).getJSONArray("runs").length())
        val limited = runsTool.execute(mapOf("task_id" to task.id, "limit" to 2), CancellationToken.uncancellable())
        assertEquals(2, payload(limited).getJSONArray("runs").length())
    }

    @Test fun taskRunsReturnsDeliveredResultAndConversationReference() {
        val f = fixture()
        val task = f.createTask()
        f.ledger.appendIfAbsent(TaskRunRecord(task.id, "delivered-run", 10L, 11L, 12L,
            "OK", "DELIVERED", deliveredResult = "The delivered answer.",
            conversationMessageId = "message-ref"))

        val run = payload(f.tools.tool(TaskManagementTools.TASK_RUNS).execute(
            mapOf("task_id" to task.id), CancellationToken.uncancellable()))
            .getJSONArray("runs").getJSONObject(0)
        assertEquals("The delivered answer.", run.getString("delivered_result"))
        assertEquals("message-ref", run.getString("conversation_message_id"))
    }

    @Test fun updateUsesExactRevisionAndPreservesTaskScopeLimits() {
        val f = fixture(creatorNames = listOf(WebSearchTools.SEARCH, WebSearchTools.FETCH))
        val created = payload(f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Read status"), CancellationToken.uncancellable()))
        val task = f.repository.get(created.getString("task_id"))!!
        val update = f.tools.tool(TaskManagementTools.UPDATE_TASK)
        val changed = payload(update.execute(mapOf("task_id" to task.id,
            "patch" to mapOf("revision" to task.revision, "instruction" to "Compare status")), CancellationToken.uncancellable()))
        assertEquals("updated", changed.getString("status"))
        assertEquals("Compare status", f.repository.get(task.id)!!.instruction)

        val current = f.repository.get(task.id)!!
        val failure = runCatching { update.execute(mapOf("task_id" to task.id,
            "patch" to mapOf("revision" to task.revision, "name" to "stale")), CancellationToken.uncancellable()) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Diagnostic task", f.repository.get(task.id)!!.name)

        val currentAgain = f.repository.get(task.id)!!
        val widened = runCatching { update.execute(mapOf("task_id" to task.id,
            "patch" to mapOf("revision" to currentAgain.revision, "tools" to listOf("unlisted_tool"))),
            CancellationToken.uncancellable()) }.exceptionOrNull()
        assertNotNull(widened)
        assertEquals("Compare status", f.repository.get(task.id)!!.instruction)
    }

    @Test fun taskWebScopeCanOnlyUseCreatorApprovedWebTools() {
        val f = fixture(creatorNames = listOf(WebSearchTools.SEARCH, WebSearchTools.FETCH))
        val created = payload(f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(
            scheduleArgs("Search headlines").plus("web" to true), CancellationToken.uncancellable()))
        val task = f.repository.get(created.getString("task_id"))!!
        assertTrue(task.toolScope.web)
        assertEquals(listOf(WebSearchTools.SEARCH, WebSearchTools.FETCH).sorted(), task.toolScope.tools.sorted())

        val update = f.tools.tool(TaskManagementTools.UPDATE_TASK)
        val restricted = payload(update.execute(mapOf("task_id" to task.id,
            "patch" to mapOf("revision" to task.revision, "web" to false)), CancellationToken.uncancellable()))
        assertEquals("updated", restricted.getString("status"))
        assertFalse(f.repository.get(task.id)!!.toolScope.web)
        assertTrue(f.repository.get(task.id)!!.toolScope.tools.isEmpty())
    }

    @Test fun pausingTaskWithDisconnectedReadToolRemainsAvailable() {
        val f = fixture(creatorNames = listOf(WebSearchTools.SEARCH))
        val task = f.repository.create("Web task", "Search safely",
            TaskSchedule.Calendar("08:00", CalendarCadence.DAILY, zone = TaskZone.Iana("UTC")),
            toolScope = TaskToolScope("LISTED", listOf(WebSearchTools.SEARCH), web = true),
            createdBy = "AGENT_CHAT:${f.sessionId}", creatorToolNames = listOf(WebSearchTools.SEARCH))
        val result = payload(f.tools.tool(TaskManagementTools.UPDATE_TASK).execute(mapOf("task_id" to task.id,
            "patch" to mapOf("revision" to task.revision, "state" to "paused")), CancellationToken.uncancellable()))
        assertEquals("updated", result.getString("status"))
        assertEquals(TaskState.Paused, f.repository.get(task.id)!!.state)
    }

    @Test fun cancellationRetainsHistoryAndDeletesTaskOnlyAfterExactRevision() {
        val f = fixture()
        val task = f.createTask()
        f.addRun(task.id)
        val cancelled = payload(f.tools.tool(TaskManagementTools.CANCEL_TASK).execute(
            mapOf("task_id" to task.id), CancellationToken.uncancellable()))
        assertEquals("cancelled", cancelled.getString("status"))
        assertEquals("kept", cancelled.getString("history"))
        assertTrue(f.repository.list().isEmpty())
        assertEquals(1, f.ledger.forTask(task.id).size)
    }

    @Test fun alreadyCancelledTokenPreventsTaskCreation() {
        val f = fixture()
        val token = CancellationToken.cancellable().also { it.cancel() }
        assertThrows(CancellationException::class.java) {
            f.tools.tool(TaskManagementTools.SCHEDULE_TASK).execute(scheduleArgs("must not exist"), token)
        }
        assertTrue(f.repository.list().isEmpty())
    }

    private data class Fixture(
        val tools: TaskManagementTools,
        val repository: TaskRepository,
        val ledger: TaskRunLedger,
        val sessionId: String,
        val creatorNames: List<String>,
        val enqueued: MutableList<Pair<String, ScheduledTask?>>,
        val pendingActions: TaskPendingActionStore,
    ) {
        fun createTask(instruction: String = "Read the current status"): ScheduledTask = repository.create(
            "Diagnostic task", instruction,
            TaskSchedule.Calendar("08:00", CalendarCadence.DAILY, zone = TaskZone.Iana("UTC")),
            toolScope = TaskToolScope("LISTED", emptyList()), createdBy = "AGENT_CHAT:$sessionId",
            creatorToolNames = creatorNames)
        fun addRun(taskId: String, startedAt: Long = 123L) = ledger.appendIfAbsent(TaskRunRecord(
            taskId, startedAt.toString(), startedAt, startedAt, startedAt + 1, "OK", "DELIVERED"))
    }

    private fun fixture(
        creatorNames: List<String> = emptyList(),
        taskCapabilityNames: List<String> = creatorNames,
        creatorMemoryAllowed: Boolean = false,
        creatorSkillIds: List<String> = emptyList(),
        clock: Clock = this.clock,
        zone: ZoneId = this.zone,
    ): Fixture {
        val root = Files.createTempDirectory("st2-task-tools").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val zoneProvider = TaskZoneProvider { zone }
        val repository = TaskRepository(context, store, ScheduleCalculator(clock, zoneProvider), clock, {}, ledger)
        val sessionId = "st2-tools-${System.nanoTime()}"
        val names = creatorNames.toList()
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, permissionGranted = { true }, approvalGate = ApprovalGate.INSTANCE)
        val enqueued = mutableListOf<Pair<String, ScheduledTask?>>()
        val pendingActions = TaskPendingActionStore(File(root, "pending.jsonl"))
        return Fixture(TaskManagementTools(context, sessionId, names, repository, ledger, clock, zoneProvider, registry,
            { _, id, _, expected -> enqueued += id to expected }, { _, _ -> false }, taskCapabilityNames,
            pendingActions, creatorMemoryAllowed, creatorSkillIds),
            repository, ledger, sessionId, names, enqueued, pendingActions)
    }

    private fun scheduleArgs(instruction: String, cadence: String = "daily", interval: Int = 30,
                             time: String = "08:00", zone: String? = "UTC", days: List<Int> = listOf(1),
                             dayOfMonth: Int = 20, localDateTime: String = "2025-03-08T08:00:00"): Map<String, Any> {
        val schedule = linkedMapOf<String, Any>("type" to cadence)
        when (cadence) {
            "at" -> schedule["local_date_time"] = localDateTime
            "every" -> { schedule["interval"] = interval; schedule["unit"] = "minutes" }
            "daily" -> schedule["time"] = time
            "weekly" -> { schedule["time"] = time; schedule["days"] = days }
            "monthly" -> { schedule["time"] = time; schedule["day_of_month"] = dayOfMonth }
        }
        if (zone != null) schedule["zone"] = zone
        return mapOf("name" to "Diagnostic task", "instruction" to instruction, "schedule" to schedule)
    }

    private val TaskManagementTools.allTools: Map<String, CoreTool> get() = tools().associateBy { it.declaration().name }
    private fun TaskManagementTools.tool(name: String): CoreTool = allTools[name] ?: error("Tool $name is missing")
    private fun payload(result: CoreToolResult): JSONObject = JSONObject(result.content)
}
