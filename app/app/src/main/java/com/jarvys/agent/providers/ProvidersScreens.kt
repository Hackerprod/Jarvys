package com.jarvys.agent.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.jarvys.agent.CodexAuthDiagnostic
import com.jarvys.agent.JarvysChoiceOption
import com.jarvys.agent.JarvysChoiceSheet
import com.jarvys.agent.CodexModelCatalog
import com.jarvys.agent.JarvysGroup
import com.jarvys.agent.JarvysListRow
import com.jarvys.agent.JarvysTag
import com.jarvys.agent.JarvysTextField
import com.jarvys.agent.JarvysSectionLabel
import com.jarvys.agent.JarvysDropdownField
import com.jarvys.agent.JarvysDropdownOption
import com.jarvys.agent.ScrollableDialogContent
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.R

@Composable
internal fun ProvidersScreen(
    state: ProvidersUiState,
    onOpenOpenAi: () -> Unit,
    onOpenOpenRouter: () -> Unit,
    onOpenService: (String) -> Unit,
    onOpenCustomEndpoint: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        JarvysSectionLabel(stringResource(R.string.settings_providers))
        JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
            ProviderListRow(
                icon = LucideIcons.Sparkles,
                title = stringResource(R.string.provider_openai_name),
                detail = when (state.openAiAuthMethod) {
                    ProviderSettings.OpenAiAuthMethod.API_KEY -> context.getString(
                        if (state.openAiApiKeyConnected) R.string.provider_openai_api_key_saved
                        else R.string.provider_openai_api_key_missing)
                    else -> if (state.codexConnected) state.codexAccountId?.takeIf(String::isNotBlank)
                        ?.let { context.getString(R.string.provider_account_id, it) }
                        ?: context.getString(R.string.provider_chatgpt_account_connected)
                    else context.getString(R.string.provider_signin_required)
                },
                active = state.activeProvider == ProviderSettings.Provider.OPENAI_CODEX
                    || state.activeProvider == ProviderSettings.Provider.OPENAI_API,
                onClick = onOpenOpenAi,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProviderListRow(
                icon = LucideIcons.Network,
                title = stringResource(R.string.provider_openrouter_name),
                detail = stringResource(if (state.openRouterConnected) R.string.provider_openrouter_key_encrypted_status
                    else R.string.provider_openrouter_key_missing),
                active = state.activeProvider == ProviderSettings.Provider.OPENROUTER,
                onClick = onOpenOpenRouter,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProviderListRow(
                icon = LucideIcons.Globe,
                title = state.customName.ifBlank { context.getString(R.string.provider_custom_endpoint_name) },
                detail = context.getString(if (state.customEndpointConfigured)
                    R.string.provider_custom_endpoint_saved_status else R.string.provider_custom_endpoint_missing_status),
                active = state.activeProvider == ProviderSettings.Provider.CUSTOM,
                onClick = onOpenCustomEndpoint,
            )
        }

        if (ProviderServiceRegistry.services.isNotEmpty()) {
            JarvysSectionLabel(stringResource(R.string.providers_services_heading), Modifier.padding(top = 6.dp))
            JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                ProviderServiceRegistry.services.forEachIndexed { index, service ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    ProviderListRow(
                        icon = service.icon,
                        title = stringResource(service.titleResource),
                        detail = stringResource(service.statusResource(state)),
                        active = false,
                        onClick = { onOpenService(service.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderListRow(icon: ImageVector, title: String, detail: String, active: Boolean, onClick: () -> Unit) {
    JarvysListRow(
        title = title,
        subtitle = detail,
        icon = icon,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (active) JarvysTag(stringResource(R.string.provider_active_tag))
                Icon(LucideIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
internal fun ProvidersOpenAiDetailScreen(repository: ProvidersRepository) {
    val state by repository.state.collectAsState()
    val codexModels by CodexModelCatalog.models.collectAsState()
    val apiModels by CodexModelCatalog.openAiApiModels.collectAsState()
    LaunchedEffect(codexModels, state.activeProvider, state.openAiModel, state.openAiReasoningVariant) {
        repository.reconcileOpenAiModels(codexModels)
    }
    val context = LocalContext.current
    ProvidersOpenAiDetailContent(
        state = state,
        models = codexModels,
        apiModels = apiModels,
        onSignIn = { context.findActivity()?.let(repository::beginCodexSignIn) },
        onCancelSignIn = repository::cancelCodexSignIn,
        onAuthMethodChange = repository::selectOpenAiAuthMethod,
        onBeginDeviceCode = repository::beginDeviceCodeSignIn,
        onCancelDeviceCode = repository::cancelDeviceCodeSignIn,
        onCopyDeviceCode = { code ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.provider_device_code_title), code))
            Toast.makeText(context, R.string.provider_device_code_copied, Toast.LENGTH_SHORT).show()
        },
        onOpenDevicePage = { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(context, R.string.provider_device_code_open_failed, Toast.LENGTH_SHORT).show() }
        },
        onApiKeyChange = repository::setOpenAiApiKeyInput,
        onValidateApiKey = { repository.validateAndSaveOpenAiApiKey() },
        onReplaceApiKey = repository::beginReplacingOpenAiApiKey,
        onCancelApiKeyEdit = repository::cancelOpenAiApiKeyEdit,
        onDeleteApiKey = repository::deleteOpenAiApiKey,
        onDisconnect = repository::disconnectCodex,
        onSelectModel = { repository.selectOpenAiModel(it, codexModels) },
        onSelectApiModel = repository::selectOpenAiApiModel,
        onSelectReasoning = repository::selectOpenAiReasoningVariant,
        onUseActive = { repository.useSelectedOpenAiMethodAsActive() },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun ProvidersOpenAiDetailContent(
    state: ProvidersUiState,
    models: List<com.jarvys.agent.ModelInfo>,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectReasoning: (String) -> Unit,
    onUseActive: () -> Unit,
    apiModels: List<com.jarvys.agent.ModelInfo> = emptyList(),
    onAuthMethodChange: (ProviderSettings.OpenAiAuthMethod) -> Unit = {},
    onBeginDeviceCode: () -> Unit = {},
    onCancelDeviceCode: () -> Unit = {},
    onCopyDeviceCode: (String) -> Unit = {},
    onOpenDevicePage: (String) -> Unit = {},
    onSelectApiModel: (String) -> Unit = {},
    onApiKeyChange: (String) -> Unit = {},
    onValidateApiKey: () -> Unit = {},
    onReplaceApiKey: () -> Unit = {},
    onCancelApiKeyEdit: () -> Unit = {},
    onDeleteApiKey: () -> Unit = {},
) {
    val providerContext = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmRemoveApiKey by remember { mutableStateOf(false) }
    val activeProvider = when (state.openAiAuthMethod) {
        ProviderSettings.OpenAiAuthMethod.BROWSER,
        ProviderSettings.OpenAiAuthMethod.DEVICE_CODE -> ProviderSettings.Provider.OPENAI_CODEX
        ProviderSettings.OpenAiAuthMethod.API_KEY -> ProviderSettings.Provider.OPENAI_API
    }
    val selectedMethodConnected = when (state.openAiAuthMethod) {
        ProviderSettings.OpenAiAuthMethod.BROWSER,
        ProviderSettings.OpenAiAuthMethod.DEVICE_CODE -> state.codexConnected
        ProviderSettings.OpenAiAuthMethod.API_KEY -> state.openAiApiKeyConnected
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        JarvysSectionLabel(stringResource(R.string.provider_openai_api_method))
        OpenAiAuthMethodControl(state.openAiAuthMethod, onAuthMethodChange)

        JarvysSectionLabel(stringResource(R.string.provider_connection_status))
        JarvysGroup {
            when (state.openAiAuthMethod) {
                ProviderSettings.OpenAiAuthMethod.BROWSER -> {
                    ProviderConnectionRow(
                        name = stringResource(R.string.provider_chatgpt_browser_name),
                        connected = state.codexConnected,
                        detail = codexConnectionDetail(state, providerContext),
                    )
                    if (state.codexConnected) {
                        Text(stringResource(R.string.provider_chatgpt_connected_summary),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                        TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.provider_disconnect_chatgpt), color = MaterialTheme.colorScheme.error)
                        }
                    } else if (state.codexOAuthInProgress) {
                        BrowserOAuthProgress(state.codexOAuthExchanging, onCancelSignIn)
                    } else {
                        Text(stringResource(R.string.provider_connect_chatgpt_browser_summary),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                        state.codexOAuthError?.let { failure ->
                            Text(failure, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("provider-browser-signin-error"),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            state.codexOAuthDiagnostic?.let { ProviderAuthDiagnostic(it, "browser") }
                        }
                        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).testTag("provider-browser-signin")) {
                            Text(stringResource(R.string.provider_signin_chatgpt))
                        }
                    }
                }
                ProviderSettings.OpenAiAuthMethod.DEVICE_CODE -> {
                    ProviderConnectionRow(
                        name = stringResource(R.string.provider_chatgpt_device_name),
                        connected = state.codexConnected,
                        detail = codexConnectionDetail(state, providerContext),
                    )
                    if (state.codexConnected) {
                        Text(stringResource(R.string.provider_chatgpt_connected_summary),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                        TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.provider_disconnect_chatgpt), color = MaterialTheme.colorScheme.error)
                        }
                    } else if (state.codexDeviceCode.isRunning) {
                        DeviceCodeProgressCard(state.codexDeviceCode, onCopyDeviceCode, onOpenDevicePage, onCancelDeviceCode)
                    } else {
                        Text(stringResource(R.string.provider_connect_chatgpt_device_summary),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                        when (val deviceState = state.codexDeviceCode) {
                            is CodexDeviceCodeState.Failed -> {
                                Text(stringResource(deviceState.messageResource),
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                deviceState.diagnostic?.let { ProviderAuthDiagnostic(it, "device_code") }
                                Button(onClick = onBeginDeviceCode,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                                        .testTag("provider-device-code-retry")) {
                                    Text(stringResource(R.string.provider_device_code_retry))
                                }
                            }
                            else -> Button(onClick = onBeginDeviceCode,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                                    .testTag("provider-start-device-code")) {
                                Text(stringResource(R.string.provider_device_code_start))
                            }
                        }
                    }
                }
                ProviderSettings.OpenAiAuthMethod.API_KEY -> {
                    ProviderConnectionRow(
                        name = stringResource(R.string.provider_openai_api_name),
                        connected = state.openAiApiKeyConnected,
                        detail = stringResource(if (state.openAiApiKeyConnected) R.string.provider_openai_api_key_saved
                            else R.string.provider_openai_api_key_missing),
                    )
                    if (state.openAiApiKeyConnected && !state.openAiApiKeyEditing) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onReplaceApiKey) { Text(stringResource(R.string.provider_openai_api_key_replace)) }
                            TextButton(onClick = { confirmRemoveApiKey = true }) {
                                Text(stringResource(R.string.provider_openai_api_key_remove), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Text(stringResource(R.string.provider_openai_api_key_disclosure),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        JarvysTextField(
                            value = state.openAiApiKeyInput,
                            onValueChange = onApiKeyChange,
                            label = { Text(stringResource(R.string.provider_openai_api_key_label)) },
                            placeholder = { Text(stringResource(R.string.provider_openai_api_key_hint)) },
                            modifier = Modifier.padding(horizontal = 14.dp).testTag("provider-openai-api-key"),
                            singleLine = true,
                            enabled = state.openAiApiKeyValidation != OpenAiApiKeyUiState.VALIDATING,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        when (state.openAiApiKeyValidation) {
                            OpenAiApiKeyUiState.VALIDATING -> Row(Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.provider_openai_api_key_validating),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OpenAiApiKeyUiState.INVALID_KEY -> Text(stringResource(R.string.provider_openai_api_key_invalid),
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            OpenAiApiKeyUiState.UNAVAILABLE -> Text(stringResource(R.string.provider_openai_api_key_validation_failed),
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            else -> Unit
                        }
                        if (state.openAiApiKeyEditing && state.openAiApiKeyConnected) {
                            OutlinedButton(onClick = onCancelApiKeyEdit,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                                enabled = state.openAiApiKeyValidation != OpenAiApiKeyUiState.VALIDATING) {
                                Text(stringResource(R.string.provider_cancel))
                            }
                        }
                        Button(onClick = onValidateApiKey,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("provider-openai-api-key-save"),
                            enabled = state.openAiApiKeyInput.isNotBlank()
                                && state.openAiApiKeyValidation != OpenAiApiKeyUiState.VALIDATING) {
                            Text(stringResource(if (state.openAiApiKeyValidation == OpenAiApiKeyUiState.INVALID_KEY
                                || state.openAiApiKeyValidation == OpenAiApiKeyUiState.UNAVAILABLE)
                                R.string.provider_openai_api_key_retry else R.string.provider_openai_api_key_save_validate))
                        }
                    }
                }
            }
        }

        JarvysSectionLabel(stringResource(R.string.provider_active_model))
        JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            if (state.openAiAuthMethod == ProviderSettings.OpenAiAuthMethod.API_KEY) {
                OpenAiApiModelField(state.openAiApiModel, apiModels, onSelectApiModel)
            } else {
                OpenAiModelFields(models, state.openAiModel, state.openAiReasoningVariant,
                    onSelectModel, onSelectReasoning)
            }
            ActiveProviderAction(
                active = state.activeProvider == activeProvider && selectedMethodConnected,
                label = stringResource(R.string.provider_use_openai_active),
                onClick = onUseActive,
                enabled = selectedMethodConnected,
                disabledDescription = if (selectedMethodConnected) null
                    else stringResource(R.string.provider_openai_api_key_disabled_reason),
            )
        }
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text(stringResource(R.string.provider_disconnect_chatgpt_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.provider_disconnect_chatgpt_body)) } },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) {
            Text(stringResource(R.string.provider_disconnect_chatgpt), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.provider_keep_connected)) } },
    )
    if (confirmRemoveApiKey) AlertDialog(
        onDismissRequest = { confirmRemoveApiKey = false },
        title = { Text(stringResource(R.string.provider_openai_api_key_remove_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.provider_openai_api_key_remove_body)) } },
        confirmButton = { TextButton(onClick = { confirmRemoveApiKey = false; onDeleteApiKey() }) {
            Text(stringResource(R.string.provider_openai_api_key_remove), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmRemoveApiKey = false }) {
            Text(stringResource(R.string.provider_keep_key))
        } },
    )
}

@Composable
private fun OpenAiAuthMethodControl(method: ProviderSettings.OpenAiAuthMethod, onSelect: (ProviderSettings.OpenAiAuthMethod) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val changeLabel = stringResource(R.string.provider_openai_change_method)
    val selectedLabel = stringResource(when (method) {
        ProviderSettings.OpenAiAuthMethod.BROWSER -> R.string.provider_openai_browser_short
        ProviderSettings.OpenAiAuthMethod.DEVICE_CODE -> R.string.provider_openai_device_short
        ProviderSettings.OpenAiAuthMethod.API_KEY -> R.string.provider_openai_api_short
    })
    JarvysGroup {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = changeLabel) { choosing = true }
            .testTag("provider-openai-auth-method").padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(selectedLabel, Modifier.weight(1f).testTag("provider-openai-auth-method-label"),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Icon(LucideIcons.ChevronDown, contentDescription = changeLabel, modifier = Modifier.size(18.dp))
        }
    }
    if (choosing) JarvysChoiceSheet(stringResource(R.string.provider_openai_api_method),
        listOf(JarvysChoiceOption(ProviderSettings.OpenAiAuthMethod.BROWSER, stringResource(R.string.provider_openai_method_browser)),
            JarvysChoiceOption(ProviderSettings.OpenAiAuthMethod.DEVICE_CODE, stringResource(R.string.provider_openai_method_device)),
            JarvysChoiceOption(ProviderSettings.OpenAiAuthMethod.API_KEY, stringResource(R.string.provider_openai_method_api_key))),
        selected = method, onSelect = onSelect, onClose = { choosing = false })
}

@Composable
private fun ProviderAuthDiagnostic(diagnostic: CodexAuthDiagnostic, method: String) {
    val context = LocalContext.current
    val title = stringResource(R.string.provider_auth_diagnostic_title)
    val displayText = diagnostic.toDisplayText(method)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(displayText, Modifier.fillMaxWidth().testTag("provider-auth-diagnostic"),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(title, displayText))
            Toast.makeText(context, R.string.provider_auth_diagnostic_copied, Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth().testTag("provider-auth-diagnostic-copy")) {
            Text(stringResource(R.string.provider_auth_diagnostic_copy))
        }
    }
}

@Composable
private fun codexConnectionDetail(state: ProvidersUiState, context: Context): String =
    if (state.codexConnected) state.codexAccountId?.takeIf(String::isNotBlank)
        ?.let { context.getString(R.string.provider_account_id, it) }
        ?: context.getString(R.string.provider_chatgpt_account_connected)
    else context.getString(R.string.provider_signin_required)

@Composable
private fun BrowserOAuthProgress(exchanging: Boolean, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(if (exchanging) R.string.provider_browser_exchanging else R.string.provider_waiting_chatgpt), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.provider_cancel_signin)) }
    }
}

@Composable
private fun DeviceCodeProgressCard(
    state: CodexDeviceCodeState,
    onCopy: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
        .testTag("provider-device-code-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.provider_device_code_title),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when (state) {
            CodexDeviceCodeState.RequestingCode -> {
                DeviceCodeProgressRow(R.string.provider_device_code_requesting)
                DeviceCodeCancelButton(onCancel)
            }
            is CodexDeviceCodeState.WaitingForCode, is CodexDeviceCodeState.ExchangingCode -> {
                val code = when (state) {
                    is CodexDeviceCodeState.WaitingForCode -> state.userCode
                    is CodexDeviceCodeState.ExchangingCode -> state.userCode
                    else -> ""
                }
                val verificationUrl = when (state) {
                    is CodexDeviceCodeState.WaitingForCode -> state.verificationUrl
                    is CodexDeviceCodeState.ExchangingCode -> state.verificationUrl
                    else -> CodexDeviceCodeFlow.VERIFICATION_URL
                }
                val expiresAtMillis = when (state) {
                    is CodexDeviceCodeState.WaitingForCode -> state.expiresAtMillis
                    is CodexDeviceCodeState.ExchangingCode -> state.expiresAtMillis
                    else -> 0L
                }
                Text(stringResource(R.string.provider_device_code_instruction),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(code,
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("provider-device-code-value"),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center)
                OutlinedButton(onClick = { onCopy(code) },
                    modifier = Modifier.fillMaxWidth().testTag("provider-device-code-copy")) {
                    Text(stringResource(R.string.provider_device_code_copy))
                }
                Button(onClick = { onOpenPage(verificationUrl) },
                    modifier = Modifier.fillMaxWidth().testTag("provider-device-code-open")) {
                    Text(stringResource(R.string.provider_device_code_open))
                }
                Text(stringResource(R.string.provider_device_code_url_caption),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(verificationUrl,
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag("provider-device-code-url"))
                }
                val expiry = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(
                    java.util.Date(expiresAtMillis))
                Text(stringResource(R.string.provider_device_code_expires, expiry),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                DeviceCodeProgressRow(if (state is CodexDeviceCodeState.ExchangingCode)
                    R.string.provider_device_code_exchanging else R.string.provider_device_code_waiting)
                DeviceCodeCancelButton(onCancel)
            }
            else -> Unit
        }
    }
}

@Composable
private fun DeviceCodeProgressRow(message: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceCodeCancelButton(onCancel: () -> Unit) {
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("provider-device-code-cancel")) {
        Text(stringResource(R.string.provider_cancel_signin))
    }
}

@Composable
internal fun ProvidersOpenRouterDetailScreen(repository: ProvidersRepository) {
    val state by repository.state.collectAsState()
    val models by CodexModelCatalog.openRouterModels.collectAsState()
    ProvidersOpenRouterDetailContent(
        state = state,
        models = models,
        onSelectModel = repository::selectOpenRouterModel,
        onUseActive = { repository.chooseActiveProvider(ProviderSettings.Provider.OPENROUTER) },
        onKeyChange = repository::setOpenRouterKeyInput,
        onSaveKey = repository::saveOpenRouterKey,
        onDeleteKey = repository::deleteOpenRouterKey,
    )
}

@Composable
internal fun ProvidersOpenRouterDetailContent(
    state: ProvidersUiState,
    models: List<com.jarvys.agent.OpenRouterModelInfo>,
    onSelectModel: (String) -> Unit,
    onUseActive: () -> Unit,
    onKeyChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onDeleteKey: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editingKey by remember(state.openRouterConnected) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JarvysSectionLabel(stringResource(R.string.provider_active_model))
        JarvysGroup(contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            OpenRouterModelField(state.openRouterModel, models, onSelectModel)
            Text(stringResource(R.string.provider_image_model_hint), Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            ActiveProviderAction(state.activeProvider == ProviderSettings.Provider.OPENROUTER,
                stringResource(R.string.provider_use_openrouter_active),
                onClick = onUseActive)
        }
        JarvysSectionLabel(stringResource(R.string.provider_openrouter_heading))
        JarvysGroup {
            if (state.openRouterConnected && !editingKey) {
                Text(stringResource(R.string.provider_openrouter_key_saved), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editingKey = true }) { Text(stringResource(R.string.provider_replace_key)) }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.provider_remove_key), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                Text(stringResource(R.string.provider_openrouter_key_privacy), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                JarvysTextField(state.openRouterKeyInput, onKeyChange,
                    label = { Text(stringResource(R.string.provider_openrouter_key_label)) },
                    modifier = Modifier.padding(horizontal = 14.dp), singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingKey) OutlinedButton(onClick = { editingKey = false }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.provider_cancel))
                    }
                    Button(onClick = onSaveKey, modifier = Modifier.weight(1f),
                        enabled = state.openRouterKeyInput.isNotBlank()) {
                        Text(stringResource(if (editingKey) R.string.provider_save_replacement_key else R.string.provider_save_api_key))
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.provider_remove_openrouter_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.provider_remove_openrouter_body)) } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteKey() }) {
            Text(stringResource(R.string.provider_remove_key), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.provider_keep_key)) } },
    )
}

@Composable
internal fun ProvidersExaDetailScreen(repository: ProvidersRepository) {
    val state by repository.state.collectAsState()
    ProvidersExaDetailContent(
        state = state,
        onKeyChange = repository::setExaKeyInput,
        onSave = repository::saveExaKey,
        onDelete = repository::deleteExaKey,
    )
}

@Composable
internal fun ProvidersExaDetailContent(
    state: ProvidersUiState,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editingKey by remember(state.exaConnected) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        JarvysSectionLabel(stringResource(R.string.web_search_exa_heading))
        JarvysGroup {
            Text(stringResource(R.string.web_search_exa_privacy), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
            if (state.exaConnected && !editingKey) {
                Text(stringResource(R.string.web_search_exa_key_saved), Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editingKey = true }) { Text(stringResource(R.string.provider_replace_key)) }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.provider_remove_key), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                JarvysTextField(state.exaKeyInput, onKeyChange,
                    label = { Text(stringResource(R.string.web_search_exa_key_label)) },
                    modifier = Modifier.padding(horizontal = 14.dp), singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingKey) OutlinedButton(onClick = { editingKey = false }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.provider_cancel))
                    }
                    Button(onClick = onSave, modifier = Modifier.weight(1f),
                        enabled = state.exaKeyInput.isNotBlank()) {
                        Text(stringResource(if (editingKey) R.string.provider_save_replacement_key else R.string.provider_save_api_key))
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.web_search_exa_remove_title)) },
        text = { ScrollableDialogContent { Text(stringResource(R.string.web_search_exa_remove_body)) } },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) {
            Text(stringResource(R.string.provider_remove_key), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.provider_keep_key)) } },
    )
}

@Composable
private fun ActiveProviderAction(
    active: Boolean,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    disabledDescription: String? = null,
) {
    if (active) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End) {
            JarvysTag(stringResource(R.string.provider_active_tag))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled) { Text(label) }
            if (!enabled && disabledDescription != null) {
                Text(disabledDescription, Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProviderConnectionRow(name: String, connected: Boolean, detail: String) {
    JarvysListRow(
        title = name,
        subtitle = detail,
        icon = if (connected) LucideIcons.CircleCheck else LucideIcons.Circle,
        trailing = {
            JarvysTag(stringResource(if (connected) R.string.connector_status_connected
                else R.string.connector_state_disconnected))
        },
    )
}
