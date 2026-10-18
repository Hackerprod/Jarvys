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
class SvgSubpathMarkerRenderingTest {
    @Test fun markerEndIsAppliedAtTheEndOfTheWholePath() {
        val source = requireNotNull(javaClass.getResourceAsStream("/svg/multi-arrow-path.svg"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val originalPath = "<path d=\"M63 59H74M135 59H146M207 59H218M279 59H290\" marker-end=\"url(#arrow)\"/>"
        val separatePaths = listOf("M63 59H74", "M135 59H146", "M207 59H218", "M279 59H290")
            .joinToString("") { "<path d=\"$it\" marker-end=\"url(#arrow)\"/>" }
        assertTrue("The fixture should contain the original combined path", source.contains(originalPath))
        val splitSource = source.replace(originalPath, separatePaths)
        assertFalse("Each split path should carry its own marker-end", splitSource.contains(originalPath))

        // SVG paints marker-end at the final vertex of the complete path, so the combined multi-subpath path
        // has no triangular pixels outside the shaft at its first three endpoints. Each independent path does.
        val combinedHeadPixels = arrowHeadPixelCounts(source)
        val splitHeadPixels = arrowHeadPixelCounts(splitSource)
        assertEquals(listOf(0, 0, 0), combinedHeadPixels.take(3))
        assertTrue("The final endpoint should have a measured arrowhead: $combinedHeadPixels", combinedHeadPixels[3] > 0)
        assertTrue("Each independent path should render an arrowhead: $splitHeadPixels", splitHeadPixels.all { it > 0 })
    }

    private fun arrowHeadPixelCounts(source: String): List<Int> {
        val prepared = sanitizeAndParseSvg(source).renderToPicture()
        assertEquals(360f, prepared.width, 0f)
        assertEquals(150f, prepared.height, 0f)

        val bitmap = Bitmap.createBitmap(prepared.width.toInt(), prepared.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            prepared.picture.draw(Canvas(bitmap))
            return listOf(74, 146, 218, 290).map { tipX ->
                // The 2 px horizontal shaft occupies y=58..60; count painted pixels above and below it
                // in the 10 x 9 px endpoint region, where this 7-unit user-space marker adds its head.
                (tipX - 8..tipX + 1).sumOf { x ->
                    (55..63).count { y -> y !in 58..60 && (bitmap.getPixel(x, y) ushr 24) > 24 }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
