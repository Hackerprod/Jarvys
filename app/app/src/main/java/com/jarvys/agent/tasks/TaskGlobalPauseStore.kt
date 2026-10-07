package com.jarvys.agent.tasks

import android.content.Context

/** Durable kill-switch for scheduled ticks; explicit manual runs use a separate work name and bypass it. */
class TaskGlobalPauseStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isPaused(): Boolean = preferences.getBoolean(KEY_PAUSED, false)
    fun resumedAt(): Long? = preferences.getLong(KEY_RESUMED_AT, 0L).takeIf { it > 0L }

    /** Returns whether the stored value changed. Re-enable time is recorded only on paused -> active. */
    fun setPaused(paused: Boolean, nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(LOCK) {
        val previous = isPaused()
        if (previous == paused) return@synchronized false
        val editor = preferences.edit().putBoolean(KEY_PAUSED, paused)
        if (paused) editor.remove(KEY_RESUMED_AT) else editor.putLong(KEY_RESUMED_AT, nowMillis)
        check(editor.commit()) { "Could not persist scheduled-task global pause" }
        TaskDataChanges.invalidate()
        true
    }

    companion object {
        const val PREFERENCES = "jarvys_scheduled_task_controls"
        const val KEY_PAUSED = "all_tasks_paused"
        const val KEY_RESUMED_AT = "tasks_resumed_at"
        private val LOCK = Any()
    }
}
