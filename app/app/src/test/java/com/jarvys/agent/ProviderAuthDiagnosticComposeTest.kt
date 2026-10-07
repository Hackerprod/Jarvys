package com.jarvys.agent

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.providers.ProvidersOpenAiDetailContent
import com.jarvys.agent.providers.ProvidersUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderAuthDiagnosticComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun state() = ProvidersUiState(ProviderSettings.Provider.OPENAI_CODEX, "gpt-5.4", "medium",
        "gpt-5.4", "medium", "openrouter/free", false, null, false,
        openRouterConnected = false, exaConnected = false)

    @Test fun browserProgressCanCancelAndFailuresExposeOnlySanitizedDiagnostic() {
        val diagnostic = CodexAuthDiagnostic.validation(CodexAuthDiagnostic.Stage.SAVE_SESSION, "readback_failed")
        val state = mutableStateOf(state().copy(codexOAuthInProgress = true, codexOAuthExchanging = true))
        var cancellations = 0
        compose.setContent { MaterialTheme {
            ProvidersOpenAiDetailContent(state.value, emptyList(), onSignIn = {}, onCancelSignIn = { cancellations++ },
                onDisconnect = {}, onSelectModel = {}, onSelectReasoning = {}, onUseActive = {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.provider_browser_exchanging)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.provider_cancel_signin)).performClick()
        assertEquals(1, cancellations)
        compose.runOnIdle { state.value = state.value.copy(codexOAuthInProgress = false, codexOAuthExchanging = false,
            codexOAuthError = "Could not save this session", codexOAuthDiagnostic = diagnostic) }
        compose.onNodeWithTag("provider-auth-diagnostic-copy").performScrollTo().performClick()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertEquals(diagnostic.toDisplayText("browser"), copied)
        assertTrue(copied.contains("reason=readback_failed"))
        assertFalse(copied.contains("access_token="))
    }
}
