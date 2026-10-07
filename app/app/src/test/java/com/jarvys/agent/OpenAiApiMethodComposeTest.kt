package com.jarvys.agent

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.providers.OpenAiApiKeyUiState
import com.jarvys.agent.providers.OpenAiApiModelField
import com.jarvys.agent.providers.ProvidersOpenAiDetailContent
import com.jarvys.agent.providers.ProvidersUiState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAiApiMethodComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun accessMethodControlsSwitchContentAndApiKeyActionsStayDistinct() {
        val base = compose.activity
        val state = mutableStateOf(baseState())
        val apiModel = ModelInfo("gpt-4.1", "GPT-4.1", 1_047_576, 32_768,
            setOf("text", "image"), setOf("text"), emptyList())
        var validationCalls = 0
        var useActiveCalls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(1000.dp)) {
                    ProvidersOpenAiDetailContent(
                        state = state.value,
                        models = listOf(ModelInfo("gpt-5.4", "GPT-5.4", 128_000, 16_000,
                            setOf("text"), setOf("text"), listOf(ModelVariant("medium", "medium", "auto", "medium")))),
                        onSignIn = {}, onCancelSignIn = {}, onDisconnect = {},
                        onSelectModel = {}, onSelectReasoning = {},
                        onUseActive = {
                            useActiveCalls++
                            val target = if (state.value.openAiAuthMethod == ProviderSettings.OpenAiAuthMethod.API_KEY)
                                ProviderSettings.Provider.OPENAI_API else ProviderSettings.Provider.OPENAI_CODEX
                            state.value = state.value.copy(activeProvider = target)
                        },
                        apiModels = listOf(apiModel),
                        onAuthMethodChange = { state.value = state.value.copy(openAiAuthMethod = it) },
                        onBeginDeviceCode = { state.value = state.value.copy(
                            codexDeviceCode = com.jarvys.agent.providers.CodexDeviceCodeState.RequestingCode) },
                        onCancelDeviceCode = { state.value = state.value.copy(
                            codexDeviceCode = com.jarvys.agent.providers.CodexDeviceCodeState.Idle) },
                        onSelectApiModel = { state.value = state.value.copy(openAiApiModel = it) },
                        onApiKeyChange = { state.value = state.value.copy(openAiApiKeyInput = it, openAiApiKeyEditing = true) },
                        onValidateApiKey = {
                            validationCalls++
                            state.value = state.value.copy(openAiApiKeyValidation = OpenAiApiKeyUiState.VALIDATING)
                        },
                        onReplaceApiKey = { state.value = state.value.copy(openAiApiKeyEditing = true, openAiApiKeyInput = "") },
                        onCancelApiKeyEdit = { state.value = state.value.copy(openAiApiKeyEditing = false,
                            openAiApiKeyInput = "", openAiApiKeyValidation = OpenAiApiKeyUiState.IDLE) },
                        onDeleteApiKey = { state.value = state.value.copy(openAiApiKeyConnected = false,
                            openAiApiKeyEditing = false, openAiApiKeyInput = "", openAiApiKeyValidation = OpenAiApiKeyUiState.IDLE) },
                    )
                }
            }
        }

        compose.onNodeWithText(base.getString(R.string.provider_signin_chatgpt)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-openai-auth-method").assertIsDisplayed()
        compose.onAllNodesWithTag("provider-device-code-cancel").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText(base.getString(R.string.provider_use_openai_active)).performScrollTo().assertIsNotEnabled()

        selectMethod(R.string.provider_openai_method_device)
        assertEquals(ProviderSettings.OpenAiAuthMethod.DEVICE_CODE, state.value.openAiAuthMethod)
        compose.onNodeWithTag("provider-start-device-code").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(base.getString(R.string.provider_signin_chatgpt)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

        selectMethod(R.string.provider_openai_method_api_key)
        assertEquals(ProviderSettings.OpenAiAuthMethod.API_KEY, state.value.openAiAuthMethod)
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_label)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-openai-api-model-dropdown").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("provider-openai-reasoning-dropdown").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(base.getString(R.string.provider_use_openai_active)).performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("provider-openai-api-key").performScrollTo().performTextInput("sk-r3b-test-secret")
        compose.onNodeWithTag("provider-openai-api-key-save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, validationCalls)
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_validating)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-openai-api-key-save").assertIsNotEnabled()

        state.value = state.value.copy(openAiApiKeyValidation = OpenAiApiKeyUiState.INVALID_KEY)
        compose.waitForIdle()
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_invalid)).performScrollTo().assertIsDisplayed()
        state.value = state.value.copy(openAiApiKeyValidation = OpenAiApiKeyUiState.UNAVAILABLE)
        compose.waitForIdle()
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_validation_failed)).performScrollTo().assertIsDisplayed()

        state.value = state.value.copy(openAiApiKeyConnected = true, openAiApiKeyInput = "",
            openAiApiKeyEditing = false, openAiApiKeyValidation = OpenAiApiKeyUiState.SAVED)
        compose.waitForIdle()
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_saved)).performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("sk-r3b-test-secret").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_replace)).performScrollTo().performClick()
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_label)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.provider_cancel)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(base.getString(R.string.provider_openai_api_key_saved)).performScrollTo().assertIsDisplayed()

        state.value = state.value.copy(openAiAuthMethod = ProviderSettings.OpenAiAuthMethod.API_KEY,
            activeProvider = ProviderSettings.Provider.OPENAI_CODEX)
        compose.onNodeWithText(base.getString(R.string.provider_use_openai_active)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(ProviderSettings.Provider.OPENAI_API, state.value.activeProvider)
        assertEquals(1, useActiveCalls)
    }

    @Test fun accessMethodAndApiKeyControlsFitBothLocalesAtFontScaleOneAndTwo() {
        val app = RuntimeEnvironment.getApplication()
        val language = mutableStateOf("en")
        val fontScale = mutableStateOf(1f)
        val state = mutableStateOf(baseState().copy(openAiAuthMethod = ProviderSettings.OpenAiAuthMethod.API_KEY,
            openAiApiKeyConnected = true, openAiApiKeyValidation = OpenAiApiKeyUiState.SAVED))
        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(language.value) {
                app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                    setLocale(Locale(language.value))
                })
            }
            CompositionLocalProvider(LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    ProvidersOpenAiDetailContent(
                        state = state.value,
                        models = emptyList(),
                        onSignIn = {}, onCancelSignIn = {}, onDisconnect = {}, onSelectModel = {},
                        onSelectReasoning = {}, onUseActive = {},
                        apiModels = listOf(ModelInfo("gpt-4.1", "GPT-4.1", 1_047_576, 32_768,
                            setOf("text"), setOf("text"), emptyList())),
                    )
                }
            }
        }
        for (locale in listOf("en", "es")) {
            language.value = locale
            val localized = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale(locale))
            })
            for (scale in listOf(1f, 2f)) {
                fontScale.value = scale
                compose.waitForIdle()
                compose.onNodeWithTag("provider-openai-auth-method").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("provider-openai-api-model-dropdown").performScrollTo().assertIsDisplayed()
                val savedKey = compose.onNodeWithText(localized.getString(R.string.provider_openai_api_key_saved))
                    .performScrollTo().fetchSemanticsNode().boundsInRoot
                val access = compose.onNodeWithTag("provider-openai-auth-method").fetchSemanticsNode().boundsInRoot
                val activeModel = compose.onNodeWithTag("provider-openai-api-model-dropdown").fetchSemanticsNode().boundsInRoot
                assertTrue("Access selector overlaps model at $locale/fontScale=$scale", access.bottom <= activeModel.top)
                assertTrue("Key status overlaps model selector at $locale/fontScale=$scale", savedKey.bottom <= activeModel.top)
            }
        }
    }

    @Test fun apiModelComboRetainsCurrentModelAndOffersManualIdWhenCatalogIsEmpty() {
        val modelId = mutableStateOf("vendor/current-model")
        compose.setContent {
            MaterialTheme {
                OpenAiApiModelField(modelId.value, emptyList(), { modelId.value = it })
            }
        }
        compose.onNodeWithText("vendor/current-model").assertIsDisplayed()
        compose.onNodeWithTag("provider-openai-api-model-dropdown").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_model_other)).performClick()
        compose.onNodeWithTag("provider-openai-api-custom-model").performTextInput("manual/")
        compose.waitForIdle()
        assertEquals("manual/vendor/current-model", modelId.value)
        compose.onNodeWithText("manual/vendor/current-model").assertIsDisplayed()
    }

    @Test fun apiModelComboSelectsAKnownToolCapableCatalogModel() {
        val modelId = mutableStateOf("gpt-4.1")
        val models = listOf(
            ModelInfo("gpt-4.1", "GPT-4.1", 1_047_576, 32_768, setOf("text"), setOf("text"), emptyList()),
            ModelInfo("gpt-4o", "GPT-4o", 128_000, 16_384, setOf("text", "image"), setOf("text"), emptyList()),
        )
        compose.setContent {
            MaterialTheme {
                OpenAiApiModelField(modelId.value, models, { modelId.value = it })
            }
        }
        compose.onNodeWithTag("provider-openai-api-model-dropdown").performClick()
        compose.onNodeWithText("GPT-4o").performClick()
        compose.waitForIdle()
        assertEquals("gpt-4o", modelId.value)
    }

    private fun selectMethod(optionRes: Int) {
        compose.onAllNodesWithTag("jarvys-dropdown-field").get(0).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(optionRes)).performClick()
        compose.waitForIdle()
    }

    private fun baseState() = ProvidersUiState(
        activeProvider = ProviderSettings.Provider.OPENROUTER,
        activeModel = "openrouter/free", activeReasoningVariant = "medium",
        openAiModel = "gpt-5.4", openAiReasoningVariant = "medium", openRouterModel = "openrouter/free",
        codexConnected = false, codexAccountId = null, codexOAuthInProgress = false,
        openRouterConnected = false, exaConnected = false,
        openAiApiModel = "gpt-4.1",
    )
}
