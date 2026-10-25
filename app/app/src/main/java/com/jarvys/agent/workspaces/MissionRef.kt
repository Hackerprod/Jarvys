package com.jarvys.agent.workspaces

import android.content.Context
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewProcessIdentity
import org.json.JSONObject

/**
 * Durable pointer from a Central thread message to a bot mission in an invisible workspace. It is
 * stored as a `mission_ref` message part, never as markup inside the message text. [resultId] and
 * [resultSha256] are present only on the message that announces a delivered worker result.
 */
data class MissionRef(
    val workspaceId: String,
    val executionSessionId: String,
    val missionId: String,
    val assignmentId: String,
    val resultId: String? = null,
    val resultSha256: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("workspace_id", workspaceId).put("execution_session_id", executionSessionId)
        .put("mission_id", missionId).put("assignment_id", assignmentId)
        .put("result_id", resultId ?: JSONObject.NULL).put("result_sha256", resultSha256 ?: JSONObject.NULL)
        .toString()

    companion object {
        /** Message part kind; unknown kinds already round-trip through the message ledger. */
        const val PART_KIND = "mission_ref"

        /** Null for a payload that is not a coherent reference; the message then reads as plain text. */
        fun fromJson(payload: String): MissionRef? = runCatching {
            val json = JSONObject(payload)
            val workspaceId = json.getString("workspace_id")
            val session = json.getString("execution_session_id")
            if (WorkspaceIdentity.executionSessionId(workspaceId) != session) return@runCatching null
            MissionRef(workspaceId, session, json.getString("mission_id").takeIf(String::isNotBlank) ?: return@runCatching null,
                json.getString("assignment_id").takeIf(String::isNotBlank) ?: return@runCatching null,
                json.optString("result_id").takeIf { it.isNotBlank() && it != "null" },
                json.optString("result_sha256").takeIf { it.isNotBlank() && it != "null" })
        }.getOrNull()

        fun of(workspaceId: String, assignmentId: String, missionId: String): MissionRef =
            MissionRef(workspaceId, WorkspaceIdentity.executionSessionId(workspaceId), missionId, assignmentId)
    }
}

/** Derives what a mission card shows from the registry and the durable Crew snapshots. */
object MissionRefResolver {
    /** The recorded mission, or null when the workspace, assignment or snapshot no longer exists. */
    fun resolve(context: Context, ref: MissionRef): CrewMissionSnapshot? {
        val app = context.applicationContext
        val assignment = WorkspaceRegistry(app).use { registry ->
            val scope = registry.getWorkspace(ref.workspaceId) ?: return null
            if (scope.executionSessionId != ref.executionSessionId) return null
            registry.getAssignment(ref.workspaceId, ref.assignmentId)
        } ?: return null
        if (assignment.missionId != ref.missionId) return null
        val recorded = LocalRunStore(app).readCrewMissionSnapshots(ref.executionSessionId)
            .lastOrNull { it.missionId == ref.missionId } ?: return null
        // A snapshot written by another process describes work that this process cannot be running.
        return if (recorded.processId == CrewProcessIdentity.ID) recorded else recorded.interrupted()
    }
}
