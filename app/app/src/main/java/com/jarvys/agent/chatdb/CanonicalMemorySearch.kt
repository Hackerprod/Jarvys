package com.jarvys.agent.chatdb

/** Pinned lexical grammar; presentation excerpts must not feed this parser. */
object CanonicalMemorySearch {
    @JvmStatic fun normalizeLimit(limit: Int): Int = if (limit <= 0) 10 else minOf(limit, 20)
    @JvmStatic fun normalizeMatchMode(raw: String?): String = when (raw) {
        null, "", "all" -> "all"
        "any" -> "any"
        else -> throw IllegalArgumentException("memory_search_invalid_match_mode")
    }
    @JvmStatic fun terms(query: String): List<String> = MemoryObservationContent.fields(query)
        .map { it.trim('"') }.filter(String::isNotEmpty)
    @JvmStatic fun matchExpression(terms: List<String>, mode: String): String = terms.joinToString(
        if (normalizeMatchMode(mode) == "any") " OR " else " AND "
    ) { "\"${it.replace("\"", "\"\"")}\"" }
    fun usesLike(terms: List<String>): Boolean = terms.any { it.codePointCount(0, it.length) < 3 }
    fun likeTerm(term: String): String = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    /** Integer floor before SQLite date conversion, including pre-epoch timestamps. */
    fun seconds(column: String): String = "(($column / 1000) - CASE WHEN $column < 0 AND $column % 1000 != 0 THEN 1 ELSE 0 END)"
    fun date(column: String): String = "datetime(${seconds(column)},'unixepoch')"
}
