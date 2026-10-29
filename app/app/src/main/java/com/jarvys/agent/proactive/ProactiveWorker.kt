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
    private val processor: ProactiveBatchProcessor?,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) :
        this(appContext, params, null)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        try {
            com.jarvys.agent.FreshStorageBootstrap.awaitReady(app)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.jarvys.agent.AgentErrorReporter.reportBackgroundFailure(app, failure)
            return@withContext Result.retry()
        }
        val preferences = ProactivePreferences(app)
        if (!preferences.enabled) return@withContext Result.success()

        var agentRunActive = false
        var proactiveRunActive = false
        var token: CancellationToken? = null
        preferences.captureIfEnabled {
            if (hasInteractiveRun()) {
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
            if (hasInteractiveRun()) {
                runToken.cancel()
                ProactiveReviewStateStore(app).recordFailure("run_active")
                return@withContext Result.retry()
            }
            runToken.throwIfCancelled()
            val currentProcessor = processor ?: ProactiveBatchProcessorFactory.create(app)
            when (val result = currentProcessor.review(batches, runToken)) {
                ProactiveProcessingResult.Completed -> Result.success()
                is ProactiveProcessingResult.Retryable -> Result.retry()
                is ProactiveProcessingResult.Failed -> Result.failure()
                ProactiveProcessingResult.Stopped -> if (hasInteractiveRun()) {
                    ProactiveReviewStateStore(app).recordFailure("run_active")
                    Result.retry()
                } else Result.success()
            }
        } catch (cancelled: CancellationException) {
            if (hasInteractiveRun()) {
                ProactiveReviewStateStore(app).recordFailure("run_active")
                Result.retry()
            } else if (runToken.isCancelled) Result.success() else throw cancelled
        } finally {
            cancellationRegistration.dispose()
            ProactiveRunController.finish(runToken)
            if (!BackgroundRunController.isInteractiveActive()) {
                runCatching { com.jarvys.agent.tasks.TaskScheduler.rearm(app) }
            }
        }
    }

    private fun hasInteractiveRun(): Boolean {
        val snapshot = AgentRunUiState.state.value
        return snapshot.running || snapshot.compacting || BackgroundRunController.isInteractiveActive()
    }
}
