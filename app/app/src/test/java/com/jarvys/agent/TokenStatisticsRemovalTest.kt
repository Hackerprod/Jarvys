package com.jarvys.agent

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.jarvys.agent.proactive.ProactiveStatus
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenStatisticsRemovalTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun assistantResponseRendersWithoutAnyTokenBadgeOrTokenDetails() {
        val event = AgentRunUiEvent.messageEvent(9L, "assistant", "A simple answer", 1L)
            .copyMetadata("legacy-answer", 1_200L)
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
            MaterialTheme {
                com.jarvys.agent.ui.chat.ConversationTimeline(
                    conversationKey = "token-stats-removal",
                    events = listOf(event),
                    isRunning = false,
                    connectorRegistry = registry,
                    onOpenPreview = {},
                    onOpenSkillFile = {},
                    chatWithoutMemory = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.chat_assistant_message_accessibility),
        ).assertExists()
        compose.onAllNodesWithText("token", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("1,200", substring = true).assertCountEquals(0)
    }

    @Test
    fun preferencesScreenAndBothLocaleResourceSetsHaveNoTokenStatsOption() {
        compose.setContent {
            MaterialTheme {
                JarvysSettingsScreen(
                    page = JarvysSettingsPage.PREFERENCES,
                    themeMode = JarvysThemeMode.SYSTEM,
                    showAgentEvents = true,
                    proactiveEnabled = false,
                    proactiveStatus = ProactiveStatus(enabled = false),
                    agentTimeoutSeconds = 0,
                    memoryEnabled = false,
                    memoryUsedCharacters = 0,
                    languageChoice = AppLanguageChoice.ENGLISH,
                    onLanguageChange = {},
                    onThemeChange = {},
                    onShowAgentEventsChange = {},
                    onProactiveEnabledChange = {},
                    onRefreshProactiveStatus = {},
                    onAgentTimeoutChange = {},
                    onNavigateRoute = {}, onMcp = {}, onSkills = {}, onConnectors = {},
                    onMemory = {}, onAccessibilitySettings = {}, onNavigate = {},
                )
            }
        }
        compose.onAllNodesWithText("Show response token stats", ignoreCase = true).assertCountEquals(0)
        val root = File("src/main").absoluteFile
        val english = File(root, "res/values/strings.xml").readText()
        val spanish = File(root, "res/values-es/strings.xml").readText()
        listOf("chat_footer_token_summary", "chat_footer_token_details", "chat_footer_token_stats_setting")
            .forEach { key ->
                assertFalse("$key must be removed from English resources", english.contains("name=\"$key\""))
                assertFalse("$key must be removed from Spanish resources", spanish.contains("name=\"$key\""))
            }
        val sources = File(root, "java").walkTopDown().filter {
            it.isFile && (it.extension == "kt" || it.extension == "java")
        }.joinToString("\n") { it.readText() }
        listOf("showTokenStats", "onShowTokenStatsChange", "TokenBadge", "KEY_SHOW_TOKEN_STATS",
            "chat_footer_token_summary", "chat_footer_token_details", "chat_footer_token_stats_setting")
            .forEach { assertFalse("production source must not reference $it", sources.contains(it)) }
    }

    @Test
    fun contextUsageStillFeedsCompactionDecision() {
        val contextUsage = ModelReply("answer", emptyList(), "", null, "fake", 30_000)
        val model = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                                  tools: List<ToolSpec>, token: CancellationToken) = contextUsage
            override fun contextWindow(token: CancellationToken) = 32_768
        }
        assertEquals(30_000, model.usageTokens(contextUsage))
        assertTrue("context-window usage remains an internal compaction signal",
            ConversationCompactionPolicy.shouldCompact(model.usageTokens(contextUsage)!!, model.contextWindow(CancellationToken.uncancellable())))
    }
}
