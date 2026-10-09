package com.jarvys.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Presentation-only default for ordinary local HTML. Never serializes a DOM or edits a file.
 * This is deliberately a conservative scanner, not an HTML parser/security boundary: uncertain
 * syntax/encoding passes through. Authorization, integrity and response headers belong to the
 * caller. Native Chromium remains responsible for parsing and honoring authored viewports.
 */
internal object PreviewMobileViewport {
    const val MAX_BYTES = 1024 * 1024
    const val META = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    private val insertion = META.toByteArray(Charsets.UTF_8)
    private val rawElements = setOf("script", "style", "title", "textarea", "xmp", "iframe", "noembed", "noframes", "noscript")

    /** Consumes at most MAX_BYTES + 1; the returned stream owns any still-open source. */
    fun forRendering(source: InputStream): InputStream {
        val prefix = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        try {
            while (prefix.size() <= MAX_BYTES) {
                val count = source.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - prefix.size()))
                if (count < 0) {
                    source.close()
                    return ByteArrayInputStream(adapt(prefix.toByteArray()))
                }
                if (count == 0) {
                    val single = source.read()
                    if (single < 0) {
                        source.close()
                        return ByteArrayInputStream(adapt(prefix.toByteArray()))
                    }
                    prefix.write(single)
                } else prefix.write(chunk, 0, count)
            }
            return SequenceInputStream(ByteArrayInputStream(prefix.toByteArray()), source)
        } catch (failure: Exception) {
            runCatching { source.close() }
            throw failure
        }
    }

    internal fun adapt(bytes: ByteArray): ByteArray {
        if (bytes.size > MAX_BYTES) return bytes
        val html = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { return bytes }
        if ('\u0000' in html) return bytes // Includes UTF-16 without a BOM; never transcode it.
        val offset = insertionPoint(html) ?: return bytes
        val byteOffset = html.substring(0, offset).toByteArray(Charsets.UTF_8).size
        return ByteArray(bytes.size + insertion.size).also { out ->
            bytes.copyInto(out, 0, 0, byteOffset)
            insertion.copyInto(out, byteOffset)
            bytes.copyInto(out, byteOffset + insertion.size, byteOffset)
        }
    }

    private data class Tag(val name: String, val end: Int, val closing: Boolean, val attributes: List<Pair<String, String>>)

    private fun insertionPoint(html: String): Int? {
        var position = if (html.startsWith('\uFEFF')) 1 else 0
        var insertAt = position
        var prolog = true
        var sawHtml = false
        var sawHead = false
        var sawDoctype = false
        var templateDepth = 0
        var sawSelect = false
        // Only needed for contexts whose tree-building rules this scanner does not implement.
        val opaqueMetaMayMatter by lazy { possibleAuthoredMetadata(html) }
        while (position < html.length) {
            if (html[position] != '<') {
                if (!html[position].htmlSpace()) prolog = false
                position++
                continue
            }
            if (html.startsWith("<!--", position)) {
                // HTML abruptly closes <!--> / <!--->; don't hide following active markup.
                if (html.startsWith(">", position + 4) || html.startsWith("->", position + 4)) return null
                val end = html.indexOf("-->", position + 4)
                if (end < 0 || html.substring(position + 4, end).contains("--")) return null
                position = end + 3
                if (prolog) insertAt = position
                continue
            }
            if (html.regionMatches(position, "<!doctype", 0, 9, ignoreCase = true)) {
                val end = html.indexOf('>', position + 9)
                // Accept only the HTML5 declaration. No DTD, XML or quoted legacy identifiers.
                if (!prolog || sawDoctype || end < 0 ||
                    html.substring(position + 9, end).trimHtmlSpace().lowercase() != "html") return null
                sawDoctype = true
                position = end + 1
                insertAt = position
                continue
            }
            if (html.startsWith("<!", position) || html.startsWith("<?", position)) return null
            val next = html.getOrNull(position + 1)
            if (next != '/' && next?.asciiLetter() != true) {
                prolog = false
                position++ // A literal less-than in ordinary text does not begin a tag.
                continue
            }
            val tag = tagAt(html, position) ?: return null
            // A normal SVG icon must not force an otherwise mobile page back to a wide layout.
            // Foreign/in-select rules can hide an active meta from a context-aware simple scan,
            // so adapt only when the context-blind scan rules out every possible such candidate.
            if (!tag.closing && (tag.name == "svg" || tag.name == "math") && opaqueMetaMayMatter) return null
            if (!tag.closing && tag.name == "select") sawSelect = true
            if (!tag.closing && sawSelect && tag.name in rawElements && opaqueMetaMayMatter) return null
            if (tag.name == "template") {
                templateDepth += if (tag.closing) -1 else 1
                if (templateDepth < 0) return null
                prolog = false
                position = tag.end
                continue
            }
            if (!tag.closing) {
                if (tag.name == "meta" && templateDepth == 0) {
                    // Any possibly authored viewport takes precedence, even duplicates, malformed
                    // content or an entity-encoded/ambiguous name attribute.
                    if (preserveMetadata(tag)) return null
                }
                if (tag.name == "html" && templateDepth == 0) {
                    if (!prolog || sawHtml || sawHead) return null
                    sawHtml = true
                    insertAt = tag.end
                } else if (tag.name == "head" && templateDepth == 0) {
                    if (!prolog || sawHead) return null
                    sawHead = true
                    insertAt = tag.end
                    prolog = false
                } else {
                    prolog = false
                }
                if (tag.name == "plaintext") return null
                if (tag.name in rawElements) {
                    val close = rawEnd(html, tag.name, tag.end) ?: return null
                    // Script's legacy escaped/double-escaped states are outside this adapter.
                    if (tag.name == "script" && html.substring(tag.end, close).contains("<!--")) return null
                    position = tagAt(html, close)?.end ?: return null
                    continue
                }
            } else if (tag.name == "head" && !sawHead && templateDepth == 0) {
                return null
            }
            position = tag.end
        }
        return insertAt.takeIf { templateDepth == 0 }
    }

    /** Over-approximate static metadata in opaque contexts; never skip overlapping candidates. */
    private fun possibleAuthoredMetadata(html: String): Boolean {
        var position = 0
        var candidates = 0
        var inspected = 0
        while (true) {
            val at = html.indexOf("<meta", position, ignoreCase = true)
            if (at < 0) return false
            if (++candidates > 256) return true
            val tag = tagAt(html, at) ?: return true
            inspected += tag.end - at
            if (inspected > MAX_BYTES || (tag.name == "meta" && preserveMetadata(tag))) return true
            // A raw-text example's quoted attribute can span a real </script> and active meta.
            // Advancing to tag.end would incorrectly hide that inner literal occurrence.
            position = at + 1
        }
    }

    private fun preserveMetadata(tag: Tag): Boolean = tag.attributes.any { (name, value) ->
        when (name) {
            "name" -> value.trimHtmlSpace().equals("viewport", true) || '&' in value
            // The response contract is UTF-8; don't shift a conflicting or uncertain declaration.
            "charset" -> !value.equals("utf-8", true) && !value.equals("utf8", true)
            "http-equiv" -> value.trimHtmlSpace().equals("content-type", true) || '&' in value
            else -> false
        }
    }

    private fun rawEnd(html: String, name: String, start: Int): Int? {
        var at = start
        while (true) {
            at = html.indexOf('<', at)
            if (at < 0) return null
            if (html.regionMatches(at, "</$name", 0, name.length + 2, true)) {
                val after = html.getOrNull(at + name.length + 2)
                if (after == '>' || after == '/' || after?.htmlSpace() == true) return at
            }
            at++
        }
    }

    private fun tagAt(html: String, start: Int): Tag? {
        var at = start + 1
        val closing = html.getOrNull(at) == '/'
        if (closing) at++
        val nameStart = at
        if (html.getOrNull(at)?.asciiLetter() != true) return null
        while (html.getOrNull(at)?.let { it.asciiLetter() || it in '0'..'9' || it == '-' || it == ':' } == true) at++
        val name = html.substring(nameStart, at).lowercase()
        val attributes = ArrayList<Pair<String, String>>()
        val attributeNames = HashSet<String>()
        while (at < html.length) {
            val separated = html[at].htmlSpace()
            while (html.getOrNull(at)?.htmlSpace() == true) at++
            if (html.getOrNull(at) == '>') return Tag(name, at + 1, closing, attributes)
            if (html.getOrNull(at) == '/' && html.getOrNull(at + 1) == '>')
                return if (closing || name in rawElements || name == "html" || name == "head" || name == "template") null
                    else Tag(name, at + 2, false, attributes)
            if (!separated || closing) return null
            val attributeStart = at
            while (html.getOrNull(at)?.let { !it.htmlSpace() && it !in "=/>'\"<`" } == true) at++
            if (at == attributeStart) return null
            val attributeName = html.substring(attributeStart, at).lowercase()
            if (attributes.size >= 256 || !attributeNames.add(attributeName)) return null
            val afterName = at
            while (html.getOrNull(at)?.htmlSpace() == true) at++
            var value = ""
            if (html.getOrNull(at) == '=') {
                at++
                while (html.getOrNull(at)?.htmlSpace() == true) at++
                val quote = html.getOrNull(at)
                if (quote == '\'' || quote == '"') {
                    val end = html.indexOf(quote, at + 1)
                    if (end < 0) return null
                    value = html.substring(at + 1, end)
                    at = end + 1
                } else {
                    val valueStart = at
                    while (html.getOrNull(at)?.let { !it.htmlSpace() && it != '>' } == true) {
                        if (html[at] in "'\"`=<") return null
                        at++
                    }
                    if (at == valueStart) return null
                    value = html.substring(valueStart, at)
                }
            } else at = afterName
            attributes += attributeName to value
        }
        return null
    }

    private fun Char.asciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.htmlSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C'
    private fun String.trimHtmlSpace() = trim { it.htmlSpace() }
}
