package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class AgentResultPresentationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun resetLanguage() { AppLanguageRuntime.select(context, AppLanguageChoice.ENGLISH) }

    @Test fun systemInterruptionMessagesUseCurrentAppLanguageAndRetainTypedReason() {
        for (language in listOf(AppLanguageChoice.ENGLISH, AppLanguageChoice.SPANISH)) {
            AppLanguageRuntime.select(context, language)
            val localized = AppLanguageRuntime.localizedContext(context)
            for ((reason, resource) in listOf(
                CoreAgentLoop.InterruptionReason.DEADLINE to R.string.agent_timeout_result,
                CoreAgentLoop.InterruptionReason.NO_PROGRESS to R.string.agent_loop_result,
                CoreAgentLoop.InterruptionReason.PROVIDER_UNAVAILABLE to R.string.agent_provider_partial,
            )) {
                val source = CoreAgentLoop.Result("synthetic", "internal English message", 4, "PARTIAL", 1234, reason)
                val result = AgentResultPresentation.localize(context, source)
                assertEquals(localized.getString(resource), result.text)
                assertEquals(reason, result.interruptionReason)
                assertEquals("PARTIAL", result.outcome)
                assertEquals(1234, result.durationMs)
                assertEquals("internal English message", source.text)
                if (language == AppLanguageChoice.ENGLISH) assertFalse(result.text.contains("La tarea"))
                val session = "ux38-localized-${language.name}-${reason.name}"
                val store = LocalRunStore(context)
                store.appendConversationMessage(session, "assistant", result.text, null, result.runId, null, result.outcome)
                assertEquals(result.text, store.readConversationMessages(session).last().getString("content"))
            }
            assertEquals(localized.getString(R.string.agent_timeout_result), AgentResultPresentation.timeout(context))
        }
    }

    @Test fun modelOutputIsNotTranslatedBySystemPresentation() {
        AppLanguageRuntime.select(context, AppLanguageChoice.SPANISH)
        val source = CoreAgentLoop.Result("model", "User-requested English response", 1, "COMPLETED")
        assertSame(source, AgentResultPresentation.localize(context, source))
    }

    @Test fun failureMessagesAlsoUseConfiguredLanguageWithoutLeakingDetails() {
        for (language in listOf(AppLanguageChoice.ENGLISH, AppLanguageChoice.SPANISH)) {
            AppLanguageRuntime.select(context, language)
            val localized = AppLanguageRuntime.localizedContext(context)
            assertEquals(localized.getString(R.string.agent_failure_http, "401"),
                AgentResultPresentation.failure(context, "HTTP 401 synthetic private detail"))
            assertEquals(localized.getString(R.string.agent_failure_network),
                AgentResultPresentation.failure(context, "network synthetic private detail"))
            assertEquals(localized.getString(R.string.agent_failure_generic),
                AgentResultPresentation.failure(context, "synthetic private detail"))
        }
    }
}
