package com.jarvys.agent.providers

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

data class CustomEndpointModel(val id: String, val name: String, val contextLimit: Int, val outputLimit: Int)
data class CustomEndpointModelsResponse(val statusCode: Int, val body: String)

fun interface CustomEndpointModelsTransport {
    fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CustomEndpointModelsResponse
}

sealed class CustomEndpointProbeResult {
    data class Verified(val endpoint: NormalizedCustomEndpoint, val models: List<CustomEndpointModel>) : CustomEndpointProbeResult()
    object InvalidUrl : CustomEndpointProbeResult()
    object InvalidKey : CustomEndpointProbeResult()
    data class CannotCheck(val statusCode: Int) : CustomEndpointProbeResult()
    object NetworkError : CustomEndpointProbeResult()
}

/** In-memory endpoint-scoped model cache; unavailable catalogs never erase a known list. */
object CustomEndpointModelCache {
    private val cache = ConcurrentHashMap<String, List<CustomEndpointModel>>()

    fun get(baseUrl: String): List<CustomEndpointModel> = cache[baseUrl].orEmpty()
    fun put(baseUrl: String, models: List<CustomEndpointModel>) { cache[baseUrl] = models.toList() }
    fun clear(baseUrl: String) { cache.remove(baseUrl) }
}

class CustomEndpointModelsClient(
    private val transport: CustomEndpointModelsTransport = UrlConnectionCustomEndpointModelsTransport,
    private val validator: CustomEndpointUrlValidator = CustomEndpointUrlValidator(),
) {
    fun probe(baseUrl: String, apiKey: String): CustomEndpointProbeResult {
        val normalized = try { validator.validateAndResolveBaseUrl(baseUrl) }
            catch (_: CustomEndpointUrlException) { return CustomEndpointProbeResult.InvalidUrl }
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        val response = try { transport.get(normalized.modelsUrl, headers, followRedirects = false) }
            catch (_: Exception) { return CustomEndpointProbeResult.NetworkError }
        return when {
            response.statusCode in 200..299 -> {
                val models = parseModels(response.body)
                CustomEndpointModelCache.put(normalized.baseUrl, models)
                CustomEndpointProbeResult.Verified(normalized, models)
            }
            response.statusCode == 401 || response.statusCode == 403 -> CustomEndpointProbeResult.InvalidKey
            else -> CustomEndpointProbeResult.CannotCheck(response.statusCode)
        }
    }

    private fun parseModels(body: String): List<CustomEndpointModel> = runCatching {
        val rows = JSONObject(body).optJSONArray("data") ?: return emptyList()
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id", "").trim()
                if (id.isEmpty()) continue
                add(CustomEndpointModel(
                    id = id,
                    name = row.optString("name", id).ifBlank { id },
                    contextLimit = row.optInt("context_length", row.optInt("context", 0)).takeIf { it > 0 } ?: 0,
                    outputLimit = row.optInt("output_limit", row.optInt("max_output_tokens", 0)).takeIf { it > 0 } ?: 0,
                ))
            }
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}

private object UrlConnectionCustomEndpointModelsTransport : CustomEndpointModelsTransport {
    override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CustomEndpointModelsResponse {
        require(!followRedirects) { "Custom endpoints must not follow redirects" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            if (status !in 200..299) {
                runCatching { connection.errorStream?.close() }
                return CustomEndpointModelsResponse(status, "")
            }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_RESPONSE_BYTES)
                    output.write(buffer, 0, read)
                }
                String(output.toByteArray(), StandardCharsets.UTF_8)
            }
            return CustomEndpointModelsResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
}
