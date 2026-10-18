package com.jarvys.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Picture
import android.graphics.RectF
import android.util.Xml
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.renderer.LatexAutoWrap
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import org.intellij.markdown.MarkdownTokenTypes
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.io.StringWriter
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.ceil
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal data class MarkdownFenceSource(
    val language: String,
    val source: String,
    val closed: Boolean,
    val headerComplete: Boolean,
)

internal fun fenceSource(content: String, node: org.intellij.markdown.ast.ASTNode): MarkdownFenceSource {
    val languageNode = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
    val language = languageNode
        ?.let { content.substring(it.startOffset, it.endOffset).trim().takeWhile { char -> !char.isWhitespace() }.lowercase() }.orEmpty()
    val source = node.children.filter { it.type == org.intellij.markdown.MarkdownTokenTypes.CODE_FENCE_CONTENT }
        .joinToString("") { content.substring(it.startOffset, it.endOffset) }
    val closed = node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }
    val languageIndex = languageNode?.let(node.children::indexOf) ?: -1
    val headerComplete = node.children.withIndex().any { (index, child) ->
        (languageIndex < 0 || index > languageIndex) &&
            (child.type == MarkdownTokenTypes.EOL || child.type == MarkdownTokenTypes.CODE_FENCE_CONTENT)
    }
    return MarkdownFenceSource(language, source, closed, headerComplete)
}

internal enum class InlineFenceState { RENDER, STREAM_PLACEHOLDER, CODE }

internal fun inlineFenceState(
    language: String,
    closed: Boolean,
    streaming: Boolean,
    headerComplete: Boolean,
): InlineFenceState = when {
    !closed && streaming && (inlineBlockRenderer(language) != null || !headerComplete) -> InlineFenceState.STREAM_PLACEHOLDER
    closed && inlineBlockRenderers[language.lowercase()] != null -> InlineFenceState.RENDER
    else -> InlineFenceState.CODE
}

/** One generic language registry is the only place that associates fence tags with viewers. */
internal interface InlineBlockRenderer {
    @Composable fun Render(source: String, modifier: Modifier, label: String)
}

private val inlineBlockRenderers: Map<String, InlineBlockRenderer> = mapOf(
    "svg" to SvgInlineBlockRenderer,
    "chart" to ChartInlineBlockRenderer,
    "latex" to LatexInlineBlockRenderer,
    "tex" to LatexInlineBlockRenderer,
)

internal fun inlineBlockRenderer(language: String): InlineBlockRenderer? = inlineBlockRenderers[language.lowercase()]

@Composable
internal fun RenderAssistantCodeFence(
    content: String,
    node: org.intellij.markdown.ast.ASTNode,
    codeStyle: androidx.compose.ui.text.TextStyle,
    streaming: Boolean,
) {
    val fence = fenceSource(content, node)
    val renderer = inlineBlockRenderer(fence.language)
    val state = inlineFenceState(fence.language, fence.closed, streaming, fence.headerComplete)
    if (renderer == null && state == InlineFenceState.CODE) {
        val observer = LocalAssistantMarkdownRenderObserver.current
        if (!fence.closed && streaming) {
            SideEffect { observer?.onStreamingCodeFallbackComposed(fence.language) }
        }
        MarkdownCodeFence(content, node, codeStyle)
        return
    }
    val sizeMotion = if (LocalReducedMotion.current) Modifier else Modifier.animateContentSize(JarvysMotion.expand())
    // Keep one fence slot through streaming updates; only its inline content changes on closure.
    Box(Modifier.fillMaxWidth().then(sizeMotion)) {
        when (state) {
            InlineFenceState.STREAM_PLACEHOLDER -> InlineBlockPlaceholder(
                fence.language.takeIf { renderer != null },
            )
            InlineFenceState.RENDER -> ObservedInlineBlock(renderer!!, fence.source, fence.language)
            InlineFenceState.CODE -> AssistantCodeFallback(fence.source, fence.language, codeStyle)
        }
    }
}

@Composable
private fun InlineBlockPlaceholder(language: String?) {
    val renderingLabel = if (language != null) stringResource(R.string.inline_block_rendering, language)
        else stringResource(R.string.inline_block_pending)
    val observer = LocalAssistantMarkdownRenderObserver.current
    SideEffect { observer?.onPlaceholderComposed(language.orEmpty()) }
    Text(renderingLabel, Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ObservedInlineBlock(renderer: InlineBlockRenderer, source: String, label: String) {
    val observer = LocalAssistantMarkdownRenderObserver.current
    DisposableEffect(label) {
        observer?.onBlockMounted(label)
        onDispose { }
    }
    renderer.Render(source, Modifier.fillMaxWidth(), label)
}

@Composable
internal fun AssistantCodeFallback(source: String, language: String, style: androidx.compose.ui.text.TextStyle) {
    val context = LocalContext.current
    val codeLabel = stringResource(R.string.inline_block_code_label)
    val background = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(language.ifBlank { codeLabel }, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { copyBlockSource(context, source) }) {
                Text(stringResource(R.string.chat_footer_copy), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(source, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun copyBlockSource(context: Context, source: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_footer_clip_label), source))
}

internal data class ParsedSvg(val svg: SVG, val width: Float, val height: Float, val description: String)
internal data class PreparedSvg(val picture: Picture, val width: Float, val height: Float, val description: String)

internal fun ParsedSvg.renderToPicture(currentColor: Color? = null): PreparedSvg {
    val pictureWidth = ceil(width.toDouble()).toInt()
    val pictureHeight = ceil(height.toDouble()).toInt()
    require(pictureWidth > 0 && pictureHeight > 0)
    val options = RenderOptions.create()
    if (currentColor != null) options.css("svg { color: ${currentColor.toCssHexColor()}; }")
    return PreparedSvg(svg.renderToPicture(pictureWidth, pictureHeight, options), width, height, description)
}

private fun Color.toCssHexColor(): String = "#%06X".format(Locale.ROOT, toArgb() and 0x00FFFFFF)

private object SvgInlineBlockRenderer : InlineBlockRenderer {
    @Composable override fun Render(source: String, modifier: Modifier, label: String) {
        val currentColor = MaterialTheme.colorScheme.onSurface
        val observer = LocalAssistantMarkdownRenderObserver.current
        val result = remember(source, currentColor) {
            observer?.onBlockSourceParsed(label)
            runCatching { sanitizeAndParseSvg(source).renderToPicture(currentColor) }
        }
        val prepared = result.getOrNull()
        if (prepared == null) {
            AssistantCodeFallback(source, label, MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            return
        }
        val svgFigureLabel = stringResource(R.string.inline_block_svg_figure)
        val accessibleLabel = prepared.description.ifBlank { svgFigureLabel }
        Canvas(
            modifier.fillMaxWidth()
                .aspectRatio(prepared.width / prepared.height)
                .copySourceAction(source)
                .semantics { contentDescription = accessibleLabel },
        ) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPicture(prepared.picture, RectF(0f, 0f, size.width, size.height))
            }
        }
    }
}

internal fun sanitizeAndParseSvg(source: String): ParsedSvg {
    val sanitizedSource = sanitizeSvgSource(source)
    val metadata = inspectSanitizedSvg(sanitizedSource)
    return ParsedSvg(SVG.getFromString(sanitizedSource), metadata.width, metadata.height, metadata.description)
}

/** Sanitizes before and after the XML-only context-paint compatibility rewrite. */
internal fun sanitizeSvgSource(source: String): String {
    inspectSanitizedSvg(source)
    val transformed = cloneContextPaintMarkers(source)
    if (transformed != source) inspectSanitizedSvg(transformed)
    return transformed
}

private data class SvgMetadata(val width: Float, val height: Float, val description: String)

private fun inspectSanitizedSvg(source: String): SvgMetadata {
    require(source.isNotBlank())
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(StringReader(source))
    }
    var rootSeen = false
    var width = Float.NaN
    var height = Float.NaN
    var viewBoxSeen = false
    var title = ""
    var description = ""
    var modelLabel = ""
    var titleText: StringBuilder? = null
    var descriptionText: StringBuilder? = null
    var styleText: StringBuilder? = null
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.DOCDECL, XmlPullParser.PROCESSING_INSTRUCTION ->
                throw IllegalArgumentException("SVG declarations and processing instructions are not supported")
            XmlPullParser.START_TAG -> {
                val name = parser.name.substringAfter(':').lowercase()
                require(name in supportedSvgElements)
                if (parser.depth == 1) {
                    require(name == "svg")
                    rootSeen = true
                    val viewBoxSource = parser.getAttributeValue(null, "viewBox")
                    require(!viewBoxSource.isNullOrBlank())
                    val viewBox = viewBoxSource.trim()
                        .split(',', ' ', '\n', '\r', '\t').filter(String::isNotBlank).map(String::toFloat)
                    require(viewBox.size == 4)
                    viewBoxSeen = true
                    width = viewBox[2]
                    height = viewBox[3]
                    modelLabel = parser.getAttributeValue(null, "aria-label")?.trim().orEmpty()
                }
                for (index in 0 until parser.attributeCount) {
                    val attributeName = parser.getAttributeName(index).substringAfter(':').lowercase()
                    val value = parser.getAttributeValue(index).trim()
                    require(!attributeName.startsWith("on"))
                    if (name == "marker" && attributeName == "orient") {
                        require(!value.equals("auto-start-reverse", ignoreCase = true)) {
                            "SVG marker orient=auto-start-reverse is not supported by this renderer"
                        }
                    }
                    if (attributeName == "href" || attributeName == "src") require(value.startsWith('#') && value.length > 1)
                    require(attributeName != "base")
                    validateCssReferences(value)
                }
                if (parser.depth == 2 && name == "title") titleText = StringBuilder()
                if (parser.depth == 2 && name == "desc") descriptionText = StringBuilder()
                if (name == "style") styleText = StringBuilder()
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                titleText?.append(parser.text)
                descriptionText?.append(parser.text)
                styleText?.append(parser.text)
            }
            XmlPullParser.ENTITY_REF -> if (styleText != null) {
                throw IllegalArgumentException("XML entity references are not supported in SVG styles")
            }
            XmlPullParser.END_TAG -> {
                if (parser.depth == 2) {
                    when (parser.name.substringAfter(':').lowercase()) {
                        "title" -> { title = titleText?.toString()?.trim().orEmpty(); titleText = null }
                        "desc" -> { description = descriptionText?.toString()?.trim().orEmpty(); descriptionText = null }
                    }
                }
                if (parser.name.substringAfter(':').equals("style", ignoreCase = true)) {
                    validateCssReferences(styleText?.toString().orEmpty())
                    styleText = null
                }
            }
        }
        event = parser.nextToken()
    }
    require(rootSeen && viewBoxSeen)
    require(width > 0f && height > 0f && width.isFinite() && height.isFinite())
    val aspectRatio = width / height
    require(aspectRatio > 0f && aspectRatio.isFinite())
    val accessibleDescription = listOf(title, description).filter(String::isNotBlank).joinToString(". ")
        .ifBlank { modelLabel }
    return SvgMetadata(width, height, accessibleDescription)
}

private val supportedSvgElements = setOf(
    "svg", "g", "defs", "title", "desc", "rect", "circle", "ellipse", "line", "polyline",
    "polygon", "path", "text", "tspan", "style", "lineargradient", "radialgradient", "stop",
    "clippath", "mask", "pattern", "marker",
)

/** Reject all non-fragment CSS URLs without pattern-based sanitization. */
private fun validateCssReferences(css: String) {
    require(!css.contains("\\") && !css.contains("/*"))
    var index = 0
    while (index < css.length) {
        if (css[index].equals('@', ignoreCase = true) && css.regionMatches(index, "@import", 0, 7, ignoreCase = true)) {
            throw IllegalArgumentException("External stylesheet references are not supported")
        }
        if (css.regionMatches(index, "url", 0, 3, ignoreCase = true)) {
            var open = index + 3
            while (open < css.length && css[open].isWhitespace()) open++
            if (open < css.length && css[open] == '(') {
                var end = open + 1
                var quote: Char? = null
                while (end < css.length) {
                    val char = css[end]
                    if (quote != null) {
                        if (char == quote) quote = null
                    } else if (char == '\'' || char == '"') quote = char
                    else if (char == ')') break
                    end++
                }
                require(end < css.length)
                val target = css.substring(open + 1, end).trim().removeSurrounding("\"").removeSurrounding("'").trim()
                require(target.startsWith('#') && target.length > 1)
                index = end
            }
        }
        index++
    }
}

/*
 * AndroidSVG 1.4 does not implement context-stroke/context-fill. Clone the referenced marker once
 * for each referencing SVG element and replace those paints in the clone with the referencing
 * element's resolved paint. Marker placement and geometry remain AndroidSVG's responsibility.
 */
private fun cloneContextPaintMarkers(source: String): String {
    val document = parseSvgDom(source)
    val root = document.documentElement
    val elements = document.allElements()
    val markers = elements.filter { it.localTagName() == "marker" }
    if (markers.isEmpty()) return source

    val markerIds = markers.mapNotNull { it.getAttribute("id").takeIf(String::isNotBlank) }.toSet()
    val rules = document.svgCssRules()
    val computed = document.computedSvgStyles(rules)
    val authoredStyleSheets = elements.filter { it.localTagName() == "style" && !it.ancestorOrSelfIsMarker() }
        .associateWith { it.textContent.orEmpty() }
    val markerById = markers.mapNotNull { marker ->
        marker.getAttribute("id").takeIf(String::isNotBlank)?.let { it to marker }
    }.toMap()

    // Nested markers and marker-to-marker references can recurse through rendering. Reject these
    // structures before cloning rather than trying to expand or bound their graph.
    for (marker in markers) {
        require(marker.descendants().none { it.localTagName() == "marker" }) {
            "Nested SVG markers are not supported"
        }
        for (descendant in listOf(marker) + marker.descendants()) {
            for (property in markerProperties) {
                val referencedId = computed[descendant]?.get(property)?.let(::fragmentUrlId)
                require(referencedId == null || referencedId !in markerIds) {
                    "Marker-to-marker references are not supported"
                }
            }
        }
    }

    var defs = root.childElements().firstOrNull { it.localTagName() == "defs" }
    val usedIds = elements.mapNotNull { it.getAttribute("id").takeIf(String::isNotBlank) }.toMutableSet()
    var cloneSequence = 0
    fun freshCloneId(): String {
        while (true) {
            val candidate = "svg_context_marker_${cloneSequence++}"
            if (usedIds.add(candidate)) return candidate
        }
    }
    var changed = false

    for (referencer in elements) {
        if (referencer.localTagName() == "marker" || referencer.ancestorOrSelfIsMarker()) continue
        val style = computed[referencer] ?: continue
        val contextReferences = markerProperties.mapNotNull { property ->
            val markerId = fragmentUrlId(style[property].orEmpty()) ?: return@mapNotNull null
            val marker = markerById[markerId] ?: return@mapNotNull null
            val paints = markerNeedsPaintRewrite(marker, computed, rules)
            if (paints) property to (markerId to marker) else null
        }
        val uniqueMarkers = contextReferences.map { it.second.first }.distinct()
        if (uniqueMarkers.isEmpty()) continue
        require(uniqueMarkers.size == 1) {
            "An SVG element cannot reference multiple context-paint markers"
        }
        val (markerId, marker) = markerById.getValue(uniqueMarkers.single()).let { uniqueMarkers.single() to it }
        require(elements.count { it.getAttribute("id") == markerId } == 1) {
            "Referenced SVG marker ids must be unique"
        }
        val cloned = marker.cloneNode(true) as Element
        val cloneIds = linkedMapOf<String, String>()
        val markerCloneId = freshCloneId()
        cloneIds[markerId] = markerCloneId
        val sourceSubtree = listOf(marker) + marker.descendants()
        val cloneSubtree = listOf(cloned) + cloned.descendants()
        for (index in sourceSubtree.indices) {
            val oldId = sourceSubtree[index].getAttribute("id").takeIf(String::isNotBlank) ?: continue
            require(elements.count { it.getAttribute("id") == oldId } == 1) {
                "Ids inside referenced SVG markers must be unique"
            }
            if (oldId !in cloneIds) cloneIds[oldId] = freshCloneId()
            cloneSubtree[index].setAttribute("id", cloneIds.getValue(oldId))
        }
        rewriteClonedReferences(cloned, cloneIds)
        appendClonedIdCssRules(authoredStyleSheets, cloneIds)
        val cloneId = cloneIds.getValue(markerId)

        val targetStyle = style.toMutableMap()
        for (property in listOf("fill", "stroke")) {
            val paint = targetStyle[property].orEmpty()
            if (paint.equals("currentColor", ignoreCase = true)) targetStyle[property] = targetStyle["color"] ?: "currentColor"
        }
        replaceMarkerContextPaints(
            cloned, marker, targetStyle,
            hasExplicitSvgProperty(referencer, "fill", rules), computed, rules,
        )
        val outputDefs = defs ?: document.createElementNS(root.namespaceURI, "defs").also {
            root.insertBefore(it, root.firstChild)
            defs = it
        }
        outputDefs.appendChild(cloned)

        val inlineOverrides = linkedMapOf<String, String>()
        contextReferences.forEach { (property, _) -> inlineOverrides[property] = "url(#$cloneId)" }
        setInlineStyles(referencer, inlineOverrides)
        changed = true
    }

    if (!changed) return source
    return serializeSvgDom(document)
}

private val markerProperties = listOf("marker-start", "marker-mid", "marker-end")

private fun rewriteClonedReferences(clone: Element, idMap: Map<String, String>) {
    for (element in listOf(clone) + clone.descendants()) {
        val attrs = element.attributes
        for (index in 0 until attrs.length) {
            val attr = attrs.item(index)
            val localName = (attr.localName ?: attr.nodeName.substringAfter(':')).lowercase(Locale.ROOT)
            attr.nodeValue = if (localName == "href" || localName == "src") {
                idMap[attr.nodeValue.trim().removePrefix("#")]?.let { "#$it" } ?: attr.nodeValue
            } else rewriteUrlFragments(attr.nodeValue, idMap)
        }
        if (element.localTagName() == "style") element.textContent = rewriteCssSelectorsAndUrls(element.textContent.orEmpty(), idMap)
    }
}

private fun rewriteUrlFragments(value: String, idMap: Map<String, String>): String {
    val output = StringBuilder()
    var index = 0
    while (index < value.length) {
        if (!value.regionMatches(index, "url", 0, 3, ignoreCase = true)) {
            output.append(value[index++])
            continue
        }
        var open = index + 3
        while (open < value.length && value[open].isWhitespace()) open++
        if (open >= value.length || value[open] != '(') {
            output.append(value[index++])
            continue
        }
        var end = open + 1
        var quote: Char? = null
        while (end < value.length) {
            val char = value[end]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '\'' || char == '"') quote = char
            else if (char == ')') break
            end++
        }
        if (end == value.length) {
            output.append(value.substring(index))
            break
        }
        val rawTarget = value.substring(open + 1, end)
        val quoteChar = rawTarget.trim().firstOrNull()?.takeIf { it == '\'' || it == '"' }
        val target = rawTarget.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        val rewritten = if (target.startsWith('#')) idMap[target.drop(1)]?.let { "#$it" } else null
        output.append(value.substring(index, open + 1))
        if (rewritten == null) output.append(rawTarget) else {
            if (quoteChar != null) output.append(quoteChar)
            output.append(rewritten)
            if (quoteChar != null) output.append(quoteChar)
        }
        output.append(')')
        index = end + 1
    }
    return output.toString()
}

private fun rewriteCssSelectorsAndUrls(css: String, idMap: Map<String, String>): String {
    val output = StringBuilder()
    var index = 0
    while (index < css.length) {
        val open = css.indexOf('{', index)
        if (open < 0) {
            output.append(css.substring(index))
            break
        }
        val close = css.indexOf('}', open + 1)
        if (close < 0) {
            output.append(css.substring(index))
            break
        }
        output.append(rewriteCssSelectorIds(css.substring(index, open), idMap))
        output.append('{').append(rewriteUrlFragments(css.substring(open + 1, close), idMap)).append('}')
        index = close + 1
    }
    return output.toString()
}

private fun appendClonedIdCssRules(styleSheets: Map<Element, String>, idMap: Map<String, String>) {
    for ((styleElement, originalCss) in styleSheets) {
        val clonedRules = StringBuilder()
        var index = 0
        while (index < originalCss.length) {
            val open = originalCss.indexOf('{', index)
            if (open < 0) break
            val close = originalCss.indexOf('}', open + 1)
            if (close < 0) break
            val selectors = splitCss(originalCss.substring(index, open), ',').map(String::trim)
            val clonedSelectors = selectors.map { it to rewriteCssSelectorIds(it, idMap) }
                .filter { (sourceSelector, clonedSelector) -> sourceSelector != clonedSelector }
                .map { it.second }
            if (clonedSelectors.isNotEmpty()) {
                clonedRules.append(clonedSelectors.joinToString(", ")).append('{')
                    .append(originalCss.substring(open + 1, close)).append("}\n")
            }
            index = close + 1
        }
        if (clonedRules.isNotEmpty()) styleElement.textContent = styleElement.textContent.orEmpty() + "\n" + clonedRules
    }
}

private fun rewriteCssSelectorIds(selector: String, idMap: Map<String, String>): String {
    val output = StringBuilder()
    var index = 0
    while (index < selector.length) {
        if (selector[index] != '#') {
            output.append(selector[index++])
            continue
        }
        var end = index + 1
        while (end < selector.length && (selector[end].isLetterOrDigit() || selector[end] == '_' || selector[end] == '-')) end++
        val id = selector.substring(index + 1, end)
        output.append(idMap[id]?.let { "#$it" } ?: selector.substring(index, end))
        index = end
    }
    return output.toString()
}

private data class CssDeclaration(val name: String, val value: String, val important: Boolean)
private data class CssRule(val selector: String, val specificity: Int, val order: Int, val declarations: List<CssDeclaration>)
private data class CascadeValue(val value: String, val important: Boolean, val specificity: Int, val order: Int)

private fun markerNeedsPaintRewrite(
    marker: Element,
    computed: Map<Element, Map<String, String>>,
    rules: List<CssRule>,
): Boolean = (listOf(marker) + marker.descendants()).any { element ->
        val style = computed[element].orEmpty()
        val contextPaint = listOf("fill", "stroke").any { property ->
            style[property].orEmpty().equals("context-stroke", true) || style[property].orEmpty().equals("context-fill", true)
        }
        val implicitShapeFill = element.localTagName() in drawableSvgElements &&
            style["fill"].equals("#000000", true) && !hasExplicitSvgProperty(element, "fill", rules)
        contextPaint || implicitShapeFill
    }

private fun replaceMarkerContextPaints(
    clone: Element,
    original: Element,
    target: Map<String, String>,
    targetFillExplicit: Boolean,
    originalComputed: Map<Element, Map<String, String>>,
    rules: List<CssRule>,
) {
    val originalNodes = listOf(original) + original.descendants()
    val clonedNodes = listOf(clone) + clone.descendants()
    require(originalNodes.size == clonedNodes.size)
    val localComputed = IdentityHashMap<Element, MutableMap<String, String>>()
    for (index in originalNodes.indices) {
        val originalNode = originalNodes[index]
        val clonedNode = clonedNodes[index]
        val parent = clonedNode.parentNode as? Element
        val inherited = parent?.let { clonedParent ->
            localComputed[clonedParent]?.toMap() ?: (originalNode.parentNode as? Element)?.let { originalComputed[it] }.orEmpty()
        }.orEmpty()
        val values = cascadeStyle(originalNode, inherited, rules).toMutableMap()
        val overrides = linkedMapOf<String, String>()
        for (property in listOf("fill", "stroke")) {
            val paint = values[property].orEmpty()
            if (paint.equals("context-stroke", true) || paint.equals("context-fill", true)) {
                val sourceProperty = if (paint.equals("context-stroke", true)) "stroke" else "fill"
                var resolved = target[sourceProperty] ?: if (sourceProperty == "fill") "#000000" else "none"
                if (resolved.equals("currentColor", true)) resolved = target["color"] ?: "currentColor"
                require(!resolved.equals("context-stroke", true) && !resolved.equals("context-fill", true)) {
                    "Nested context paints are not supported"
                }
                overrides[property] = resolved
                values[property] = resolved
            }
        }
        // AndroidSVG and Chrome both follow the SVG default fill of black. For inline chat we
        // deliberately inherit an omitted marker fill from the referring stroke so a default
        // arrow remains visible in dark theme; authored marker fills are left intact.
        if (clonedNode === clone && clonedNode.localTagName() == "marker" &&
            !hasExplicitSvgProperty(originalNode, "fill", rules)) {
            val stroke = target["stroke"] ?: "none"
            val referencerFill = target["fill"] ?: "#000000"
            val fill = when {
                !stroke.equals("none", true) -> stroke
                targetFillExplicit && !referencerFill.equals("none", true) -> referencerFill
                else -> target["color"] ?: "currentColor"
            }
            clonedNode.setAttribute("fill", fill)
            values["fill"] = fill
        }
        if (overrides.isNotEmpty()) setInlineStyles(clonedNode, overrides)
        localComputed[clonedNode] = values
    }
}

private fun setInlineStyles(element: Element, replacements: Map<String, String>) {
    val declarations = parseDeclarations(element.getAttribute("style"))
        .filterNot { it.name in replacements }
        .toMutableList()
    replacements.forEach { (name, value) -> declarations += CssDeclaration(name, value, important = false) }
    element.setAttribute("style", declarations.joinToString("; ") {
        "${it.name}: ${it.value}${if (it.important) " !important" else ""}"
    })
}

private fun parseSvgDom(source: String): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        // The pull-parser validation immediately before this function rejects DOCTYPE and
        // external references. Older Android XML providers do not expose every JAXP knob.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    }
    return factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(source)))
}

private fun serializeSvgDom(document: Document): String {
    val transformerFactory = TransformerFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    val transformer = transformerFactory.newTransformer().apply {
        setOutputProperty(OutputKeys.METHOD, "xml")
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        setOutputProperty(OutputKeys.INDENT, "no")
    }
    val output = StringWriter()
    transformer.transform(DOMSource(document), StreamResult(output))
    return output.toString()
}

private fun Document.allElements(): List<Element> = documentElement.descendants(includeSelf = true)

private fun Element.descendants(includeSelf: Boolean = false): List<Element> = buildList {
    if (includeSelf) add(this@descendants)
    fun appendChildren(parent: Element) {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) {
                add(child)
                appendChildren(child)
            }
            child = child.nextSibling
        }
    }
    appendChildren(this@descendants)
}

private fun Element.childElements(): List<Element> = buildList {
    var child = firstChild
    while (child != null) {
        if (child is Element) add(child)
        child = child.nextSibling
    }
}

private fun Element.localTagName(): String = (localName ?: tagName.substringAfter(':')).lowercase(Locale.ROOT)

private fun Element.ancestorOrSelfIsMarker(): Boolean {
    var element: Element? = this
    while (element != null) {
        if (element.localTagName() == "marker") return true
        element = element.parentNode as? Element
    }
    return false
}

private fun Document.svgCssRules(): List<CssRule> {
    val rules = mutableListOf<CssRule>()
    var order = 0
    for (style in allElements().filter { it.localTagName() == "style" }) {
        val css = style.textContent.orEmpty()
        var index = 0
        while (index < css.length) {
            while (index < css.length && css[index].isWhitespace()) index++
            val open = css.indexOf('{', index)
            if (open < 0) break
            val close = css.indexOf('}', open + 1)
            if (close < 0) break
            val selectorText = css.substring(index, open).trim()
            val declarations = parseDeclarations(css.substring(open + 1, close))
            for (selector in splitCss(selectorText, ',')) {
                val specificity = selectorSpecificity(selector.trim()) ?: continue
                rules += CssRule(selector.trim(), specificity, order++, declarations)
            }
            index = close + 1
        }
    }
    return rules
}

private fun Document.computedSvgStyles(rules: List<CssRule>): IdentityHashMap<Element, Map<String, String>> {
    val result = IdentityHashMap<Element, Map<String, String>>()
    fun visit(element: Element, parent: Map<String, String>) {
        val values = cascadeStyle(element, parent, rules)
        result[element] = values
        element.childElements().forEach { visit(it, values) }
    }
    visit(documentElement, emptyMap())
    return result
}

private fun cascadeStyle(element: Element, parent: Map<String, String>, rules: List<CssRule>): Map<String, String> {
    val inheritedProperties = listOf("fill", "stroke", "color", "marker-start", "marker-mid", "marker-end")
    val values = linkedMapOf(
        "fill" to (parent["fill"] ?: "#000000"),
        "stroke" to (parent["stroke"] ?: "none"),
        "color" to (parent["color"] ?: defaultSvgValue("color")),
        "marker-start" to (parent["marker-start"] ?: "none"),
        "marker-mid" to (parent["marker-mid"] ?: "none"),
        "marker-end" to (parent["marker-end"] ?: "none"),
    )
    val winners = mutableMapOf<String, CascadeValue>()
    for (property in inheritedProperties) {
        winningSvgDeclaration(element, property, rules)?.let { winners[property] = it }
    }
    winners.forEach { (property, winner) ->
        if (winner.value.equals("inherit", true) || winner.value.equals("unset", true)) values[property] = parent[property] ?: defaultSvgValue(property)
        else if (winner.value.equals("initial", true)) values[property] = defaultSvgValue(property)
        else values[property] = winner.value
    }
    return values
}

private val drawableSvgElements = setOf("path", "line", "polyline", "polygon", "rect", "circle", "ellipse", "text", "tspan")

private fun hasExplicitSvgProperty(element: Element, property: String, rules: List<CssRule>): Boolean {
    var current: Element? = element
    while (current != null) {
        val declaration = winningSvgDeclaration(current, property, rules)
        if (declaration != null && !declaration.value.equals("inherit", true) && !declaration.value.equals("unset", true)) {
            return true
        }
        current = current.parentNode as? Element
    }
    return false
}

private fun winningSvgDeclaration(element: Element, property: String, rules: List<CssRule>): CascadeValue? {
    val winners = mutableMapOf<String, CascadeValue>()
    if (element.hasAttribute(property)) {
        winners[property] = CascadeValue(element.getAttribute(property).trim(), false, 0, -1)
    }
    for (rule in rules) {
        if (selectorMatches(element, rule.selector)) for (declaration in rule.declarations) {
            if (declaration.name == property) applyCascade(winners, declaration, rule.specificity, rule.order)
        }
    }
    val inline = parseDeclarations(element.getAttribute("style"))
    inline.forEachIndexed { index, declaration ->
        if (declaration.name == property) applyCascade(winners, declaration, 1000, Int.MAX_VALUE - inline.size + index)
    }
    return winners[property]
}

private fun defaultSvgValue(property: String): String = when (property) {
    "fill" -> "#000000"
    "stroke" -> "none"
    // Preserve the renderer's configured root color; RenderOptions supplies the active theme.
    "color" -> "currentColor"
    else -> "none"
}

private fun applyCascade(winners: MutableMap<String, CascadeValue>, declaration: CssDeclaration, specificity: Int, order: Int) {
    val candidate = CascadeValue(declaration.value, declaration.important, specificity, order)
    val old = winners[declaration.name]
    if (old == null || compareValuesBy(candidate, old, CascadeValue::important, CascadeValue::specificity, CascadeValue::order) >= 0) {
        winners[declaration.name] = candidate
    }
}

private fun parseDeclarations(source: String): List<CssDeclaration> = splitCss(source, ';').mapNotNull { raw ->
    val colon = raw.indexOf(':')
    if (colon <= 0) return@mapNotNull null
    val name = raw.substring(0, colon).trim().lowercase(Locale.ROOT)
    var value = raw.substring(colon + 1).trim()
    if (name.isBlank() || value.isBlank()) return@mapNotNull null
    val important = value.endsWith("!important", ignoreCase = true)
    if (important) value = value.dropLast("!important".length).trim()
    CssDeclaration(name, value, important)
}

private fun splitCss(source: String, delimiter: Char): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var quote: Char? = null
    var depth = 0
    for (index in source.indices) {
        val char = source[index]
        if (quote != null) {
            if (char == quote) quote = null
        } else when (char) {
            '\'', '"' -> quote = char
            '(' -> depth++
            ')' -> if (depth > 0) depth--
            delimiter -> if (depth == 0) {
                result += source.substring(start, index)
                start = index + 1
            }
        }
    }
    result += source.substring(start)
    return result
}

private fun selectorSpecificity(selector: String): Int? {
    val parts = selector.trim().split(Regex("\\s+"))
    if (parts.isEmpty() || parts.any { it.isBlank() || it.contains(':') || it.contains('[') || it.contains('>') || it.contains('+') || it.contains('~') }) return null
    var specificity = 0
    for (part in parts) {
        var index = 0
        while (index < part.length) {
            when (part[index]) {
                '#' -> { specificity += 100; index++ }
                '.' -> { specificity += 10; index++ }
                '*' -> index++
                else -> {
                    specificity++
                    while (index < part.length && part[index] != '.' && part[index] != '#') index++
                }
            }
            while (index < part.length && part[index] != '.' && part[index] != '#') index++
        }
    }
    return specificity
}

private fun selectorMatches(element: Element, selector: String): Boolean {
    val parts = selector.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (parts.isEmpty() || !simpleSelectorMatches(element, parts.last())) return false
    var ancestor = element.parentNode as? Element
    for (index in parts.lastIndex - 1 downTo 0) {
        while (ancestor != null && !simpleSelectorMatches(ancestor, parts[index])) ancestor = ancestor.parentNode as? Element
        if (ancestor == null) return false
        ancestor = ancestor.parentNode as? Element
    }
    return true
}

private fun simpleSelectorMatches(element: Element, selector: String): Boolean {
    var index = 0
    var tag: String? = null
    val classes = mutableListOf<String>()
    var id: String? = null
    while (index < selector.length) {
        when (selector[index]) {
            '#' -> {
                index++
                val start = index
                while (index < selector.length && selector[index] != '.' && selector[index] != '#') index++
                id = selector.substring(start, index)
            }
            '.' -> {
                index++
                val start = index
                while (index < selector.length && selector[index] != '.' && selector[index] != '#') index++
                classes += selector.substring(start, index)
            }
            '*' -> index++
            else -> {
                val start = index
                while (index < selector.length && selector[index] != '.' && selector[index] != '#') index++
                tag = selector.substring(start, index).substringAfter('|')
            }
        }
    }
    if (tag != null && element.localTagName() != tag.lowercase(Locale.ROOT)) return false
    if (id != null && element.getAttribute("id") != id) return false
    val elementClasses = element.getAttribute("class").split(Regex("\\s+")).toSet()
    return classes.all { it in elementClasses }
}

private fun fragmentUrlId(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.startsWith("url", ignoreCase = true)) return null
    val open = trimmed.indexOf('(')
    if (open < 0 || !trimmed.endsWith(')')) return null
    val target = trimmed.substring(open + 1, trimmed.length - 1).trim().removeSurrounding("\"").removeSurrounding("'").trim()
    return target.takeIf { it.startsWith('#') && it.length > 1 }?.drop(1)
}


internal data class ChartSeries(val name: String, val values: List<Double>)
internal data class ChartSpec(val type: String, val title: String, val labels: List<String>, val series: List<ChartSeries>) {
    fun accessibilitySummary(chartLabel: String, seriesLabel: String): String = buildString {
        append(title.ifBlank { chartLabel })
        series.forEach { data ->
            append(". ").append(data.name.ifBlank { seriesLabel }).append(": ")
            append(labels.indices.joinToString(", ") { index -> "${labels[index]} ${data.values[index]}" })
        }
    }
}

internal fun parseChartSpec(source: String, seriesLabel: String): ChartSpec? = runCatching {
    val json = JSONObject(source)
    val type = (json.get("type") as? String)?.lowercase() ?: error("Chart type is required")
    require(type in setOf("bar", "line", "pie"))
    val title = (json.get("title") as? String)?.trim() ?: error("Chart title is required")
    require(title.isNotBlank())
    val labels = json.getJSONArray("labels").toStringList()
    val seriesJson = json.getJSONArray("series")
    require(labels.isNotEmpty() && seriesJson.length() > 0)
    val series = (0 until seriesJson.length()).map { index ->
        val item = seriesJson.getJSONObject(index)
        val valuesJson = item.getJSONArray("values")
        val values = (0 until valuesJson.length()).map { valueIndex ->
            val value = valuesJson.get(valueIndex) as? Number ?: error("Chart values must be numbers")
            value.toDouble()
        }
        require(values.size == labels.size && values.all(Double::isFinite))
        val rawName = item.opt("name")
        val name = when (rawName) {
            null, JSONObject.NULL -> null
            is String -> rawName.trim().takeIf(String::isNotBlank)
            else -> rawName.toString().trim().takeIf(String::isNotBlank)
        } ?: "$seriesLabel ${index + 1}"
        ChartSeries(name, values)
    }
    val allValues = series.flatMap { it.values }
    require((allValues.maxOrNull()!! - allValues.minOrNull()!!).isFinite())
    if (type == "pie") {
        require(series.size == 1 && series.single().values.all { it >= 0.0 })
        val total = series.single().values.sum()
        require(total.isFinite() && total > 0.0)
    }
    ChartSpec(type, title, labels, series)
}.getOrNull()

private fun JSONArray.toStringList(): List<String> = (0 until length()).map {
    (get(it) as? String)?.takeIf(String::isNotBlank) ?: error("Chart labels must be non-empty strings")
}

private object ChartInlineBlockRenderer : InlineBlockRenderer {
    @Composable override fun Render(source: String, modifier: Modifier, label: String) {
        val seriesLabel = stringResource(R.string.inline_block_series_label)
        val observer = LocalAssistantMarkdownRenderObserver.current
        val spec = remember(source, seriesLabel) {
            observer?.onBlockSourceParsed(label)
            parseChartSpec(source, seriesLabel)
        }
        if (spec == null) {
            AssistantCodeFallback(source, label, MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
        } else NativeChart(spec, modifier, source)
    }
}

internal fun isValidLatex(source: String): Boolean = runCatching {
    source.isNotBlank() && LatexParser().parseWithDiagnostics(source).errors.isEmpty()
}.getOrDefault(false)

private object LatexInlineBlockRenderer : InlineBlockRenderer {
    @Composable override fun Render(source: String, modifier: Modifier, label: String) {
        if (!remember(source) { isValidLatex(source) }) {
            AssistantCodeFallback(source, label, MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            return
        }
        val monospaceStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        val renderResult = runCatching {
            LatexAutoWrap(
                latex = source,
                modifier = modifier.fillMaxWidth().copySourceAction(source),
                config = LatexConfig(
                    fontSize = 20.sp,
                    theme = LatexTheme.material3(MaterialTheme.colorScheme.copy(surface = Color.Transparent)),
                    accessibilityEnabled = true,
                ),
            )
        }
        if (renderResult.isFailure) AssistantCodeFallback(source, label, monospaceStyle)
    }
}

@Composable
private fun NativeChart(spec: ChartSpec, modifier: Modifier, source: String) {
    val colors = MaterialTheme.colorScheme
    val chartLabel = stringResource(R.string.inline_block_chart_label)
    val seriesLabel = stringResource(R.string.inline_block_series_label)
    val palette = listOf(colors.primary, colors.secondary, colors.tertiary, colors.error, colors.onSurfaceVariant)
    Column(modifier.fillMaxWidth().copySourceAction(source)
        .semantics { contentDescription = spec.accessibilitySummary(chartLabel, seriesLabel) }) {
        if (spec.title.isNotBlank()) {
            Text(spec.title, style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
        }
        if (spec.type == "pie") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(148.dp)) {
                    val values = spec.series.single().values
                    val total = values.sum()
                    var angle = -90f
                    values.forEachIndexed { index, value ->
                        val sweep = (value / total * 360.0).toFloat()
                        drawArc(palette[index % palette.size], angle, sweep, true)
                        angle += sweep
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    spec.labels.forEachIndexed { index, label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).background(palette[index % palette.size], RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(6.dp))
                            Text("$label - ${spec.series.single().values[index]}", color = colors.onSurface,
                                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            val minValue = spec.series.flatMap { it.values }.minOrNull() ?: 0.0
            val maxValue = spec.series.flatMap { it.values }.maxOrNull() ?: 0.0
            val low = minOf(0.0, minValue)
            val high = maxOf(0.0, maxValue)
            val range = (high - low).takeIf { it > 0.0 } ?: 1.0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(42.dp).height(164.dp), verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End) {
                    Text("${high.formatChartNumber()}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontSize = 10.sp)
                    Text("${(high + low).div(2).formatChartNumber()}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontSize = 10.sp)
                    Text("${low.formatChartNumber()}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, fontSize = 10.sp)
                }
                Spacer(Modifier.width(5.dp))
                Canvas(Modifier.weight(1f).height(164.dp)) {
                    val plotBottom = size.height - 2.dp.toPx()
                    val plotTop = 2.dp.toPx()
                    val zeroY = plotBottom - ((0.0 - low) / range).toFloat() * (plotBottom - plotTop)
                    listOf(0f, 0.5f, 1f).forEach { fraction ->
                        val y = plotBottom - fraction * (plotBottom - plotTop)
                        drawLine(colors.outlineVariant, androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                    }
                    if (spec.type == "bar") {
                        val groupWidth = size.width / spec.labels.size
                        val barWidth = groupWidth * 0.72f / spec.series.size
                        spec.series.forEachIndexed { seriesIndex, series ->
                            series.values.forEachIndexed { valueIndex, value ->
                                val x = groupWidth * valueIndex + groupWidth * 0.14f + barWidth * seriesIndex
                                val y = plotBottom - ((value - low) / range).toFloat() * (plotBottom - plotTop)
                                drawRect(palette[seriesIndex % palette.size], androidx.compose.ui.geometry.Offset(x, minOf(y, zeroY)),
                                    androidx.compose.ui.geometry.Size(barWidth, kotlin.math.abs(zeroY - y)))
                            }
                        }
                    } else {
                        spec.series.forEachIndexed { seriesIndex, series ->
                            val points = series.values.mapIndexed { index, value ->
                                androidx.compose.ui.geometry.Offset(
                                    if (series.values.size == 1) size.width / 2 else size.width * index / (series.values.size - 1),
                                    plotBottom - ((value - low) / range).toFloat() * (plotBottom - plotTop),
                                )
                            }
                            points.zipWithNext().forEach { (from, to) ->
                                drawLine(palette[seriesIndex % palette.size], from, to, 2.dp.toPx())
                            }
                            points.forEach { point -> drawCircle(palette[seriesIndex % palette.size], 4.dp.toPx(), point) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 47.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                spec.labels.forEach { label ->
                    Text(label, Modifier.weight(1f), color = colors.onSurfaceVariant, fontSize = 10.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                spec.series.forEachIndexed { index, series ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(palette[index % palette.size], RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(5.dp))
                        Text(series.name.ifBlank { seriesLabel }, color = colors.onSurface, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.copySourceAction(source: String): Modifier {
    val context = LocalContext.current
    val label = stringResource(R.string.inline_block_copy_source)
    return pointerInput(source) {
        detectTapGestures(onLongPress = { copyBlockSource(context, source) })
    }.semantics {
        customActions = listOf(CustomAccessibilityAction(label) {
            copyBlockSource(context, source)
            true
        })
    }
}

internal fun Double.formatChartNumber(): String = if (
    this % 1.0 == 0.0 && this >= Long.MIN_VALUE.toDouble() && this < Long.MAX_VALUE.toDouble()
) toLong().toString() else toString()
