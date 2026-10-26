package com.jarvys.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.PersonalMemoryCandidate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Daily consolidation reads only persisted, active user-scope daily notes; lease ownership stays in Room. */
class PersonalMemoryConsolidationWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (inputData.getString(SingleSessionReset.GENERATION_EXTRA) != StorageGeneration.NAME)
            return@withContext Result.success()
        try {
            SingleSessionReset.awaitReady(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AgentErrorReporter.reportBackgroundFailure(applicationContext, failure)
            return@withContext Result.retry()
        }
        if (!enabled(applicationContext)) return@withContext Result.success()
        val bridge = ChatDatabaseBridge.getInstance(applicationContext)
        val owner = UUID.randomUUID().toString()
        var jobId: String? = null
        var leased = false
        var finished = false
        try {
            jobId = bridge.personalAgentEnqueueJob(null, TYPE_DAILY_CONSOLIDATE)
            val now = System.currentTimeMillis()
            val job = bridge.personalAgentClaimJob(jobId, owner, now, now + TimeUnit.MINUTES.toMillis(9))
                ?: return@withContext Result.success()
            leased = true
            val token = CancellationToken.cancellable()
            val cancellationRegistration = currentCoroutineContext().job.invokeOnCompletion { token.cancel() }
            try {
                consolidatePersisted(bridge, token)
                token.throwIfCancelled()
                if (!bridge.personalAgentCompleteJob(job.id, owner)) throw IllegalStateException("memory_job_lease_lost")
                finished = true
                Result.success()
            } finally {
                cancellationRegistration.dispose()
            }
        } catch (cancelled: CancellationException) {
            retryOwnedJob(bridge, jobId, owner, leased, "cancelled")
            finished = true
            throw cancelled
        } catch (failure: Exception) {
            AgentErrorReporter.reportBackgroundFailure(applicationContext, failure)
            retryOwnedJob(bridge, jobId, owner, leased, failure.javaClass.simpleName)
            finished = true
            Result.retry()
        } finally {
            if (leased && !finished) retryOwnedJob(bridge, jobId, owner, true, "interrupted")
        }
    }

    /** One worker phase: authorization is captured before reading its persisted inputs. */
    internal fun consolidatePersisted(bridge: ChatDatabaseBridge, token: CancellationToken,
                                     model: CoreAgentModel? = null) {
        token.throwIfCancelled()
        val memoryTicket = MemoryWritePolicy.forContext(applicationContext).captureTicket()
        val candidates = bridge.personalAgentDailyConsolidationCandidates()
        if (candidates.isNotEmpty()) runConsolidation(bridge, candidates, token, memoryTicket,
            model ?: CoreAgentModel(applicationContext, "personal-memory-daily-consolidation"))
    }

    private fun runConsolidation(
        bridge: ChatDatabaseBridge,
        candidates: List<PersonalMemoryCandidate>,
        token: CancellationToken,
        memoryTicket: MemoryWriteGate.Ticket,
        model: CoreAgentModel,
    ) {
        val context = bridge.personalAgentPromptContext("", true, "principal")
        val sourceIds = candidates.map { it.id }
        val records = candidates.joinToString("\n") { candidate ->
            "<note id=\"${xml(candidate.id)}\" importance=\"${candidate.importance}\">${xml(PersonalMemoryFlush.redactSecrets(candidate.text))}</note>"
        }
        val prompt = buildString {
            append(context.rules).append("\n\n")
            append(context.consolidationPrompt)
        }
        val tools = CoreToolRegistry(PersonalMemoryTools.createForConsolidation(applicationContext, sourceIds, memoryTicket))
        val sessionId = "personal-memory-daily-consolidation"
        val loop = CoreAgentLoop(model, tools, prompt, sessionId)
        val notes = ConversationTurn("user", "<persisted_daily_notes>\n$records\n</persisted_daily_notes>")
        val result = loop.run("", listOf(notes), token, null)
        if (result.outcome != "COMPLETED") throw IllegalStateException("memory_consolidation_incomplete")
    }

    private fun retryOwnedJob(bridge: ChatDatabaseBridge, id: String?, owner: String,
                              leased: Boolean, reason: String) {
        if (!leased || id == null) return
        runCatching { bridge.personalAgentRetryJob(id, owner, reason, System.currentTimeMillis()) }
            .onFailure { AgentErrorReporter.reportBackgroundFailure(applicationContext, it) }
    }

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    companion object {
        const val PREFERENCES = "jarvys_personal_agent"
        const val DAILY_ENABLED_KEY = "daily_consolidation_enabled"
        private const val UNIQUE_WORK = "jarvys_personal_memory_daily_consolidation"
        private const val TYPE_DAILY_CONSOLIDATE = "daily_consolidate"

        @JvmStatic
        fun cancelSchedule(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        }

        @JvmStatic
        fun refreshSchedule(context: Context) {
            val app = context.applicationContext
            val work = WorkManager.getInstance(app)
            if (!enabled(app)) {
                work.cancelUniqueWork(UNIQUE_WORK)
                return
            }
            val request = PeriodicWorkRequest.Builder(PersonalMemoryConsolidationWorker::class.java, 1, TimeUnit.DAYS)
                .setInputData(androidx.work.Data.Builder().putString(SingleSessionReset.GENERATION_EXTRA, StorageGeneration.NAME).build())
                .addTag(UNIQUE_WORK)
                .build()
            work.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        @JvmStatic
        fun setDailyEnabled(context: Context, enabled: Boolean) {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(DAILY_ENABLED_KEY, enabled).apply()
            refreshSchedule(context)
        }

        private fun enabled(context: Context): Boolean =
            MemoryWritePolicy.forContext(context).isEnabled() &&
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getBoolean(DAILY_ENABLED_KEY, true)
    }
}
