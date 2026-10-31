package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.providers.CustomEndpointCompatibility
import com.jarvys.agent.providers.CustomEndpointModelsResponse
import com.jarvys.agent.providers.CustomEndpointModelsClient
import com.jarvys.agent.providers.CustomEndpointModelsTransport
import com.jarvys.agent.providers.CustomEndpointUrlValidator
import com.jarvys.agent.providers.CustomEndpointAddressResolver
import com.jarvys.agent.providers.CustomEndpointUiState
import com.jarvys.agent.providers.ProvidersRepository
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CustomEndpointRepositoryTest {
    private lateinit var context: android.app.Application
    private lateinit var secrets: SecretStore
    private lateinit var repository: ProvidersRepository

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val secretPrefs = context.getSharedPreferences("r4-custom-endpoint-secrets", Context.MODE_PRIVATE)
        secretPrefs.edit().clear().commit()
        secrets = SecretStore(secretPrefs)
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, secrets)
        context.getSharedPreferences("jarvys_provider_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ProvidersRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        repository = ProvidersRepository.get(context)
    }

    @Test fun verifiedEndpointPersistsNameUrlCompatibilityModelAndOptionalKeyAfterTwoHundred() {
        repository.setCustomNameInput("Team gateway")
        repository.setCustomBaseUrlInput("https://custom.example/v1/chat/completions/")
        repository.setCustomCompatibilityInput(CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS)
        repository.setCustomModelInput("team-model")
        repository.setCustomEndpointKeyInput("custom-key-r4")
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val client = fakeClient { url, headers ->
            requests += url to headers
            CustomEndpointModelsResponse(200, """{"data":[{"id":"team-model","name":"Team model",
                "context_length":8192,"output_limit":2048}]}""")
        }
        repository.validateAndSaveCustomEndpoint(client)!!.get(5, TimeUnit.SECONDS)

        assertEquals(listOf("https://custom.example/v1/models" to mapOf("Authorization" to "Bearer custom-key-r4")), requests)
        assertEquals("Team gateway", ProviderSettings(context).customName)
        assertEquals("https://custom.example/v1", ProviderSettings(context).customBaseUrl)
        assertEquals("OPENAI_CHAT_COMPLETIONS", ProviderSettings(context).customCompatibility)
        assertEquals("team-model", ProviderSettings(context).configuredCustomModel)
        assertEquals("custom-key-r4", secrets.customEndpointKey)
        assertTrue(repository.state.value.customEndpointConfigured)
        assertTrue(repository.state.value.customEndpointKeyConnected)
        assertEquals("team-model", repository.state.value.customModels.single().id)
        assertEquals(CustomEndpointUiState.VERIFIED, repository.state.value.customEndpointValidation)
        assertTrue(repository.chooseCustomEndpointAsActive())
        assertEquals(ProviderSettings.Provider.CUSTOM, ProviderSettings(context).provider)
    }

    @Test fun invalidUrlAndUnauthorizedEndpointNeverSaveDraftConfigurationOrKey() {
        repository.setCustomNameInput("invalid draft")
        repository.setCustomBaseUrlInput("http://localhost:11434/v1")
        repository.setCustomEndpointKeyInput("must-not-persist")
        val calls = mutableListOf<String>()
        val transportClient = fakeClient { url, _ -> calls += url; CustomEndpointModelsResponse(200, "{}") }
        repository.validateAndSaveCustomEndpoint(transportClient)!!.get(5, TimeUnit.SECONDS)
        assertEquals(CustomEndpointUiState.INVALID_URL, repository.state.value.customEndpointValidation)
        assertTrue(calls.isEmpty())
        assertEquals("", ProviderSettings(context).customBaseUrl)
        assertNull(secrets.customEndpointKey)

        repository.setCustomBaseUrlInput("https://custom.example/v1")
        repository.validateAndSaveCustomEndpoint(fakeClient { _, _ ->
            CustomEndpointModelsResponse(401, "response includes must-not-persist")
        })!!.get(5, TimeUnit.SECONDS)
        assertEquals(CustomEndpointUiState.INVALID_KEY, repository.state.value.customEndpointValidation)
        assertEquals("", ProviderSettings(context).customBaseUrl)
        assertNull(secrets.customEndpointKey)
    }

    @Test fun uncheckableStatusCanBeSavedManuallyButNetworkFailureCannotSave() {
        repository.setCustomNameInput("Manual gateway")
        repository.setCustomBaseUrlInput("https://manual.example/v1")
        repository.setCustomEndpointKeyInput("optional-key")
        repository.validateAndSaveCustomEndpoint(fakeClient { _, _ -> CustomEndpointModelsResponse(404, "not saved") })!!
            .get(5, TimeUnit.SECONDS)
        assertEquals(CustomEndpointUiState.CANNOT_CHECK, repository.state.value.customEndpointValidation)
        assertEquals("", ProviderSettings(context).customBaseUrl)
        repository.saveCustomEndpointWithoutChecking(publicDnsValidator())
        assertEquals("https://manual.example/v1", ProviderSettings(context).customBaseUrl)
        assertEquals("optional-key", secrets.customEndpointKey)
        assertEquals(CustomEndpointUiState.SAVED_UNVERIFIED, repository.state.value.customEndpointValidation)

        repository.deleteCustomEndpoint()
        repository.setCustomNameInput("Offline gateway")
        repository.setCustomBaseUrlInput("https://offline.example/v1")
        repository.setCustomEndpointKeyInput("offline-key")
        repository.validateAndSaveCustomEndpoint(CustomEndpointModelsClient(
            CustomEndpointModelsTransport { _, _, _ -> throw IOException("offline response error") }, publicDnsValidator(),
        ))!!.get(5, TimeUnit.SECONDS)
        assertEquals(CustomEndpointUiState.NETWORK_ERROR, repository.state.value.customEndpointValidation)
        assertEquals("", ProviderSettings(context).customBaseUrl)
        assertNull(secrets.customEndpointKey)
    }

    @Test fun removingActiveCustomEndpointRestoresPreviousConfiguredProviderAndClearsOnlyCustomSecret() {
        secrets.saveOpenRouterKey("router-key-stays")
        secrets.saveOpenAiApiKey("openai-key-stays")
        repository.setCustomBaseUrlInput("https://custom.example/v1")
        repository.setCustomNameInput("Custom")
        repository.setCustomCompatibilityInput(CustomEndpointCompatibility.OPENAI_CHAT_COMPLETIONS)
        repository.saveCustomEndpointWithoutChecking(publicDnsValidator())
        secrets.saveCustomEndpointKey("custom-key-remove")
        repository.chooseCustomEndpointAsActive()
        assertEquals(ProviderSettings.Provider.CUSTOM, ProviderSettings(context).provider)

        repository.deleteCustomEndpoint()
        assertEquals(ProviderSettings.Provider.OPENROUTER, ProviderSettings(context).provider)
        assertFalse(repository.state.value.customEndpointConfigured)
        assertNull(secrets.customEndpointKey)
        assertEquals("router-key-stays", secrets.openRouterKey)
        assertEquals("openai-key-stays", secrets.openAiApiKey)
    }

    private fun fakeClient(response: (String, Map<String, String>) -> CustomEndpointModelsResponse) =
        CustomEndpointModelsClient(
            CustomEndpointModelsTransport { url, headers, followRedirects ->
                assertFalse(followRedirects)
                response(url, headers)
            },
            publicDnsValidator(),
        )

    private fun publicDnsValidator() = CustomEndpointUrlValidator(CustomEndpointAddressResolver {
        listOf(InetAddress.getByName("8.8.8.8"))
    })
}
