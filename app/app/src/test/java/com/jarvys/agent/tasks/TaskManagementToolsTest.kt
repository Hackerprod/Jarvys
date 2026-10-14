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
    private lateinit var context: Context
    private val clock = Clock.fixed(Instant.parse("2025-03-07T23:30:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("UTC")

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext() }

    @Test fun declarationsAreFiveSeparateToolsWithoutCronOrAnActionDiscriminator() {
        val tools = fixture().tools.tools()
        assertEquals(TaskManagementTools.TOOL_NAMES, tools.map { it.declaration().name }.toSet())
        tools.forEach { assertFalse((it.declaration().jsonSchema()["properties"] as Map<*, *>).containsKey("action")) }
        val create = tools.single { it.declaration().name == TaskManagementTools.SCHEDULE_TASK }
        val properties = create.declaration().jsonSchema()["properties"] as Map<*, *>
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
        assertTrue(data.getString("now_local").contains("2025-03-07T23:30"))
        assertEquals(1, data.getJSONArray("tasks").length())

        val runsTool = f.tools.tool(TaskManagementTools.TASK_RUNS)
        val all = runsTool.execute(mapOf("task_id" to task.id), CancellationToken.uncancellable())
        assertFalse(all.content.contains("UNTRUSTED TASK DATA"))
        assertEquals(4, payload(all).getJSONArray("runs").length())
        val limited = runsTool.execute(mapOf("task_id" to task.id, "limit" to 2), CancellationToken.uncancellable())
        assertEquals(2, payload(limited).getJSONArray("runs").length())
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
        return Fixture(TaskManagementTools(context, sessionId, names, repository, ledger, clock, zoneProvider, registry),
            repository, ledger, sessionId, names)
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
