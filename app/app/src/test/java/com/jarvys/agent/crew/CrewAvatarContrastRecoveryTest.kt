package com.jarvys.agent.crew

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.jarvys.agent.ui.jarvysColorScheme
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class CrewAvatarContrastRecoveryTest {
    @Test fun identityStrokesRemainReadableAcrossBothFacesAndSurroundingSurfaces() {
        val identities = listOf(CrewOrbPalette.Captain, CrewOrbPalette.Explorer,
            CrewOrbPalette.Analyst, CrewOrbPalette.Critic, CrewOrbPalette.Writer, CrewOrbPalette.Operator)
        for (dark in listOf(false, true)) {
            val colors = jarvysColorScheme(dark)
            val backgrounds = listOf(colors.background, colors.surfaceContainerHigh,
                colors.tertiaryContainer.copy(alpha = 0.46f).compositeOver(colors.background),
                colors.surfaceVariant.copy(alpha = 0.54f).compositeOver(colors.background))
            for (background in backgrounds) {
                val face = colors.surface.copy(alpha = 0.98f).compositeOver(background)
                for (identity in identities) for (opacity in listOf(1f, 0.52f)) {
                    val ink = crewAvatarStrokeInk(identity.copy(alpha = opacity), face, background, colors.onSurface)
                    assertTrue("face contrast: $identity, dark=$dark", contrast(ink, face) >= 3.2)
                    assertTrue("background contrast: $identity, dark=$dark", contrast(ink, background) >= 3.2)
                }
            }
        }
    }

    @Test fun alreadyReadableStrokeKeepsItsIdentity() {
        assertEquals(Color.Black, crewAvatarStrokeInk(Color.Black, Color.White, Color.White, Color.Black))
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
