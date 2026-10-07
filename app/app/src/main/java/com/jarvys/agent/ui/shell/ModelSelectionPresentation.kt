package com.jarvys.agent.ui.shell

import com.jarvys.agent.ModelInfo
import com.jarvys.agent.OpenRouterModelInfo
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.providers.CustomEndpointModel

internal data class QuickModelOption(val id: String, val label: String)

internal fun quickModelOptions(
    provider: ProviderSettings.Provider,
    codex: List<ModelInfo>,
    api: List<ModelInfo>,
    router: List<OpenRouterModelInfo>,
    custom: List<CustomEndpointModel>,
): List<QuickModelOption> = when (provider) {
    ProviderSettings.Provider.OPENAI_CODEX -> codex.map { QuickModelOption(it.id, it.name) }
    ProviderSettings.Provider.OPENAI_API -> api.map { QuickModelOption(it.id, it.name) }
    ProviderSettings.Provider.OPENROUTER -> router.map { QuickModelOption(it.id, it.name) }
    ProviderSettings.Provider.CUSTOM -> custom.map { QuickModelOption(it.id, it.name) }
}.distinctBy { it.id }.map { it.copy(label = it.label.ifBlank { it.id }) }

internal fun quickModelDisplayName(models: List<QuickModelOption>, selectedId: String): String =
    models.firstOrNull { it.id == selectedId }?.label?.ifBlank { selectedId } ?: selectedId
