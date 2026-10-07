package com.jarvys.agent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.jarvys.agent.providers.OpenAiModelFields
import com.jarvys.agent.providers.OpenRouterModelField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderModelFieldsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var catalogFlow: MutableStateFlow<List<ModelInfo>>
    private lateinit var previousModels: List<ModelInfo>

    @Before fun installDeterministicOpenAiCatalog() {
        val field = CodexModelCatalog::class.java.getDeclaredField("_models").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        catalogFlow = field.get(CodexModelCatalog) as MutableStateFlow<List<ModelInfo>>
        previousModels = catalogFlow.value
        catalogFlow.value = listOf(
            ModelInfo("gpt-5.4", "GPT-5.4", 1_050_000, 128_000, setOf("text"), setOf("text"),
                listOf(ModelVariant("none", "none", "auto", "medium"), ModelVariant("medium", "medium", "auto", "medium"))),
            ModelInfo("gpt-6-astra", "GPT-6 Astra", 1_050_000, 128_000, setOf("text"), setOf("text"),
                listOf(ModelVariant("low", "low", "auto", "medium"), ModelVariant("medium", "medium", "auto", "medium"))),
            ModelInfo("plain", "Plain", 64_000, 8_000, setOf("text"), setOf("text"), emptyList()),
        )
    }

    @After fun restoreOpenAiCatalog() {
        catalogFlow.value = previousModels
    }

    @Test fun openAiModelSelectionChangesReasoningOptionsAndHidesFieldForModelsWithoutVariants() {
        val modelId = mutableStateOf("gpt-5.4")
        val variant = mutableStateOf("none")
        val models = listOf(
            ModelInfo("gpt-5.4", "GPT-5.4", 1_050_000, 128_000, setOf("text"), setOf("text"),
                listOf(ModelVariant("none", "none", "auto", "medium"), ModelVariant("medium", "medium", "auto", "medium"))),
            ModelInfo("gpt-6-astra", "GPT-6 Astra", 1_050_000, 128_000, setOf("text"), setOf("text"),
                listOf(ModelVariant("low", "low", "auto", "medium"), ModelVariant("medium", "medium", "auto", "medium"))),
            ModelInfo("plain", "Plain", 64_000, 8_000, setOf("text"), setOf("text"), emptyList()),
        )
        compose.setContent {
            MaterialTheme {
                OpenAiModelFields(
                    models = models, modelId = modelId.value, reasoningVariant = variant.value,
                    onModelSelected = { selected ->
                        modelId.value = selected
                        val next = models.first { it.id == selected }
                        if (next.variants.none { it.id == variant.value } && next.variants.isNotEmpty()) {
                            variant.value = next.defaultVariant.id
                        }
                    },
                    onReasoningSelected = { variant.value = it },
                )
            }
        }
        compose.onNodeWithTag("provider-openai-reasoning-dropdown").assertIsDisplayed()
        compose.onNodeWithTag("provider-openai-reasoning-dropdown").performClick()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.provider_default_variant))
            .assertIsDisplayed()
        compose.onAllNodesWithText("none").get(1).performClick()
        compose.onNodeWithTag("provider-openai-model-dropdown").performClick()
        compose.onNodeWithText("GPT-6 Astra").performClick()
        compose.waitForIdle()
        assertEquals("gpt-6-astra", modelId.value)
        assertEquals("medium", variant.value)
        compose.onNodeWithTag("provider-openai-reasoning-dropdown").performClick()
        compose.onNodeWithTag("jarvys-dropdown-filter").performTextInput("none")
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.provider_no_models_found)).assertIsDisplayed()

        compose.onNodeWithTag("provider-openai-model-dropdown").performClick()
        compose.onNodeWithText("Plain").performClick()
        compose.waitForIdle()
        assertEquals("plain", modelId.value)
        assertTrue(models.first { it.id == modelId.value }.variants.isEmpty())
        assertTrue(compose.onAllNodesWithTag("provider-openai-reasoning-dropdown").fetchSemanticsNodes().isEmpty())
    }

    @Test fun openRouterRetainsUnknownCurrentValueAndCanEnterManualIdWithoutCatalog() {
        val modelId = mutableStateOf("vendor/not-listed")
        compose.setContent {
            MaterialTheme {
                OpenRouterModelField(modelId.value, emptyList(), { modelId.value = it }, Modifier.testTag("router-fields"))
            }
        }
        compose.onNodeWithText("vendor/not-listed").assertIsDisplayed()
        compose.onNodeWithTag("provider-openrouter-model-dropdown").performClick()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.provider_model_other)).performClick()
        compose.onNodeWithTag("provider-openrouter-custom-model").performTextInput("vendor/manual-id/")
        compose.waitForIdle()
        assertEquals("vendor/manual-id/vendor/not-listed", modelId.value)
        compose.onNodeWithText("vendor/manual-id/vendor/not-listed").assertIsDisplayed()
    }

    @Test fun openRouterCanSelectKnownCatalogModel() {
        val modelId = mutableStateOf("openrouter/free")
        compose.setContent {
            MaterialTheme {
                OpenRouterModelField(
                    modelId.value,
                    listOf(OpenRouterModelInfo("vendor/known", "Known Model", 65_536, 8_192)),
                    { modelId.value = it },
                )
            }
        }
        compose.onNodeWithTag("provider-openrouter-model-dropdown").performClick()
        compose.onNodeWithText("Known Model").performClick()
        compose.waitForIdle()
        assertEquals("vendor/known", modelId.value)
    }

    @Test fun quickChatSheetUsesProviderModelAndReasoningCombosWithImmediatePersistence() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("jarvys_provider_settings", 0).edit().clear().commit()
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
            .set(null, SecretStore(context.getSharedPreferences("r1b-provider-secrets", 0)))
        com.jarvys.agent.providers.ProvidersRepository::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(null, null)
        val repository = com.jarvys.agent.providers.ProvidersRepository.get(context)
        repository.selectQuickModel(ProviderSettings.Provider.OPENAI_CODEX, "gpt-5.4", "medium")
        val selectedValues = mutableListOf<Triple<ProviderSettings.Provider, String, String>>()
        compose.setContent {
            MaterialTheme {
                QuickModelDialog(
                    providerName = ProviderSettings.Provider.OPENAI_CODEX.name,
                    openAiModel = "gpt-5.4",
                    openAiApiModel = "gpt-4.1",
                    openRouterModel = "openrouter/free",
                    reasoningVariant = "medium",
                    onDismiss = {},
                    onSelectionChange = { provider, model, variant ->
                        selectedValues += Triple(provider, model, variant)
                        repository.selectQuickModel(provider, model, variant)
                    },
                )
            }
        }
        compose.onNodeWithTag("quick-provider-dropdown").assertIsDisplayed()
        compose.onNodeWithTag("provider-openai-model-dropdown").assertIsDisplayed()
        compose.onNodeWithTag("provider-openai-reasoning-dropdown").assertIsDisplayed()
        compose.onAllNodesWithTag("jarvys-dropdown-field").get(1).performScrollTo().performClick()
        compose.onNodeWithTag("jarvys-dropdown-filter").assertIsDisplayed()
        compose.onNodeWithText("GPT-6 Astra").performClick()
        compose.waitForIdle()
        assertTrue("model selection callback was not persisted: $selectedValues", selectedValues.any { it.second == "gpt-6-astra" })
        val settings = ProviderSettings(context)
        assertEquals(ProviderSettings.Provider.OPENAI_CODEX, settings.provider)
        assertEquals("gpt-6-astra", settings.model)
        assertEquals("medium", settings.reasoningVariant)

        compose.onAllNodesWithTag("jarvys-dropdown-field").get(2).performClick()
        compose.onNodeWithText("low").performClick()
        compose.waitForIdle()
        assertEquals("low", settings.reasoningVariant)

        compose.onAllNodesWithTag("jarvys-dropdown-field").get(0).performClick()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.provider_openrouter_name)).performClick()
        compose.waitForIdle()
        assertEquals(ProviderSettings.Provider.OPENROUTER, settings.provider)
        compose.onNodeWithTag("provider-openrouter-model-dropdown").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("provider-openai-reasoning-dropdown").fetchSemanticsNodes().isEmpty())

        compose.onAllNodesWithTag("jarvys-dropdown-field").get(0).performClick()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.provider_openai_chatgpt_account_option))
            .assertIsDisplayed()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.provider_openai_method_api_key))
            .assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(ProviderSettings.Provider.OPENAI_API, settings.provider)
        assertEquals(ProviderSettings.OpenAiAuthMethod.API_KEY, settings.openAiAuthMethod)
        assertEquals("gpt-4.1", settings.model)
        compose.onNodeWithTag("provider-openai-api-model-dropdown").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("provider-openai-reasoning-dropdown").fetchSemanticsNodes().isEmpty())
    }
}
