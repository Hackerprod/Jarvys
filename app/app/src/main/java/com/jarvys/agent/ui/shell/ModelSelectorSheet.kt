package com.jarvys.agent.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.jarvys.agent.CodexModelCatalog
import com.jarvys.agent.JarvysSheetDragHandle
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.ModelVariant
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.R
import com.jarvys.agent.jarvysSheetOptionRow
import com.jarvys.agent.providers.CustomEndpointModel

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
    val provider = remember(providerName) {
        runCatching { ProviderSettings.Provider.valueOf(providerName) }.getOrDefault(ProviderSettings.Provider.OPENROUTER)
    }
    var chosenOpenAiModel by remember {
        mutableStateOf(if (codexModels.any { it.id == openAiModel }) openAiModel
            else codexModels.firstOrNull { it.id == "gpt-5.4" }?.id ?: codexModels.firstOrNull()?.id.orEmpty())
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
    val providerLabel = when (provider) {
        ProviderSettings.Provider.OPENAI_CODEX -> stringResource(R.string.provider_openai_chatgpt_account_option)
        ProviderSettings.Provider.OPENAI_API -> stringResource(R.string.provider_openai_method_api_key)
        ProviderSettings.Provider.OPENROUTER -> stringResource(R.string.provider_openrouter_name)
        ProviderSettings.Provider.CUSTOM -> customName.ifBlank { stringResource(R.string.provider_custom_endpoint_name) }
    }
    val models = remember(provider, codexModels, openAiApiModels, openRouterModels, customModels) {
        quickModelOptions(provider, codexModels, openAiApiModels, openRouterModels, customModels)
    }
    val selectedCodexModel = codexModels.firstOrNull { it.id == chosenOpenAiModel }
    val variants = if (provider == ProviderSettings.Provider.OPENAI_CODEX) selectedCodexModel?.variants.orEmpty() else emptyList()
    val manualLabel = when (provider) {
        ProviderSettings.Provider.OPENAI_CODEX -> null
        ProviderSettings.Provider.OPENAI_API -> stringResource(R.string.provider_openai_api_model_id)
        ProviderSettings.Provider.OPENROUTER -> stringResource(R.string.provider_openrouter_model_id)
        ProviderSettings.Provider.CUSTOM -> stringResource(R.string.custom_endpoint_model_id)
    }
    BackHandler(onBack = onDismiss)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ModelSelectorSheetContent(
            providerLabel, models, selectedModel(), variants, chosenVariant,
            onModelSelected = { next ->
                when (provider) {
                    ProviderSettings.Provider.OPENAI_CODEX -> {
                        chosenOpenAiModel = next
                        val model = codexModels.firstOrNull { it.id == next }
                        if (model != null && model.variants.isNotEmpty() && model.variants.none { it.id == chosenVariant }) {
                            chosenVariant = model.defaultVariant.id
                        }
                    }
                    ProviderSettings.Provider.OPENAI_API -> chosenOpenAiApiModel = next
                    ProviderSettings.Provider.OPENROUTER -> chosenOpenRouterModel = next
                    ProviderSettings.Provider.CUSTOM -> chosenCustomModel = next
                }
                saveSelection()
            },
            onVariantSelected = { next -> chosenVariant = next; saveSelection() },
            manualModelLabel = manualLabel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModelSelectorSheetContent(
    providerLabel: String,
    models: List<QuickModelOption>,
    selectedModelId: String,
    variants: List<ModelVariant>,
    selectedVariantId: String,
    onModelSelected: (String) -> Unit,
    onVariantSelected: (String) -> Unit,
    manualModelLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(providerLabel) { mutableStateOf(false) }
    var query by remember(expanded) { mutableStateOf("") }
    var editingOther by remember(providerLabel) { mutableStateOf(false) }
    var manualModel by remember(selectedModelId) { mutableStateOf(selectedModelId) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val dropdownMaxHeight = (configuration.screenHeightDp.dp * 0.6f).coerceAtMost(420.dp)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val manualFocus = remember { FocusRequester() }
    val allModels = remember(models, selectedModelId) {
        val unique = models.distinctBy { it.id }
        if (selectedModelId.isBlank() || unique.any { it.id == selectedModelId }) unique
        else unique + QuickModelOption(selectedModelId, selectedModelId)
    }
    val selectedModelLabel = quickModelDisplayName(allModels, selectedModelId)
    val filteredModels = remember(allModels, query) {
        val filter = query.trim()
        allModels.filter { it.id.contains(filter, ignoreCase = true) || it.label.contains(filter, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(expanded, query, allModels) {
        if (expanded) listState.scrollToItem(if (query.isBlank()) filteredModels.indexOfFirst { it.id == selectedModelId }.coerceAtLeast(0) else 0)
    }
    LaunchedEffect(editingOther) { if (editingOther) manualFocus.requestFocus() }
    fun closeDropdown() {
        expanded = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    fun cancelManual() {
        editingOther = false
        manualModel = selectedModelId
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    fun selectModel(id: String) {
        editingOther = false
        closeDropdown()
        onModelSelected(id)
    }
    fun saveManual() {
        val id = manualModel.trim()
        if (id.isNotBlank()) selectModel(id)
    }
    fun collapse() { if (expanded) closeDropdown() else cancelManual() }
    BackHandler(enabled = expanded || editingOther) { collapse() }
    val modelLabel = stringResource(R.string.provider_model)
    val collapsedLabel = stringResource(R.string.quick_model_collapsed)
    val expandedLabel = stringResource(R.string.quick_model_expanded)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().heightIn(max = configuration.screenHeightDp.dp)
            .imePadding().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 24.dp)
            .testTag("quick-model-sheet-content")
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && (expanded || editingOther)) {
                    if (event.type == KeyEventType.KeyUp) collapse()
                    true
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally)) { JarvysSheetDragHandle() }
        Text(modelLabel, color = colors.onSurface, style = MaterialTheme.typography.titleLarge)
        Text(providerLabel, Modifier.testTag("quick-model-provider-label"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { next ->
                if (next) {
                    editingOther = false
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    expanded = true
                } else closeDropdown()
            },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().menuAnchor()
                    .onPreviewKeyEvent { event ->
                        if (event.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionDown)) {
                            if (event.type == KeyEventType.KeyUp) { editingOther = false; expanded = true }
                            true
                        } else false
                    }
                    .focusable().onSizeChanged { anchorWidth = it.width }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$modelLabel: $selectedModelLabel"
                        stateDescription = if (expanded) expandedLabel else collapsedLabel
                    }.testTag("quick-model-combobox"),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainerHigh,
                border = BorderStroke(1.dp, if (expanded) colors.primary else colors.outlineVariant),
            ) {
                Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selectedModelLabel.ifBlank { stringResource(R.string.provider_model_other) },
                        Modifier.weight(1f).testTag("quick-model-current"), color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                }
            }
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = ::closeDropdown,
                modifier = Modifier.testTag("quick-model-dropdown"),
                matchTextFieldWidth = false,
            ) {
                Column(
                    Modifier.exposedDropdownSize()
                        .then(ModelDropdownViewport(anchorWidth, with(density) { dropdownMaxHeight.roundToPx() }, with(density) { 16.dp.roundToPx() }))
                        .width(with(density) { anchorWidth.toDp() }).heightIn(max = dropdownMaxHeight)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Escape) {
                                if (event.type == KeyEventType.KeyUp) closeDropdown()
                                true
                            } else false
                        },
                ) {
                    JarvysTextField(query, { query = it }, { Text(stringResource(R.string.provider_search_models)) },
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("quick-model-filter"))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("quick-model-list"), state = listState) {
                        if (filteredModels.isEmpty()) item {
                            Text(stringResource(R.string.provider_no_models_found),
                                Modifier.padding(horizontal = 16.dp, vertical = 18.dp).testTag("quick-model-empty"), color = colors.onSurfaceVariant)
                        }
                        items(filteredModels, key = { it.id }) { model ->
                            val isSelected = model.id == selectedModelId
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                    .jarvysSheetOptionRow(role = Role.RadioButton) { selectModel(model.id) }
                                    .background(if (isSelected) colors.secondaryContainer else colors.surfaceContainer, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp)
                                    .semantics(mergeDescendants = true) { selected = isSelected }
                                    .testTag("quick-model-row-${model.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(model.label.ifBlank { model.id }, Modifier.weight(1f).padding(vertical = 10.dp), color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
                                if (isSelected) Icon(LucideIcons.CircleCheck, null,
                                    Modifier.size(20.dp).testTag("quick-model-selected-indicator"), tint = colors.primary)
                            }
                        }
                    }
                    if (manualModelLabel != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.provider_model_other)) },
                        onClick = { closeDropdown(); manualModel = selectedModelId; editingOther = true },
                        modifier = Modifier.testTag("quick-model-other"),
                    )
                }
            }
        }
        if (editingOther && manualModelLabel != null) {
            JarvysTextField(
                manualModel, { manualModel = it }, { Text(manualModelLabel) },
                Modifier.focusRequester(manualFocus).testTag("quick-model-manual-id"),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveManual() }),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ::cancelManual, modifier = Modifier.heightIn(min = 48.dp).testTag("quick-model-manual-cancel")) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = ::saveManual, enabled = manualModel.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("quick-model-manual-save")) { Text(stringResource(R.string.settings_save)) }
            }
        }
        if (variants.isNotEmpty()) ModelEffortSlider(variants, selectedVariantId, onVariantSelected)
    }
}

/** Supplies finite intrinsics for the lazy model list inside the popup menu. */
private data class ModelDropdownViewport(val width: Int, val maxHeight: Int, val menuPadding: Int) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val availableHeight = (constraints.maxHeight - menuPadding).coerceAtLeast(constraints.minHeight)
        val child = measurable.measure(constraints.copy(maxHeight = availableHeight))
        return layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = width
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = width
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = 0
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = maxHeight
}
