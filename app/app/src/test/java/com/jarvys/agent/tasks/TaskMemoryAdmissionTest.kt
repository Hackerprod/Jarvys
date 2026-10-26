package com.jarvys.agent.tasks

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic local fixtures only; no private connector payloads or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaskMemoryAdmissionTest {
    @get:Rule val fixture = ChatTestStore()
    private lateinit var context: Context
    private lateinit var bridge: ChatDatabaseBridge
    private lateinit var tasks: TaskStore
    private lateinit var pending: TaskPendingActionStore
    private lateinit var ledger: File
    private lateinit var task: ScheduledTask
    private lateinit var action: TaskPendingAction

    @Before fun setup() {
        val files = fixture.newFilesDirectory()
        fixture.localRunStore(files)
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        bridge = ChatDatabaseBridge.getInstance(context)
        bridge.ensureConversationSync(WorkspaceIdentity.CENTRAL_SESSION)
        tasks = TaskStore(File(files, "test-tasks.jsonl"))
        ledger = File(files, "test-pending.jsonl")
        pending = TaskPendingActionStore(ledger, "memory-test-process", true, context)
        task = tasks.create(ScheduledTask(name = "Local memory", instruction = "Store a synthetic note",
            schedule = TaskSchedule.Every(ScheduledTaskValidation.MIN_INTERVAL_MILLIS, 100L), createdAt = 1L,
            toolScope = TaskToolScope("LISTED", listOf("local_memory"), writes = "ALLOW")))
        action = pending.enqueue(task, "memory-test-run", "local_memory", "{}")
    }

    private fun execution() = TaskPendingActionExecution(context, pending, tasks::get, dismissNotification = {})
    private fun source() = "task:${action.taskId}:${action.occurrenceId}"
    private fun assertNoEpisode() = assertTrue(
        bridge.personalAgentEpisodesForSourceMessagesSync(WorkspaceIdentity.CENTRAL_SESSION, listOf(source())).isEmpty())
    private fun executor(block: (EffectInvocation) -> CoreToolResult) = object : TaskPendingActionExecutor {
        override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction): CoreToolResult =
            error("Explicit context required")
        override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction,
                             invocation: EffectInvocation) = block(invocation)
        override fun hasCompleteEffectAdmissionCoverage() = true
        override fun actionEffectClassification(context: Context, task: ScheduledTask, action: TaskPendingAction) =
            TaskActionEffectClassification.MEMORY
    }

    @Test fun memoryCompletionSurvivesRecoveryWithoutReadOrExternalWriteEvidence() {
        val result = execution().execute(action.id, task.id, executor { invocation ->
            CoreToolResult.success("synthetic memory operation returned")
                .withAdministrativeReceipt(invocation.memoryCompleted())
        })
        assertEquals(TaskPendingActionExecutionStatus.EXECUTED, result)
        val reopened = TaskPendingActionStore(ledger, "reopened-process", true, context).get(action.id)!!
        assertEquals("MEMORY", reopened.approvalEffectClassification)
        val receipt = JSONObject(reopened.resultJson!!)
        assertEquals("MEMORY_COMPLETED", receipt.getString("administrative_kind"))
        assertFalse(receipt.getBoolean("read_completed"))
        assertEquals("NOT_EXECUTED", receipt.getString("effect_outcome"))
        assertNoEpisode()
    }

    @Test fun memoryClaimCrashKeepsExclusionWithoutReplayingOrInventingAnEpisode() {
        assertTrue(pending.claim(action.id, task, "memory-crash-attempt", "MEMORY") is TaskPendingActionClaim.Claimed)
        val reopened = TaskPendingActionStore(ledger, "new-process", true, context)
        assertEquals(TaskPendingActionStatus.INTERRUPTED_UNCERTAIN, reopened.get(action.id)?.status)
        assertEquals("MEMORY", reopened.get(action.id)?.approvalEffectClassification)
        assertTrue(reopened.claim(action.id, task, "retry", "MEMORY") is TaskPendingActionClaim.NotPending)
        assertNoEpisode()
    }

    @Test fun memoryToExternalWriteDriftIsRejectedBeforeDispatch() {
        var effects = 0
        val result = execution().execute(action.id, task.id, executor { invocation ->
            invocation.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
                "synthetic gate", action.id, "changed", "test"))
            effects++
            CoreToolResult.success("unreachable")
        })
        assertEquals(0, effects)
        assertEquals(TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN, result)
        assertNoEpisode()
    }

    @Test fun foreignMemoryCompletionCannotMarkAnApprovalExecuted() {
        val foreign = EffectInvocation { }.memoryCompleted()
        val result = execution().execute(action.id, task.id, executor {
            CoreToolResult.success("synthetic response").withAdministrativeReceipt(foreign)
        })
        assertEquals(TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN, result)
        assertNoEpisode()
    }

    @Test fun genericMemorySuccessDoesNotProveCompletion() {
        val result = execution().execute(action.id, task.id, executor {
            CoreToolResult.success("generic success without a memory completion receipt")
        })
        assertEquals(TaskPendingActionExecutionStatus.INTERRUPTED_UNCERTAIN, result)
        assertNoEpisode()
    }

    @Test fun trackedWrapperUsesArgumentSensitiveMemoryAndPreservesMemoryReceipt() {
        val calls = mutableListOf<TaskToolCallRecord>()
        var memoryOperations = 0
        val delegate = object : CoreTool {
            override fun declaration() = ToolSpec("mixed_local", "test", "Mixed local", "test",
                ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun unattendedAccess() = CoreTool.UnattendedAccess.WRITE
            override fun effectClass(arguments: Map<String, Any>) = CoreTool.EffectClass.WRITE
            override fun persistenceEffect() = CoreTool.PersistenceEffect.NONE
            override fun persistenceEffect(arguments: Map<String, Any>) =
                if (arguments["memory"] == true) CoreTool.PersistenceEffect.MEMORY else CoreTool.PersistenceEffect.NONE
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult =
                error("Explicit context required")
            override fun execute(arguments: Map<String, Any>, token: CancellationToken,
                                 progress: CoreTool.ProgressListener?, invocation: EffectInvocation?): CoreToolResult {
                memoryOperations++
                return CoreToolResult.success("synthetic memory saved")
                    .withAdministrativeReceipt(invocation!!.memoryCompleted())
            }
        }
        val wrapped = ScheduledTaskProcessor.TaskTrackedTool(delegate, true, calls, false,
            sourceMessageId = source(), episodeRunId = "memory-wrapper-run", context = context)
        val invocation = EffectInvocation { error("Memory is not an external write") }
        assertEquals(CoreTool.PersistenceEffect.MEMORY, wrapped.persistenceEffect(mapOf("memory" to true)))
        val result = wrapped.execute(mapOf("memory" to true), CancellationToken.cancellable(), null, invocation)
        assertEquals(1, memoryOperations)
        assertTrue(invocation.ownsMemoryCompletion(result.administrativeReceipt))
        assertFalse(invocation.ownsReadCompletion(result.administrativeReceipt))
        assertFalse(invocation.admitted())
        assertTrue(calls.single().write)
        assertFalse(calls.single().noDispatchVerified)
        assertNoEpisode()
    }

    @Test fun trackedWriteForwardsExactConnectionObservationAlongsideItsIntent() {
        val observation = com.jarvys.agent.connectors.ConnectionObservationTestSupport.observation("mail", true)
        val calls = mutableListOf<TaskToolCallRecord>()
        val delegate = object : CoreTool {
            override fun declaration() = ToolSpec("local_memory", "connector:mail", "Synthetic write", "test",
                ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun unattendedAccess() = CoreTool.UnattendedAccess.WRITE
            override fun effectClass(arguments: Map<String, Any>) = CoreTool.EffectClass.WRITE
            override fun hasCompleteEffectAdmissionCoverage() = true
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult =
                error("Explicit context required")
            override fun execute(arguments: Map<String, Any>, token: CancellationToken,
                progress: CoreTool.ProgressListener?, invocation: EffectInvocation?): CoreToolResult {
                requireNotNull(invocation).admit(EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
                    "connector:mail", "write", "1", "test", observation))
                return CoreToolResult.success("synthetic write").withEffectReceipt(invocation.receipt(EffectInvocation.Outcome.OK))
            }
        }
        val wrapper = ScheduledTaskProcessor.TaskTrackedTool(delegate, true, calls, false,
            sourceMessageId = source(), episodeRunId = "causal-task-run", context = context)
        val invocation = EffectInvocation { }
        assertTrue(wrapper.execute(emptyMap(), CancellationToken.uncancellable(), null, invocation).success)
        val entry = bridge.personalAgentEpisodesForSourceMessagesSync(WorkspaceIdentity.CENTRAL_SESSION, listOf(source())).single()
        val row = bridge.personalAgentEpisodeNarrationInputSync(entry.id)!!.evidence.single { it.refKind == "connector_event" }
        assertEquals(observation.eventId(), row.refId)
        assertEquals("causal-task-run", row.runId)
        assertNull(row.authorization)
        assertEquals("local_choice", row.outcome)
        assertEquals("OK", calls.single().outcome)
    }

    @Test fun pendingApprovalForwardsExactObservationWithoutGuessingConnectorFromToolName() {
        val observation = com.jarvys.agent.connectors.ConnectionObservationTestSupport.observation("mail", true)
        val writeExecutor = object : TaskPendingActionExecutor {
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction): CoreToolResult =
                error("Explicit context required")
            override fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction,
                invocation: EffectInvocation): CoreToolResult {
                invocation.admit(EffectInvocation.Admission(EffectInvocation.Authorization.ASK_APPROVED,
                    "connector:mail", "write", "1", "test", observation))
                return CoreToolResult.success("synthetic write").withEffectReceipt(invocation.receipt(EffectInvocation.Outcome.OK))
            }
            override fun hasCompleteEffectAdmissionCoverage() = true
            override fun actionEffectClassification(context: Context, task: ScheduledTask, action: TaskPendingAction) =
                TaskActionEffectClassification.WRITE
        }
        assertEquals(TaskPendingActionExecutionStatus.EXECUTED, execution().execute(action.id, task.id, writeExecutor))
        val entry = bridge.personalAgentEpisodesForSourceMessagesSync(WorkspaceIdentity.CENTRAL_SESSION, listOf(source())).single()
        val input = bridge.personalAgentEpisodeNarrationInputSync(entry.id)!!
        val effect = input.evidence.single { it.refKind == "tool_call" }
        val causal = input.evidence.single { it.refKind == "connector_event" }
        assertEquals(observation.eventId(), causal.refId)
        assertEquals(effect.runId, causal.runId)
        assertEquals(effect.refId, JSONObject(causal.factsJson).getString("observed_for_call_id"))
        assertEquals("mail", causal.connector)
        assertNull(causal.authorization)
        assertEquals("local_choice", causal.outcome)
        assertEquals("ask_approved", effect.authorization)
    }

}
