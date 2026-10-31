package com.jarvys.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AlertDialogScrollSourceTest {
    private data class TextSlot(val file: String, val line: Int, val body: String)

    @Test fun everyMainSourceAlertDialogTextSlotUsesScrollableDialogContent() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = sequenceOf(
            File(workingDirectory, "src/main"),
            File(workingDirectory, "app/src/main"),
            File(workingDirectory.parentFile, "app/src/main"),
        ).firstOrNull { it.isDirectory } ?: error("Could not locate app/src/main from ${workingDirectory.path}")
        val dialogPattern = Regex("\\bAlertDialog\\s*\\(")
        val textPattern = Regex("\\btext\\s*=\\s*\\{")
        val helperPattern = Regex("\\bScrollableDialogContent\\b")
        val slots = mutableListOf<TextSlot>()

        Files.walk(sourceRoot.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { path ->
                val source = String(Files.readAllBytes(path), Charsets.UTF_8)
                dialogPattern.findAll(source).forEach { dialog ->
                    val callEnd = matchingDelimiter(source, dialog.range.last, '(', ')')
                        ?: error("Unclosed AlertDialog at ${path.fileName}:${lineAt(source, dialog.range.first)}")
                    val call = source.substring(dialog.range.first, callEnd + 1)
                    val text = textPattern.find(call)
                    assertTrue("AlertDialog at ${path.fileName}:${lineAt(source, dialog.range.first)} has no auditable text slot", text != null)
                    val textEnd = matchingDelimiter(call, text!!.range.last, '{', '}')
                        ?: error("Unclosed AlertDialog.text at ${path.fileName}:${lineAt(source, dialog.range.first)}")
                    slots += TextSlot(path.toString(), lineAt(source, dialog.range.first), call.substring(text.range.last + 1, textEnd))
                }
            }
        }

        assertTrue("No AlertDialog text slots found under ${sourceRoot.path}", slots.isNotEmpty())
        val missingHelper = slots.filterNot { helperPattern.containsMatchIn(it.body) }
        // There are currently no exceptions: former own-scroll slots have been migrated into the shared helper.
        assertFalse(
            "AlertDialog.text must use ScrollableDialogContent:\n" +
                missingHelper.joinToString("\n") { "${it.file}:${it.line}" },
            missingHelper.isNotEmpty(),
        )
    }

    private fun lineAt(source: String, index: Int): Int = source.take(index).count { it == '\n' } + 1

    /** Delimiter scanner skips comments and quoted text so nested lambdas are handled without dependencies. */
    private fun matchingDelimiter(source: String, opening: Int, open: Char, close: Char): Int? {
        var index = opening
        var depth = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    index = source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
                    continue
                }
                source.startsWith("/*", index) -> {
                    val end = source.indexOf("*/", index + 2)
                    index = if (end < 0) source.length else end + 2
                    continue
                }
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    index = if (end < 0) source.length else end + 3
                    continue
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index++]
                    var escaped = false
                    while (index < source.length) {
                        val character = source[index++]
                        if (escaped) escaped = false
                        else if (character == '\\') escaped = true
                        else if (character == quote) break
                    }
                    continue
                }
                source[index] == open -> depth++
                source[index] == close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }
}
