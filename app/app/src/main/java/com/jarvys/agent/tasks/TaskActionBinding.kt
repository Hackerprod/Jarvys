package com.jarvys.agent.tasks

import com.jarvys.agent.NativeActionPolicy
import org.json.JSONArray
import org.json.JSONObject

/** Immutable semantic requests, not grants to the scheduler, providers or a task's tools. */
internal object TaskActionBinding {
    fun task(session: String, operation: String, task: ScheduledTask, effect: String = ""): String =
        NativeActionPolicy.binding(session, operation, payload(task), effect)

    fun update(session: String, before: ScheduledTask, after: ScheduledTask): String =
        NativeActionPolicy.binding(session, "update", payload(before), payload(after))

    fun history(runs: List<TaskRunRecord>): String = NativeActionPolicy.binding(
        *runs.sortedBy { it.idempotencyKey }.map { run -> JSONArray(listOf(
            run.taskId, run.runId, run.scheduledFor, run.startedAt, run.finishedAt ?: JSONObject.NULL,
            run.status, run.deliveryStatus, run.reason ?: JSONObject.NULL, run.skipReason ?: JSONObject.NULL,
            run.notified, JSONArray(run.toolsCalled.map { JSONArray(listOf(it.name, it.write, it.outcome)) }),
            run.errorCause ?: JSONObject.NULL, run.model ?: JSONObject.NULL, run.usageTokens ?: JSONObject.NULL,
            run.manualOccurrenceId ?: JSONObject.NULL,
        )).toString() }.toTypedArray())

    private fun payload(task: ScheduledTask): String = JSONArray(listOf(
        task.id, task.revision, task.name, task.instruction, schedule(task.schedule),
        when (val state = task.state) {
            TaskState.Active -> "ACTIVE"
            TaskState.Paused -> "PAUSED"
            TaskState.Done -> "DONE"
            TaskState.AwaitingUser -> "AWAITING_USER"
            is TaskState.NeedsAttention -> "NEEDS_ATTENTION:${state.reason}"
        }, task.timePrecision.name, task.catchUp.name, task.validUntil ?: JSONObject.NULL,
        task.deleteAfterRun, task.nextRunAt ?: JSONObject.NULL,
        task.lastRun?.let { JSONArray(listOf(it.runId, it.scheduledFor, it.startedAt,
            it.finishedAt ?: JSONObject.NULL, it.status, it.deliveryStatus)) } ?: JSONObject.NULL,
        JSONArray(listOf(task.toolScope.mode, JSONArray(task.toolScope.tools), task.toolScope.web, task.toolScope.writes)),
        task.delivery.name, task.createdBy, JSONArray(task.creatorToolNames), task.createdAt, task.updatedAt,
    )).toString()

    private fun schedule(value: TaskSchedule): JSONArray = when (value) {
        is TaskSchedule.At -> JSONArray(listOf("AT", value.localDateTime, zone(value.zone)))
        is TaskSchedule.Calendar -> JSONArray(listOf("CALENDAR", value.time, value.cadence.name,
            JSONArray(value.daysOfWeek.sorted()), value.dayOfMonth ?: JSONObject.NULL, zone(value.zone)))
        is TaskSchedule.Every -> JSONArray(listOf("EVERY", value.intervalMillis, value.anchorMillis))
    }
    private fun zone(value: TaskZone): String = when (value) {
        TaskZone.FollowDevice -> "FOLLOW_DEVICE"
        is TaskZone.Iana -> value.id
    }
}
