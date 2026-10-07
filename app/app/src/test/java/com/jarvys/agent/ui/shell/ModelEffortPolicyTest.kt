package com.jarvys.agent.ui.shell

import androidx.compose.ui.graphics.Color
import com.jarvys.agent.JarvysPalette
import com.jarvys.agent.ModelVariant
import org.junit.Assert.*
import org.junit.Test

class ModelEffortPolicyTest {
    private fun variants(vararg ids: String) = ids.map { ModelVariant(it, it, "auto", "medium") }

    @Test fun appearanceUsesSelectedVariantThenMediumThenFirst() {
        assertNull(modelEffortAppearance(emptyList(), "high"))
        assertEquals(ModelEffortAppearance(2, 2), modelEffortAppearance(variants("low", "medium", "high"), "high"))
        assertEquals(ModelEffortAppearance(1, 2), modelEffortAppearance(variants("low", "medium", "high"), "unknown"))
        assertEquals(ModelEffortAppearance(0, 1), modelEffortAppearance(variants("low", "high"), "unknown"))
        assertEquals(ModelEffortAppearance(0, 0), modelEffortAppearance(variants("medium"), "medium"))
    }

    @Test fun touchStopsClampAndMirrorInRtl() {
        assertEquals(0, effortIndexAt(-100f, 200f, 18f, 4, false))
        assertEquals(4, effortIndexAt(400f, 200f, 18f, 4, false))
        assertEquals(2, effortIndexAt(100f, 200f, 18f, 4, false))
        assertEquals(4, effortIndexAt(18f, 200f, 18f, 4, true))
        assertEquals(0, effortIndexAt(182f, 200f, 18f, 4, true))
        for (index in 0..4) {
            val x = 18f + 164f * index / 4f
            assertEquals(index, effortIndexAt(x, 200f, 18f, 4, false))
            assertEquals(4 - index, effortIndexAt(x, 200f, 18f, 4, true))
        }
    }

    @Test fun degenerateTrackOrSingleVariantHasFirstStop() {
        assertEquals(0, effortIndexAt(10f, 36f, 18f, 4, false))
        assertEquals(0, effortIndexAt(10f, 0f, 18f, 4, true))
        assertEquals(0, effortIndexAt(100f, 200f, 18f, 0, false))
    }

    @Test fun trackAndGradientKeepWhiteMarkersReadable() {
        for (primary in listOf(JarvysPalette.GlassLight, JarvysPalette.GlassDark, Color.White)) {
            for (index in 0..6) {
                effortGradientColors(primary, index, 6).forEach {
                    assertTrue("white marker contrast", effortContrastRatio(Color.White, it) >= 4.5f)
                }
            }
        }
        assertEquals(Color.Black, effortTrackColor(Color.Black))
    }

    @Test fun gradientProgressIsFractionalAndClamped() {
        val low = effortGradientColors(JarvysPalette.GlassLight, 0, 4)
        val middle = effortGradientColors(JarvysPalette.GlassLight, 2, 4)
        val high = effortGradientColors(JarvysPalette.GlassLight, 4, 4)
        assertEquals(low[0], low[1])
        assertNotEquals(low[1], middle[1])
        assertNotEquals(middle[1], high[1])
        assertEquals(low, effortGradientColors(JarvysPalette.GlassLight, -3, 4))
        assertEquals(high, effortGradientColors(JarvysPalette.GlassLight, 20, 4))
    }

    @Test fun sparkleHasQuietPhaseAndBoundedPeak() {
        assertEquals(0f, effortSparkleAlpha(0f, 0), 0.0001f)
        assertEquals(0.76f, effortSparkleAlpha(0.31f, 0), 0.0001f)
        assertEquals(0f, effortSparkleAlpha(0.8f, 0), 0.0001f)
        for (particle in 0..17) for (step in 0..100) {
            assertTrue(effortSparkleAlpha(step / 100f, particle) in 0f..0.76001f)
        }
    }
}
