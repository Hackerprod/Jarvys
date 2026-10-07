package com.jarvys.agent.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvys.agent.JarvysDropdownField
import com.jarvys.agent.JarvysDropdownOption
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.ModelInfo
import com.jarvys.agent.OpenRouterModelInfo
import com.jarvys.agent.R

@Composable
internal fun OpenAiModelFields(
    models: List<ModelInfo>,
    modelId: String,
    reasoningVariant: String,
    onModelSelected: (String) -> Unit,
    onReasoningSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modelOptions = models.map { model ->
        val metadata = if (model.contextLimit > 0) stringResource(R.string.provider_model_option_metadata,
            model.id, stringResource(R.string.provider_model_context, model.contextLimit / 1000)) else model.id
        JarvysDropdownOption(model.id, model.name, metadata)
    }
    val selectedModel = models.firstOrNull { it.id == modelId }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JarvysDropdownField(
            label = stringResource(R.string.provider_model),
            value = selectedModel?.id ?: modelId,
            options = modelOptions,
            onSelect = onModelSelected,
            filterLabel = stringResource(R.string.provider_search_models),
            noResultsLabel = stringResource(R.string.provider_no_models_found),
            modifier = Modifier.testTag("provider-openai-model-dropdown"),
        )
        if (!selectedModel?.variants.isNullOrEmpty()) {
            val variants = selectedModel?.variants.orEmpty()
            val selected = variants.firstOrNull { it.id == reasoningVariant } ?: selectedModel?.defaultVariant
            JarvysDropdownField(
                label = stringResource(R.string.provider_reasoning),
                value = selected?.id.orEmpty(),
                options = variants.map { variant ->
                    JarvysDropdownOption(variant.id, variant.id,
                        if (variant.id == selectedModel?.defaultVariant?.id) stringResource(R.string.provider_default_variant) else null)
                },
                onSelect = onReasoningSelected,
                filterLabel = stringResource(R.string.provider_search_models),
                noResultsLabel = stringResource(R.string.provider_no_models_found),
                modifier = Modifier.testTag("provider-openai-reasoning-dropdown"),
            )
        }
    }
}

private data class OpenAiApiSelection(val modelId: String, val custom: Boolean = false)

@Composable
internal fun OpenAiApiModelField(
    modelId: String,
    models: List<ModelInfo>,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingOther by remember { mutableStateOf(false) }
    var otherModel by remember(modelId) { mutableStateOf(modelId) }
    val currentKnown = models.any { it.id == modelId }
    val options = buildList {
        models.forEach { model ->
            val metadata = if (model.contextLimit > 0) stringResource(R.string.provider_model_option_metadata,
                model.id, stringResource(R.string.provider_model_context, model.contextLimit / 1000)) else model.id
            add(JarvysDropdownOption(OpenAiApiSelection(model.id), model.name, metadata))
        }
        if (!currentKnown) add(JarvysDropdownOption(OpenAiApiSelection(modelId), modelId, modelId))
        add(JarvysDropdownOption(OpenAiApiSelection("", custom = true), stringResource(R.string.provider_model_other)))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JarvysDropdownField(
            label = stringResource(R.string.provider_model),
            value = if (editingOther) OpenAiApiSelection("", custom = true) else OpenAiApiSelection(modelId),
            options = options,
            onSelect = { selection ->
                if (selection.custom) {
                    editingOther = true
                    otherModel = modelId
                } else {
                    editingOther = false
                    onModelSelected(selection.modelId)
                }
            },
            filterLabel = stringResource(R.string.provider_search_models),
            noResultsLabel = stringResource(R.string.provider_no_models_found),
            modifier = Modifier.testTag("provider-openai-api-model-dropdown"),
        )
        if (editingOther) JarvysTextField(
            value = otherModel,
            onValueChange = { value ->
                otherModel = value
                if (value.isNotBlank()) onModelSelected(value)
            },
            label = { Text(stringResource(R.string.provider_openai_api_model_id)) },
            placeholder = { Text(stringResource(R.string.provider_openai_api_model_hint)) },
            modifier = Modifier.testTag("provider-openai-api-custom-model"),
            singleLine = true,
        )
    }
}

private data class CustomModelSelection(val modelId: String, val custom: Boolean = false)

@Composable
internal fun CustomEndpointModelField(
    modelId: String,
    models: List<CustomEndpointModel>,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingOther by remember { mutableStateOf(false) }
    var otherModel by remember(modelId) { mutableStateOf(modelId) }
    val isKnown = models.any { it.id == modelId }
    val options = buildList {
        models.forEach { model ->
            val metadata = if (model.contextLimit > 0) stringResource(R.string.provider_model_option_metadata,
                model.id, stringResource(R.string.provider_model_context, model.contextLimit / 1000)) else model.id
            add(JarvysDropdownOption(CustomModelSelection(model.id), model.name, metadata))
        }
        if (!isKnown) add(JarvysDropdownOption(CustomModelSelection(modelId), modelId, modelId))
        add(JarvysDropdownOption(CustomModelSelection("", custom = true), stringResource(R.string.provider_model_other)))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JarvysDropdownField(
            label = stringResource(R.string.provider_model),
            value = if (editingOther) CustomModelSelection("", custom = true) else CustomModelSelection(modelId),
            options = options,
            onSelect = { selection ->
                if (selection.custom) {
                    editingOther = true
                    otherModel = modelId
                } else {
                    editingOther = false
                    onModelSelected(selection.modelId)
                }
            },
            filterLabel = stringResource(R.string.provider_search_models),
            noResultsLabel = stringResource(R.string.provider_no_models_found),
            modifier = Modifier.testTag("custom-endpoint-model-dropdown"),
        )
        if (editingOther) JarvysTextField(
            value = otherModel,
            onValueChange = { value ->
                otherModel = value
                if (value.isNotBlank()) onModelSelected(value)
            },
            label = { Text(stringResource(R.string.custom_endpoint_model_id)) },
            placeholder = { Text(stringResource(R.string.custom_endpoint_model_hint)) },
            modifier = Modifier.testTag("custom-endpoint-custom-model"),
            singleLine = true,
        )
    }
}

private data class OpenRouterSelection(val modelId: String, val custom: Boolean = false)

@Composable
internal fun OpenRouterModelField(
    modelId: String,
    models: List<OpenRouterModelInfo>,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingOther by remember { mutableStateOf(false) }
    var otherModel by remember(modelId) { mutableStateOf(modelId) }
    val isKnown = models.any { it.id == modelId }
    val options = buildList {
        models.forEach { model ->
            val metadata = if (model.contextLimit > 0) stringResource(R.string.provider_model_option_metadata,
                model.id, stringResource(R.string.provider_model_context, model.contextLimit / 1000)) else model.id
            add(JarvysDropdownOption(OpenRouterSelection(model.id), model.name, metadata))
        }
        if (!isKnown) add(JarvysDropdownOption(OpenRouterSelection(modelId), modelId, modelId))
        add(JarvysDropdownOption(OpenRouterSelection("", custom = true), stringResource(R.string.provider_model_other)))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JarvysDropdownField(
            label = stringResource(R.string.provider_model),
            value = if (editingOther) OpenRouterSelection("", custom = true) else OpenRouterSelection(modelId),
            options = options,
            onSelect = { selection ->
                if (selection.custom) {
                    editingOther = true
                    otherModel = modelId
                } else {
                    editingOther = false
                    onModelSelected(selection.modelId)
                }
            },
            filterLabel = stringResource(R.string.provider_search_models),
            noResultsLabel = stringResource(R.string.provider_no_models_found),
            modifier = Modifier.testTag("provider-openrouter-model-dropdown"),
        )
        if (editingOther) JarvysTextField(
            value = otherModel,
            onValueChange = { value ->
                otherModel = value
                if (value.isNotBlank()) onModelSelected(value)
            },
            label = { Text(stringResource(R.string.provider_openrouter_model_id)) },
            placeholder = { Text(stringResource(R.string.provider_openrouter_model_hint)) },
            modifier = Modifier.testTag("provider-openrouter-custom-model"),
            singleLine = true,
        )
    }
}
