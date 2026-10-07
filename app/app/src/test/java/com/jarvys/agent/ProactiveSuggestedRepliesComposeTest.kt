package com.jarvys.agent

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.proactive.ProactiveSuggestedReply
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
class ProactiveSuggestedRepliesComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun proactiveReplyChipsWrapInEnglishAndSpanishAtFontScaleOneAndTwoAndConsumeOnce() {
        val base = RuntimeEnvironment.getApplication()
        val language = mutableStateOf("en")
        val scale = mutableStateOf(1f)
        val message = mutableStateOf(event("en"))
        var selected: Pair<String, Int>? = null
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true }, ApprovalGate.INSTANCE)

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
                    Box(Modifier.width(320.dp).height(900.dp)) {
                        AgentRunScreen(
                            conversationKey = ProactiveConversationKey,
                            events = listOf(message.value),
                            isRunning = false,
                            emptyReport = null,
                            connectorRegistry = registry,
                            onOpenPreview = {},
                            onOpenSkillFile = {},
                            chatWithoutMemory = false,
                            onProactiveSuggestedReply = { id, index ->
                                selected = id to index
                                message.value = message.value.copy(proactiveRepliesUsed = true)
                            },
                        )
                    }
                }
            }
        }

        for (locale in listOf("en", "es")) {
            language.value = locale
            for (fontScale in listOf(1f, 2f)) {
                message.value = event(locale)
                scale.value = fontScale
                compose.waitForIdle()
                val labels = message.value.proactiveReplies.map(ProactiveSuggestedReply::label)
                val chipBounds = labels.map { label ->
                    compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                }
                for (first in chipBounds.indices) for (second in first + 1 until chipBounds.size) {
                    val a = chipBounds[first]
                    val b = chipBounds[second]
                    assertTrue("chip bounds overlap at locale=$locale scale=$fontScale: $a / $b",
                        a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
                }
                compose.onNodeWithText(labels.first()).performClick()
                compose.waitForIdle()
                assertEquals(message.value.messageId to 0, selected)
                val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    setLocale(Locale(locale))
                })
                val used = "${labels.first()} · ${localized.getString(R.string.proactive_suggested_reply_used)}"
                compose.onNodeWithText(used).assertIsDisplayed().assertIsNotEnabled()
            }
        }
        assertTrue(message.value.proactiveRepliesUsed)
    }

    private fun event(locale: String) = AgentRunUiEvent.proactiveMessageEvent(
        id = 1L,
        role = "assistant",
        text = "Choose a response.",
        timestampMillis = 10L,
        messageId = "notice-message",
        threadKey = "account-update",
        replies = if (locale == "en") listOf(
            ProactiveSuggestedReply("Yes", "Yes, that was me"),
            ProactiveSuggestedReply("Summarize", "Summarize this"),
            ProactiveSuggestedReply("Draft a reply", "Draft a reply that says hello"),
        ) else listOf(
            ProactiveSuggestedReply("Sí", "Sí, fui yo"),
            ProactiveSuggestedReply("Resúmelo", "Resúmelo"),
            ProactiveSuggestedReply("Respóndele", "Respóndele que hola"),
        ),
        repliesUsed = false,
    )

    private companion object { const val ProactiveConversationKey = "jarvys-proactive" }
}
