package com.jarvys.agent.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvys.agent.JarvysDropdownField
import com.jarvys.agent.JarvysDropdownOption
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysTag
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.R
import com.jarvys.agent.ScrollableDialogContent

@Composable
internal fun ProvidersCustomEndpointDetailScreen(repository: ProvidersRepository) {
    val state by repository.state.collectAsState()
    ProvidersCustomEndpointDetailContent(
        state = state,
        onNameChange = repository::setCustomNameInput,
        onUrlChange = repository::setCustomBaseUrlInput,
        onCompatibilityChange = repository::setCustomCompatibilityInput,
        onModelChange = repository::setCustomModelInput,
        onKeyChange = repository::setCustomEndpointKeyInput,
        onProbe = { repository.validateAndSaveCustomEndpoint() },
        onSaveWithoutCheck = repository::saveCustomEndpointWithoutChecking,
        onUseActive = repository::chooseCustomEndpointAsActive,
        onDelete = repository::deleteCustomEndpoint,
    )
}

@Composable
internal fun ProvidersCustomEndpointDetailContent(
    state: ProvidersUiState,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onCompatibilityChange: (CustomEndpointCompatibility) -> Unit,
    onModelChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onProbe: () -> Unit,
    onSaveWithoutCheck: () -> Unit,
    onUseActive: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val validating = state.customEndpointValidation == CustomEndpointUiState.VALIDATING
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JarvysSectionLabel(stringResource(R.string.custom_endpoint_heading))
        JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Text(stringResource(if (state.customEndpointConfigured) R.string.provider_custom_endpoint_saved_status
                else R.string.provider_custom_endpoint_missing_status),
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            JarvysTextField(
                value = state.customNameInput,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.custom_endpoint_name_label)) },
                placeholder = { Text(stringResource(R.string.provider_custom_endpoint_name)) },
                modifier = Modifier.testTag("custom-endpoint-name"),
                enabled = !validating,
            )
            JarvysTextField(
                value = state.customBaseUrlInput,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.custom_endpoint_url_label)) },
                placeholder = { Text(stringResource(R.string.custom_endpoint_url_hint)) },
                modifier = Modifier.testTag("custom-endpoint-url"),
                enabled = !validating,
            )
            JarvysDropdownField(
                label = stringResource(R.string.custom_endpoint_compatibility_label),
                value = state.customCompatibilityInput,
                options = CustomEndpointCompatibilityRegistry.descriptors.map { descriptor ->
                    JarvysDropdownOption(descriptor.compatibility,
                        stringResource(descriptor.labelResource))
                },
                onSelect = onCompatibilityChange,
                filterLabel = stringResource(R.string.custom_endpoint_compatibility_search),
                noResultsLabel = stringResource(R.string.custom_endpoint_compatibility_empty),
                enabled = !validating,
                modifier = Modifier.testTag("custom-endpoint-compatibility"),
            )
            if (state.customEndpointKeyConnected) {
                Text(stringResource(R.string.custom_endpoint_key_saved_note),
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            JarvysTextField(
                value = state.customEndpointKeyInput,
                onValueChange = onKeyChange,
                label = { Text(stringResource(R.string.custom_endpoint_api_key_label)) },
                placeholder = { Text(stringResource(R.string.custom_endpoint_api_key_hint)) },
                modifier = Modifier.testTag("custom-endpoint-api-key"),
                singleLine = true,
                enabled = !validating,
                visualTransformation = PasswordVisualTransformation(),
            )

            when (state.customEndpointValidation) {
                CustomEndpointUiState.VALIDATING -> Row(Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.custom_endpoint_validating), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CustomEndpointUiState.INVALID_URL -> CustomEndpointMessage(R.string.custom_endpoint_invalid_url, true)
                CustomEndpointUiState.INVALID_KEY -> CustomEndpointMessage(R.string.custom_endpoint_invalid_key, true)
                CustomEndpointUiState.CANNOT_CHECK -> CustomEndpointMessage(R.string.custom_endpoint_cannot_check, true)
                CustomEndpointUiState.NETWORK_ERROR -> CustomEndpointMessage(R.string.custom_endpoint_network_error, true)
                CustomEndpointUiState.VERIFIED -> CustomEndpointMessage(R.string.custom_endpoint_verified, false)
                CustomEndpointUiState.SAVED_UNVERIFIED -> CustomEndpointMessage(R.string.custom_endpoint_saved_unverified, false)
                CustomEndpointUiState.IDLE -> Unit
            }
            Button(onClick = onProbe, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                .testTag("custom-endpoint-save-test"),
                enabled = !validating && state.customBaseUrlInput.isNotBlank()) {
                Text(stringResource(if (state.customEndpointValidation == CustomEndpointUiState.CANNOT_CHECK
                    || state.customEndpointValidation == CustomEndpointUiState.NETWORK_ERROR
                    || state.customEndpointValidation == CustomEndpointUiState.INVALID_KEY)
                    R.string.custom_endpoint_retry else R.string.custom_endpoint_save_test))
            }
            if (state.customEndpointValidation == CustomEndpointUiState.CANNOT_CHECK) {
                OutlinedButton(onClick = onSaveWithoutCheck,
                    modifier = Modifier.fillMaxWidth().testTag("custom-endpoint-save-unchecked"), enabled = !validating) {
                    Text(stringResource(R.string.custom_endpoint_save_without_check))
                }
            }
        }

        JarvysSectionLabel(stringResource(R.string.provider_active_model))
        JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            CustomEndpointModelField(state.customModelInput, state.customModels, onModelChange,
                modifier = Modifier.testTag("custom-endpoint-model-field"))
            if (state.customEndpointConfigured) {
                CustomEndpointActiveAction(state.activeProvider == ProviderSettings.Provider.CUSTOM,
                    onClick = onUseActive)
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()
                    .testTag("custom-endpoint-remove")) {
                    Text(stringResource(R.string.custom_endpoint_remove), color = MaterialTheme.colorScheme.error)
                }
            } else {
                OutlinedButton(onClick = onUseActive, enabled = false, modifier = Modifier.fillMaxWidth()
                    .testTag("custom-endpoint-use-active-disabled")) {
                    Text(stringResource(R.string.custom_endpoint_use_active))
                }
                Text(stringResource(R.string.custom_endpoint_active_requires_url),
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.custom_endpoint_remove_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.custom_endpoint_remove_body)) } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) {
            Text(stringResource(R.string.custom_endpoint_remove), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) {
            Text(stringResource(R.string.provider_cancel))
        } },
    )
}

@Composable
private fun CustomEndpointMessage(resource: Int, error: Boolean) {
    Text(stringResource(resource), Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CustomEndpointActiveAction(active: Boolean, onClick: () -> Unit) {
    if (active) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            JarvysTag(stringResource(R.string.provider_active_tag))
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()
            .testTag("custom-endpoint-use-active")) {
            Text(stringResource(R.string.custom_endpoint_use_active))
        }
    }
}
