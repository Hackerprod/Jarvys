package com.jarvys.agent.providers

import com.jarvys.agent.ModelProviderClient
import com.jarvys.agent.OpenRouterClient
import com.jarvys.agent.ProviderSettings
import com.jarvys.agent.R
import com.jarvys.agent.SecretStore

enum class CustomEndpointCompatibility { OPENAI_CHAT_COMPLETIONS }

/** Closed list of compatibility modes that have a real client implementation. */
object CustomEndpointCompatibilityRegistry {
    class Descriptor internal constructor(
        val compatibility: CustomEndpointCompatibility,
        val labelResource: Int,
        private val clientFactory: (SecretStore, ProviderSettings) -> ModelProviderClient,
    ) {
        fun createClient(secrets: SecretStore, settings: ProviderSettings): ModelProviderClient =
            clientFactory(secrets, settings)
    }

    private val openAiChatCompletions = Descriptor(
        compatibility = CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS,
        labelResource = R.string.custom_endpoint_compat_openai_chat_completions,
        clientFactory = { secrets, settings ->
            val validator = CustomEndpointUrlValidator()
            val normalized = validator.normalizeBaseUrl(settings.customBaseUrl)
            val baseUrl = normalized.baseUrl
            OpenRouterClient(secrets, settings, ChatCompletionsConfig.customOpenAiCompatible(
                normalized.chatCompletionsUrl,
                SecretStore::getCustomEndpointKey,
                { model ->
                    CustomEndpointModelCache.get(baseUrl).firstOrNull { it.id == model }
                        ?.outputLimit?.takeIf { it > 0 }
                },
                validator::validateChatCompletionsUrl,
            ))
        },
    )

    val descriptors: List<Descriptor>
        get() = CustomEndpointCompatibility.values().map(::descriptor)

    fun descriptor(compatibility: CustomEndpointCompatibility): Descriptor = when (compatibility) {
        CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS -> openAiChatCompletions
    }

    fun parse(value: String): CustomEndpointCompatibility? =
        CustomEndpointCompatibility.values().firstOrNull { it.name == value }
}
