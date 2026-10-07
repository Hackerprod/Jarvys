package com.jarvys.agent

import android.content.Context
import java.io.File
import com.jarvys.agent.providers.OpenAiApiKeyUiState
import com.jarvys.agent.providers.OpenAiApiKeyValidationResult
import com.jarvys.agent.providers.OpenAiApiKeyValidator
import com.jarvys.agent.providers.OpenAiApiModelsTransport
import com.jarvys.agent.providers.ProvidersRepository
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiApiKeyValidationTest {
    private lateinit var context: android.app.Application
    private lateinit var secrets: SecretStore
    private lateinit var repository: ProvidersRepository

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        secrets = SecretStore(context.getSharedPreferences("r3b-api-key-secrets", Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        })
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, secrets)
        context.getSharedPreferences("jarvys_provider_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ProvidersRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        repository = ProvidersRepository.get(context)
    }

    @Test fun validation200StoresKeyOnlyAfterSuccessAndRemovalPreservesOtherCredentials() {
        secrets.saveOpenRouterKey("router-key-preserved")
        secrets.saveCodexTokens("codex-access-preserved", "codex-refresh-preserved",
            System.currentTimeMillis() + 60_000, "codex-account")
        val key = "sk-r3b-valid-secret"
        repository.selectOpenAiAuthMethod(ProviderSettings.OpenAiAuthMethod.API_KEY)
        repository.setOpenAiApiKeyInput("  $key  ")
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        repository.validateAndSaveOpenAiApiKey(OpenAiApiKeyValidator(OpenAiApiModelsTransport { url, headers ->
            requests += url to headers
            200
        }))!!.get(5, TimeUnit.SECONDS)

        assertEquals(listOf(OpenAiApiKeyValidator.MODELS_ENDPOINT to mapOf("Authorization" to "Bearer $key")), requests)
        assertEquals(key, secrets.openAiApiKey)
        assertTrue(repository.state.value.openAiApiKeyConnected)
        assertEquals("", repository.state.value.openAiApiKeyInput)
        assertEquals(OpenAiApiKeyUiState.SAVED, repository.state.value.openAiApiKeyValidation)
        assertFalse("A saved key must not remain observable in provider state", repository.state.value.toString().contains(key))
        listOf(
            "com/jarvys/agent/providers/OpenAiApiKeyValidator.kt",
            "com/jarvys/agent/providers/ProvidersRepository.kt",
            "com/jarvys/agent/OpenRouterClient.java",
        ).forEach { relative ->
            val source = sourceFile(relative).readText()
            assertFalse("API key handling source should not log credentials: $relative", source.contains("Log."))
            assertFalse("API key handling source should not print credentials: $relative", source.contains("System.out"))
        }

        repository.deleteOpenAiApiKey()
        assertNull(secrets.openAiApiKey)
        assertEquals("router-key-preserved", secrets.openRouterKey)
        assertEquals("codex-access-preserved", secrets.codexCredentials?.accessToken)
        assertFalse(repository.state.value.openAiApiKeyConnected)
    }

    @Test fun unauthorizedAndNetworkFailuresNeverPersistTheCandidateKey() {
        val key = "sk-r3b-candidate-secret"
        for ((result, status) in listOf(
            OpenAiApiKeyValidationResult.INVALID_KEY to 401,
            OpenAiApiKeyValidationResult.INVALID_KEY to 403,
            OpenAiApiKeyValidationResult.UNAVAILABLE to null,
        )) {
            repository.setOpenAiApiKeyInput(key)
            var observedAuthorization: String? = null
            val transport = OpenAiApiModelsTransport { url, headers ->
                assertEquals(OpenAiApiKeyValidator.MODELS_ENDPOINT, url)
                observedAuthorization = headers["Authorization"]
                if (status == null) throw IOException("offline response contained $key")
                status
            }
            repository.validateAndSaveOpenAiApiKey(OpenAiApiKeyValidator(transport))!!.get(5, TimeUnit.SECONDS)
            assertEquals("Bearer $key", observedAuthorization)
            assertNull("$result must not persist a key", secrets.openAiApiKey)
            assertFalse(repository.state.value.openAiApiKeyConnected)
            assertEquals(when (result) {
                OpenAiApiKeyValidationResult.INVALID_KEY -> OpenAiApiKeyUiState.INVALID_KEY
                else -> OpenAiApiKeyUiState.UNAVAILABLE
            }, repository.state.value.openAiApiKeyValidation)
        }
    }

    @Test fun apiAuthMethodAndModelPersistWithoutChangingOtherProviderModels() {
        val settings = ProviderSettings(context)
        settings.setOpenAiAuthMethod(ProviderSettings.OpenAiAuthMethod.API_KEY)
        settings.setOpenAiApiModel("vendor/manual-api-model")
        settings.setProvider(ProviderSettings.Provider.OPENAI_API)
        settings.setModel("vendor/manual-api-model")
        settings.setProvider(ProviderSettings.Provider.OPENROUTER)
        settings.setModel("vendor/router-model")
        settings.setOpenAiModel("gpt-5.4")

        val reopened = ProviderSettings(context)
        reopened.setProvider(ProviderSettings.Provider.OPENAI_API)
        assertEquals("vendor/manual-api-model", reopened.model)
        assertEquals(ProviderSettings.OpenAiAuthMethod.API_KEY, reopened.openAiAuthMethod)
        reopened.setProvider(ProviderSettings.Provider.OPENROUTER)
        assertEquals("vendor/router-model", reopened.model)
        reopened.setProvider(ProviderSettings.Provider.OPENAI_CODEX)
        assertEquals("gpt-5.4", reopened.configuredOpenAiModel)
    }

    @Test fun selectedOpenAiAccessMethodActivatesOnlyWhenItsCredentialExists() {
        repository.selectOpenAiAuthMethod(ProviderSettings.OpenAiAuthMethod.API_KEY)
        assertFalse(repository.useSelectedOpenAiMethodAsActive())
        assertEquals(ProviderSettings.Provider.OPENROUTER, ProviderSettings(context).provider)

        secrets.saveOpenAiApiKey("sk-r3b-active")
        repository.refresh()
        assertTrue(repository.useSelectedOpenAiMethodAsActive())
        assertEquals(ProviderSettings.Provider.OPENAI_API, ProviderSettings(context).provider)

        repository.selectOpenAiAuthMethod(ProviderSettings.OpenAiAuthMethod.DEVICE_CODE)
        assertFalse(repository.useSelectedOpenAiMethodAsActive())
        secrets.saveCodexTokens("access-r3b", "refresh-r3b", System.currentTimeMillis() + 60_000, "codex-r3b")
        repository.refresh()
        assertTrue(repository.useSelectedOpenAiMethodAsActive())
        assertEquals(ProviderSettings.Provider.OPENAI_CODEX, ProviderSettings(context).provider)
    }

    @Test fun repeatedSubmissionDuringValidationReturnsSameWorkAndDoesNotSendTwice() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        repository.setOpenAiApiKeyInput("sk-r3b-single-submit")
        val validator = OpenAiApiKeyValidator(OpenAiApiModelsTransport { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            200
        })
        val first = repository.validateAndSaveOpenAiApiKey(validator)
        assertNotNull(first)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(OpenAiApiKeyUiState.VALIDATING, repository.state.value.openAiApiKeyValidation)
        val second = repository.validateAndSaveOpenAiApiKey(validator)
        assertSame(first, second)
        assertEquals(1, calls.get())
        release.countDown()
        first!!.get(5, TimeUnit.SECONDS)
        assertEquals(1, calls.get())
        assertTrue(repository.state.value.openAiApiKeyConnected)
    }

    private fun sourceFile(relative: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not locate source root from ${working.path}")
        return File(sourceRoot, relative)
    }
}
