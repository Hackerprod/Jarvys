package com.jarvys.agent.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.CodexModelCatalog
import com.jarvys.agent.JarvysDropdownField
import com.jarvys.agent.JarvysDropdownOption
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.R
import com.jarvys.agent.providers.CustomEndpointModel
import com.jarvys.agent.providers.CustomEndpointModelField
import com.jarvys.agent.providers.OpenAiApiModelField
import com.jarvys.agent.providers.OpenAiModelFields
import com.jarvys.agent.providers.OpenRouterModelField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    providerName: String,
    openAiModel: String,
    openAiApiModel: String,
    openRouterModel: String,
    reasoningVariant: String,
    onDismiss: () -> Unit,
    onSelectionChange: (ProviderSettings.Provider, String, String) -> Unit,
    customName: String = "",
    customModel: String = "",
    customModels: List<CustomEndpointModel> = emptyList(),
) {
    val codexModels by CodexModelCatalog.models.collectAsState()
    val openAiApiModels by CodexModelCatalog.openAiApiModels.collectAsState()
    val openRouterModels by CodexModelCatalog.openRouterModels.collectAsState()
    val initialProvider = runCatching { ProviderSettings.Provider.valueOf(providerName) }
        .getOrDefault(ProviderSettings.Provider.OPENROUTER)
    var provider by remember { mutableStateOf(initialProvider) }
    var chosenOpenAiModel by remember {
        mutableStateOf(if (codexModels.any { it.id == openAiModel }) openAiModel
            else codexModels.firstOrNull { it.id == "gpt-5.4" }?.id ?: codexModels.first().id)
    }
    var chosenOpenAiApiModel by remember { mutableStateOf(openAiApiModel) }
    var chosenOpenRouterModel by remember { mutableStateOf(openRouterModel) }
    var chosenCustomModel by remember { mutableStateOf(customModel) }
    var chosenVariant by remember { mutableStateOf(reasoningVariant) }
    fun selectedModel() = when (provider) {
        ProviderSettings.Provider.OPENAI_CODEX -> chosenOpenAiModel
        ProviderSettings.Provider.OPENAI_API -> chosenOpenAiApiModel
        ProviderSettings.Provider.OPENROUTER -> chosenOpenRouterModel
        ProviderSettings.Provider.CUSTOM -> chosenCustomModel
    }
    fun saveSelection() = onSelectionChange(provider, selectedModel(), chosenVariant)

    LaunchedEffect(codexModels, provider) {
        if (provider == ProviderSettings.Provider.OPENAI_CODEX && codexModels.isNotEmpty()) {
            val selected = codexModels.firstOrNull { it.id == chosenOpenAiModel }
                ?: codexModels.firstOrNull { it.id == "gpt-5.4" }
                ?: codexModels.first()
            if (selected.id != chosenOpenAiModel) chosenOpenAiModel = selected.id
            if (selected.variants.isNotEmpty() && selected.variants.none { it.id == chosenVariant }) {
                chosenVariant = selected.defaultVariant.id
            }
            saveSelection()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)
            .imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JarvysDropdownField(
                label = stringResource(R.string.provider_provider),
                value = provider,
                options = listOf(
                    JarvysDropdownOption(ProviderSettings.Provider.OPENAI_CODEX,
                        stringResource(R.string.provider_openai_chatgpt_account_option)),
                    JarvysDropdownOption(ProviderSettings.Provider.OPENAI_API,
                        stringResource(R.string.provider_openai_method_api_key)),
                    JarvysDropdownOption(ProviderSettings.Provider.OPENROUTER,
                        stringResource(R.string.provider_openrouter_name)),
                    JarvysDropdownOption(ProviderSettings.Provider.CUSTOM,
                        customName.ifBlank { stringResource(R.string.provider_custom_endpoint_name) }),
                ),
                onSelect = { next ->
                    provider = next
                    if (next == ProviderSettings.Provider.OPENAI_CODEX) {
                        val model = codexModels.firstOrNull { it.id == chosenOpenAiModel }
                        if (model != null && model.variants.isNotEmpty() && model.variants.none { it.id == chosenVariant }) {
                            chosenVariant = model.defaultVariant.id
                        }
                    }
                    saveSelection()
                },
                filterLabel = stringResource(R.string.provider_search_models),
                noResultsLabel = stringResource(R.string.provider_no_models_found),
                modifier = Modifier.testTag("quick-provider-dropdown"),
            )
            when (provider) {
                ProviderSettings.Provider.OPENAI_CODEX -> OpenAiModelFields(
                    models = codexModels,
                    modelId = chosenOpenAiModel,
                    reasoningVariant = chosenVariant,
                    onModelSelected = { next ->
                        chosenOpenAiModel = next
                        val model = codexModels.firstOrNull { it.id == next }
                        if (model != null && model.variants.isNotEmpty() && model.variants.none { it.id == chosenVariant }) {
                            chosenVariant = model.defaultVariant.id
                        }
                        saveSelection()
                    },
                    onReasoningSelected = { next -> chosenVariant = next; saveSelection() },
                )
                ProviderSettings.Provider.OPENAI_API -> OpenAiApiModelField(chosenOpenAiApiModel, openAiApiModels, onModelSelected = { next ->
                    chosenOpenAiApiModel = next
                    saveSelection()
                })
                ProviderSettings.Provider.OPENROUTER -> OpenRouterModelField(chosenOpenRouterModel, openRouterModels, onModelSelected = { next ->
                    chosenOpenRouterModel = next
                    saveSelection()
                })
                ProviderSettings.Provider.CUSTOM -> CustomEndpointModelField(chosenCustomModel, customModels, onModelSelected = { next ->
                    chosenCustomModel = next
                    saveSelection()
                })
            }
        }
    }
}
