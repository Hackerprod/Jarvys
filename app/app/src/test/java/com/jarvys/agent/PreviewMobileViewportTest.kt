package com.jarvys.agent

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/** Bytes/stream contracts only; none of these tests runs Chromium. */
class PreviewMobileViewportTest {
    private val meta = PreviewMobileViewport.META
    private fun adapted(html: String) = String(PreviewMobileViewport.adapt(html.toByteArray()), Charsets.UTF_8)
    private fun unchanged(html: String) = assertEquals(html, adapted(html))

    @Test fun insertsBeforeAuthorHeadContentAndKeepsEveryOtherByte() {
        val html = "<!doctype html>\n<html lang='es'><head><meta charset='utf-8'><title>¡Hola 🐱!</title></head><body>Uno</body></html>"
        val actual = adapted(html)
        assertEquals(html.replace("<head>", "<head>$meta"), actual)
        assertEquals(html, actual.replace(meta, ""))
    }
    @Test fun insertionIsIdempotent() {
        val once = adapted("<!doctype html><h1>Hello</h1>")
        assertEquals(once, adapted(once))
    }
    @Test fun headlessDocumentKeepsDoctypeBeforeFallback() {
        assertEquals("<!DOCTYPE HTML>$meta\n<title>T</title><p>P", adapted("<!DOCTYPE HTML>\n<title>T</title><p>P"))
    }
    @Test fun htmlWithoutHeadGetsFallbackInsideHtml() {
        assertEquals("<!doctype html><html lang=en>$meta<body>Hi</body></html>",
            adapted("<!doctype html><html lang=en><body>Hi</body></html>"))
    }
    @Test fun fragmentsAndEmptyDocumentsReceiveDefaultWithoutInventedWrappers() {
        for (html in listOf("", "Hello", "<p>Hi</p>", "3 < 4", "\n<button>Tap</button>")) assertEquals(meta + html, adapted(html))
    }
    @Test fun bomUnicodeAndCommentsBeforeDoctypeRetainOrderAndExactBytes() {
        val html = "\uFEFF<!-- España 🐱 -->\n<!doctype html><html><head>\r\n</head></html>"
        assertEquals(html.replace("<head>", "<head>$meta"), adapted(html))
    }
    @Test fun realViewportsRemainExactlyAuthored() {
        for (viewport in listOf(
            "<meta name=viewport content='width=device-width, initial-scale=1'>",
            "<META CONTENT=\"width=1280, initial-scale=.5\" NAME='VIEWPORT'>",
            "<meta\nname = \" viewport \"\tcontent=''>", "<meta name=viewport>",
            "<meta name=viewport content='broken'>", "<meta name=viewport content='user-scalable=no'>",
            "<meta name=viewport content='width=800'><meta name=viewport content='width=500'>",
            "<meta content='width=device-width' name='view&#112;ort'>",
            "<meta name='viewport' name='description' content='width=800'>",
        )) unchanged("<!doctype html><html><head>$viewport</head><body>Keep me</body></html>")
    }
    @Test fun bodyViewportIsAlsoPreserved() {
        unchanged("<html><head></head><body><meta name=viewport content='width=800'></body></html>")
    }
    @Test fun commentedViewportAndQuotedTagExamplesDoNotSuppressFallback() {
        val html = "<!doctype html><!-- <meta name=viewport content='width=980'> --><head><meta name=description content=\"example > <meta name='viewport'>\"></head>"
        assertEquals(html.replace("<head>", "<head>$meta"), adapted(html))
    }
    @Test fun rawTextExamplesDoNotBecomeAuthoredViewport() {
        for (tag in listOf("script", "style", "title", "textarea", "xmp", "iframe", "noembed", "noframes", "noscript")) {
            val html = "<html><head><$tag>example <meta name='viewport' content='width=980'></$tag></head></html>"
            assertEquals(tag, html.replace("<head>", "<head>$meta"), adapted(html))
        }
    }
    @Test fun rawTextCloseRequiresNameBoundary() {
        val html = "<head><script>const x='</scripted><meta name=viewport>';</script></head>"
        assertEquals(html.replace("<head>", "<head>$meta"), adapted(html))
    }
    @Test fun nestedTemplatesAreInertAndDoNotSuppressFallback() {
        val html = "<head><template><meta name=viewport><template><head><meta name=viewport></head></template></template></head><body>Hi</body>"
        assertEquals("<head>$meta" + html.removePrefix("<head>"), adapted(html))
    }
    @Test fun liveViewportAfterTemplateStillTakesPrecedence() {
        unchanged("<head><template><meta name=viewport></template><meta name=viewport content='width=1200'></head>")
    }
    @Test fun booleanAndQuotedGreaterThanAttributesRemainUnchangedAroundInsertion() {
        val html = "<html lang=en><head data-a='x > y'></head><body><input disabled checked type=text><br/></body></html>"
        assertEquals(html.replace("<head data-a='x > y'>", "<head data-a='x > y'>$meta"), adapted(html))
    }
    @Test fun malformedAndAmbiguousSyntaxPassesThrough() {
        for (html in listOf("<head", "<head><meta name='x></head>", "<!-- no close", "<!-- bad -- comment -->",
            "<script/>", "<script>no end", "<script><!-- <script></script> --> </script>",
            "<!doctype html><!doctype html><p>x", "<body>x<head></head>", "</head><p>x",
            "<head a=b a=c></head>", "<meta name=viewport=bad>", "<meta name='other'name='viewport'>",
            "<template><meta name=viewport>", "</template>", "<template/>", "<plaintext>text",
            "<?xml version='1.0'?><html/>", "<!DOCTYPE html PUBLIC \"legacy\"><head></head>")) unchanged(html)
    }
    @Test fun abruptlyClosedCommentsCannotHideAnAuthoredViewport() {
        for (prefix in listOf("<!-->", "<!--->"))
            unchanged(prefix + "<meta name=viewport content='width=1280'>--><p>Keep</p>")
    }
    @Test fun foreignIntegrationPointsCannotHideAnAuthoredViewport() {
        for (root in listOf("svg", "math")) for (container in listOf("title", "template", "script"))
            unchanged("<$root><$container><meta name=viewport content='width=1280'></$container></$root>")
    }
    @Test fun oldProviderSelectParsingCannotHideAnAuthoredViewport() {
        for (raw in listOf("style", "title", "xmp"))
            unchanged("<select><$raw></select><meta name=viewport content='width=1280'></$raw>")
        val html = "<head></head><body><select><option>One</option></select></body>"
        assertEquals(html.replace("<head>", "<head>$meta"), adapted(html))
    }
    @Test fun benignForeignIconsAndTitlesDoNotPreventMobileFallback() {
        for (foreign in listOf("<svg><title>Logo</title><path d='M 0 0'/></svg>", "<math><mi>x</mi></math>")) {
            val html = "<!doctype html><head><meta charset=utf-8></head><body>$foreign</body>"
            assertEquals(html.replace("<head>", "<head>$meta"), adapted(html))
        }
    }
    @Test fun normalSelectFollowedByScriptStillGetsMobileFallback() {
        val html = "<head><meta charset=utf-8></head><body><select><option>One</option></select><script>window.local=true;</script></body>"
        assertEquals(html.replace("<head>", "<head>$meta"), adapted(html))
    }
    @Test fun opaqueScanDoesNotSkipActiveMetaInsideAnEarlierQuotedExample() {
        unchanged("<head></head><body><svg/><script>const example = \"<meta content='\";</script>" +
            "<meta name=viewport content=\"width=1280\"><p data-q='>tail'>After</p></body>")
    }
    @Test fun opaqueScanPreservesEncodedMixedCaseDuplicateAndMalformedCandidates() {
        for (candidate in listOf("<MeTa NaMe=ViEwPoRt>", "<meta/name=viewport>",
            "<meta name='view&#112;ort'>", "<meta name=description name=viewport>", "<meta name='unterminated>"))
            unchanged("<head></head><svg><title>$candidate</title></svg>")
    }
    @Test fun foreignContextsCannotHideConflictingOrUncertainEncodingDeclarations() {
        for (candidate in listOf("<meta charset=windows-1252>", "<meta http-equiv=content-type content='text/html;charset=utf-16'>",
            "<meta http-equiv='content&#45;type' content='text/html;charset=utf-16'>"))
            unchanged("<head></head><svg><title>$candidate</title></svg>")
    }
    @Test fun opaqueScanCandidateBudgetPassesThroughInsteadOfGuessing() {
        unchanged("<head></head><svg/>" + "<meta name=description content=x>".repeat(257))
    }
    @Test fun unsupportedOrConflictingEncodingsPassThrough() {
        for (html in listOf("<head><meta charset=windows-1252></head>",
            "<head><meta http-equiv=content-type content='text/html;charset=UTF-8'></head>", "<head>\u0000</head>")) unchanged(html)
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), "<head></head>".toByteArray(Charsets.UTF_16),
            "<head></head>".toByteArray(Charsets.UTF_16LE))) assertArrayEquals(bytes, PreviewMobileViewport.adapt(bytes))
    }
    @Test fun excessiveAttributesPassThroughWithoutUnboundedWork() {
        unchanged("<head " + (1..257).joinToString(" ") { "data-$it=x" } + "></head>")
    }
    @Test fun supportedLimitEqualsExistingSnapshotHtmlBudget() {
        assertEquals(HtmlPreviewCapture.MAX_FILE_BYTES, PreviewMobileViewport.MAX_BYTES.toLong())
        val bytes = ByteArray(PreviewMobileViewport.MAX_BYTES) { ' '.code.toByte() }
        assertEquals(bytes.size + meta.toByteArray().size, PreviewMobileViewport.adapt(bytes).size)
    }
    @Test fun oversizedInputIsNotAdaptedOrTruncated() {
        val bytes = ByteArray(PreviewMobileViewport.MAX_BYTES + 33) { (it % 127).toByte() }
        val source = TrackedStream(bytes)
        val rendering = PreviewMobileViewport.forRendering(source)
        assertFalse(source.closed)
        assertEquals(PreviewMobileViewport.MAX_BYTES + 1, source.consumed)
        assertArrayEquals(bytes, rendering.use { it.readBytes() })
        assertTrue(source.closed)
    }
    @Test fun boundedSourceClosesBeforeReturningIndependentRenderBytes() {
        val source = TrackedStream("<h1>Hi</h1>".toByteArray())
        val rendering = PreviewMobileViewport.forRendering(source)
        assertTrue(source.closed)
        assertEquals(meta + "<h1>Hi</h1>", rendering.use { String(it.readBytes()) })
    }
    @Test fun earlyCloseOfOversizedRenderingClosesItsOriginalStream() {
        val source = TrackedStream(ByteArray(PreviewMobileViewport.MAX_BYTES + 20))
        PreviewMobileViewport.forRendering(source).close()
        assertTrue(source.closed)
    }
    @Test fun readFailureClosesOriginalAndDoesNotReturnPartialSuccess() {
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = throw IOException("fixture")
            override fun close() { closed = true }
        }
        try { PreviewMobileViewport.forRendering(stream); fail("Must propagate read failure") }
        catch (_: IOException) { assertTrue(closed) }
    }
    @Test fun zeroLengthReadCannotSpinForever() {
        val bytes = "<p>Zero</p>".toByteArray()
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        assertEquals(meta + String(bytes), PreviewMobileViewport.forRendering(source).use { String(it.readBytes()) })
    }
    @Test fun fixedWidthCssBaseAndAuthorZoomRemainUntouched() {
        val html = "<head><base href='https://outside.invalid/'><style>body{min-width:1280px}canvas{width:1800px}</style></head><body><canvas></canvas></body>"
        assertEquals(html.replace("<head>", "<head>$meta"), adapted(html))
    }

    private class TrackedStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        val consumed get() = pos
        override fun close() { closed = true; super.close() }
    }
}
