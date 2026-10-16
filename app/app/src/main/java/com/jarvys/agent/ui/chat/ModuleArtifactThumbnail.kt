package com.jarvys.agent.ui.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.DeliveredArtifactStore
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatFileOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ModuleThumbnailState(val loaded: Boolean, val bitmap: Bitmap? = null)

/** Shows the file-backed thumbnail referenced by the restored artifact row. */
@Composable
internal fun ModuleArtifactThumbnailCard(
    event: AgentRunUiEvent,
    conversationId: String,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val artifact = event.presentationArtifact
    val attachment = event.deliveredArtifact
    if (artifact == null || attachment == null) {
        DeliveredArtifactEventCard(event, conversationId)
        return
    }
    val request = androidx.compose.runtime.remember(conversationId, attachment) {
        ChatFileRequest.attachment(conversationId, attachment, delivered = true)
    }
    val pixels = produceState(ModuleThumbnailState(loaded = false), context, conversationId, artifact.id, artifact.thumbnailRef) {
        value = ModuleThumbnailState(loaded = false)
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { loadModuleThumbnail(context, conversationId, artifact.id, artifact.thumbnailRef) }.getOrNull()
        }
        value = ModuleThumbnailState(loaded = true, bitmap = bitmap)
    }.value
    HtmlThumbnailCardSurface(
        request = request,
        image = pixels.bitmap,
        loading = !pixels.loaded,
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp),
        onOpen = onOpen,
        openLabel = stringResource(R.string.chat_artifact_thumbnail_open, artifact.title ?: attachment.name),
        loadingLabel = stringResource(R.string.chat_artifact_thumbnail_loading),
        unavailableLabel = stringResource(R.string.chat_artifact_thumbnail_unavailable),
        statusTagPrefix = "artifact-thumbnail",
        title = artifact.title ?: attachment.name,
    )
}

private suspend fun loadModuleThumbnail(context: Context, conversationId: String, artifactId: String, thumbnailRef: String?): Bitmap? {
    if (thumbnailRef.isNullOrBlank()) return null
    val artifact = ChatDatabaseBridge.getInstance(context)
        .findChatFileSync(conversationId, "delivered", artifactId) as? ChatFileOwnership.Delivered ?: return null
    if (artifact.value.thumbnail_ref != thumbnailRef) return null
    val reference = ChatDatabaseBridge.getInstance(context)
        .findArtifactThumbnailSync(conversationId, artifactId) ?: return null
    if (reference.id != thumbnailRef) return null
    val file = DeliveredArtifactStore(context).resolveThumbnail(conversationId, artifactId, reference)
    return decodeChatAttachmentPreview(file, 640, 360)
}
