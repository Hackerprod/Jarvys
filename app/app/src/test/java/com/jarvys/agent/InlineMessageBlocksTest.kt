package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineMessageBlocksTest {
    @Test fun svgSanitizerRejectsScriptsForeignObjectsAndNonFragmentReferences() {
        val dangerous = listOf(
            "<svg viewBox='0 0 10 10'><script>alert(1)</script></svg>",
            "<svg viewBox='0 0 10 10'><foreignObject><div>outside</div></foreignObject></svg>",
            "<svg viewBox='0 0 10 10'><defs><linearGradient href='https://example.invalid/a.svg#x'/></defs></svg>",
            "<svg viewBox='0 0 10 10'><rect style=\"fill:url(file:///secret)\"/></svg>",
            "<svg viewBox='0 0 10 10'><style>.shape { fill: url(https://example.invalid/a.svg); }</style></svg>",
            "<!DOCTYPE svg SYSTEM 'https://example.invalid/external.dtd'><svg viewBox='0 0 10 10'/>",
            "<?xml-stylesheet href='https://example.invalid/remote.css'?><svg viewBox='0 0 10 10'/>",
        )
        dangerous.forEach { source -> assertThrows("Sanitizer accepted: $source", IllegalArgumentException::class.java) { sanitizeAndParseSvg(source) } }
    }

    @Test fun svgSanitizerRejectsEntityReferencesInsideStyleBeforeCssValidation() {
        val dangerous = listOf(
            "<svg viewBox='0 0 10 10'><style>.a{fill:&#117;rl(http://x)}</style></svg>",
            "<svg viewBox='0 0 10 10'><style>&#64;import 'https://example.invalid/a.css';</style></svg>",
        )
        dangerous.forEach { source ->
            assertThrows("Sanitizer accepted: $source", IllegalArgumentException::class.java) { sanitizeAndParseSvg(source) }
        }
    }

    @Test fun svgSanitizerKeepsTitleAndViewBoxForNativeDrawing() {
        val result = sanitizeAndParseSvg(
            "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 120 60'><title>Diagram</title><circle cx='30' cy='30' r='12'/></svg>",
        )
        assertEquals(120f, result.width)
        assertEquals(60f, result.height)
        assertEquals("Diagram", result.description)
        val modelLabeled = sanitizeAndParseSvg("<svg viewBox='0 0 10 10' aria-label='Flow diagram'><circle cx='5' cy='5' r='2'/></svg>")
        assertEquals("Flow diagram", modelLabeled.description)
    }

    @Test fun chartSpecValidatesShapeAndRendererRegistryOwnsLanguageDispatch() {
        val chart = parseChartSpec(
            """{"type":"bar","title":"Monthly","labels":["Jan","Feb"],"series":[{"name":"Sales","values":[3,5]}]}""",
            seriesLabel = "Series",
        )
        assertNotNull(chart)
        assertEquals("Monthly. Sales: Jan 3.0, Feb 5.0", chart!!.accessibilitySummary("Chart", "Series"))
        assertNull(parseChartSpec("""{"type":"line","title":"Broken","labels":["Jan","Feb"],"series":[{"values":[2]}]}""", "Series"))
        assertNull(parseChartSpec("""{"type":"pie","title":"Broken","labels":["Jan"],"series":[{"values":[-1]}]}""", "Series"))
        assertNotNull(inlineBlockRenderer("SVG"))
        assertNotNull(inlineBlockRenderer("chart"))
        assertNotNull(inlineBlockRenderer("tex"))
        assertNull(inlineBlockRenderer("python"))
    }

    @Test fun nullChartSeriesNameUsesTheLocalizedFallbackInsteadOfTheLiteralNull() {
        val chart = parseChartSpec(
            """{"type":"bar","title":"Monthly","labels":["Jan"],"series":[{"name":null,"values":[3]}]}""",
            seriesLabel = "Serie",
        )
        assertNotNull(chart)
        assertEquals("Serie 1", chart!!.series.single().name)
        assertEquals("Monthly. Serie 1: Jan 3.0", chart.accessibilitySummary("Chart", "Serie"))
    }

    @Test fun chartNumberFormattingDoesNotOverflowLongForLargeIntegralValues() {
        val outsideLongRange = Long.MAX_VALUE.toDouble()
        assertEquals(java.lang.Double.toString(outsideLongRange), outsideLongRange.formatChartNumber())
        assertEquals(Long.MIN_VALUE.toString(), Long.MIN_VALUE.toDouble().formatChartNumber())
    }

    @Test fun latexFenceUsesNativeParserAndRejectsMalformedEquationsToCodeFallback() {
        assertEquals(true, isValidLatex("\\frac{1}{2} = 0.5"))
        assertEquals(false, isValidLatex("\\frac{1}{"))
    }

    @Test fun incompleteSpecialFencesWaitOnlyWhileStreamingAndThenFallBackToCode() {
        assertEquals(InlineFenceState.STREAM_PLACEHOLDER, inlineFenceState("svg", closed = false, streaming = true))
        assertEquals(InlineFenceState.STREAM_PLACEHOLDER, inlineFenceState("unknown-prefix", closed = false, streaming = true))
        assertEquals(InlineFenceState.CODE, inlineFenceState("svg", closed = false, streaming = false))
        assertEquals(InlineFenceState.RENDER, inlineFenceState("chart", closed = true, streaming = false))
        assertEquals(InlineFenceState.CODE, inlineFenceState("text", closed = true, streaming = false))
    }
}
