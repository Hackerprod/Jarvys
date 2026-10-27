package com.jarvys.agent.tasks

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.ToolSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaskAsyncExclusionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun currentAsyncDefinitionRejectsLegacyPendingBeforeClaimOrIntent() = pendingRejected(CoreTool.CompletionMode.ASYNC_JOB)
    @Test fun unknownCurrentDefinitionCannotDefaultToSynchronousClaim() = pendingRejected(null)

    private fun pendingRejected(mode: CoreTool.CompletionMode?) {
        val root = Files.createTempDirectory("task-async-exclusion").toFile()
        val tasks = TaskStore(File(root, "tasks.jsonl"))
        val ledger = File(root, "pending.jsonl")
        val store = TaskPendingActionStore(ledger, "synthetic-owner", true, context)
        val task = tasks.create(ScheduledTask(name = "Synthetic", instruction = "Synthetic operation",
            schedule = TaskSchedule.Every(ScheduledTaskValidation.MIN_INTERVAL_MILLIS, 100L), createdAt = 1L,
            toolScope = TaskToolScope("LISTED", listOf("synthetic_operation"), writes = "ALLOW")))
        val action = store.enqueue(task, "synthetic-run", "synthetic_operation", "{}")
        val originalBytes = ledger.readBytes()
        var metadataCalls = 0
        val executor = object : TaskPendingActionExecutor {
            override fun completionMode(context: Context, task: ScheduledTask, action: TaskPendingAction): CoreTool.CompletionMode? {
                metadataCalls++
                assertEquals("synthetic_operation", action.toolName)
                return mode
            }
            override fun actionEffectClassification(context: Context, task: ScheduledTask, action: TaskPendingAction): TaskActionEffectClassification =
                error("Unsupported completion must be rejected before synchronous evidence preparation")
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction): CoreToolResult =
                error("No dispatch")
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction, invocation: EffectInvocation): CoreToolResult =
                error("No dispatch or admission")
        }
        val execution = TaskPendingActionExecution(context, store, tasks::get,
            dismissNotification = { error("Existing pending action must remain unchanged") })
        repeat(2) {
            assertEquals(TaskPendingActionExecutionStatus.UNSUPPORTED, execution.execute(action.id, task.id, executor))
            assertEquals(action, store.get(action.id))
            assertArrayEquals(originalBytes, ledger.readBytes())
        }
        assertEquals(2, metadataCalls)
    }

    @Test fun trackedAsyncRejectsBeforeEvidenceAndPreservesArgumentSensitiveMetadata() {
        val calls = mutableListOf<TaskToolCallRecord>()
        val delegate = object : CoreTool {
            override fun declaration() = ToolSpec("synthetic_operation", "test", "Synthetic", "test",
                ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun completionMode(arguments: Map<String, Any>) = if (arguments["async"] == true)
                CoreTool.CompletionMode.ASYNC_JOB else CoreTool.CompletionMode.SYNCHRONOUS
            override fun hasCompleteAsyncSubmissionCoverage() = true
            override fun effectClass() = CoreTool.EffectClass.WRITE
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult = error("No dispatch")
        }
        val wrapper = ScheduledTaskProcessor.TaskTrackedTool(delegate, true, calls, false,
            sourceMessageId = "synthetic-source", episodeRunId = "synthetic-run", context = context)
        assertEquals(CoreTool.CompletionMode.SYNCHRONOUS, wrapper.completionMode(emptyMap()))
        assertEquals(CoreTool.CompletionMode.ASYNC_JOB, wrapper.completionMode(mapOf("async" to true)))
        assertTrue(wrapper.hasCompleteAsyncSubmissionCoverage())
        val invocation = EffectInvocation { error("No synchronous admission") }
        val result = wrapper.execute(mapOf("async" to true), CancellationToken.cancellable(), null, invocation)
        assertFalse(result.success)
        assertFalse(invocation.admitted())
        assertEquals(EffectInvocation.Outcome.NOT_EXECUTED, result.effectReceipt.outcome)
        assertEquals("NOT_EXECUTED", calls.single().outcome)
        assertTrue(calls.single().noDispatchVerified)
    }
}
