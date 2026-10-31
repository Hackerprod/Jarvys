package com.jarvys.agent

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit

/** Drains canceled/active test work before closing WorkManager's in-memory Room database. */
internal object WorkManagerTestCleanup {
    fun close(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        val active = WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (manager.getWorkInfos(active).get(5, TimeUnit.SECONDS).isNotEmpty()) {
            check(System.nanoTime() < deadline) { "WorkManager test work did not drain before the in-memory database closed" }
            Thread.sleep(10)
        }
        // Synchronous test executors can finish the WorkSpec before the reschedule callback
        // posted by cancellation has unwound. Let that callback leave Room before closing it.
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        Thread.sleep(50)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
}
