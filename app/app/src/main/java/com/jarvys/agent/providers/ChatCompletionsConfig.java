package com.jarvys.agent.providers;

import com.jarvys.agent.OpenRouterClient;
import com.jarvys.agent.CodexModelCatalog;
import com.jarvys.agent.SecretStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Endpoint, encrypted Bearer-key lookup, and optional provider-specific headers for Chat Completions. */
public final class ChatCompletionsConfig {
    public static final String OPENAI_API_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    @FunctionalInterface
    public interface BearerKeyProvider {
        String getBearerKey(SecretStore secrets);
    }

    @FunctionalInterface
    public interface OutputTokenLimitProvider {
        Integer getOutputTokenLimit(String modelId);
    }

    @FunctionalInterface
    public interface EndpointSafetyCheck {
        void validate(String endpoint);
    }

    private final String endpoint;
    private final BearerKeyProvider bearerKeyProvider;
    private final Map<String, String> additionalHeaders;
    private final String outputTokenLimitParameterName;
    private final OutputTokenLimitProvider outputTokenLimitProvider;
    private final Double temperature;
    private final String providerName;
    private final String invalidAuthorizationMessage;
    private final boolean bearerRequired;
    private final boolean followRedirects;
    private final EndpointSafetyCheck endpointSafetyCheck;

    public ChatCompletionsConfig(
            String endpoint,
            BearerKeyProvider bearerKeyProvider,
            Map<String, String> additionalHeaders) {
        this(endpoint, bearerKeyProvider, additionalHeaders, null, null, null,
                "OpenRouter", null, true, true, null);
    }

    public ChatCompletionsConfig(
            String endpoint,
            BearerKeyProvider bearerKeyProvider,
            Map<String, String> additionalHeaders,
            String outputTokenLimitParameterName,
            OutputTokenLimitProvider outputTokenLimitProvider,
            Double temperature,
            String providerName,
            String invalidAuthorizationMessage) {
        this(endpoint, bearerKeyProvider, additionalHeaders, outputTokenLimitParameterName,
                outputTokenLimitProvider, temperature, providerName, invalidAuthorizationMessage, true, true, null);
    }

    public ChatCompletionsConfig(
            String endpoint,
            BearerKeyProvider bearerKeyProvider,
            Map<String, String> additionalHeaders,
            String outputTokenLimitParameterName,
            OutputTokenLimitProvider outputTokenLimitProvider,
            Double temperature,
            String providerName,
            String invalidAuthorizationMessage,
            boolean bearerRequired,
            boolean followRedirects,
            EndpointSafetyCheck endpointSafetyCheck) {
        if (endpoint == null || endpoint.trim().isEmpty()) throw new IllegalArgumentException("Chat Completions endpoint is empty");
        if (bearerKeyProvider == null) throw new IllegalArgumentException("Chat Completions Bearer key provider is required");
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        if (additionalHeaders != null) {
            for (Map.Entry<String, String> header : additionalHeaders.entrySet()) {
                if ("authorization".equalsIgnoreCase(header.getKey())) {
                    throw new IllegalArgumentException("Authorization is supplied by the Bearer key provider");
                }
                headers.put(header.getKey(), header.getValue());
            }
        }
        this.endpoint = endpoint;
        this.bearerKeyProvider = bearerKeyProvider;
        this.additionalHeaders = Collections.unmodifiableMap(headers);
        this.outputTokenLimitParameterName = outputTokenLimitParameterName;
        this.outputTokenLimitProvider = outputTokenLimitProvider;
        this.temperature = temperature;
        this.providerName = providerName == null ? "OpenAI API" : providerName;
        this.invalidAuthorizationMessage = invalidAuthorizationMessage;
        this.bearerRequired = bearerRequired;
        this.followRedirects = followRedirects;
        this.endpointSafetyCheck = endpointSafetyCheck;
    }

    public static ChatCompletionsConfig openRouterDefault() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Title", "Jarvys Android Agent");
        return new ChatCompletionsConfig(OpenRouterClient.ENDPOINT, SecretStore::getOpenRouterKey, headers);
    }

    public static ChatCompletionsConfig openAiApiDefault() {
        return new ChatCompletionsConfig(
                OPENAI_API_ENDPOINT,
                SecretStore::getOpenAiApiKey,
                Collections.emptyMap(),
                "max_completion_tokens",
                CodexModelCatalog::openAiApiOutputLimit,
                null,
                "OpenAI API",
                "Clave de API de OpenAI inválida o revocada", true, true, null);
    }

    public static ChatCompletionsConfig customOpenAiCompatible(
            String endpoint,
            BearerKeyProvider bearerKeyProvider,
            OutputTokenLimitProvider outputTokenLimitProvider,
            EndpointSafetyCheck endpointSafetyCheck) {
        return new ChatCompletionsConfig(endpoint, bearerKeyProvider, Collections.emptyMap(),
                "max_tokens", outputTokenLimitProvider, null,
                "Custom endpoint", "Custom endpoint rejected the API key", false, false, endpointSafetyCheck);
    }

    public String getEndpoint() { return endpoint; }

    public Map<String, String> getAdditionalHeaders() { return additionalHeaders; }

    public String getBearerKey(SecretStore secrets) { return bearerKeyProvider.getBearerKey(secrets); }

    public boolean isBearerRequired() { return bearerRequired; }

    public boolean followsRedirects() { return followRedirects; }

    public void validateEndpoint() {
        if (endpointSafetyCheck != null) endpointSafetyCheck.validate(endpoint);
    }

    public String getOutputTokenLimitParameterName() { return outputTokenLimitParameterName; }

    public Integer getOutputTokenLimit(String modelId) {
        return outputTokenLimitProvider == null ? null : outputTokenLimitProvider.getOutputTokenLimit(modelId);
    }

    public Double getTemperature() { return temperature; }

    public String authorizationFailureMessage(int status) {
        return invalidAuthorizationMessage == null
                ? providerName + " request failed with HTTP " + status : invalidAuthorizationMessage;
    }

    public boolean hasSpecificAuthorizationFailureMessage() { return invalidAuthorizationMessage != null; }

    public String rateLimitMessage() { return providerName + " request failed with HTTP 429"; }

    public String serverFailureMessage(int status) { return providerName + " server request failed with HTTP " + status; }

    public String contextOverflowMessage() { return providerName + " context window exceeded"; }

    public String requestFailureMessage(int status) { return providerName + " request failed with HTTP " + status; }

    /** Keeps the standard OpenRouter headers/key policy while allowing an isolated local-transport check. */
    public ChatCompletionsConfig withEndpoint(String endpoint) {
        return new ChatCompletionsConfig(endpoint, bearerKeyProvider, additionalHeaders,
                outputTokenLimitParameterName, outputTokenLimitProvider, temperature,
                providerName, invalidAuthorizationMessage, bearerRequired, followRedirects, endpointSafetyCheck);
    }
}
