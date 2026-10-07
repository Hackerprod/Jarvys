package com.jarvys.agent

/** Small deterministic title normalization and legacy fallback for one conversation's first message. */
object ConversationTitle {
    const val MAX_CHARS = 60

    @JvmStatic
    fun normalizeModelTitle(raw: String?): String? {
        val line = raw.orEmpty().replace('\r', '\n').lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
            ?: return null
        val title = line
            .replace(Regex("^title\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '\'', '`')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.isEmpty()) return null
        return truncate(title)
    }

    @JvmStatic
    fun fromFirstMessage(message: String?): String? {
        val normalized = message.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty() || normalized.startsWith("/")) return null
        val firstSentence = normalized.split(Regex("(?<=[.!?])\\s+"), limit = 2).first().trim()
        return truncate(firstSentence).takeIf(String::isNotBlank)
    }

    private fun truncate(value: String): String {
        if (value.length <= MAX_CHARS) return value
        val prefix = value.substring(0, MAX_CHARS - 1)
        val wordBoundary = prefix.lastIndexOf(' ')
        return (if (wordBoundary > MAX_CHARS * 0.6) prefix.substring(0, wordBoundary) else prefix).trimEnd() + "…"
    }
}
