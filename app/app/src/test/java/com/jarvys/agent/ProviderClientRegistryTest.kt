package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.providers.ChatCompletionsConfig
import com.jarvys.agent.providers.ProviderClientRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderClientRegistryTest {
    private lateinit var context: android.app.Application
    private lateinit var secrets: SecretStore
    private lateinit var settings: ProviderSettings
    private lateinit var catalogFlow: MutableStateFlow<List<ModelInfo>>
    private lateinit var apiCatalogFlow: MutableStateFlow<List<ModelInfo>>
    private lateinit var previousModels: List<ModelInfo>
    private lateinit var previousApiModels: List<ModelInfo>
    private var previousAuthoritative = false
    private var previousApiAuthoritative = false

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        secrets = SecretStore(context.getSharedPreferences("r2-provider-registry-secrets", Context.MODE_PRIVATE)).also {
            SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, it)
        }
        context.getSharedPreferences("jarvys_provider_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("provider_context_metadata", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ProviderSettings(context)

        val modelsField = CodexModelCatalog::class.java.getDeclaredField("_models").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        catalogFlow = modelsField.get(CodexModelCatalog) as MutableStateFlow<List<ModelInfo>>
        previousModels = catalogFlow.value
        val authoritativeField = CodexModelCatalog::class.java.getDeclaredField("authoritativeCatalogLoaded")
            .apply { isAccessible = true }
        previousAuthoritative = authoritativeField.getBoolean(CodexModelCatalog)
        val apiModelsField = CodexModelCatalog::class.java.getDeclaredField("_openAiApiModels").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        apiCatalogFlow = apiModelsField.get(CodexModelCatalog) as MutableStateFlow<List<ModelInfo>>
        previousApiModels = apiCatalogFlow.value
        val apiAuthoritativeField = CodexModelCatalog::class.java
            .getDeclaredField("authoritativeOpenAiApiCatalogLoaded").apply { isAccessible = true }
        previousApiAuthoritative = apiAuthoritativeField.getBoolean(CodexModelCatalog)
        catalogFlow.value = listOf(ModelInfo("gpt-5.4", "GPT-5.4", 51_337, 8_192,
            setOf("text"), setOf("text"), listOf(ModelVariant("medium", "medium", "auto", "medium"))))
        authoritativeField.setBoolean(CodexModelCatalog, true)
        apiCatalogFlow.value = listOf(ModelInfo("gpt-4.1", "GPT-4.1", 1_047_576, 32_768,
            setOf("text", "image"), setOf("text"), emptyList()))
        apiAuthoritativeField.setBoolean(CodexModelCatalog, true)
    }

    @After fun tearDown() {
        catalogFlow.value = previousModels
        CodexModelCatalog::class.java.getDeclaredField("authoritativeCatalogLoaded").apply { isAccessible = true }
            .setBoolean(CodexModelCatalog, previousAuthoritative)
        apiCatalogFlow.value = previousApiModels
        CodexModelCatalog::class.java.getDeclaredField("authoritativeOpenAiApiCatalogLoaded").apply { isAccessible = true }
            .setBoolean(CodexModelCatalog, previousApiAuthoritative)
    }

    @Test fun exhaustiveRegistryCreatesTheExpectedClientForEachRegisteredProvider() {
        assertTrue(ProviderClientRegistry.createClient(ProviderSettings.Provider.OPENAI_CODEX, secrets, settings)
            is OpenAICodexResponsesClient)
        assertTrue(ProviderClientRegistry.createClient(ProviderSettings.Provider.OPENROUTER, secrets, settings)
            is OpenRouterClient)
        settings.saveCustomEndpoint("custom", "https://api.example.com/v1", "OPENAI_CHAT_COMPLETIONS", "custom-model")
        assertTrue(ProviderClientRegistry.createClient(ProviderSettings.Provider.CUSTOM, secrets, settings)
            is OpenRouterClient)
        assertEquals(ProviderSettings.Provider.OPENAI_CODEX,
            ProviderClientRegistry.descriptor(ProviderSettings.Provider.OPENAI_CODEX).provider)
        assertEquals(ProviderSettings.Provider.OPENROUTER,
            ProviderClientRegistry.descriptor(ProviderSettings.Provider.OPENROUTER).provider)
        assertEquals(ProviderSettings.Provider.CUSTOM,
            ProviderClientRegistry.descriptor(ProviderSettings.Provider.CUSTOM).provider)
    }

    @Test fun openAiApiDescriptorCreatesFixedOpenAiChatClientWithBearerAndNoOpenRouterTitle() {
        secrets.saveOpenAiApiKey("openai-api-r3b")
        settings.setProvider(ProviderSettings.Provider.OPENAI_API)
        settings.setOpenAiApiModel("gpt-4.1")
        val client = ProviderClientRegistry.createClient(ProviderSettings.Provider.OPENAI_API, secrets, settings)
        assertTrue(client is OpenRouterClient)
        val config = OpenRouterClient::class.java.getDeclaredField("config").apply { isAccessible = true }
            .get(client) as ChatCompletionsConfig
        assertEquals(ChatCompletionsConfig.OPENAI_API_ENDPOINT, config.endpoint)
        assertEquals("openai-api-r3b", config.getBearerKey(secrets))
        assertTrue(config.additionalHeaders.isEmpty())
        assertEquals("max_completion_tokens", config.outputTokenLimitParameterName)
        assertNull(config.temperature)
    }

    @Test fun apiKeyChatCompletionsRequestUsesCatalogMaxCompletionTokensAndOmitsTemperatureAndXTitle() {
        val key = "sk-openai-request-r3b"
        secrets.saveOpenAiApiKey(key)
        settings.setProvider(ProviderSettings.Provider.OPENAI_API)
        settings.setOpenAiApiModel("gpt-4.1")
        assertEquals(32_768, CodexModelCatalog.openAiApiOutputLimit("gpt-4.1"))
        LocalChatCompletionsServer(200, CHAT_RESPONSE).use { server ->
            val client = OpenRouterClient(secrets, settings,
                ChatCompletionsConfig.openAiApiDefault().withEndpoint(server.endpoint))
            assertEquals("ok", client.complete("system", "user", emptyList(), emptyList(), "session-r3b",
                CancellationToken.uncancellable()).text)
            val request = server.awaitRequest()
            assertEquals("Bearer $key", request.headers["authorization"])
            assertFalse(request.headers.containsKey("x-title"))
            val body = JSONObject(request.body)
            assertEquals("gpt-4.1", body.getString("model"))
            assertEquals(32_768, body.getInt("max_completion_tokens"))
            assertFalse(body.has("max_tokens"))
            assertFalse(body.has("temperature"))
        }
    }

    @Test fun openAiApiAuthenticationAndServerErrorsNeverEchoKeyOrBody() {
        val key = "sk-openai-error-secret-r3b"
        secrets.saveOpenAiApiKey(key)
        settings.setProvider(ProviderSettings.Provider.OPENAI_API)
        settings.setOpenAiApiModel("gpt-4.1")
        val config = ChatCompletionsConfig.openAiApiDefault()
        assertTrue(config.hasSpecificAuthorizationFailureMessage())
        assertEquals("Clave de API de OpenAI inválida o revocada", config.authorizationFailureMessage(401))
        for (status in listOf(401, 403, 429, 503)) {
            LocalChatCompletionsServer(status, "private body contains $key").use { server ->
                val client = OpenRouterClient(secrets, settings, config.withEndpoint(server.endpoint))
                val actualConfig = OpenRouterClient::class.java.getDeclaredField("config").apply { isAccessible = true }
                    .get(client) as ChatCompletionsConfig
                assertTrue(actualConfig.hasSpecificAuthorizationFailureMessage())
                val failure = runCatching {
                    client.complete("system", "user", emptyList(), emptyList(), "session-r3b",
                        CancellationToken.uncancellable())
                }.exceptionOrNull()
                assertNotNull(failure)
                assertFalse(failure.toString().contains(key))
                assertFalse(failure.toString().contains("private body"))
                if (status == 401 || status == 403) {
                    assertEquals("Clave de API de OpenAI inválida o revocada", failure?.message)
                }
                server.awaitRequest()
            }
        }
    }

    @Test fun eachProviderAvailabilityMatchesItsExistingCredentialAndUnavailableMessage() {
        listOf(ProviderSettings.Provider.OPENAI_CODEX, ProviderSettings.Provider.OPENAI_API,
            ProviderSettings.Provider.OPENROUTER).forEach { provider ->
            assertFalse(ProviderClientRegistry.isConfigured(provider, secrets, settings))
            assertEquals("sin proveedor", ProviderClientRegistry.unavailableReason(provider, secrets, settings))
        }

        secrets.saveCodexTokens("access-r2", "refresh-r2", System.currentTimeMillis() + 60_000, "account-r2")
        assertTrue(ProviderClientRegistry.isConfigured(ProviderSettings.Provider.OPENAI_CODEX, secrets, settings))
        assertNull(ProviderClientRegistry.unavailableReason(ProviderSettings.Provider.OPENAI_CODEX, secrets, settings))
        assertFalse(ProviderClientRegistry.isConfigured(ProviderSettings.Provider.OPENROUTER, secrets, settings))
        assertEquals("sin proveedor", ProviderClientRegistry.unavailableReason(ProviderSettings.Provider.OPENROUTER, secrets, settings))
        assertFalse(ProviderClientRegistry.isConfigured(ProviderSettings.Provider.OPENAI_API, secrets, settings))
        assertEquals("sin proveedor", ProviderClientRegistry.unavailableReason(ProviderSettings.Provider.OPENAI_API, secrets, settings))

        secrets.saveOpenRouterKey("router-key-r2")
        assertTrue(ProviderClientRegistry.isConfigured(ProviderSettings.Provider.OPENROUTER, secrets, settings))
        assertNull(ProviderClientRegistry.unavailableReason(ProviderSettings.Provider.OPENROUTER, secrets, settings))

        secrets.saveOpenAiApiKey("openai-api-r3b")
        assertTrue(ProviderClientRegistry.isConfigured(ProviderSettings.Provider.OPENAI_API, secrets, settings))
        assertNull(ProviderClientRegistry.unavailableReason(ProviderSettings.Provider.OPENAI_API, secrets, settings))

        assertFalse(ProviderClientRegistry.isConfigured(ProviderSettings.Provider.CUSTOM, secrets, settings))
        assertEquals("sin proveedor", ProviderClientRegistry.unavailableReason(ProviderSettings.Provider.CUSTOM, secrets, settings))
        settings.saveCustomEndpoint("custom", "https://api.example.com/v1", "OPENAI_CHAT_COMPLETIONS", "model")
        assertTrue(ProviderClientRegistry.isConfigured(ProviderSettings.Provider.CUSTOM, secrets, settings))
        assertNull(ProviderClientRegistry.unavailableReason(ProviderSettings.Provider.CUSTOM, secrets, settings))
    }

    @Test fun contextWindowDispatchUsesTheExistingProviderSpecificResolvers() {
        val codexModel = "gpt-5.4"
        settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX)
        settings.setModel(codexModel)
        assertEquals(51_337, ProviderContextWindowResolver.resolve(context, settings, CancellationToken.uncancellable()))

        val routerModel = "vendor/context-window-r2"
        settings.setProvider(ProviderSettings.Provider.OPENROUTER)
        settings.setModel(routerModel)
        context.getSharedPreferences("provider_context_metadata", Context.MODE_PRIVATE).edit()
            .putInt("model_$routerModel", 73_991)
            .putLong("fetched_$routerModel", System.currentTimeMillis())
            .commit()
        assertEquals(73_991, ProviderContextWindowResolver.resolve(context, settings, CancellationToken.uncancellable()))

        settings.setProvider(ProviderSettings.Provider.OPENAI_API)
        settings.setOpenAiApiModel("gpt-4.1")
        assertEquals(1_047_576, ProviderContextWindowResolver.resolve(context, settings, CancellationToken.uncancellable()))

        settings.saveCustomEndpoint("custom", "https://api.example.com/v1", "OPENAI_CHAT_COMPLETIONS", "custom-model")
        settings.setProvider(ProviderSettings.Provider.CUSTOM)
        assertEquals(ProviderContextWindowResolver.FALLBACK_CONTEXT_WINDOW,
            ProviderContextWindowResolver.resolve(context, settings, CancellationToken.uncancellable()))
    }

    @Test fun chatCompletionsConfigurationPreservesOpenRouterDefaultsAndSupportsAnotherEndpoint() {
        val defaults = ChatCompletionsConfig.openRouterDefault()
        assertEquals(OpenRouterClient.ENDPOINT, defaults.endpoint)
        assertEquals(mapOf("X-Title" to "Jarvys Android Agent"), defaults.additionalHeaders)
        secrets.saveOpenRouterKey("default-key-r2")
        val defaultClient = OpenRouterClient(secrets, settings)
        val defaultClientConfig = OpenRouterClient::class.java.getDeclaredField("config").apply { isAccessible = true }
            .get(defaultClient) as ChatCompletionsConfig
        assertEquals(OpenRouterClient.ENDPOINT, defaultClientConfig.endpoint)
        assertEquals(defaults.additionalHeaders, defaultClientConfig.additionalHeaders)
        assertEquals("default-key-r2", defaultClientConfig.getBearerKey(secrets))
        val defaultServer = LocalChatCompletionsServer(200, CHAT_RESPONSE).use { server ->
            val client = OpenRouterClient(secrets, settings, defaults.withEndpoint(server.endpoint))
            val reply = client.complete("system-r2", "user-r2", emptyList(), emptyList(), "session-r2",
                CancellationToken.uncancellable())
            assertEquals("ok", reply.text)
            server.awaitRequest().also { request ->
                assertEquals("POST /v1/chat/completions HTTP/1.1", request.line)
                assertEquals("Bearer default-key-r2", request.headers["authorization"])
                assertEquals("Jarvys Android Agent", request.headers["x-title"])
                assertEquals("application/json; charset=utf-8", request.headers["content-type"])
                val body = JSONObject(request.body)
                assertEquals("openrouter/free", body.getString("model"))
                assertFalse(body.getBoolean("stream"))
                val messages = body.getJSONArray("messages")
                assertEquals("system", messages.getJSONObject(0).getString("role"))
                assertEquals("system-r2", messages.getJSONObject(0).getString("content"))
                assertEquals("user", messages.getJSONObject(1).getString("role"))
                assertEquals("user-r2", messages.getJSONObject(1).getString("content"))
                assertFalse(body.has("tools"))
            }
        }
        assertNotNull(defaultServer)

        val customKey = "custom-key-r2-do-not-leak"
        val customConfig = ChatCompletionsConfig(
            "unused-until-replaced",
            { customKey },
            mapOf("X-R2-Target" to "custom"),
        )
        LocalChatCompletionsServer(503, "upstream echoed $customKey") .use { server ->
            val custom = OpenRouterClient(secrets, settings, customConfig.withEndpoint(server.endpoint))
            val failure = runCatching {
                custom.complete("system", "user", emptyList(), emptyList(), "session-r2", CancellationToken.uncancellable())
            }.exceptionOrNull()
            assertTrue(failure is ProviderHttpException)
            assertFalse(failure.toString().contains(customKey))
            val request = server.awaitRequest()
            assertEquals("POST /v1/chat/completions HTTP/1.1", request.line)
            assertEquals("Bearer $customKey", request.headers["authorization"])
            assertEquals("custom", request.headers["x-r2-target"])
            assertFalse(request.headers.containsKey("x-title"))
            assertEquals("openrouter/free", JSONObject(request.body).getString("model"))
        }
    }

    @Test fun formerDispatchSitesDelegateToTheRegistry() {
        val main = sourceFile("com/jarvys/agent/CoreAgentModel.java").readText()
        val agent = sourceFile("com/jarvys/agent/ProviderAgentModel.java").readText()
        val contextResolver = sourceFile("com/jarvys/agent/ProviderContextWindowResolver.java").readText()
        val proactive = sourceFile("com/jarvys/agent/proactive/ProactiveAgentProcessor.kt").readText()
        val chatCompletionsClient = sourceFile("com/jarvys/agent/OpenRouterClient.java").readText()
        listOf(main, agent, contextResolver, proactive).forEach { source ->
            assertFalse(source.contains("getProvider() == ProviderSettings.Provider."))
            assertFalse(source.contains("ProviderSettings.Provider.OPENAI_CODEX"))
            assertFalse(source.contains("ProviderSettings.Provider.OPENROUTER"))
            assertFalse(source.contains("ProviderSettings.Provider.OPENAI_CODEX ->"))
            assertFalse(source.contains("ProviderSettings.Provider.OPENROUTER ->"))
            assertFalse(source.contains("new OpenAICodexResponsesClient"))
            assertFalse(source.contains("new OpenRouterClient"))
        }
        assertTrue(main.contains("ProviderClientRegistry.createClient"))
        assertTrue(agent.contains("ProviderClientRegistry.createClient"))
        assertTrue(contextResolver.contains("ProviderClientRegistry.contextWindow"))
        assertTrue(proactive.contains("ProviderClientRegistry.unavailableReason"))
        assertFalse(chatCompletionsClient.contains("Log."))

        val registry = sourceFile("com/jarvys/agent/providers/ProviderClientRegistry.kt").readText()
        val dispatch = registry.substringAfter("fun descriptor(provider: ProviderSettings.Provider): Descriptor = when (provider)")
            .substringBefore("@JvmStatic\n    fun createClient")
        assertTrue(dispatch.contains("ProviderSettings.Provider.OPENAI_CODEX"))
        assertTrue(dispatch.contains("ProviderSettings.Provider.OPENAI_API"))
        assertTrue(dispatch.contains("ProviderSettings.Provider.OPENROUTER"))
        assertTrue(dispatch.contains("ProviderSettings.Provider.CUSTOM"))
        assertFalse(dispatch.contains("else ->"))
    }

    private fun sourceFile(relative: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not locate app source root from ${working.path}")
        return File(sourceRoot, relative)
    }

    private data class CapturedRequest(val line: String, val headers: Map<String, String>, val body: String)

    private class LocalChatCompletionsServer(status: Int, response: String) : AutoCloseable {
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
                val byteCount = headers["content-length"]?.toIntOrNull() ?: 0
                val bytes = ByteArray(byteCount)
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
                captured.set(CapturedRequest(requestLine, headers, String(bytes, 0, offset, StandardCharsets.UTF_8)))
                val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                val reason = if (status in 200..299) "OK" else "Service Unavailable"
                val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${responseBytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(head.toByteArray(StandardCharsets.US_ASCII))
                socket.getOutputStream().write(responseBytes)
                socket.getOutputStream().flush()
            }
        }.apply { isDaemon = true; start() }

        fun awaitRequest(): CapturedRequest {
            worker.join(2_000)
            return requireNotNull(captured.get()) { "Local Chat Completions server received no request" }
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

    private companion object {
        const val CHAT_RESPONSE = """{"choices":[{"message":{"content":"ok"}}]}"""
    }
}
