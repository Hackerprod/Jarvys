package com.jarvys.agent

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.providers.ProvidersExaDetailContent
import com.jarvys.agent.providers.ProvidersScreen
import com.jarvys.agent.providers.ProvidersUiState
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSearchSettingsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerListAndExaDetailRemainReadableInBothLanguagesAtFontScaleOneAndTwo() {
        val base = RuntimeEnvironment.getApplication()
        val language = mutableStateOf("en")
        val fontScale = mutableStateOf(1f)
        val showExaDetail = mutableStateOf(false)
        val state = ProvidersUiState(
            activeProvider = ProviderSettings.Provider.OPENAI_CODEX,
            activeModel = "gpt-5.4", activeReasoningVariant = "medium",
            openAiModel = "gpt-5.4", openAiReasoningVariant = "medium",
            openRouterModel = "openrouter/free", codexConnected = false, codexAccountId = null,
            codexOAuthInProgress = false, openRouterConnected = false, exaConnected = false,
        )
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
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(900.dp)) {
                        if (showExaDetail.value) ProvidersExaDetailContent(state, {}, {}, {})
                        else ProvidersScreen(state, {}, {}, {}, {})
                    }
                }
            }
        }

        for (locale in listOf("en", "es")) {
            language.value = locale
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale(locale))
            })
            for (scale in listOf(1f, 2f)) {
                fontScale.value = scale
                showExaDetail.value = false
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.settings_providers)).assertIsDisplayed()
                compose.onNodeWithText(localized.getString(R.string.providers_services_heading)).assertIsDisplayed()
                compose.onNodeWithText(localized.getString(R.string.provider_openai_name)).assertIsDisplayed()
                compose.onNodeWithText(localized.getString(R.string.provider_openrouter_name)).assertIsDisplayed()
                compose.onNodeWithText(localized.getString(R.string.web_search_exa_name)).assertIsDisplayed()
                val openAiBounds = compose.onNodeWithText(localized.getString(R.string.provider_openai_name)).fetchSemanticsNode().boundsInRoot
                val openRouterBounds = compose.onNodeWithText(localized.getString(R.string.provider_openrouter_name)).fetchSemanticsNode().boundsInRoot
                assertTrue("provider rows overlap at $locale/$scale", openAiBounds.bottom <= openRouterBounds.top
                    || openRouterBounds.bottom <= openAiBounds.top)

                showExaDetail.value = true
                compose.waitForIdle()
                val privacyNode = compose.onNodeWithText(localized.getString(R.string.web_search_exa_privacy))
                    .performScrollTo().assertIsDisplayed()
                val keyNode = compose.onNodeWithText(localized.getString(R.string.web_search_exa_key_label))
                    .performScrollTo().assertIsDisplayed()
                assertTrue("Exa privacy and key controls overlap at $locale/$scale",
                    privacyNode.fetchSemanticsNode().boundsInRoot.bottom <= keyNode.fetchSemanticsNode().boundsInRoot.top)
            }
        }
    }
}
