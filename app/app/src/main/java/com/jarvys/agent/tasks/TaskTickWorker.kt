package com.jarvys.agent.tasks

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvys.agent.CancellationToken
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Runs the bounded scheduled-task executor from WorkManager's worker context. */
class TaskTickWorker(
    appContext: Context,
    params: WorkerParameters,
    private val executor: TaskExecutor,
    private val tasks: TaskStore = TaskStore(appContext),
    private val runs: TaskRunLedger = TaskRunLedger(appContext),
    private val clock: Clock = Clock.systemUTC(),
    private val calculator: ScheduleCalculator = ScheduleCalculator(clock),
    private val rearm: () -> Unit = { TaskScheduler.rearm(appContext) },
    private val isGloballyPaused: () -> Boolean = { TaskGlobalPauseStore(appContext).isPaused() },
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, ScheduledTaskExecutor(appContext))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + MAX_TICK_PROCESSING_MILLIS
        try {
            val result = withTimeout(MAX_TICK_PROCESSING_MILLIS) {
                val token = CancellationToken.cancellable()
                val cancellationRegistration = currentCoroutineContext().job.invokeOnCompletion { token.cancel() }
                try {
                    TaskTickEngine(tasks, runs, calculator, executor, clock, rearm, {
                        !isStopped && SystemClock.elapsedRealtime() < deadline
                    }, token, isGloballyPaused).tick()
                } finally { cancellationRegistration.dispose() }
            }
            if (result.retryWork) Result.retry() else Result.success()
        } catch (_: TimeoutCancellationException) {
            runCatching(rearm)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            runCatching(rearm)
            Result.retry()
        }
    }

    companion object {
        // Leave headroom under WorkManager's hard 10-minute Worker limit to persist/re-arm cleanly.
        const val MAX_TICK_PROCESSING_MILLIS = 9L * 60L * 1000L
    }
}
