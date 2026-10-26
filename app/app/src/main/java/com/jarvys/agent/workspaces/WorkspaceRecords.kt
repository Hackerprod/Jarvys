package com.jarvys.agent.workspaces

import com.jarvys.agent.crew.CrewMissionAccess
import java.util.Collections

/** Durable metadata only. Existing Crew/checkpoint/file stores own execution and artifacts. */
data class WorkspaceRecord internal constructor(
    val workspaceId: String,
    val title: String,
    val executionSessionId: String,
    val ownerCentralSessionId: String,
    val createdAt: Long,
)

enum class WorkspaceAssignmentState { PREPARED, STARTED, BOUND, UNCERTAIN }

class WorkspaceAssignment internal constructor(
    val workspaceId: String,
    val assignmentId: String,
    val assignmentKey: String,
    val requestId: String,
    val originalUserRequest: String,
    val missionId: String,
    val role: String,
    val missionText: String,
    tools: List<String>?,
    val name: String?,
    val missionAccess: CrewMissionAccess,
    val revision: Long,
    val processId: String?,
    val state: WorkspaceAssignmentState,
    val botId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val centralSource: com.jarvys.agent.CentralInvocationSource? = null,
) {
    val tools: List<String>? = tools?.let { Collections.unmodifiableList(ArrayList(it)) }
    /** STARTED is never evidence that spawning failed; never replay it after reopening. */
    val needsReconciliation: Boolean
        get() = state == WorkspaceAssignmentState.STARTED || state == WorkspaceAssignmentState.UNCERTAIN
}

enum class WorkspaceControlType { PAUSE, RESUME, CORRECT }
enum class WorkspaceControlState { PENDING, STARTED, COMPLETE, UNCERTAIN }

data class WorkspaceUserControl internal constructor(
    val requestId: String,
    val workspaceId: String,
    val assignmentId: String,
    val botId: String,
    val assignmentRevision: Long,
    val admittedAssignmentRevision: Long?,
    val type: WorkspaceControlType,
    val userText: String,
    val origin: String,
    val revision: Long,
    val processId: String?,
    val state: WorkspaceControlState,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val needsReconciliation: Boolean
        get() = state == WorkspaceControlState.STARTED || state == WorkspaceControlState.UNCERTAIN
}

data class CentralReadReceipt internal constructor(
    val workspaceId: String,
    val assignmentId: String,
    val snapshotDigest: String,
    val readAt: Long,
    val readerCentralSessionId: String,
)

class WorkspaceIdempotencyConflictException(message: String) : IllegalArgumentException(message)
class StaleWorkspaceRevisionException(message: String) : IllegalStateException(message)
class WorkspaceRegistryCorruptException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Native UI authoring only. Do not register this gateway or submit as a model tool. */
class NativeWorkspaceControls internal constructor(private val registry: WorkspaceRegistry) {
    @JvmOverloads
    fun submit(
        requestId: String,
        workspaceId: String,
        assignmentId: String,
        botId: String,
        assignmentRevision: Long,
        type: WorkspaceControlType,
        userText: String,
        beforeWrite: Runnable = Runnable {},
    ): WorkspaceUserControl = registry.submitNativeControl(
        requestId, workspaceId, assignmentId, botId, assignmentRevision, type, userText, beforeWrite)
}
