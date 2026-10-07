package com.jarvys.agent.providers

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R

/** Minimal registry for service rows and their matching detail destinations. */
data class ProviderServiceDescriptor(
    val id: String,
    val titleResource: Int,
    val icon: ImageVector,
    val statusResource: (ProvidersUiState) -> Int,
    val detail: @Composable (ProvidersRepository) -> Unit,
)

object ProviderServiceRegistry {
    const val EXA_ID = "exa"

    val services: List<ProviderServiceDescriptor> = listOf(
        ProviderServiceDescriptor(
            id = EXA_ID,
            titleResource = R.string.web_search_exa_name,
            icon = LucideIcons.Search,
            statusResource = { state -> if (state.exaConnected) R.string.web_search_exa_connected
                else R.string.web_search_exa_fallback_status },
            detail = { repository -> ProvidersExaDetailScreen(repository) },
        ),
    )

    fun find(id: String): ProviderServiceDescriptor? = services.firstOrNull { it.id == id }
}
