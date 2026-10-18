package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.skills.SkillFileLink
import com.jarvys.agent.skills.SkillFileLinkParser
import com.jarvys.agent.skills.SkillRepository
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes

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

internal val LocalAssistantMarkdownSuccessCache = compositionLocalOf<AssistantMarkdownSuccessCache?> { null }
internal val LocalAssistantMarkdownRenderObserver = compositionLocalOf<AssistantMarkdownRenderObserver?> { null }
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
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
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
                markdownComponents(codeFence = { model ->
                    RenderAssistantCodeFence(model.content, model.node, model.typography.code,
                        LocalAssistantMarkdownStreaming.current)
                })
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
