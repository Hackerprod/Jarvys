package com.jarvys.agent

internal fun drawerChatsForDisplay(items: List<RunHistoryItem>, query: String, archived: Boolean = false): List<RunHistoryItem> {
    val needle = query.trim()
    return items.asSequence()
        .filter { it.archived == archived && drawerChatMatches(it.title, it.goal, needle) }
        .distinctBy { it.sessionId }
        .sortedWith(compareByDescending<RunHistoryItem> { it.pinned }.thenByDescending { it.timestampSeconds })
        .toList()
}

internal fun drawerChatMatches(title: String?, goal: String, query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() || title.orEmpty().contains(needle, ignoreCase = true)
        || goal.contains(needle, ignoreCase = true)
}

internal fun drawerConversationTitle(title: String?, goal: String, fallback: String): String =
    title?.takeIf(String::isNotBlank)
        ?: ConversationTitle.fromFirstMessage(goal)
        ?: goal.ifBlank { fallback }
