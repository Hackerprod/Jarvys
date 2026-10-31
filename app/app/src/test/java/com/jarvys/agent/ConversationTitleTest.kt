package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitleTest {
    @Test
    fun normalizesTheModelTitleAndCapsItsLength() {
        assertEquals("Métricas del servidor", ConversationTitle.normalizeModelTitle("Title: \"Métricas del servidor\"\n"))
        val longTitle = ConversationTitle.normalizeModelTitle(
            "Primer mensaje con un tema concreto y texto adicional que excede el límite normal para un título de conversación que debe seguir siendo breve",
        )
        assertTrue(longTitle.orEmpty().length <= ConversationTitle.MAX_CHARS)
        assertTrue(longTitle.orEmpty().endsWith("…"))
    }

    @Test
    fun derivesALegacyFallbackFromTheFirstMessageWithoutRenamingOnLaterContent() {
        assertEquals("Revisar el proceso del servidor.", ConversationTitle.fromFirstMessage("Revisar el proceso del servidor. Además, comprueba el uso de memoria."))
        assertNull(ConversationTitle.fromFirstMessage("/settings"))
    }
}
