package com.jarvys.agent.mcp

import java.net.URI
import java.util.Locale

/** Remote MCP copy is display-only and explicitly marked as untrusted metadata. */
data class McpToolDescriptionPresentation(
    val shortText: String,
    val fullText: String,
    val isRemoteUntrusted: Boolean,
)

data class McpServerRowPresentation(
    val name: String,
    val status: McpConnectionStatus,
    val enabledTools: Int,
    val totalTools: Int,
)

data class McpListRowPresentation(val name: String, val state: McpConnectionStatus, val toolCount: Int)

object McpToolPresentation {
    private const val MAX_FULL_DESCRIPTION = 600
    private const val MAX_SHORT_DESCRIPTION = 110

    fun toolTitle(wireName: String, annotationTitle: String?): String {
        val title = annotationTitle.orEmpty()
            .replace(Regex("<[^>]*>"), " ")
            .let { McpToolSecurity.sanitizeRemoteText(it, 128) }
            .takeIf(String::isNotBlank)
        if (title != null) return title
        val normalized = wireName
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return ""
        return normalized.split(' ').joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }.take(128)
    }

    fun description(raw: String?): McpToolDescriptionPresentation {
        val bounded = McpToolSecurity.sanitizeRemoteText(raw.orEmpty(), 8_000)
        val cleaned = cleanMarkupAndExamples(bounded)
            .replace(Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[`*_~#>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val full = ellipsize(cleaned, MAX_FULL_DESCRIPTION)
        val firstSentence = Regex("(?<=[.!?])\\s+").split(cleaned)
            .firstOrNull(String::isNotBlank).orEmpty().trim()
        return McpToolDescriptionPresentation(
            shortText = ellipsize(firstSentence.ifBlank { cleaned }, MAX_SHORT_DESCRIPTION),
            fullText = full,
            isRemoteUntrusted = cleaned.isNotBlank(),
        )
    }

    fun serverRow(config: McpServerConfig, status: McpConnectionStatus) = McpServerRowPresentation(
        name = McpToolSecurity.sanitizeRemoteText(config.alias, 100),
        status = status,
        enabledTools = config.tools.count { it.enabled },
        totalTools = config.tools.size,
    )

    fun listRow(config: McpServerConfig, status: McpConnectionStatus) = McpListRowPresentation(
        name = McpToolSecurity.sanitizeRemoteText(config.alias, 100),
        state = status,
        toolCount = config.tools.size,
    )

    /** A disconnected state only affects availability; the persisted catalog remains the detail source. */
    fun detailTools(config: McpServerConfig): List<McpToolConfig> = config.tools

    fun endpointHost(endpoint: String): String = runCatching {
        val uri = URI(endpoint)
        buildString {
            append(uri.host ?: endpoint.substringAfter("://", endpoint).substringBefore('/'))
            if (uri.port >= 0) append(":${uri.port}")
        }
    }.getOrElse {
        McpToolSecurity.sanitizeRemoteText(endpoint, 80)
    }.take(80)

    private fun cleanMarkupAndExamples(input: String): String {
        var text = input.replace(Regex("(?s)```.*?```"), " ")
        text = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '{') {
                val end = jsonObjectEnd(text, index)
                if (end > index && text.substring(index, end).contains(':')) {
                    output.append(' ')
                    index = end
                    continue
                }
            }
            output.append(text[index])
            index++
        }
        return output.toString()
    }

    /** Returns the exclusive end of a balanced JSON-like object, respecting quoted braces. */
    private fun jsonObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return -1
    }

    private fun ellipsize(value: String, maximum: Int): String =
        if (value.length <= maximum) value else value.take(maximum - 1).trimEnd() + "…"
}
