package com.jarvys.agent.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Manifest receiver is intentionally limited to rearming the single scheduler tick. */
class TaskRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        TaskScheduler.rearm(context.applicationContext)
    }
}
