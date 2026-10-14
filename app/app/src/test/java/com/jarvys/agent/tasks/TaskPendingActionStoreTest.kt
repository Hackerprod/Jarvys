package com.jarvys.agent.tasks

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPendingActionStoreTest {
    @Test fun queueCanonicalizesAndBindsTheExactTaskRunToolAndArguments() {
        val file = tempFile()
        val store = TaskPendingActionStore(file, "process-a", true)
        val task = task()
        val pending = store.enqueue(task, "run-1", "gmail_send_message",
            """{"z":2,"a":{"y":true,"x":[1,"two"]}}""")

        assertEquals("""{"a":{"x":[1,"two"],"y":true},"z":2}""", pending.argumentsJson)
        assertEquals(task.revision, pending.taskRevision)
        assertEquals("run-1", pending.runId)
        assertTrue(pending.isCurrentFor(task))
        assertEquals(pending, TaskPendingActionStore(file, "process-a", true).get(pending.id))
        assertEquals(pending, store.enqueue(task, "run-1", "gmail_send_message",
            """{"a":{"x":[1,"two"],"y":true},"z":2}"""))
        assertEquals(1, file.readLines().size)
        assertEquals(TaskState.AwaitingUser, store.stateForTask(task.id, task.revision))
        assertFalse(pending.taskBinding == TaskPendingActionStore.taskBindingFor(
            task.copy(revision = task.revision + 1L), pending.runId, pending.toolName, pending.argumentsJson))
    }

    @Test fun claimIsDurableSingleUseAndExecutionReceiptFinishesTheSameBinding() {
        val file = tempFile()
        val store = TaskPendingActionStore(file, "process-a", true)
        val task = task()
        val pending = store.enqueue(task, "run-2", "drive_create_file", """{"name":"notes.txt"}""")

        val claim = store.claim(pending.id, task)
        assertTrue(claim is TaskPendingActionClaim.Claimed)
        assertEquals(TaskPendingActionStatus.CLAIMED, store.get(pending.id)?.status)
        assertTrue(store.claim(pending.id, task) is TaskPendingActionClaim.NotPending)

        val executed = store.markExecuted(pending.id, pending.actionBinding, """{"created":true}""")
        assertEquals(TaskPendingActionStatus.EXECUTED, executed?.status)
        assertEquals("""{"created":true}""", executed?.resultJson)
        assertTrue(store.claim(pending.id, task) is TaskPendingActionClaim.NotPending)
        assertEquals(3, file.readLines().size)
    }

    @Test fun rejectionAndTaskRevisionChangeAreTerminalWithoutAClaim() {
        val store = TaskPendingActionStore(tempFile(), "process-a", true)
        val task = task()
        val rejected = store.enqueue(task, "run-reject", "calendar_create_event", "{}")
        assertEquals(TaskPendingActionStatus.REJECTED,
            store.reject(rejected.id, rejected.actionBinding)?.status)
        assertEquals(TaskPendingActionStatus.REJECTED, store.reject(rejected.id)?.status)
        assertEquals(TaskState.Active, store.stateForTask(task.id, task.revision))

        val stale = store.enqueue(task, "run-stale", "calendar_create_event", "{}")
        val changed = task.copy(revision = task.revision + 1L)
        val claim = store.claim(stale.id, changed)
        assertTrue(claim is TaskPendingActionClaim.Stale)
        assertEquals(TaskPendingActionStatus.STALE, store.get(stale.id)?.status)
        assertEquals(TaskState.AwaitingUser, store.stateForTask(task.id, task.revision))
    }

    @Test fun reopeningAClaimedActionMarksItUncertainAndDoesNotAllowReplay() {
        val file = tempFile()
        val firstProcess = TaskPendingActionStore(file, "process-a", true)
        val task = task()
        val pending = firstProcess.enqueue(task, "run-crash", "gmail_send_message",
            """{"to":"person@example.test"}""")
        assertTrue(firstProcess.claim(pending.id, task) is TaskPendingActionClaim.Claimed)

        val reopened = TaskPendingActionStore(file, "process-b", true)
        assertEquals(TaskPendingActionStatus.INTERRUPTED_UNCERTAIN, reopened.get(pending.id)?.status)
        assertTrue(reopened.claim(pending.id, task) is TaskPendingActionClaim.NotPending)
        assertEquals(3, file.readLines().size)
    }

    private fun tempFile() = File(Files.createTempDirectory("task-pending-actions").toFile(), "pending.jsonl")
    private fun task() = ScheduledTask(
        name = "Send update", instruction = "Send the requested update",
        schedule = TaskSchedule.Every(900_000L, 100L), createdAt = 1L,
        toolScope = TaskToolScope("LISTED", listOf("gmail_send_message"), writes = "ALLOW"),
    )
}
