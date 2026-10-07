package com.jarvys.agent.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.CancellationToken
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters,
    private val processor: ProactiveBatchProcessor,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) :
        this(appContext, params, ProactiveBatchProcessorFactory.create(appContext))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val preferences = ProactivePreferences(app)
        if (!preferences.enabled) return@withContext Result.success()

        var agentRunActive = false
        var proactiveRunActive = false
        var token: CancellationToken? = null
        preferences.captureIfEnabled {
            val snapshot = AgentRunUiState.state.value
            if (snapshot.running || snapshot.compacting) {
                agentRunActive = true
            } else {
                token = ProactiveRunController.tryStart()
                if (token == null) proactiveRunActive = true
            }
        }
        if (agentRunActive) {
            ProactiveReviewStateStore(app).recordFailure("run_active")
            return@withContext Result.retry()
        }
        if (proactiveRunActive) {
            ProactiveReviewStateStore(app).recordFailure("proactive_run_active")
            return@withContext Result.retry()
        }
        val runToken = token ?: return@withContext Result.success()
        val cancellationRegistration = currentCoroutineContext().job.invokeOnCompletion { runToken.cancel() }
        try {
            runToken.throwIfCancelled()
            val batches = ProactiveCandidateQueue(ProactiveEventStore(app)).nextBatches()
            when (val result = processor.review(batches, runToken)) {
                ProactiveProcessingResult.Completed -> Result.success()
                is ProactiveProcessingResult.Retryable -> Result.retry()
                is ProactiveProcessingResult.Failed -> Result.failure()
                ProactiveProcessingResult.Stopped -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            if (runToken.isCancelled) Result.success() else throw cancelled
        } finally {
            cancellationRegistration.dispose()
            ProactiveRunController.finish(runToken)
            if (!BackgroundRunController.isInteractiveActive()) {
                runCatching { com.jarvys.agent.tasks.TaskScheduler.rearm(app) }
            }
        }
    }
}
