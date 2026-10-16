package com.jarvys.agent.ui.chat

import androidx.compose.runtime.Composable
import com.jarvys.agent.AgentRunUiEvent
import java.util.Locale

/** Native card implementations are registered by their internal presentation module. */
internal interface ArtifactModuleCardPresenter {
    val artifactType: String

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
    override val artifactType = "image/*"

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
            DeliveredArtifactEventCard(event, conversationId, onOpenPreview)
        }
    }
}

private object HtmlModuleCardPresenter : ArtifactModuleCardPresenter {
    override val artifactType = "text/html"

    @Composable
    override fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    ) = DeliveredArtifactEventCard(event, conversationId, onOpenPreview)
}

private object FileModuleCardPresenter : ArtifactModuleCardPresenter {
    override val artifactType = "*/*"

    @Composable
    override fun Card(
        event: AgentRunUiEvent,
        conversationId: String,
        onOpenPreview: (String) -> Unit,
        onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
        onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
    ) = DeliveredArtifactEventCard(event, conversationId, onOpenPreview)
}

internal object PresentationArtifactViewerRegistry {
    private val presenters = mutableMapOf<String, ArtifactModuleCardPresenter>().apply {
        put(ImageModuleCardPresenter.artifactType, ImageModuleCardPresenter)
        put(HtmlModuleCardPresenter.artifactType, HtmlModuleCardPresenter)
        put(FileModuleCardPresenter.artifactType, FileModuleCardPresenter)
    }

    fun register(presenter: ArtifactModuleCardPresenter) = synchronized(presenters) {
        check(presenters[presenter.artifactType] == null) { "Artifact viewer type is already registered" }
        presenters[presenter.artifactType] = presenter
    }

    fun viewerForArtifactType(type: String?): ArtifactModuleCardPresenter? {
        val canonical = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        synchronized(presenters) { presenters[canonical] }?.let { return it }
        val major = canonical.substringBefore('/', "")
        if (major.isNotBlank()) synchronized(presenters) { presenters["$major/*"] }?.let { return it }
        return synchronized(presenters) { presenters["*/*"] }
    }
}

@Composable
internal fun PresentationArtifactCard(
    event: AgentRunUiEvent,
    conversationId: String,
    onOpenPreview: (String) -> Unit,
    onSaveGeneratedImage: (AgentRunUiEvent) -> Unit,
    onShareGeneratedImage: (AgentRunUiEvent) -> Unit,
) {
    val presenter = PresentationArtifactViewerRegistry.viewerForArtifactType(event.presentationArtifact?.artifactType)
        ?: FileModuleCardPresenter
    presenter.Card(event, conversationId, onOpenPreview, onSaveGeneratedImage, onShareGeneratedImage)
}
