package com.jarvys.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchPromptTest {
    @Test
    fun generalSearchAndEvidenceGuidanceHasEnglishSpanishParityAndNoDomainTriggers() {
        val english = AgentPrompts.MEMORY_SEARCH_GUIDANCE_EN.lowercase()
        val spanish = AgentPrompts.MEMORY_SEARCH_GUIDANCE_ES.lowercase()
        listOf("search_files", "untrusted", "date", "source", "previous", "preferences", "name", "description", "approval")
            .forEach { assertTrue("English guidance missing $it", english.contains(it)) }
        listOf("search_files", "no confiables", "fecha", "fuente", "previo", "preferencias", "name", "description", "aprobación")
            .forEach { assertTrue("Spanish guidance missing $it", spanish.contains(it)) }
        listOf("email", "calendar", "contacts", "travel", "if the user asks")
            .forEach { trigger ->
                assertTrue("Guidance must remain domain-general", !english.contains(trigger) && !spanish.contains(trigger))
            }
    }

    @Test
    fun reflectionPromptRequiresDatedEvidenceNonDestructiveUpdatesAndOneValidPreferencesPage() {
        val prompt = MemoryReflectionPrompt.SYSTEM.lowercase()
        listOf("captured user message", "short evidence source", "previous value", "previously",
            "preferences.md", "frontmatter", "name", "description", "duplicate entries", "without the user's approval")
            .forEach { assertTrue("Reflection prompt missing $it", prompt.contains(it)) }
        assertTrue(prompt.contains("only an explicit user-authored confirmation"))
    }
}
