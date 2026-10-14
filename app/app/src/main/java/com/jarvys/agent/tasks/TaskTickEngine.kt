package com.jarvys.agent.tasks

import java.time.Clock
import java.util.UUID
import com.jarvys.agent.CancellationToken

data class TaskExecutionResult(
    val status: String = "OK",
    val deliveryStatus: String = "NOT_APPLICABLE",
    val reason: String? = null,
    val skipReason: String? = null,
    val notified: Boolean = false,
    val toolsCalled: List<TaskToolCallRecord> = emptyList(),
    val errorCause: String? = null,
    val model: String? = null,
    val usageTokens: Int? = null,
    val causeCode: TaskRunCause? = null,
    val causeDetail: String? = null,
    val deliveredResult: String? = null,
    val conversationMessageId: String? = null,
)

fun interface TaskExecutor {
    fun execute(task: ScheduledTask, scheduledFor: Long, executedAt: Long,
                runId: String, token: CancellationToken): TaskExecutionResult
}

/** ST0 diagnostic-only executor. It performs no model, network, connector, or UI work. */
class RecordingTaskExecutor : TaskExecutor {
    private val recorded = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    override fun execute(task: ScheduledTask, scheduledFor: Long, executedAt: Long,
                         runId: String, token: CancellationToken): TaskExecutionResult {
        token.throwIfCancelled()
        recorded += task.id to scheduledFor
        return TaskExecutionResult("OK", "NOT_APPLICABLE")
    }
    fun occurrences(): List<Pair<String, Long>> = synchronized(recorded) { recorded.toList() }
}

data class TaskTickResult(val started: Int, val skipped: Int, val deferred: Boolean, val retryWork: Boolean = false)

/** Processes all currently due tasks serially and owns occurrence idempotence before execution. */
class TaskTickEngine(
    private val tasks: TaskStore,
    private val runs: TaskRunLedger,
    private val calculator: ScheduleCalculator,
    private val executor: TaskExecutor,
    private val clock: Clock = Clock.systemUTC(),
    private val rearm: () -> Unit = {},
    private val canStartAnother: () -> Boolean = { true },
    private val token: CancellationToken = CancellationToken.uncancellable(),
    private val isGloballyPaused: () -> Boolean = { false },
    private val pendingActions: TaskPendingActionStore? = null,
) {
    fun tick(): TaskTickResult {
        var started = 0
        var skipped = 0
        var deferred = false
        var retryWork = false
        try {
            val now = clock.millis()
            val due = tasks.read().tasks.asSequence()
                .filter { it.state == TaskState.Active }
                .filter { pendingActions?.stateForTask(it.id, it.revision, it.state) != TaskState.AwaitingUser }
                .filter { (it.validUntil != null && now >= it.validUntil)
                    || (it.nextRunAt != null && it.nextRunAt <= now) }
                .sortedWith(compareBy<ScheduledTask> { it.nextRunAt }.thenBy { it.id })
                .toList()
            for (snapshot in due) {
                if (token.isCancelled || !canStartAnother() || isGloballyPaused()) {
                    deferred = true
                    break
                }
                val task = tasks.get(snapshot.id) ?: continue
                if (task.state != TaskState.Active) continue
                if (pendingActions?.stateForTask(task.id, task.revision, task.state) == TaskState.AwaitingUser) continue
                if (task.validUntil != null && now >= task.validUntil) {
                    updateTask(task, task.copy(state = TaskState.Done, nextRunAt = null))
                    continue
                }
                val at = task.nextRunAt ?: continue
                val latestDue = calculator.latestDueAtOrBefore(task, now) ?: at
                val skip = task.catchUp == TaskCatchUp.SKIP_MISSED && now > at
                val previousTaskRetry = task.lastRun?.takeIf { it.status == "RETRYABLE" || it.status == "INTERRUPTED" }
                val previousLedgerRetry = runs.forTask(task.id)
                    .lastOrNull { it.status == "RETRYABLE" || it.status == "INTERRUPTED" }
                val scheduledFor = previousTaskRetry?.scheduledFor ?: previousLedgerRetry?.scheduledFor ?: latestDue
                val startedAt = clock.millis()
                val outcome = runs.withOccurrenceLock {
                    if (isGloballyPaused()) null else {
                        val existing = runs.find(task.id, scheduledFor)
                        if (existing != null && existing.status !in RETRYABLE_STATES) {
                            if (existing.status == "STARTED") {
                                val interrupted = existing.copy(finishedAt = now, status = "INTERRUPTED",
                                    deliveryStatus = "UNKNOWN", reason = null, skipReason = null, errorCause = null,
                                    causeCode = TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED)
                                runs.saveSnapshot(interrupted)
                                TickOccurrence.Existing(interrupted)
                            } else TickOccurrence.Existing(existing)
                        } else {
                            val runId = UUID.randomUUID().toString()
                            started++
                            val result = if (skip) TaskExecutionResult("SKIPPED", "NOT_APPLICABLE",
                                causeCode = TaskRunCause.MISSED_OCCURRENCE)
                                else try { executor.execute(task, scheduledFor, startedAt, runId, token) }
                                catch (_: java.util.concurrent.CancellationException) {
                                    TaskExecutionResult("INTERRUPTED", "NOT_APPLICABLE",
                                        causeCode = TaskRunCause.INTERRUPTED)
                                }
                                catch (failure: Exception) { TaskExecutionResult("ERROR", "NOT_APPLICABLE",
                                    causeCode = TaskRunCause.EXECUTION_FAILED,
                                    causeDetail = failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName) }
                            val finishedAt = clock.millis()
                            val writeMayHaveRun = result.toolsCalled.any { it.write }
                            val status = if (result.status in RETRYABLE_STATES && writeMayHaveRun) "PARTIAL" else result.status
                            val record = TaskRunRecord(task.id, runId, scheduledFor, startedAt, finishedAt, status,
                                result.deliveryStatus, null,
                                notified = result.notified, toolsCalled = result.toolsCalled,
                                model = result.model, usageTokens = result.usageTokens,
                                causeCode = result.causeCode ?: TaskRunCause.fromLegacy(
                                    result.errorCause ?: result.skipReason ?: result.reason),
                                causeDetail = result.causeDetail ?: TaskRunCause.legacyDetail(
                                    result.errorCause ?: result.skipReason ?: result.reason,
                                    result.causeCode ?: TaskRunCause.fromLegacy(result.errorCause ?: result.skipReason ?: result.reason)),
                                deliveredResult = result.deliveredResult,
                                conversationMessageId = result.conversationMessageId)
                            if (status in RETRYABLE_STATES && !writeMayHaveRun) TickOccurrence.Retry(record)
                            else {
                                val saved = if (existing == null) {
                                    if (runs.appendIfAbsent(record)) record else runs.find(task.id, scheduledFor) ?: record
                                } else {
                                    runs.saveSnapshot(record)
                                    record
                                }
                                TickOccurrence.Completed(saved)
                            }
                        }
                    }
                }
                if (outcome == null) {
                    deferred = true
                    break
                }
                when (outcome) {
                    is TickOccurrence.Existing -> {
                        if (outcome.record.status == "STARTED") {
                            val next = calculator.nextRunAfter(task, now)
                            val updated = task.copy(state = TaskState.NeedsAttention(
                                TaskRunCause.PREVIOUS_OCCURRENCE_INTERRUPTED.code),
                                nextRunAt = next, lastRun = outcome.record.toLastRun())
                            updateAfterRun(task, updated)
                        } else updateAfterRun(task, stateAfter(task, outcome.record, now))
                    }
                    is TickOccurrence.Retry -> {
                        retryWork = true
                        updateAfterRun(task, task.copy(state = TaskState.Active, nextRunAt = scheduledFor,
                            lastRun = outcome.record.toLastRun()))
                    }
                    is TickOccurrence.Completed -> {
                        if (outcome.record.status == "SKIPPED") skipped++
                        val latest = tasks.get(task.id)?.takeIf { it.revision == task.revision } ?: task
                        val next = stateAfter(latest, outcome.record, outcome.record.finishedAt ?: now)
                        val autoDeleteAfterRun = task.deleteAfterRun && outcome.record.status in SUCCESS_STATES
                        val pending = pendingActions?.stateForTask(latest.id, latest.revision, latest.state) ==
                            TaskState.AwaitingUser
                        if (autoDeleteAfterRun && !pending) {
                            runCatching { tasks.delete(task.id, latest.revision, clock.millis(), expectedTask = latest) }
                        } else updateAfterRun(latest, next)
                    }
                }
            }
        } finally {
            if (!retryWork || deferred || token.isCancelled) rearm()
        }
        return TaskTickResult(started, skipped, deferred, retryWork)
    }

    private fun stateAfter(task: ScheduledTask, record: TaskRunRecord, now: Long): ScheduledTask {
        val next = calculator.nextRunAfter(task, now)
        val expired = task.validUntil != null && now >= task.validUntil
        val oneShot = task.schedule is TaskSchedule.At
        val state = when {
            record.status == "ERROR" -> TaskState.NeedsAttention((record.causeCode ?: TaskRunCause.EXECUTION_FAILED).code)
            record.status == "PARTIAL" -> TaskState.NeedsAttention((record.causeCode ?: TaskRunCause.EFFECT_OCCURRED).code)
            expired || oneShot || task.deleteAfterRun -> TaskState.Done
            else -> TaskState.Active
        }
        val lastRun = if (record.status == "SKIPPED" && task.lastRun?.status == "ERROR") task.lastRun
            else record.toLastRun()
        val incident = if (record.status == "ERROR" || record.status == "PARTIAL")
            record.causeCode ?: task.openIncidentCause else task.openIncidentCause
        return task.copy(state = state, nextRunAt = if (state == TaskState.Active) next else null,
            lastRun = lastRun, openIncidentCause = incident)
    }

    private fun updateTask(original: ScheduledTask, updated: ScheduledTask) {
        try { tasks.update(updated.copy(updatedAt = maxOf(updated.updatedAt, clock.millis())), original.revision) }
        catch (_: StaleTaskRevisionException) { /* A concurrent user change wins; do not overwrite it. */ }
    }

    private fun updateAfterRun(original: ScheduledTask, updated: ScheduledTask) {
        val pending = pendingActions?.stateForTask(original.id, original.revision, original.state) ==
            TaskState.AwaitingUser
        if (pending) {
            try {
                tasks.updateRuntimeSnapshot(updated.copy(updatedAt = maxOf(updated.updatedAt, clock.millis()),
                    revision = original.revision), original.revision, original)
            } catch (_: StaleTaskRevisionException) { /* A concurrent user change wins; do not overwrite it. */ }
        } else updateTask(original, updated)
    }

    private fun TaskRunRecord.toLastRun() = TaskLastRun(runId, scheduledFor, startedAt, finishedAt, status,
        deliveryStatus, causeCode)

    private sealed interface TickOccurrence {
        val record: TaskRunRecord
        data class Existing(override val record: TaskRunRecord) : TickOccurrence
        data class Retry(override val record: TaskRunRecord) : TickOccurrence
        data class Completed(override val record: TaskRunRecord) : TickOccurrence
    }

    companion object {
        private val RETRYABLE_STATES = setOf("RETRYABLE", "INTERRUPTED")
        private val SUCCESS_STATES = setOf("OK", "SILENT", "NO_RESULT")
    }
}
