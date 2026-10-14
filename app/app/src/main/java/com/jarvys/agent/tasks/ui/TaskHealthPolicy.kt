package com.jarvys.agent.tasks.ui

import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.TaskRunCause
import com.jarvys.agent.tasks.TaskState

enum class TaskHealthKind { NOTIFICATIONS, BATTERY, DELAYED, ATTENTION }

data class TaskHealthIssue(
    val kind: TaskHealthKind,
    val taskId: String? = null,
    val taskName: String? = null,
    val causeCode: TaskRunCause? = null,
    val scheduledFor: Long? = null,
)

data class TaskHealthInput(
    val notificationsAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val delayedTasks: List<ScheduledTask> = emptyList(),
    val attentionTasks: List<ScheduledTask> = emptyList(),
)

/** Pure projection for the health card; delay has no invented grace interval. */
object TaskHealthPolicy {
    fun evaluate(input: TaskHealthInput): List<TaskHealthIssue> = buildList {
        if (!input.notificationsAllowed) add(TaskHealthIssue(TaskHealthKind.NOTIFICATIONS))
        if (!input.batteryOptimizationIgnored) add(TaskHealthIssue(TaskHealthKind.BATTERY))
        input.delayedTasks.forEach { task ->
            add(TaskHealthIssue(TaskHealthKind.DELAYED, task.id, task.name, scheduledFor = task.nextRunAt))
        }
        input.attentionTasks.forEach { task ->
            val storedCause = (task.state as? TaskState.NeedsAttention)?.reason
            add(TaskHealthIssue(TaskHealthKind.ATTENTION, task.id, task.name,
                TaskRunCause.fromCode(storedCause) ?: TaskRunCause.fromLegacy(storedCause)))
        }
    }
}

object TaskUiProjection {
    fun isDelayed(task: ScheduledTask, nowMillis: Long, runInProgress: Boolean): Boolean =
        task.state == TaskState.Active && task.nextRunAt?.let { it < nowMillis } == true && !runInProgress
}
