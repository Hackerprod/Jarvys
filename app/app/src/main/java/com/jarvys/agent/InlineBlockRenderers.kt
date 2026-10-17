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
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale
import kotlin.math.ceil

internal data class MarkdownFenceSource(val language: String, val source: String, val closed: Boolean)

internal fun fenceSource(content: String, node: org.intellij.markdown.ast.ASTNode): MarkdownFenceSource {
    val language = node.children.firstOrNull { it.type == org.intellij.markdown.MarkdownTokenTypes.FENCE_LANG }
        ?.let { content.substring(it.startOffset, it.endOffset).trim().takeWhile { char -> !char.isWhitespace() }.lowercase() }.orEmpty()
    val source = node.children.filter { it.type == org.intellij.markdown.MarkdownTokenTypes.CODE_FENCE_CONTENT }
        .joinToString("") { content.substring(it.startOffset, it.endOffset) }
    val closed = node.children.any { it.type == org.intellij.markdown.MarkdownTokenTypes.CODE_FENCE_END }
    return MarkdownFenceSource(language, source, closed)
}

internal enum class InlineFenceState { RENDER, STREAM_PLACEHOLDER, CODE }

internal fun inlineFenceState(language: String, closed: Boolean, streaming: Boolean): InlineFenceState = when {
    inlineBlockRenderers[language.lowercase()] == null -> InlineFenceState.CODE
    !closed && streaming -> InlineFenceState.STREAM_PLACEHOLDER
    closed -> InlineFenceState.RENDER
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
    if (renderer == null) {
        MarkdownCodeFence(content, node, codeStyle)
        return
    }
    val state = inlineFenceState(fence.language, fence.closed, streaming)
    val sizeMotion = if (LocalReducedMotion.current) Modifier else Modifier.animateContentSize(JarvysMotion.expand())
    // Keep one fence slot through streaming updates; only its inline content changes on closure.
    Box(Modifier.fillMaxWidth().then(sizeMotion)) {
        when (state) {
            InlineFenceState.STREAM_PLACEHOLDER -> InlineBlockPlaceholder(fence.language)
            InlineFenceState.RENDER -> renderer.Render(fence.source, Modifier.fillMaxWidth(), fence.language)
            InlineFenceState.CODE -> AssistantCodeFallback(fence.source, fence.language, codeStyle)
        }
    }
}

@Composable
private fun InlineBlockPlaceholder(language: String) {
    val renderingLabel = stringResource(R.string.inline_block_rendering, language)
    Text(renderingLabel, Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
        val result = remember(source, currentColor) {
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
    return ParsedSvg(SVG.getFromString(source), width, height, accessibleDescription)
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
        val spec = remember(source, seriesLabel) { parseChartSpec(source, seriesLabel) }
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
