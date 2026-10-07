package com.jarvys.agent.providers

import java.net.HttpURLConnection
import java.net.URL

fun interface OpenAiApiModelsTransport {
    fun getModels(url: String, headers: Map<String, String>): Int
}

enum class OpenAiApiKeyValidationResult { VALID, INVALID_KEY, UNAVAILABLE }

/** Validates a candidate key without retaining it or reading/logging the response body. */
class OpenAiApiKeyValidator(
    private val transport: OpenAiApiModelsTransport = UrlConnectionOpenAiApiModelsTransport,
) {
    fun validate(apiKey: String): OpenAiApiKeyValidationResult {
        if (apiKey.isBlank()) return OpenAiApiKeyValidationResult.INVALID_KEY
        return try {
            val status = transport.getModels(MODELS_ENDPOINT, mapOf("Authorization" to "Bearer $apiKey"))
            when {
                status in 200..299 -> OpenAiApiKeyValidationResult.VALID
                status == 401 || status == 403 -> OpenAiApiKeyValidationResult.INVALID_KEY
                else -> OpenAiApiKeyValidationResult.UNAVAILABLE
            }
        } catch (_: Exception) {
            OpenAiApiKeyValidationResult.UNAVAILABLE
        }
    }

    companion object {
        const val MODELS_ENDPOINT = "https://api.openai.com/v1/models"
    }
}

private object UrlConnectionOpenAiApiModelsTransport : OpenAiApiModelsTransport {
    override fun getModels(url: String, headers: Map<String, String>): Int {
        require(url == OpenAiApiKeyValidator.MODELS_ENDPOINT) { "Untrusted OpenAI API validation endpoint" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
