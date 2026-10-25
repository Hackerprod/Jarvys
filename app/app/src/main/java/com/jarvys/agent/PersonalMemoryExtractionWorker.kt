package com.jarvys.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.PersonalMemoryJob
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Durable extraction/snapshot execution. The database lease is the cross-process authority. */
class PersonalMemoryExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
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
        val conversationId = inputData.getString(KEY_CONVERSATION_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val type = inputData.getString(KEY_TYPE) ?: TYPE_EXTRACT
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (type !in setOf(TYPE_EXTRACT, TYPE_SNAPSHOT) || !MessageReactionTool.isOrdinaryChat(conversationId)) {
            return@withContext Result.success()
        }
        val preferences = applicationContext.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(MemoryConstants.ENABLED_KEY, true)
            || !manual && !MemoryReflectionPreferences(applicationContext).enabled()) {
            return@withContext Result.success()
        }

        val runStore = LocalRunStore(applicationContext)
        val bridge = ChatDatabaseBridge.getInstance(applicationContext)
        var jobId: String? = null
        val owner = UUID.randomUUID().toString()
        var leased = false
        var finished = false
        try {
            jobId = bridge.personalAgentEnqueueJob(conversationId, type)
            val now = System.currentTimeMillis()
            // WorkManager's worker execution window is shorter than this lease; expired work can be reclaimed.
            val job: PersonalMemoryJob = bridge.personalAgentClaimJob(
                jobId,
                owner,
                now,
                now + TimeUnit.MINUTES.toMillis(9),
            ) ?: return@withContext Result.success()
            leased = true
            val token = CancellationToken.cancellable()
            val cancellationRegistration = currentCoroutineContext().job.invokeOnCompletion { token.cancel() }
            try {
                val transcript = runStore.loadConversationContext(conversationId)
                val model = CoreAgentModel(applicationContext, "$conversationId-memory-maintenance")
                val completed = PersonalMemoryFlush(
                    applicationContext,
                    conversationId,
                    "principal",
                    model,
                ).runOffline(transcript, token, job.type == TYPE_SNAPSHOT)
                token.throwIfCancelled()
                if (!completed) throw IllegalStateException("memory_maintenance_failed")
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

    private fun retryOwnedJob(bridge: ChatDatabaseBridge, id: String?, owner: String,
                              leased: Boolean, reason: String) {
        if (!leased || id == null) return
        runCatching { bridge.personalAgentRetryJob(id, owner, reason, System.currentTimeMillis()) }
            .onFailure { AgentErrorReporter.reportBackgroundFailure(applicationContext, it) }
    }

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_TYPE = "maintenance_type"
        private const val KEY_MANUAL = "manual_request"
        const val TYPE_EXTRACT = "extract"
        const val TYPE_SNAPSHOT = "snapshot"

        @JvmStatic
        @JvmOverloads
        fun enqueue(context: Context, conversationId: String, snapshot: Boolean = false, manual: Boolean = false) {
            if (!MessageReactionTool.isOrdinaryChat(conversationId)) return
            val type = if (snapshot) TYPE_SNAPSHOT else TYPE_EXTRACT
            val input = Data.Builder()
                .putString(SingleSessionReset.GENERATION_EXTRA, StorageGeneration.NAME)
                .putString(KEY_CONVERSATION_ID, conversationId)
                .putString(KEY_TYPE, type)
                .putBoolean(KEY_MANUAL, manual)
                .build()
            val request = OneTimeWorkRequest.Builder(PersonalMemoryExtractionWorker::class.java)
                .setInputData(input)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, androidx.work.WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(TAG_ALL)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName(conversationId, type),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private fun uniqueName(conversationId: String, type: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(conversationId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "jarvys_personal_memory_${type}_$hash"
        }

        fun cancelAll(context: Context) = WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG_ALL)

        @JvmStatic
        fun cancelConversation(context: Context, conversationId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueName(conversationId, TYPE_EXTRACT))
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueName(conversationId, TYPE_SNAPSHOT))
        }

        private const val TAG_ALL = "jarvys_personal_memory_maintenance"
    }
}
