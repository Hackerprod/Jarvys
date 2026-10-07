package com.jarvys.agent.skills

import java.nio.charset.StandardCharsets

data class SkillMetadata(
    val id: String,
    val name: String,
    val description: String,
    val version: Int,
    val allowedTools: List<String>,
    val tags: List<String>,
)

data class ParsedSkill(val metadata: SkillMetadata, val body: String)

/** Parses the deliberately small, non-executable YAML subset documented in DESIGN_E.md §4. */
object SkillMarkdownParser {
    const val MAX_DOCUMENT_BYTES = 256 * 1024
    private val allowedKeys = setOf("id", "name", "description", "version", "allowed-tools", "tags")

    fun parse(markdown: String): ParsedSkill {
        require(markdown.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Skill Markdown exceeds 256 KiB" }
        val source = markdown.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = source.split('\n')
        require(lines.firstOrNull() == "---") { "Skill must start with YAML frontmatter delimiter ---" }
        val end = (1 until lines.size).firstOrNull { lines[it] == "---" }
            ?: throw IllegalArgumentException("Skill frontmatter has no closing --- delimiter")
        val values = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, MutableList<String>>()
        var activeList: String? = null

        for (index in 1 until end) {
            val line = lines[index]
            require(line.length <= MAX_FRONTMATTER_LINE_CHARS) { "Frontmatter line ${index + 1} is too long" }
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            require('\t' !in line) { "Tabs are not supported in YAML frontmatter" }
            if (line.startsWith("  - ")) {
                val key = activeList ?: throw IllegalArgumentException("List item is not attached to an allowed list")
                lists.getValue(key).add(unquote(line.substring(4).trim()))
                continue
            }
            require(!line.startsWith(' ') && !line.startsWith('-')) { "Nested YAML structures are not supported" }
            val separator = line.indexOf(':')
            require(separator > 0) { "Invalid frontmatter entry on line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            require(key in allowedKeys) { "Unknown skill metadata key: $key" }
            require(!values.containsKey(key) && !lists.containsKey(key)) { "Duplicate skill metadata key: $key" }
            val raw = line.substring(separator + 1).trim()
            if (raw.isEmpty() && key in setOf("allowed-tools", "tags")) {
                lists[key] = mutableListOf()
                activeList = key
            } else {
                activeList = null
                values[key] = raw
            }
        }

        val inlineAllowed = values.remove("allowed-tools")?.let(::parseInlineList)
        val inlineTags = values.remove("tags")?.let(::parseInlineList)
        require(inlineAllowed == null || lists["allowed-tools"] == null) { "allowed-tools cannot use both list formats" }
        require(inlineTags == null || lists["tags"] == null) { "tags cannot use both list formats" }
        val allowedTools = inlineAllowed ?: lists["allowed-tools"].orEmpty()
        val tags = inlineTags ?: lists["tags"].orEmpty()

        val id = scalar(values, "id", required = true)
        val name = scalar(values, "name", required = true)
        val description = scalar(values, "description", required = true)
        val versionText = scalar(values, "version", required = true)
        require(ID_PATTERN.matches(id)) { "Skill id must contain 1-128 letters, digits, dots, underscores or dashes" }
        require(name.length <= MAX_NAME_CHARS && name.isNotBlank() && cleanText(name)) { "Skill name is invalid" }
        require(description.length <= MAX_DESCRIPTION_CHARS && description.isNotBlank() && cleanText(description)) {
            "Skill description is invalid"
        }
        require(versionText == "1") { "Unsupported skill format version: $versionText" }
        require(allowedTools.size <= MAX_LIST_ITEMS && allowedTools.distinct().size == allowedTools.size) {
            "allowed-tools must be a unique list of at most $MAX_LIST_ITEMS entries"
        }
        require(allowedTools.all { TOOL_NAME_PATTERN.matches(it) }) { "allowed-tools contains an invalid tool name" }
        require(tags.size <= MAX_LIST_ITEMS && tags.distinct().size == tags.size) {
            "tags must be a unique list of at most $MAX_LIST_ITEMS entries"
        }
        require(tags.all { TAG_PATTERN.matches(it) }) { "tags contains an invalid tag" }

        val body = lines.drop(end + 1).joinToString("\n").trim()
        require(body.isNotBlank()) { "Skill body must contain instructions" }
        return ParsedSkill(
            SkillMetadata(id, name, description, versionText.toInt(), allowedTools, tags),
            body,
        )
    }

    private fun parseInlineList(raw: String): List<String> {
        require(raw.startsWith('[') && raw.endsWith(']')) { "Only flat YAML lists in [item, item] form are supported here" }
        val inside = raw.substring(1, raw.length - 1).trim()
        if (inside.isEmpty()) return emptyList()
        return inside.split(',').map { unquote(it.trim()) }.also { values ->
            require(values.none(String::isBlank)) { "Skill metadata lists cannot contain empty items" }
        }
    }

    private fun unquote(value: String): String {
        require(value.isNotEmpty()) { "Skill metadata values cannot be empty" }
        if (value == "*") return value
        if (value.first() == '\'' || value.first() == '"') {
            require(value.length >= 2 && value.last() == value.first()) { "Unclosed quoted YAML scalar" }
            val inner = value.substring(1, value.length - 1)
            if (value.first() == '"') {
                require('\\' !in inner) { "Escaped YAML strings are not supported" }
            }
            return inner
        }
        require(!value.startsWith('&') && !value.startsWith('*') && !value.startsWith('!')) {
            "YAML anchors, aliases and tags are not supported"
        }
        return value
    }

    private fun scalar(values: Map<String, String>, key: String, required: Boolean): String {
        val raw = values[key]
        if (raw == null) {
            require(!required) { "Skill metadata is missing required key '$key'" }
            return ""
        }
        return unquote(raw)
    }

    private fun cleanText(value: String): Boolean = value.none(Char::isISOControl)

    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val TOOL_NAME_PATTERN = Regex("[A-Za-z0-9_:.~*-]{1,128}")
    private val TAG_PATTERN = Regex("[A-Za-z0-9_.-]{1,40}")
    private const val MAX_FRONTMATTER_LINE_CHARS = 4096
    private const val MAX_NAME_CHARS = 120
    private const val MAX_DESCRIPTION_CHARS = 600
    private const val MAX_LIST_ITEMS = 64
}
