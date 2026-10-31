package com.jarvys.agent.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.workspaces.WorkspaceIdentity
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
        val executor = instrumentedExecutor { _, task, action ->
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
        val executor = instrumentedExecutor { _, _, _ ->
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

    @Test fun legacySuccessCannotInventAnEffectReceipt() {
        val fixture = fixture("legacy")
        val result = fixture.execution().execute(fixture.pending.id, fixture.task.id,
            TaskPendingActionExecutor { _, _, _ -> CoreToolResult.success("administrative success") })
        assertEquals(TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN, result)
        assertEquals(TaskPendingActionStatus.INTERRUPTED_UNCERTAIN, fixture.pendingStore.get(fixture.pending.id)?.status)
    }

    @Test fun typedVisibleWaitDoesNotAdmitAnEffectAndIgnoresResponseText() {
        val fixture = fixture("visible")
        val executor = object : TaskPendingActionExecutor {
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction): CoreToolResult =
                error("context required")
            override fun hasCompleteEffectAdmissionCoverage() = true
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction,
                                 invocation: EffectInvocation): CoreToolResult {
                invocation.noteDecision(EffectInvocation.Admission(EffectInvocation.Authorization.ASK_APPROVED,
                    "test-authority", action.id, task.revision.toString(), "task_approval"))
                return CoreToolResult.success("arbitrary localized visible-app message")
                    .withEffectReceipt(invocation.receipt(EffectInvocation.Outcome.DEFERRED))
            }
        }
        assertEquals(TaskPendingActionExecutionStatus.WAITING_VISIBLE_APP,
            fixture.execution().execute(fixture.pending.id, fixture.task.id, executor))
        assertEquals(TaskPendingActionStatus.WAITING_VISIBLE_APP, fixture.pendingStore.get(fixture.pending.id)?.status)
    }

    @Test fun classifiedReadCompletesAdministrativelyWithoutCreatingWriteEpisode() {
        val fixture = fixture("read-only")
        val executor = readExecutor { invocation ->
            CoreToolResult.success("read complete").withAdministrativeReceipt(invocation.readCompleted())
        }
        assertEquals(TaskPendingActionExecutionStatus.EXECUTED,
            fixture.execution().execute(fixture.pending.id, fixture.task.id, executor))
        TaskPendingActionStore(fixture.pendingLedger, "reopened-process", true, context).get(fixture.pending.id)
        assertTrue(ChatDatabaseBridge.getInstance(context).personalAgentEpisodesForSourceMessagesSync(
            WorkspaceIdentity.CENTRAL_SESSION,
            listOf("task:${fixture.pending.taskId}:${fixture.pending.occurrenceId}")).isEmpty())
    }

    @Test fun readToWriteCatalogDriftFailsClosedBeforeEffectWithoutInventingWriteIntent() {
        val fixture = fixture("read-drift")
        var effects = 0
        val executor = readExecutor { invocation ->
            invocation.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
                "changed-catalog", fixture.pending.id, "new-revision", "test"))
            effects++
            CoreToolResult.success("must not execute")
        }
        assertEquals(TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN,
            fixture.execution().execute(fixture.pending.id, fixture.task.id, executor))
        assertEquals(0, effects)
        assertTrue(ChatDatabaseBridge.getInstance(context).personalAgentEpisodesForSourceMessagesSync(
            WorkspaceIdentity.CENTRAL_SESSION,
            listOf("task:${fixture.pending.taskId}:${fixture.pending.occurrenceId}")).isEmpty())
    }

    @Test fun readClaimCrashRetainsClassificationAndRecoveryDoesNotCreateWriteEpisode() {
        val fixture = fixture("read-crash")
        assertTrue(fixture.pendingStore.claim(fixture.pending.id, fixture.task, "read-crash-attempt", "READ")
            is TaskPendingActionClaim.Claimed)
        val reopened = TaskPendingActionStore(fixture.pendingLedger, "different-process", true, context)
        assertEquals(TaskPendingActionStatus.INTERRUPTED_UNCERTAIN, reopened.get(fixture.pending.id)?.status)
        assertEquals("READ", reopened.get(fixture.pending.id)?.approvalEffectClassification)
        assertTrue(ChatDatabaseBridge.getInstance(context).personalAgentEpisodesForSourceMessagesSync(
            WorkspaceIdentity.CENTRAL_SESSION,
            listOf("task:${fixture.pending.taskId}:${fixture.pending.occurrenceId}")).isEmpty())
    }

    private fun readExecutor(effect: (EffectInvocation) -> CoreToolResult) = object : TaskPendingActionExecutor {
        override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction): CoreToolResult =
            error("context required")
        override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction,
                             invocation: EffectInvocation) = effect(invocation)
        override fun hasCompleteEffectAdmissionCoverage() = true
        override fun effectClassification(context: Context, task: ScheduledTask, action: TaskPendingAction) =
            com.jarvys.agent.CoreTool.EffectClass.READ
    }

    private fun instrumentedExecutor(effect: (Context, ScheduledTask, TaskPendingAction) -> CoreToolResult) =
        object : TaskPendingActionExecutor {
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction): CoreToolResult =
                error("context required")
            override fun hasCompleteEffectAdmissionCoverage() = true
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction,
                                 invocation: EffectInvocation): CoreToolResult {
                invocation.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ASK_APPROVED,
                    "test-authority", action.id, task.revision.toString(), "task_approval"))
                val episode = ChatDatabaseBridge.getInstance(context).personalAgentEpisodesForSourceMessagesSync(
                    WorkspaceIdentity.CENTRAL_SESSION, listOf("task:${action.taskId}:${action.occurrenceId}")).single()
                val evidence = ChatDatabaseBridge.getInstance(context).personalAgentEpisodeNarrationInputSync(episode.id)!!.evidence
                assertTrue(evidence.any { it.refId == action.approvalAttemptId && it.runId == action.approvalAttemptId &&
                    it.outcome == "started" && it.authorization == "ask_approved" })
                val result = effect(context, task, action)
                return result.withEffectReceipt(invocation.receipt(
                    if (result.success) EffectInvocation.Outcome.OK else EffectInvocation.Outcome.ERROR))
            }
        }

    private fun fixture(suffix: String = "run"): Fixture {
        val root = Files.createTempDirectory("pending-action-execution-$suffix").toFile()
        val taskStore = TaskStore(File(root, "tasks.jsonl"))
        val pendingLedger = File(root, "pending-actions.jsonl")
        val pendingStore = TaskPendingActionStore(pendingLedger, "process-$suffix", true, context)
        val task = taskStore.create(ScheduledTask(name = "Send update", instruction = "Send the update",
            schedule = TaskSchedule.Every(ScheduledTaskValidation.MIN_INTERVAL_MILLIS, 100L), createdAt = 1L,
            toolScope = TaskToolScope("LISTED", listOf("gmail_send_message"), writes = "ALLOW")))
        val pending = pendingStore.enqueue(task, "run-$suffix", "gmail_send_message",
            """{"to":"person@example.test"}""")
        return Fixture(context, taskStore, pendingStore, task, pending, pendingLedger)
    }

    private data class Fixture(
        val context: Context,
        val taskStore: TaskStore,
        val pendingStore: TaskPendingActionStore,
        val task: ScheduledTask,
        val pending: TaskPendingAction,
        val pendingLedger: File,
    ) {
        fun execution() = TaskPendingActionExecution(context, pendingStore, taskStore::get, dismissNotification = {})
    }
}
