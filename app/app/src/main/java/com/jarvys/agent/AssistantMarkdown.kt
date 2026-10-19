package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.times
import com.jarvys.agent.skills.SkillFileLink
import com.jarvys.agent.skills.SkillFileLinkParser
import com.jarvys.agent.skills.SkillRepository
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import java.text.BreakIterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

internal interface AssistantMarkdownRenderObserver {
    fun onLoadingComposed()
    fun onMarkdownContentRendered(content: String)
    fun onPlaceholderComposed(language: String)
    fun onStreamingCodeFallbackComposed(language: String)
    fun onBlockSourceParsed(language: String)
    fun onBlockMounted(language: String)
}

/** Successful parse trees retained for the lifetime of one ChatMessageList conversation. */
internal class AssistantMarkdownSuccessCache {
    private val statesByMarkdown = mutableMapOf<String, State.Success>()

    fun get(normalizedMarkdown: String): State.Success? = statesByMarkdown[normalizedMarkdown]

    fun put(normalizedMarkdown: String, state: State.Success) {
        statesByMarkdown[normalizedMarkdown] = state
    }
}

/** Assistant prose size; block formulas derive their size from it so both stay in proportion. */
internal val AssistantBodyFontSize = 14.sp
internal const val AssistantMathFontScale = 1.15f

internal val LocalAssistantMarkdownSuccessCache =compositionLocalOf<AssistantMarkdownSuccessCache?> { null }
internal val LocalAssistantMarkdownRenderObserver = compositionLocalOf<AssistantMarkdownRenderObserver?> { null }
internal interface AssistantMarkdownTableLayoutObserver {
    fun onCellTextLayout(text: String, result: TextLayoutResult)
    fun onTableLayout(minimumWidth: Dp, totalWidth: Dp, horizontallyScrollable: Boolean)
}
internal val LocalAssistantMarkdownTableLayoutObserver =
    compositionLocalOf<AssistantMarkdownTableLayoutObserver?> { null }
private val LocalAssistantMarkdownStreaming = compositionLocalOf { false }

@Composable
internal fun AssistantMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    userMessage: Boolean = false,
    streaming: Boolean = false,
) {
    val platformUriHandler = LocalUriHandler.current
    val openSkillFile by rememberUpdatedState(onOpenSkillFile)
    val normalizedMarkdown = remember(markdown, userMessage) {
        if (userMessage) markdown else WebSearchCitationMarkup.normalize(markdown)
    }
    val uriHandler = remember(platformUriHandler, userMessage) {
        messageMarkdownUriHandler(platformUriHandler, userMessage) { openSkillFile(it) }
    }
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = AssistantBodyFontSize, lineHeight = 20.sp)
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge,
        h2 = MaterialTheme.typography.titleMedium,
        h3 = MaterialTheme.typography.titleSmall,
        h4 = body.copy(fontSize = 15.sp, fontWeight = MaterialTheme.typography.titleSmall.fontWeight),
        h5 = body.copy(fontWeight = MaterialTheme.typography.titleSmall.fontWeight),
        h6 = body.copy(fontWeight = MaterialTheme.typography.titleSmall.fontWeight),
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        code = body.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        inlineCode = body.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
    )
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        if (userMessage) {
            // v28's userMessage mode keeps ordinary newlines and literal HTML without
            // changing the stored text. Do not fillMaxWidth: short user bubbles wrap text.
            val state = rememberMarkdownState(normalizedMarkdown)
            val annotator = remember {
                markdownAnnotator(markdownAnnotatorConfig(eolAsNewLine = true)) { content, node ->
                    if (node.type == MarkdownTokenTypes.HTML_TAG) {
                        append(content.substring(node.startOffset, node.endOffset))
                        true
                    } else false
                }
            }
            val components = remember {
                markdownComponents(
                    codeFence = { model ->
                        if (model.node.children.none { it.type == MarkdownTokenTypes.CODE_FENCE_END }) {
                            MarkdownText(model.content.substring(model.node.startOffset, model.node.endOffset),
                                style = model.typography.text)
                        } else {
                            MarkdownCodeFence(model.content, model.node, model.typography.code)
                        }
                    },
                    custom = { type, model ->
                        if (type == MarkdownElementTypes.HTML_BLOCK) {
                            MarkdownText(model.content.substring(model.node.startOffset, model.node.endOffset),
                                style = model.typography.text)
                        } else {
                            val currentComponents = LocalMarkdownComponents.current
                            model.node.children.forEach { child ->
                                MarkdownElement(child, currentComponents, model.content)
                            }
                        }
                    },
                )
            }
            val codeBackground = userMarkdownCodeBackground(textColor, MaterialTheme.colorScheme.primaryContainer)
            val fallbackStyle = MaterialTheme.typography.bodyLarge
            Markdown(
                markdownState = state,
                modifier = modifier,
                colors = markdownColor(text = textColor, codeBackground = codeBackground,
                    inlineCodeBackground = codeBackground),
                typography = typography,
                annotator = annotator,
                components = components,
                loading = { Text(markdown, modifier = it, color = textColor, style = fallbackStyle) },
                error = { Text(markdown, modifier = it, color = textColor, style = fallbackStyle) },
            )
        } else {
            // Renderer 0.37.0 parses content-keyed states on Dispatchers.Default; immediate=true would block composition.
            // Keep the last successful tree mounted while a replacement parse runs.
            val observer = LocalAssistantMarkdownRenderObserver.current
            // Read streaming inside the callback so promotion does not replace the MarkdownComponents instance.
            val components = remember {
                markdownComponents(
                    codeFence = { model ->
                        RenderAssistantCodeFence(model.content, model.node, model.typography.code,
                            LocalAssistantMarkdownStreaming.current)
                    },
                    table = { model -> AssistantMarkdownTable(model) },
                )
            }
            val successCache = if (streaming) null else LocalAssistantMarkdownSuccessCache.current
            val cachedSuccess = successCache?.get(normalizedMarkdown)
            CompositionLocalProvider(LocalAssistantMarkdownStreaming provides streaming) {
                val markdownState = rememberMarkdownState(normalizedMarkdown)
                val parsedState by markdownState.state.collectAsState()
                // This retained value is only read as a fallback while parsing; keeping it outside snapshot state avoids
                // a second recomposition for every successfully parsed delta. It also carries a cache hit forward if this
                // row's text changes while the row remains composed.
                val lastSuccessState = remember { arrayOfNulls<State.Success>(1) }
                val parsedSuccess = parsedState as? State.Success
                val parsedError = parsedState as? State.Error
                val preferredDisplayState = parsedSuccess
                    ?: parsedError
                    ?: cachedSuccess
                    ?: lastSuccessState[0]
                    ?: parsedState
                // Preserve the required parsed Success/Error > cache > last Success > parser-state choice. Once a
                // Success wins, reuse an already displayed equivalent tree so its Compose subtree keeps its identity.
                val displayState = (preferredDisplayState as? State.Success)?.let { preferredSuccess ->
                    val priorEquivalentSuccess = cachedSuccess?.takeIf { it.content == preferredSuccess.content }
                        ?: lastSuccessState[0]?.takeIf { it.content == preferredSuccess.content }
                    priorEquivalentSuccess ?: preferredSuccess
                } ?: preferredDisplayState
                SideEffect {
                    val displayedSuccess = displayState as? State.Success
                    displayedSuccess?.let { lastSuccessState[0] = it }
                    if (parsedSuccess != null && cachedSuccess == null) {
                        displayedSuccess?.let { successCache?.put(normalizedMarkdown, it) }
                    }
                    displayedSuccess?.let { observer?.onMarkdownContentRendered(it.content) }
                }
                Markdown(
                    state = displayState,
                    modifier = modifier,
                    colors = markdownColor(text = textColor),
                    typography = typography,
                    components = components,
                    loading = { loadingModifier ->
                        val loadingObserver = LocalAssistantMarkdownRenderObserver.current
                        SideEffect { loadingObserver?.onLoadingComposed() }
                        Box(loadingModifier)
                    },
                    error = { errorModifier -> Text(normalizedMarkdown, modifier = errorModifier,
                        color = textColor, style = body) },
                )
            }
        }
    }
}

internal data class AssistantMarkdownColumnMeasure(val minimum: Dp, val preferred: Dp)

internal data class AssistantMarkdownTableLayout(
    val columnWidths: List<Dp>,
    val minimumWidth: Dp,
    val totalWidth: Dp,
    val horizontallyScrollable: Boolean,
)

/**
 * The upstream table renderer limits every cell to one line. Assistant tables instead wrap normally,
 * allocate available width by the columns' measured content, and scroll only when an unbreakable run
 * cannot fit in the available width.
 */
@Composable
private fun AssistantMarkdownTable(model: MarkdownComponentModel) {
    val style = model.typography.table
    val dimens = LocalMarkdownDimens.current
    val colors = LocalMarkdownColors.current
    val annotator = annotatorSettings()
    val textMeasurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val streaming = LocalAssistantMarkdownStreaming.current
    val rows = remember(model.node) { model.node.children.filter { it.type == HEADER || it.type == ROW } }
    val columnsCount = remember(rows) { rows.maxOfOrNull { row -> row.children.count { it.type == CELL } } ?: 0 }
    if (columnsCount == 0) return

    // The parser rebuilds the table AST as streamed content changes. Its cell offsets refer to the
    // matching source string, so measure once while streaming and refresh when streaming ends.
    val measurementContentKey = if (streaming) null else model.content
    val columnMeasures = remember(model.node.startOffset, columnsCount, style, annotator, textMeasurer, density,
        dimens.tableCellPadding, streaming, measurementContentKey) {
        measureAssistantMarkdownColumns(
            rows = rows,
            columnsCount = columnsCount,
            content = model.content,
            style = style,
            annotator = annotator,
            textMeasurer = textMeasurer,
            density = density,
            cellPadding = dimens.tableCellPadding,
        )
    }
    BoxWithConstraints(
        modifier = Modifier
            .widthIn(max = dimens.tableMaxWidth)
            .fillMaxWidth()
            .background(colors.tableBackground, RoundedCornerShape(dimens.tableCornerSize)),
    ) {
        val layout = remember(columnMeasures, maxWidth) {
            allocateAssistantMarkdownTableWidth(columnMeasures, maxWidth)
        }
        val layoutObserver = LocalAssistantMarkdownTableLayoutObserver.current
        SideEffect { layoutObserver?.onTableLayout(layout.minimumWidth, layout.totalWidth, layout.horizontallyScrollable) }
        Column(
            modifier = if (layout.horizontallyScrollable) {
                Modifier.horizontalScroll(rememberScrollState()).requiredWidth(layout.totalWidth)
            } else {
                Modifier.width(layout.totalWidth)
            },
        ) {
            model.node.children.forEach { child ->
                when (child.type) {
                    HEADER, ROW -> AssistantMarkdownTableRow(
                        content = model.content,
                        row = child,
                        columnWidths = layout.columnWidths,
                        totalWidth = layout.totalWidth,
                        style = style,
                        header = child.type == HEADER,
                        annotator = annotator,
                    )
                    TABLE_SEPARATOR -> MarkdownDivider()
                }
            }
        }
    }
}

@Composable
private fun AssistantMarkdownTableRow(
    content: String,
    row: ASTNode,
    columnWidths: List<Dp>,
    totalWidth: Dp,
    style: TextStyle,
    header: Boolean,
    annotator: com.mikepenz.markdown.annotator.AnnotatorSettings,
) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownDimens.current.tableCellPadding
    val layoutObserver = LocalAssistantMarkdownTableLayoutObserver.current
    val cellStyle = (if (header) style.copy(fontWeight = FontWeight.Bold) else style)
        .copy(lineBreak = LineBreak.Simple)
    val cells = remember(row) { row.children.filter { it.type == CELL } }
    Row(modifier = Modifier.width(totalWidth), verticalAlignment = Alignment.CenterVertically) {
        columnWidths.forEachIndexed { index, columnWidth ->
            val cell = cells.getOrNull(index)
            if (cell == null) {
                Spacer(Modifier.width(columnWidth))
            } else {
                Column(modifier = Modifier.width(columnWidth).padding(padding)) {
                    if (cell.children.any { it.type == MarkdownElementTypes.IMAGE }) {
                        MarkdownElement(node = cell, components = components, content = content, includeSpacer = false)
                    } else {
                        val annotatedText = remember(cell, content, cellStyle, annotator) {
                            content.buildMarkdownAnnotatedString(
                                textNode = cell,
                                style = cellStyle,
                                annotatorSettings = annotator,
                            )
                        }
                        MarkdownBasicText(
                            text = annotatedText,
                            style = cellStyle,
                            overflow = TextOverflow.Clip,
                            softWrap = true,
                            onTextLayout = { result -> layoutObserver?.onCellTextLayout(annotatedText.text, result) },
                        )
                    }
                }
            }
        }
    }
}

private fun measureAssistantMarkdownColumns(
    rows: List<ASTNode>,
    columnsCount: Int,
    content: String,
    style: TextStyle,
    annotator: com.mikepenz.markdown.annotator.AnnotatorSettings,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: Density,
    cellPadding: Dp,
): List<AssistantMarkdownColumnMeasure> = with(density) {
    List(columnsCount) { columnIndex ->
        var widestBreakableRunPx = 0
        var widestCellPx = 0
        rows.forEach { row ->
            val cell = row.children.filter { it.type == CELL }.getOrNull(columnIndex) ?: return@forEach
            val cellStyle = (if (row.type == HEADER) style.copy(fontWeight = FontWeight.Bold) else style)
                .copy(lineBreak = LineBreak.Simple)
            val annotated = content.buildMarkdownAnnotatedString(
                textNode = cell,
                style = cellStyle,
                annotatorSettings = annotator,
            )
            widestCellPx = maxOf(widestCellPx, measureAssistantMarkdownTextPx(textMeasurer, annotated, cellStyle))
            assistantMarkdownBreakableRuns(annotated.text).forEach { run ->
                widestBreakableRunPx = maxOf(
                    widestBreakableRunPx,
                    measureAssistantMarkdownTextPx(textMeasurer, AnnotatedString(run), cellStyle),
                )
            }
        }
        val horizontalPadding = cellPadding * 2
        AssistantMarkdownColumnMeasure(
            minimum = widestBreakableRunPx.toDp() + horizontalPadding,
            preferred = widestCellPx.toDp() + horizontalPadding,
        )
    }
}

private fun assistantMarkdownBreakableRuns(text: String): List<String> {
    val breaks = BreakIterator.getLineInstance()
    breaks.setText(text)
    val urlLike = text.contains('/') && text.contains('.')
    val result = ArrayList<String>()
    var segmentStart = breaks.first()
    var segmentEnd = breaks.next()
    while (segmentEnd != BreakIterator.DONE) {
        val segment = text.substring(segmentStart, segmentEnd)
        var runStart = 0
        var index = 0
        while (index < segment.length) {
            val codePoint = segment.codePointAt(index)
            val codePointLength = Character.charCount(codePoint)
            if (isAssistantMarkdownCjk(codePoint)) {
                segment.substring(runStart, index).trim().takeIf { it.isNotEmpty() }?.let(result::add)
                result.add(segment.substring(index, index + codePointLength))
                runStart = index + codePointLength
            } else if (urlLike && codePoint in URL_BREAK_AFTER) {
                segment.substring(runStart, index + codePointLength).trim()
                    .takeIf { it.isNotEmpty() }?.let(result::add)
                runStart = index + codePointLength
            }
            index += codePointLength
        }
        segment.substring(runStart).trim().takeIf { it.isNotEmpty() }?.let(result::add)
        segmentStart = segmentEnd
        segmentEnd = breaks.next()
    }
    return result
}

private fun isAssistantMarkdownCjk(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL -> true
    else -> false
}

private val URL_BREAK_AFTER = setOf('/'.code, '?'.code, '&'.code, '='.code, '#'.code)

private fun measureAssistantMarkdownTextPx(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
): Int = textMeasurer.measure(
    text = text,
    style = style,
    overflow = TextOverflow.Clip,
    softWrap = false,
    maxLines = 1,
    constraints = Constraints(maxWidth = Constraints.Infinity),
).size.width

internal fun allocateAssistantMarkdownTableWidth(
    measures: List<AssistantMarkdownColumnMeasure>,
    availableWidth: Dp,
): AssistantMarkdownTableLayout {
    val minimumWidth = measures.sumOf { it.minimum.value.toDouble() }.dp
    if (!availableWidth.value.isFinite()) {
        val widths = measures.map { maxOf(it.minimum, it.preferred) }
        val totalWidth = widths.sumOf { it.value.toDouble() }.dp
        return AssistantMarkdownTableLayout(widths, minimumWidth, totalWidth, horizontallyScrollable = false)
    }
    if (minimumWidth > availableWidth) {
        return AssistantMarkdownTableLayout(
            columnWidths = measures.map { it.minimum },
            minimumWidth = minimumWidth,
            totalWidth = minimumWidth,
            horizontallyScrollable = true,
        )
    }

    val extraWidth = availableWidth - minimumWidth
    val weights = measures.map { (it.preferred - it.minimum).coerceAtLeast(0.dp).value.toDouble() }
    val weightTotal = weights.sum()
    val widths = measures.mapIndexed { index, measure ->
        val extra = if (weightTotal > 0.0) {
            extraWidth * (weights[index] / weightTotal).toFloat()
        } else {
            extraWidth / measures.size
        }
        measure.minimum + extra
    }
    return AssistantMarkdownTableLayout(widths, minimumWidth, availableWidth, horizontallyScrollable = false)
}

internal fun messageMarkdownUriHandler(
    platformUriHandler: UriHandler,
    userMessage: Boolean,
    onOpenSkillFile: (SkillFileLink) -> Unit,
): UriHandler = object : UriHandler {
    override fun openUri(uri: String) {
        when {
            uri.startsWith("jarvys:", ignoreCase = true) -> {
                if (!userMessage) SkillFileLinkParser.parse(uri)?.let(onOpenSkillFile)
            }
            uri.startsWith("webcite:", ignoreCase = true) -> {
                if (!userMessage) {
                    val id = uri.substringAfter(':').trim()
                    WebSearchCitationStore.resolve(id)?.let { citation ->
                        runCatching { platformUriHandler.openUri(citation.url) }
                    }
                }
            }
            userMessage -> { runCatching { platformUriHandler.openUri(uri) } }
            else -> platformUriHandler.openUri(uri)
        }
    }
}

internal fun userMarkdownCodeBackground(text: Color, bubble: Color): Color {
    val candidate = text.copy(alpha = 0.08f).compositeOver(bubble)
    return if (colorContrastRatio(text.toArgb().toLong(), candidate.toArgb().toLong()) >= 4.5) candidate else bubble
}

@Composable
internal fun SkillFileDialog(
    link: SkillFileLink,
    repository: SkillRepository,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember(link) { mutableStateOf<String?>(null) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    LaunchedEffect(link) {
        val result = withContext(Dispatchers.IO) {
            runCatching { repository.readWorkspaceSkillFile(link.skillId, link.relativePath) }
        }
        result.onSuccess { content = it }.onFailure { error = it.message ?: "Could not read the skill file" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${link.skillId}/${link.relativePath}", maxLines = 1) },
        text = {
            when {
                error != null -> ScrollableDialogContent {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                content == null -> ScrollableDialogContent {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> ScrollableDialogContent {
                    AssistantMarkdown(content.orEmpty(), Modifier.fillMaxWidth(), onOpenSkillFile)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.markdown_close)) } },
    )
}
