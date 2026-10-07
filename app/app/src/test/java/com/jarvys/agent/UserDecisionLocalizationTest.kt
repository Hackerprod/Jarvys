package com.jarvys.agent

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDecisionLocalizationTest {
    @Test fun decisionCardResourcesHaveEnglishSpanishParityAndComposableUsesResources() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val root = sequenceOf(working, File(working, "app"), File(working.parentFile, "app"))
            .first { File(it, "src/main/res/values/strings.xml").isFile }
        fun keys(file: File): Set<String> {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val nodes = builder.parse(file).getElementsByTagName("string")
            return (0 until nodes.length).mapNotNull {
                nodes.item(it).attributes?.getNamedItem("name")?.nodeValue
            }.toSet()
        }
        val english = keys(File(root, "src/main/res/values/strings.xml")).filter { it.startsWith("user_decision_") }.toSet()
        val spanish = keys(File(root, "src/main/res/values-es/strings.xml")).filter { it.startsWith("user_decision_") }.toSet()
        assertTrue("English decision resources are missing", english.isNotEmpty())
        assertEquals(english, spanish)

        val timeline = File(root, "src/main/java/com/jarvys/agent/ui/chat/ConversationTimeline.kt").readText()
        val card = timeline.substringAfter("internal fun UserDecisionEventCard(").substringBefore("private fun ApprovalDecisionCard(")
        assertFalse(Regex("Text\\s*\\(\\s*\"[A-Za-z]").containsMatchIn(card))
        assertTrue(card.contains("R.string.user_decision_heading"))
        assertTrue(card.contains("R.string.user_decision_unanswered"))
    }
}
