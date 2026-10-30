package com.jarvys.agent.chatdb

/** Canonical retained domains only; neither transcript counts nor authority metadata. */
class MemoryStats(
    val totalSessions: Long,
    val totalObservations: Long,
    val totalPrompts: Long,
    projects: List<String>,
) {
    val projects: List<String> = java.util.Collections.unmodifiableList(ArrayList(projects))
    init {
        require(totalSessions >= 0 && totalObservations >= 0 && totalPrompts >= 0)
    }
}

/** Upstream's optional string assertion, with the existing D1 project normalization. */
object MemoryStatsArguments {
    @JvmStatic fun project(arguments: Map<String, Any?>): String? {
        require(arguments.keys.all { it == "project" }) { "memory_stats_unknown_argument" }
        val raw = arguments["project"] as? String ?: return null
        if (MemoryObservationContent.trimSpace(raw).isEmpty()) return null
        return MemoryNamespace.normalizeProject(raw)
    }
}
