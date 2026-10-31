package com.jarvys.agent

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
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
import com.jarvys.agent.providers.CustomEndpointCompatibility
import com.jarvys.agent.providers.CustomEndpointModel
import com.jarvys.agent.providers.CustomEndpointUiState
import com.jarvys.agent.providers.ProvidersCustomEndpointDetailContent
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
class CustomEndpointComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun customDetailShowsSinglePrimaryProbeAndDistinctManualSaveUseAndRemoveActions() {
        val state = mutableStateOf(ProvidersUiState(
            activeProvider = ProviderSettings.Provider.OPENROUTER,
            activeModel = "openrouter/free", activeReasoningVariant = "medium",
            openAiModel = "gpt-5.4", openAiReasoningVariant = "medium", openRouterModel = "openrouter/free",
            codexConnected = false, codexAccountId = null, codexOAuthInProgress = false,
            openRouterConnected = false, exaConnected = false,
        ))
        var probes = 0
        var activeCalls = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(1100.dp)) {
                    customContent(state.value,
                        onName = { state.value = state.value.copy(customNameInput = it) },
                        onUrl = { state.value = state.value.copy(customBaseUrlInput = it) },
                        onCompat = { state.value = state.value.copy(customCompatibilityInput = it) },
                        onModel = { state.value = state.value.copy(customModelInput = it) },
                        onKey = { state.value = state.value.copy(customEndpointKeyInput = it) },
                        onProbe = { probes++; state.value = state.value.copy(customEndpointValidation = CustomEndpointUiState.VALIDATING) },
                        onSaveUnchecked = { state.value = state.value.copy(customEndpointConfigured = true,
                            customEndpointValidation = CustomEndpointUiState.SAVED_UNVERIFIED) },
                        onUse = { activeCalls++ },
                        onDelete = { state.value = state.value.copy(customEndpointConfigured = false) },
                    )
                }
            }
        }
        compose.onNodeWithTag("custom-endpoint-name").assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-url").assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-compatibility").assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-api-key").assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-model-dropdown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.custom_endpoint_compat_openai_chat_completions))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.custom_endpoint_active_requires_url))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-use-active-disabled").assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithTag("custom-endpoint-remove").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("custom-endpoint-url").performTextInput("https://gateway.example/v1")
        compose.onNodeWithTag("custom-endpoint-api-key").performTextInput("sk-custom-secret")
        compose.onNodeWithTag("custom-endpoint-save-test").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, probes)
        compose.onNodeWithTag("custom-endpoint-save-test").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.custom_endpoint_validating)).performScrollTo().assertIsDisplayed()

        state.value = state.value.copy(customEndpointValidation = CustomEndpointUiState.CANNOT_CHECK,
            customEndpointHttpStatus = 404)
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.custom_endpoint_cannot_check)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-save-unchecked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-save-unchecked").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.custom_endpoint_saved_unverified)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("custom-endpoint-use-active").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, activeCalls)
        compose.onNodeWithTag("custom-endpoint-remove").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.custom_endpoint_remove_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_cancel)).performClick()
        compose.waitForIdle()
        assertTrue(state.value.customEndpointConfigured)
    }

    @Test fun customDetailLabelsAndInputsStaySeparatedAtFontScaleOneAndTwoInBothLocales() {
        val app = RuntimeEnvironment.getApplication()
        val locale = mutableStateOf("en")
        val fontScale = mutableStateOf(1f)
        val state = mutableStateOf(ProvidersUiState(
            activeProvider = ProviderSettings.Provider.OPENROUTER,
            activeModel = "openrouter/free", activeReasoningVariant = "medium",
            openAiModel = "gpt-5.4", openAiReasoningVariant = "medium", openRouterModel = "openrouter/free",
            codexConnected = false, codexAccountId = null, codexOAuthInProgress = false,
            openRouterConnected = false, exaConnected = false,
            customName = "Public endpoint", customNameInput = "Public endpoint",
            customBaseUrl = "https://api.example/v1", customBaseUrlInput = "https://api.example/v1",
            customEndpointConfigured = true,
            customModels = listOf(CustomEndpointModel("model", "Model", 32_000, 4_000)),
            customModel = "model", customModelInput = "model",
        ))
        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(locale.value) {
                app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                    setLocale(Locale(locale.value))
                })
            }
            CompositionLocalProvider(LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    ProvidersCustomEndpointDetailContent(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        for (language in listOf("en", "es")) {
            locale.value = language
            val localized = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale(language))
            })
            for (scale in listOf(1f, 2f)) {
                fontScale.value = scale
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.custom_endpoint_heading)).performScrollTo()
                compose.onNodeWithTag("custom-endpoint-name").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("custom-endpoint-compatibility").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("custom-endpoint-api-key").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("custom-endpoint-model-dropdown").performScrollTo().assertIsDisplayed()
                val heading = compose.onNodeWithText(localized.getString(R.string.custom_endpoint_heading))
                    .performScrollTo().fetchSemanticsNode().boundsInRoot
                val name = compose.onNodeWithTag("custom-endpoint-name").performScrollTo().assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                val url = compose.onNodeWithTag("custom-endpoint-url").performScrollTo().assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Name overlaps URL at $language/fontScale=$scale", name.bottom <= url.top)
                assertTrue("Heading is below name at $language/fontScale=$scale", heading.top < name.top)
            }
        }
    }

    @Composable
    private fun customContent(
        state: ProvidersUiState,
        onName: (String) -> Unit,
        onUrl: (String) -> Unit,
        onCompat: (CustomEndpointCompatibility) -> Unit,
        onModel: (String) -> Unit,
        onKey: (String) -> Unit,
        onProbe: () -> Unit,
        onSaveUnchecked: () -> Unit,
        onUse: () -> Unit,
        onDelete: () -> Unit,
    ) = ProvidersCustomEndpointDetailContent(state, onName, onUrl, onCompat, onModel, onKey,
        onProbe, onSaveUnchecked, onUse, onDelete)
}
