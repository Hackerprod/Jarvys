package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DrawerConversationPresentationTest {
    @Test fun flatDrawerListFiltersCaseInsensitivelyAndSortsNewestFirst() {
        val older = RunHistoryItem("old", "Alpha request", "CHAT", 0, 2, 10.0, title = "Alpha title")
        val newer = RunHistoryItem("new", "Beta request", "CHAT", 0, 2, 20.0, title = "Beta title")
        assertEquals(listOf("new", "old"), drawerChatsForDisplay(listOf(older, newer), "").map { it.id })
        assertEquals(listOf("old"), drawerChatsForDisplay(listOf(newer, older), "ALPHA").map { it.id })
        assertEquals(listOf("new"), drawerChatsForDisplay(listOf(older, newer), "beta title").map { it.id })
        assertTrue(drawerChatsForDisplay(emptyList(), "nothing").isEmpty())
    }

    @Test fun drawerTitleContainsOnlyTheChatTitleAndNoMessageCount() {
        assertEquals("Chosen title", drawerConversationTitle("Chosen title", "user goal", "Chat"))
        val generated = drawerConversationTitle(null, "A task about calendars", "Chat")
        assertTrue(generated.isNotBlank())
        assertFalse(generated.contains("messages", ignoreCase = true))
    }

    @Test fun conversationRowsShowSelectionAndActiveSessionState() {
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val source = File(module, "src/main/java/com/jarvys/agent/ui/chat/ConversationDrawer.kt").readText()
        val start = source.indexOf("private fun SessionRow")
        assertTrue(start >= 0)
        val row = source.substring(start)
        assertTrue(row.contains("Text(title"))
        assertTrue(row.contains("selected"))
        assertTrue(row.contains("if (active) Icon(LucideIcons.Circle"))
    }

    @Test fun sourceDrawerHasNoDateGroupsOrMaterialIconGlyphReferences() {
        val module = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = sequenceOf(File(module, "src/main"), File(module, "app/src/main"))
            .first { it.isDirectory }
        val kotlinFiles = Files.walk(sourceRoot.toPath()).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { String(Files.readAllBytes(it), Charsets.UTF_8) }.toList()
        }
        assertFalse(kotlinFiles.any { "drawerRunDateLabel" in it || "groupedRuns" in it })
        val materialIconReference = Regex("(?<![A-Za-z0-9_])Icons\\.(?:Default|Outlined|Filled|Rounded|AutoMirrored)\\.")
        assertFalse(kotlinFiles.any(materialIconReference::containsMatchIn))
    }
}
