package com.jarvys.agent.proactive

import java.util.Locale

/** One plain-text projection shared by the Proactive chat message and its Android notification. */
object ProactiveTextSanitizer {
    fun sanitize(raw: String): String {
        var text = raw
            .replace(Regex("(?is)```.*?```|~~~.*?~~~"), " ")
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("!?(?:\\[([^\\]]*)])\\((?:[^()]|\\([^)]*\\))*\\)"), "$1")
            .replace(Regex("(?i)\\b(?:jarvys|webcite|workspace|content|file)://[^\\s)]+"), " ")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*(?:[-+*]|\\d+[.)])\\s+"), "")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
            .replace(Regex("\\*\\*|__|~~|`|\\*|_"), "")
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("[\\p{Z}\\s]+"), " ").trim()
        return text
    }
}

object ProactiveThreadKey {
    fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-").trim('-')

    fun fingerprint(raw: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(normalize(raw).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
