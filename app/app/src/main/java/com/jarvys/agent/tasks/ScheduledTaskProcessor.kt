package com.jarvys.agent.tasks

import android.content.Context
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreAgentLoop
import com.jarvys.agent.CoreConnectorTool
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ProviderHttpException
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.ProviderTransportException
import com.jarvys.agent.SecretStore
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.WebSearchTools
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.proactive.BackgroundRunController
import com.jarvys.agent.proactive.BackgroundRunKind
import com.jarvys.agent.proactive.ConfiguredProactiveCoreLoopFactory
import com.jarvys.agent.proactive.ProactiveCoreLoopFactory
import com.jarvys.agent.proactive.ProactiveRedactor
import com.jarvys.agent.proactive.ProactiveReadOnlyToolFactory
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveTextSanitizer
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Single silent-run execution boundary. No chat CoreAgentRuntime, history, UI state, approvals, or Crew. */
class ScheduledTaskProcessor @JvmOverloads constructor(
    context: Context,
    private val loopFactory: ProactiveCoreLoopFactory = ConfiguredProactiveCoreLoopFactory(),
    private val connectorRegistry: ConnectorRegistry = ConnectorRegistry.get(context.applicationContext),
    private val conversationStore: com.jarvys.agent.LocalRunStore = com.jarvys.agent.LocalRunStore(context.applicationContext),
    private val clock: Clock = Clock.systemUTC(),
    private val notifications: ScheduledTaskNotifications = TaskNotifier,
) : TaskExecutor {
    private val app = context.applicationContext
    private val localized = AppLanguageRuntime.localizedContext(app)
    private val locale = localized.resources.configuration.locales[0]

    override fun execute(task: ScheduledTask, scheduledFor: Long, executedAt: Long,
                         runId: String, workerToken: CancellationToken): TaskExecutionResult {
        return executeWithOccurrence(task, scheduledFor, executedAt, runId, workerToken,
            "task:${task.id}:$scheduledFor", "task-${task.id}-$scheduledFor")
    }

    /** Manual requests use a UUID occurrence key so they can never collide with a scheduled wall-time run. */
    fun executeManual(task: ScheduledTask, scheduledFor: Long, executedAt: Long, runId: String,
                      occurrenceId: String, workerToken: CancellationToken): TaskExecutionResult {
        require(occurrenceId.startsWith("manual:${task.id}:")) { "Invalid manual task occurrence id" }
        return executeWithOccurrence(task, scheduledFor, executedAt, runId, workerToken,
            occurrenceId, "task-${task.id}-$occurrenceId")
    }

    private fun executeWithOccurrence(task: ScheduledTask, scheduledFor: Long, executedAt: Long,
                                      runId: String, workerToken: CancellationToken,
                                      occurrenceId: String, sessionId: String): TaskExecutionResult {
        val missingProvider = loopFactory.providerUnavailableReason(localized)
        if (missingProvider != null) return permanentFailure(task, scheduledFor, TaskRunCause.NO_PROVIDER,
            missingProvider, emptyList(), currentModel(), occurrenceId)

        val token = BackgroundRunController.tryStart(BackgroundRunKind.TASK)
            ?: return TaskExecutionResult("RETRYABLE", "NOT_APPLICABLE",
                causeCode = TaskRunCause.BACKGROUND_RUN_ACTIVE, model = currentModel())
        val cancelWorkerRun = workerToken.registerCancelAction { token.cancel() }
        val toolCalls = mutableListOf<TaskToolCallRecord>()
        val registered = AtomicReference<List<ConnectorRegistry>>(emptyList())
        try {
            token.throwIfCancelled()
            val scope = buildTools(task, toolCalls)
            if (scope.error != null) return permanentFailure(task, scheduledFor,
                if (scope.error.startsWith("connector_unavailable:")) TaskRunCause.CONNECTOR_UNAVAILABLE
                else TaskRunCause.TOOL_SCOPE_UNAVAILABLE,
                scope.error, toolCalls, currentModel(), occurrenceId)

            val registryList = scope.connectors
            registered.set(registryList)
            registryList.forEach { it.beginAgentRun(token.generation(), task.instruction, emptyList()) }
            val systemPrompt = if (task.toolScope.web && task.toolScope.tools.any {
                    it in setOf(WebSearchTools.SEARCH, WebSearchTools.FETCH)
                }) WebSearchTools.citationPrompt() else ""
            val userPrompt = userRequest(task)
            val result = runLoop(sessionId, scope.registry, systemPrompt, userPrompt, emptyList(), token)
            token.throwIfCancelled()
            val failedConnectorCall = toolCalls.firstOrNull { it.name in scope.connectorNames && it.outcome == "ERROR" }
            if (failedConnectorCall != null) return permanentFailure(task, scheduledFor,
                TaskRunCause.CONNECTOR_UNAVAILABLE, failedConnectorCall.name, toolCalls, currentModel(), occurrenceId)
            if (!result.outcome.equals("COMPLETED", ignoreCase = true)) return permanentFailure(task, scheduledFor,
                TaskRunCause.EXECUTION_FAILED, result.outcome, toolCalls, currentModel(), occurrenceId)
            val response = scope.respondent.result()
            val finalText = displayText(result.text.orEmpty())
            val noVisibleResult = finalText.isBlank() || finalText == NO_REPLY

            val recovering = task.lastRun?.status == "ERROR" || task.state is TaskState.NeedsAttention
            if (noVisibleResult && task.delivery == TaskDelivery.ONLY_IF_NOTABLE) {
                return TaskExecutionResult("SILENT", "SILENT", toolsCalled = toolCalls.toList(),
                    model = currentModel())
            }
            if (noVisibleResult) return TaskExecutionResult("OK", "NO_RESULT", toolsCalled = toolCalls.toList(),
                model = currentModel())
            val title = response?.title?.let(::displayText)?.takeIf(String::isNotBlank)
                ?: displayText(task.name).ifBlank { task.name }
            val body = finalText

            val session = ScheduledTaskConversation.SESSION_ID
            val threadKey = ScheduledTaskConversation.threadKey(task.id)
            conversationStore.appendConversationTitleIfAbsent(session, ScheduledTaskConversation.title(localized))
            val chatMessage = "$title\n\n$body"
            val messageId = conversationStore.appendProactiveAssistantMessageIfAbsent(
                session, occurrenceId, chatMessage, emptyList(), threadKey)
            // A retry after process death reuses the exact first committed deliverable for the same occurrence.
            val committedText = conversationStore.readConversationTimeline(session)
                .firstOrNull { it.messageId == messageId }?.text ?: chatMessage
            val separator = committedText.indexOf("\n\n")
            val committedTitle = if (separator >= 0) committedText.substring(0, separator) else title
            val committedBody = if (separator >= 0) committedText.substring(separator + 2) else committedText
            val notified = notifications.result(app, conversationStore, task, messageId, committedTitle, committedBody,
                response?.urgency ?: "normal", response?.suggestedReplies.orEmpty())
            val recoveredNotice = if (recovering) notifications.recovered(app, task) else false
            return TaskExecutionResult("OK", when {
                notified || recoveredNotice -> "DELIVERED"
                else -> "CHAT_ONLY"
            }, toolsCalled = toolCalls.toList(), notified = notified || recoveredNotice,
                model = currentModel())
        } catch (cancelled: CancellationException) {
            val partial = toolCalls.any { it.write }
            return TaskExecutionResult(if (partial) "PARTIAL" else "INTERRUPTED", "NOT_APPLICABLE",
                toolsCalled = toolCalls.toList(), causeCode = if (partial) TaskRunCause.EFFECT_OCCURRED
                else TaskRunCause.INTERRUPTED, model = currentModel())
        } catch (failure: RuntimeException) {
            if (token.isCancelled) {
                val partial = toolCalls.any { it.write }
                return TaskExecutionResult(if (partial) "PARTIAL" else "INTERRUPTED", "NOT_APPLICABLE",
                    toolsCalled = toolCalls.toList(), causeCode = if (partial) TaskRunCause.EFFECT_OCCURRED
                    else TaskRunCause.INTERRUPTED, model = currentModel())
            }
            if (isTransient(failure)) return TaskExecutionResult("RETRYABLE", "NOT_APPLICABLE",
                toolsCalled = toolCalls.toList(), causeCode = transientCause(failure),
                causeDetail = failure.message?.takeIf(String::isNotBlank), model = currentModel())
            val (cause, detail) = normalizedCause(failure)
            return permanentFailure(task, scheduledFor, cause, detail, toolCalls, currentModel(), occurrenceId)
        } finally {
            registered.get().forEach { it.endAgentRun(token.generation()) }
            cancelWorkerRun.run()
            BackgroundRunController.finish(BackgroundRunKind.TASK, token)
        }
    }

    private data class Scope(
        val registry: CoreToolRegistry,
        val respondent: TaskRespondTool,
        val connectors: List<ConnectorRegistry>,
        val connectorNames: Set<String>,
        val error: String? = null,
    )

    private fun buildTools(task: ScheduledTask, calls: MutableList<TaskToolCallRecord>): Scope {
        if (task.toolScope.mode !in setOf("READ_ONLY", "LISTED")) return failedScope("invalid_tool_scope")
        val allowlist = ProactiveReadOnlyToolFactory.connectorAllowlist
        val connectorCatalog = connectorRegistry.definitions.value.flatMap { definition ->
            definition.operations.filter { !it.write && it.name in allowlist[definition.id].orEmpty() }
                .map { CoreConnectorTool.toolName(definition.id, it.name) }
        }.toSet()
        val webNames = setOf(WebSearchTools.SEARCH, WebSearchTools.FETCH)
        val knownSafe = connectorCatalog + webNames + TaskRespondTool.NAME
        val requested = task.toolScope.tools.toSet()
        val unsafe = requested - knownSafe
        if (unsafe.isNotEmpty()) return failedScope("tool_scope_not_read_only:${unsafe.sorted().joinToString()}")
        val creatorCaps = task.creatorToolNames.toSet()
        var selected = if (creatorCaps.isEmpty()) requested else requested intersect creatorCaps
        if (!task.toolScope.web) selected = selected - webNames

        connectorRegistry.refreshStates()
        val availableReadOnly = ProactiveReadOnlyToolFactory.readOnlyConnectorTools(connectorRegistry)
        val availableConnectorNames = availableReadOnly.map { it.declaration().name }.toSet()
        val unavailable = selected intersect connectorCatalog - availableConnectorNames
        if (unavailable.isNotEmpty()) return failedScope("connector_unavailable:${unavailable.sorted().joinToString()}")

        val tracked = mutableListOf<CoreTool>()
        val selectedConnectors = availableReadOnly.filter { it.declaration().name in selected }
        selectedConnectors.forEach { tracked += TaskTrackedTool(it, false, calls, redactOutput = true) }
        val selectedWeb = selected intersect webNames
        if (selectedWeb.isNotEmpty()) {
            val exaKey = SecretStore.get(app).getConnectorSecret("web_search", "exa_api_key")
            val language = com.jarvys.agent.WebSearchRequestPolicy.resolveAcceptLanguage("auto", locale)
            WebSearchTools.create(exaKey, language).filter { it.declaration().name in selectedWeb }
                .forEach { tracked += TaskTrackedTool(it, false, calls, redactOutput = true) }
        }
        val respondent = TaskRespondTool()
        tracked += TaskTrackedTool(respondent, false, calls, redactOutput = false)
        return Scope(CoreToolRegistry(tracked), respondent,
            if (selectedConnectors.isEmpty()) emptyList() else listOf(connectorRegistry),
            selectedConnectors.map { it.declaration().name }.toSet())
    }

    private fun failedScope(reason: String) = Scope(CoreToolRegistry(emptyList()), TaskRespondTool(),
        emptyList(), emptySet(), reason)

    private fun runLoop(sessionId: String, tools: CoreToolRegistry, system: String, request: String,
                        history: List<com.jarvys.agent.ConversationTurn>, token: CancellationToken): CoreAgentLoop.Result {
        val result = loopFactory.createLoop(localized, sessionId, tools, system).run(request, history, token, null)
        token.throwIfCancelled()
        // Scheduled capabilities are read-only; preserve their existing transient classification.
        result.throwIfProviderUnavailable()
        return result
    }

    private fun userRequest(task: ScheduledTask): String {
        val zone = when (val schedule = task.schedule) {
            is TaskSchedule.At -> schedule.zone.toZoneId()
            is TaskSchedule.Calendar -> schedule.zone.toZoneId()
            is TaskSchedule.Every -> ZoneId.systemDefault()
        }
        val formatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy HH:mm z", locale)
        val nowLocal = Instant.ofEpochMilli(clock.millis()).atZone(zone).format(formatter)
        val timeData = localized.getString(com.jarvys.agent.R.string.scheduled_task_current_local_time,
            nowLocal, zone.id)
        return "Task name: ${task.name}\n$timeData\n\nTask instruction:\n${task.instruction}"
    }

    private fun permanentFailure(task: ScheduledTask, scheduledFor: Long, cause: TaskRunCause,
                                 detail: String? = null,
                                 calls: List<TaskToolCallRecord>, model: String,
                                 occurrenceId: String = "task:${task.id}:$scheduledFor"): TaskExecutionResult {
        val messageId = "$occurrenceId:attention"
        val session = ScheduledTaskConversation.SESSION_ID
        runCatching {
            conversationStore.appendConversationTitleIfAbsent(session, ScheduledTaskConversation.title(localized))
            conversationStore.appendProactiveAssistantMessageIfAbsent(session, messageId,
                localized.getString(com.jarvys.agent.R.string.scheduled_tasks_attention_body,
                    ProactiveTextSanitizer.sanitize(cause.localized(localized))), emptyList(), ScheduledTaskConversation.threadKey(task.id))
        }
        val notified = notifications.attention(app, task, cause)
        return TaskExecutionResult("ERROR", if (notified) "DELIVERED" else "CHAT_ONLY",
            notified = notified, toolsCalled = calls.toList(), model = model,
            causeCode = cause, causeDetail = detail)
    }

    private fun currentModel(): String = runCatching { ProviderSettings(app).model }.getOrDefault("")

    companion object { const val NO_REPLY = "NO_REPLY" }

    private fun displayText(value: String): String =
        ProactiveTextSanitizer.sanitize(ProactiveRedactor.redactForModel(value))

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

    private fun transientCause(error: RuntimeException): TaskRunCause = when (error) {
        is ProviderHttpException -> if (error.httpStatus == 429) TaskRunCause.PROVIDER_RATE_LIMITED else TaskRunCause.PROVIDER_UNAVAILABLE
        is ProviderTransportException -> TaskRunCause.NETWORK
        else -> TaskRunCause.NETWORK
    }

    private fun normalizedCause(error: RuntimeException): Pair<TaskRunCause, String?> = when (error) {
        is ProviderHttpException -> when {
            error.httpStatus == 429 -> TaskRunCause.PROVIDER_RATE_LIMITED to error.message
            error.httpStatus >= 500 -> TaskRunCause.PROVIDER_UNAVAILABLE to error.message
            else -> TaskRunCause.PROVIDER_ERROR to error.message
        }
        is ProviderTransportException -> TaskRunCause.NETWORK to error.message
        else -> TaskRunCause.EXECUTION_FAILED to (error.message?.takeIf(String::isNotBlank)
            ?: error.javaClass.simpleName.takeIf(String::isNotBlank))
    }

    private class TaskTrackedTool(
        private val delegate: CoreTool,
        private val isWrite: Boolean,
        private val calls: MutableList<TaskToolCallRecord>,
        private val redactOutput: Boolean,
    ) : CoreTool {
        override fun declaration(): ToolSpec = delegate.declaration()
        override fun executionMode() = delegate.executionMode()

        override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
            val name = delegate.declaration().name
            try {
                val result = delegate.execute(arguments, token)
                synchronized(calls) { calls += TaskToolCallRecord(name, isWrite, if (result.success) "OK" else "ERROR") }
                val body = if (redactOutput)
                    com.jarvys.agent.proactive.ProactiveToolOutputRedactor.redactJsonTextFields(result.content)
                else result.content
                return when {
                    result.finishRun -> CoreToolResult.finish(body)
                    result.completeContentRequired -> CoreToolResult.complete(body)
                    result.previewId != null -> CoreToolResult.preview(body, result.previewId)
                    result.success -> CoreToolResult.success(body)
                    else -> CoreToolResult.failure(body)
                }
            } catch (cancelled: CancellationException) {
                synchronized(calls) { calls += TaskToolCallRecord(name, isWrite, "INTERRUPTED") }
                throw cancelled
            } catch (failure: RuntimeException) {
                synchronized(calls) { calls += TaskToolCallRecord(name, isWrite, "ERROR") }
                throw failure
            }
        }
    }


}

/** Worker-facing façade kept separate from the interactive CoreAgentRuntime. */
class ScheduledTaskExecutor(private val processor: ScheduledTaskProcessor) : TaskExecutor {
    constructor(context: Context) : this(ScheduledTaskProcessor(context.applicationContext))

    override fun execute(task: ScheduledTask, scheduledFor: Long, executedAt: Long,
                         runId: String, token: CancellationToken): TaskExecutionResult =
        processor.execute(task, scheduledFor, executedAt, runId, token)
}

private fun TaskZone.toZoneId(): ZoneId = when (this) {
    TaskZone.FollowDevice -> ZoneId.systemDefault()
    is TaskZone.Iana -> ZoneId.of(id)
}
