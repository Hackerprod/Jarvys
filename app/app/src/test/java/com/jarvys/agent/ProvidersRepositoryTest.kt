package com.jarvys.agent

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvidersRepositoryTest {
    private lateinit var context: android.app.Application
    private lateinit var repository: com.jarvys.agent.providers.ProvidersRepository
    private lateinit var testSecretPreferences: android.content.SharedPreferences

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testSecretPreferences = context.getSharedPreferences("r1-provider-repository-secrets", Context.MODE_PRIVATE)
        testSecretPreferences.edit().clear().commit()
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
            .set(null, SecretStore(testSecretPreferences))
        val preferences = context.getSharedPreferences("jarvys_provider_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        resetRepositorySingleton()
        repository = com.jarvys.agent.providers.ProvidersRepository.get(context)
    }

    @Test fun serviceAndOpenRouterCredentialsRetainTheirExistingEncryptedStorageAndActiveModelRules() {
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENAI_CODEX)
        repository.selectOpenRouterModel("vendor/model")
        assertEquals("vendor/model", repository.state.value.openRouterModel)
        assertEquals(ProviderSettings.Provider.OPENAI_CODEX, repository.state.value.activeProvider)

        repository.setOpenRouterKeyInput("router-secret")
        repository.saveOpenRouterKey()
        assertTrue(repository.state.value.openRouterConnected)
        assertEquals("router-secret", SecretStore.get(context).openRouterKey)
        repository.deleteOpenRouterKey()
        assertFalse(repository.state.value.openRouterConnected)
        assertEquals(ProviderSettings.Provider.OPENAI_CODEX, repository.state.value.activeProvider)

        repository.setExaKeyInput("exa-secret")
        repository.saveExaKey()
        assertTrue(repository.state.value.exaConnected)
        assertEquals("exa-secret", SecretStore.get(context).getConnectorSecret("web_search", "exa_api_key"))
        repository.deleteExaKey()
        assertFalse(repository.state.value.exaConnected)
    }

    @Test fun selectingProvidersUpdatesTheActiveStateWithoutTouchingAuthenticationClients() {
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENROUTER)
        assertEquals(ProviderSettings.Provider.OPENROUTER, repository.state.value.activeProvider)
        assertEquals("openrouter/free", repository.state.value.activeModel)
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENAI_CODEX)
        assertEquals(ProviderSettings.Provider.OPENAI_CODEX, repository.state.value.activeProvider)
        assertTrue(repository.state.value.activeModel.isNotBlank())
    }

    @Test fun openAiSessionConnectionAndDisconnectStateRemainInTheRepository() {
        assertFalse(repository.state.value.codexConnected)
        SecretStore.get(context).saveCodexTokens("access", "refresh", System.currentTimeMillis() + 60_000, "account")
        repository.refresh()
        assertTrue(repository.state.value.codexConnected)
        assertEquals("account", repository.state.value.codexAccountId)
        repository.disconnectCodex()
        assertFalse(repository.state.value.codexConnected)
    }

    @Test fun openAiModelChangesKeepSupportedReasoningAndNormalizeInvalidVariantsToDefault() {
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENAI_CODEX)
        repository.selectQuickModel(ProviderSettings.Provider.OPENAI_CODEX, "gpt-5.4", "none")
        repository.selectOpenAiModel("gpt-6-astra")
        assertEquals("gpt-6-astra", repository.state.value.openAiModel)
        assertEquals("medium", repository.state.value.openAiReasoningVariant)
        assertEquals("medium", context.getSharedPreferences("jarvys_provider_settings", 0)
            .getString("reasoning_variant_openai", null))

        repository.selectQuickModel(ProviderSettings.Provider.OPENAI_CODEX, "gpt-5.4", "not-a-variant")
        assertEquals("gpt-5.4", repository.state.value.openAiModel)
        assertEquals("medium", repository.state.value.openAiReasoningVariant)
    }

    @Test fun unknownOpenRouterModelPersistsAcrossRepositoryRecreation() {
        repository.selectOpenRouterModel("vendor/custom-model")
        assertEquals("vendor/custom-model", repository.state.value.openRouterModel)
        resetRepositorySingleton()
        repository = com.jarvys.agent.providers.ProvidersRepository.get(context)
        assertEquals("vendor/custom-model", repository.state.value.openRouterModel)
    }

    @Test fun inactiveOpenAiModelStillNormalizesAgainstCatalogWithoutChangingActiveProvider() {
        repository.chooseActiveProvider(ProviderSettings.Provider.OPENROUTER)
        ProviderSettings(context).setOpenAiModel("missing/openai-model")
        repository.refresh()
        repository.reconcileOpenAiModels(CodexModelCatalog.currentModels())
        assertEquals("gpt-5.4", repository.state.value.openAiModel)
        assertEquals(ProviderSettings.Provider.OPENROUTER, repository.state.value.activeProvider)
    }

    private fun resetRepositorySingleton() {
        com.jarvys.agent.providers.ProvidersRepository::class.java
            .getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
}
