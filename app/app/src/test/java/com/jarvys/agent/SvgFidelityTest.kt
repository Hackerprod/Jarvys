package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.caverock.androidsvg.SVG
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgFidelityTest {
    @Test fun markerGeometryUsesAndroidSvgStrokeWidthScalingWithoutTriangleInflation() {
        for ((strokeWidth, expected) in listOf("1" to 10, "1.5" to 15, "2" to 20)) {
            val bounds = renderMarkerOnly(strokeWidth = strokeWidth)
                .alphaBounds(PixelRoi(80, 101, 20, 60)) ?: throw AssertionError("stroke-width=$strokeWidth marker is empty")
            assertTrue("stroke-width=$strokeWidth bounds=${bounds.format()}", kotlin.math.abs(bounds.width - expected) <= 2)
            assertTrue("stroke-width=$strokeWidth bounds=${bounds.format()}", kotlin.math.abs(bounds.height - expected) <= 2)
        }
        val userSpace = renderMarkerOnly(units = "userSpaceOnUse", strokeWidth = "2")
            .alphaBounds(PixelRoi(80, 101, 20, 60)) ?: throw AssertionError("userSpaceOnUse marker is empty")
        assertTrue("userSpaceOnUse bounds=${userSpace.format()}", kotlin.math.abs(userSpace.width - 10) <= 2)
        assertTrue("userSpaceOnUse bounds=${userSpace.format()}", kotlin.math.abs(userSpace.height - 10) <= 2)
    }

    @Test fun contextStrokeAndFillAreResolvedFromReferencerIncludingClassAndCurrentColor() {
        val contextSource = renderMarkerOnlySource(markerPath = "<path d='M0 0 L10 5 L0 10 Z' fill='context-stroke'/>")
        val contextStroke = renderMarkerOnly(
            strokeWidth = "1.5",
            targetPaint = "stroke='#D02030'",
            markerPath = "<g><path d='M0 0 L10 5 L0 10 Z' fill='context-stroke'/><path d='M2 2 L8 2 L5 4 Z' fill='#11AA22'/></g>",
        )
        assertTrue("context-stroke should paint the marker red", contextStroke.opaqueColors(PixelRoi(80, 101, 30, 51))["#D02030"].orZero() > 10)
        assertTrue("an explicit descendant fill must survive marker cloning", contextStroke.opaqueColors(PixelRoi(80, 101, 30, 51))["#11AA22"].orZero() > 0)

        val contextFill = renderMarkerOnly(
            strokeWidth = "1.5",
            targetPaint = "fill='#A030D0' stroke='none'",
            markerPath = "<path d='M0 0 L10 5 L0 10 Z' fill='none' stroke='context-fill' stroke-width='1'/>",
        )
        assertTrue("context-fill should outline the marker purple", contextFill.opaqueColors(PixelRoi(80, 101, 30, 51))["#A030D0"].orZero() > 0)

        val cssClass = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 80"><style>.source { color: #1864AB; stroke: currentColor; marker-end: url(#m); } .head { fill: context-stroke; }</style><defs><marker id="m" markerUnits="strokeWidth" markerWidth="10" markerHeight="10" viewBox="0 0 10 10" refX="0" refY="5" orient="auto"><path class="head" d="M0 0 L10 5 L0 10 Z"/></marker></defs><path class="source" d="M20 40 L80 40" stroke-width="1.5"/></svg>"""
        val cssBitmap = renderSvg(cssClass, Color(0xFFF0F0F0))
        assertTrue("class-based context-stroke should use the computed CSS stroke", cssBitmap.opaqueColors(PixelRoi(80, 101, 30, 51))["#1864AB"].orZero() > 10)

        val currentColor = renderMarkerOnly(
            strokeWidth = "1.5",
            rootAttrs = "color='#2468AC'",
            targetPaint = "stroke='currentColor'",
            markerPath = "<path d='M0 0 L10 5 L0 10 Z' fill='context-stroke'/>",
        )
        assertTrue("context-stroke should resolve currentColor on the referencer", currentColor.opaqueColors(PixelRoi(80, 101, 30, 51))["#2468AC"].orZero() > 10)

        for (theme in listOf(Color(0xFF202020), Color(0xFFF0F0F0))) {
            val expected = "#%06X".format(theme.toArgb() and 0x00FFFFFF)
            val themedContextStroke = renderMarkerOnly(
                targetPaint = "stroke='currentColor'",
                markerPath = "<path d='M0 0 L10 5 L0 10 Z' fill='context-stroke'/>",
                theme = theme,
            )
            assertTrue("context-stroke currentColor should follow theme $expected", themedContextStroke.opaqueColors(PixelRoi(80, 101, 30, 51))[expected].orZero() > 10)
            val themedImplicitFill = renderMarkerOnly(
                targetPaint = "stroke='currentColor'",
                markerPath = "<path d='M0 0 L10 5 L0 10 Z'/>",
                theme = theme,
            )
            assertTrue("implicit marker fill should follow currentColor theme $expected", themedImplicitFill.opaqueColors(PixelRoi(80, 101, 30, 51))[expected].orZero() > 10)
        }

    }

    @Test fun markerCloneRewritesInnerIdsAndPreservesSafeInternalReferences() {
        val source = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 80"><style>#m path { stroke-width: 1; } #paint stop { stop-color: #D02030; }</style><defs><marker id="m" markerUnits="strokeWidth" markerWidth="10" markerHeight="10" viewBox="0 0 10 10" refX="0" refY="5" orient="auto"><linearGradient id="paint"><stop offset="0"/></linearGradient><path d="M0 0 L10 5 L0 10 Z" fill="url(#paint)" stroke="context-stroke" stroke-width="1"/></marker></defs><line x1="20" y1="40" x2="80" y2="40" stroke="#2468AC" stroke-width="1.5" marker-end="url(#m)"/></svg>"""
        val transformed = sanitizeSvgSource(source)
        assertTrue(transformed.contains("id=\"svg_context_marker_0\"") || transformed.contains("id='svg_context_marker_0'"))
        assertTrue(transformed.contains("id=\"svg_context_marker_1\"") || transformed.contains("id='svg_context_marker_1'"))
        assertTrue(transformed.contains("url(#svg_context_marker_1)"))
        assertTrue(transformed.contains("#svg_context_marker_0 path"))
        assertTrue(transformed.contains("#svg_context_marker_1 stop"))
        assertTrue(transformed.contains("stroke: #2468AC"))
        sanitizeAndParseSvg(transformed)

        val oneCloneForBothEnds = sanitizeSvgSource(source.replace("marker-end=\"url(#m)\"", "marker-end=\"url(#m)\" marker-start=\"url(#m)\""))
        assertTrue(Regex("<marker[^>]*id=[\"']svg_context_marker_[0-9]+[\"']").findAll(oneCloneForBothEnds).count() == 1)
    }

    @Test fun autoStartReverseUsesVisibleCodeFallbackAndTransformedSvgStillRejectsUnsafeInput() {
        val unsupported = renderMarkerOnlySource(markerAttrs = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='0' refY='5' orient='auto-start-reverse'")
        assertThrows(IllegalArgumentException::class.java) { sanitizeAndParseSvg(unsupported) }

        val safeMarker = "<defs><marker id='m' markerWidth='10' markerHeight='10' viewBox='0 0 10 10'><path d='M0 0 L10 5 L0 10 Z' fill='context-stroke'/></marker></defs>"
        val unsafe = listOf(
            "<svg viewBox='0 0 160 80' onload='alert(1)'>$safeMarker<line x1='20' y1='40' x2='80' y2='40' stroke='#D02030' marker-end='url(#m)'/></svg>",
            "<svg viewBox='0 0 160 80'>$safeMarker<path href='https://example.test/a.png' d='M0 0L1 1'/><line x1='20' y1='40' x2='80' y2='40' stroke='#D02030' marker-end='url(#m)'/></svg>",
            "<svg viewBox='0 0 160 80'>$safeMarker<line x1='20' y1='40' x2='80' y2='40' stroke='url(https://example.test/paint)' marker-end='url(#m)'/></svg>",
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg viewBox='0 0 160 80'>$safeMarker<line x1='20' y1='40' x2='80' y2='40' stroke='#D02030' marker-end='url(#m)'/></svg>",
        )
        unsafe.forEach { input -> assertThrows(IllegalArgumentException::class.java) { sanitizeSvgSource(input) } }

        val nested = """<svg viewBox="0 0 10 10"><defs><marker id="outer"><marker id="inner"><path d="M0 0L1 1"/></marker></marker></defs></svg>"""
        assertThrows(IllegalArgumentException::class.java) { sanitizeSvgSource(nested) }
        val cycle = """<svg viewBox="0 0 10 10"><defs><marker id="a"><path d="M0 0L1 1" marker-end="url(#b)"/></marker><marker id="b"><path d="M0 0L1 1" marker-end="url(#a)"/></marker></defs><line x1="0" y1="0" x2="8" y2="8" marker-end="url(#a)"/></svg>"""
        assertThrows(IllegalArgumentException::class.java) { sanitizeSvgSource(cycle) }
    }

    @Test fun implicitMarkerFillFollowsReferencerStrokeForDarkThemeVisibility() {
        val implicitSource = renderMarkerOnlySource(markerPath = "<path d='M0 0 L10 5 L0 10 Z'/>")
        val bitmap = renderMarkerOnly(markerPath = "<path d='M0 0 L10 5 L0 10 Z'/>", theme = Color(0xFFF0F0F0))
        assertTrue("an omitted marker fill follows the referring stroke", bitmap.opaqueColors(PixelRoi(80, 101, 30, 51))["#D02030"].orZero() > 10)
    }

    @Test fun markerMatrixReportsNativeAndroidSvgMeasurements() {
        val cases = markerMatrix()
        for (case in cases) {
            val source = case.source()
            val rawBitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
            val raw = runCatching { SVG.getFromString(source) }.getOrNull()
            if (raw == null) {
                println("SVG_RAW\t${case.name}\tFALLBACK")
            } else {
                raw.renderToPicture(160, 80).draw(Canvas(rawBitmap))
                val rawBounds = case.rawColor?.let { rawBitmap.alphaBounds(case.roi) }
                val rawColors = rawBitmap.opaqueColors(case.roi).entries
                    .sortedByDescending { it.value }.take(3).joinToString(",") { "${it.key}:${it.value}" }
                println("SVG_RAW\t${case.name}\t${rawBounds?.format() ?: "EMPTY"}\t${rawColors.ifBlank { "NO_OPAQUE_PIXELS" }}")
            }
            rawBitmap.recycle()
            val parsed = runCatching { sanitizeAndParseSvg(source) }.getOrNull()
            if (parsed == null) {
                assertTrue("${case.name} should use the visible fallback", case.expectedFallback)
                println("SVG_MATRIX\t${case.name}\tFALLBACK")
                continue
            }
            assertTrue("${case.name} unexpectedly used the fallback", !case.expectedFallback)
            val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
            val picture = parsed.renderToPicture(case.currentColor).picture
            picture.draw(Canvas(bitmap))
            val bounds = bitmap.alphaBounds(case.roi)
            assertTrue("${case.name} marker is empty", bounds != null)
            assertTrue("${case.name} marker bounds=${bounds?.format()}", kotlin.math.abs(bounds!!.width - case.expectedWidth) <= 2)
            assertTrue("${case.name} marker bounds=${bounds.format()}", kotlin.math.abs(bounds.height - case.expectedHeight) <= 2)
            val colors = bitmap.opaqueColors(case.roi).entries
                .sortedByDescending { it.value }.take(3).joinToString(",") { "${it.key}:${it.value}" }
            if (case.expectedColor != null) {
                assertTrue("${case.name} expected ${case.expectedColor}, got $colors",
                    bitmap.opaqueColors(case.roi)[case.expectedColor].orZero() > 0)
            }
            println("SVG_MATRIX\t${case.name}\t${bounds.format()}\t${colors.ifBlank { "NO_OPAQUE_PIXELS" }}")
            bitmap.recycle()
        }
    }

    private data class MarkerCase(
        val name: String,
        val markerAttrs: String = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='0' refY='5' orient='auto'",
        val markerContent: String = "<path d='M0 0 L10 5 L0 10 Z' fill='currentColor'/>",
        val style: String = "",
        val target: String = "line",
        val strokeWidth: String = "1.5",
        val currentColor: Color = Color(0xFF2468AC),
        val targetFill: String? = null,
        val targetStroke: String = "#D02030",
        val markerAtStart: Boolean = false,
        val roi: PixelRoi = PixelRoi(80, 101, 0, 80),
        val expectedWidth: Int = 15,
        val expectedHeight: Int = 15,
        val expectedColor: String? = "#2468AC",
        val rawColor: String? = expectedColor,
        val chromeColor: String? = expectedColor,
        val expectedFallback: Boolean = false,
    ) {
        fun source(): String {
            val stroke = when (target) {
                "line" -> "<line x1='20' y1='40' x2='80' y2='40'"
                "path" -> "<path d='M20 40 L80 40'"
                "polyline" -> "<polyline points='20,40 50,40 80,40'"
                else -> error("Unknown target $target")
            }
            val markerAttribute = if (markerAtStart) "marker-start" else "marker-end"
            val colorHex = "#%06X".format(currentColor.toArgb() and 0x00FFFFFF)
            val fill = targetFill?.let { "fill=\"$it\"" }.orEmpty()
            return """<svg xmlns="http://www.w3.org/2000/svg" width="160" height="80" viewBox="0 0 160 80" color="$colorHex">$style<defs><marker id="m" $markerAttrs>$markerContent</marker></defs>$stroke $fill stroke="$targetStroke" stroke-width="$strokeWidth" $markerAttribute="url(#m)"/></svg>"""
        }
    }

    private data class PixelRoi(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)
    private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        fun format() = "${width}x${height}@${left},${top}"
    }

    private fun Bitmap.alphaBounds(roi: PixelRoi): PixelBounds? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in roi.minY until min(roi.maxY, height)) for (x in roi.minX until min(roi.maxX, width)) {
            val pixel = getPixel(x, y)
            if (AndroidColor.alpha(pixel) > 0) {
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        return if (right < left || bottom < top) null else PixelBounds(left, top, right, bottom)
    }

    private fun Bitmap.opaqueColors(roi: PixelRoi): Map<String, Int> = buildMap {
        for (y in roi.minY until min(roi.maxY, height)) for (x in roi.minX until min(roi.maxX, width)) {
            val pixel = getPixel(x, y)
            if (AndroidColor.alpha(pixel) == 255) {
                val color = "#%06X".format(pixel and 0x00FFFFFF)
                put(color, (get(color) ?: 0) + 1)
            }
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun renderMarkerOnly(
        strokeWidth: String = "1.5",
        units: String = "strokeWidth",
        rootAttrs: String = "",
        targetPaint: String = "stroke='#D02030'",
        markerPath: String = "<path d='M0 0 L10 5 L0 10 Z' fill='currentColor'/>",
        theme: Color = Color(0xFF2468AC),
    ): Bitmap = renderSvg(renderMarkerOnlySource(strokeWidth, units, rootAttrs, targetPaint, markerPath), theme)

    private fun renderMarkerOnlySource(
        strokeWidth: String = "1.5",
        units: String = "strokeWidth",
        rootAttrs: String = "",
        targetPaint: String = "stroke='#D02030'",
        markerPath: String = "<path d='M0 0 L10 5 L0 10 Z' fill='currentColor'/>",
        markerAttrs: String = "markerUnits='$units' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='0' refY='5' orient='auto'",
    ): String = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 80" $rootAttrs><defs><marker id="m" $markerAttrs>$markerPath</marker></defs><path d="M20 40 L80 40" $targetPaint stroke-width="$strokeWidth" marker-end="url(#m)"/></svg>"""

    private fun renderSvg(source: String, theme: Color): Bitmap {
        val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        sanitizeAndParseSvg(source).renderToPicture(theme).picture.draw(Canvas(bitmap))
        return bitmap
    }

    companion object {
        private fun markerMatrix(): List<MarkerCase> = buildList {
            add(MarkerCase("baseline.strokeWidth.viewBox.10x10.ref0,5.auto.fillCurrentColor"))
            add(MarkerCase("units.userSpaceOnUse", markerAttrs = "markerUnits='userSpaceOnUse' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='0' refY='5' orient='auto'", expectedWidth = 10, expectedHeight = 10))
            add(MarkerCase("viewBox.absent", markerAttrs = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' refX='0' refY='5' orient='auto'"))
            add(MarkerCase("dimensions.absent", markerAttrs = "markerUnits='strokeWidth' viewBox='0 0 10 10' refX='0' refY='5' orient='auto'", expectedWidth = 5, expectedHeight = 4))
            add(MarkerCase("dimensions.absent.viewBox.absent", markerAttrs = "markerUnits='strokeWidth' refX='0' refY='5' orient='auto'", expectedWidth = 5, expectedHeight = 4))
            add(MarkerCase("refXY.absent", markerAttrs = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' orient='auto'"))
            add(MarkerCase("refX10.refY5", markerAttrs = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='10' refY='5' orient='auto'", roi = PixelRoi(65, 81, 20, 60)))
            add(MarkerCase("orient.auto-start-reverse", markerAttrs = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='0' refY='5' orient='auto-start-reverse'", expectedFallback = true))
            add(MarkerCase("path.fill.currentColor", markerContent = "<path d='M0 0 L10 5 L0 10 Z' fill='currentColor'/>", currentColor = Color(0xFF202020), expectedColor = "#202020"))
            add(MarkerCase("path.default.fill.theme.dark", markerContent = "<path d='M0 0 L10 5 L0 10 Z'/>", currentColor = Color(0xFFF0F0F0), expectedColor = "#D02030", rawColor = "#000000", chromeColor = "#000000"))
            add(MarkerCase("path.stroke.currentColor", markerContent = "<path d='M0 0 L10 5 L0 10 Z' fill='none' stroke='currentColor' stroke-width='1'/>", expectedColor = "#2468AC"))
            add(MarkerCase("style.markerPath.currentColor", markerContent = "<path class='arrow-head' d='M0 0 L10 5 L0 10 Z'/>", style = "<style>marker path { fill: none; stroke: currentColor; stroke-width: 1; }</style>"))
            add(MarkerCase("style.class.currentColor", markerContent = "<path class='arrow-head' d='M0 0 L10 5 L0 10 Z'/>", style = "<style>.arrow-head { fill: none; stroke: currentColor; stroke-width: 1; }</style>"))
            add(MarkerCase("paint.context-stroke-as-fill", markerContent = "<path d='M0 0 L10 5 L0 10 Z' fill='context-stroke'/>", currentColor = Color(0xFF202020), expectedColor = "#D02030", rawColor = "#000000"))
            add(MarkerCase("paint.context-fill-as-stroke", markerContent = "<path d='M0 0 L10 5 L0 10 Z' fill='none' stroke='context-fill' stroke-width='1'/>", targetFill = "#A030D0", currentColor = Color(0xFF202020), expectedColor = "#A030D0", rawColor = null))
            add(MarkerCase("shape.path", target = "path"))
            add(MarkerCase("shape.polyline", target = "polyline"))
            for (strokeWidth in listOf("1", "1.5", "2")) {
                val expected = (strokeWidth.toFloat() * 10).toInt()
                add(MarkerCase("strokeWidth.$strokeWidth", strokeWidth = strokeWidth, expectedWidth = expected, expectedHeight = expected))
            }
            add(MarkerCase("theme.light", currentColor = Color(0xFF202020), expectedColor = "#202020"))
            add(MarkerCase("theme.dark", currentColor = Color(0xFFF0F0F0), expectedColor = "#F0F0F0"))
            add(MarkerCase("start.auto", markerAtStart = true, markerAttrs = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='0' refY='5' orient='auto'", roi = PixelRoi(20, 36, 0, 80)))
            add(MarkerCase("start.auto-start-reverse", markerAtStart = true, markerAttrs = "markerUnits='strokeWidth' markerWidth='10' markerHeight='10' viewBox='0 0 10 10' refX='0' refY='5' orient='auto-start-reverse'", roi = PixelRoi(0, 20, 0, 80), expectedFallback = true))
        }
    }
}
