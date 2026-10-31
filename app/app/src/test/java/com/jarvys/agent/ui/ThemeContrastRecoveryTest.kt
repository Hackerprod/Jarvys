package com.jarvys.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.jarvys.agent.JarvysPalette
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeContrastRecoveryTest {
    @Test fun readableIdentityIsPreserved() {
        assertEquals(Color.Black, readableThemeInk(Color.Black, Color.White, Color.Black))
        assertEquals(JarvysPalette.GlassLight, readableThemeInk(JarvysPalette.GlassLight, Color.White, Color.Black))
    }

    @Test fun adjustedInkMeetsRenderedSrgbContrastForBothThemesAndAlpha() {
        for (dark in listOf(false, true)) {
            val scheme = jarvysColorScheme(dark)
            for (identity in listOf(scheme.background, Color.Red, Color.Blue.copy(alpha = 0.2f), Color.Transparent)) {
                val adjusted = readableThemeInk(identity, scheme.background, scheme.onSurface)
                assertTrue(contrast(adjusted, scheme.background) >= 4.7)
            }
        }
    }

    @Test fun unreadableFallbackIsReturnedUnchanged() {
        val fallback = Color.White.copy(alpha = 0.5f)
        assertEquals(fallback, readableThemeInk(Color.White, Color.White, fallback))
    }

    @Test fun requiresOpaqueBackgroundAndValidContrast() {
        assertThrows(IllegalArgumentException::class.java) { readableThemeInk(Color.Red, Color.Transparent, Color.Black) }
        for (contrast in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.9, 21.1)) {
            assertThrows(IllegalArgumentException::class.java) { readableThemeInk(Color.Red, Color.White, Color.Black, contrast) }
        }
    }

    @Test fun recoveredSurfaceTonesAndWarningColorsAreWiredIntoSchemes() {
        val light = jarvysColorScheme(false)
        val dark = jarvysColorScheme(true)
        assertEquals(Color(0xFFE9EEF7), light.surfaceContainerHigh)
        assertEquals(Color(0xFF1B2435), dark.surfaceContainerHigh)
        assertEquals(Color(0xFF895D18), light.secondary)
        assertEquals(JarvysPalette.BrassDark, dark.secondary)
        assertEquals(light.secondary, light.tertiary)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((argb ushr shift) and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
        }
        val first = luminance(Color(foreground.toArgb()).compositeOver(background).toArgb())
        val second = luminance(background.toArgb())
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }
}
