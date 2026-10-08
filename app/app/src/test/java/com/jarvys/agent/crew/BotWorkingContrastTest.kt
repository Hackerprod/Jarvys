package com.jarvys.agent.crew

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.jarvys.agent.ui.jarvysColorScheme
import com.jarvys.agent.ui.shell.effortContrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

class BotWorkingContrastTest {
    @Test fun lightSweepPreservesNameAndBuiltinGlyphContrast() = verify(false)
    @Test fun darkSweepPreservesNameAndBuiltinGlyphContrast() = verify(true)
    private fun verify(dark: Boolean) {
        val colors = jarvysColorScheme(dark)
        val nameInk = if (dark) colors.primary else colors.onSurface
        for (step in 0..100) {
            val overlay = Color.White.copy(alpha = BOT_WORKING_SWEEP_ALPHA * step / 100f)
            assertTrue("Name contrast at sweep step $step", effortContrastRatio(overlay.compositeOver(nameInk), colors.background) >= 4.5f)
            assertTrue("Glyph contrast at sweep step $step", effortContrastRatio(overlay.compositeOver(colors.primary),
                overlay.compositeOver(colors.primaryContainer)) >= 3f)
        }
    }
}
