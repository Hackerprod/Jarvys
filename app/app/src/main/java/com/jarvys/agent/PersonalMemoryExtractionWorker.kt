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
import com.jarvys.agent.chatdb.MaintenanceMemoryBinding
import com.jarvys.agent.chatdb.MaintenanceClaimStatus
import com.jarvys.agent.workspaces.WorkspaceIdentity
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
    internal var modelFactory: () -> CoreAgentModel = { CoreAgentModel(applicationContext, "${inputData.getString(KEY_CONVERSATION_ID)}-memory-maintenance") }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (inputData.getString(FreshStorageBootstrap.GENERATION_EXTRA) != StorageGeneration.NAME)
            return@withContext Result.success()
        val conversationId = inputData.getString(KEY_CONVERSATION_ID)?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure()
        val type = inputData.getString(KEY_TYPE) ?: TYPE_EXTRACT
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!WorkspaceIdentity.isCentral(conversationId)
            || type !in setOf(TYPE_EXTRACT, TYPE_SNAPSHOT, TYPE_NARRATE)) return@withContext Result.success()
        try {
            FreshStorageBootstrap.awaitReady(applicationContext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AgentErrorReporter.reportBackgroundFailure(applicationContext, failure)
            return@withContext Result.retry()
        }
        if (!MemoryWritePolicy.forContext(applicationContext).isEnabled()
            || !manual && !MemoryReflectionPreferences(applicationContext).enabled()) {
            return@withContext Result.success()
        }

        val bridge = ChatDatabaseBridge.getInstance(applicationContext)
        var jobId: String? = null
        val owner = UUID.randomUUID().toString()
        var leased = false
        var finished = false
        var binding:MaintenanceMemoryBinding?=null
        val ticket=MemoryWritePolicy.forContext(applicationContext).captureTicket()
        val token=CancellationToken.cancellable()
        val coroutineJob=currentCoroutineContext().job
        try {
            jobId = bridge.personalAgentEnqueueJob(conversationId, type)
            val now = bridge.maintenanceNow()
            val claim=bridge.claimMaintenanceJob(ticket,token,jobId,owner,now,now+TimeUnit.MINUTES.toMillis(9),java.util.function.BooleanSupplier { coroutineJob.isActive })
            if(claim.status==MaintenanceClaimStatus.SESSION_DELETED) { finished=true;return@withContext Result.failure() }
            if(claim.status!=MaintenanceClaimStatus.CLAIMED) return@withContext Result.success()
            val job=requireNotNull(claim.job)
            binding=requireNotNull(claim.binding)
            leased=true
            val cancellationRegistration=coroutineJob.invokeOnCompletion { token.cancel() }
            try {
                binding.checkCancellation(token)
                val model = modelFactory()
                val completed = if (job.type == TYPE_NARRATE) {
                    EpisodeNarrator(applicationContext, conversationId, model).narratePending(token,null,ticket,binding)
                } else {
                    extractIncrementally(conversationId, bridge, model, token, job.type == TYPE_SNAPSHOT,ticket,binding)
                }
                binding.checkCancellation(token)
                token.throwIfCancelled()
                if (!completed) throw IllegalStateException("memory_maintenance_failed")
                binding.checkCancellation(token)
                if (!bridge.completeMaintenanceJob(ticket,token,binding)) throw IllegalStateException("memory_job_lease_lost")
                finished = true
                Result.success()
            } finally {
                cancellationRegistration.dispose()
            }
        } catch (cancelled: CancellationException) {
            retryOwnedJob(bridge, jobId, owner, leased, "cancelled",ticket,binding)
            finished = true
            throw cancelled
        } catch (deleted: com.jarvys.agent.chatdb.MaintenanceSessionDeletedException) {
            val current=requireNotNull(binding)
            bridge.abortDeletedMaintenanceJob(current)
            finished=true
            Result.failure()
        } catch (failure: Exception) {
            if(!coroutineJob.isActive) {
                token.cancel();retryOwnedJob(bridge,jobId,owner,leased,"cancelled",ticket,binding);finished=true
                throw CancellationException("Memory maintenance cancelled",failure)
            }
            AgentErrorReporter.reportBackgroundFailure(applicationContext, failure)
            retryOwnedJob(bridge, jobId, owner, leased, failure.javaClass.simpleName,ticket,binding)
            finished = true
            Result.retry()
        } finally {
            if (leased && !finished) retryOwnedJob(bridge, jobId, owner, true, "interrupted",ticket,binding)
        }
    }

    internal fun extractIncrementally(conversationId: String, bridge: ChatDatabaseBridge,
                                     model: CoreAgentModel, token: CancellationToken, snapshot: Boolean, originalTicket:MemoryWriteGate.Ticket?=null, maintenance:MaintenanceMemoryBinding?=null): Boolean {
        if (!WorkspaceIdentity.isCentral(conversationId)) return false
        val policy = MemoryWritePolicy.forContext(applicationContext)
        fun memoryEnabled() = policy.isEnabled()
        if (!memoryEnabled()) return false
        val ticket = originalTicket ?: policy.captureTicket()
        // Narration is driven by durable evidence, not this cursor. Advancing it there would skip user facts.
        val cursor = bridge.personalAgentExtractionCursorSync(conversationId)
        val messages = bridge.readInputsAfterSync(conversationId, cursor)
        val throughSeq = messages.maxOfOrNull { it.seq } ?: cursor
        val transcript = messages.filter { it.role == "user" }.map {
            ConversationTurn("user", it.text).withDurableMessageId(it.messageId)
        }
        val completed = transcript.isEmpty() || PersonalMemoryFlush(applicationContext, conversationId, "principal", model, ticket,maintenance)
            .runOffline(transcript, token, snapshot)
        token.throwIfCancelled()
        if (!completed || !memoryEnabled()) return false
        if (throughSeq > cursor) { if(maintenance==null) bridge.personalAgentAdvanceExtractionCursorAutomatic(ticket, token, conversationId, throughSeq) else bridge.advanceMaintenanceCursor(ticket,token,maintenance,conversationId,throughSeq) }
        return true
    }

    private fun retryOwnedJob(bridge: ChatDatabaseBridge, id: String?, owner: String,
                              leased: Boolean, reason: String,ticket:MemoryWriteGate.Ticket,binding:MaintenanceMemoryBinding?) {
        if (!leased || id == null) return
        runCatching { if(binding!=null) bridge.retryMaintenanceJob(ticket,binding,reason,bridge.maintenanceNow()) else false }
            .onFailure { AgentErrorReporter.reportBackgroundFailure(applicationContext, it) }
    }

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_TYPE = "maintenance_type"
        private const val KEY_MANUAL = "manual_request"
        const val TYPE_EXTRACT = "extract"
        const val TYPE_SNAPSHOT = "snapshot"
        const val TYPE_NARRATE = "narrate"

        @JvmStatic
        fun enqueueNarration(context: Context, conversationId: String) {
            if (!WorkspaceIdentity.isCentral(conversationId)) return
            val input = Data.Builder()
                .putString(FreshStorageBootstrap.GENERATION_EXTRA, StorageGeneration.NAME)
                .putString(KEY_CONVERSATION_ID, conversationId)
                .putString(KEY_TYPE, TYPE_NARRATE)
                .build()
            val request = OneTimeWorkRequest.Builder(PersonalMemoryExtractionWorker::class.java)
                .setInputData(input)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,
                    androidx.work.WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(TAG_ALL)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName(conversationId, TYPE_NARRATE), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        @JvmStatic
        @JvmOverloads
        fun enqueue(context: Context, conversationId: String, snapshot: Boolean = false, manual: Boolean = false) {
            if (!WorkspaceIdentity.isCentral(conversationId)) return
            val type = if (snapshot) TYPE_SNAPSHOT else TYPE_EXTRACT
            val input = Data.Builder()
                .putString(FreshStorageBootstrap.GENERATION_EXTRA, StorageGeneration.NAME)
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
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueName(conversationId, TYPE_NARRATE))
        }

        private const val TAG_ALL = "jarvys_personal_memory_maintenance"
    }
}
