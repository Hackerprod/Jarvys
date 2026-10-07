package com.jarvys.agent.providers

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CodexModelCatalog
import com.jarvys.agent.CodexOAuthManager
import com.jarvys.agent.ModelProviderClient
import com.jarvys.agent.OpenAICodexResponsesClient
import com.jarvys.agent.OpenRouterClient
import com.jarvys.agent.ProviderContextWindowResolver
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.SecretStore

/** Single exhaustive registry for provider clients, credential availability, and context metadata. */
object ProviderClientRegistry {
    class Descriptor internal constructor(
        val provider: ProviderSettings.Provider,
        private val clientFactory: (SecretStore, ProviderSettings) -> ModelProviderClient,
        private val configured: (SecretStore, ProviderSettings) -> Boolean,
        private val contextWindowResolver: (Context, String, CancellationToken) -> Int?,
    ) {
        fun createClient(secrets: SecretStore, settings: ProviderSettings): ModelProviderClient =
            clientFactory(secrets, settings)

        fun isConfigured(secrets: SecretStore, settings: ProviderSettings): Boolean = configured(secrets, settings)

        fun unavailableReason(secrets: SecretStore, settings: ProviderSettings): String? =
            if (isConfigured(secrets, settings)) null else UNAVAILABLE_REASON

        fun contextWindow(context: Context, model: String, token: CancellationToken): Int? =
            contextWindowResolver(context, model, token)
    }

    private val openAiCodex = Descriptor(
        provider = ProviderSettings.Provider.OPENAI_CODEX,
        clientFactory = { secrets, settings -> OpenAICodexResponsesClient(CodexOAuthManager(secrets), settings) },
        configured = { secrets, _ -> secrets.codexCredentials != null },
        contextWindowResolver = { context, model, token -> CodexModelCatalog.contextLimitForRun(context, model, token) },
    )

    private val openAiApi = Descriptor(
        provider = ProviderSettings.Provider.OPENAI_API,
        clientFactory = { secrets, settings ->
            OpenRouterClient(secrets, settings, ChatCompletionsConfig.openAiApiDefault())
        },
        configured = { secrets, _ -> !secrets.openAiApiKey.isNullOrBlank() },
        contextWindowResolver = { context, model, token ->
            CodexModelCatalog.contextLimitForOpenAiApiRun(context, model, token)
        },
    )

    private val custom = Descriptor(
        provider = ProviderSettings.Provider.CUSTOM,
        clientFactory = { secrets, settings ->
            CustomEndpointCompatibilityRegistry.descriptor(
                CustomEndpointCompatibilityRegistry.parse(settings.customCompatibility)
                    ?: throw IllegalStateException("Custom endpoint compatibility is unavailable"),
            ).createClient(secrets, settings)
        },
        configured = { _, settings ->
            settings.customBaseUrl.isNotBlank()
                && CustomEndpointCompatibilityRegistry.parse(settings.customCompatibility) != null
                && runCatching { CustomEndpointUrlValidator().normalizeBaseUrl(settings.customBaseUrl) }.isSuccess
        },
        contextWindowResolver = { _, _, _ -> null },
    )

    private val openRouter = Descriptor(
        provider = ProviderSettings.Provider.OPENROUTER,
        clientFactory = { secrets, settings -> OpenRouterClient(secrets, settings) },
        configured = { secrets, _ -> !secrets.openRouterKey.isNullOrBlank() },
        contextWindowResolver = { context, model, token ->
            ProviderContextWindowResolver.openRouterContextWindow(context, model, token)
        },
    )

    @JvmStatic
    fun descriptor(provider: ProviderSettings.Provider): Descriptor = when (provider) {
        ProviderSettings.Provider.OPENAI_CODEX -> openAiCodex
        ProviderSettings.Provider.OPENAI_API -> openAiApi
        ProviderSettings.Provider.OPENROUTER -> openRouter
        ProviderSettings.Provider.CUSTOM -> custom
    }

    @JvmStatic
    fun createClient(
        provider: ProviderSettings.Provider,
        secrets: SecretStore,
        settings: ProviderSettings,
    ): ModelProviderClient = descriptor(provider).createClient(secrets, settings)

    @JvmStatic
    fun isConfigured(provider: ProviderSettings.Provider, secrets: SecretStore, settings: ProviderSettings): Boolean =
        descriptor(provider).isConfigured(secrets, settings)

    @JvmStatic
    fun unavailableReason(provider: ProviderSettings.Provider, secrets: SecretStore, settings: ProviderSettings): String? =
        descriptor(provider).unavailableReason(secrets, settings)

    @JvmStatic
    fun contextWindow(
        provider: ProviderSettings.Provider,
        context: Context,
        model: String,
        token: CancellationToken,
    ): Int? = descriptor(provider).contextWindow(context, model, token)

    private const val UNAVAILABLE_REASON = "sin proveedor"
}
