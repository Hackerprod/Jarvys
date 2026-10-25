package com.jarvys.agent.chatdb

/** Query terms and bounded excerpts shared by `memory_search` and `history_search`; no language-specific rule. */
object SearchTerms {
    /** The words a query is made of, as the memory and history searches match them. */
    @JvmStatic
    fun of(query: String): List<String> = PersonalMemoryFtsSql.terms(query)

    /**
     * The line holding the first query term, shortened around that term when the line itself exceeds [maxChars];
     * the first line when no term is found. An omitted side is marked with an ellipsis.
     */
    @JvmStatic
    fun excerpt(text: String, terms: List<String>, maxChars: Int): String {
        if (text.isEmpty()) return ""
        val budget = maxChars.coerceAtLeast(1)
        val lower = text.lowercase(java.util.Locale.ROOT)
        val found = terms.map { lower.indexOf(it.lowercase(java.util.Locale.ROOT)) }.filter { it >= 0 }.minOrNull() ?: 0
        val lineStart = text.lastIndexOf('\n', found - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', found).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        if (line.length <= budget) return line.trim()
        var from = (found - lineStart - budget / 2).coerceAtLeast(0)
        var to = (from + budget).coerceAtMost(line.length)
        from = (to - budget).coerceAtLeast(0)
        if (from in 1 until line.length && Character.isLowSurrogate(line[from])) from++
        if (to < line.length && to > from && Character.isHighSurrogate(line[to - 1])) to--
        return (if (from > 0) "…" else "") + line.substring(from, to).trim() + (if (to < line.length) "…" else "")
    }
}
