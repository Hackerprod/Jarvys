package com.jarvys.agent

/** Pure formatting and conflict helpers shared by the memory screen and JVM tests. */
object MemoryUiLogic {
    enum class ValidationIssue { FRONTMATTER, INDEX, DEPTH, FILE_LIMIT, CORE_LIMIT, SECRET, PATH, COLLISION, GENERAL }
    enum class DiffKind { ADDED, REMOVED, UNCHANGED }
    data class DiffLine(val kind: DiffKind, val text: String)
    data class EditorDocument(val name: String, val description: String, val body: String)

    fun validationIssue(error: String?): ValidationIssue {
        val text = error.orEmpty().lowercase()
        return when {
            "secret" in text || "credential" in text || "password" in text -> ValidationIssue.SECRET
            "frontmatter" in text || "name and description" in text || "name is required" in text
                || "description is required" in text || "one line" in text -> ValidationIssue.FRONTMATTER
            "missing required index" in text || "root memory index" in text || "memory.md" in text -> ValidationIssue.INDEX
            "depth" in text -> ValidationIssue.DEPTH
            "core memory" in text -> ValidationIssue.CORE_LIMIT
            "characters" in text || "20,000" in text -> ValidationIssue.FILE_LIMIT
            "collision" in text || "case-insensitive" in text -> ValidationIssue.COLLISION
            "path" in text || "symlink" in text || "symbolic link" in text || "skills/" in text -> ValidationIssue.PATH
            else -> ValidationIssue.GENERAL
        }
    }

    fun hasRevisionConflict(expectedRevisionId: Long, actualRevisionId: Long): Boolean =
        expectedRevisionId != actualRevisionId

    fun sessionMemoryDisabledKey(sessionId: String): String = "chat_without_memory_$sessionId"

    fun parseDocument(path: String, raw: String): EditorDocument {
        if (path == MemoryConstants.ROOT_INDEX || path.endsWith("/${MemoryConstants.ROOT_INDEX}")) {
            return EditorDocument("", "", raw)
        }
        val text = raw.replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return EditorDocument("", "", text)
        val end = text.indexOf("\n---\n", 4)
        if (end < 0) return EditorDocument("", "", text)
        var name = ""
        var description = ""
        text.substring(4, end).lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                when (line.substring(0, separator).trim()) {
                    "name" -> name = line.substring(separator + 1).trim()
                    "description" -> description = line.substring(separator + 1).trim()
                }
            }
        }
        return EditorDocument(name, description, text.substring(end + 5))
    }

    fun serializeDocument(path: String, name: String, description: String, body: String): String {
        if (path == MemoryConstants.ROOT_INDEX || path.endsWith("/${MemoryConstants.ROOT_INDEX}")) return body
        require(name.trim().isNotEmpty()) { "name is required" }
        require(description.trim().isNotEmpty()) { "description is required" }
        require(!name.contains('\n') && !description.contains('\n') && !name.contains('\r') && !description.contains('\r')) {
            "name and description must each be one line"
        }
        return "---\nname: ${name.trim()}\ndescription: ${description.trim()}\n---\n$body"
    }

    fun charCountLabel(used: Int, maximum: Int): String = "${used.coerceAtLeast(0)} / $maximum"

    /** Bounded, dependency-free single-span diff for the compact history detail. */
    fun diffLines(before: String?, after: String?): List<DiffLine> {
        val oldLines = before.orEmpty().split('\n')
        val newLines = after.orEmpty().split('\n')
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix
            && oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]) suffix++
        val result = ArrayList<DiffLine>()
        for (index in 0 until prefix) result += DiffLine(DiffKind.UNCHANGED, oldLines[index])
        for (index in prefix until oldLines.size - suffix) result += DiffLine(DiffKind.REMOVED, oldLines[index])
        for (index in prefix until newLines.size - suffix) result += DiffLine(DiffKind.ADDED, newLines[index])
        for (index in suffix downTo 1) result += DiffLine(DiffKind.UNCHANGED, oldLines[oldLines.size - index])
        return result.takeLast(160)
    }

    fun shortContent(value: String?, maximum: Int = 280): String {
        val text = value.orEmpty()
        return if (text.length <= maximum) text else text.take(maximum).trimEnd() + "…"
    }
}
