package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LucideIconsTest {
    @Test fun everyGeneratedLucideImageVectorHasTheExpectedViewportAndPaths() {
        val icons = LucideIcons.all()
        assertTrue(icons.isNotEmpty())
        icons.forEach { icon ->
            assertEquals(24f, icon.viewportWidth, 0f)
            assertEquals(24f, icon.viewportHeight, 0f)
            assertTrue(icon.name.isNotBlank())
        }
    }

    @Test fun generatedPathsResetTheSvgCurrentPointBetweenIndependentElements() {
        val source = File(requireNotNull(System.getProperty("user.dir")),
            "src/main/java/com/jarvys/agent/LucideIcons.kt").readText()
        val accessibility = Regex("""val Accessibility:.*?vector\("Accessibility", "([^"]+)"\)""")
            .find(source)?.groupValues?.get(1)
        assertTrue("Accessibility SVG has independent circle/path elements", accessibility != null)
        assertTrue("Relative path moveto must restart from SVG origin", accessibility!!.contains("M 0 0 m18 19"))
        assertEquals("Four following SVG elements each reset to origin", 4,
            Regex("M 0 0").findAll(accessibility).count())
        assertEquals(57, LucideIcons.all().size)
        assertTrue(LucideIcons.all().any { it.name == "Mic" })
        assertTrue(LucideIcons.all().any { it.name == "Share" })
    }

    @Test fun appSourceDoesNotUseMaterialIconGlyphs() {
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = sequenceOf(File(module, "src/main"), File(module, "app/src/main"))
            .first { it.isDirectory }
        val forbidden = Regex("(?<![A-Za-z0-9_])Icons\\.(?:Default|Outlined|Filled|Rounded|AutoMirrored)\\.")
        val violations = mutableListOf<String>()
        Files.walk(sourceRoot.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { path ->
                val source = String(Files.readAllBytes(path), Charsets.UTF_8)
                forbidden.findAll(source).forEach { match ->
                    violations += "${path.fileName}:${source.take(match.range.first).count { it == '\n' } + 1}"
                }
            }
        }
        assertFalse("Material icon references remain: ${violations.joinToString()}", violations.isNotEmpty())
    }
}
