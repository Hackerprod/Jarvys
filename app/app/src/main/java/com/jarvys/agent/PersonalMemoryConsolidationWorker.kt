package com.jarvys.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.MaintenanceMemoryBinding
import com.jarvys.agent.chatdb.MaintenanceClaimStatus
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
    internal var modelFactory: () -> CoreAgentModel = { CoreAgentModel(applicationContext,"personal-memory-daily-consolidation") }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (inputData.getString(FreshStorageBootstrap.GENERATION_EXTRA) != StorageGeneration.NAME)
            return@withContext Result.success()
        try {
            FreshStorageBootstrap.awaitReady(applicationContext)
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
        var binding:MaintenanceMemoryBinding?=null
        val ticket=MemoryWritePolicy.forContext(applicationContext).captureTicket()
        val token=CancellationToken.cancellable()
        val coroutineJob=currentCoroutineContext().job
        try {
            jobId = bridge.personalAgentEnqueueJob(null, TYPE_DAILY_CONSOLIDATE)
            val now=bridge.maintenanceNow()
            val claim=bridge.claimMaintenanceJob(ticket,token,jobId,owner,now,now+TimeUnit.MINUTES.toMillis(9),java.util.function.BooleanSupplier { coroutineJob.isActive })
            if(claim.status==MaintenanceClaimStatus.SESSION_DELETED) { finished=true;return@withContext Result.failure() }
            if(claim.status!=MaintenanceClaimStatus.CLAIMED) return@withContext Result.success()
            val job=requireNotNull(claim.job)
            binding=requireNotNull(claim.binding)
            leased=true
            val cancellationRegistration=coroutineJob.invokeOnCompletion { token.cancel() }
            try {
                binding.checkCancellation(token)
                consolidatePersisted(bridge, token,modelFactory(),ticket,binding)
                token.throwIfCancelled()
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

    /** One worker phase: authorization is captured before reading its persisted inputs. */
    internal fun consolidatePersisted(bridge: ChatDatabaseBridge, token: CancellationToken,
                                     model: CoreAgentModel? = null,originalTicket:MemoryWriteGate.Ticket?=null,maintenance:MaintenanceMemoryBinding?=null) {
        token.throwIfCancelled()
        val memoryTicket = originalTicket ?: MemoryWritePolicy.forContext(applicationContext).captureTicket()
        val candidates = bridge.personalAgentDailyConsolidationCandidates()
        if (candidates.isNotEmpty()) runConsolidation(bridge, candidates, token, memoryTicket,
            model ?: CoreAgentModel(applicationContext, "personal-memory-daily-consolidation"),maintenance)
    }

    private fun runConsolidation(
        bridge: ChatDatabaseBridge,
        candidates: List<PersonalMemoryCandidate>,
        token: CancellationToken,
        memoryTicket: MemoryWriteGate.Ticket,
        model: CoreAgentModel,maintenance:MaintenanceMemoryBinding?,
    ) {
        val context = bridge.personalAgentPromptContext("", false, "principal")
        val sourceIds = candidates.map { it.id }
        val records = candidates.joinToString("\n") { candidate ->
            "<note id=\"${xml(candidate.id)}\" importance=\"${candidate.importance}\">${xml(PersonalMemoryFlush.redactSecrets(candidate.text))}</note>"
        }
        val prompt = buildString {
            append(context.rules).append("\n\n")
            append(context.consolidationPrompt)
        }
        val tools = CoreToolRegistry(if(maintenance==null) PersonalMemoryTools.createForConsolidation(applicationContext, sourceIds, memoryTicket) else PersonalMemoryTools.createForConsolidation(applicationContext,sourceIds,memoryTicket,maintenance))
        val sessionId = "personal-memory-daily-consolidation"
        val loop = CoreAgentLoop(model, tools, prompt, sessionId)
        val notes = ConversationTurn("user", "<persisted_daily_notes>\n$records\n</persisted_daily_notes>")
        maintenance?.checkCancellation(token)
        val result = loop.run("", listOf(notes), token, null)
        maintenance?.checkCancellation(token)
        if (result.outcome != "COMPLETED") throw IllegalStateException("memory_consolidation_incomplete")
    }

    private fun retryOwnedJob(bridge: ChatDatabaseBridge, id: String?, owner: String,
                              leased: Boolean, reason: String,ticket:MemoryWriteGate.Ticket,binding:MaintenanceMemoryBinding?) {
        if (!leased || id == null) return
        runCatching { if(binding!=null) bridge.retryMaintenanceJob(ticket,binding,reason,bridge.maintenanceNow()) else false }
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
                .setInputData(androidx.work.Data.Builder().putString(FreshStorageBootstrap.GENERATION_EXTRA, StorageGeneration.NAME).build())
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
