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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.providers.CodexDeviceCodeState
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
class CodexDeviceCodeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deviceCodeStatesExposeCorrectActionsAndRemainLegibleInEnglishAndSpanishAtFontScaleTwo() {
        val base = RuntimeEnvironment.getApplication()
        val locale = mutableStateOf("en")
        val fontScale = mutableStateOf(1f)
        val state = mutableStateOf(ProvidersUiState(
            activeProvider = ProviderSettings.Provider.OPENAI_CODEX,
            activeModel = "gpt-5.4", activeReasoningVariant = "medium",
            openAiModel = "gpt-5.4", openAiReasoningVariant = "medium",
            openRouterModel = "openrouter/free", codexConnected = false, codexAccountId = null,
            codexOAuthInProgress = false, openRouterConnected = false, exaConnected = false,
            openAiAuthMethod = ProviderSettings.OpenAiAuthMethod.DEVICE_CODE,
        ))
        val counters = mutableStateOf(0)
        val copied = mutableStateOf("")
        val opened = mutableStateOf("")
        val model = ModelInfo("gpt-5.4", "GPT-5.4", 128_000, 16_000, setOf("text"), setOf("text"),
            listOf(ModelVariant("medium", "medium", "auto", "medium")))
        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(locale.value) {
                base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    setLocale(Locale(locale.value))
                })
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(1000.dp)) {
                        ProvidersOpenAiDetailContent(
                            state = state.value,
                            models = listOf(model),
                            onSignIn = { counters.value++ },
                            onCancelSignIn = { counters.value++ },
                            onDisconnect = { counters.value++ },
                            onSelectModel = {},
                            onSelectReasoning = {},
                            onUseActive = {},
                            onBeginDeviceCode = {
                                counters.value++
                                state.value = state.value.copy(codexDeviceCode = CodexDeviceCodeState.RequestingCode)
                            },
                            onCancelDeviceCode = {
                                counters.value++
                                state.value = state.value.copy(codexDeviceCode = CodexDeviceCodeState.Idle)
                            },
                            onCopyDeviceCode = { copied.value = it },
                            onOpenDevicePage = { opened.value = it },
                        )
                    }
                }
            }
        }

        for (language in listOf("en", "es")) {
            locale.value = language
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale(language))
            })
            for (scale in listOf(1f, 2f)) {
                fontScale.value = scale
                state.value = state.value.copy(codexConnected = false, codexAccountId = null,
                    codexOAuthInProgress = false, codexDeviceCode = CodexDeviceCodeState.Idle)
                compose.waitForIdle()

                compose.onNodeWithTag("provider-start-device-code").performScrollTo().assertIsDisplayed()
                assertTrue(compose.onAllNodesWithTag("provider-device-code-cancel").fetchSemanticsNodes().isEmpty())
                assertTrue(compose.onAllNodesWithText(localized.getString(R.string.provider_disconnect_chatgpt))
                    .fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("provider-start-device-code").performClick()
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_device_code_requesting))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("provider-device-code-cancel").performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(CodexDeviceCodeState.Idle, state.value.codexDeviceCode)

                state.value = state.value.copy(codexDeviceCode = CodexDeviceCodeState.ExchangingCode(
                    "ABCD-EFGH", "https://auth.openai.com/codex/device", System.currentTimeMillis() + 900_000L))
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_device_code_exchanging))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("provider-device-code-value").assertTextEquals("ABCD-EFGH")
                compose.onNodeWithTag("provider-device-code-cancel").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("provider-device-code-cancel").performClick()
                compose.waitForIdle()
                assertEquals(CodexDeviceCodeState.Idle, state.value.codexDeviceCode)

                val verification = "https://auth.openai.com/codex/device"
                state.value = state.value.copy(codexDeviceCode = CodexDeviceCodeState.WaitingForCode(
                    "ABCD-EFGH", verification, System.currentTimeMillis() + 15 * 60 * 1000L))
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_device_code_instruction))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("provider-device-code-value").assertTextEquals("ABCD-EFGH")
                compose.onNodeWithTag("provider-device-code-cancel").performScrollTo()
                val codeBounds = compose.onNodeWithTag("provider-device-code-value").fetchSemanticsNode().boundsInRoot
                val copyBounds = compose.onNodeWithTag("provider-device-code-copy").fetchSemanticsNode().boundsInRoot
                val openBounds = compose.onNodeWithTag("provider-device-code-open").fetchSemanticsNode().boundsInRoot
                val cancelBounds = compose.onNodeWithTag("provider-device-code-cancel").fetchSemanticsNode().boundsInRoot
                assertTrue("code overlaps Copy at $language/fontScale=$scale", codeBounds.bottom <= copyBounds.top)
                assertTrue("Copy overlaps Open at $language/fontScale=$scale", copyBounds.bottom <= openBounds.top)
                assertTrue("Open overlaps Cancel at $language/fontScale=$scale", openBounds.bottom <= cancelBounds.top)
                compose.onNodeWithTag("provider-device-code-copy").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithTag("provider-device-code-open").performScrollTo().assertIsDisplayed().performClick()
                assertEquals("ABCD-EFGH", copied.value)
                assertEquals(verification, opened.value)
                compose.onNodeWithTag("provider-device-code-cancel").performScrollTo().assertIsDisplayed()
                assertTrue(compose.onAllNodesWithText(localized.getString(R.string.provider_signin_chatgpt))
                    .fetchSemanticsNodes().isEmpty())
                assertTrue(compose.onAllNodesWithText(localized.getString(R.string.provider_disconnect_chatgpt))
                    .fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("provider-device-code-cancel").performClick()
                compose.waitForIdle()
                assertEquals(CodexDeviceCodeState.Idle, state.value.codexDeviceCode)

                state.value = state.value.copy(codexDeviceCode = CodexDeviceCodeState.Failed(
                    R.string.provider_device_code_expired))
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_device_code_expired))
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("provider-device-code-retry").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_device_code_requesting))
                    .performScrollTo().assertIsDisplayed()

                state.value = state.value.copy(codexConnected = true, codexAccountId = "account-r3a",
                    codexDeviceCode = CodexDeviceCodeState.Connected("account-r3a"))
                compose.waitForIdle()
                compose.onNodeWithText(localized.getString(R.string.provider_disconnect_chatgpt))
                    .performScrollTo().assertIsDisplayed()
                assertTrue(compose.onAllNodesWithText(localized.getString(R.string.provider_disconnect_chatgpt_title))
                    .fetchSemanticsNodes().isEmpty())
                compose.onAllNodesWithTag("provider-device-code-cancel").assertCountEquals(0)
                compose.onAllNodesWithText(localized.getString(R.string.provider_signin_chatgpt)).assertCountEquals(0)
            }
        }
    }
}
