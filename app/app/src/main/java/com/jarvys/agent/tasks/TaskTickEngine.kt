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
                                    deliveryStatus = "UNKNOWN", reason = "recovered_interrupted_run")
                                runs.saveSnapshot(interrupted)
                                TickOccurrence.Existing(interrupted)
                            } else TickOccurrence.Existing(existing)
                        } else {
                            val runId = UUID.randomUUID().toString()
                            started++
                            val result = if (skip) TaskExecutionResult("SKIPPED", "NOT_APPLICABLE", "missed_occurrence")
                                else try { executor.execute(task, scheduledFor, startedAt, runId, token) }
                                catch (_: java.util.concurrent.CancellationException) {
                                    TaskExecutionResult("INTERRUPTED", "NOT_APPLICABLE", "interrupted")
                                }
                                catch (failure: Exception) { TaskExecutionResult("ERROR", "NOT_APPLICABLE",
                                    failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName) }
                            val finishedAt = clock.millis()
                            val writeMayHaveRun = result.toolsCalled.any { it.write }
                            val status = if (result.status in RETRYABLE_STATES && writeMayHaveRun) "PARTIAL" else result.status
                            val record = TaskRunRecord(task.id, runId, scheduledFor, startedAt, finishedAt, status,
                                result.deliveryStatus, result.reason,
                                skipReason = result.skipReason ?: result.reason.takeIf { status == "SKIPPED" },
                                notified = result.notified, toolsCalled = result.toolsCalled,
                                errorCause = result.errorCause ?: result.reason, model = result.model,
                                usageTokens = result.usageTokens)
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
                            updateTask(task, task.copy(state = TaskState.NeedsAttention("Previous occurrence was interrupted"),
                                nextRunAt = next, lastRun = outcome.record.toLastRun()))
                        } else updateTask(task, stateAfter(task, outcome.record, now))
                    }
                    is TickOccurrence.Retry -> {
                        retryWork = true
                        updateTask(task, task.copy(state = TaskState.Active, nextRunAt = scheduledFor,
                            lastRun = outcome.record.toLastRun()))
                    }
                    is TickOccurrence.Completed -> {
                        if (outcome.record.status == "SKIPPED") skipped++
                        updateTask(task, stateAfter(task, outcome.record, outcome.record.finishedAt ?: now))
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
            record.status == "ERROR" -> TaskState.NeedsAttention(record.reason ?: "Task execution failed")
            record.status == "PARTIAL" -> TaskState.NeedsAttention(record.reason ?: "Task run stopped after an effect")
            expired || oneShot || task.deleteAfterRun -> TaskState.Done
            else -> TaskState.Active
        }
        val lastRun = if (record.status == "SKIPPED" && task.lastRun?.status == "ERROR") task.lastRun
            else record.toLastRun()
        return task.copy(state = state, nextRunAt = if (state == TaskState.Active) next else null,
            lastRun = lastRun)
    }

    private fun updateTask(original: ScheduledTask, updated: ScheduledTask) {
        try { tasks.update(updated.copy(updatedAt = maxOf(updated.updatedAt, clock.millis())), original.revision) }
        catch (_: StaleTaskRevisionException) { /* A concurrent user change wins; do not overwrite it. */ }
    }

    private fun TaskRunRecord.toLastRun() = TaskLastRun(runId, scheduledFor, startedAt, finishedAt, status, deliveryStatus)

    private sealed interface TickOccurrence {
        val record: TaskRunRecord
        data class Existing(override val record: TaskRunRecord) : TickOccurrence
        data class Retry(override val record: TaskRunRecord) : TickOccurrence
        data class Completed(override val record: TaskRunRecord) : TickOccurrence
    }

    companion object {
        private val RETRYABLE_STATES = setOf("RETRYABLE", "INTERRUPTED")
    }
}
