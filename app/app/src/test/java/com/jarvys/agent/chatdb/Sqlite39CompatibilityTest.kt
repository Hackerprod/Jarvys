package com.jarvys.agent.chatdb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source lint for SQL syntax unavailable in the oldest supported Android SQLite (3.9.2). */
class Sqlite39CompatibilityTest {
    @Test fun productionChatDatabaseSqlStaysWithinSqlite39() {
        val sourceRoot = locateChatDbSources()
        val forbidden = listOf(
            Regex("(?is)\\bON\\s+CONFLICT\\b[\\s\\S]{0,100}?\\bDO\\s+(?:UPDATE|NOTHING)\\b"),
            Regex("(?is)\\bRETURNING\\b"),
            Regex("(?is)\\bOVER\\s*\\("),
            Regex("(?is)\\bFILTER\\s*\\(\\s*WHERE\\b"),
            Regex("(?is)\\bALTER\\s+TABLE\\b[^;\\n]*\\bRENAME\\s+COLUMN\\b"),
            Regex("(?is)\\bALTER\\s+TABLE\\b[^;\\n]*\\bDROP\\s+COLUMN\\b"),
            Regex("(?is)\\bGENERATED\\s+ALWAYS\\s+AS\\b"),
            Regex("(?is)\\bSTRICT\\s*[,;)]"),
            Regex("(?is)\\bNULLS\\s+(?:FIRST|LAST)\\b"),
            Regex("(?is)\\bIIF\\s*\\("),
            Regex("(?is)\\b(?:UNIXEPOCH|FORMAT|CONCAT|CONCAT_WS|TIMEDIFF|UNISTR)\\s*\\("),
            Regex("(?is)\\bIS\\s+DISTINCT\\s+FROM\\b"),
            Regex("(?is)(?:\\b(?:WHERE|AND|OR)\\s*)?\\(\\s*[A-Za-z_][A-Za-z_0-9]*\\s*,\\s*[A-Za-z_][A-Za-z_0-9]*(?:\\s*,\\s*[A-Za-z_][A-Za-z_0-9]*)*\\s*\\)\\s*(?:<=|>=|<>|!=|=|<|>|\\bIN\\b)\\s*\\("),
            Regex("(?is)\\bJSON(?:_EXTRACT|_SET|_INSERT|_REMOVE|_TYPE|_ARRAY|_OBJECT|_VALID|_QUOTE|_PATCH|_EACH|_TREE)\\s*\\("),
            Regex("(?is)(?:->>|->)\\s*['\"]"),
            Regex("(?is)\\bAS\\s+MATERIALIZED\\b"),
        )
        val violations = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = file.readText()
                sqlLiteralGroups(source).flatMap { sql ->
                    forbidden.flatMap { pattern ->
                        pattern.findAll(sql.text).map { match ->
                            "${file.name}:${sql.line} ${match.value.replace(Regex("\\s+"), " ").take(80)}"
                        }
                    }
                }
            }.toList()
        assertTrue("SQLite 3.9 incompatible production SQL:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private data class SqlLiteralGroup(val line: Int, val text: String)

    private fun locateChatDbSources(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "app/app/src/main/java/com/jarvys/agent/chatdb")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate production chatdb sources for SQLite compatibility lint")
    }

    private fun sqlLiteralGroups(source: String): List<SqlLiteralGroup> {
        val literals = Regex("(?s)\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"").findAll(source).toList()
        val groups = mutableListOf<SqlLiteralGroup>()
        var previous: MatchResult? = null
        literals.forEach { literal ->
            val value = literal.value.removeSurrounding("\"\"\"").removeSurrounding("\"")
            val prior = previous
            val joinsPrevious = prior != null && source.substring(prior.range.last + 1, literal.range.first)
                .matches(Regex("\\s*\\+\\s*"))
            if (joinsPrevious && groups.isNotEmpty()) {
                val last = groups.removeAt(groups.lastIndex)
                groups += last.copy(text = last.text + value)
            } else {
                val line = source.substring(0, literal.range.first).count { it == '\n' } + 1
                groups += SqlLiteralGroup(line, value)
            }
            previous = literal
        }
        return groups
    }
}
