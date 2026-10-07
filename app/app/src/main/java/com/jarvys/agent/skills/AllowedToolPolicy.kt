package com.jarvys.agent.skills

/** Pure allow-list validation so tool-family additions stay covered by local JVM tests. */
object AllowedToolPolicy {
    @JvmStatic
    fun validate(requested: List<String>, available: Set<String>, groups: Set<String>): String? {
        if (requested.isEmpty()) return null
        val unknown = requested.filterNot { it == "*" || it in available || it in groups }
        return if (unknown.isEmpty()) null else "Unknown or disabled tools: ${unknown.joinToString(", ")}"
    }
}
