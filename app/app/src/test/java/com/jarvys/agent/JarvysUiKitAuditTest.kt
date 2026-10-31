package com.jarvys.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JarvysUiKitAuditTest {
    @Test fun materialTopBarsAndOutlinedFieldsAreOnlyDeclaredByTheSharedKit() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not locate app/src/main/java from ${working.path}")
        val violations = mutableListOf<String>()
        Files.walk(sourceRoot.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() != "JarvysUiKit.kt" }
                .forEach { path ->
                    val source = String(Files.readAllBytes(path), Charsets.UTF_8)
                    Regex("\\b(?:TopAppBar|OutlinedTextField)\\s*\\(").findAll(source).forEach { match ->
                        val line = source.take(match.range.first).count { it == '\n' } + 1
                        violations += "${path.fileName}:$line"
                    }
                }
        }
        assertTrue("Material controls bypass JarvysUiKit: $violations", violations.isEmpty())
    }
}
