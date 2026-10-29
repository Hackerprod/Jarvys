package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.providers.ChatCompletionsConfig
import com.jarvys.agent.providers.CustomEndpointCompatibilityRegistry
import com.jarvys.agent.providers.CustomEndpointUrlValidator
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Local preparation is not inference; both semantic dry-run and apply require explicit consent. */
data class MemorySemanticScanOptions(val scan:MemoryRelationScanOptions=MemoryRelationScanOptions(),val concurrency:Int=5,
    val timeoutSeconds:Int=60,val maxSemantic:Int=100)
data class MemorySemanticRoute(val provider:String,val model:String,val reasoningVariant:String?,val destination:String,
    val compatibility:String?,val authenticationMode:String?)
data class MemorySemanticPair(val index:Int,val sourceId:String,val sourceTitle:String,val sourceContent:String,
    val targetId:String,val targetTitle:String,val targetContent:String,val prompt:String,val blockedReason:String?)
internal data class SemanticPairSource(val source:MemoryRelationScanSnapshot,val target:MemoryRelationScanSnapshot)
class PreparedMemorySemanticScan internal constructor(internal val owner:MemorySemanticScan,internal val plan:MemoryRelationScanPlan,
    val options:MemorySemanticScanOptions,val route:MemorySemanticRoute,internal val settings:ProviderSettings,
    pairs:List<MemorySemanticPair>,internal val snapshots:List<SemanticPairSource>,val inspected:Int,val candidatesFound:Int,
    val capped:Boolean,val nextCursor:MemoryRelationScanCursor?) {
    val pairs:List<MemorySemanticPair> = Collections.unmodifiableList(ArrayList(pairs))
    internal val consumed=AtomicBoolean(false)
}
data class MemorySemanticOutcome(val index:Int,val sourceId:String,val targetId:String,val status:String,
    val verdict:MemorySemanticVerdict?=null,val reportedModel:String?=null,val relation:MemoryRelationView?=null,val error:String?=null)
data class MemorySemanticScanResult(val dryRun:Boolean,val inspected:Int,val rankedQueries:Int,val candidatesFound:Int,
    val semanticJudged:Int,val semanticSkipped:Int,val semanticErrors:Int,val capped:Boolean,val nextCursor:MemoryRelationScanCursor?,
    val outcomes:List<MemorySemanticOutcome>,val rankingPath:String)
/** Negative cancellation identity only; not a provider or mutation grant. */
class MemorySemanticOperationHandle internal constructor()
internal class SemanticRouteChanged:IllegalStateException("memory_semantic_route_changed")
internal class SemanticSecretBlocked:IllegalStateException("memory_semantic_secret_blocked")

/** Owned by the existing application Bridge, not a composable or persistent job. */
class MemorySemanticScan internal constructor(context:Context,private val bridge:ChatDatabaseBridge) {
    private val context=context.applicationContext
    private class Active(val token:CancellationToken) {
        val handle=MemorySemanticOperationHandle()
        val terminal=AtomicReference<Throwable?>(null)
        val children=Collections.newSetFromMap(ConcurrentHashMap<CancellationToken,Boolean>())
        fun check() { terminal.get()?.let {throw it};token.throwIfCancelled() }
        fun stop(failure:Throwable) { if(terminal.compareAndSet(null,failure)) children.forEach {it.cancel()} }
    }
    private val active=AtomicReference<Active?>(null)
    /** Instance-local fake provider seam. Production always uses the existing configured real client. */
    internal var modelFactory:(ProviderSettings)->CoreAgentModel = { CoreAgentModel(this.context,"memory-semantic-${UUID.randomUUID()}",it) }
    fun isRunning():Boolean=active.get()!=null
    fun activeOperation():MemorySemanticOperationHandle?=active.get()?.handle
    fun cancelIfCurrent(expected:MemorySemanticOperationHandle):Boolean {
        val observed=active.get() ?: return false
        if(observed.handle!==expected) return false
        // Even if this slot clears concurrently, only the captured old token is ever cancelled.
        observed.token.cancel();observed.stop(CancellationException("memory_semantic_cancelled"));return true
    }

    private fun route(settings:ProviderSettings):MemorySemanticRoute {
        val provider=settings.provider
        val destination=when(provider) {
            ProviderSettings.Provider.OPENAI_CODEX -> OpenAICodexResponsesClient.ENDPOINT
            ProviderSettings.Provider.OPENAI_API -> ChatCompletionsConfig.OPENAI_API_ENDPOINT
            ProviderSettings.Provider.OPENROUTER -> OpenRouterClient.ENDPOINT
            ProviderSettings.Provider.CUSTOM -> {
                require(CustomEndpointCompatibilityRegistry.parse(settings.customCompatibility)!=null) { "memory_semantic_custom_compatibility_invalid" }
                CustomEndpointUrlValidator().normalizeBaseUrl(settings.customBaseUrl).chatCompletionsUrl
            }
        }
        return MemorySemanticRoute(provider.name,settings.model,if(provider==ProviderSettings.Provider.OPENAI_CODEX) settings.reasoningVariant else null,
            destination,if(provider==ProviderSettings.Provider.CUSTOM) settings.customCompatibility else null,
            if(provider==ProviderSettings.Provider.OPENAI_CODEX) settings.openAiAuthMethod.name else null)
    }
    private fun requireRoute(prepared:PreparedMemorySemanticScan) {
        if(route(ProviderSettings(context).snapshot())!=prepared.route) throw SemanticRouteChanged()
    }

    /** No client, credentials, context resolver, network or model construction occurs here. */
    fun prepare(options:MemorySemanticScanOptions,token:CancellationToken):PreparedMemorySemanticScan {
        require(options.concurrency in 1..20) { "memory_semantic_concurrency_invalid" }
        require(options.timeoutSeconds in 1..600) { "memory_semantic_timeout_invalid" }
        check(!isRunning()) { "memory_semantic_busy" };token.throwIfCancelled()
        val normalized=options.copy(maxSemantic=if(options.maxSemantic<=0) 100 else options.maxSemantic)
        val settings=ProviderSettings(context).snapshotForPrivateSemanticRequest()
        val route=route(settings)
        val plan=bridge.prepareSemanticScan(normalized.scan,token)
        val seen=HashSet<Pair<String,String>>();val pairs=ArrayList<MemorySemanticPair>();val snapshots=ArrayList<SemanticPairSource>()
        var inspected=0;var found=0;var capped=false
        scan@ for(selection in plan.sources) {
            inspected++;found+=selection.candidates.size
            // A source query error is not a permission to silently send a partial inferred universe.
            if(selection.problem!=null) throw IllegalStateException(selection.problem)
            for(match in selection.candidates) {
                val a=selection.source.base.id;val b=match.target.base.id
                val key=if(a<b) a to b else b to a
                if(!seen.add(key)) continue
                if(pairs.size==normalized.maxSemantic) {capped=true;break@scan}
                val titleA=MemoryObservationContent.prepareTitle(selection.source.base.titleText())
                val titleB=MemoryObservationContent.prepareTitle(match.target.base.titleText())
                val bodyA=MemoryObservationContent.prepareTitle(selection.source.base.content)
                val bodyB=MemoryObservationContent.prepareTitle(match.target.base.content)
                val prompt=MemorySemanticPrompt.build(a,titleA,bodyA,b,titleB,bodyB)
                pairs.add(MemorySemanticPair(pairs.size,a,titleA,bodyA,b,titleB,bodyB,prompt,
                    if(ContentSecretHeuristic.containsLikelySecret(prompt)) "memory_semantic_secret_blocked" else null))
                snapshots.add(SemanticPairSource(selection.source,match.target))
            }
        }
        val last=plan.sources.lastOrNull()?.source
        val cursor=if(!capped && plan.hasMore && last!=null) MemoryRelationScanCursor(plan.database,plan.generation,plan.options.project,
            plan.options.sinceMillis,last.rowId,last.base.id) else null
        token.throwIfCancelled()
        return PreparedMemorySemanticScan(this,plan,normalized,route,settings,pairs,Collections.unmodifiableList(snapshots),inspected,found,capped,cursor)
    }

    /** Blocking owner: the active slot is not released until every physical task has drained. */
    fun execute(prepared:PreparedMemorySemanticScan,sensitiveContentConfirmed:Boolean,token:CancellationToken):MemorySemanticScanResult {
        require(sensitiveContentConfirmed) { "memory_semantic_consent_required" }
        require(prepared.owner===this) { "memory_semantic_preparation_invalid" }
        token.throwIfCancelled()
        val operation=Active(token)
        check(active.compareAndSet(null,operation)) { "memory_semantic_busy" }
        var pool:java.util.concurrent.ExecutorService?=null
        var timers:java.util.concurrent.ScheduledExecutorService?=null
        var interrupted=false
        val unregister=token.registerCancelAction {operation.children.forEach {it.cancel()}}
        try {
            check(prepared.consumed.compareAndSet(false,true)) { "memory_semantic_preparation_consumed" }
            requireRoute(prepared);operation.check()
            val workers=Executors.newFixedThreadPool(prepared.options.concurrency) { task -> Thread(task,"memory-semantic").apply {isDaemon=true} }
            pool=workers
            val scheduler=Executors.newSingleThreadScheduledExecutor {task -> Thread(task,"memory-semantic-timeout").apply {isDaemon=true} }
            timers=scheduler
            val futures=prepared.pairs.map { pair -> workers.submit<MemorySemanticOutcome> {
                if(operation.terminal.get()!=null || token.isCancellationRequested) return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"not_attempted")
                if(pair.blockedReason!=null) return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error=pair.blockedReason)
                val child=CancellationToken.cancellable();operation.children.add(child)
                val deadline=scheduler.schedule({child.cancelForTimeout()},prepared.options.timeoutSeconds.toLong(),TimeUnit.SECONDS)
                try {
                    operation.check();child.throwIfCancelled()
                    val source=prepared.snapshots[pair.index]
                    requireRoute(prepared)
                    bridge.validateSemanticPair(prepared.plan,source.source,source.target,child)
                    if(ContentSecretHeuristic.containsLikelySecret(pair.prompt)) throw SemanticSecretBlocked()
                    // Provider setup and context metadata may use the network only after consent.
                    operation.check();child.throwIfCancelled()
                    val model=try {modelFactory(prepared.settings)}
                    catch(failure:CancellationException) {throw failure}
                    catch(_:RuntimeException) {operation.check();child.throwIfCancelled();return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_provider_setup_error")}
                    operation.check();child.throwIfCancelled()
                    val request=try {model.prepareMainChat("",emptyList(),pair.prompt,emptyList(),child)}
                    catch(failure:CancellationException) {throw failure}
                    catch(_:RuntimeException) {operation.check();child.throwIfCancelled();return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_context_error")}
                    operation.check();child.throwIfCancelled();requireRoute(prepared)
                    // Source SQL/ownership is OUTSIDE provider-error normalization.
                    bridge.validateSemanticPair(prepared.plan,source.source,source.target,child)
                    try {request.requireFits(child)}
                    catch(failure:CancellationException) {throw failure}
                    catch(_:IllegalStateException) {operation.check();child.throwIfCancelled();return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_budget_exceeded")}
                    operation.check();child.throwIfCancelled()
                    val reply=try {model.completePrepared(request,child,null)}
                    catch(failure:CancellationException) {throw failure}
                    catch(_:RuntimeException) {operation.check();child.throwIfCancelled();return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_provider_error")}
                    operation.check();child.throwIfCancelled()
                    if(reply.calls.isNotEmpty()) return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_tool_call_refused")
                    val verdict=try { MemorySemanticVerdictParser.parse(reply.text) } catch(_:IllegalArgumentException) {
                        operation.check();child.throwIfCancelled()
                        return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_invalid_verdict")
                    }
                    val reported=MemoryObservationContent.prepareTitle(reply.model.orEmpty()).takeIf {it.isNotEmpty()}
                    requireRoute(prepared);operation.check();child.throwIfCancelled()
                    bridge.validateSemanticPair(prepared.plan,source.source,source.target,child)
                    if(!prepared.options.scan.apply) return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,
                        if(verdict.relation=="not_conflict") "skipped" else "judged",verdict,reported)
                    val relation=try {bridge.applySemanticPair(prepared.plan,source.source,source.target,verdict,reported,child,operation::check)}
                    catch(_:MemoryRelationScanSqlItemFailure) {operation.check();child.throwIfCancelled();return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_sql_item_failed")}
                    catch(_:MemoryJudgmentItemException) {operation.check();child.throwIfCancelled();return@submit MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_pair_refused")}
                    // The actual Bridge return, not the internal COMMIT, confirms this relation.
                    MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"judged",verdict,reported,relation)
                } catch(failure:CancellationException) {
                    if(child.isTimedOut && !token.isCancellationRequested && operation.terminal.get()==null)
                        MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"error",error="memory_semantic_timeout")
                    else {operation.stop(failure);MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"not_attempted")}
                } catch(failure:Throwable) {
                    operation.stop(failure);MemorySemanticOutcome(pair.index,pair.sourceId,pair.targetId,"not_attempted")
                } finally {deadline.cancel(false);operation.children.remove(child)}
            } }
            val outcomes=ArrayList<MemorySemanticOutcome>()
            for(future in futures) {
                while(true) {
                    try {outcomes.add(future.get());break}
                    catch(failure:InterruptedException) {interrupted=true;token.cancel();operation.stop(ChatDatabaseWaitInterruptedException(failure))}
                    catch(failure:java.util.concurrent.ExecutionException) {operation.stop(failure.cause ?: failure);break}
                }
            }
            operation.check()
            return MemorySemanticScanResult(!prepared.options.scan.apply,prepared.inspected,prepared.inspected,prepared.candidatesFound,
                outcomes.count {it.status=="judged"},outcomes.count {it.status=="skipped"},outcomes.count {it.status=="error"},
                prepared.capped,prepared.nextCursor,outcomes.sortedBy {it.index},prepared.plan.rankingPath)
        } catch(failure:Throwable) {
            // Coordinator/submission failures also fence already-started work before joining it.
            operation.stop(failure);throw failure
        } finally {
            pool?.shutdown();timers?.shutdownNow()
            // Future.cancel/isDone is not evidence that a provider physically returned.
            listOfNotNull(pool,timers).forEach { owned ->
                while(!owned.isTerminated) { try {owned.awaitTermination(1,TimeUnit.DAYS)}
                    catch(_:InterruptedException) {interrupted=true;token.cancel();operation.children.forEach {it.cancel()}} }
            }
            unregister.run();active.compareAndSet(operation,null)
            if(interrupted) Thread.currentThread().interrupt()
        }
    }
}
