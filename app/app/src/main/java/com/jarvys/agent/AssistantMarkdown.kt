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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.skills.SkillFileLink
import com.jarvys.agent.skills.SkillFileLinkParser
import com.jarvys.agent.skills.SkillRepository
import com.jarvys.agent.WebSearchCitationStore
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenSkillFile: (SkillFileLink) -> Unit,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val platformUriHandler = LocalUriHandler.current
    val openSkillFile by rememberUpdatedState(onOpenSkillFile)
    val normalizedMarkdown = remember(markdown) { WebSearchCitationMarkup.normalize(markdown) }
    val uriHandler = remember(platformUriHandler, normalizedMarkdown) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("jarvys:", ignoreCase = true)) {
                    SkillFileLinkParser.parse(uri)?.let(openSkillFile)
                } else if (uri.startsWith("webcite:", ignoreCase = true)) {
                    val id = uri.substringAfter(':').trim()
                    WebSearchCitationStore.resolve(id)?.let { citation ->
                        runCatching { platformUriHandler.openUri(citation.url) }
                    }
                } else {
                    platformUriHandler.openUri(uri)
                }
            }
        }
    }
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = normalizedMarkdown,
            modifier = modifier,
            colors = markdownColor(text = textColor),
            typography = markdownTypography(
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
            ),
        )
    }
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
