package com.jarvys.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.R
import com.jarvys.agent.modules.ModuleArtifactViewerLaunch
import com.jarvys.agent.modules.ModuleRegistry

/** Native card implementations are keyed by the viewer declared in a module manifest. */
internal interface ArtifactModuleCardPresenter {
    val viewer: String

    @Composable
    fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    )
}

private object ImageModuleCardPresenter : ArtifactModuleCardPresenter {
    override val viewer = "native/image"

    @Composable
    override fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    ) {
        if (event.presentationArtifact?.isGeneratedImage == true) {
            GeneratedImageEventCard(event, conversationId, onSaveGeneratedImage, onShareGeneratedImage)
        } else {
            DeliveredArtifactEventCard(event, conversationId, renderAsImage = true, onOpenPreview = onOpenPreview)
        }
    }
}

private object HtmlModuleCardPresenter : ArtifactModuleCardPresenter {
    override val viewer = "native/html"

    @Composable
    override fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    ) = DeliveredArtifactEventCard(event, conversationId, renderAsImage = false,
        renderHtmlPreview = true, onOpenPreview = onOpenPreview)
}

private object FileModuleCardPresenter : ArtifactModuleCardPresenter {
    override val viewer = "native/file"

    @Composable
    override fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    ) = DeliveredArtifactEventCard(event, conversationId, onOpenPreview)
}

private object FallbackArtifactCardPresenter : ArtifactModuleCardPresenter {
    override val viewer = ""

    @Composable
    override fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    ) = DeliveredArtifactEventCard(event, conversationId, onOpenPreview)
}

private object ModuleWebViewerCardPresenter : ArtifactModuleCardPresenter {
    override val viewer = ""

    @Composable
    override fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    ) {
        val launchToken = event.presentationArtifact?.id?.let(ModuleArtifactViewerLaunch::token)
        val hasThumbnail = !event.presentationArtifact?.thumbnailRef.isNullOrBlank()
        if (launchToken != null && hasThumbnail) {
            ModuleArtifactThumbnailCard(event, conversationId) { onOpenPreview(launchToken) }
        } else {
            Column(Modifier.fillMaxWidth()) {
                DeliveredArtifactEventCard(event, conversationId)
                if (launchToken != null) {
                    androidx.compose.material3.TextButton(
                        onClick = { onOpenPreview(launchToken) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.chat_view_in_jarvys))
                    }
                }
            }
        }
    }
}

internal object PresentationArtifactViewerRegistry {
    private val presenters = listOf(
        ImageModuleCardPresenter,
        HtmlModuleCardPresenter,
        FileModuleCardPresenter,
    ).associateBy { it.viewer }

    fun viewerForArtifactType(registry: ModuleRegistry, type: String?): ArtifactModuleCardPresenter? =
        type?.let(registry::artifactViewerForType)?.let(presenters::get)
}

@Composable
internal fun PresentationArtifactCard(
    event: AgentRunUiEvent,
    conversationId: String,
    onOpenPreview: (String) -> Unit,
    onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
    onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
) {
    val context = LocalContext.current
    val modules = remember(context) { ModuleRegistry.get(context) }
    val viewer = event.presentationArtifact?.artifactType?.let(modules::artifactViewerForType)
    val nativePresenter = PresentationArtifactViewerRegistry.viewerForArtifactType(
        modules,
        event.presentationArtifact?.artifactType,
    )
    val presenter = when {
        viewer == null -> FallbackArtifactCardPresenter
        nativePresenter != null -> nativePresenter
        else -> ModuleWebViewerCardPresenter
    }
    presenter.Card(event, conversationId, onOpenPreview, onSaveGeneratedImage, onShareGeneratedImage)
}
