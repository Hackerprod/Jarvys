package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagePresentationTest {
    @Test fun assistantReadingColumnUsesAvailablePhoneWidthAndCapsWideLayouts() {
        assertEquals(360f, assistantReadingWidthLimitDp(360f), 0f)
        assertEquals(720f, assistantReadingWidthLimitDp(1200f), 0f)
        assertEquals(0f, assistantReadingWidthLimitDp(-10f), 0f)
    }

    @Test fun assistantTextColorsMeetWcagAaContrastOnLightAndDarkBackgrounds() {
        assertTrue(colorContrastRatio(0xFFE3E1E9, 0xFF1A1B21) >= 4.5)
        assertTrue(colorContrastRatio(0xFF1A1B21, 0xFFFEFBFF) >= 4.5)
        assertTrue(colorContrastRatio(0xFFFCB4BD, 0xFF1A1B21) >= 4.5)
        assertTrue(colorContrastRatio(0xFFBB0947, 0xFFFEFBFF) >= 4.5)
    }
}
