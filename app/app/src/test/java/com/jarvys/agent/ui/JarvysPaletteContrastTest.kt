package com.jarvys.agent.ui

import androidx.compose.ui.graphics.Color
import com.jarvys.agent.JarvysPalette
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM WCAG checks for the Material color pairs used by both theme schemes. */
class JarvysPaletteContrastTest {
    private data class PairCheck(val name: String, val foreground: Color, val background: Color,
                                 val minimum: Double)

    @Test fun lightAndDarkPalettePairsMeetWcagTextAndComponentThresholds() {
        val checks = listOf(
            PairCheck("dark onSurface/surface", JarvysPalette.InkDark, JarvysPalette.SurfaceDark, 4.5),
            PairCheck("dark onBackground/background", JarvysPalette.InkDark, JarvysPalette.CanvasDark, 4.5),
            PairCheck("dark onSurfaceVariant/surface", JarvysPalette.SecondaryInkDark, JarvysPalette.SurfaceDark, 4.5),
            PairCheck("dark onSurfaceVariant/raised", JarvysPalette.SecondaryInkDark, JarvysPalette.RaisedDark, 4.5),
            PairCheck("dark primary/surface", JarvysPalette.GlassDark, JarvysPalette.SurfaceDark, 4.5),
            PairCheck("dark primary/background", JarvysPalette.GlassDark, JarvysPalette.CanvasDark, 4.5),
            PairCheck("dark onPrimary/primary", JarvysPalette.CanvasDark, JarvysPalette.GlassDark, 4.5),
            PairCheck("dark error/surface", JarvysPalette.ClayDark, JarvysPalette.SurfaceDark, 4.5),
            PairCheck("dark brass/surface", JarvysPalette.BrassDark, JarvysPalette.SurfaceDark, 4.5),
            PairCheck("dark outline/surface", JarvysPalette.SecondaryInkDark, JarvysPalette.SurfaceDark, 3.0),
            PairCheck("light onSurface/surface", JarvysPalette.InkLight, JarvysPalette.SurfaceLight, 4.5),
            PairCheck("light onBackground/background", JarvysPalette.InkLight, JarvysPalette.CanvasLight, 4.5),
            PairCheck("light onSurfaceVariant/surface", JarvysPalette.SecondaryInkLight, JarvysPalette.SurfaceLight, 4.5),
            PairCheck("light onSurfaceVariant/raised", JarvysPalette.SecondaryInkLight, JarvysPalette.RaisedLight, 4.5),
            PairCheck("light primary/surface", JarvysPalette.GlassLight, JarvysPalette.SurfaceLight, 4.5),
            PairCheck("light primary/background", JarvysPalette.GlassLight, JarvysPalette.CanvasLight, 4.5),
            PairCheck("light onPrimary/primary", JarvysPalette.SurfaceLight, JarvysPalette.GlassLight, 4.5),
            PairCheck("light error/surface", JarvysPalette.ClayLight, JarvysPalette.SurfaceLight, 4.5),
            PairCheck("light brass/surface", JarvysPalette.BrassLight, JarvysPalette.SurfaceLight, 4.5),
            PairCheck("light outline/surface", JarvysPalette.SecondaryInkLight, JarvysPalette.SurfaceLight, 3.0),
        )
        checks.forEach { pair ->
            val measured = contrast(pair.foreground, pair.background)
            assertTrue("${pair.name}: measured ${"%.3f".format(java.util.Locale.ROOT, measured)}:1, requires ${pair.minimum}:1",
                measured >= pair.minimum)
        }
    }

    @Test fun floatingComposerSurfaceAtItsChosenAlphaKeepsTextContrastOverExtremeChatColors() {
        val alpha = 0.94
        val extremes = listOf(Color.Black, Color.White)
        listOf(
            JarvysPalette.InkLight to JarvysPalette.SurfaceLight,
            JarvysPalette.InkDark to JarvysPalette.SurfaceDark,
        ).forEach { (foreground, surface) ->
            extremes.forEach { backdrop ->
                val composite = composite(surface, backdrop, alpha)
                assertTrue("foreground=$foreground surface=$surface backdrop=$backdrop",
                    contrast(foreground, composite) >= 4.5)
            }
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val luminances = listOf(luminance(first), luminance(second)).sortedDescending()
        return (luminances[0] + 0.05) / (luminances[1] + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private fun composite(surface: Color, backdrop: Color, alpha: Double) = Color(
        red = (surface.red * alpha + backdrop.red * (1.0 - alpha)).toFloat(),
        green = (surface.green * alpha + backdrop.green * (1.0 - alpha)).toFloat(),
        blue = (surface.blue * alpha + backdrop.blue * (1.0 - alpha)).toFloat(),
    )
}
