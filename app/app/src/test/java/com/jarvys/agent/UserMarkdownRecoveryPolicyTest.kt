package com.jarvys.agent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.UriHandler
import com.jarvys.agent.skills.SkillFileLink
import org.junit.Assert.*
import org.junit.Test

class UserMarkdownRecoveryPolicyTest {
    @Test fun userLinksIgnoreInternalSchemesButKeepOrdinaryLinks() {
        val opened = mutableListOf<String>()
        val skills = mutableListOf<SkillFileLink>()
        val handler = messageMarkdownUriHandler(object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }, userMessage = true) { skills += it }
        handler.openUri("jarvys://skill/example/SKILL.md")
        handler.openUri("JARVYS:internal")
        handler.openUri("webcite:fixture")
        handler.openUri("WEBCITE:fixture")
        handler.openUri("https://example.test/docs")
        assertEquals(listOf("https://example.test/docs"), opened)
        assertTrue(skills.isEmpty())
    }

    @Test fun userLinkWithoutPlatformHandlerDoesNotCrash() {
        val handler = messageMarkdownUriHandler(object : UriHandler {
            override fun openUri(uri: String): Unit = error("No activity can open this local fixture")
        }, userMessage = true) { error("Unexpected skill link") }
        handler.openUri("https://example.test/docs")
    }

    @Test fun codeBackgroundKeepsReadableContrastInBothBubbleTones() {
        for ((text, bubble) in listOf(Color.Black to Color(0xFFDDE8FF), Color.White to Color(0xFF19345B))) {
            val background = userMarkdownCodeBackground(text, bubble)
            assertTrue(colorContrastRatio(text.toArgb().toLong(), background.toArgb().toLong()) >= 4.5)
            assertNotEquals("Readable bubble tones retain a distinct code background", bubble, background)
        }
        val bubble = Color(0xFF767676)
        assertEquals("Do not reduce an already marginal contrast", bubble, userMarkdownCodeBackground(Color.White, bubble))
    }
}
