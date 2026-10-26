package com.jarvys.agent.connectors

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Repairs exact local state/history only. This worker has no connector runtime or permission API. */
class ConnectionRepairWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val access = ConnectionAccess.find(applicationContext) ?: return Result.failure()
        return try {
            // The process-owned future is not cancelled when WorkManager replaces this observer.
            when (withContext(Dispatchers.IO) { access.repairOnce().get() }) {
                ConnectionAccess.RepairOutcome.SUCCESS -> Result.success()
                ConnectionAccess.RepairOutcome.RETRY -> Result.retry()
                ConnectionAccess.RepairOutcome.BLOCKED -> Result.failure()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
    companion object {
        const val UNIQUE_WORK = "jarvys_connector_state_repair"
        internal fun request() = OneTimeWorkRequest.Builder(ConnectionRepairWorker::class.java)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        @JvmStatic fun enqueue(context: Context): androidx.work.Operation {
            val request = request()
            // New state/reopen replaces the observer; a failed attempt without new state keeps backoff.
            return WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
