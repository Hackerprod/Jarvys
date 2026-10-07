package com.jarvys.agent.proactive

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreAgentLoop
import com.jarvys.agent.CoreAgentModel
import com.jarvys.agent.CoreConnectorTool
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.ModelReply
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.ProviderHttpException
import com.jarvys.agent.ProviderTransportException
import com.jarvys.agent.SecretStore
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.providers.ProviderClientRegistry
import com.jarvys.agent.connectors.CalendarConnector
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorOperation
import com.jarvys.agent.connectors.ContactsConnector
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONArray

data class ProactiveDecision(
    val notify: Boolean,
    val title: String,
    val body: String,
    val urgency: String,
    val eventIds: List<String>,
    val threadKey: String,
    val suggestedReplies: List<ProactiveSuggestedReply> = emptyList(),
)

object ProactiveDecisionValidation {
    fun failure(decision: ProactiveDecision, validEventIds: Set<String>): String? {
        if (decision.eventIds.distinct().size != decision.eventIds.size || decision.eventIds.any { it !in validEventIds })
            return "event_ids must identify events in this batch without duplicates"
        if (decision.notify && decision.eventIds.isEmpty()) return "A notice must identify at least one event from this batch"
        if (decision.notify && ProactiveThreadKey.normalize(decision.threadKey).isBlank()) return "A notice must provide a stable thread_key"
        if (decision.suggestedReplies.any { it.label.isBlank() || it.text.isBlank() })
            return "suggested_replies labels and text must not be empty"
        if (!decision.notify && decision.suggestedReplies.isNotEmpty()) return "Silent decisions cannot include suggested_replies"
        return null
    }
}

fun interface ProactiveDecisionSink {
    fun submit(decision: ProactiveDecision): CoreToolResult
}

/** Production/fake-provider boundary for the existing CoreAgentLoop engine. */
interface ProactiveCoreLoopFactory {
    fun providerUnavailableReason(context: Context): String?
    fun createLoop(
        context: Context,
        sessionId: String,
        tools: CoreToolRegistry,
        systemPrompt: String,
    ): CoreAgentLoop
}

class ConfiguredProactiveCoreLoopFactory : ProactiveCoreLoopFactory {
    override fun providerUnavailableReason(context: Context): String? = try {
        val settings = ProviderSettings(context)
        val secrets = SecretStore.get(context)
        ProviderClientRegistry.unavailableReason(settings.provider, secrets, settings)
    } catch (_: RuntimeException) {
        "sin proveedor"
    }

    override fun createLoop(
        context: Context,
        sessionId: String,
        tools: CoreToolRegistry,
        systemPrompt: String,
    ): CoreAgentLoop = CoreAgentLoop(CoreAgentModel(context, sessionId), tools, systemPrompt, sessionId)
}

/** Explicit capability allowlist; operation.write is also checked as a defense against catalog drift. */
object ProactiveReadOnlyToolFactory {
    val connectorAllowlist: Map<String, Set<String>> = linkedMapOf(
        ContactsConnector.ID to setOf(ContactsConnector.SEARCH, ContactsConnector.GET_DETAIL),
        CalendarConnector.ID to setOf(CalendarConnector.SEARCH),
        "sms" to setOf("list_sms"),
        "call_log" to setOf("list_calls"),
    )

    fun create(context: Context, decisionSink: ProactiveDecisionSink): CoreToolRegistry {
        val registry = ConnectorRegistry.get(context.applicationContext)
        registry.refreshStates()
        return create(registry, context, decisionSink)
    }

    /** Shared capability assembly lets call sites supply an existing connected registry without widening it. */
    fun create(
        registry: ConnectorRegistry,
        context: Context,
        decisionSink: ProactiveDecisionSink,
    ): CoreToolRegistry {
        val tools = mutableListOf<CoreTool>().apply { addAll(readOnlyConnectorTools(registry)) }
        tools += ProactiveRespondCoreTool(context.applicationContext, decisionSink)
        tools += ProactiveStatusCoreTool(context.applicationContext, includeRawTimes = false)
        return CoreToolRegistry(tools)
    }

    /** Same connected, explicitly allowlisted, redacting connector tools used by Proactive. */
    internal fun readOnlyConnectorTools(registry: ConnectorRegistry): List<CoreTool> {
        val tools = mutableListOf<CoreTool>()
        registry.connectedDefinitions().forEach { definition ->
            val allowedOperations = connectorAllowlist[definition.id].orEmpty()
            definition.operations
                .filter { !it.write && it.name in allowedOperations }
                .forEach { operation ->
                    tools += ProactiveRedactingConnectorTool(CoreConnectorTool(registry, definition, operation))
                }
        }
        return tools
    }
}

/** Connector output is also redacted before becoming a model tool-result turn. */
private class ProactiveRedactingConnectorTool(private val delegate: CoreTool) : CoreTool {
    override fun declaration(): ToolSpec = delegate.declaration()

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        val result = delegate.execute(arguments, token)
        if (!result.success) return result
        return CoreToolResult.success(ProactiveToolOutputRedactor.redactJsonTextFields(result.content))
    }
}

class ProactiveAgentProcessor(
    context: Context,
    private val loopFactory: ProactiveCoreLoopFactory = ConfiguredProactiveCoreLoopFactory(),
    private val readOnlyTools: (Context, ProactiveDecisionSink) -> CoreToolRegistry =
        { app, sink -> ProactiveReadOnlyToolFactory.create(app, sink) },
    private val reviewRecorder: ProactiveReviewRecorder = ProactiveReviewRecorder(ProactiveReviewStateStore(context)),
    private val auditStore: ProactiveDecisionAuditStore = ProactiveDecisionAuditStore(context),
    private val conversationStore: com.jarvys.agent.LocalRunStore = com.jarvys.agent.LocalRunStore(context),
) : ProactiveBatchProcessor {
    private val app = context.applicationContext
    private val localized = AppLanguageRuntime.localizedContext(app)
    private val eventStore = ProactiveEventStore(app)

    override fun review(
        batches: List<ProactiveEventBatch>,
        token: CancellationToken,
    ): ProactiveProcessingResult {
        reviewRecorder.recordReview(batches.size)
        if (batches.isEmpty()) {
            reviewRecorder.clearFailure()
            return ProactiveProcessingResult.Completed
        }
        try {
            loopFactory.providerUnavailableReason(app)?.let { return retry(it) }
            for (batch in batches) {
                token.throwIfCancelled()
                val safeEvents = batch.events.map { it.modelSafe(localized) }
                val decisionId = decisionId(batch.events)
                val existing = auditStore.find(decisionId)
                val modelDecision = existing?.let(::decisionFromAudit)
                    ?: runDecision(safeEvents, decisionId, token)
                    ?: return retry("sin proactive_respond")
                token.throwIfCancelled()

                val deliverDecision = if (existing != null) modelDecision else finalizeDecision(modelDecision)
                val audit = existing ?: auditStore.saveIfAbsent(toAudit(decisionId, batch.events, deliverDecision))
                val savedDecision = decisionFromAudit(audit)
                if (savedDecision.notify) {
                    token.throwIfCancelled()
                    val message = chatMessage(savedDecision)
                    conversationStore.appendConversationTitleIfAbsent(
                        ProactiveConversation.SESSION_ID,
                        ProactiveConversation.title(localized),
                    )
                    token.throwIfCancelled()
                    val messageId = conversationStore.appendProactiveAssistantMessageIfAbsent(
                        ProactiveConversation.SESSION_ID,
                        decisionId,
                        message,
                        savedDecision.suggestedReplies,
                        savedDecision.threadKey,
                    )
                    if (!com.jarvys.agent.MainActivity.isAppForeground()) {
                        val timeline = conversationStore.readConversationTimeline(ProactiveConversation.SESSION_ID)
                        val threadMessages = timeline.filter { it.proactiveThreadKey == savedDecision.threadKey }
                            .map { ProactiveThreadMessage(it.text, it.timestampMillis, it.kind == "assistant") }
                        val storedReply = timeline.firstOrNull { it.messageId == messageId }
                        ProactiveNotifier.post(localized, savedDecision.threadKey, messageId,
                            message, savedDecision.urgency, threadMessages,
                            messageId,
                            storedReply?.proactiveReplies.orEmpty(), storedReply?.proactiveRepliesUsed ?: false)
                    }
                }

                token.throwIfCancelled()
                batch.events.forEach { event ->
                    if (!eventStore.markProcessed(event.id)) {
                        val stored = eventStore.readAll().firstOrNull { it.id == event.id }
                        if (stored?.state != ProactiveEvent.PROCESSED) {
                            return retry("no se pudo guardar el estado procesado")
                        }
                    }
                }
            }
            reviewRecorder.clearFailure()
            return ProactiveProcessingResult.Completed
        } catch (cancelled: CancellationException) {
            return if (token.isCancelled) ProactiveProcessingResult.Stopped else throw cancelled
        } catch (failure: RuntimeException) {
            if (token.isCancelled) return ProactiveProcessingResult.Stopped
            if (failure is ProactiveDecisionRequiredException) return retry("sin proactive_respond")
            val reason = failureReason(failure)
            return if (isTransient(failure)) retry(reason) else {
                reviewRecorder.recordFailure(reason)
                ProactiveProcessingResult.Failed(reason)
            }
        }
    }

    private fun runDecision(
        safeEvents: List<ProactiveModelEvent>,
        decisionId: String,
        token: CancellationToken,
    ): ProactiveDecision? {
        val captured = AtomicReference<ProactiveDecision?>(null)
        val allowedEventIds = safeEvents.map(ProactiveModelEvent::id).toSet()
        val sink = ProactiveDecisionSink { decision ->
            ProactiveDecisionValidation.failure(decision, allowedEventIds)?.let {
                return@ProactiveDecisionSink CoreToolResult.failure(it)
            }
            if (!captured.compareAndSet(null, decision)) {
                CoreToolResult.failure("proactive_respond may only be called once")
            } else {
                CoreToolResult.finish(JSONObject()
                    .put("notify", decision.notify)
                    .put("urgency", decision.urgency)
                    .put("event_ids", JSONArray(decision.eventIds))
                    .put("thread_key", decision.threadKey)
                    .put("suggested_replies", JSONArray(decision.suggestedReplies.map { reply ->
                        JSONObject().put("label", reply.label).put("text", reply.text)
                    }))
                    .toString())
            }
        }
        val tools = readOnlyTools(localized, sink)
        val sessionId = "proactive-$decisionId"
        val loop = loopFactory.createLoop(localized, sessionId, tools, ProactivePrompt.system(localized))
        val result = try {
            loop.run(ProactivePrompt.eventPayload(safeEvents, auditStore.recentNotificationTitles()), emptyList(), token, null)
        } catch (failure: IllegalStateException) {
            if (failure.message == "Provider returned neither an answer nor a tool call") {
                throw ProactiveDecisionRequiredException()
            }
            throw failure
        }
        token.throwIfCancelled()
        if (!"COMPLETED".equals(result.outcome, ignoreCase = true)) return null
        return captured.get()
    }

    private fun finalizeDecision(decision: ProactiveDecision): ProactiveDecision {
        if (!decision.notify) return decision.copy(title = "", body = "")
        val title = ProactiveTextSanitizer.sanitize(decision.title).replace(Regex("\\s+"), " ").trim()
        val body = ProactiveTextSanitizer.sanitize(decision.body)
        val replies = decision.suggestedReplies.map { reply -> ProactiveSuggestedReply(
            ProactiveTextSanitizer.sanitize(reply.label), ProactiveTextSanitizer.sanitize(reply.text),
        ) }.filter { it.label.isNotBlank() && it.text.isNotBlank() }
        return decision.copy(title = title, body = body, threadKey = ProactiveThreadKey.normalize(decision.threadKey),
            suggestedReplies = replies)
    }

    private fun toAudit(
        decisionId: String,
        events: List<ProactiveEvent>,
        decision: ProactiveDecision,
    ): ProactiveDecisionRecord = ProactiveDecisionRecord(
        decisionId = decisionId,
        events = events.map { event ->
            ProactiveAuditedEvent(
                id = event.id,
                sourceId = event.sourceId,
                appPackage = event.appPackage,
                category = event.category,
                receivedAtMillis = event.receivedAtMillis,
                dedupKey = event.dedupKey,
            )
        },
        notify = decision.notify,
        urgency = decision.urgency,
        title = decision.title.takeIf { decision.notify },
        body = decision.body.takeIf { decision.notify },
        decidedAtMillis = System.currentTimeMillis(),
        eventIds = decision.eventIds,
        threadKey = decision.threadKey,
        suggestedReplies = decision.suggestedReplies,
    )

    private fun decisionFromAudit(record: ProactiveDecisionRecord) = ProactiveDecision(
        notify = record.notify,
        title = record.title.orEmpty(),
        body = record.body.orEmpty(),
        urgency = record.urgency,
        eventIds = record.eventIds,
        threadKey = record.threadKey,
        suggestedReplies = record.suggestedReplies,
    )

    private fun chatMessage(decision: ProactiveDecision): String =
        "${decision.title}\n\n${decision.body}".trim()

    private fun decisionId(events: List<ProactiveEvent>): String =
        ProactiveNormalizer.dedupKey(
            "proactive_decision",
            events.sortedBy(ProactiveEvent::receivedAtMillis).joinToString("\u001f") { it.id },
            events.minOfOrNull(ProactiveEvent::receivedAtMillis) ?: 0L,
        )

    private fun failureReason(error: RuntimeException): String = when (error) {
        is ProviderHttpException -> when {
            error.httpStatus == 429 -> "429"
            error.httpStatus >= 500 -> "5xx"
            else -> "proveedor_http_${error.httpStatus}"
        }
        is ProviderTransportException -> "red"
        is CancellationException -> "detenido"
        else -> "error_en_revision"
    }

    private fun retry(reason: String): ProactiveProcessingResult.Retryable {
        reviewRecorder.recordFailure(reason)
        return ProactiveProcessingResult.Retryable(reason)
    }

    private fun isTransient(error: RuntimeException): Boolean {
        if (error is ProviderTransportException) return true
        if (error is ProviderHttpException) return error.httpStatus == 429 || error.httpStatus in 500..599
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }

    private class ProactiveDecisionRequiredException : IllegalStateException("proactive_respond is required")
}
