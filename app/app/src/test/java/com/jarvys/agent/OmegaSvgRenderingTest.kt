package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OmegaSvgRenderingTest {
    @Test fun omegaArrowheadUsesStrokeWidthUnitsUnlessUserSpaceIsRequested() {
        val omega = requireNotNull(javaClass.getResourceAsStream("/svg/omega.svg"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

        // SVG's default markerUnits is strokeWidth: the conforming head cross-section is 10 * 2.5 = 25 viewBox units.
        val defaultWidth = measuredArrowheadCrossSection(omega)
        assertEquals(25f, defaultWidth, 2f)

        assertFalse(omega.contains("markerUnits=\"userSpaceOnUse\""))
        val userSpaceSvg = omega.replace(
            "<marker id=\"arrow-omega\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"5\" orient=\"auto\">",
            "<marker id=\"arrow-omega\" markerWidth=\"10\" markerHeight=\"10\" refX=\"8\" refY=\"5\" orient=\"auto\" markerUnits=\"userSpaceOnUse\">",
        )
        assertTrue("The variant should explicitly use userSpaceOnUse", userSpaceSvg.contains("markerUnits=\"userSpaceOnUse\""))
        assertEquals(10f, measuredArrowheadCrossSection(userSpaceSvg), 1.5f)
    }

    private fun measuredArrowheadCrossSection(source: String): Float {
        val parsed = sanitizeAndParseSvg(source)
        val prepared = parsed.renderToPicture()
        assertEquals(432f, prepared.width, 0f)
        assertEquals(768f, prepared.height, 0f)

        val bitmap = Bitmap.createBitmap(prepared.width.toInt(), prepared.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            prepared.picture.draw(Canvas(bitmap))

            // The first arrow is isolated in this ROI. Its widest row measures the head across its fill.
            val widestRow = (178..215).maxOf { y ->
                val occupied = (196..236).filter { x -> (bitmap.getPixel(x, y) ushr 24) > 24 }
                if (occupied.isEmpty()) 0 else occupied.last() - occupied.first() + 1
            }
            assertTrue("Rendered SVG must contain the OMEGA arrowhead", widestRow > 0)
            return widestRow.toFloat()
        } finally {
            bitmap.recycle()
        }
    }
}
