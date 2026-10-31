package com.jarvys.agent

import com.jarvys.agent.providers.CustomEndpointCompatibility
import com.jarvys.agent.providers.CustomEndpointCompatibilityRegistry
import com.jarvys.agent.providers.CustomEndpointAddressResolver
import com.jarvys.agent.providers.CustomEndpointModelsResponse
import com.jarvys.agent.providers.CustomEndpointModelsClient
import com.jarvys.agent.providers.CustomEndpointModelsTransport
import com.jarvys.agent.providers.CustomEndpointProbeResult
import com.jarvys.agent.providers.CustomEndpointUrlException
import com.jarvys.agent.providers.CustomEndpointUrlValidator
import com.jarvys.agent.providers.ChatCompletionsConfig
import com.jarvys.agent.OpenRouterClient
import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomEndpointSecurityTest {
    @Test fun normalizesBaseAndFullChatCompletionsUrlsWithoutAllowingNonHttps() {
        val validator = CustomEndpointUrlValidator()
        val base = validator.normalizeBaseUrl("https://api.example.com/v1///")
        assertEquals("https://api.example.com/v1", base.baseUrl)
        assertEquals("https://api.example.com/v1/models", base.modelsUrl)
        assertEquals("https://api.example.com/v1/chat/completions", base.chatCompletionsUrl)
        assertEquals(base, validator.normalizeBaseUrl("https://api.example.com/v1/chat/completions/"))
        listOf(
            "http://api.example.com/v1",
            "https:///v1",
            "https://user:pass@api.example.com/v1",
            "https://api.example.com/v1#fragment",
            "https://api.example.com/v1?query=1",
            "https://localhost/v1",
            "https://service.localhost/v1",
            "https://printer.local/v1",
            "https://metadata.internal/v1",
        ).forEach { input -> assertRejected(validator, input) }
    }

    @Test fun literalPrivateSpecialAndMetadataAddressesAreRejectedButPublicLiteralsRemainValid() {
        val validator = CustomEndpointUrlValidator()
        listOf(
            "10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.1", "127.0.0.1",
            "169.254.169.254", "100.64.0.1", "fc00::1", "fd12::1", "::1", "fe80::1", "ff02::1",
            "2001:db8::1", "168.63.129.16",
        ).forEach { host -> assertRejected(validator, "https://$host/v1") }
        assertEquals("https://8.8.8.8/v1", validator.normalizeBaseUrl("https://8.8.8.8/v1").baseUrl)
        assertEquals("https://[2606:4700:4700::1111]/v1",
            validator.normalizeBaseUrl("https://[2606:4700:4700::1111]/v1").baseUrl)
    }

    @Test fun dnsResolutionRejectsTheHostIfAnyAnswerIsNonPublic() {
        val mixedAnswers = CustomEndpointUrlValidator(CustomEndpointAddressResolver {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.10"))
        })
        assertTrue(runCatching { mixedAnswers.validateAndResolveBaseUrl("https://mixed.example/v1") }
            .exceptionOrNull() is CustomEndpointUrlException)
        val publicAnswers = CustomEndpointUrlValidator(CustomEndpointAddressResolver {
            listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))
        })
        assertEquals("https://public.example/v1", publicAnswers.validateAndResolveBaseUrl("https://public.example/v1").baseUrl)
    }

    @Test fun customModelsProbeUsesNormalizedModelsUrlOptionalBearerAndNoRedirects() {
        val calls = mutableListOf<Triple<String, Map<String, String>, Boolean>>()
        val transport = CustomEndpointModelsTransport { url, headers, follow ->
            calls += Triple(url, headers, follow)
            CustomEndpointModelsResponse(200, """{"data":[{"id":"vendor/model-a","name":"Model A",
                "context_length":65536,"output_limit":8192},{"id":"vendor/model-a"},{"id":"vendor/model-b"}]}""")
        }
        val client = CustomEndpointModelsClient(transport, CustomEndpointUrlValidator(CustomEndpointAddressResolver {
            listOf(InetAddress.getByName("8.8.8.8"))
        }))
        val result = client.probe("https://api.example.com/v1/chat/completions/", " custom-key ")
        val verified = result as CustomEndpointProbeResult.Verified
        assertEquals("https://api.example.com/v1", verified.endpoint.baseUrl)
        assertEquals("https://api.example.com/v1/models", calls.single().first)
        assertEquals(mapOf("Authorization" to "Bearer custom-key"), calls.single().second)
        assertFalse(calls.single().third)
        assertEquals(listOf("vendor/model-a", "vendor/model-b"), verified.models.map { it.id })
        assertEquals(65_536, verified.models.first().contextLimit)
        assertEquals(8_192, verified.models.first().outputLimit)
        assertEquals(verified.models, com.jarvys.agent.providers.CustomEndpointModelCache.get("https://api.example.com/v1"))
    }

    @Test fun customModelsProbeAllowsNoAuthAndClassifiesInvalidKeyUnavailableAndInvalidUrl() {
        val statuses = ArrayDeque<Int>().apply { add(401); add(404); add(302); add(204) }
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val client = CustomEndpointModelsClient(
            CustomEndpointModelsTransport { url, headers, follow ->
                assertFalse(follow)
                calls += url to headers
                CustomEndpointModelsResponse(statuses.removeFirst(), "not exposed")
            },
            CustomEndpointUrlValidator(CustomEndpointAddressResolver { listOf(InetAddress.getByName("9.9.9.9")) }),
        )
        assertTrue(client.probe("https://endpoint.example/v1", "").isInvalidKey())
        assertTrue(client.probe("https://endpoint.example/v1", "").isCannotCheck(404))
        assertTrue(client.probe("https://endpoint.example/v1", "").isCannotCheck(302))
        assertTrue(client.probe("https://endpoint.example/v1", "").isVerified())
        assertTrue(calls.all { it.second.isEmpty() })

        val noNetworkCalls = mutableListOf<String>()
        val noNetwork = CustomEndpointModelsClient(
            CustomEndpointModelsTransport { url, _, _ -> noNetworkCalls += url; CustomEndpointModelsResponse(200, "{}") },
            CustomEndpointUrlValidator(CustomEndpointAddressResolver { listOf(InetAddress.getByName("8.8.8.8")) }),
        )
        assertEquals(CustomEndpointProbeResult.InvalidUrl, noNetwork.probe("http://endpoint.example/v1", "key"))
        assertTrue(noNetworkCalls.isEmpty())
    }

    @Test fun customChatCompletionsSendsOptionalBearerWithoutOpenRouterHeadersOrRedirects() {
        val context = RuntimeEnvironment.getApplication()
        val secrets = SecretStore(context.getSharedPreferences("r4-custom-chat-secrets", Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        })
        val settings = ProviderSettings(context)
        settings.setProvider(ProviderSettings.Provider.CUSTOM)
        settings.saveCustomEndpoint("R4", "https://public.example/v1", "OPENAI_CHAT_COMPLETIONS", "vendor/model")
        val configFactory: (String) -> ChatCompletionsConfig = { endpoint ->
            ChatCompletionsConfig.customOpenAiCompatible(endpoint, SecretStore::getCustomEndpointKey, { 512 }, null)
        }

        LocalCustomChatServer(200, """{"choices":[{"message":{"content":"ok"}}]}""").use { server ->
            val client = OpenRouterClient(secrets, settings, configFactory(server.endpoint))
            assertEquals("ok", client.complete("system", "user", emptyList(), emptyList(), "session-r4",
                CancellationToken.uncancellable()).text)
            val request = server.awaitRequest()
            assertEquals("POST /v1/chat/completions HTTP/1.1", request.line)
            assertFalse(request.headers.containsKey("authorization"))
            assertFalse(request.headers.containsKey("x-title"))
            val body = JSONObject(request.body)
            assertEquals("vendor/model", body.getString("model"))
            assertEquals(512, body.getInt("max_tokens"))
            assertFalse(body.has("max_completion_tokens"))
            assertFalse(body.has("temperature"))
        }

        val key = "custom-endpoint-secret-r4"
        secrets.saveCustomEndpointKey(key)
        LocalCustomChatServer(302, "", "http://127.0.0.1/redirect-target").use { server ->
            val client = OpenRouterClient(secrets, settings, configFactory(server.endpoint))
            val failure = runCatching {
                client.complete("system", "user", emptyList(), emptyList(), "session-r4",
                    CancellationToken.uncancellable())
            }.exceptionOrNull()
            assertEquals("Custom endpoint request failed with HTTP 302", failure?.message)
            assertFalse(failure.toString().contains(key))
            val request = server.awaitRequest()
            assertEquals("Bearer $key", request.headers["authorization"])
        }
    }

    @Test fun compatibilityMenuContainsOnlyRegisteredClientAndExaIsTheServiceDescriptor() {
        assertEquals(listOf(CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS),
            CustomEndpointCompatibilityRegistry.descriptors.map { it.compatibility })
        assertEquals("OPENAI_CHAT_COMPLETIONS", CustomEndpointCompatibilityRegistry.parse("OPENAI_CHAT_COMPLETIONS")?.name)
        assertEquals(listOf(com.jarvys.agent.providers.ProviderServiceRegistry.EXA_ID),
            com.jarvys.agent.providers.ProviderServiceRegistry.services.map { it.id })
        assertEquals(null, com.jarvys.agent.providers.ProviderServiceRegistry.find("unknown-service"))
    }

    private fun assertRejected(validator: CustomEndpointUrlValidator, url: String) {
        assertTrue("Expected unsafe endpoint to be rejected: $url", runCatching { validator.normalizeBaseUrl(url) }
            .exceptionOrNull() is CustomEndpointUrlException)
    }

    private fun CustomEndpointProbeResult.isInvalidKey() = this is CustomEndpointProbeResult.InvalidKey
    private fun CustomEndpointProbeResult.isCannotCheck(status: Int) = this is CustomEndpointProbeResult.CannotCheck
        && statusCode == status
    private fun CustomEndpointProbeResult.isVerified() = this is CustomEndpointProbeResult.Verified

    private data class CapturedRequest(val line: String, val headers: Map<String, String>, val body: String)

    private class LocalCustomChatServer(
        private val status: Int,
        private val response: String,
        private val location: String? = null,
    ) : AutoCloseable {
        private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val endpoint = "http://127.0.0.1:${listener.localPort}/v1/chat/completions"
        private val captured = AtomicReference<CapturedRequest>()
        private val worker = Thread {
            listener.accept().use { socket ->
                val input = socket.getInputStream()
                val requestLine = readAsciiLine(input)
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input)
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase(Locale.ROOT)] = line.substringAfter(':').trim()
                }
                val bytes = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
                captured.set(CapturedRequest(requestLine, headers, String(bytes, 0, offset, StandardCharsets.UTF_8)))
                val payload = response.toByteArray(StandardCharsets.UTF_8)
                val phrase = if (status == 200) "OK" else "Found"
                val header = "HTTP/1.1 $status $phrase\r\nContent-Length: ${payload.size}\r\n" +
                    (location?.let { "Location: $it\r\n" } ?: "") + "Connection: close\r\n\r\n"
                socket.getOutputStream().write(header.toByteArray(StandardCharsets.US_ASCII))
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
            }
        }.apply { isDaemon = true; start() }

        fun awaitRequest(): CapturedRequest {
            worker.join(2_000)
            return requireNotNull(captured.get()) { "No custom endpoint request was captured" }
        }

        override fun close() {
            listener.close()
            worker.join(2_000)
        }

        private fun readAsciiLine(input: java.io.InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0 || value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString(StandardCharsets.US_ASCII.name())
        }
    }
}
