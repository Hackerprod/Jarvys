package com.jarvys.agent.tasks

import android.content.Context
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CodexResponseException
import com.jarvys.agent.CoreAgentRuntime
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.CoreConnectorTool
import com.jarvys.agent.CoreAgentLoop
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ProviderHttpException
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.ProviderTransportException
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.WebSearchTools
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.proactive.BackgroundRunController
import com.jarvys.agent.proactive.BackgroundRunKind
import com.jarvys.agent.proactive.ConfiguredProactiveCoreLoopFactory
import com.jarvys.agent.proactive.ProactiveCoreLoopFactory
import com.jarvys.agent.proactive.ProactiveRedactor
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveTextSanitizer
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Silent-run boundary: reuses CoreAgentRuntime's tool registry under the task ceiling and a task-owned
 * session, without chat history, UI state, interactive approvals, or Crew.
 */
class ScheduledTaskProcessor @JvmOverloads constructor(
    context: Context,
    private val loopFactory: ProactiveCoreLoopFactory = ConfiguredProactiveCoreLoopFactory(),
    private val connectorRegistry: ConnectorRegistry = ConnectorRegistry.get(context.applicationContext),
    private val conversationStore: com.jarvys.agent.LocalRunStore = com.jarvys.agent.LocalRunStore(context.applicationContext),
    private val clock: Clock = Clock.systemUTC(),
    private val notifications: ScheduledTaskNotifications = TaskNotifier,
    private val tasks: TaskStore = TaskStore(context.applicationContext),
    private val pendingActions: TaskPendingActionStore = TaskPendingActionStore(context.applicationContext),
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
            val scope = buildTools(task, runId, toolCalls)
            if (scope.error != null) return permanentFailure(task, scheduledFor,
                if (scope.error.startsWith("connector_unavailable:")) TaskRunCause.CONNECTOR_UNAVAILABLE
                else TaskRunCause.TOOL_SCOPE_UNAVAILABLE,
                scope.error, toolCalls, currentModel(), occurrenceId)

            val registryList = scope.connectors
            registered.set(registryList)
            registryList.forEach { it.beginAgentRun(token.generation(), task.instruction, emptyList()) }
            val systemPrompt = buildString {
                val memoryEnabled = app.getSharedPreferences(
                    com.jarvys.agent.MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE,
                ).getBoolean(com.jarvys.agent.MemoryConstants.ENABLED_KEY, true)
                val context = ChatDatabaseBridge.getInstance(app)
                    .personalAgentPromptContext(ScheduledTaskConversation.SESSION_ID, !memoryEnabled, "task")
                append(context.identity).append('\n').append(context.soul).append('\n').append(context.rules)
                if (context.userProfile.isNotBlank()) {
                    append("\n\nUser profile preferences and facts (system and current user instructions take priority):\n")
                    append(context.userProfile)
                }
                append("\nBackground task session: use identity and profile context only. Do not read private conversation memory or ask bootstrap questions.")
                if (task.toolScope.web && task.toolScope.tools.any {
                    it in setOf(WebSearchTools.SEARCH, WebSearchTools.FETCH)
                }) append("\n\n").append(WebSearchTools.citationPrompt())
            }
            val userPrompt = userRequest(task)
            val result = runLoop(sessionId, scope.registry, systemPrompt, userPrompt, emptyList(), token)
            token.throwIfCancelled()
            if (toolCalls.any { it.outcome == "WAITING_VISIBLE_APP" }) return permanentFailure(
                task, scheduledFor, TaskRunCause.AWAITING_USER,
                "Open the app to complete the deferred action.", toolCalls, currentModel(), occurrenceId,
            )
            val failedConnectorCall = toolCalls.firstOrNull { it.name in scope.connectorNames && it.outcome == "ERROR" }
            if (failedConnectorCall != null) return permanentFailure(task, scheduledFor,
                TaskRunCause.CONNECTOR_UNAVAILABLE, failedConnectorCall.name, toolCalls, currentModel(), occurrenceId)
            if (!result.outcome.equals("COMPLETED", ignoreCase = true)) return permanentFailure(task, scheduledFor,
                TaskRunCause.EXECUTION_FAILED, result.outcome, toolCalls, currentModel(), occurrenceId)
            val response = scope.respondent.result()
            val rawFinalText = result.text.orEmpty()
            val finalText = displayText(rawFinalText)
            // Compare the control token before Markdown sanitization: that projection removes
            // underscores, so NO_REPLY would otherwise become ordinary visible text (NOREPLY).
            val noVisibleResult = finalText.isBlank() || rawFinalText.trim().equals(NO_REPLY, ignoreCase = true)

            if (noVisibleResult && task.delivery == TaskDelivery.ONLY_IF_NOTABLE) {
                val recovered = recoverIncident(task)
                return TaskExecutionResult("SILENT", if (recovered) "DELIVERED" else "SILENT",
                    notified = recovered, toolsCalled = toolCalls.toList(), model = currentModel())
            }
            if (noVisibleResult) {
                val recovered = recoverIncident(task)
                return TaskExecutionResult("OK", if (recovered) "DELIVERED" else "NO_RESULT",
                    notified = recovered, toolsCalled = toolCalls.toList(), model = currentModel())
            }
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
            val recoveredNotice = recoverIncident(task)
            return TaskExecutionResult("OK", when {
                notified || recoveredNotice -> "DELIVERED"
                else -> "CHAT_ONLY"
            }, toolsCalled = toolCalls.toList(), notified = notified || recoveredNotice,
                model = currentModel(), deliveredResult = committedBody, conversationMessageId = messageId)
        } catch (cancelled: CancellationException) {
            val partial = toolCalls.any { it.write && it.outcome != "PENDING" }
            return TaskExecutionResult(if (partial) "PARTIAL" else "INTERRUPTED", "NOT_APPLICABLE",
                toolsCalled = toolCalls.toList(), causeCode = if (partial) TaskRunCause.EFFECT_OCCURRED
                else TaskRunCause.INTERRUPTED, model = currentModel())
        } catch (failure: RuntimeException) {
            if (token.isCancelled) {
                val partial = toolCalls.any { it.write && it.outcome != "PENDING" }
                return TaskExecutionResult(if (partial) "PARTIAL" else "INTERRUPTED", "NOT_APPLICABLE",
                    toolsCalled = toolCalls.toList(), causeCode = if (partial) TaskRunCause.EFFECT_OCCURRED
                    else TaskRunCause.INTERRUPTED, model = currentModel())
            }
            if (isTransient(failure)) return TaskExecutionResult("RETRYABLE", "NOT_APPLICABLE",
                toolsCalled = toolCalls.toList(), causeCode = transientCause(failure),
                causeDetail = transientCauseDetail(failure), model = currentModel())
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

    private fun buildTools(task: ScheduledTask, runId: String,
                           calls: MutableList<TaskToolCallRecord>): Scope {
        if (task.toolScope.mode !in setOf("READ_ONLY", "LISTED")) return failedScope("invalid_tool_scope")
        connectorRegistry.refreshStates()
        val taskCapabilityCeiling = task.creatorToolNames.toSet()
        val requested = task.toolScope.tools.toSet() intersect taskCapabilityCeiling
        val connectorCatalog = connectorRegistry.definitions.value.flatMap { definition ->
            definition.operations.map { operation ->
                CoreConnectorTool.toolName(definition.id, operation.name) to
                    connectorRegistry.configuredAutonomyPolicy(definition, operation)
            }
        }.filter { it.second != AutonomyPolicy.DENY }.map { it.first }.toSet()
        val availableConnectorNames = connectorRegistry.connectedDefinitions().flatMap { definition ->
            definition.operations.filter { operation ->
                definition.operationAccessGranted?.invoke(operation.name) != false &&
                    connectorRegistry.configuredAutonomyPolicy(definition, operation) != AutonomyPolicy.DENY
            }.map { operation -> CoreConnectorTool.toolName(definition.id, operation.name) }
        }.toSet()
        val unavailable = requested intersect connectorCatalog - availableConnectorNames
        if (unavailable.isNotEmpty()) return failedScope("connector_unavailable:${unavailable.sorted().joinToString()}")
        val taskTools = CoreAgentRuntime.createScheduledTaskTools(
            app, task, "task-${task.id}-$runId", connectorRegistry,
            ScheduledTaskWriteDeferrer { toolName, arguments, token ->
                token.throwIfCancelled()
                val canonicalArguments = TaskPendingActionStore.canonicalizeArguments(arguments)
                val pending = pendingActions.enqueue(task, runId, toolName, canonicalArguments)
                notifications.pendingAction(app, task, pending)
                CoreToolResult.success(JSONObject()
                    .put("status", "pending_user_approval")
                    .put("executed", false)
                    .put("pending_action_id", pending.id)
                    .toString())
            })
        val tracked = mutableListOf<CoreTool>()
        val connectorNames = taskTools.connectorToolNames().toSet()
        taskTools.names().forEach { name ->
            val tool = taskTools.tool(name) ?: return@forEach
            val isWebRead = name == WebSearchTools.SEARCH || name == WebSearchTools.FETCH
            tracked += TaskTrackedTool(tool,
                tool.unattendedAccess() == CoreTool.UnattendedAccess.WRITE,
                calls,
                redactOutput = tool.isConnectorTool() || tool.isMcpTool() || isWebRead)
        }
        val respondent = TaskRespondTool()
        tracked += TaskTrackedTool(respondent, false, calls, redactOutput = false)
        return Scope(CoreToolRegistry(tracked), respondent,
            if (connectorNames.isEmpty()) emptyList() else listOf(connectorRegistry), connectorNames)
    }

    private fun failedScope(reason: String) = Scope(CoreToolRegistry(emptyList()), TaskRespondTool(),
        emptyList(), emptySet(), reason)

    private fun runLoop(sessionId: String, tools: CoreToolRegistry, system: String, request: String,
                        history: List<com.jarvys.agent.ConversationTurn>, token: CancellationToken): CoreAgentLoop.Result {
        val result = loopFactory.createLoop(localized, sessionId, tools, system).run(request, history, token, null)
        token.throwIfCancelled()
        // Preserve existing provider and transport transient classification for task capabilities.
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
        val isNewIncident = beginIncident(task, cause)
        val notified = if (isNewIncident) {
            val messageId = "$occurrenceId:attention"
            val session = ScheduledTaskConversation.SESSION_ID
            runCatching {
                conversationStore.appendConversationTitleIfAbsent(session, ScheduledTaskConversation.title(localized))
                conversationStore.appendProactiveAssistantMessageIfAbsent(session, messageId,
                    localized.getString(com.jarvys.agent.R.string.scheduled_tasks_attention_body,
                        ProactiveTextSanitizer.sanitize(cause.localized(localized))), emptyList(), ScheduledTaskConversation.threadKey(task.id))
            }
            notifications.attention(app, task, cause)
        } else false
        return TaskExecutionResult("ERROR", if (notified) "DELIVERED" else "CHAT_ONLY",
            notified = notified, toolsCalled = calls.toList(), model = model,
            causeCode = cause, causeDetail = detail)
    }

    private fun beginIncident(task: ScheduledTask, cause: TaskRunCause): Boolean {
        if (task.openIncidentCause == cause) return false
        if (tasks.openIncident(task.id, task.revision, cause)) return true
        return tasks.get(task.id) == null && task.openIncidentCause != cause
    }

    private fun recoverIncident(task: ScheduledTask): Boolean {
        val hasIncident = task.openIncidentCause != null || task.state is TaskState.NeedsAttention ||
            task.lastRun?.status == "ERROR"
        if (!hasIncident) return false
        // Persist closure first; a repeated worker delivery sees a closed incident and cannot re-alert.
        if (!tasks.closeIncident(task.id, task.revision, clock.millis())) return false
        return notifications.recovered(app, task)
    }

    private fun currentModel(): String = runCatching { ProviderSettings(app).model }.getOrDefault("")

    companion object { const val NO_REPLY = "NO_REPLY" }

    private fun displayText(value: String): String =
        ProactiveTextSanitizer.sanitize(ProactiveRedactor.redactForModel(value))

    private fun isTransient(error: RuntimeException): Boolean {
        return retryableProviderCause(error) != null
    }

    private fun retryableProviderCause(error: Throwable): Throwable? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            when (cause) {
                is ProviderTransportException, is IOException -> return cause
                is CodexResponseException -> return cause.takeIf {
                    it.kind == CodexResponseException.Kind.INCOMPLETE
                }
                is ProviderHttpException -> return cause.takeIf {
                    it.httpStatus == 408 || it.httpStatus == 429 || it.httpStatus in 500..599
                }
            }
            cause = cause.cause
        }
        return null
    }

    private fun transientCause(error: RuntimeException): TaskRunCause = when (val cause = retryableProviderCause(error)) {
        is ProviderHttpException -> if (cause.httpStatus == 429) TaskRunCause.PROVIDER_RATE_LIMITED
            else TaskRunCause.PROVIDER_UNAVAILABLE
        is ProviderTransportException, is IOException -> TaskRunCause.NETWORK
        is CodexResponseException -> TaskRunCause.PROVIDER_UNAVAILABLE
        else -> TaskRunCause.NETWORK
    }

    /** Keep the provider's underlying transport detail when an adapter wraps it for classification. */
    private fun transientCauseDetail(error: RuntimeException): String? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = error
        var detail: String? = null
        while (cause != null && seen.add(cause)) {
            if (cause is ProviderTransportException || cause is IOException ||
                cause is ProviderHttpException || cause is CodexResponseException) {
                cause.message?.takeIf(String::isNotBlank)?.let { detail = it }
            }
            cause = cause.cause
        }
        return detail ?: error.message?.takeIf(String::isNotBlank)
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
                val resultStatus = runCatching { JSONObject(result.content).optString("status") }.getOrDefault("")
                val outcome = when {
                    !result.success -> "ERROR"
                    resultStatus == "pending_user_approval" -> "PENDING"
                    resultStatus == "deferred_visible_app_required" -> "WAITING_VISIBLE_APP"
                    else -> "OK"
                }
                synchronized(calls) { calls += TaskToolCallRecord(name, isWrite, outcome) }
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
