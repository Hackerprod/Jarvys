package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.CoreAgentRuntime
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.R
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewProcessIdentity

/** UI reads recorded evidence, never a worker's claimed success as independent verification. */
object WorkspaceProjection {
    /** Compatibility projection for callers that intentionally need only the first bounded page. */
    fun sidebar(context: Context): List<WorkspaceSidebarRow> = sidebarPage(context).rows

    fun sidebarPage(context: Context, afterWorkspaceId: String? = null): WorkspaceSidebarPage = WorkspaceRegistry(context).use { store ->
        val localized = AppLanguageRuntime.localizedContext(context)
        val workspaces = store.listWorkspaces(WORKSPACE_PAGE_SIZE, afterWorkspaceId)
        val rows = workspaces.map { scope ->
            val assignments = store.listAssignments(scope.workspaceId, WORKSPACE_PAGE_SIZE)
            val hasMore = assignmentNext(store, scope.workspaceId, assignments) != null
            val snapshots = LocalRunStore(context).readCrewMissionSnapshots(scope.executionSessionId)
            // Catalog browsing must never instantiate/configure a Crew runtime for each row.
            val manager = CoreAgentRuntime.crewManagerForSession(scope.executionSessionId)
            val projected = assignments.map { assignment -> projectAssignment(localized, store, scope, assignment, snapshots, manager) }
            WorkspaceSidebarRow(scope.workspaceId, scope.title,
                workspaceAggregateStatus(projected.map { it.status }, hasMore), hasMore)
        }
        val next = workspaces.lastOrNull()?.workspaceId?.takeIf {
            workspaces.size == WORKSPACE_PAGE_SIZE && store.listWorkspaces(1, it).isNotEmpty()
        }
        WorkspaceSidebarPage(rows, next)
    }

    fun detail(context: Context, id: String, afterAssignmentId: String? = null): WorkspaceDetailUiState = WorkspaceRegistry(context).use { store ->
        val localized = AppLanguageRuntime.localizedContext(context)
        val scope = requireNotNull(store.getWorkspace(id)) { "Unknown workspace" }
        val manager = CoreAgentRuntime.prepareWorkspaceCrew(context, scope.executionSessionId)
        val snapshots = LocalRunStore(context).readCrewMissionSnapshots(scope.executionSessionId)
        val records = store.listAssignments(id, WORKSPACE_PAGE_SIZE, afterAssignmentId)
        val next = assignmentNext(store, id, records)
        val assignments = records.map { assignment -> projectAssignment(localized, store, scope, assignment, snapshots, manager) }
        val pageLimited = afterAssignmentId != null || next != null
        WorkspaceDetailUiState(id, scope.title, workspaceAggregateStatus(assignments.map { it.status }, pageLimited),
            assignments, canOpenFiles = true, nextCursor = next, statusPageLimited = pageLimited)
    }

    private fun assignmentNext(store: WorkspaceRegistry, id: String, records: List<WorkspaceAssignment>): String? =
        records.lastOrNull()?.assignmentId?.takeIf {
            records.size == WORKSPACE_PAGE_SIZE && store.listAssignments(id, 1, it).isNotEmpty()
        }

    private fun projectAssignment(context: Context, store: WorkspaceRegistry, scope: WorkspaceRecord, assignment: WorkspaceAssignment,
                           snapshots: List<CrewMissionSnapshot>, manager: CrewManager?): WorkspaceAssignmentUi {
        val mission = snapshots.firstOrNull { it.missionId == assignment.missionId }
        val current = if (mission?.processId == CrewProcessIdentity.ID) mission else mission?.interrupted()
        val recorded = current?.bots?.firstOrNull { it.id == assignment.botId }
        val live = assignment.botId?.let { manager?.bot(it) }?.takeIf { it.missionId == assignment.missionId }
        val unsettled = store.getUnsettledControl(scope.workspaceId, assignment.assignmentId)
        val pending = store.getPendingControl(scope.workspaceId, assignment.assignmentId, assignment.revision)
        val bound = assignment.state == WorkspaceAssignmentState.BOUND && live != null
        val active = live?.status() in setOf(CrewManager.Status.QUEUED, CrewManager.Status.RUNNING, CrewManager.Status.WAITING)
        val quiescent = live?.isQuiescent == true && manager?.hasPendingOwnedWork(live) == false
        val resumable = bound && quiescent && (live!!.canResume() ||
            (assignment.processId == CrewProcessIdentity.ID && !live.requiresExplicitResume() &&
                live.status() in setOf(CrewManager.Status.STOPPED, CrewManager.Status.FAILED, CrewManager.Status.PARTIAL, CrewManager.Status.DONE)))
        val issue = WorkspaceChanges.issue(scope.executionSessionId, assignment.missionId)
            ?.let { context.getString(R.string.workspace_snapshot_unavailable) }
        val status = when {
            unsettled?.type == WorkspaceControlType.PAUSE && !quiescent -> WorkspaceUiStatus.STOPPING
            assignment.needsReconciliation || issue != null -> WorkspaceUiStatus.UNKNOWN
            recorded?.status == "STOPPED" && quiescent -> WorkspaceUiStatus.PAUSED
            unsettled != null -> WorkspaceUiStatus.UNKNOWN
            else -> status(recorded?.status)
        }
        val note = listOfNotNull(issue, com.jarvys.agent.WorkspaceResultDelivery.issue(scope.workspaceId),
            if (assignment.needsReconciliation) context.getString(R.string.workspace_admission_uncertain) else null,
            if (unsettled != null) context.getString(R.string.workspace_control_uncertain) else null,
            if (recorded == null) context.getString(R.string.workspace_evidence_unavailable) else recorded.recoveryNote.takeIf { it.isNotBlank() },
            if (live?.requiresExplicitResume() == true && !live.canResume()) context.getString(R.string.workspace_checkpoint_unavailable) else null)
            .joinToString("\n").takeIf { it.isNotBlank() }
        return WorkspaceAssignmentUi(assignment.assignmentId, assignment.revision, live?.name ?: assignment.name ?: assignment.role,
            assignment.missionText, status, note, recorded?.result?.takeIf { it.isNotBlank() },
            canCorrect = bound && unsettled == null && (!live!!.requiresExplicitResume() || resumable),
            canPause = bound && (!quiescent || active), canResume = resumable && unsettled == null, resultVerified = false,
            pendingControlSaved = pending != null)
    }

    private fun status(value: String?) = when (value) {
        "QUEUED", "RUNNING" -> WorkspaceUiStatus.ACTIVE
        "WAITING" -> WorkspaceUiStatus.WAITING
        "PARTIAL" -> WorkspaceUiStatus.PARTIAL
        "INTERRUPTED", "FAILED" -> WorkspaceUiStatus.INTERRUPTED
        "STOPPED" -> WorkspaceUiStatus.STOPPING
        // DONE remains a worker report; every result keeps its verification disclaimer.
        "DONE" -> WorkspaceUiStatus.COMPLETED
        else -> WorkspaceUiStatus.UNKNOWN
    }
}
