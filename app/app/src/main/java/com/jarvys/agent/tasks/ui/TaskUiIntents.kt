package com.jarvys.agent.tasks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.jarvys.agent.proactive.ProactiveWorkNames
import com.jarvys.agent.tasks.TaskManualRunScheduler
import com.jarvys.agent.tasks.TaskScheduler

object TaskUiIntents {
    fun notificationSettings(context: Context): Intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

    fun batteryOptimizationSettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

/** Background work is discovered through WorkManager rather than a screen timer. */
object TaskBackgroundStatus {
    fun isBusy(context: Context, taskId: String): Boolean =
        TaskManualRunScheduler.isActive(context, taskId) || TaskScheduler.isTickRunning(context) ||
                isRunning(context, ProactiveWorkNames.PERIODIC) || isRunning(context, ProactiveWorkNames.RUN_NOW)

    private fun isRunning(context: Context, uniqueName: String): Boolean = runCatching {
        WorkManager.getInstance(context.applicationContext).getWorkInfosForUniqueWork(uniqueName).get()
            .any { it.state == WorkInfo.State.RUNNING }
    }.getOrDefault(false)
}
