package com.jarvys.agent.tasks

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkInfo
import java.util.concurrent.TimeUnit

object TaskWorkNames {
    const val TICK = "jarvys_tasks_tick"
}

object TaskScheduler {
    fun rearm(context: Context) = rearm(context.applicationContext, TaskStore(context), System.currentTimeMillis(), ScheduleCalculator())

    fun isTickRunning(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWork(TaskWorkNames.TICK).get()
            .any { it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)

    internal fun rearm(context: Context, store: TaskStore, nowMillis: Long,
                       calculator: ScheduleCalculator = ScheduleCalculator()) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (TaskGlobalPauseStore(context).isPaused()) {
            manager.cancelUniqueWork(TaskWorkNames.TICK)
            return
        }
        val active = store.list().filter { it.state == TaskState.Active }
        active.forEach { task ->
            val rebasedNext = nextForRearm(task, nowMillis, calculator)
            if (rebasedNext != task.nextRunAt) {
                try { store.update(task.copy(nextRunAt = rebasedNext, updatedAt = nowMillis), task.revision) }
                catch (_: StaleTaskRevisionException) { /* A concurrent mutation will rearm the current snapshot. */ }
            }
        }
        val next = store.list().asSequence().filter { it.state == TaskState.Active }
            .flatMap { task -> sequenceOf(task.nextRunAt, task.validUntil).filterNotNull() }
            .minOrNull()
        if (next == null) {
            manager.cancelUniqueWork(TaskWorkNames.TICK)
            return
        }
        val request = OneTimeWorkRequest.Builder(TaskTickWorker::class.java)
            .setInitialDelay((next - nowMillis).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork(TaskWorkNames.TICK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Global resume skips everything missed while paused and schedules only future occurrences. */
    fun setAllPaused(context: Context, paused: Boolean, nowMillis: Long = System.currentTimeMillis(),
                     store: TaskStore = TaskStore(context.applicationContext),
                     calculator: ScheduleCalculator = ScheduleCalculator()): Boolean {
        val app = context.applicationContext
        val controls = TaskGlobalPauseStore(app)
        if (paused) {
            if (!controls.setPaused(true, nowMillis)) return false
            WorkManager.getInstance(app).cancelUniqueWork(TaskWorkNames.TICK)
            return true
        }
        if (!controls.isPaused()) return false
        store.list().filter { it.state == TaskState.Active }.forEach { snapshot ->
            while (true) {
                val task = store.get(snapshot.id) ?: break
                if (task.state != TaskState.Active) break
                val expired = task.validUntil != null && nowMillis >= task.validUntil
                val next = if (expired) null else calculator.nextRunAfter(task, nowMillis)
                val oneShotMissed = task.schedule is TaskSchedule.At && next == null
                val updated = task.copy(
                    state = if (expired || oneShotMissed) TaskState.Done else TaskState.Active,
                    nextRunAt = if (expired || oneShotMissed) null else next,
                    updatedAt = maxOf(task.updatedAt, nowMillis),
                )
                try {
                    store.update(updated, task.revision)
                    break
                } catch (_: StaleTaskRevisionException) {
                    // Re-read concurrent chat edits and rebase their current schedule from resume time.
                }
            }
        }
        // Keep the gate closed until every missed occurrence has been rebased.
        controls.setPaused(false, nowMillis)
        rearm(app, store, nowMillis, calculator)
        return true
    }

    private fun nextForRearm(task: ScheduledTask, now: Long, calculator: ScheduleCalculator): Long? {
        if (task.validUntil != null && now >= task.validUntil) return task.validUntil
        val dueWasQueued = task.nextRunAt != null && task.nextRunAt <= now
        if (task.schedule is TaskSchedule.At) {
            return calculator.latestDueAtOrBefore(task, now) ?: calculator.nextRunAfter(task, now)
        } else if (dueWasQueued) {
            if (task.catchUp == TaskCatchUp.RUN_LATE_ONCE) {
                return calculator.latestDueAtOrBefore(task, now) ?: calculator.nextRunAfter(task, now)
            }
            return task.nextRunAt
        }
        // Re-resolve FOLLOW_DEVICE calendar rules after startup/time/zone changes.
        return calculator.nextRunAfter(task, now)
    }
}
