package com.jarvys.agent

import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentRunUiEvent(
    val id: Long,
    val kind: String,
    val stage: String? = null,
    val text: String,
    val detail: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val toolCallId: String? = null,
    val previewId: String? = null,
    val approvalId: String? = null,
    val approvalLines: List<String> = emptyList(),
    val approvalStatus: String? = null,
    val approvalPermission: String? = null,
    val approvalIntent: com.jarvys.agent.connectors.ApprovalIntentSpec? = null,
    val approvalPermissionDeniedIntent: com.jarvys.agent.connectors.ApprovalIntentSpec? = null,
    val approvalAllowAlwaysAvailable: Boolean = false,
    val approvalAutonomyConnectorId: String? = null,
    val approvalAutonomyOperationName: String? = null,
    val approvalStatusDetail: String? = null,
    val approvalLocalizedTitle: com.jarvys.agent.connectors.ConnectorUiText? = null,
    val approvalLocalizedLines: List<com.jarvys.agent.connectors.ConnectorUiText>? = null,
    val approvalPermissionLabel: com.jarvys.agent.connectors.ConnectorUiText? = null,
    val approvalStatusDetailText: com.jarvys.agent.connectors.ConnectorUiText? = null,
    val approvalCompactSummary: com.jarvys.agent.connectors.ConnectorUiText? = null,
    val approvalRequester: String? = null,
    val approvalRequesterColorKey: String? = null,
    val toolDisplayName: String? = null,
    val toolStatusResourceId: Int? = null,
    val memoryRevisionId: Long? = null,
    val memoryPath: String? = null,
    val memoryBefore: String? = null,
    val memoryAfter: String? = null,
    val compactionMessageCount: Int = 0,
    val messageId: String = "",
    val durationMs: Long = 0,
    val memoryReflectionGroupId: String? = null,
    val memoryRevisionIds: List<Long> = emptyList(),
    val crewMissionSnapshot: CrewMissionSnapshot? = null,
    val proactiveThreadKey: String? = null,
    val proactiveReplies: List<ProactiveSuggestedReply> = emptyList(),
    val proactiveRepliesUsed: Boolean = false,
    val proactiveNotice: Boolean = false,
    val decisionId: String? = null,
    val decisionBody: String? = null,
    val decisionOptions: List<UserDecisionOption> = emptyList(),
    val decisionAllowDismiss: Boolean = true,
    val decisionStatus: String? = null,
    val decisionOptionId: String? = null,
    val decisionOptionLabel: String? = null,
    val generatedImagePath: String? = null,
    val generatedImagePrompt: String? = null,
    val generatedImageRevisedPrompt: String? = null,
    val generatedImageMimeType: String? = null,
    val generatedImageSize: String? = null,
    val generatedImageStatus: String? = null,
    val generatedImageError: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val deliveredArtifact: ChatAttachment? = null,
    val toolAuditDetail: String? = null,
    val reactionEmoji: String = "",
    val previewIsCurrent: Boolean = false,
 ) {
    fun copyMetadata(messageId: String, durationMs: Long): AgentRunUiEvent =
        copy(messageId = messageId, durationMs = durationMs)
    fun copyReaction(emoji: String): AgentRunUiEvent =
        copy(reactionEmoji = if (kind == "user" && proactiveThreadKey.isNullOrEmpty()) emoji else "")
    fun copyToolPresentation(auditDetail: String?, currentPreview: Boolean): AgentRunUiEvent =
        copy(toolAuditDetail = auditDetail?.takeIf(String::isNotBlank), previewIsCurrent = currentPreview)
    fun copyStage(stage: String?): AgentRunUiEvent = copy(stage = stage)
    fun copyAttachments(attachments: List<ChatAttachment>): AgentRunUiEvent =
        copy(attachments = if (kind == "user" && proactiveThreadKey.isNullOrEmpty()) attachments.toList() else emptyList())

    companion object {
        @JvmStatic
        fun messageEvent(id: Long, role: String, text: String, timestampMillis: Long): AgentRunUiEvent =
            AgentRunUiEvent(id, role, null, text, null, timestampMillis)

        @JvmStatic
        fun compactionEvent(id: Long, summary: String, messageCount: Int, mode: String,
                            timestampMillis: Long): AgentRunUiEvent =
            AgentRunUiEvent(id, "compaction", mode, summary, null, timestampMillis,
                compactionMessageCount = messageCount)

        @JvmStatic
        fun reflectionMemoryEvent(id: Long, summary: String, reflectionId: String, revisionIds: List<Long>,
                                  status: String, timestampMillis: Long): AgentRunUiEvent =
            AgentRunUiEvent(id, "memory", "REFLECTION", summary, status, timestampMillis,
                memoryReflectionGroupId = reflectionId, memoryRevisionIds = revisionIds.toList())

        @JvmStatic
        fun translationEvent(id: Long, language: String, translation: String,
                             messageId: String, timestampMillis: Long): AgentRunUiEvent =
            AgentRunUiEvent(id, "assistant_translation", language, translation, messageId, timestampMillis)

        @JvmStatic
        fun userDecisionEvent(id: Long, decisionId: String, title: String, body: String,
                              options: List<UserDecisionOption>, allowDismiss: Boolean, status: String,
                              optionId: String?, optionLabel: String?, timestampMillis: Long): AgentRunUiEvent =
            AgentRunUiEvent(id = id, kind = "user_decision", stage = status, text = title,
                detail = body, timestampMillis = timestampMillis, decisionId = decisionId,
                decisionBody = body, decisionOptions = options.toList(), decisionAllowDismiss = allowDismiss,
                decisionStatus = status, decisionOptionId = optionId, decisionOptionLabel = optionLabel)

        @JvmStatic
        fun proactiveMessageEvent(id: Long, role: String, text: String, timestampMillis: Long,
                                  messageId: String, threadKey: String?,
                                  replies: List<ProactiveSuggestedReply>, repliesUsed: Boolean,
                                  durationMs: Long = 0L, stage: String? = null,
                                  proactiveNotice: Boolean = false): AgentRunUiEvent =
            AgentRunUiEvent(id = id, kind = role, stage = stage, text = text,
                timestampMillis = timestampMillis, messageId = messageId,
                durationMs = durationMs, proactiveThreadKey = threadKey,
                proactiveReplies = replies, proactiveRepliesUsed = repliesUsed, proactiveNotice = proactiveNotice)

        @JvmStatic
        fun crewMissionEvent(id: Long, snapshot: CrewMissionSnapshot): AgentRunUiEvent =
            AgentRunUiEvent(id, "crew_mission", snapshot.status, snapshot.title,
                timestampMillis = snapshot.startedAtMillis, crewMissionSnapshot = snapshot)

        @JvmStatic
        fun deliveredFileEvent(id: Long, artifact: ChatAttachment, timestampMillis: Long): AgentRunUiEvent =
            AgentRunUiEvent(id = id, kind = "delivered_file", text = artifact.name,
                deliveredArtifact = artifact, timestampMillis = timestampMillis)

        @JvmStatic
        fun generatedImageEvent(id: Long, relativePath: String?, prompt: String, revisedPrompt: String?,
                                mimeType: String, size: String?, status: String, error: String?,
                                timestampMillis: Long): AgentRunUiEvent = AgentRunUiEvent(
            id = id, kind = "generated_image", stage = status, text = prompt,
            timestampMillis = timestampMillis, generatedImagePath = relativePath,
            generatedImagePrompt = prompt, generatedImageRevisedPrompt = revisedPrompt,
            generatedImageMimeType = mimeType, generatedImageSize = size,
            generatedImageStatus = status, generatedImageError = error,
        )

        @JvmStatic
        fun assistantStageForOutcome(outcome: String?): String? =
            outcome?.takeIf { it.isNotEmpty() && it != "COMPLETED" }

        @JvmStatic
        fun toolEvent(
            id: Long,
            stage: String,
            displayName: String,
            detail: String?,
            callId: String?,
            previewId: String?,
            timestampMillis: Long,
        ): AgentRunUiEvent {
            val normalizedStage = when (stage) {
                "tool_call" -> "tool_call"
                "tool_error" -> "tool_error"
                "tool_progress" -> "tool_progress"
                "tool_interrupted" -> "tool_interrupted"
                "tool_not_started" -> "tool_not_started"
                else -> "tool_result"
            }
            val statusResource: Int? = when (normalizedStage) {
                "tool_call" -> R.string.connector_tool_using
                "tool_error" -> R.string.connector_tool_failed
                "tool_progress" -> null
                "tool_interrupted" -> R.string.connector_tool_unconfirmed
                "tool_not_started" -> R.string.connector_tool_not_started
                else -> R.string.connector_tool_used
            }
            return AgentRunUiEvent(
                id = id,
                kind = "tool",
                stage = normalizedStage,
                text = displayName,
                detail = detail,
                timestampMillis = timestampMillis,
                toolCallId = callId,
                previewId = previewId,
                toolDisplayName = displayName,
                toolStatusResourceId = statusResource,
            )
        }
    }
}

data class AgentRunUiSnapshot(
    val runId: String? = null,
    val sessionId: String? = null,
    val goal: String = "",
    val running: Boolean = false,
    val outcome: String? = null,
    val events: List<AgentRunUiEvent> = emptyList(),
    val compacting: Boolean = false,
    val compactionStatus: String? = null,
    val reflecting: Boolean = false,
    val reflectionSessionId: String? = null,
    val reflectionStatus: String? = null,
    val reflectionLastSuccessMillis: Long = 0L,
)


data class RunHistoryItem(
    val id: String,
    val goal: String,
    val outcome: String,
    val steps: Int,
    val turns: Int,
    val timestampSeconds: Double,
    val sessionId: String = id,
    val title: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

/** Bridges the foreground service's existing AgentLoop progress callback into a Compose-observable StateFlow. */
object AgentRunUiState {
    private const val MAX_EVENTS = 300
    private var nextEventId = 1L
    private val lock = Any()
    private val pendingProgress = mutableListOf<AgentRunUiEvent>()
    private val _state = MutableStateFlow(AgentRunUiSnapshot())
    val state: StateFlow<AgentRunUiSnapshot> = _state.asStateFlow()

    @JvmStatic
    fun beginRun(sessionId: String, goal: String) = beginRun(sessionId, goal, emptyList())

    @JvmStatic
    fun beginRun(sessionId: String, goal: String, attachments: List<ChatAttachment>) = synchronized(lock) {
        val visibleAttachments = if (isManagedSystemConversation(sessionId)) emptyList() else attachments
        val current = _state.value
        val sameTurnAlreadyStarted = current.sessionId == sessionId && current.running && current.goal == goal
        if (!sameTurnAlreadyStarted) pendingProgress.clear()
        val events = if (current.sessionId == sessionId) {
            if (sameTurnAlreadyStarted) current.events else append(current.events, event("user", goal).copyAttachments(visibleAttachments))
        } else listOf(event("user", goal).copyAttachments(visibleAttachments))
        _state.value = current.copy(
            sessionId = sessionId,
            goal = goal,
            running = true,
            outcome = null,
            events = events,
            compacting = false,
            compactionStatus = null,
        )
    }

    /** Bind the optimistic row once persistence returns its identity; never match reactions by text. */
    @JvmStatic
    fun bindCurrentUserMessage(sessionId: String, messageId: String) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId || messageId.isBlank()) return@synchronized
        val index = current.events.indexOfLast { it.kind == "user" }
        if (index < 0 || (current.events[index].messageId.isNotEmpty() && current.events[index].messageId != messageId)) return@synchronized
        val bound = current.events.mapIndexed { i, event ->
            if (i == index && event.messageId.isEmpty()) event.copy(messageId = messageId) else event
        }
        // Hydration can race the optimistic row. Deduplicate by durable identity, never by text.
        val seenUserIds = mutableSetOf<String>()
        _state.value = current.copy(events = bound.filter { event ->
            event.kind != "user" || event.messageId.isBlank() || seenUserIds.add(event.messageId)
        })
    }

    @JvmStatic
    fun messageReactionChanged(sessionId: String, messageId: String, emoji: String) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        _state.value = current.copy(events = current.events.map { event ->
            if (event.kind == "user" && event.messageId == messageId) event.copyReaction(emoji) else event
        })
    }

    @JvmStatic
    fun beginRegenerationRun(sessionId: String, goal: String) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = current.copy(
            sessionId = sessionId,
            goal = goal,
            running = true,
            outcome = null,
            compacting = false,
            compactionStatus = null,
        )
    }

    @JvmStatic
    fun restoreSession(sessionId: String, events: List<AgentRunUiEvent>) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        nextEventId = maxOf(nextEventId, (events.maxOfOrNull { it.id } ?: 0L) + 1L)
        _state.value = AgentRunUiSnapshot(
            sessionId = sessionId,
            goal = events.lastOrNull { it.kind == "user" }?.text.orEmpty(),
            events = events,
            reflecting = current.reflecting,
            reflectionSessionId = current.reflectionSessionId,
            reflectionStatus = current.reflectionStatus,
            reflectionLastSuccessMillis = current.reflectionLastSuccessMillis,
        )
    }

    @JvmStatic
    fun refreshPersistedSession(sessionId: String, persistedEvents: List<AgentRunUiEvent>) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val durableKinds = setOf("user", "assistant", "assistant_translation", "compaction", "compaction_error", "memory", "crew_mission", "tool", "user_decision", "generated_image", "delivered_file")
        val liveInFlightToolIds = if (current.running) current.events.asSequence()
            .filter { it.kind == "tool" && it.stage in setOf("tool_call", "tool_progress", "approval_waiting") }
            .mapNotNull { it.toolCallId }.toSet() else emptySet()
        // A durable missing-result observation is not a terminal outcome while this process still
        // owns that same in-flight call. Actual persisted results always supersede the live row.
        val authoritativePersisted = persistedEvents.filterNot { event ->
            event.kind == "tool" && event.stage in setOf("tool_interrupted", "tool_not_started")
                && event.toolCallId in liveInFlightToolIds
        }
        val persistedToolCallIds = authoritativePersisted.asSequence().filter { it.kind == "tool" }
            .mapNotNull { it.toolCallId }.toSet()
        val livePendingDecisionIds = if (current.running) current.events.asSequence()
            .filter { it.kind == "user_decision" && it.decisionStatus == "PENDING" }
            .mapNotNull { it.decisionId }.toSet() else emptySet()
        val transientEvents = if (current.running) current.events.filter { event ->
            event.kind !in durableKinds || event.kind == "tool"
                    && (event.toolCallId.isNullOrBlank() || event.toolCallId !in persistedToolCallIds)
                    || event.kind == "user_decision" && event.decisionId in livePendingDecisionIds
                    || event.kind == "generated_image" && persistedEvents.none {
                        it.kind == "generated_image" && it.generatedImagePath == event.generatedImagePath
                    }
                    || event.kind == "delivered_file" && persistedEvents.none {
                        it.kind == "delivered_file" && it.deliveredArtifact?.id == event.deliveredArtifact?.id
                    }
        } else emptyList()
        val durableEvents = authoritativePersisted.filterNot { event ->
            event.kind == "user_decision" && event.decisionId in livePendingDecisionIds
        }
        nextEventId = maxOf(nextEventId, (persistedEvents.maxOfOrNull { it.id } ?: 0L) + 1L)
        _state.value = current.copy(
            goal = persistedEvents.lastOrNull { it.kind == "user" }?.text ?: current.goal,
            events = durableEvents + transientEvents,
        )
    }

    @JvmStatic
    fun crewMissionChanged(sessionId: String, snapshot: CrewMissionSnapshot) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val events = current.events.toMutableList()
        val index = events.indexOfLast { it.kind == "crew_mission"
            && it.crewMissionSnapshot?.missionId == snapshot.missionId }
        val updated = AgentRunUiEvent.crewMissionEvent(
            if (index >= 0) events[index].id else nextEventId++, snapshot)
        if (index >= 0) {
            events[index] = updated
            _state.value = current.copy(events = events)
        } else {
            _state.value = current.copy(events = append(events, updated))
        }
    }

    @JvmStatic
    fun deliveredFileAdded(sessionId: String, artifact: ChatAttachment) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        if (current.events.any { it.kind == "delivered_file" && it.deliveredArtifact?.id == artifact.id }) return@synchronized
        _state.value = current.copy(events = append(current.events,
            AgentRunUiEvent.deliveredFileEvent(nextEventId++, artifact, System.currentTimeMillis())))
    }

    @JvmStatic
    fun generatedImageAdded(sessionId: String, image: AgentRunUiEvent) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId || image.kind != "generated_image") return@synchronized
        val visibleImage = image.copy(id = nextEventId++)
        val events = current.events.filterNot {
            it.kind == "generated_image" && it.generatedImagePath == image.generatedImagePath
        }
        _state.value = current.copy(events = append(events, visibleImage))
    }

    @JvmStatic
    fun resetSession(sessionId: String) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = AgentRunUiSnapshot(sessionId = sessionId, reflecting = current.reflecting,
            reflectionSessionId = current.reflectionSessionId, reflectionStatus = current.reflectionStatus,
            reflectionLastSuccessMillis = current.reflectionLastSuccessMillis)
    }

    @JvmStatic
    fun compactionStarted(sessionId: String, message: String) = synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(sessionId = sessionId, compacting = true, compactionStatus = message)
    }

    @JvmStatic
    fun compactionFinished(sessionId: String, summary: String, summarizedMessages: Int, mode: String) = synchronized(lock) {
        val current = _state.value
        val compactEvent = AgentRunUiEvent.compactionEvent(nextEventId++, summary, summarizedMessages, mode, System.currentTimeMillis())
        _state.value = current.copy(sessionId = sessionId, compacting = false, compactionStatus = null,
            events = if (current.sessionId == sessionId) append(current.events, compactEvent) else listOf(compactEvent))
    }

    @JvmStatic
    fun compactionFailed(sessionId: String, message: String) = synchronized(lock) {
        val current = _state.value
        val warning = event("compaction_error", message)
        _state.value = current.copy(sessionId = sessionId, compacting = false, compactionStatus = null,
            events = if (current.sessionId == sessionId) append(current.events, warning) else listOf(warning))
    }

    @JvmStatic
    fun compactionCancelled(sessionId: String) = synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(sessionId = sessionId, compacting = false, compactionStatus = null)
    }

    @JvmStatic
    fun reflectionStarted(sessionId: String, status: String) = synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(reflecting = true, reflectionSessionId = sessionId, reflectionStatus = status)
    }

    @JvmStatic
    fun reflectionFinished(sessionId: String, reflectionId: String?, summary: String,
                           revisionIds: List<Long>, status: String, successful: Boolean) = synchronized(lock) {
        val current = _state.value
        val matching = current.sessionId == sessionId
        val event = if (!reflectionId.isNullOrBlank() && revisionIds.isNotEmpty())
            AgentRunUiEvent.reflectionMemoryEvent(nextEventId++, summary, reflectionId, revisionIds, status, System.currentTimeMillis())
        else null
        _state.value = current.copy(
            reflecting = false,
            reflectionSessionId = sessionId,
            reflectionStatus = status,
            reflectionLastSuccessMillis = if (successful)
                System.currentTimeMillis() else current.reflectionLastSuccessMillis,
            events = when {
                event == null -> current.events
                matching -> append(current.events, event)
                else -> current.events
            },
        )
    }

    @JvmStatic
    fun reflectionUndone(sessionId: String, reflectionId: String) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        _state.value = current.copy(events = current.events.map { event ->
            if (event.memoryReflectionGroupId == reflectionId) event.copy(detail = "undone") else event
        })
    }

    @JvmStatic
    fun reflectionCancelled(sessionId: String, status: String) = synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(reflecting = false, reflectionSessionId = sessionId, reflectionStatus = status)
    }

    @JvmStatic
    fun onProgress(stage: String, message: String) = synchronized(lock) {
        val current = _state.value
        if (!current.running) return@synchronized
        if (stage == "thinking") return@synchronized
        val kind = if (stage == "tool_call" || stage == "tool_result" || stage == "tool_error") "tool" else "progress"
        val progress = when (stage) {
            "tool_call" -> AgentRunUiEvent.toolEvent(nextEventId++, stage, message.trim(), null, null, null,
                System.currentTimeMillis())
            "tool_result", "tool_error" -> {
                val separator = message.indexOf(" · ")
                val name = if (separator >= 0) message.substring(0, separator).trim() else message.trim()
                val detail = if (separator >= 0) message.substring(separator + 3).trim() else ""
                AgentRunUiEvent.toolEvent(nextEventId++, stage, name, detail.takeIf(String::isNotEmpty),
                    null, null, System.currentTimeMillis())
            }
            else -> event(kind, message, stage)
        }
        if (kind == "tool") {
            val events = pendingProgress.fold(current.events) { accumulated, buffered -> append(accumulated, buffered) }
            pendingProgress.clear()
            _state.value = current.copy(events = append(events, progress))
        } else {
            pendingProgress.add(progress)
        }
    }

    @JvmStatic
    fun onToolProgress(stage: String, callId: String, displayName: String, detail: String?) =
        onToolProgress(stage, callId, displayName, detail, null)

    @JvmStatic
    fun onToolProgress(stage: String, callId: String, displayName: String,
                       detail: String?, previewId: String?) =
        onToolProgress(stage, callId, displayName, detail, previewId, null)

    @JvmStatic
    fun onToolProgress(stage: String, callId: String, displayName: String,
                       detail: String?, previewId: String?, auditDetail: String?) = synchronized(lock) {
        val current = _state.value
        if (!current.running) return@synchronized
        val events = pendingProgress.fold(current.events) { accumulated, buffered -> append(accumulated, buffered) }.toMutableList()
        pendingProgress.clear()
        val existingIndex = events.indexOfLast { it.kind == "tool" && it.toolCallId == callId }
        val updated = AgentRunUiEvent.toolEvent(nextEventId++, stage, displayName, detail, callId, previewId,
            System.currentTimeMillis()).copy(toolAuditDetail = auditDetail ?: events.getOrNull(existingIndex)?.toolAuditDetail)
        if (stage != "tool_call" && existingIndex >= 0) {
            events[existingIndex] = updated.copy(id = events[existingIndex].id)
        } else {
            events.add(updated)
        }
        _state.value = current.copy(events = events.takeLast(MAX_EVENTS))
    }

    @JvmStatic
    fun memoryChanged(revision: MemoryStore.Revision) = synchronized(lock) {
        val current = _state.value
        if (!current.running || current.sessionId != revision.conversationId
            || revision.actor == MemoryStore.Actor.USER || revision.actor == MemoryStore.Actor.REFLECTION) return@synchronized
        val change = AgentRunUiEvent(
            id = nextEventId++,
            kind = "memory",
            stage = revision.operation,
            text = revision.path,
            memoryRevisionId = revision.id,
            memoryPath = revision.path,
            memoryBefore = MemoryUiLogic.shortContent(revision.previousContent, 320),
            memoryAfter = MemoryUiLogic.shortContent(revision.newContent, 320),
        )
        _state.value = current.copy(events = append(current.events, change))
    }

    @JvmStatic
    fun showApproval(
        id: String,
        title: String,
        lines: List<String>,
        permission: String?,
        activityIntent: com.jarvys.agent.connectors.ApprovalIntentSpec? = null,
        permissionDeniedIntent: com.jarvys.agent.connectors.ApprovalIntentSpec? = null,
        allowAlwaysAvailable: Boolean = false,
        autonomyConnectorId: String? = null,
        autonomyOperationName: String? = null,
        localizedTitle: com.jarvys.agent.connectors.ConnectorUiText? = null,
        localizedLines: List<com.jarvys.agent.connectors.ConnectorUiText>? = null,
        permissionLabel: com.jarvys.agent.connectors.ConnectorUiText? = null,
        compactSummary: com.jarvys.agent.connectors.ConnectorUiText? = null,
        requester: String? = null,
        requesterColorKey: String? = null,
    ) = synchronized(lock) {
        val current = _state.value
        if (!current.running || current.events.any { it.approvalId == id }) return@synchronized
        val events = current.events.toMutableList()
        val pendingToolIndex = events.indexOfLast { it.kind == "tool" && it.stage == "tool_call" }
        if (pendingToolIndex >= 0) {
            val tool = events[pendingToolIndex]
            events[pendingToolIndex] = tool.copy(
                stage = "approval_waiting",
                toolStatusResourceId = com.jarvys.agent.R.string.connector_tool_waiting_for_approval,
            )
        }
        val card = AgentRunUiEvent(
            id = nextEventId++, kind = "approval", stage = "PENDING", text = title,
            approvalId = id, approvalLines = lines.toList(), approvalStatus = "PENDING",
            approvalPermission = permission, approvalIntent = activityIntent,
            approvalPermissionDeniedIntent = permissionDeniedIntent,
            approvalAllowAlwaysAvailable = allowAlwaysAvailable,
            approvalAutonomyConnectorId = autonomyConnectorId,
            approvalAutonomyOperationName = autonomyOperationName,
            approvalLocalizedTitle = localizedTitle,
            approvalLocalizedLines = localizedLines,
            approvalPermissionLabel = permissionLabel,
            approvalCompactSummary = compactSummary,
            approvalRequester = requester,
            approvalRequesterColorKey = requesterColorKey,
        )
        _state.value = current.copy(events = append(events, card))
    }

    @JvmStatic
    fun updateApproval(id: String, status: String) = synchronized(lock) {
        val current = _state.value
        val index = current.events.indexOfLast { it.kind == "approval" && it.approvalId == id }
        if (index < 0) return@synchronized
        val events = current.events.toMutableList()
        events[index] = events[index].copy(stage = status, approvalStatus = status)
        _state.value = current.copy(events = events)
    }

    @JvmStatic
    fun markProactiveRepliesUsed(messageId: String) = synchronized(lock) {
        val current = _state.value
        val index = current.events.indexOfLast { it.kind == "assistant" && it.messageId == messageId }
        if (index < 0) return@synchronized
        val updated = current.events.toMutableList()
        updated[index] = updated[index].copy(proactiveRepliesUsed = true)
        _state.value = current.copy(events = updated)
    }

    @JvmStatic
    fun updateApprovalDetail(id: String, detail: String) = synchronized(lock) {
        val current = _state.value
        val index = current.events.indexOfLast { it.kind == "approval" && it.approvalId == id }
        if (index < 0) return@synchronized
        val events = current.events.toMutableList()
        events[index] = events[index].copy(approvalStatusDetail = detail, approvalStatusDetailText = null)
        _state.value = current.copy(events = events)
    }

    @JvmStatic
    fun updateApprovalDetail(id: String, detail: com.jarvys.agent.connectors.ConnectorUiText) = synchronized(lock) {
        val current = _state.value
        val index = current.events.indexOfLast { it.kind == "approval" && it.approvalId == id }
        if (index < 0) return@synchronized
        val events = current.events.toMutableList()
        events[index] = events[index].copy(approvalStatusDetail = detail.fallback, approvalStatusDetailText = detail)
        _state.value = current.copy(events = events)
    }

    @JvmStatic
    fun showUserDecision(sessionId: String, id: String, title: String, body: String,
                         options: List<UserDecisionOption>, allowDismiss: Boolean) = synchronized(lock) {
        val current = _state.value
        if (!current.running || current.sessionId != sessionId
            || current.events.any { it.kind == "user_decision" && it.decisionId == id }) return@synchronized
        val events = current.events.toMutableList()
        val pendingToolIndex = events.indexOfLast { it.kind == "tool" && it.stage == "tool_call" }
        if (pendingToolIndex >= 0) events[pendingToolIndex] = events[pendingToolIndex].copy(
            stage = "decision_waiting", toolStatusResourceId = R.string.user_decision_tool_waiting)
        events.add(AgentRunUiEvent(
            id = nextEventId++, kind = "user_decision", stage = "PENDING", text = title,
            decisionId = id, decisionBody = body, decisionOptions = options.toList(),
            decisionAllowDismiss = allowDismiss, decisionStatus = "PENDING",
        ))
        _state.value = current.copy(events = append(events.dropLast(1), events.last()))
    }

    @JvmStatic
    fun updateUserDecision(sessionId: String, id: String, status: String,
                           optionId: String? = null, optionLabel: String? = null) = synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val index = current.events.indexOfLast { it.kind == "user_decision" && it.decisionId == id }
        if (index < 0 || current.events[index].decisionStatus != "PENDING") return@synchronized
        val events = current.events.toMutableList()
        events[index] = events[index].copy(stage = status, decisionStatus = status,
            decisionOptionId = optionId, decisionOptionLabel = optionLabel)
        _state.value = current.copy(events = events)
    }

    @JvmStatic
    fun complete(runId: String, outcome: String, summary: String) = synchronized(lock) {
        complete(runId, outcome, summary, "", 0L)
    }

    @JvmStatic
    fun complete(runId: String, outcome: String, summary: String, messageId: String,
                 durationMs: Long) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        val assistant = event("assistant", summary).copy(
            stage = AgentRunUiEvent.assistantStageForOutcome(outcome), messageId = messageId, durationMs = durationMs)
        _state.value = current.copy(
            runId = runId,
            running = false,
            outcome = outcome,
            compacting = false,
            compactionStatus = null,
            events = append(current.events, assistant),
        )
    }

    @JvmStatic
    fun completeChatTurn(sessionId: String, turnId: String, answer: String) = synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(
            runId = turnId,
            sessionId = sessionId,
            running = false,
            outcome = "COMPLETED",
            compacting = false,
            compactionStatus = null,
            events = append(current.events, event("assistant", answer)),
        )
    }

    @JvmStatic
    fun fail(message: String) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = current.copy(
            running = false,
            outcome = "FAILED",
            compacting = false,
            compactionStatus = null,
            events = append(current.events, event("result", message, "FAILED")),
        )
    }

    @JvmStatic
    fun fail(sessionId: String, message: String) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = current.copy(
            sessionId = sessionId,
            running = false,
            outcome = "FAILED",
            compacting = false,
            compactionStatus = null,
            events = append(current.events, event("result", message, "FAILED")),
        )
    }

    @JvmStatic
    fun failChatTurn(sessionId: String, message: String) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = current.copy(
            sessionId = sessionId,
            running = false,
            outcome = "FAILED",
            compacting = false,
            compactionStatus = null,
            events = append(current.events, event("assistant", message, "FAILED")),
        )
    }

    private fun append(existing: List<AgentRunUiEvent>, item: AgentRunUiEvent): List<AgentRunUiEvent> {
        val result = existing + item
        val durableKinds = setOf("user", "assistant", "compaction", "compaction_error", "memory", "crew_mission", "user_decision", "generated_image", "delivered_file")
        // Pending approvals are live requests with blocked workers behind them; evicting one
        // would orphan the ApprovalGate latch. Keep all pending cards even when history is busy.
        fun evictable(event: AgentRunUiEvent) = event.kind !in durableKinds
                && !(event.kind == "approval" && event.approvalStatus == "PENDING")
                && !(event.kind == "user_decision" && event.decisionStatus == "PENDING")
        val overflow = result.count(::evictable) - MAX_EVENTS
        if (overflow <= 0) return result
        var remaining = overflow
        return result.filter { event ->
            if (remaining > 0 && evictable(event)) {
                remaining--
                false
            } else true
        }
    }

    private fun event(
        kind: String,
        text: String,
        stage: String? = null,
        detail: String? = null,
        toolCallId: String? = null,
        previewId: String? = null,
    ) = AgentRunUiEvent(
        id = nextEventId++,
        kind = kind,
        stage = stage,
        text = text,
        detail = detail,
        toolCallId = toolCallId,
        previewId = previewId,
    )
}
