package com.jarvys.agent.workspaces

import androidx.compose.runtime.Immutable
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.LocalRunStore
import java.util.Collections
import java.util.LinkedHashSet

/** Presentation only. The runtime supplies recorded status and exact action availability. */
enum class WorkspaceUiStatus {
    UNKNOWN, ACTIVE, WAITING, CANCEL_REQUESTED, STOPPING, PAUSED, PARTIAL, INTERRUPTED, COMPLETED,
}

@Immutable
data class WorkspaceSidebarRow(val id: String, val title: String, val status: WorkspaceUiStatus,
    val statusPageLimited: Boolean = false,
    val coordination: WorkspaceCoordinationSummary = WorkspaceCoordinationSummary())

/** One visible catalog page; never retain or eagerly load the whole catalog. */
class WorkspaceSidebarPage(rows: List<WorkspaceSidebarRow>, val nextCursor: String? = null) {
    val rows: List<WorkspaceSidebarRow> = Collections.unmodifiableList(ArrayList(rows))
    init { require(this.rows.size <= WORKSPACE_PAGE_SIZE) }
}

internal const val WORKSPACE_PAGE_SIZE = 50
internal const val WORKSPACE_CURSOR_HISTORY_LIMIT = 32

/** Bound saved cursor memory independently of how many pages the user visits. */
internal fun workspacePushCursor(history: List<String>, current: String?): List<String> =
    (history + current.orEmpty()).takeLast(WORKSPACE_CURSOR_HISTORY_LIMIT)

/**
 * Aggregate recorded observations, not objective verification. Priority: cleanup/cancel, active,
 * waiting, interruption, missing evidence, partial, paused, completed. A bounded page cannot
 * establish global completion: unseen assignments add UNKNOWN as an explicit conservative input.
 */
internal fun workspaceAggregateStatus(statuses: List<WorkspaceUiStatus>, unseenAssignments: Boolean = false): WorkspaceUiStatus {
    val present = statuses.toMutableSet()
    if (unseenAssignments || present.isEmpty()) present += WorkspaceUiStatus.UNKNOWN
    return listOf(WorkspaceUiStatus.STOPPING, WorkspaceUiStatus.CANCEL_REQUESTED,
        WorkspaceUiStatus.ACTIVE, WorkspaceUiStatus.WAITING, WorkspaceUiStatus.INTERRUPTED,
        WorkspaceUiStatus.UNKNOWN, WorkspaceUiStatus.PARTIAL, WorkspaceUiStatus.PAUSED,
        WorkspaceUiStatus.COMPLETED).first { it in present }
}

internal data class WorkspaceSharedTextPage(val text: String, val nextCursor: String?)

/** Source reader already caps files at 256 KiB. Cursor binds exact owner, path and full text. */
internal fun workspaceSharedTextPage(owner: String, path: String, text: String, cursor: String?,
                                    limit: Int = 16000): WorkspaceSharedTextPage {
    require(owner.isNotBlank() && path.isNotBlank() && limit in 2..16000)
    val input = "${owner.length}:$owner${path.length}:$path$text".toByteArray(Charsets.UTF_8)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val offset = if (cursor == null) 0 else {
        val parts = cursor.split('.')
        require(parts.size == 3 && parts[0] == "v1" && parts[1] == digest) { "File page source changed" }
        require(parts[2].matches(Regex("0|[1-9][0-9]{0,8}"))) { "Invalid file page cursor" }
        requireNotNull(parts[2].toIntOrNull())
    }
    require(offset in 0..text.length) { "Invalid file page offset" }
    require(offset == 0 || offset == text.length || !(Character.isLowSurrogate(text[offset]) && Character.isHighSurrogate(text[offset - 1])))
    var end = minOf(offset + limit, text.length)
    if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
    return WorkspaceSharedTextPage(text.substring(offset, end), if (end < text.length) "v1.$digest.$end" else null)
}

@Immutable
data class WorkspaceAssignmentUi(
    val id: String,
    val revision: Long,
    val name: String,
    val mission: String,
    val status: WorkspaceUiStatus,
    val recoveryNote: String? = null,
    val result: String? = null,
    val canCorrect: Boolean = false,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val resultVerified: Boolean = false,
    val pendingControlSaved: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Assignment identity is required" }
        require(revision >= 0) { "Assignment revision must not be negative" }
    }
}

/** Defensive snapshot: callers cannot mutate rows beneath a rendered target/revision. */
@Immutable
class WorkspaceDetailUiState(
    val id: String,
    val title: String,
    val status: WorkspaceUiStatus,
    assignments: List<WorkspaceAssignmentUi> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val canOpenFiles: Boolean = false,
    val nextCursor: String? = null,
    val statusPageLimited: Boolean = false,
    val coordination: WorkspaceCoordinationSummary = WorkspaceCoordinationSummary(),
) {
    val assignments: List<WorkspaceAssignmentUi> = Collections.unmodifiableList(ArrayList(assignments))

    init {
        require(id.isNotBlank()) { "Workspace identity is required" }
        require(this.assignments.map { it.id }.distinct().size == this.assignments.size) {
            "Assignment identities must be unique within a workspace"
        }
    }
}

/** Conversation presentation comes from the workspace's execution-session ledger. */
@Immutable
class WorkspaceChatUiState(
    val workspaceId: String,
    val executionSessionId: String,
    val title: String,
    val status: WorkspaceUiStatus,
    events: List<AgentRunUiEvent> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasOlderHistory: Boolean = false,
    val olderHistoryCursor: Long = LocalRunStore.NO_TIMELINE_CURSOR,
    hiddenAssistantMessageIds: Set<String> = emptySet(),
    hiddenTranslationMessageIds: Set<String> = emptySet(),
    translationMessageIds: Set<String> = emptySet(),
    invalidatedToolTurnMessageIds: Set<String> = emptySet(),
    val projectionMarkers: LocalRunStore.TimelineProjectionMarkers = LocalRunStore.TimelineProjectionMarkers(),
    assignments: List<WorkspaceAssignmentUi> = emptyList(),
) {
    val events: List<AgentRunUiEvent> = Collections.unmodifiableList(ArrayList(events))
    val assignments: List<WorkspaceAssignmentUi> = Collections.unmodifiableList(ArrayList(assignments))
    val hiddenAssistantMessageIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(hiddenAssistantMessageIds))
    val hiddenTranslationMessageIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(hiddenTranslationMessageIds))
    val translationMessageIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(translationMessageIds))
    val invalidatedToolTurnMessageIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(invalidatedToolTurnMessageIds))

    init {
        require(workspaceId.isNotBlank()) { "Workspace identity is required" }
        require(executionSessionId == WorkspaceIdentity.executionSessionId(workspaceId)) {
            "Workspace conversation must use its registered execution session"
        }
    }

    fun withTimelinePage(page: LocalRunStore.ConversationTimelinePage): WorkspaceChatUiState = WorkspaceChatUiState(
        workspaceId = workspaceId,
        executionSessionId = executionSessionId,
        title = title,
        status = status,
        events = events,
        loading = loading,
        error = error,
        hasOlderHistory = page.hasOlder,
        olderHistoryCursor = page.olderCursor,
        hiddenAssistantMessageIds = page.hiddenAssistantMessageIds,
        hiddenTranslationMessageIds = page.hiddenTranslationMessageIds,
        translationMessageIds = page.translationMessageIds,
        invalidatedToolTurnMessageIds = page.invalidatedToolTurnMessageIds,
        projectionMarkers = page.projectionMarkers,
        assignments = assignments,
    )

    fun withOlderTimelinePage(page: LocalRunStore.ConversationTimelinePage): WorkspaceChatUiState = WorkspaceChatUiState(
        workspaceId = workspaceId,
        executionSessionId = executionSessionId,
        title = title,
        status = status,
        events = events,
        loading = loading,
        error = error,
        hasOlderHistory = page.hasOlder,
        olderHistoryCursor = page.olderCursor,
        hiddenAssistantMessageIds = hiddenAssistantMessageIds + page.hiddenAssistantMessageIds,
        hiddenTranslationMessageIds = hiddenTranslationMessageIds + page.hiddenTranslationMessageIds,
        translationMessageIds = translationMessageIds + page.translationMessageIds,
        invalidatedToolTurnMessageIds = invalidatedToolTurnMessageIds + page.invalidatedToolTurnMessageIds,
        projectionMarkers = page.projectionMarkers,
        assignments = assignments,
    )
}

internal fun workspaceChatUiState(
    detail: WorkspaceDetailUiState,
    events: List<AgentRunUiEvent>,
): WorkspaceChatUiState = WorkspaceChatUiState(
    workspaceId = detail.id,
    executionSessionId = WorkspaceIdentity.executionSessionId(detail.id),
    title = detail.title,
    status = detail.status,
    events = events,
    assignments = detail.assignments,
)

internal fun workspaceChatUiState(
    detail: WorkspaceDetailUiState,
    page: LocalRunStore.ConversationTimelinePage,
): WorkspaceChatUiState = WorkspaceChatUiState(
    workspaceId = detail.id,
    executionSessionId = WorkspaceIdentity.executionSessionId(detail.id),
    title = detail.title,
    status = detail.status,
    events = page.events,
    assignments = detail.assignments,
    hasOlderHistory = page.hasOlder,
    olderHistoryCursor = page.olderCursor,
    hiddenAssistantMessageIds = page.hiddenAssistantMessageIds,
    hiddenTranslationMessageIds = page.hiddenTranslationMessageIds,
    translationMessageIds = page.translationMessageIds,
    invalidatedToolTurnMessageIds = page.invalidatedToolTurnMessageIds,
    projectionMarkers = page.projectionMarkers,
)

internal fun isWorkspaceCoordinationSession(sessionId: String): Boolean =
    WorkspaceIdentity.isCentral(sessionId) || WorkspaceIdentity.isExecutionSession(sessionId)

/** Bound text measurement without parsing worker content as Markdown, links or commands. */
internal fun workspaceDisplayText(value: String?, fallback: String, limit: Int = 4096): String {
    if (value.isNullOrBlank()) return fallback
    return if (value.length <= limit) value else value.take(limit) + "…"
}
