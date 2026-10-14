package com.jarvys.agent.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CoreToolResult
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskPendingActionExecutionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun approvalInvokesTheExactActionOnceAndPersistsItsReceipt() {
        val fixture = fixture()
        var invocations = 0
        val execution = fixture.execution()
        val executor = TaskPendingActionExecutor { _, task, action ->
            invocations++
            assertEquals(fixture.task.id, task.id)
            assertEquals("gmail_send_message", action.toolName)
            assertEquals("""{"to":"person@example.test"}""", action.argumentsJson)
            CoreToolResult.success("{\"sent\":true}")
        }

        assertEquals(TaskPendingActionExecutionStatus.EXECUTED,
            execution.execute(fixture.pending.id, fixture.task.id, executor))
        assertEquals(TaskPendingActionExecutionStatus.NOT_PENDING,
            execution.execute(fixture.pending.id, fixture.task.id, executor))
        assertEquals(1, invocations)
        val receipt = fixture.pendingStore.get(fixture.pending.id)
        assertEquals(TaskPendingActionStatus.EXECUTED, receipt?.status)
        assertTrue(receipt?.resultJson.orEmpty().contains("sent"))
    }

    @Test fun failedExecutionIsMarkedUncertainAndARepeatedWorkerCannotReplayIt() {
        val fixture = fixture()
        var invocations = 0
        val execution = fixture.execution()
        val executor = TaskPendingActionExecutor { _, _, _ ->
            invocations++
            error("simulated failure after dispatch began")
        }

        assertEquals(TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN,
            execution.execute(fixture.pending.id, fixture.task.id, executor))
        assertEquals(TaskPendingActionExecutionStatus.NOT_PENDING,
            execution.execute(fixture.pending.id, fixture.task.id, executor))
        assertEquals(1, invocations)
        assertEquals(TaskPendingActionStatus.INTERRUPTED_UNCERTAIN,
            fixture.pendingStore.get(fixture.pending.id)?.status)
    }

    @Test fun rejectionAndRevisionChangeNeverInvokeTheApprovedTool() {
        val rejected = fixture("rejected")
        rejected.pendingStore.reject(rejected.pending.id, rejected.pending.actionBinding)
        var rejectedInvocations = 0
        assertEquals(TaskPendingActionExecutionStatus.NOT_PENDING,
            rejected.execution().execute(rejected.pending.id, rejected.task.id,
                TaskPendingActionExecutor { _, _, _ -> rejectedInvocations++; CoreToolResult.success("unexpected") }))
        assertEquals(0, rejectedInvocations)

        val changed = fixture("changed")
        changed.taskStore.update(changed.task.copy(instruction = "Changed after request"), changed.task.revision)
        var changedInvocations = 0
        assertEquals(TaskPendingActionExecutionStatus.STALE,
            changed.execution().execute(changed.pending.id, changed.task.id,
                TaskPendingActionExecutor { _, _, _ -> changedInvocations++; CoreToolResult.success("unexpected") }))
        assertEquals(0, changedInvocations)
        assertEquals(TaskPendingActionStatus.STALE, changed.pendingStore.get(changed.pending.id)?.status)
    }

    private fun fixture(suffix: String = "run"): Fixture {
        val root = Files.createTempDirectory("pending-action-execution-$suffix").toFile()
        val taskStore = TaskStore(File(root, "tasks.jsonl"))
        val pendingStore = TaskPendingActionStore(File(root, "pending-actions.jsonl"), "process-$suffix", true)
        val task = taskStore.create(ScheduledTask(name = "Send update", instruction = "Send the update",
            schedule = TaskSchedule.Every(ScheduledTaskValidation.MIN_INTERVAL_MILLIS, 100L), createdAt = 1L,
            toolScope = TaskToolScope("LISTED", listOf("gmail_send_message"), writes = "ALLOW")))
        val pending = pendingStore.enqueue(task, "run-$suffix", "gmail_send_message",
            """{"to":"person@example.test"}""")
        return Fixture(context, taskStore, pendingStore, task, pending)
    }

    private data class Fixture(
        val context: Context,
        val taskStore: TaskStore,
        val pendingStore: TaskPendingActionStore,
        val task: ScheduledTask,
        val pending: TaskPendingAction,
    ) {
        fun execution() = TaskPendingActionExecution(context, pendingStore, taskStore::get, dismissNotification = {})
    }
}
