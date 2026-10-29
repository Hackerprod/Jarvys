package com.jarvys.agent.chatdb

/** Read-only session metadata; active with no endedAt is not proof of process liveness. */
data class PersonalMemoryTimelineSession(val id: String, val project: String, val state: String,
    val startedAt: Long, val endedAt: Long?)

data class PersonalMemoryTimeline(val focus: PersonalMemoryHit, val before: List<PersonalMemoryHit>,
    val after: List<PersonalMemoryHit>, val sessionInfo: PersonalMemoryTimelineSession?,
    val associationStatus: String, val totalInRange: Long)
