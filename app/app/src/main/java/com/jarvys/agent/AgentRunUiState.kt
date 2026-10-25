package com.jarvys.agent

import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.tasks.ScheduledTaskConversation
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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
    /** Source identity for durable conversation inputs; null for assistant/tool presentation events. */
    val conversationInputOrigin: ConversationInput.Origin? = null,
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
    val presentationArtifact: PresentationArtifact? = null,
    /** Compatibility projection used by the existing download/share controls. */
    val deliveredArtifact: ChatAttachment? = null,
    val toolAuditDetail: String? = null,
    val reactionEmoji: String = "",
    val previewIsCurrent: Boolean = false,
    val toolActivity: ToolActivity? = null,
    val toolActivityEvents: List<ToolActivity> = emptyList(),
    /** Ledger-scoped identity for a persisted tool call (batch/call or turn/execution). */
    val toolIdentity: String? = null,
    /** User message that owns this persisted tool event. */
    val toolTurnMessageId: String? = null,
    val submissionError: String? = null,
    val scheduledTaskArtifact: ScheduledTaskArtifact? = null,
    /** In-memory row identity retained while a transient assistant stream is promoted to a durable answer. */
    val timelineKey: Long? = null,
    /** User row that owns this transient stream, when the conversation has one. */
    val streamUserEventId: Long? = null,
    /** System-origin workspace wake replies have no user prompt to regenerate. */
    val canRegenerate: Boolean = true,
    /** Discreet attribution used only for bot messages projected into a multi-bot workspace chat. */
    val workspaceSenderLabel: String? = null,
 ) {
    /** Parsed once per immutable event instance; a data-class copy gets a fresh analysis. */
    internal val visibilityDetailAnalysis: AgentRunUiEventVisibilityDetail by lazy {
        AgentRunUiEventVisibilityDetail.parse(this)
    }

    fun copyMetadata(messageId: String, durationMs: Long): AgentRunUiEvent =
        copy(messageId = messageId, durationMs = durationMs)
    fun copyReaction(emoji: String): AgentRunUiEvent =
        copy(reactionEmoji = if (kind == "user" && proactiveThreadKey.isNullOrEmpty()) emoji else "")
    fun copyToolPresentation(auditDetail: String?, currentPreview: Boolean): AgentRunUiEvent =
        copy(toolAuditDetail = auditDetail?.takeIf(String::isNotBlank), previewIsCurrent = currentPreview)
    fun copyToolTimelineIdentity(identity: String?, userMessageId: String?): AgentRunUiEvent =
        copy(toolIdentity = identity?.takeIf(String::isNotBlank),
            toolTurnMessageId = userMessageId?.takeIf(String::isNotBlank))
    fun copyStage(stage: String?): AgentRunUiEvent = copy(stage = stage)
    fun copyCanRegenerate(value: Boolean): AgentRunUiEvent = copy(canRegenerate = value)
    fun copyAttachments(attachments: List<ChatAttachment>): AgentRunUiEvent =
        copy(attachments = if (kind == "user" && proactiveThreadKey.isNullOrEmpty()) attachments.toList() else emptyList())

    companion object {
        @JvmStatic
        fun activityEvent(id: Long, activity: ToolActivity): AgentRunUiEvent = toolEvent(id, activity.stage,
            activity.displayName, activity.detail.takeIf { it.isNotBlank() }, activity.executionId,
            activity.previewId.takeIf { it.isNotBlank() }, activity.timestampMillis).copy(toolActivity = activity,
            toolActivityEvents = listOf(activity), toolAuditDetail = activity.auditDetail.takeIf { it.isNotBlank() })

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
        fun artifactEvent(id: Long, artifact: PresentationArtifact, timestampMillis: Long,
                          deliveredFile: ChatAttachment? = null): AgentRunUiEvent =
            AgentRunUiEvent(id = id, kind = "artifact", text = artifact.title?.takeIf(String::isNotBlank) ?: artifact.name,
                timestampMillis = timestampMillis, presentationArtifact = artifact, deliveredArtifact = deliveredFile)

        @JvmStatic
        fun deliveredFileEvent(id: Long, artifact: ChatAttachment, timestampMillis: Long): AgentRunUiEvent =
            artifactEvent(id, PresentationArtifact(
                id = artifact.id,
                artifactType = artifact.mimeType,
                moduleId = null,
                name = artifact.name,
                title = artifact.name,
                mimeType = artifact.mimeType,
                sizeBytes = artifact.sizeBytes,
                relativePath = artifact.relativePath,
                sha256 = null,
                manifestJson = null,
                thumbnailRef = null,
                storageKind = artifact.kind.name,
            ), timestampMillis, deliveredFile = artifact)

        @JvmStatic
        fun generatedImageEvent(id: Long, relativePath: String?, prompt: String, revisedPrompt: String?,
                                mimeType: String, size: String?, status: String, error: String?,
                                timestampMillis: Long): AgentRunUiEvent = AgentRunUiEvent(
            id = id, kind = "artifact", stage = status, text = prompt,
            timestampMillis = timestampMillis, generatedImagePath = relativePath,
            generatedImagePrompt = prompt, generatedImageRevisedPrompt = revisedPrompt,
            generatedImageMimeType = mimeType, generatedImageSize = size,
            generatedImageStatus = status, generatedImageError = error,
            presentationArtifact = PresentationArtifact(
                id = relativePath?.let { "generated:$it" } ?: "generated:$timestampMillis",
                artifactType = mimeType,
                moduleId = null,
                name = relativePath?.substringAfterLast('/') ?: "artifact",
                title = prompt.takeIf(String::isNotBlank),
                mimeType = mimeType,
                sizeBytes = 0L,
                relativePath = relativePath,
                sha256 = null,
                manifestJson = null,
                thumbnailRef = null,
                storageKind = "IMAGE",
            ),
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
                "tool_result" -> "tool_result"
                else -> "tool_interrupted"
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

internal data class AgentRunUiEventVisibilityDetail(
    val deliveredArtifactId: String? = null,
    val generatedImageId: String? = null,
    val scheduledTaskArtifact: ScheduledTaskArtifact? = null,
) {
    companion object {
        @Volatile
        internal var parseObserver: (() -> Unit)? = null

        private val generatedImageReference = Regex("\\bgenerated:([0-9a-fA-F-]{36})\\b")

        fun parse(event: AgentRunUiEvent): AgentRunUiEventVisibilityDetail {
            if (event.kind != "tool" || event.stage != "tool_result") return AgentRunUiEventVisibilityDetail()
            parseObserver?.invoke()
            val detail = event.detail.orEmpty()
            val startsWithJsonObject = detail.trimStart().startsWith('{')
            val exactJson = if (startsWithJsonObject) runCatching { JSONObject(detail) }.getOrNull() else null
            val start = detail.indexOf('{')
            val end = detail.lastIndexOf('}')
            val json = exactJson ?: if (start >= 0 && end >= start) {
                runCatching { JSONObject(detail.substring(start, end + 1)) }.getOrNull()
            } else null

            val taskArtifact = runCatching {
                val task = json?.optJSONObject("task") ?: return@runCatching null
                if (task.optString("kind") != "scheduled_task") return@runCatching null
                val id = task.optString("id").takeIf(String::isNotBlank) ?: return@runCatching null
                val name = task.optString("name").takeIf(String::isNotBlank) ?: return@runCatching null
                val schedule = task.optString("schedule_human").takeIf(String::isNotBlank)
                    ?: return@runCatching null
                ScheduledTaskArtifact(id, name, schedule,
                    task.optString("next_run_local").takeIf { it.isNotBlank() && it != "null" },
                    task.optString("status", json?.optString("status").orEmpty()))
            }.getOrNull()
            val deliveredArtifactId = if (startsWithJsonObject) {
                exactJson?.optString("artifact_id")?.takeIf { it.startsWith("delivered:") }
                    ?.removePrefix("delivered:")?.takeIf(String::isNotBlank)
            } else null
            val generatedImageId = generatedImageReference.find(detail)?.groupValues?.getOrNull(1)
                ?.takeIf(String::isNotBlank)
            return AgentRunUiEventVisibilityDetail(deliveredArtifactId, generatedImageId, taskArtifact)
        }
    }
}

/** Visible timeline events are already published as an immutable snapshot for Compose consumers. */
data class AgentRunUiTimelineEvents(
    val sessionId: String? = null,
    val events: List<AgentRunUiEvent> = emptyList(),
)

/** Control projection and compact event summaries used outside the conversation timeline. */
data class AgentRunUiRenderState(
    val snapshot: AgentRunUiSnapshot = AgentRunUiSnapshot(),
    val hasUserAttachments: Boolean = false,
    val conversationMessageCount: Int = 0,
    val crewMissions: List<CrewMissionSnapshot> = emptyList(),
)

/** Typed contract projected from successful task management tool results into the chat timeline. */
data class ScheduledTaskArtifact(
    val taskId: String,
    val name: String,
    val scheduleHuman: String,
    val nextRunLocal: String?,
    val status: String,
)

/** Transient current-run presentation evidence; never reconstructed from persisted rows. */
data class LiveMascotTool(
    val executionId: String,
    val eligibleToAnimate: Boolean,
)

/** Activity-owned eligibility evidence for the mounted main-chat header renderer. */
data class HeaderMascotAvailability(
    val sessionId: String? = null,
    val visible: Boolean = false,
    val resumed: Boolean = false,
    val reducedMotion: Boolean = false,
    val availableAnimationIds: Set<String> = emptySet(),
) {
    fun failure(animationId: String): String? = when {
        sessionId.isNullOrBlank() || !visible -> "The main chat mascot is not visible."
        !resumed -> "The main chat mascot is not active."
        reducedMotion -> "Mascot animation is unavailable while reduced motion is enabled."
        animationId !in availableAnimationIds -> "The $animationId mascot animation is unavailable in the current animation package."
        else -> null
    }
}

/** Monotonic identity lets Compose consume a service-thread request exactly once. */
data class HeaderMascotAnimationRequest(
    val requestId: String,
    val sessionId: String,
    val animationId: String,
    val executionId: String? = null,
)

/** Factual result reported by the UI for one requested playback. */
data class HeaderMascotAnimationResult(
    val animationId: String,
    val outcome: String,
    val reason: String? = null,
    val requestId: String? = null,
)

data class AgentRunUiSnapshot(
    val runId: String? = null,
    val sessionId: String? = null,
    val goal: String = "",
    val running: Boolean = false,
    val stopping: Boolean = false,
    val interactiveOwnerSessionId: String? = null,
    val outcome: String? = null,
    val events: List<AgentRunUiEvent> = emptyList(),
    val compacting: Boolean = false,
    val compactionStatus: String? = null,
    val reflecting: Boolean = false,
    val reflectionSessionId: String? = null,
    val reflectionStatus: String? = null,
    val reflectionLastSuccessMillis: Long = 0L,
    val liveMascotTool: LiveMascotTool? = null,
    // Current process/generation evidence only, never inferred from restored timeline rows.
    val liveModelWaitId: Long? = null,
    val liveUserWaitId: String? = null,
    val liveRunIdentity: String? = null,
    val headerMascotAvailability: HeaderMascotAvailability = HeaderMascotAvailability(),
    val headerMascotAnimationRequest: HeaderMascotAnimationRequest? = null,
    val headerMascotAnimationResult: HeaderMascotAnimationResult? = null,
    /** Compact event-derived state used by the root UI projection; null on full compatibility snapshots. */
    val presenceSummary: AgentRunUiPresenceSummary? = null,
)

data class AgentRunUiPresenceSummary(
    val hasPendingApproval: Boolean = false,
    val hasPendingDecision: Boolean = false,
    val hasFailedOutput: Boolean = false,
    val hasCompletedOutput: Boolean = false,
    val lastEventIsToolCall: Boolean = false,
    val currentToolName: String? = null,
) {
    companion object {
        fun from(events: List<AgentRunUiEvent>): AgentRunUiPresenceSummary {
            var hasPendingApproval = false
            var hasPendingDecision = false
            var latestOutputStage: String? = null
            var lastEventIsToolCall = false
            var currentToolName: String? = null
            events.forEach { event ->
                if (event.kind == "approval" && event.approvalStatus == "PENDING") hasPendingApproval = true
                if (event.kind == "user_decision" && event.decisionStatus == "PENDING") hasPendingDecision = true
                if (event.kind == "assistant" || event.kind == "result") latestOutputStage = event.stage
                lastEventIsToolCall = event.kind == "tool" && event.stage == "tool_call"
                currentToolName = if (lastEventIsToolCall) event.toolDisplayName ?: event.text else null
            }
            return AgentRunUiPresenceSummary(
                hasPendingApproval, hasPendingDecision, latestOutputStage == "FAILED",
                latestOutputStage == "COMPLETED", lastEventIsToolCall, currentToolName,
            )
        }
    }
}


/** Proactive and scheduled-task threads are written by the app, never by the user's own messages. */
internal fun isManagedSystemConversation(sessionId: String): Boolean =
    sessionId == ProactiveConversation.SESSION_ID || sessionId == ScheduledTaskConversation.SESSION_ID

/** Bridges the foreground service's existing AgentLoop progress callback into a Compose-observable StateFlow. */
object AgentRunUiState {
    private data class RunOwnership(
        var sessionId: String? = null,
        var generation: Long = Long.MIN_VALUE,
        var active: Boolean = false,
        var liveSnapshot: AgentRunUiSnapshot? = null,
        var systemOriginGeneration: Long? = null,
        var internalInputGeneration: Long? = null,
        var stoppingGeneration: Long? = null,
        var stoppedCompletion: StoppedCompletion? = null,
    )

    private data class StoppedCompletion(
        val runId: String,
        val outcome: String,
        val text: String,
        val messageId: String,
        val durationMs: Long,
        val canRegenerate: Boolean,
    )

    // A thread carries only its target session. The snapshot itself always lives in
    // sessionSnapshots, so a long-running run cannot write an old thread-local copy back.
    private val workingSession = ThreadLocal<String?>()
    private val approvalSessionContext = ThreadLocal<String?>()
    private val sessionSnapshots = mutableMapOf<String, AgentRunUiSnapshot>()
    private val sessionOwners = mutableMapOf<String, RunOwnership>()
    private val sessionProgress = mutableMapOf<String, MutableList<AgentRunUiEvent>>()
    /** In-process-only Codex text rows. These never enter a Room timeline or checkpoint. */
    private val assistantTextStreams = mutableMapOf<String, AgentRunUiEvent>()
    private var nextAssistantTextStreamId = -1L
    private var visibleSessionId: String? = null
    private val publishedState = MutableStateFlow(AgentRunUiSnapshot())
    private val publishedTimelineEvents = MutableStateFlow(AgentRunUiTimelineEvents())
    /** Per-session immutable timeline snapshots avoid routing through the currently visible session. */
    private val publishedTimelineEventsBySession = ConcurrentHashMap<String, MutableStateFlow<AgentRunUiTimelineEvents>>()
    private val publishedRenderState = MutableStateFlow(AgentRunUiRenderState())
    private var summarizedSessionId: String? = null
    private var summarizedEvents: List<AgentRunUiEvent>? = null
    private var summarizedHasUserAttachments = false
    private var summarizedConversationMessageCount = 0
    private var summarizedCrewMissions: List<CrewMissionSnapshot> = emptyList()
    private var summarizedCrewMissionEvents: List<AgentRunUiEvent> = emptyList()
    private var summarizedPresence = AgentRunUiPresenceSummary()

    private fun publishTimelineEventsForSessionLocked(snapshot: AgentRunUiSnapshot) {
        val sessionId = snapshot.sessionId ?: return
        val timeline = AgentRunUiTimelineEvents(sessionId, snapshot.events)
        publishedTimelineEventsBySession.computeIfAbsent(sessionId) {
            MutableStateFlow(AgentRunUiTimelineEvents(sessionId))
        }.value = timeline
    }

    private fun storeSessionSnapshotLocked(sessionId: String, snapshot: AgentRunUiSnapshot) {
        sessionSnapshots[sessionId] = snapshot
        if (visibleSessionId != sessionId) publishTimelineEventsForSessionLocked(snapshot)
    }

    private fun publishVisibleSnapshotLocked(snapshot: AgentRunUiSnapshot) {
        publishedState.value = snapshot
        publishedTimelineEvents.value = AgentRunUiTimelineEvents(snapshot.sessionId, snapshot.events)
        publishTimelineEventsForSessionLocked(snapshot)
        if (summarizedSessionId != snapshot.sessionId || summarizedEvents !== snapshot.events) {
            summarizedSessionId = snapshot.sessionId
            summarizedEvents = snapshot.events
            var hasUserAttachments = false
            var conversationMessageCount = 0
            val crewMissions = mutableListOf<CrewMissionSnapshot>()
            val crewMissionEvents = mutableListOf<AgentRunUiEvent>()
            snapshot.events.forEach { event ->
                if (event.kind == "user" && event.attachments.isNotEmpty()) hasUserAttachments = true
                if (event.kind == "user" || event.kind == "assistant") conversationMessageCount++
                event.crewMissionSnapshot?.let {
                    crewMissions.add(it)
                    crewMissionEvents.add(event)
                }
            }
            summarizedHasUserAttachments = hasUserAttachments
            summarizedConversationMessageCount = conversationMessageCount
            summarizedCrewMissions = crewMissions.toList()
            summarizedCrewMissionEvents = crewMissionEvents.toList()
            summarizedPresence = AgentRunUiPresenceSummary.from(snapshot.events)
        }
        publishedRenderState.value = AgentRunUiRenderState(
            snapshot = snapshot.copy(events = summarizedCrewMissionEvents, presenceSummary = summarizedPresence),
            hasUserAttachments = summarizedHasUserAttachments,
            conversationMessageCount = summarizedConversationMessageCount,
            crewMissions = summarizedCrewMissions,
        )
    }

    private val _state = object {
        var value: AgentRunUiSnapshot
            get() {
                val sessionId = workingSession.get() ?: return publishedState.value
                return synchronized(lock) {
                    sessionSnapshots[sessionId] ?: AgentRunUiSnapshot(sessionId = sessionId)
                }
            }
            set(value) {
                val sessionId = workingSession.get()
                if (sessionId != null) synchronized(lock) {
                    storeSessionSnapshotLocked(sessionId, value)
                    sessionOwners[sessionId]?.takeIf {
                        it.active && it.sessionId == sessionId && value.running
                    }?.liveSnapshot = value
                    if (visibleSessionId == sessionId) publishVisibleSnapshotLocked(value)
                    else refreshVisibleInteractiveOwnerLocked()
                }
                else {
                    synchronized(lock) {
                        value.sessionId?.let { storeSessionSnapshotLocked(it, value) }
                        if (visibleSessionId == null) visibleSessionId = value.sessionId
                        publishVisibleSnapshotLocked(value)
                        refreshVisibleInteractiveOwnerLocked()
                    }
                }
            }
    }

    private fun currentSessionId(): String? = workingSession.get() ?: _state.value.sessionId
    private fun owner(): RunOwnership = sessionOwners.getOrPut(currentSessionId().orEmpty()) { RunOwnership() }
    private var activeGeneration: Long
        get() = owner().generation
        set(value) { owner().generation = value }
    private var ownedSessionId: String?
        get() = owner().sessionId
        set(value) { owner().sessionId = value }
    private var ownedRunActive: Boolean
        get() = owner().active
        set(value) { owner().active = value }
    private var ownedLiveSnapshot: AgentRunUiSnapshot?
        get() = owner().liveSnapshot
        set(value) { owner().liveSnapshot = value }
    private val pendingProgress: MutableList<AgentRunUiEvent>
        get() = sessionProgress.getOrPut(currentSessionId().orEmpty()) { mutableListOf() }

    private fun <T> inSession(sessionId: String, block: () -> T): T {
        val existing = workingSession.get()
        if (existing == sessionId) return block()
        workingSession.set(sessionId)
        return try {
            block()
        } finally {
            // Restore the session context even when nested session work or publication throws.
            if (existing == null) workingSession.remove() else workingSession.set(existing)
            synchronized(lock) {
                // Keep legacy behavior where entering a session establishes its empty snapshot,
                // while always using the latest shared value.
                val latest = sessionSnapshots.getOrPut(sessionId) { AgentRunUiSnapshot(sessionId = sessionId) }
                storeSessionSnapshotLocked(sessionId, latest)
                if (visibleSessionId == sessionId) publishVisibleSnapshotLocked(latest)
            }
        }
    }

    private fun activeInteractiveOwnerSessionIdLocked(preferredSessionId: String? = visibleSessionId): String? {
        val activeOwners = sessionOwners.entries
            .filter { (sessionId, ownership) -> ownership.active && ownership.sessionId == sessionId }
            .map { it.key }
        return when (activeOwners.size) {
            0 -> null
            1 -> activeOwners.single()
            else -> preferredSessionId?.takeIf { it in activeOwners }
        }
    }

    private fun refreshVisibleInteractiveOwnerLocked() {
        val visibleSession = visibleSessionId ?: return
        val current = publishedState.value
        val ownerSessionId = activeInteractiveOwnerSessionIdLocked(visibleSession)
        if (current.interactiveOwnerSessionId == ownerSessionId) return
        val projected = current.copy(interactiveOwnerSessionId = ownerSessionId)
        storeSessionSnapshotLocked(visibleSession, projected)
        publishVisibleSnapshotLocked(projected)
    }

    private fun clearSupersededOwnershipLocked(sessionId: String) {
        val current = sessionSnapshots[sessionId] ?: sessionOwners[sessionId]?.liveSnapshot ?: return
        val cleared = current.copy(
            interactiveOwnerSessionId = null,
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            liveRunIdentity = null,
        )
        storeSessionSnapshotLocked(sessionId, cleared)
        if (visibleSessionId == sessionId) publishVisibleSnapshotLocked(cleared)
    }

    /** Selects which session the compatibility StateFlow projects for Compose. */
    @JvmStatic
    fun showSession(sessionId: String) = synchronized(lock) {
        publishedState.value.sessionId?.let { storeSessionSnapshotLocked(it, publishedState.value) }
        visibleSessionId = sessionId
        val selected = (sessionSnapshots[sessionId] ?: AgentRunUiSnapshot(sessionId = sessionId))
            .copy(interactiveOwnerSessionId = activeInteractiveOwnerSessionIdLocked(sessionId))
        storeSessionSnapshotLocked(sessionId, selected)
        publishVisibleSnapshotLocked(selected)
    }

    @JvmStatic
    fun snapshotForSession(sessionId: String): AgentRunUiSnapshot = synchronized(lock) {
        if (visibleSessionId == sessionId) publishedState.value else sessionSnapshots[sessionId]
            ?: AgentRunUiSnapshot(sessionId = sessionId)
    }

    /** Events for a selected conversation, guarded against a stale compatibility snapshot. */
    @JvmStatic
    fun eventsForSession(sessionId: String): List<AgentRunUiEvent> = synchronized(lock) {
        snapshotForSession(sessionId).takeIf { it.sessionId == sessionId }?.events.orEmpty()
    }

    /**
     * Immutable per-session timeline stream for Compose routes. It keeps the requested
     * conversation available while the compatibility state flow is projecting another session.
     */
    @JvmStatic
    fun timelineEventsForSession(sessionId: String): StateFlow<AgentRunUiTimelineEvents> =
        publishedTimelineEventsBySession.computeIfAbsent(sessionId) {
            MutableStateFlow(AgentRunUiTimelineEvents(sessionId))
        }.asStateFlow()

    @JvmStatic
    fun containsEvent(sessionId: String, event: AgentRunUiEvent): Boolean = synchronized(lock) {
        eventsForSession(sessionId).any {
            it.id == event.id && it.kind == event.kind && it.messageId == event.messageId
        }
    }

    @JvmStatic
    fun isRunning(sessionId: String): Boolean = snapshotForSession(sessionId).running

    private fun preserveOwnedSnapshot() {
        if (ownedRunActive && _state.value.sessionId == ownedSessionId) ownedLiveSnapshot = _state.value
    }

    private fun sessionForApproval(id: String): String? = synchronized(lock) {
        sessionForApprovalLocked(id)
    }

    private fun sessionForApprovalLocked(id: String): String? {
        return publishedState.value.takeIf { snapshot -> snapshot.events.any { it.approvalId == id } }
            ?.sessionId
            ?: sessionSnapshots.entries.firstOrNull { (_, snapshot) ->
                snapshot.events.any { it.approvalId == id }
            }?.key
            ?: sessionOwners.entries.firstOrNull { (_, ownership) ->
                ownership.liveSnapshot?.events?.any { it.approvalId == id } == true
            }?.key
    }

    private fun sessionForGenerationLocked(generation: Long): String? = sessionOwners.entries
        .filter { (sessionId, ownership) -> ownership.sessionId == sessionId && ownership.generation == generation }
        .map { it.key }
        .singleOrNull()

    /** Legacy approvals use the sole active owner, or the visible owner when several runs exist. */
    private fun legacyApprovalSessionLocked(): String? {
        val activeOwners = sessionOwners.entries
            .filter { (sessionId, ownership) -> ownership.active && ownership.sessionId == sessionId }
            .map { it.key }
        val visible = visibleSessionId
        return when (activeOwners.size) {
            0 -> workingSession.get() ?: visible
            1 -> activeOwners.single()
            else -> visible?.takeIf { it in activeOwners }
        }
    }

    private fun fallbackForUnknownGenerationLocked(): String? {
        workingSession.get()?.let { return it }
        val visible = visibleSessionId ?: return null
        val hasActiveOwner = sessionOwners.any { (sessionId, ownership) ->
            ownership.active && ownership.sessionId == sessionId
        }
        return visible.takeUnless { hasActiveOwner }
    }

    private fun withApprovalSession(generation: Long? = null, action: (String) -> Unit) {
        val sessionId = synchronized(lock) {
            if (generation == null) (approvalSessionContext.get() ?: legacyApprovalSessionLocked())
            else sessionForGenerationLocked(generation) ?: fallbackForUnknownGenerationLocked()
        } ?: return
        val previousContext = approvalSessionContext.get()
        approvalSessionContext.set(sessionId)
        try {
            if (workingSession.get() == sessionId) action(sessionId)
            else inSession(sessionId) { action(sessionId) }
        } finally {
            if (previousContext == null) approvalSessionContext.remove()
            else approvalSessionContext.set(previousContext)
        }
    }

    private fun sessionForMessage(messageId: String): String? = synchronized(lock) {
        publishedState.value.takeIf { snapshot -> snapshot.events.any { it.messageId == messageId } }
            ?.sessionId
            ?: sessionSnapshots.entries.firstOrNull { (_, snapshot) ->
                snapshot.events.any { it.messageId == messageId }
            }?.key
    }

    private fun publishOwnedSnapshot(snapshot: AgentRunUiSnapshot) {
        if (_state.value.sessionId == snapshot.sessionId) _state.value = snapshot
        else ownedLiveSnapshot = snapshot
    }

    private const val MAX_EVENTS = 300
    private var nextEventId = 1L
    private val lock = Any()
    private data class PendingHeaderMascotAnimation(
        val request: HeaderMascotAnimationRequest,
        val completion: CompletableFuture<HeaderMascotAnimationResult> = CompletableFuture(),
        var waiters: Int = 0,
    )
    private data class HeaderMascotAdmission(
        val pending: PendingHeaderMascotAnimation? = null,
        val rejection: HeaderMascotAnimationResult? = null,
    )
    private val pendingHeaderMascotAnimations = mutableMapOf<String, PendingHeaderMascotAnimation>()
    val state: StateFlow<AgentRunUiSnapshot> = publishedState.asStateFlow()
    /** Control state for screen chrome; ordinary timeline updates do not recompose the app root. */
    val renderState: StateFlow<AgentRunUiRenderState> = publishedRenderState.asStateFlow()
    /** The selected conversation's immutable timeline snapshot for chat-route consumers. */
    val timelineEvents: StateFlow<AgentRunUiTimelineEvents> = publishedTimelineEvents.asStateFlow()

    @JvmStatic
    fun beginRun(sessionId: String, goal: String) { beginRunInternal(sessionId, goal, emptyList(), false, false) }

    @JvmStatic
    fun beginRun(sessionId: String, goal: String, attachments: List<ChatAttachment>) {
        beginRunInternal(sessionId, goal, attachments, false, false)
    }

    /** Starts a user submission and returns the exact optimistic row identity for later binding/failure. */
    @JvmStatic
    fun beginChatSubmission(sessionId: String, goal: String, attachments: List<ChatAttachment>): Long =
        requireNotNull(beginRunInternal(sessionId, goal, attachments, true, true))

    private fun beginRunInternal(
        sessionId: String,
        goal: String,
        attachments: List<ChatAttachment>,
        appendUserForSameTurn: Boolean,
        preserveActiveRun: Boolean,
    ): Long? = run {
        if (workingSession.get() == null) showSession(sessionId)
        inSession(sessionId) { synchronized(lock) {
        val runStillActive = preserveActiveRun && owner().active && owner().sessionId == sessionId
        val previousStopPending = preserveActiveRun && owner().stoppingGeneration != null
        val preservePreviousRunState = runStillActive || previousStopPending
        if (!preservePreviousRunState) {
            owner().stoppingGeneration = null
            owner().stoppedCompletion = null
        }
        val visibleAttachments = if (isManagedSystemConversation(sessionId)) emptyList() else attachments
        val current = _state.value
        val sameTurnAlreadyStarted = current.sessionId == sessionId && current.running && current.goal == goal
        val appendUser = !sameTurnAlreadyStarted || appendUserForSameTurn
        current.headerMascotAnimationRequest?.let { request ->
            if (request.sessionId != sessionId) resolveHeaderMascotAnimationLocked(request, "rejected", "The chat session changed.")
            else if (appendUser) resolveHeaderMascotAnimationLocked(request, "rejected", "The animation request was cleared when a new run started.")
        }
        if (appendUser && !preservePreviousRunState) pendingProgress.clear()
        val optimisticUserEvent = if (appendUser) event("user", goal).copyAttachments(visibleAttachments) else null
        val events = if (current.sessionId == sessionId) {
            optimisticUserEvent?.let { append(current.events, it) } ?: current.events
        } else listOf(checkNotNull(optimisticUserEvent))
        _state.value = current.copy(
            sessionId = sessionId,
            goal = goal,
            running = !previousStopPending,
            stopping = previousStopPending || (runStillActive && current.stopping),
            outcome = if (preservePreviousRunState) current.outcome else null,
            liveMascotTool = if (runStillActive) current.liveMascotTool else null,
            liveModelWaitId = if (runStillActive) current.liveModelWaitId else null,
            liveUserWaitId = if (runStillActive) current.liveUserWaitId else null,
            liveRunIdentity = if (runStillActive) current.liveRunIdentity else null,
            headerMascotAnimationRequest = if (appendUser) null else current.headerMascotAnimationRequest,
            headerMascotAnimationResult = if (appendUser) null else _state.value.headerMascotAnimationResult,
            events = events,
            compacting = if (runStillActive) current.compacting else false,
            compactionStatus = if (runStillActive) current.compactionStatus else null,
        )
        optimisticUserEvent?.id
        } }
    }

    /** Bind the optimistic row once persistence returns its identity; never match reactions by text. */
    @JvmStatic
    fun bindCurrentUserMessage(sessionId: String, messageId: String) =
        bindCurrentUserMessageInternal(sessionId, messageId, null)

    @JvmStatic
    fun bindCurrentUserMessage(sessionId: String, messageId: String, optimisticEventId: Long) =
        bindCurrentUserMessageInternal(sessionId, messageId, optimisticEventId)

    private fun bindCurrentUserMessageInternal(sessionId: String, messageId: String, optimisticEventId: Long?) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId || messageId.isBlank()) return@synchronized
        val index = if (optimisticEventId != null) {
            current.events.indexOfLast { it.kind == "user" && it.id == optimisticEventId }
        } else {
            current.events.indexOfLast { it.kind == "user" && it.messageId.isBlank() }
                .takeIf { it >= 0 }
                ?: current.events.indexOfLast { it.kind == "user" && it.messageId == messageId }
        }
        if (index < 0 || (current.events[index].messageId.isNotEmpty() && current.events[index].messageId != messageId)) return@synchronized
        val bound = current.events.mapIndexed { i, event ->
            if (i == index && event.messageId.isEmpty()) event.copy(messageId = messageId) else event
        }
        // Hydration can race the optimistic row. Deduplicate by durable identity, never by text.
        val seenUserIds = mutableSetOf<String>()
        _state.value = current.copy(events = bound.filter { event ->
            event.kind != "user" || event.messageId.isBlank() || seenUserIds.add(event.messageId)
        })
    } }

    /** Marks only the identified optimistic user row failed; this error is rendered in that row. */
    @JvmStatic
    fun failOptimisticUserMessage(sessionId: String, optimisticEventId: Long, message: String) =
        inSession(sessionId) { synchronized(lock) {
            val current = _state.value
            var found = false
            val events = current.events.map { event ->
                if (event.id == optimisticEventId && event.kind == "user" && event.messageId.isBlank()) {
                    found = true
                    event.copy(submissionError = message)
                } else event
            }
            if (!found) return@synchronized
            val runStillActive = owner().active && owner().sessionId == sessionId
            val previousStopPending = owner().stoppingGeneration != null && current.stopping
            if (!runStillActive && !previousStopPending) pendingProgress.clear()
            val anotherOptimisticSubmission = events.any {
                it.kind == "user" && it.messageId.isBlank() && it.submissionError == null
            }
            val remainsRunning = runStillActive || anotherOptimisticSubmission
            _state.value = current.copy(
                running = remainsRunning && !previousStopPending,
                stopping = previousStopPending || (remainsRunning && current.stopping),
                outcome = if (runStillActive || anotherOptimisticSubmission || previousStopPending) current.outcome else "FAILED",
                liveMascotTool = if (runStillActive) current.liveMascotTool else null,
                liveModelWaitId = if (runStillActive) current.liveModelWaitId else null,
                liveUserWaitId = if (runStillActive) current.liveUserWaitId else null,
                liveRunIdentity = if (runStillActive) current.liveRunIdentity else null,
                compacting = if (runStillActive) current.compacting else false,
                compactionStatus = if (runStillActive) current.compactionStatus else null,
                events = events,
            )
        } }

    @JvmStatic
    fun messageReactionChanged(sessionId: String, messageId: String, emoji: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        _state.value = current.copy(events = current.events.map { event ->
            if (event.kind == "user" && event.messageId == messageId) event.copyReaction(emoji) else event
        })
    } }

    @JvmStatic
    fun beginRegenerationRun(sessionId: String, goal: String) = run {
        if (workingSession.get() == null) showSession(sessionId)
        inSession(sessionId) { synchronized(lock) {
        owner().stoppingGeneration = null
        owner().stoppedCompletion = null
        pendingProgress.clear()
        val current = _state.value
        current.headerMascotAnimationRequest?.let { request ->
            val reason = if (request.sessionId != sessionId) "The chat session changed."
            else "The animation request was cleared when a new run started."
            resolveHeaderMascotAnimationLocked(request, "rejected", reason)
        }
        _state.value = current.copy(
            sessionId = sessionId,
            goal = goal,
            running = true,
            stopping = false,
            outcome = null,
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            liveRunIdentity = null,
            headerMascotAnimationRequest = null,
            headerMascotAnimationResult = null,
            compacting = false,
            compactionStatus = null,
        )
        } }
    }

    @JvmStatic
    fun restoreSession(sessionId: String, events: List<AgentRunUiEvent>) {
        synchronized(lock) { showSession(sessionId) }
        inSession(sessionId) { synchronized(lock) {
        preserveOwnedSnapshot()
        if (ownedRunActive && ownedSessionId == sessionId && ownedLiveSnapshot != null) {
            _state.value = ownedLiveSnapshot!!.copy(running = true, interactiveOwnerSessionId = ownedSessionId)
            refreshPersistedSession(sessionId, events)
            return@synchronized
        }
        _state.value.headerMascotAnimationRequest?.let { request ->
            if (request.sessionId != sessionId) resolveHeaderMascotAnimationLocked(request, "rejected", "The chat session changed.")
            else resolveHeaderMascotAnimationLocked(request, "rejected", "The animation request was cleared while the session was restored.")
        }
        pendingProgress.clear()
        val current = _state.value
        nextEventId = maxOf(nextEventId, (events.maxOfOrNull { it.id } ?: 0L) + 1L)
        _state.value = AgentRunUiSnapshot(
            sessionId = sessionId,
            running = ownedRunActive && ownedSessionId == sessionId,
            interactiveOwnerSessionId = activeInteractiveOwnerSessionIdLocked(sessionId),
            goal = events.lastOrNull { it.kind == "user" }?.text.orEmpty(),
            events = events,
            reflecting = current.reflecting,
            reflectionSessionId = current.reflectionSessionId,
            reflectionStatus = current.reflectionStatus,
            reflectionLastSuccessMillis = current.reflectionLastSuccessMillis,
        )
        } }
    }

    @JvmStatic
    fun refreshPersistedSession(sessionId: String, persistedEvents: List<AgentRunUiEvent>) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val durableKinds = setOf("user", "assistant", "assistant_translation", "compaction", "compaction_error", "memory", "crew_mission", "tool", "user_decision", "artifact")
        val liveInFlightTools = if (current.running) current.events.mapNotNull { event ->
            if (event.kind == "tool" && event.stage in setOf("tool_call", "tool_progress", "approval_waiting"))
                event.toolCallId?.let { event to refreshToolIdentity(event) }
            else null
        } else emptyList()
        val liveInFlightToolIdentities = liveInFlightTools.mapNotNullTo(mutableSetOf()) { it.second }
        val liveInFlightToolCallCounts = liveInFlightTools.asSequence().mapNotNull { it.first.toolCallId }
            .groupingBy { it }.eachCount()
        // A durable missing-result observation is not a terminal outcome while this process still
        // owns that same in-flight call. Actual persisted results always supersede the live row.
        val authoritativePersisted = persistedEvents.mapNotNull { event ->
            val uncertainTool = event.kind == "tool"
                && event.stage in setOf("tool_interrupted", "tool_not_started")
            val identity = if (uncertainTool) refreshToolIdentity(event) else null
            val matchedLiveTool = identity?.let { it in liveInFlightToolIdentities }
                ?: (event.toolCallId != null && liveInFlightToolCallCounts[event.toolCallId] == 1)
            if (uncertainTool && matchedLiveTool) null else event
        }
        val streamPromotions = assistantProgressPromotions(current, authoritativePersisted)
        val persistedToolIdentities = authoritativePersisted.mapNotNullTo(mutableSetOf()) { event ->
            if (event.kind == "tool") refreshToolIdentity(event) else null
        }
        val persistedToolCallCounts = authoritativePersisted.asSequence().filter { it.kind == "tool" }
            .mapNotNull { it.toolCallId }.groupingBy { it }.eachCount()
        val livePendingDecisionIds = if (current.running) current.events.asSequence()
            .filter { it.kind == "user_decision" && it.decisionStatus == "PENDING" }
            .mapNotNull { it.decisionId }.toSet() else emptySet()
        val transientEvents = if (current.running) current.events.mapNotNull { event ->
            val persistedToolMatch = if (event.kind == "tool") {
                val identity = refreshToolIdentity(event)
                when {
                    identity != null -> identity in persistedToolIdentities
                    event.toolCallId.isNullOrBlank() -> false
                    else -> persistedToolCallCounts[event.toolCallId] == 1
                }
            } else false
            val isTransient =
            event.id !in streamPromotions.streamIds && (event.kind !in durableKinds || event.kind == "tool"
                    && !persistedToolMatch
                    || event.kind == "user_decision" && event.decisionId in livePendingDecisionIds
                    || event.kind == "artifact" && event.presentationArtifact?.isGeneratedImage == true && persistedEvents.none {
                        it.kind == "artifact" && it.presentationArtifact?.isGeneratedImage == true &&
                            it.generatedImagePath == event.generatedImagePath
                    }
                    || event.kind == "artifact" && event.presentationArtifact?.isGeneratedImage != true && persistedEvents.none {
                        it.kind == "artifact" && it.presentationArtifact?.id == event.presentationArtifact?.id
                    })
            if (isTransient) event else null
        } else emptyList()
        val retainedAssistantTimelineKeys = current.events.asSequence()
            .filter { it.kind == "assistant" && it.timelineKey != null }
            .mapNotNull { event -> refreshEventIdentity(event)?.let { it to event.timelineKey } }
            .toMap()
        val durableEvents = authoritativePersisted.filterNot { event ->
            event.kind == "user_decision" && event.decisionId in livePendingDecisionIds
        }.map { event ->
            if (event.kind != "assistant") event
            else (retainedAssistantTimelineKeys[refreshEventIdentity(event)]
                ?: streamPromotions.timelineKeys[refreshEventIdentity(event)])
                ?.let { timelineKey -> event.copy(timelineKey = timelineKey) } ?: event
        }
        val durableEventIdentities = durableEvents.mapNotNullTo(mutableSetOf<String>()) {
            refreshEventIdentity(it)
        }
        val firstPersistedId = durableEvents.minOfOrNull { it.id } ?: Long.MAX_VALUE
        val transientEventIds = transientEvents.mapTo(mutableSetOf()) { it.id }
        val olderLoadedEvents = current.events.filter { event ->
            val identity = refreshEventIdentity(event)
            val replacedLiveTool = event.kind == "tool"
                && event.stage in setOf("tool_call", "tool_progress", "approval_waiting")
                && refreshToolIdentity(event) == null
                && !event.toolCallId.isNullOrBlank()
                && persistedToolCallCounts[event.toolCallId] == 1
            event.id !in transientEventIds
                && !replacedLiveTool
                && (identity == null || identity !in durableEventIdentities)
                && event.id < firstPersistedId && event.kind in durableKinds
        }
        val retainedRunningTimelineEvents = if (current.running) current.events.filter { event ->
            event.timelineKey != null
                && event.id >= firstPersistedId
                && event.id !in transientEventIds
                && (refreshEventIdentity(event) == null || refreshEventIdentity(event) !in durableEventIdentities)
        } else emptyList()
        val mergedDurableEvents = (olderLoadedEvents + durableEvents + retainedRunningTimelineEvents).distinctBy { it.id }
        nextEventId = maxOf(nextEventId, ((mergedDurableEvents + transientEvents).maxOfOrNull { it.id } ?: 0L) + 1L)
        val usedIds = mergedDurableEvents.mapTo(mutableSetOf()) { it.id }
        val uniqueTransient = transientEvents.map { live ->
            if (usedIds.add(live.id)) live else live.copy(id = nextEventId++).also { usedIds.add(it.id) }
        }
        _state.value = current.copy(
            goal = persistedEvents.lastOrNull { it.kind == "user" }?.text ?: current.goal,
            events = mergedDurableEvents + uniqueTransient,
            liveMascotTool = current.liveMascotTool?.takeUnless { live ->
                authoritativePersisted.any { event ->
                    event.kind == "tool" && event.toolCallId == live.executionId
                        && event.stage in setOf("tool_result", "tool_error", "tool_not_started", "tool_interrupted")
                }
            },
        )
    } }

    /** Prepends a durable older window while preserving this session's live and cached events. */
    @JvmStatic
    fun prependPersistedSession(
        sessionId: String,
        olderEvents: List<AgentRunUiEvent>,
        newerHiddenAssistantMessageIds: Set<String> = emptySet(),
        newerHiddenTranslationMessageIds: Set<String> = emptySet(),
        newerTranslationMessageIds: Set<String> = emptySet(),
    ) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId || olderEvents.isEmpty()) return@synchronized
        val existingIds = current.events.mapTo(mutableSetOf()) { it.id }
        val existingMessageKeys = current.events.mapNotNullTo(mutableSetOf()) { event ->
            val identity = when (event.kind) {
                "user", "assistant" -> event.messageId
                "assistant_translation" -> event.detail
                else -> null
            }
            identity?.takeIf(String::isNotBlank)?.let { "${event.kind}:$it" }
        }
        val dispatchedUserMessageIds = current.events.asSequence()
            .filter { it.kind == "user" && it.stage != "QUEUED" }
            .mapNotNull { it.messageId.takeIf(String::isNotBlank) }
            .toSet()
        val uniqueOlder = olderEvents.filterNot { event ->
            // Page events already account for markers in their own ledger slice. These sets
            // contain only markers from newer loaded slices and therefore suppress older rows.
            event.kind == "assistant" && event.messageId in newerHiddenAssistantMessageIds
                || event.kind == "assistant_translation" && event.detail != null
                && (event.detail in newerHiddenTranslationMessageIds || event.detail in newerTranslationMessageIds)
                || event.kind == "user" && event.stage == "QUEUED" && event.messageId.startsWith("pending-")
                && event.messageId.removePrefix("pending-") in dispatchedUserMessageIds
                || timelineEventIdentity(event)?.let { it in existingMessageKeys } == true
        }.filter { existingIds.add(it.id) }
        if (uniqueOlder.isEmpty()) return@synchronized
        nextEventId = maxOf(nextEventId, (uniqueOlder.maxOfOrNull { it.id } ?: 0L) + 1L)
        _state.value = current.copy(events = uniqueOlder + current.events)
    } }

    private fun timelineEventIdentity(event: AgentRunUiEvent): String? {
        val identity = when (event.kind) {
            "user", "assistant" -> event.messageId
            "assistant_translation" -> event.detail
            else -> null
        }?.takeIf(String::isNotBlank)?.takeUnless {
            (event.kind == "user" || event.kind == "assistant") && it.startsWith("legacy-")
        } ?: return null
        return "${event.kind}:$identity"
    }

    private fun refreshEventIdentity(event: AgentRunUiEvent): String? {
        val identity = when (event.kind) {
            "user", "assistant" -> event.messageId.takeIf(String::isNotBlank)
                ?.takeUnless { it.startsWith("legacy-") }
            "assistant_translation" -> event.detail?.takeIf(String::isNotBlank)
            "tool" -> event.toolIdentity?.takeIf(String::isNotBlank)
            "user_decision" -> event.decisionId?.takeIf(String::isNotBlank)
            "artifact" -> event.presentationArtifact?.id?.takeIf(String::isNotBlank)
            "crew_mission" -> event.crewMissionSnapshot?.missionId?.takeIf(String::isNotBlank)
            "memory" -> event.memoryRevisionId?.toString()
                ?: event.memoryRevisionIds.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
            else -> null
        } ?: return null
        return "${event.kind}:$identity"
    }

    private data class AssistantProgressPromotions(
        val timelineKeys: Map<String, Long> = emptyMap(),
        val streamIds: Set<Long> = emptySet(),
    )

    /** Reconciles a persisted progress row that arrived through refresh before its service callback. */
    private fun assistantProgressPromotions(
        current: AgentRunUiSnapshot,
        persistedEvents: List<AgentRunUiEvent>,
    ): AssistantProgressPromotions {
        if (!current.running) return AssistantProgressPromotions()
        val progressEvents = persistedEvents.filter { it.kind == "assistant" && it.stage == "PROGRESS" }
        if (progressEvents.isEmpty()) return AssistantProgressPromotions()

        val timelineKeys = mutableMapOf<String, Long>()
        val promotedStreamIds = mutableSetOf<Long>()
        val matchedProgressIds = mutableSetOf<String>()
        current.events.filter { it.kind == "assistant_stream" }.forEach { stream ->
            val ownerMessageId = stream.streamUserEventId?.let { userEventId ->
                current.events.firstOrNull { it.kind == "user" && it.id == userEventId }
                    ?.messageId?.takeIf(String::isNotBlank)
            } ?: return@forEach
            val progress = progressEvents.asSequence().filter { event ->
                event.toolTurnMessageId == ownerMessageId
                    && event.timestampMillis >= stream.timestampMillis
                    && event.messageId !in matchedProgressIds
            }.maxWithOrNull(compareBy<AgentRunUiEvent>({ it.timestampMillis }, { it.id }))
                ?: return@forEach
            refreshEventIdentity(progress)?.let { identity ->
                timelineKeys[identity] = stream.timelineKey ?: stream.id
            }
            matchedProgressIds += progress.messageId
            promotedStreamIds += stream.id
        }
        return AssistantProgressPromotions(timelineKeys, promotedStreamIds)
    }

    private fun refreshToolIdentity(event: AgentRunUiEvent): String? {
        if (event.kind != "tool") return null
        event.toolIdentity?.takeIf(String::isNotBlank)?.let { return "tool:$it" }
        val executionId = event.toolActivity?.executionId
            ?: event.toolActivityEvents.firstOrNull()?.executionId
        return executionId?.takeIf(String::isNotBlank)?.let { "tool:execution:$it" }
    }

    /** Applies a durable translation to this materialized window without dropping older pages. */
    @JvmStatic
    fun assistantTranslationAdded(sessionId: String, translation: AgentRunUiEvent) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId || translation.kind != "assistant_translation" || translation.detail.isNullOrBlank())
            return@synchronized
        val anchor = current.events.indexOfLast { it.kind == "assistant" && it.messageId == translation.detail }
        if (anchor < 0) return@synchronized
        val events = current.events.filterNot {
            it.kind == "assistant_translation" && it.detail == translation.detail
        }.toMutableList()
        val adjustedAnchor = events.indexOfLast { it.kind == "assistant" && it.messageId == translation.detail }
        nextEventId = maxOf(nextEventId, (events.maxOfOrNull { it.id } ?: 0L) + 1L)
        events.add((adjustedAnchor + 1).coerceAtMost(events.size), translation.copy(id = nextEventId++))
        _state.value = current.copy(events = events)
    } }

    /** Removes the visible translation while retaining its append-only ledger marker. */
    @JvmStatic
    fun assistantTranslationHidden(sessionId: String, messageId: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val events = current.events.filterNot { it.kind == "assistant_translation" && it.detail == messageId }
        if (events.size != current.events.size) _state.value = current.copy(events = events)
    } }

    /** Removes an assistant message and its translation from the visible window. */
    @JvmStatic
    fun assistantMessageDeleted(sessionId: String, messageId: String, userMessageId: String? = null) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val events = current.events.filterNot {
            it.kind == "assistant" && it.messageId == messageId
                || it.kind == "assistant_translation" && it.detail == messageId
                || it.kind == "tool" && userMessageId != null && it.toolTurnMessageId == userMessageId
                || it.kind == "assistant" && it.stage == "PROGRESS"
                    && userMessageId != null && it.toolTurnMessageId == userMessageId
                || it.kind == "compaction"
        }
        if (events.size != current.events.size) _state.value = current.copy(events = events)
    } }

    /** Drops materialized durable pages after an edit, then installs the refreshed recent window. */
    @JvmStatic
    fun replacePersistedTimelineWindow(sessionId: String, persistedEvents: List<AgentRunUiEvent>) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val durableKinds = setOf("user", "assistant", "assistant_translation", "compaction", "compaction_error",
            "memory", "crew_mission", "tool", "user_decision", "artifact")
        val activeToolStages = setOf("tool_call", "tool_progress", "approval_waiting")
        val retainedAssistantTimelineKeys = current.events.asSequence()
            .filter { it.kind == "assistant" && it.timelineKey != null }
            .mapNotNull { event -> refreshEventIdentity(event)?.let { it to event.timelineKey } }
            .toMap()
        val keyedPersistedEvents = persistedEvents.map { event ->
            if (event.kind != "assistant") event
            else retainedAssistantTimelineKeys[refreshEventIdentity(event)]
                ?.let { timelineKey -> event.copy(timelineKey = timelineKey) } ?: event
        }
        val liveEvents = current.events.filter { event ->
            event.kind !in durableKinds
                || event.kind == "tool" && event.stage in activeToolStages
                || event.kind == "user_decision" && event.decisionStatus == "PENDING"
        }
        _state.value = current.copy(events = liveEvents)
        refreshPersistedSession(sessionId, keyedPersistedEvents)
    } }

    @JvmStatic
    fun crewMissionChanged(sessionId: String, snapshot: CrewMissionSnapshot) = inSession(sessionId) { synchronized(lock) {
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
    }

    @JvmStatic
    fun deliveredFileAdded(sessionId: String, artifact: ChatAttachment) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        if (current.events.any { it.kind == "artifact" && it.presentationArtifact?.id == artifact.id }) return@synchronized
        _state.value = current.copy(events = append(current.events,
            AgentRunUiEvent.deliveredFileEvent(nextEventId++, artifact, System.currentTimeMillis())))
    } }

    /** Publishes a delivered module artifact immediately while retaining the durable transfer row. */
    @JvmStatic
    fun moduleArtifactDelivered(
        sessionId: String,
        artifact: ChatAttachment,
        artifactType: String,
        moduleId: String,
        manifestJson: String,
        title: String?,
        thumbnailRef: String? = null,
    ) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        if (current.events.any { it.kind == "artifact" && it.presentationArtifact?.id == artifact.id }) return@synchronized
        val presentation = PresentationArtifact(
            id = artifact.id,
            artifactType = artifactType,
            moduleId = moduleId,
            name = artifact.name,
            title = title?.takeIf(String::isNotBlank) ?: artifact.name,
            mimeType = artifact.mimeType,
            sizeBytes = artifact.sizeBytes,
            relativePath = artifact.relativePath,
            sha256 = null,
            manifestJson = manifestJson,
            thumbnailRef = thumbnailRef,
            storageKind = artifact.kind.name,
        )
        val event = AgentRunUiEvent.artifactEvent(nextEventId++, presentation, System.currentTimeMillis(), artifact)
        _state.value = current.copy(events = append(current.events, event))
    } }

    @JvmStatic
    fun generatedImageAdded(sessionId: String, image: AgentRunUiEvent) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId || image.kind != "artifact" || image.presentationArtifact?.isGeneratedImage != true) return@synchronized
        val visibleImage = image.copy(id = nextEventId++)
        val events = current.events.filterNot {
            it.kind == "artifact" && it.presentationArtifact?.isGeneratedImage == true &&
                it.generatedImagePath == image.generatedImagePath
        }
        _state.value = current.copy(events = append(events, visibleImage))
    } }

    @JvmStatic
    fun resetSession(sessionId: String) = run {
        if (workingSession.get() == null) showSession(sessionId)
        inSession(sessionId) { synchronized(lock) {
        preserveOwnedSnapshot()
        pendingProgress.clear()
        val current = _state.value
        current.headerMascotAnimationRequest?.let { request ->
            val reason = if (request.sessionId != sessionId) "The chat session changed."
            else "The animation request was cleared with the session state."
            resolveHeaderMascotAnimationLocked(request, "rejected", reason)
        }
        _state.value = AgentRunUiSnapshot(sessionId = sessionId,
            interactiveOwnerSessionId = activeInteractiveOwnerSessionIdLocked(sessionId), reflecting = current.reflecting,
            reflectionSessionId = current.reflectionSessionId, reflectionStatus = current.reflectionStatus,
            reflectionLastSuccessMillis = current.reflectionLastSuccessMillis)
        } }
    }

    @JvmStatic
    fun compactionStarted(sessionId: String, message: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(sessionId = sessionId, compacting = true, compactionStatus = message,
            liveMascotTool = if (current.sessionId == sessionId)
                current.liveMascotTool?.copy(eligibleToAnimate = false) else null,
            liveModelWaitId = null, liveUserWaitId = null)
    } }

    @JvmStatic
    fun compactionFinished(sessionId: String, summary: String, summarizedMessages: Int, mode: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        val compactEvent = AgentRunUiEvent.compactionEvent(nextEventId++, summary, summarizedMessages, mode, System.currentTimeMillis())
        _state.value = current.copy(sessionId = sessionId, compacting = false, compactionStatus = null,
            events = if (current.sessionId == sessionId) append(current.events, compactEvent) else listOf(compactEvent))
    } }

    @JvmStatic
    fun compactionFailed(sessionId: String, message: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        val warning = event("compaction_error", message)
        _state.value = current.copy(sessionId = sessionId, compacting = false, compactionStatus = null,
            events = if (current.sessionId == sessionId) append(current.events, warning) else listOf(warning))
    } }

    @JvmStatic
    fun compactionCancelled(sessionId: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(sessionId = sessionId, compacting = false, compactionStatus = null)
    } }

    @JvmStatic
    fun reflectionStarted(sessionId: String, status: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(reflecting = true, reflectionSessionId = sessionId, reflectionStatus = status)
    } }

    @JvmStatic
    fun reflectionFinished(sessionId: String, reflectionId: String?, summary: String,
                           revisionIds: List<Long>, status: String, successful: Boolean) = inSession(sessionId) { synchronized(lock) {
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
    } }

    @JvmStatic
    fun reflectionUndone(sessionId: String, reflectionId: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        _state.value = current.copy(events = current.events.map { event ->
            if (event.memoryReflectionGroupId == reflectionId) event.copy(detail = "undone") else event
        })
    } }

    @JvmStatic
    fun reflectionCancelled(sessionId: String, status: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(reflecting = false, reflectionSessionId = sessionId, reflectionStatus = status)
    } }

    @JvmStatic
    fun bindGeneration(sessionId: String, generation: Long) = inSession(sessionId) { synchronized(lock) {
        val generationChanged = ownedSessionId != sessionId || activeGeneration != generation
        val executionId = "$sessionId:$generation"
        _state.value.headerMascotAnimationRequest?.takeIf { it.executionId != executionId }?.let { request ->
            val reason = if (request.sessionId != sessionId) "The chat session changed."
            else "The animation request belonged to a previous run."
            resolveHeaderMascotAnimationLocked(request, "rejected", reason)
        }
        if (generationChanged) ownedLiveSnapshot = null
        // A generation identifies one run; binding it again supersedes any stale owner that still carries it.
        sessionOwners.forEach { (otherSession, other) ->
            if (otherSession != sessionId && other.generation == generation) {
                clearSupersededOwnershipLocked(otherSession)
                other.generation = Long.MIN_VALUE
                other.active = false
                other.liveSnapshot = null
            }
        }
        ownedSessionId = sessionId
        activeGeneration = generation
        ownedRunActive = true
        _state.value = _state.value.copy(interactiveOwnerSessionId = sessionId,
            liveMascotTool = if (generationChanged) null else _state.value.liveMascotTool,
            liveModelWaitId = if (generationChanged) null else _state.value.liveModelWaitId,
            liveUserWaitId = if (generationChanged) null else _state.value.liveUserWaitId,
            liveRunIdentity = if (_state.value.sessionId == sessionId) "$sessionId:$generation" else null)
        if (_state.value.sessionId == sessionId) _state.value = _state.value.copy(running = true, outcome = null)
    } }

    @JvmStatic
    fun beginServiceRun(sessionId: String, goal: String, attachments: List<ChatAttachment>,
        regeneration: Boolean, generation: Long, userMessageId: String? = null) = inSession(sessionId) { synchronized(lock) {
        owner().systemOriginGeneration = null
        owner().internalInputGeneration = null
        _state.value.headerMascotAnimationRequest?.takeIf { _state.value.sessionId != sessionId }?.let { request ->
            resolveHeaderMascotAnimationLocked(request, "rejected", "The chat session changed.")
        }
        if (_state.value.sessionId == sessionId || _state.value.sessionId == null) {
            val current = _state.value
            val alreadyShownMessage = !regeneration && !userMessageId.isNullOrBlank()
                && current.events.any { it.kind == "user" && it.messageId == userMessageId }
            when {
                regeneration -> beginRegenerationRun(sessionId, goal)
                alreadyShownMessage -> _state.value = current.copy(
                    goal = goal,
                    running = true,
                    stopping = false,
                    outcome = null,
                )
                else -> beginRun(sessionId, goal, attachments)
            }
        }
        bindGeneration(sessionId, generation)
        if (_state.value.sessionId != sessionId) {
            ownedLiveSnapshot = AgentRunUiSnapshot(sessionId = sessionId, goal = goal, running = true,
                interactiveOwnerSessionId = sessionId, liveRunIdentity = "$sessionId:$generation",
                events = listOf(event("user", goal).copyMetadata(userMessageId.orEmpty(), 0).copyAttachments(attachments)))
        }
    } }

    /** Starts a system-origin run without appending a synthetic user bubble. */
    @JvmStatic
    fun beginSystemServiceRun(sessionId: String, generation: Long) = inSession(sessionId) { synchronized(lock) {
        owner().systemOriginGeneration = generation
        owner().internalInputGeneration = generation
        val current = _state.value
        if (current.sessionId == sessionId || current.sessionId == null) {
            _state.value = current.copy(sessionId = sessionId, goal = "", running = true,
                stopping = false, outcome = null, liveRunIdentity = "$sessionId:$generation")
        } else {
            ownedLiveSnapshot = AgentRunUiSnapshot(sessionId = sessionId, running = true,
                interactiveOwnerSessionId = sessionId, liveRunIdentity = "$sessionId:$generation")
        }
    } }

    /** Internal inputs can steer a user run without becoming a user regeneration target. */
    @JvmStatic
    fun markInternalConversationInput(sessionId: String, generation: Long) = inSession(sessionId) { synchronized(lock) {
        if (ownsGeneration(sessionId, generation)) owner().internalInputGeneration = generation
    } }

    @JvmStatic
    fun ownsGeneration(sessionId: String, generation: Long): Boolean = inSession(sessionId) { synchronized(lock) {
        _state.value.sessionId == sessionId && ownedRunActive && ownedSessionId == sessionId
            && activeGeneration == generation
    } }

    @JvmStatic
    fun withGeneration(sessionId: String, generation: Long, update: Runnable) = inSession(sessionId) { synchronized(lock) {
        if (ownedRunActive && ownsGeneration(sessionId, generation) && _state.value.running) update.run()
    } }

    /** Begins a clean transient answer buffer for one provider attempt in this agent run. */
    @JvmStatic
    fun assistantTextStreamStarted(sessionId: String, generation: Long, runId: String) =
        inSession(sessionId) { synchronized(lock) {
            if (!ownsAssistantStreamLocked(sessionId, generation)) return@synchronized
            val key = assistantTextStreamKey(sessionId, generation, runId)
            val current = _state.value
            val userEventId = current.events.lastOrNull { it.kind == "user" }?.id
            val event = assistantTextStreams[key]?.copy(text = "", timestampMillis = System.currentTimeMillis(),
                streamUserEventId = userEventId)
                ?: nextAssistantTextStreamId--.let { streamId ->
                    AgentRunUiEvent(id = streamId, kind = "assistant_stream", stage = "STREAMING",
                        text = "", messageId = runId, timelineKey = streamId, streamUserEventId = userEventId)
                }
            assistantTextStreams[key] = event
            _state.value = current.copy(events = current.events.filterNot { it.id == event.id })
        } }

    /** Appends only provider-classified output_text.delta text to transient Kotlin UI state. */
    @JvmStatic
    fun assistantTextStreamDelta(sessionId: String, generation: Long, runId: String, delta: String) =
        inSession(sessionId) { synchronized(lock) {
            if (delta.isEmpty() || !ownsAssistantStreamLocked(sessionId, generation)) return@synchronized
            val key = assistantTextStreamKey(sessionId, generation, runId)
            val prior = assistantTextStreams[key] ?: return@synchronized
            val updated = prior.copy(text = prior.text + delta, timestampMillis = System.currentTimeMillis())
            assistantTextStreams[key] = updated
            val current = _state.value
            val index = current.events.indexOfFirst { it.id == updated.id }
            val events = current.events.toMutableList()
            if (index < 0) events.add(updated) else events[index] = updated
            _state.value = current.copy(events = events)
        } }

    /** Removes any stream row that was not promoted into durable assistant progress. */
    @JvmStatic
    fun assistantTextStreamEnded(sessionId: String, generation: Long, runId: String) =
        inSession(sessionId) { synchronized(lock) {
            val key = assistantTextStreamKey(sessionId, generation, runId)
            val event = assistantTextStreams[key] ?: return@synchronized
            val current = _state.value
            _state.value = current.copy(events = current.events.filterNot { it.id == event.id })
            assistantTextStreams[key] = event.copy(text = "")
        } }

    /** Clears all transient answer events owned by a run, including cancellation and failure paths. */
    @JvmStatic
    fun clearAssistantTextStreams(sessionId: String, generation: Long) = inSession(sessionId) { synchronized(lock) {
        clearAssistantTextStreamsLocked(sessionId, generation)
    } }

    private fun ownsAssistantStreamLocked(sessionId: String, generation: Long): Boolean =
        ownedRunActive && ownedSessionId == sessionId && activeGeneration == generation
            && _state.value.sessionId == sessionId && _state.value.running

    private fun assistantTextStreamKey(sessionId: String, generation: Long, runId: String) =
        "$sessionId:$generation:$runId"

    private fun takeAssistantTextStreamsLocked(sessionId: String, generation: Long): List<AgentRunUiEvent> {
        val prefix = "$sessionId:$generation:"
        val keys = assistantTextStreams.keys.filter { it.startsWith(prefix) }
        return keys.mapNotNull { assistantTextStreams.remove(it) }
    }

    private fun clearAssistantTextStreamsLocked(sessionId: String, generation: Long) {
        val ids = takeAssistantTextStreamsLocked(sessionId, generation).mapTo(mutableSetOf()) { it.id }
        if (ids.isEmpty()) return
        val current = _state.value
        if (current.events.any { it.id in ids })
            _state.value = current.copy(events = current.events.filterNot { it.id in ids })
    }

    @JvmStatic
    fun rejectUnstarted(sessionId: String, events: List<AgentRunUiEvent>) = inSession(sessionId) { synchronized(lock) {
        if (_state.value.sessionId != sessionId) return@synchronized
        val stillOwnsRun = ownedRunActive && ownedSessionId == sessionId
        if (!stillOwnsRun) _state.value.headerMascotAnimationRequest?.let { request ->
            resolveHeaderMascotAnimationLocked(request, "rejected", "The agent run ended before the animation finished.")
        }
        _state.value = _state.value.copy(running = stillOwnsRun,
            outcome = if (stillOwnsRun) _state.value.outcome else "FAILED",
            liveMascotTool = if (stillOwnsRun) _state.value.liveMascotTool else null,
            liveModelWaitId = if (stillOwnsRun) _state.value.liveModelWaitId else null,
            liveUserWaitId = if (stillOwnsRun) _state.value.liveUserWaitId else null,
            liveRunIdentity = if (stillOwnsRun) _state.value.liveRunIdentity else null)
        refreshPersistedSession(sessionId, events)
    } }

    /** Resolves a queued submission stopped before it acquired a generation. */
    @JvmStatic
    fun stopUnstarted(sessionId: String, events: List<AgentRunUiEvent>) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val stillOwnsRun = ownedRunActive && ownedSessionId == sessionId
        val stopFinalizationPending = owner().stoppingGeneration != null
        if (!stillOwnsRun) _state.value.headerMascotAnimationRequest?.let { request ->
            resolveHeaderMascotAnimationLocked(request, "cancelled", null)
        }
        val latest = _state.value
        _state.value = latest.copy(
            running = stillOwnsRun,
            stopping = if (stillOwnsRun || stopFinalizationPending) latest.stopping else false,
            outcome = if (stillOwnsRun || stopFinalizationPending) latest.outcome else "STOPPED",
            liveMascotTool = if (stillOwnsRun) latest.liveMascotTool else null,
            liveModelWaitId = if (stillOwnsRun) latest.liveModelWaitId else null,
            liveUserWaitId = if (stillOwnsRun) latest.liveUserWaitId else null,
            liveRunIdentity = if (stillOwnsRun) latest.liveRunIdentity else null,
        )
        refreshPersistedSession(sessionId, events)
    } }

    @JvmStatic
    fun withSession(sessionId: String, update: Runnable) = inSession(sessionId) { update.run() }

    /** UI publishes only transient eligibility and locally available identities; no asset bytes cross this state. */
    @JvmStatic
    fun headerMascotAvailabilityChanged(availability: HeaderMascotAvailability) = inSession(availability.sessionId.orEmpty()) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != availability.sessionId) return@synchronized
        current.headerMascotAnimationRequest?.takeIf { it.sessionId == availability.sessionId }?.let { request ->
            availability.failure(request.animationId)?.let { reason ->
                resolveHeaderMascotAnimationLocked(request, "rejected", reason)
            }
        }
        val latest = _state.value
        val updated = latest.copy(headerMascotAvailability = availability)
        _state.value = updated
        if (ownedRunActive && ownedSessionId == availability.sessionId) ownedLiveSnapshot = updated
    } }

    @JvmStatic
    fun clearHeaderMascotAvailability(sessionId: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId || current.headerMascotAvailability.sessionId != sessionId) return@synchronized
        current.headerMascotAnimationRequest?.takeIf { it.sessionId == sessionId }?.let { request ->
            val reason = current.headerMascotAvailability.failure(request.animationId) ?: "The main chat mascot is no longer available."
            resolveHeaderMascotAnimationLocked(request, "rejected", reason)
        }
        val updated = _state.value.copy(headerMascotAvailability = HeaderMascotAvailability())
        _state.value = updated
        if (ownedRunActive && ownedSessionId == sessionId) ownedLiveSnapshot = updated
    } }

    /** Publishes a request and waits for a terminal result reported by the UI. */
    @JvmStatic
    fun requestHeaderMascotAnimation(
        sessionId: String,
        animationId: String,
        token: CancellationToken,
    ): HeaderMascotAnimationResult {
        val admission = inSession(sessionId) { synchronized(lock) {
            val current = _state.value
            fun rejected(reason: String) = HeaderMascotAdmission(
                rejection = HeaderMascotAnimationResult(animationId, "rejected", reason),
            )
            if (!UserDecisionUiAvailability.isChatVisible() || current.sessionId != sessionId || !current.running
                || current.interactiveOwnerSessionId != sessionId) {
                rejected("The active main chat is not available.")
            } else {
                val runOwner = sessionOwners[sessionId]
                    ?.takeIf { it.active && it.sessionId == sessionId }
                val executionId = runOwner?.let { "$sessionId:${it.generation}" }
                    ?.takeIf { current.liveRunIdentity == it }
                val failure = current.headerMascotAvailability.failure(animationId)
                if (executionId == null) {
                    rejected("The active main chat run is not available.")
                } else if (failure != null) {
                    rejected(failure)
                } else {
                    val activeRequest = current.headerMascotAnimationRequest
                    val joined = activeRequest?.takeIf {
                        it.sessionId == sessionId && it.executionId == executionId && it.animationId == animationId
                    }?.let { pendingHeaderMascotAnimations[it.requestId] }
                    if (activeRequest != null && joined == null) {
                        rejected("Another mascot animation is already in progress.")
                    } else {
                        val pendingRequest = joined ?: PendingHeaderMascotAnimation(HeaderMascotAnimationRequest(
                            java.util.UUID.randomUUID().toString(), sessionId, animationId, executionId)).also { created ->
                            pendingHeaderMascotAnimations[created.request.requestId] = created
                            val updated = current.copy(headerMascotAnimationRequest = created.request, headerMascotAnimationResult = null)
                            _state.value = updated
                            if (ownedRunActive && ownedSessionId == sessionId) ownedLiveSnapshot = updated
                        }
                        pendingRequest.waiters++
                        HeaderMascotAdmission(pending = pendingRequest)
                    }
                }
            }
        } }
        admission.rejection?.let { return it }
        val pending = checkNotNull(admission.pending)

        val waiterReleased = AtomicBoolean(false)
        fun releaseWaiter(cancelled: Boolean) {
            if (!waiterReleased.compareAndSet(false, true)) return
            synchronized(lock) {
                pending.waiters--
                if (cancelled && pending.waiters == 0 && !pending.completion.isDone) {
                    resolveHeaderMascotAnimationLocked(pending.request, "cancelled", null)
                }
                if (pending.waiters == 0 && pending.completion.isDone) {
                    pendingHeaderMascotAnimations.remove(pending.request.requestId, pending)
                }
            }
        }

        val waiterResult = CompletableFuture<HeaderMascotAnimationResult>()
        pending.completion.whenComplete { result, failure ->
            if (result != null) waiterResult.complete(result)
            else if (failure != null) waiterResult.complete(HeaderMascotAnimationResult(animationId, "rejected", "The animation result could not be read.", pending.request.requestId))
        }
        val unregisterCancellation = token.registerCancelAction {
            waiterResult.complete(HeaderMascotAnimationResult(animationId, "cancelled", requestId = pending.request.requestId))
            releaseWaiter(cancelled = true)
        }
        try {
            val result = try {
                waiterResult.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                waiterResult.complete(HeaderMascotAnimationResult(animationId, "cancelled", requestId = pending.request.requestId))
                releaseWaiter(cancelled = true)
                waiterResult.getNow(HeaderMascotAnimationResult(animationId, "cancelled", requestId = pending.request.requestId))
            } catch (_: ExecutionException) {
                HeaderMascotAnimationResult(animationId, "rejected", "The animation result could not be read.", pending.request.requestId)
            }
            return result
        } finally {
            unregisterCancellation.run()
            releaseWaiter(cancelled = token.isCancellationRequested())
        }
    }

    /** Completes a request from UI playback or cleanup, correlated by its request identity. */
    @JvmStatic
    fun completeHeaderMascotAnimationRequest(
        sessionId: String,
        requestId: String,
        outcome: String,
        reason: String? = null,
    ) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        val request = current.headerMascotAnimationRequest?.takeIf { it.sessionId == sessionId && it.requestId == requestId }
            ?: pendingHeaderMascotAnimations[requestId]?.request?.takeIf { it.sessionId == sessionId }
            ?: return@synchronized
        resolveHeaderMascotAnimationLocked(request, outcome, reason)
    } }

    /** Clearing a published UI request is a rejection so its waiting tool always completes. */
    @JvmStatic
    fun clearHeaderMascotAnimationRequest(sessionId: String, requestId: String) =
        completeHeaderMascotAnimationRequest(sessionId, requestId, "rejected", "The animation request was cleared before playback finished.")

    private fun resolveHeaderMascotAnimationLocked(request: HeaderMascotAnimationRequest, outcome: String, reason: String?) {
        val result = HeaderMascotAnimationResult(request.animationId, outcome, reason, request.requestId)
        inSession(request.sessionId) {
            val current = _state.value
            if (current.headerMascotAnimationRequest?.requestId == request.requestId) {
                _state.value = current.copy(headerMascotAnimationRequest = null, headerMascotAnimationResult = result)
            }
        }
        pendingHeaderMascotAnimations.remove(request.requestId)?.completion?.complete(result)
    }

    private fun resolveHeaderMascotAnimationForExecutionLocked(
        sessionId: String,
        executionId: String?,
        outcome: String,
        reason: String?,
    ) {
        if (executionId.isNullOrBlank()) return
        _state.value.headerMascotAnimationRequest
            ?.takeIf { it.sessionId == sessionId && it.executionId == executionId }
            ?.let { resolveHeaderMascotAnimationLocked(it, outcome, reason) }
    }

    @JvmStatic
    fun stopPendingGeneration(sessionId: String, generation: Long) = inSession(sessionId) { synchronized(lock) {
        clearAssistantTextStreamsLocked(sessionId, generation)
        if (ownedRunActive && ownedSessionId == sessionId && activeGeneration == generation) {
            resolveHeaderMascotAnimationForExecutionLocked(
                sessionId, "$sessionId:$generation", "cancelled", null,
            )
            ownedRunActive = false
            ownedLiveSnapshot = null
            _state.value = _state.value.copy(interactiveOwnerSessionId = null, liveMascotTool = null,
                liveModelWaitId = null, liveUserWaitId = null, running = false, stopping = true,
                outcome = null, events = interruptActivities(_state.value.events))
            owner().stoppingGeneration = generation
            owner().stoppedCompletion = null
        } else if (_state.value.sessionId == sessionId && _state.value.compacting) {
            _state.value = _state.value.copy(compacting = false, compactionStatus = null,
                running = false, stopping = true, outcome = null)
            owner().stoppingGeneration = generation
            owner().stoppedCompletion = null
        }
    } }

    @JvmStatic
    fun finishPendingGeneration(sessionId: String, generation: Long) = inSession(sessionId) { synchronized(lock) {
        clearAssistantTextStreamsLocked(sessionId, generation)
        val ownership = owner()
        if (ownership.stoppingGeneration != generation || ownership.generation != generation) return@synchronized
        // The drain thread can still have a pre-STOP ThreadLocal snapshot. Use the latest
        // published snapshot for this session, and let the stopped generation marker own
        // finalization instead of relying on a possibly stale `stopping` bit.
        val current = sessionSnapshots[sessionId] ?: _state.value
        if (current.sessionId != sessionId) return@synchronized
        ownership.stoppingGeneration = null
        val completion = ownership.stoppedCompletion
        ownership.stoppedCompletion = null
        ownership.systemOriginGeneration = null
        val terminalOutcome = completion?.outcome ?: "STOPPED"
        val events = completion?.takeIf { it.text.isNotEmpty() || it.messageId.isNotEmpty() }?.let {
            val assistant = event("assistant", it.text).copy(
                stage = AgentRunUiEvent.assistantStageForOutcome(it.outcome),
                messageId = it.messageId, durationMs = it.durationMs, canRegenerate = it.canRegenerate)
            append(interruptActivities(current.events), assistant)
        } ?: interruptActivities(current.events)
        _state.value = current.copy(
            runId = completion?.runId ?: current.runId,
            running = false,
            stopping = false,
            outcome = terminalOutcome,
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            compacting = false,
            compactionStatus = null,
            events = events,
        )
    } }

    @JvmStatic
    fun completeGeneration(sessionId: String, generation: Long, runId: String, outcome: String,
        text: String, messageId: String, durationMs: Long) = inSession(sessionId) { synchronized(lock) {
        val streams = takeAssistantTextStreamsLocked(sessionId, generation)
        val streamIds = streams.mapTo(mutableSetOf()) { it.id }
        val current = _state.value
        val ownership = owner()
        if (ownership.stoppingGeneration == generation) {
            ownership.stoppedCompletion = StoppedCompletion(runId, outcome, text, messageId, durationMs,
                canRegenerate = ownership.systemOriginGeneration != generation
                    && ownership.internalInputGeneration != generation)
            if (current.events.any { it.id in streamIds })
                _state.value = current.copy(events = current.events.filterNot { it.id in streamIds })
            return@synchronized
        }
        if (ownedRunActive && ownedSessionId == sessionId && activeGeneration == generation) {
            val animationOutcome = if (outcome == "STOPPED" || outcome == "CANCELLED") "cancelled" else "rejected"
            val animationReason = if (animationOutcome == "cancelled") null
                else "The agent run ended before the animation finished."
            val animationRequest = current.headerMascotAnimationRequest?.takeIf {
                it.sessionId == sessionId && it.executionId == "$sessionId:$generation"
            }
            val animationResult = animationRequest?.let { request ->
                HeaderMascotAnimationResult(request.animationId, animationOutcome, animationReason, request.requestId)
                    .also { result ->
                        pendingHeaderMascotAnimations.remove(request.requestId)?.completion?.complete(result)
                    }
            }
            ownedRunActive = false
            ownedLiveSnapshot = null
            val canRegenerate = ownership.systemOriginGeneration != generation
                && ownership.internalInputGeneration != generation
            ownership.stoppingGeneration = null
            ownership.stoppedCompletion = null
            ownership.systemOriginGeneration = null
            ownership.internalInputGeneration = null
            if (current.sessionId == sessionId) {
                pendingProgress.clear()
                val currentEventIds = current.events.mapTo(mutableSetOf()) { it.id }
                val promotedStream = streams.lastOrNull { stream ->
                    stream.kind == "assistant_stream" && stream.id in currentEventIds
                }
                val assistant = event("assistant", text).copy(
                    stage = AgentRunUiEvent.assistantStageForOutcome(outcome),
                    messageId = messageId,
                    durationMs = durationMs,
                    timelineKey = promotedStream?.timelineKey ?: promotedStream?.id,
                    streamUserEventId = null,
                    canRegenerate = canRegenerate,
                )
                val baseEvents = interruptActivities(current.events.filterNot { it.id in streamIds })
                val events = if (text.isEmpty() && messageId.isEmpty()) baseEvents else append(baseEvents, assistant)
                _state.value = current.copy(
                    runId = runId,
                    running = false,
                    stopping = false,
                    outcome = outcome,
                    interactiveOwnerSessionId = null,
                    liveMascotTool = null,
                    liveModelWaitId = null,
                    liveUserWaitId = null,
                    compacting = false,
                    compactionStatus = null,
                    headerMascotAnimationRequest = if (animationRequest == null) current.headerMascotAnimationRequest else null,
                    headerMascotAnimationResult = animationResult ?: current.headerMascotAnimationResult,
                    events = events,
                )
            }
        } else if (current.events.any { it.id in streamIds }) {
            _state.value = current.copy(events = current.events.filterNot { it.id in streamIds })
        }
    } }

    @JvmStatic
    fun assistantProgress(sessionId: String, progress: AgentRunUiEvent) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (!current.running || current.sessionId != sessionId || progress.stage != "PROGRESS"
            || progress.messageId.isBlank()) return@synchronized
        if (current.events.any { it.messageId == progress.messageId }) return@synchronized
        _state.value = current.copy(events = append(current.events, progress.copy(id = nextEventId++)))
    } }

    /** Promotes this model turn's stream into its durable preamble without changing its timeline key. */
    @JvmStatic
    fun assistantProgress(sessionId: String, generation: Long, runId: String, progress: AgentRunUiEvent) =
        inSession(sessionId) { synchronized(lock) {
            val current = _state.value
            if (!ownsAssistantStreamLocked(sessionId, generation) || progress.stage != "PROGRESS"
                || progress.messageId.isBlank()) return@synchronized

            val streamKey = assistantTextStreamKey(sessionId, generation, runId)
            val stream = assistantTextStreams[streamKey]
            val existingIndex = current.events.indexOfFirst {
                it.kind == "assistant" && it.messageId == progress.messageId
            }
            val streamIndex = stream?.let { candidate -> current.events.indexOfFirst { it.id == candidate.id } } ?: -1
            if (stream != null) assistantTextStreams.remove(streamKey)

            val events = current.events.toMutableList()
            if (existingIndex >= 0) {
                if (stream != null) {
                    events[existingIndex] = events[existingIndex].copy(timelineKey = stream.timelineKey)
                    if (streamIndex >= 0 && streamIndex != existingIndex) events.removeAt(streamIndex)
                    _state.value = current.copy(events = events)
                }
                return@synchronized
            }

            val promoted = progress.copy(id = nextEventId++, timelineKey = stream?.timelineKey)
            if (streamIndex >= 0) events[streamIndex] = promoted
            else events += promoted
            _state.value = current.copy(events = events)
    } }

    @JvmStatic
    fun onProgress(stage: String, message: String) = synchronized(lock) {
        val current = _state.value
        if (!current.running) return@synchronized
        if (stage == "thinking") {
            _state.value = current.copy(liveModelWaitId = null, liveUserWaitId = null)
            return@synchronized
        }
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
        val modelWaiting = stage == "model_wait" && ownedRunActive
            && ownedSessionId == current.sessionId
            && current.interactiveOwnerSessionId == current.sessionId && !current.compacting
        if (kind == "tool") {
            val events = pendingProgress.fold(current.events) { accumulated, buffered -> append(accumulated, buffered) }
            pendingProgress.clear()
            _state.value = current.copy(events = append(events, progress),
                liveModelWaitId = null, liveUserWaitId = null)
        } else {
            pendingProgress.add(progress)
            _state.value = current.copy(
                liveModelWaitId = progress.id.takeIf { modelWaiting },
                liveMascotTool = if (modelWaiting) null else current.liveMascotTool,
                liveUserWaitId = null,
            )
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
        val turnStart = events.indexOfLast { it.kind == "user" }
        val existingIndex = if (callId.isBlank()) -1 else events.indexOfLast { it.kind == "tool" && it.toolCallId == callId }
            .takeIf { it > turnStart } ?: -1
        val updated = AgentRunUiEvent.toolEvent(nextEventId++, stage, displayName, detail, callId, previewId,
            System.currentTimeMillis()).copy(toolAuditDetail = auditDetail ?: events.getOrNull(existingIndex)?.toolAuditDetail)
        if (existingIndex >= 0 && ToolActivity.definitive(events[existingIndex].stage)
            && !ToolActivity.definitive(stage)) return@synchronized
        if (existingIndex >= 0) {
            events[existingIndex] = updated.copy(id = events[existingIndex].id)
        } else {
            events.add(updated)
        }
        _state.value = current.copy(events = events.takeLast(MAX_EVENTS), liveModelWaitId = null)
    }

    @JvmStatic
    fun onToolActivity(activity: ToolActivity) = synchronized(lock) {
        val current = _state.value
        if (!current.running) return@synchronized
        val events = pendingProgress.fold(current.events) { accumulated, buffered -> append(accumulated, buffered) }.toMutableList()
        pendingProgress.clear()
        val index = events.indexOfLast { it.toolActivity?.executionId == activity.executionId }
        val old = events.getOrNull(index)
        val history = old?.toolActivityEvents.orEmpty().let { prior ->
            if (prior.any { it.eventId == activity.eventId }) prior else prior + activity
        }
        val projected = ToolActivity.project(history, true)
        // The service supplies these typed callbacks through withGeneration. Existing ownership
        // is still required; optimistic rows, restored calls and orphan progress cannot start it.
        val liveMascotTool = when {
            !ownedRunActive || ownedSessionId != current.sessionId
                || current.interactiveOwnerSessionId != current.sessionId -> null
            current.liveMascotTool?.executionId == activity.executionId
                && projected.stage !in setOf("tool_call", "tool_progress") -> null
            activity.stage == "tool_call" && projected.stage == "tool_call" && old == null
                && current.liveMascotTool?.executionId != activity.executionId ->
                LiveMascotTool(activity.executionId, eligibleToAnimate = !current.compacting)
            else -> current.liveMascotTool
        }
        val updated = AgentRunUiEvent.activityEvent(old?.id ?: nextEventId++, projected)
            .copy(toolActivityEvents = history)
            .copyToolTimelineIdentity("execution:${activity.executionId}",
                events.lastOrNull { it.kind == "user" }?.messageId)
        if (index < 0) events.add(updated) else events[index] = updated
        _state.value = current.copy(events = events.takeLast(MAX_EVENTS),
            liveMascotTool = liveMascotTool, liveModelWaitId = null)
    }

    private fun interruptActivities(events: List<AgentRunUiEvent>): List<AgentRunUiEvent> = events.map { event ->
        if (event.kind == "tool" && event.stage in setOf("tool_call", "tool_progress")) {
            event.toolActivity?.let { AgentRunUiEvent.activityEvent(event.id,it.interrupted()).copy(toolActivityEvents = event.toolActivityEvents) }
                ?: event.copy(stage = "tool_interrupted", toolStatusResourceId = R.string.connector_tool_unconfirmed)
        } else event
    }

    @JvmStatic
    fun memoryChanged(revision: MemoryStore.Revision) = inSession(revision.conversationId) { synchronized(lock) {
        // Seed remains journaled by MemoryStore; only its live chat presentation is suppressed.
        if (revision.operation == "SEED") return@synchronized
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
    } }

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
    ) = withApprovalSession { sessionId ->
        synchronized(lock) {
            val ownership = sessionOwners[sessionId]
            val current = if (ownership?.active == true && ownership.sessionId == sessionId) {
                _state.value.takeIf { it.sessionId == sessionId }
                    ?: sessionSnapshots[sessionId]
                    ?: ownership.liveSnapshot
                    ?: return@synchronized
            } else {
                _state.value.takeIf { it.sessionId == sessionId } ?: return@synchronized
            }
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
            val updated = current.copy(events = append(events, card),
                liveMascotTool = current.liveMascotTool?.copy(eligibleToAnimate = false),
                liveModelWaitId = null,
                liveUserWaitId = if (ownership?.active == true && ownership.sessionId == sessionId) "approval:$id" else null)
            if (workingSession.get() == sessionId) _state.value = updated
            else storeSessionSnapshotLocked(sessionId, updated)
            if (ownership?.active == true && ownership.sessionId == sessionId) ownership.liveSnapshot = updated
            if (visibleSessionId == sessionId) publishVisibleSnapshotLocked(updated)
        }
    }

    /** Presents a gate request in the session whose current run generation requested it. */
    @JvmStatic
    fun showApprovalForGeneration(generation: Long, id: String, summary: com.jarvys.agent.connectors.ApprovalSummary) =
        withApprovalSession(generation) {
            showApproval(
                id, summary.title, summary.lines, summary.permission, summary.activityIntent,
                summary.permissionDeniedIntent, summary.allowAlwaysAvailable,
                summary.autonomyConnectorId, summary.autonomyOperationName,
                summary.localizedTitle, summary.localizedLines, summary.permissionLabel,
                summary.compactSummary, summary.requester, summary.requesterColorKey,
            )
        }

    @JvmStatic
    fun updateApproval(id: String, status: String) {
        val sessionId = sessionForApproval(id) ?: return
        updateApprovalInSession(sessionId, id, status)
    }

    /** Resolves a gate update using its run owner, falling back to the already-attributed card. */
    @JvmStatic
    fun updateApprovalForGeneration(generation: Long, id: String, status: String) {
        val sessionId = synchronized(lock) {
            sessionForGenerationLocked(generation) ?: sessionForApprovalLocked(id)
        } ?: return
        updateApprovalInSession(sessionId, id, status)
    }

    private fun updateApprovalInSession(sessionId: String, id: String, status: String) =
        inSession(sessionId) { synchronized(lock) {
            val current = _state.value
            val index = current.events.indexOfLast { it.kind == "approval" && it.approvalId == id }
            if (index < 0) return@synchronized
            val events = current.events.toMutableList()
            events[index] = events[index].copy(stage = status, approvalStatus = status)
            publishOwnedSnapshot(current.copy(events = events,
                liveUserWaitId = current.liveUserWaitId?.takeUnless { it == "approval:$id" && status != "PENDING" }))
        } }

    @JvmStatic
    fun markProactiveRepliesUsed(messageId: String) {
        val sessionId = sessionForMessage(messageId) ?: return
        inSession(sessionId) { synchronized(lock) {
            val current = _state.value
            val index = current.events.indexOfLast { it.kind == "assistant" && it.messageId == messageId }
            if (index < 0) return@synchronized
            val updated = current.events.toMutableList()
            updated[index] = updated[index].copy(proactiveRepliesUsed = true)
            _state.value = current.copy(events = updated)
        } }
    }

    @JvmStatic
    fun updateApprovalDetail(id: String, detail: String) {
        val sessionId = sessionForApproval(id) ?: return
        inSession(sessionId) { synchronized(lock) {
            val current = _state.value
            val index = current.events.indexOfLast { it.kind == "approval" && it.approvalId == id }
            if (index < 0) return@synchronized
            val events = current.events.toMutableList()
            events[index] = events[index].copy(approvalStatusDetail = detail, approvalStatusDetailText = null)
            _state.value = current.copy(events = events)
        } }
    }

    @JvmStatic
    fun updateApprovalDetail(id: String, detail: com.jarvys.agent.connectors.ConnectorUiText) {
        val sessionId = sessionForApproval(id) ?: return
        inSession(sessionId) { synchronized(lock) {
            val current = _state.value
            val index = current.events.indexOfLast { it.kind == "approval" && it.approvalId == id }
            if (index < 0) return@synchronized
            val events = current.events.toMutableList()
            events[index] = events[index].copy(approvalStatusDetail = detail.fallback, approvalStatusDetailText = detail)
            _state.value = current.copy(events = events)
        } }
    }

    @JvmStatic
    fun showUserDecision(sessionId: String, id: String, title: String, body: String,
                         options: List<UserDecisionOption>, allowDismiss: Boolean) = inSession(sessionId) { synchronized(lock) {
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
        _state.value = current.copy(events = append(events.dropLast(1), events.last()),
            liveMascotTool = current.liveMascotTool?.copy(eligibleToAnimate = false),
            liveModelWaitId = null,
            liveUserWaitId = if (ownedRunActive && ownedSessionId == current.sessionId) "decision:$id" else null)
    } }

    @JvmStatic
    fun updateUserDecision(sessionId: String, id: String, status: String,
                           optionId: String? = null, optionLabel: String? = null) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        if (current.sessionId != sessionId) return@synchronized
        val index = current.events.indexOfLast { it.kind == "user_decision" && it.decisionId == id }
        if (index < 0 || current.events[index].decisionStatus != "PENDING") return@synchronized
        val events = current.events.toMutableList()
        events[index] = events[index].copy(stage = status, decisionStatus = status,
            decisionOptionId = optionId, decisionOptionLabel = optionLabel)
        _state.value = current.copy(events = events,
            liveUserWaitId = current.liveUserWaitId?.takeUnless { it == "decision:$id" && status != "PENDING" })
    } }

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
            stopping = false,
            outcome = outcome,
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            compacting = false,
            compactionStatus = null,
            events = append(interruptActivities(current.events), assistant),
        )
    }

    @JvmStatic
    fun completeChatTurn(sessionId: String, turnId: String, answer: String) = inSession(sessionId) { synchronized(lock) {
        val current = _state.value
        _state.value = current.copy(
            runId = turnId,
            sessionId = sessionId,
            running = false,
            stopping = false,
            outcome = "COMPLETED",
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            compacting = false,
            compactionStatus = null,
            events = append(interruptActivities(current.events), event("assistant", answer)),
        )
    } }

    @JvmStatic
    fun fail(message: String) = synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = current.copy(
            running = false,
            stopping = false,
            outcome = "FAILED",
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            compacting = false,
            compactionStatus = null,
            events = append(interruptActivities(current.events), event("result", message, "FAILED")),
        )
    }

    @JvmStatic
    fun fail(sessionId: String, message: String) = inSession(sessionId) { synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = current.copy(
            sessionId = sessionId,
            running = false,
            stopping = false,
            outcome = "FAILED",
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            compacting = false,
            compactionStatus = null,
            events = append(interruptActivities(current.events), event("result", message, "FAILED")),
        )
    } }

    @JvmStatic
    fun failChatTurn(sessionId: String, message: String) = inSession(sessionId) { synchronized(lock) {
        pendingProgress.clear()
        val current = _state.value
        _state.value = current.copy(
            sessionId = sessionId,
            running = false,
            stopping = false,
            outcome = "FAILED",
            liveMascotTool = null,
            liveModelWaitId = null,
            liveUserWaitId = null,
            compacting = false,
            compactionStatus = null,
            events = append(interruptActivities(current.events), event("assistant", message, "FAILED")),
        )
    } }

    private fun append(existing: List<AgentRunUiEvent>, item: AgentRunUiEvent): List<AgentRunUiEvent> {
        val result = existing + item
        val durableKinds = setOf("user", "assistant", "compaction", "compaction_error", "memory", "crew_mission", "user_decision", "artifact")
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
