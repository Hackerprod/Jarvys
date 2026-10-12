package com.jarvys.agent.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.NativeActionPolicy
import com.jarvys.agent.connectors.AutonomyPolicy
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskActionPolicyTest {
    private val clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private fun task() = ScheduledTask(name = "Exact task", instruction = "Read status",
        schedule = TaskSchedule.Calendar("08:00", CalendarCadence.DAILY, zone = TaskZone.Iana("UTC")),
        toolScope = TaskToolScope("LISTED", emptyList(), false, "DEFER"), createdBy = "AGENT_CHAT:session:message",
        createdAt = clock.millis(), creatorToolNames = listOf("web_search"))

    @Test fun everySemanticFieldSessionRevisionAndSubeffectChangesTheExactBinding() {
        val original = task()
        val key = TaskActionBinding.task("session", "create", original, "create")
        val alternatives = listOf(
            original.copy(id = java.util.UUID.randomUUID().toString()), original.copy(revision = 2),
            original.copy(name = "Other"), original.copy(instruction = "Other"),
            original.copy(schedule = TaskSchedule.Calendar("09:00", CalendarCadence.DAILY)),
            original.copy(state = TaskState.Paused), original.copy(timePrecision = TaskTimePrecision.EXACT),
            original.copy(catchUp = TaskCatchUp.SKIP_MISSED), original.copy(validUntil = clock.millis() + 10000),
            original.copy(deleteAfterRun = true), original.copy(toolScope = original.toolScope.copy(web = true)),
            original.copy(toolScope = original.toolScope.copy(tools = listOf("web_search"))),
            original.copy(toolScope = original.toolScope.copy(writes = "NEVER")),
            original.copy(delivery = TaskDelivery.ONLY_IF_NOTABLE), original.copy(createdBy = "AGENT_CHAT:other"),
            original.copy(creatorToolNames = emptyList()), original.copy(updatedAt = clock.millis() + 1),
        )
        alternatives.forEach { assertNotEquals(key, TaskActionBinding.task("session", "create", it, "create")) }
        assertNotEquals(key, TaskActionBinding.task("other", "create", original, "create"))
        assertNotEquals(key, TaskActionBinding.task("session", "create", original, "create_and_test"))
        assertNotEquals(TaskActionBinding.update("session", original, original.copy(name = "New")),
            TaskActionBinding.update("session", original.copy(revision = 2), original.copy(name = "New")))
    }

    @Test fun bindingsCannotBeReusedForChangedScopeAndClosedRequestsCannotReplay() {
        val policy = NativeActionPolicy.inMemory()
        val candidate = task()
        val exact = TaskActionBinding.task("session", "create", candidate)
        val permission = policy.prepare(NativeActionPolicy.Operation.TASK_CREATE, exact)
        assertTrue(runCatching { permission.admit {
            permission.requireBinding(TaskActionBinding.task("session", "create",
                candidate.copy(toolScope = candidate.toolScope.copy(web = true))))
        } }.isFailure)
        permission.admit { permission.requireBinding(exact) }
        permission.close()
        assertTrue(runCatching { permission.admit() }.isFailure)
    }

    @Test fun historyBindingCoversChangedRecordsAndNewOccurrences() {
        val first = TaskRunRecord("task", "run", 1, 2, status = "OK")
        val second = first.copy(runId = "other", scheduledFor = 3)
        val binding = TaskActionBinding.history(listOf(first))
        assertNotEquals(binding, TaskActionBinding.history(listOf(first.copy(status = "ERROR"))))
        assertNotEquals(binding, TaskActionBinding.history(listOf(first.copy(toolsCalled = listOf(TaskToolCallRecord("read", false, "OK"))))))
        assertNotEquals(binding, TaskActionBinding.history(listOf(first, second)))
        assertEquals(TaskActionBinding.history(listOf(first, second)), TaskActionBinding.history(listOf(second, first)))
    }

    @Test fun finalStorageAdmissionRejectsRevocationBeforeAnyTaskAppend() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = Files.createTempDirectory("task-policy-store").resolve("tasks.jsonl").toFile()
        val store = TaskStore(file)
        val ledger = TaskRunLedger(java.io.File(file.parentFile, "runs.jsonl"))
        var rearmed = 0
        val repository = TaskRepository(context, store, ScheduleCalculator(clock), clock, { rearmed++ }, ledger)
        val policy = NativeActionPolicy.inMemory()
        val candidate = task()
        val permission = policy.prepare(NativeActionPolicy.Operation.TASK_CREATE,
            TaskActionBinding.task("session", "create", candidate))
        assertTrue(runCatching { repository.createPreparedIfEquivalent(candidate) {
            policy.set(NativeActionPolicy.Operation.TASK_CREATE, AutonomyPolicy.DENY)
            permission.admit(CancellationToken.uncancellable())
        } }.isFailure)
        assertTrue(repository.list().isEmpty())
        assertFalse(file.exists())
        assertEquals(0, rearmed)

        val saved = repository.createPreparedIfEquivalent(candidate).task
        val bytes = file.readBytes()
        val beforeRearm = rearmed
        listOf<(() -> Unit) -> Any?>(
            { check -> repository.update(saved.copy(name = "changed"), saved.revision, check) },
            { check -> repository.pause(saved.id, saved.revision, check) },
            { check -> repository.delete(saved.id, saved.revision, check) },
        ).forEach { action ->
            assertTrue(runCatching { action { throw IllegalStateException("revoked before storage") } }.isFailure)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(saved, repository.get(saved.id))
        }
        assertEquals(beforeRearm, rearmed)
        permission.close()
    }

    @Test fun sameRevisionRestoredContentCannotBeOverwrittenPausedResumedOrDeleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = Files.createTempDirectory("task-policy-restore").resolve("tasks.jsonl").toFile()
        val store = TaskStore(file)
        val repository = TaskRepository(context, store, ScheduleCalculator(clock), clock, {})
        val reviewed = repository.createPreparedIfEquivalent(task()).task
        val row = org.json.JSONObject(file.readLines().single()).put("instruction", "Restored different content")
        file.writeText(row.toString() + "\n")
        val restored = repository.get(reviewed.id)!!
        assertEquals(reviewed.revision, restored.revision)
        var admissions = 0
        val admit = { admissions++ }
        val attempts = listOf<() -> Any?>(
            { repository.update(reviewed.copy(name = "Old approved edit"), reviewed.revision, { admit(); Unit }, reviewed) },
            { repository.pause(reviewed.id, reviewed.revision, { admit(); Unit }, reviewed) },
            { repository.resume(reviewed.id, reviewed.revision, { admit(); Unit }, reviewed) },
            { repository.delete(reviewed.id, reviewed.revision, { admit(); Unit }, reviewed) },
        )
        attempts.forEach { action -> assertTrue(runCatching(action).isFailure); assertEquals(restored, repository.get(reviewed.id)) }
        assertEquals(0, admissions)
    }

    @Test fun staleRevisionFailsBeforeRetryBookkeepingAndProtectedHistoryReplacement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory("task-policy-retry").toFile()
        val store = TaskStore(java.io.File(root, "tasks.jsonl"))
        val ledgerFile = java.io.File(root, "runs.jsonl")
        val ledger = TaskRunLedger(ledgerFile)
        val repository = TaskRepository(context, store, ScheduleCalculator(clock), clock, {}, ledger)
        val scheduled = clock.millis() - 1
        val failed = task().copy(state = TaskState.NeedsAttention("error"), lastRun =
            TaskLastRun("failed", scheduled, scheduled, scheduled, "ERROR", "NOT_APPLICABLE"))
        val saved = store.create(failed)
        ledger.appendIfAbsent(TaskRunRecord(saved.id, "failed", scheduled, scheduled, status = "ERROR"))
        repository.update(saved.copy(name = "new revision"), saved.revision)
        val bytes = ledgerFile.readBytes()
        assertTrue(runCatching { repository.resume(saved.id, saved.revision) }.isFailure)
        assertArrayEquals(bytes, ledgerFile.readBytes())
        assertTrue(runCatching { ledger.clearTask(saved.id) { throw IllegalStateException("revoked") } }.isFailure)
        assertArrayEquals(bytes, ledgerFile.readBytes())
        assertEquals("ERROR", ledger.forTask(saved.id).single().status)
    }
}
