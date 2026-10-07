package com.jarvys.agent.providers

import android.app.Activity
import android.content.Context
import com.jarvys.agent.CodexAuthDiagnostic
import com.jarvys.agent.CodexOAuthManager
import com.jarvys.agent.CodexModelCatalog
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.R
import com.jarvys.agent.SecretStore
import com.jarvys.agent.CancellationToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class ProvidersUiState(
    val activeProvider: ProviderSettings.Provider,
    val activeModel: String,
    val activeReasoningVariant: String,
    val openAiModel: String,
    val openAiReasoningVariant: String,
    val openRouterModel: String,
    val codexConnected: Boolean,
    val codexAccountId: String?,
    val codexOAuthInProgress: Boolean,
    val codexOAuthExchanging: Boolean = false,
    val codexOAuthError: String? = null,
    val codexOAuthDiagnostic: CodexAuthDiagnostic? = null,
    val codexDeviceCode: CodexDeviceCodeState = CodexDeviceCodeState.Idle,
    val openRouterConnected: Boolean,
    val exaConnected: Boolean,
    val openRouterKeyInput: String = "",
    val exaKeyInput: String = "",
    val openAiAuthMethod: ProviderSettings.OpenAiAuthMethod = ProviderSettings.OpenAiAuthMethod.BROWSER,
    val openAiApiModel: String = "gpt-4.1",
    val openAiApiKeyConnected: Boolean = false,
    val openAiApiKeyInput: String = "",
    val openAiApiKeyEditing: Boolean = false,
    val openAiApiKeyValidation: OpenAiApiKeyUiState = OpenAiApiKeyUiState.IDLE,
    val customName: String = "",
    val customBaseUrl: String = "",
    val customCompatibility: CustomEndpointCompatibility = CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS,
    val customModel: String = "",
    val customEndpointConfigured: Boolean = false,
    val customEndpointKeyConnected: Boolean = false,
    val customModels: List<CustomEndpointModel> = emptyList(),
    val customNameInput: String = "",
    val customBaseUrlInput: String = "",
    val customCompatibilityInput: CustomEndpointCompatibility = CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS,
    val customModelInput: String = "",
    val customEndpointKeyInput: String = "",
    val customEndpointValidation: CustomEndpointUiState = CustomEndpointUiState.IDLE,
    val customEndpointHttpStatus: Int? = null,
)

enum class OpenAiApiKeyUiState { IDLE, VALIDATING, INVALID_KEY, UNAVAILABLE, SAVED }
enum class CustomEndpointUiState { IDLE, VALIDATING, INVALID_URL, INVALID_KEY, CANNOT_CHECK, NETWORK_ERROR, VERIFIED, SAVED_UNVERIFIED }

data class ProvidersFeedback(val resourceId: Int, val arguments: List<String> = emptyList())

/** App-scoped provider UI state survives navigation between the Providers list and its routes. */
class ProvidersRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val settings = ProviderSettings(app)
    private val secretStore = SecretStore.get(app)
    @Volatile private var codexOAuthManager: CodexOAuthManager? = null
    private val authAttemptPreferences = app.getSharedPreferences("jarvys_auth_attempt", Context.MODE_PRIVATE)
    private val deviceCodeLock = Any()
    private val deviceCodeExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysCodexDeviceCode").apply { isDaemon = true }
    }
    private var deviceCodeCancellation: CancellationToken? = null
    private var deviceCodeFuture: Future<*>? = null
    private val apiKeyLock = Any()
    private val apiKeyExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysOpenAiApiKeyValidation").apply { isDaemon = true }
    }
    private var apiKeyValidationFuture: Future<*>? = null
    private val customEndpointLock = Any()
    private val customEndpointExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysCustomEndpointValidation").apply { isDaemon = true }
    }
    private var customEndpointFuture: Future<*>? = null
    private val _state = MutableStateFlow(readPersisted())
    val state = _state.asStateFlow()
    private val _feedback = MutableSharedFlow<ProvidersFeedback>(extraBufferCapacity = 8)
    val feedback = _feedback.asSharedFlow()

    init {
        val interrupted = authAttemptPreferences.getString("pending", null)
        if (interrupted != null) {
            authAttemptPreferences.edit().remove("pending").commit()
            if (!_state.value.codexConnected) {
                _state.update { it.copy(
                    codexOAuthError = if (interrupted == "browser") app.getString(R.string.oauth_signin_interrupted) else null,
                    codexDeviceCode = if (interrupted == "device") CodexDeviceCodeState.Failed(R.string.oauth_signin_interrupted,
                        CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.UNKNOWN, "interrupted")) else it.codexDeviceCode,
                ) }
            }
        }
    }

    private fun markAuthAttempt(method: String?) = synchronized(authAttemptPreferences) {
        val editor = authAttemptPreferences.edit()
        if (method == null) editor.remove("pending") else editor.putString("pending", method)
        editor.commit()
    }

    private fun clearAuthAttempt(method: String) = synchronized(authAttemptPreferences) {
        if (authAttemptPreferences.getString("pending", null) == method) authAttemptPreferences.edit().remove("pending").commit()
    }

    fun refresh() {
        val previous = _state.value
        val persisted = readPersisted()
        _state.value = persisted.copy(
            codexOAuthInProgress = previous.codexOAuthInProgress,
            codexOAuthExchanging = previous.codexOAuthExchanging,
            codexOAuthError = previous.codexOAuthError,
            codexOAuthDiagnostic = previous.codexOAuthDiagnostic,
            codexDeviceCode = previous.codexDeviceCode,
            openRouterKeyInput = previous.openRouterKeyInput,
            exaKeyInput = previous.exaKeyInput,
            openAiApiKeyInput = previous.openAiApiKeyInput,
            openAiApiKeyEditing = previous.openAiApiKeyEditing,
            openAiApiKeyValidation = previous.openAiApiKeyValidation,
            customNameInput = previous.customNameInput,
            customBaseUrlInput = previous.customBaseUrlInput,
            customCompatibilityInput = previous.customCompatibilityInput,
            customModelInput = previous.customModelInput,
            customEndpointKeyInput = previous.customEndpointKeyInput,
            customEndpointValidation = previous.customEndpointValidation,
            customEndpointHttpStatus = previous.customEndpointHttpStatus,
            customModels = if (persisted.customBaseUrl == previous.customBaseUrl) previous.customModels else persisted.customModels,
        )
    }

    fun chooseActiveProvider(provider: ProviderSettings.Provider) {
        settings.setProvider(provider)
        refresh()
    }

    fun selectOpenAiAuthMethod(method: ProviderSettings.OpenAiAuthMethod) {
        val previous = _state.value.openAiAuthMethod
        if (previous != method) {
            if (method != ProviderSettings.OpenAiAuthMethod.DEVICE_CODE && _state.value.codexDeviceCode.isRunning) {
                cancelDeviceCodeSignIn()
            }
            if (method != ProviderSettings.OpenAiAuthMethod.BROWSER && _state.value.codexOAuthInProgress) {
                cancelCodexSignIn()
            }
        }
        settings.setOpenAiAuthMethod(method)
        refresh()
    }

    fun useSelectedOpenAiMethodAsActive(): Boolean {
        val provider = when (state.value.openAiAuthMethod) {
            ProviderSettings.OpenAiAuthMethod.BROWSER,
            ProviderSettings.OpenAiAuthMethod.DEVICE_CODE -> {
                if (!state.value.codexConnected) return false
                ProviderSettings.Provider.OPENAI_CODEX
            }
            ProviderSettings.OpenAiAuthMethod.API_KEY -> {
                if (!state.value.openAiApiKeyConnected) return false
                ProviderSettings.Provider.OPENAI_API
            }
        }
        settings.setProvider(provider)
        refresh()
        return true
    }

    fun selectOpenAiApiModel(model: String) {
        settings.setOpenAiApiModel(model)
        refresh()
    }

    fun chooseCustomEndpointAsActive(): Boolean {
        if (!_state.value.customEndpointConfigured) return false
        settings.setProvider(ProviderSettings.Provider.CUSTOM)
        refresh()
        return true
    }

    fun setCustomNameInput(value: String) { updateCustomDraft { it.copy(customNameInput = value) } }
    fun setCustomBaseUrlInput(value: String) { updateCustomDraft { it.copy(customBaseUrlInput = value) } }
    fun setCustomModelInput(value: String) { updateCustomDraft { it.copy(customModelInput = value) } }
    fun setCustomEndpointKeyInput(value: String) { updateCustomDraft { it.copy(customEndpointKeyInput = value) } }
    fun setCustomCompatibilityInput(value: CustomEndpointCompatibility) {
        updateCustomDraft { it.copy(customCompatibilityInput = value) }
    }

    private fun updateCustomDraft(transform: (ProvidersUiState) -> ProvidersUiState) {
        _state.update { current ->
            val updated = transform(current)
            if (current.customEndpointValidation == CustomEndpointUiState.VALIDATING) updated
            else updated.copy(customEndpointValidation = CustomEndpointUiState.IDLE, customEndpointHttpStatus = null)
        }
    }

    fun validateAndSaveCustomEndpoint(client: CustomEndpointModelsClient = CustomEndpointModelsClient()): Future<*>? {
        synchronized(customEndpointLock) {
            if (_state.value.customEndpointValidation == CustomEndpointUiState.VALIDATING) return customEndpointFuture
            _state.update { it.copy(customEndpointValidation = CustomEndpointUiState.VALIDATING,
                customEndpointHttpStatus = null) }
            val draft = _state.value
            val oldBaseUrl = settings.customBaseUrl
            val normalizedDraft = runCatching { CustomEndpointUrlValidator().normalizeBaseUrl(draft.customBaseUrlInput).baseUrl }
                .getOrNull()
            val existingKey = if (normalizedDraft == oldBaseUrl) secretStore.customEndpointKey.orEmpty() else ""
            val candidateKey = draft.customEndpointKeyInput.trim().ifBlank { existingKey }
            val future = customEndpointExecutor.submit {
                val result = client.probe(draft.customBaseUrlInput, candidateKey)
                synchronized(customEndpointLock) {
                    when (result) {
                        is CustomEndpointProbeResult.Verified -> commitCustomEndpoint(
                            draft = draft,
                            normalizedBaseUrl = result.endpoint.baseUrl,
                            models = result.models,
                            verified = true,
                            replaceKey = draft.customEndpointKeyInput.trim().takeIf(String::isNotEmpty),
                            clearOldKey = secretStore.customEndpointKey != null && oldBaseUrl != result.endpoint.baseUrl,
                        )
                        CustomEndpointProbeResult.InvalidUrl -> _state.update { it.copy(customEndpointValidation = CustomEndpointUiState.INVALID_URL) }
                        CustomEndpointProbeResult.InvalidKey -> _state.update { it.copy(customEndpointValidation = CustomEndpointUiState.INVALID_KEY) }
                        CustomEndpointProbeResult.NetworkError -> _state.update { it.copy(customEndpointValidation = CustomEndpointUiState.NETWORK_ERROR) }
                        is CustomEndpointProbeResult.CannotCheck -> _state.update { it.copy(
                            customEndpointValidation = CustomEndpointUiState.CANNOT_CHECK,
                            customEndpointHttpStatus = result.statusCode,
                        ) }
                    }
                    customEndpointFuture = null
                }
            }
            customEndpointFuture = future
            return future
        }
    }

    fun saveCustomEndpointWithoutChecking() {
        saveCustomEndpointWithoutChecking(CustomEndpointUrlValidator())
    }

    internal fun saveCustomEndpointWithoutChecking(validator: CustomEndpointUrlValidator) {
        synchronized(customEndpointLock) {
            if (_state.value.customEndpointValidation == CustomEndpointUiState.VALIDATING) return
            val draft = _state.value
            val normalized = try { validator.validateAndResolveBaseUrl(draft.customBaseUrlInput) }
                catch (_: CustomEndpointUrlException) {
                    _state.update { it.copy(customEndpointValidation = CustomEndpointUiState.INVALID_URL,
                        customEndpointHttpStatus = null) }
                    return
                }
            val oldBaseUrl = settings.customBaseUrl
            commitCustomEndpoint(
                draft = draft,
                normalizedBaseUrl = normalized.baseUrl,
                models = CustomEndpointModelCache.get(normalized.baseUrl),
                verified = false,
                replaceKey = draft.customEndpointKeyInput.trim().takeIf(String::isNotEmpty),
                clearOldKey = secretStore.customEndpointKey != null && oldBaseUrl != normalized.baseUrl,
            )
        }
    }

    private fun commitCustomEndpoint(
        draft: ProvidersUiState,
        normalizedBaseUrl: String,
        models: List<CustomEndpointModel>,
        verified: Boolean,
        replaceKey: String?,
        clearOldKey: Boolean,
    ) {
        val model = draft.customModelInput.trim().ifBlank { models.firstOrNull()?.id.orEmpty() }
        // Drop a credential bound to a different saved URL before switching the destination.
        if (clearOldKey) secretStore.clearCustomEndpointKey()
        settings.saveCustomEndpoint(draft.customNameInput, normalizedBaseUrl, draft.customCompatibilityInput.name, model)
        if (replaceKey != null) secretStore.saveCustomEndpointKey(replaceKey)
        refresh()
        _state.update { it.copy(
            customNameInput = draft.customNameInput.trim(),
            customBaseUrlInput = normalizedBaseUrl,
            customCompatibilityInput = draft.customCompatibilityInput,
            customModelInput = model,
            customEndpointKeyInput = "",
            customModels = models,
            customEndpointValidation = if (verified) CustomEndpointUiState.VERIFIED else CustomEndpointUiState.SAVED_UNVERIFIED,
            customEndpointHttpStatus = null,
        ) }
    }

    fun deleteCustomEndpoint() {
        val previousRaw = settings.customPreviousProvider
        val previous = runCatching { previousRaw?.let(ProviderSettings.Provider::valueOf) }.getOrNull()
        settings.clearCustomEndpoint()
        secretStore.clearCustomEndpointKey()
        CustomEndpointModelCache.clear(_state.value.customBaseUrl)
        if (settings.provider == ProviderSettings.Provider.CUSTOM) {
            val fallback = listOfNotNull(previous, ProviderSettings.Provider.OPENAI_CODEX,
                ProviderSettings.Provider.OPENAI_API, ProviderSettings.Provider.OPENROUTER)
                .distinct()
                .firstOrNull { it != ProviderSettings.Provider.CUSTOM
                    && ProviderClientRegistry.isConfigured(it, secretStore, settings) }
                ?: ProviderSettings.Provider.OPENROUTER
            settings.setProvider(fallback)
        }
        refresh()
        _state.update { it.copy(customNameInput = "", customBaseUrlInput = "", customModelInput = "",
            customCompatibilityInput = CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS,
            customEndpointKeyInput = "", customModels = emptyList(),
            customEndpointValidation = CustomEndpointUiState.IDLE, customEndpointHttpStatus = null) }
    }

    fun setOpenAiApiKeyInput(value: String) {
        _state.update { it.copy(openAiApiKeyInput = value, openAiApiKeyEditing = true,
            openAiApiKeyValidation = if (it.openAiApiKeyValidation == OpenAiApiKeyUiState.VALIDATING)
                it.openAiApiKeyValidation else OpenAiApiKeyUiState.IDLE) }
    }

    fun beginReplacingOpenAiApiKey() {
        synchronized(apiKeyLock) {
            if (_state.value.openAiApiKeyValidation == OpenAiApiKeyUiState.VALIDATING) return
            _state.update { it.copy(openAiApiKeyEditing = true, openAiApiKeyInput = "",
                openAiApiKeyValidation = OpenAiApiKeyUiState.IDLE) }
        }
    }

    fun cancelOpenAiApiKeyEdit() {
        synchronized(apiKeyLock) {
            if (_state.value.openAiApiKeyValidation == OpenAiApiKeyUiState.VALIDATING) return
            _state.update { it.copy(openAiApiKeyEditing = false, openAiApiKeyInput = "",
                openAiApiKeyValidation = OpenAiApiKeyUiState.IDLE) }
        }
    }

    fun validateAndSaveOpenAiApiKey(validator: OpenAiApiKeyValidator = OpenAiApiKeyValidator()): Future<*>? {
        synchronized(apiKeyLock) {
            if (_state.value.openAiApiKeyValidation == OpenAiApiKeyUiState.VALIDATING) return apiKeyValidationFuture
            val candidate = _state.value.openAiApiKeyInput.trim()
            if (candidate.isEmpty()) return null
            _state.update { it.copy(openAiApiKeyEditing = true, openAiApiKeyValidation = OpenAiApiKeyUiState.VALIDATING) }
            val future = apiKeyExecutor.submit {
                val result = validator.validate(candidate)
                synchronized(apiKeyLock) {
                    try {
                        when (result) {
                            OpenAiApiKeyValidationResult.VALID -> {
                                secretStore.saveOpenAiApiKey(candidate)
                                refresh()
                                _state.update { it.copy(openAiApiKeyInput = "", openAiApiKeyEditing = false,
                                    openAiApiKeyValidation = OpenAiApiKeyUiState.SAVED) }
                            }
                            OpenAiApiKeyValidationResult.INVALID_KEY -> _state.update {
                                it.copy(openAiApiKeyEditing = true, openAiApiKeyValidation = OpenAiApiKeyUiState.INVALID_KEY)
                            }
                            OpenAiApiKeyValidationResult.UNAVAILABLE -> _state.update {
                                it.copy(openAiApiKeyEditing = true, openAiApiKeyValidation = OpenAiApiKeyUiState.UNAVAILABLE)
                            }
                        }
                    } catch (_: RuntimeException) {
                        _state.update { it.copy(openAiApiKeyEditing = true,
                            openAiApiKeyValidation = OpenAiApiKeyUiState.UNAVAILABLE) }
                    } finally {
                        apiKeyValidationFuture = null
                    }
                }
            }
            apiKeyValidationFuture = future
            return future
        }
    }

    fun deleteOpenAiApiKey() {
        synchronized(apiKeyLock) {
            if (_state.value.openAiApiKeyValidation == OpenAiApiKeyUiState.VALIDATING) return
            secretStore.clearOpenAiApiKey()
            refresh()
            _state.update { it.copy(openAiApiKeyInput = "", openAiApiKeyEditing = false,
                openAiApiKeyValidation = OpenAiApiKeyUiState.IDLE) }
        }
    }

    fun setOpenRouterKeyInput(value: String) { _state.update { it.copy(openRouterKeyInput = value) } }
    fun setExaKeyInput(value: String) { _state.update { it.copy(exaKeyInput = value) } }

    fun reconcileOpenAiModels(models: List<com.jarvys.agent.ModelInfo>) {
        if (models.isEmpty()) return
        val current = _state.value
        val selected = models.firstOrNull { it.id == current.openAiModel }
            ?: models.firstOrNull { it.id == "gpt-5.4" }
            ?: models.first()
        if (selected.variants.isEmpty()) {
            if (selected.id != current.openAiModel) {
                settings.setOpenAiModel(selected.id)
                refresh()
            }
            return
        }
        val variant = selected.variants.firstOrNull { it.id == current.openAiReasoningVariant }
            ?: selected.defaultVariant
        if (selected.id != current.openAiModel || variant.id != current.openAiReasoningVariant) {
            settings.setCodexModelAndVariant(selected.id, variant.id)
            refresh()
        }
    }

    fun selectOpenAiModel(modelId: String, models: List<com.jarvys.agent.ModelInfo> = CodexModelCatalog.currentModels()) {
        val model = models.firstOrNull { it.id == modelId } ?: return
        val currentVariant = _state.value.openAiReasoningVariant
        val variant = model.variants.firstOrNull { it.id == currentVariant } ?: model.defaultVariant
        if (model.variants.isEmpty()) settings.setOpenAiModel(model.id)
        else settings.setCodexModelAndVariant(model.id, variant.id)
        refresh()
    }

    fun selectOpenAiReasoningVariant(variantId: String) {
        val model = CodexModelCatalog.find(_state.value.openAiModel) ?: return
        if (model.variants.isEmpty()) return
        val variant = model.variants.firstOrNull { it.id == variantId } ?: model.defaultVariant
        settings.setCodexModelAndVariant(model.id, variant.id)
        refresh()
    }

    fun selectOpenRouterModel(modelValue: String) {
        val model = modelValue.trim()
        if (model.isEmpty()) return
        settings.setOpenRouterModel(model)
        refresh()
    }

    fun selectQuickModel(provider: ProviderSettings.Provider, model: String, reasoningVariant: String) {
        try {
            settings.setProvider(provider)
            if (provider == ProviderSettings.Provider.OPENAI_CODEX) {
                val info = CodexModelCatalog.find(model)
                    ?: throw IllegalArgumentException("Unknown OpenAI Codex model")
                if (info.variants.isEmpty()) settings.setOpenAiModel(info.id)
                else {
                    val variant = info.variants.firstOrNull { it.id == reasoningVariant } ?: info.defaultVariant
                    settings.setCodexModelAndVariant(info.id, variant.id)
                }
            } else {
                if (provider == ProviderSettings.Provider.OPENAI_API) {
                    settings.setOpenAiAuthMethod(ProviderSettings.OpenAiAuthMethod.API_KEY)
                }
                if (provider == ProviderSettings.Provider.CUSTOM) {
                    settings.setCustomModel(model)
                } else {
                settings.setModel(model)
                }
            }
            refresh()
        } catch (error: RuntimeException) {
            feedback(R.string.chat_toast_invalid_model, error.message.orEmpty())
        }
    }

    fun beginCodexSignIn(activity: Activity) = beginCodexSignIn(activity, CodexOAuthManager(secretStore))

    internal fun beginCodexSignIn(activity: Activity, manager: CodexOAuthManager) {
        if (_state.value.codexOAuthInProgress || _state.value.codexDeviceCode.isRunning) return
        markAuthAttempt("browser")
        _state.update { it.copy(codexOAuthInProgress = true, codexOAuthExchanging = false,
            codexOAuthError = null, codexOAuthDiagnostic = null, codexDeviceCode = CodexDeviceCodeState.Idle) }
        try {
            codexOAuthManager?.cancelAuthorization()
            codexOAuthManager = manager
            manager.authorize(activity, object : CodexOAuthManager.Listener {
                override fun onDiagnostic(diagnostic: CodexAuthDiagnostic) {
                    if (codexOAuthManager === manager) _state.update { it.copy(codexOAuthDiagnostic = diagnostic) }
                }

                override fun onExchanging() {
                    if (codexOAuthManager === manager) _state.update { it.copy(codexOAuthExchanging = true) }
                }

                override fun onSuccess(accountId: String) {
                    if (codexOAuthManager !== manager) return
                    clearAuthAttempt("browser")
                    _state.update { it.copy(codexOAuthInProgress = false, codexOAuthExchanging = false,
                        codexOAuthError = null, codexOAuthDiagnostic = null) }
                    refresh()
                    feedback(R.string.chat_toast_chatgpt_connected)
                }

                override fun onFailure(message: String) {
                    if (codexOAuthManager !== manager) return
                    clearAuthAttempt("browser")
                    _state.update { it.copy(codexOAuthInProgress = false, codexOAuthExchanging = false, codexOAuthError = message) }
                    refresh()
                    feedback(R.string.chat_toast_signin_failed, message)
                }
            })
        } catch (error: RuntimeException) {
            manager.cancelAuthorization()
            clearAuthAttempt("browser")
            val message = app.getString(R.string.oauth_exchange_failed)
            _state.update { it.copy(codexOAuthInProgress = false, codexOAuthExchanging = false,
                codexOAuthError = message, codexOAuthDiagnostic = CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.BROWSER_LAUNCH, error)) }
            feedback(R.string.chat_toast_oauth_start_failed, message)
        }
    }

    fun cancelCodexSignIn() {
        codexOAuthManager?.cancelAuthorization()
        codexOAuthManager = null
        clearAuthAttempt("browser")
        _state.update { it.copy(codexOAuthInProgress = false, codexOAuthExchanging = false,
            codexOAuthError = null, codexOAuthDiagnostic = null) }
        refresh()
        feedback(if (_state.value.codexConnected) R.string.chat_toast_chatgpt_connected else R.string.chat_toast_oauth_cancelled)
    }

    fun beginDeviceCodeSignIn() = beginDeviceCodeSignIn(null)

    internal fun beginDeviceCodeSignIn(transport: CodexDeviceCodeTransport?) {
        synchronized(deviceCodeLock) {
            if (_state.value.codexOAuthInProgress || _state.value.codexDeviceCode.isRunning) return
            val cancellation = CancellationToken.cancellable()
            deviceCodeCancellation = cancellation
            markAuthAttempt("device")
            _state.update { it.copy(codexDeviceCode = CodexDeviceCodeState.RequestingCode) }
            deviceCodeFuture = deviceCodeExecutor.submit { runDeviceCodeSignIn(cancellation, transport) }
        }
    }

    fun cancelDeviceCodeSignIn() {
        val cancellation: CancellationToken?
        val future: Future<*>?
        synchronized(deviceCodeLock) {
            cancellation = deviceCodeCancellation
            future = deviceCodeFuture
            deviceCodeCancellation = null
            deviceCodeFuture = null
            clearAuthAttempt("device")
            _state.update { it.copy(codexDeviceCode = CodexDeviceCodeState.Idle) }
        }
        cancellation?.cancel()
        future?.cancel(true)
        refresh()
    }

    private fun runDeviceCodeSignIn(cancellation: CancellationToken, transport: CodexDeviceCodeTransport?) {
        try {
            val manager = CodexOAuthManager(secretStore)
            val gate = CodexOAuthManager.CredentialCommitGate { persist ->
                synchronized(deviceCodeLock) {
                    if (deviceCodeCancellation !== cancellation || cancellation.isCancelled)
                        throw CancellationException("Device-code sign-in cancelled")
                    persist.run()
                }
            }
            val flow = if (transport == null) CodexDeviceCodeFlow(manager, commitGate = gate)
                else CodexDeviceCodeFlow(manager, transport = transport, commitGate = gate)
            val accountId = flow.authenticate(cancellation) { next ->
                synchronized(deviceCodeLock) {
                    if (deviceCodeCancellation === cancellation && !cancellation.isCancelled && next !is CodexDeviceCodeState.Connected) {
                        _state.update { it.copy(codexDeviceCode = next) }
                    }
                }
            }
            synchronized(deviceCodeLock) {
                if (deviceCodeCancellation === cancellation && !cancellation.isCancelled) {
                    clearAuthAttempt("device")
                    refresh()
                    _state.update { it.copy(codexDeviceCode = CodexDeviceCodeState.Connected(accountId)) }
                    feedback(R.string.chat_toast_chatgpt_connected)
                }
            }
        } catch (_: CancellationException) {
            synchronized(deviceCodeLock) {
                if (deviceCodeCancellation === cancellation) {
                    clearAuthAttempt("device")
                    _state.update { it.copy(codexDeviceCode = CodexDeviceCodeState.Idle) }
                }
            }
        } catch (failure: CodexDeviceCodeFailure) {
            publishDeviceCodeFailure(cancellation, failure.messageResource, failure.diagnostic)
        } catch (error: Exception) {
            publishDeviceCodeFailure(cancellation, R.string.provider_device_code_failed,
                CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.UNKNOWN, error))
        } finally {
            synchronized(deviceCodeLock) {
                if (deviceCodeCancellation === cancellation) {
                    deviceCodeCancellation = null
                    deviceCodeFuture = null
                    clearAuthAttempt("device")
                }
            }
        }
    }

    private fun publishDeviceCodeFailure(cancellation: CancellationToken, messageResource: Int, diagnostic: CodexAuthDiagnostic? = null) {
        synchronized(deviceCodeLock) {
            if (deviceCodeCancellation === cancellation && !cancellation.isCancelled) {
                clearAuthAttempt("device")
                _state.update { it.copy(codexDeviceCode = CodexDeviceCodeState.Failed(messageResource, diagnostic)) }
            }
        }
    }

    fun disconnectCodex() {
        codexOAuthManager?.cancelAuthorization()
        codexOAuthManager = null
        cancelDeviceCodeSignIn()
        markAuthAttempt(null)
        secretStore.clearCodexTokens()
        refresh()
        _state.update { it.copy(codexOAuthInProgress = false, codexOAuthExchanging = false,
            codexOAuthError = null, codexOAuthDiagnostic = null, codexDeviceCode = CodexDeviceCodeState.Idle) }
    }

    fun saveOpenRouterKey() {
        try {
            secretStore.saveOpenRouterKey(_state.value.openRouterKeyInput)
            _state.update { it.copy(openRouterKeyInput = "") }
            refresh()
            feedback(R.string.chat_toast_openrouter_key_saved)
        } catch (error: RuntimeException) {
            feedback(R.string.chat_toast_openrouter_key_failed, error.message.orEmpty())
        }
    }

    fun deleteOpenRouterKey() {
        secretStore.clearOpenRouterKey()
        refresh()
    }

    fun saveExaKey() {
        val value = _state.value.exaKeyInput.trim()
        if (value.isEmpty()) return
        try {
            secretStore.saveConnectorSecret("web_search", "exa_api_key", value)
            _state.update { it.copy(exaKeyInput = "") }
            refresh()
            feedback(R.string.web_search_exa_key_saved_toast)
        } catch (_: RuntimeException) {
            feedback(R.string.web_search_exa_key_failed)
        }
    }

    fun deleteExaKey() {
        secretStore.saveConnectorSecret("web_search", "exa_api_key", "")
        refresh()
    }

    private fun readPersisted(): ProvidersUiState {
        val provider = settings.provider
        val codexModel = settings.configuredOpenAiModel
        val codexVariant = settings.configuredOpenAiReasoningVariant
        val openRouterModel = settings.configuredOpenRouterModel
        val customName = settings.customName
        val customBaseUrl = settings.customBaseUrl
        val customCompatibility = CustomEndpointCompatibilityRegistry.parse(settings.customCompatibility)
            ?: CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS
        val customModel = settings.configuredCustomModel
        val credentials = secretStore.codexCredentials
        return ProvidersUiState(
            activeProvider = provider,
            activeModel = settings.model,
            activeReasoningVariant = settings.reasoningVariant,
            openAiModel = codexModel,
            openAiReasoningVariant = codexVariant,
            openRouterModel = openRouterModel,
            codexConnected = credentials != null,
            codexAccountId = credentials?.accountId,
            codexOAuthInProgress = false,
            openRouterConnected = !secretStore.openRouterKey.isNullOrBlank(),
            exaConnected = !secretStore.getConnectorSecret("web_search", "exa_api_key").isNullOrBlank(),
            openAiAuthMethod = settings.openAiAuthMethod,
            openAiApiModel = settings.configuredOpenAiApiModel,
            openAiApiKeyConnected = !secretStore.openAiApiKey.isNullOrBlank(),
            customName = customName,
            customBaseUrl = customBaseUrl,
            customCompatibility = customCompatibility,
            customModel = customModel,
            customEndpointConfigured = customBaseUrl.isNotBlank(),
            customEndpointKeyConnected = !secretStore.customEndpointKey.isNullOrBlank(),
            customModels = CustomEndpointModelCache.get(customBaseUrl),
            customNameInput = customName,
            customBaseUrlInput = customBaseUrl,
            customCompatibilityInput = customCompatibility,
            customModelInput = customModel,
        )
    }

    private fun feedback(resourceId: Int, vararg arguments: String) {
        _feedback.tryEmit(ProvidersFeedback(resourceId, arguments.toList()))
    }

    companion object {
        @Volatile private var instance: ProvidersRepository? = null
        fun get(context: Context): ProvidersRepository = instance ?: synchronized(this) {
            instance ?: ProvidersRepository(context.applicationContext).also { instance = it }
        }
    }
}
