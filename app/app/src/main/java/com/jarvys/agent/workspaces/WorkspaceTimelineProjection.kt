package com.jarvys.agent.workspaces

import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.crew.CrewActivityTimeline
import com.jarvys.agent.crew.CrewMessage
import com.jarvys.agent.crew.CrewMissionSnapshot

/** Expands Crew's durable snapshots into the regular conversation event vocabulary. */
internal object WorkspaceTimelineProjection {
    private data class OrderedEvent(val event: AgentRunUiEvent, val sourceOrder: Int, val rowOrder: Int)
    private data class MissionRow(val event: AgentRunUiEvent, val sourceOrder: Int)

    fun project(workspaceId: String, events: List<AgentRunUiEvent>): List<AgentRunUiEvent> {
        require(workspaceId.isNotBlank())
        val latestSnapshots = LinkedHashMap<String, Pair<Int, CrewMissionSnapshot>>()
        events.forEachIndexed { index, event ->
            event.crewMissionSnapshot?.let { snapshot -> latestSnapshots[snapshot.missionId] = index to snapshot }
        }

        val projected = ArrayList<OrderedEvent>()
        events.forEachIndexed { sourceOrder, event ->
            when (event.kind) {
                "workspace_result" -> Unit // Internal ledger evidence is deliberately not a chat message.
                "crew_mission" -> {
                    val snapshot = event.crewMissionSnapshot ?: return@forEachIndexed
                    if (latestSnapshots[snapshot.missionId]?.first != sourceOrder) return@forEachIndexed
                    val rows = projectMission(workspaceId, snapshot)
                    rows.forEachIndexed { rowOrder, row ->
                        projected += OrderedEvent(row, sourceOrder, rowOrder)
                    }
                }
                else -> projected += OrderedEvent(event, sourceOrder, 0)
            }
        }
        return projected.sortedWith(compareBy<OrderedEvent> { it.event.timestampMillis }
            .thenBy { it.sourceOrder }.thenBy { it.rowOrder })
            .map { it.event }
    }

    private fun projectMission(workspaceId: String, snapshot: CrewMissionSnapshot): List<AgentRunUiEvent> {
        val rows = ArrayList<MissionRow>()
        val messages = CrewActivityTimeline.project(snapshot.messages, snapshot.bots)
        messages.forEachIndexed { sourceOrder, message ->
            val stableId = stableId(workspaceId, snapshot.missionId, message.id)
            when {
                message.activity != null -> {
                    rows += MissionRow(AgentRunUiEvent.activityEvent(stableId, message.activity)
                        .copy(messageId = message.id, timelineKey = stableId)
                        .copyToolTimelineIdentity(message.activity.executionId, message.id), sourceOrder)
                }
                isUserMessage(message) -> rows += MissionRow(AgentRunUiEvent.messageEvent(
                    stableId, "user", message.text, message.timestampMillis,
                ).copy(messageId = message.id, timelineKey = stableId), sourceOrder)
                isUserFacingBotMessage(message) -> rows += MissionRow(AgentRunUiEvent.messageEvent(
                    stableId, "assistant", message.text, message.timestampMillis,
                ).copy(messageId = message.id, timelineKey = stableId, canRegenerate = false), sourceOrder)
            }
        }

        snapshot.synthesis.takeIf(String::isNotBlank)
            ?.takeUnless { synthesis -> rows.any { it.event.kind == "assistant" && it.event.text == synthesis } }
            ?.let { synthesis ->
                val resultTime = snapshot.finishedAtMillis.takeIf { it > 0L }
                    ?: messages.lastOrNull()?.timestampMillis ?: snapshot.startedAtMillis
                val stableId = stableId(workspaceId, snapshot.missionId, "synthesis")
                rows += MissionRow(AgentRunUiEvent.messageEvent(stableId, "assistant", synthesis, resultTime)
                    .copy(messageId = "${snapshot.missionId}:synthesis", timelineKey = stableId, canRegenerate = false),
                    messages.size)
            }
        return rows.sortedWith(compareBy<MissionRow> { it.event.timestampMillis }.thenBy { it.sourceOrder })
            .map { it.event }
    }

    private fun isUserMessage(message: CrewMessage): Boolean =
        message.from == "user" && message.type == CrewMessage.Type.USER

    /** Keep user-facing content and untyped worker findings/results, while filtering causal coordination. */
    private fun isUserFacingBotMessage(message: CrewMessage): Boolean =
        message.from != "user" && message.coordination == null && message.text.isNotBlank() &&
            message.type in setOf(
                CrewMessage.Type.FINDING,
                CrewMessage.Type.CRITIQUE,
                CrewMessage.Type.QUESTION,
                CrewMessage.Type.BLOCKER,
                CrewMessage.Type.ANSWER,
                CrewMessage.Type.RESULT,
            ) && (message.to == "user" || (message.to == "chief" && message.from != "chief"))

    private fun stableId(workspaceId: String, missionId: String, rowId: String): Long {
        val identity = "$workspaceId\u0000$missionId\u0000$rowId"
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis.
        identity.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = (hash xor (byte.toLong() and 0xffL)) * 0x100000001b3L
        }
        return hash or Long.MIN_VALUE
    }
}
