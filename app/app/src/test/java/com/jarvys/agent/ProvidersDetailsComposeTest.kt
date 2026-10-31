package com.jarvys.agent

import android.content.res.Configuration
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.providers.ProvidersExaDetailContent
import com.jarvys.agent.providers.ProvidersOpenAiDetailContent
import com.jarvys.agent.providers.ProvidersOpenRouterDetailContent
import com.jarvys.agent.providers.ProvidersUiState
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProvidersDetailsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allProviderAndServiceDetailsFitEnglishAndSpanishAtFontScaleOneAndTwo() {
        val base = RuntimeEnvironment.getApplication()
        val language = mutableStateOf("en")
        val scale = mutableStateOf(1f)
        val page = mutableStateOf("openai")
        val state = mutableStateOf(ProvidersUiState(
            activeProvider = ProviderSettings.Provider.OPENAI_CODEX,
            activeModel = "gpt-5.4", activeReasoningVariant = "medium",
            openAiModel = "gpt-5.4", openAiReasoningVariant = "medium",
            openRouterModel = "vendor/model", codexConnected = false, codexAccountId = null,
            codexOAuthInProgress = false, openRouterConnected = false, exaConnected = false,
        ))
        val model = ModelInfo("gpt-5.4", "GPT-5.4", 128_000, 16_000, setOf("text"), setOf("text"),
            listOf(ModelVariant("medium", "medium", "auto", "medium")))
        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(language.value) {
                base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    setLocale(Locale(language.value))
                })
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, scale.value),
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(1000.dp)) {
                        when (page.value) {
                            "openai" -> ProvidersOpenAiDetailContent(state.value, listOf(model), {}, {}, {}, {}, {}, {})
                            "openrouter" -> ProvidersOpenRouterDetailContent(state.value, emptyList(), {}, {}, {}, {}, {})
                            else -> ProvidersExaDetailContent(state.value, {}, {}, {})
                        }
                    }
                }
            }
        }

        for (locale in listOf("en", "es")) {
            language.value = locale
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale(locale))
            })
            for (fontScale in listOf(1f, 2f)) {
                scale.value = fontScale
                compose.waitForIdle()

                page.value = "openai"
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_signin_chatgpt))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("provider-openai-model-dropdown")
                    .performScrollTo().assertIsDisplayed()
                val reasoningPicker = compose.onNodeWithTag("provider-openai-reasoning-dropdown")
                    .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val modelPickerAfterScroll = compose.onNodeWithTag("provider-openai-model-dropdown")
                    .fetchSemanticsNode().boundsInRoot
                val signInBounds = compose.onNodeWithText(localized.getString(R.string.provider_signin_chatgpt))
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("OpenAI detail controls overlap at $locale/$fontScale", signInBounds.bottom <= modelPickerAfterScroll.top)
                assertTrue("OpenAI dropdowns overlap at $locale/$fontScale: $modelPickerAfterScroll / $reasoningPicker",
                    modelPickerAfterScroll.bottom <= reasoningPicker.top)
                compose.onAllNodesWithText(localized.getString(R.string.provider_cancel_signin)).assertCountEquals(0)
                compose.onAllNodesWithText(localized.getString(R.string.provider_disconnect_chatgpt)).assertCountEquals(0)
                state.value = state.value.copy(codexOAuthInProgress = true)
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_waiting_chatgpt)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(localized.getString(R.string.provider_cancel_signin)).performScrollTo().assertIsDisplayed()
                compose.onAllNodesWithText(localized.getString(R.string.provider_signin_chatgpt)).assertCountEquals(0)
                state.value = state.value.copy(codexConnected = true, codexOAuthInProgress = false, codexAccountId = "acct")
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_disconnect_chatgpt)).performScrollTo().assertIsDisplayed()
                compose.onAllNodesWithText(localized.getString(R.string.provider_signin_chatgpt)).assertCountEquals(0)
                state.value = state.value.copy(codexConnected = false, codexAccountId = null)

                page.value = "openrouter"
                compose.waitForIdle()
                val modelField = compose.onNodeWithTag("provider-openrouter-model-dropdown")
                    .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val keyField = compose.onNodeWithText(localized.getString(R.string.provider_openrouter_key_label))
                    .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("OpenRouter detail fields overlap at $locale/$fontScale", modelField.bottom <= keyField.top)

                page.value = "exa"
                compose.waitForIdle()
                val privacy = compose.onNodeWithText(localized.getString(R.string.web_search_exa_privacy))
                    .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val exaKey = compose.onNodeWithText(localized.getString(R.string.web_search_exa_key_label))
                    .performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Exa detail controls overlap at $locale/$fontScale", privacy.bottom <= exaKey.top)
            }
        }
    }
}
