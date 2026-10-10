package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.*
import com.jarvys.agent.R
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import java.util.Locale
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainAgentComposerTest {
    // Queue markdown StateFlow resumptions on the test scheduler instead of its parser worker.
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    @Test fun englishLight() = scene("en", false, 1f)
    @Test fun englishDark() = scene("en", true, 1f)
    @Test fun spanishLight() = scene("es", false, 1f)
    @Test fun spanishDark() = scene("es", true, 1f)
    @Test fun englishLargeLight() = scene("en", false, 2f)
    @Test fun englishLargeDark() = scene("en", true, 2f)
    @Test fun spanishLargeLight() = scene("es", false, 2f)
    @Test fun spanishLargeDark() = scene("es", true, 2f)
    @Test fun noReplacementForManagedOrBlockedScope() = scene("en", false, 1f, allowed = false)
    @Test fun emptyDraftOnlyOffersStop() = scene("en", false, 1f, draft = "")

    @Test fun restoredApprovalWithCollidingNumericIdRendersAlongsideDurableProgress() {
        AgentRunUiState.beginRun("collision-owner", "Inspect")
        AgentRunUiState.bindGeneration("collision-owner", 99)
        AgentRunUiState.showApproval("approval-collision", "Approve hydration", listOf("A reviewed action"), null)
        val approvalId = AgentRunUiState.state.value.events.single { it.approvalId == "approval-collision" }.id
        AgentRunUiState.resetSession("other")
        val durable = listOf(
            AgentRunUiEvent.messageEvent(approvalId + 1, "user", "Inspect", 1).copyMetadata("user-proof", 0),
            AgentRunUiEvent.messageEvent(approvalId, "assistant", "Durable progress", 2).copyMetadata("progress-proof", 0).copyStage("PROGRESS"))
        AgentRunUiState.restoreSession("collision-owner", durable)
        val events = AgentRunUiState.state.value.events
        assertEquals(events.size, events.map { it.id }.toSet().size)
        assertEquals(1, events.count { it.approvalId == "approval-collision" })
        val connectors = com.jarvys.agent.connectors.ConnectorRegistry.createForTests(
            object : com.jarvys.agent.connectors.ConnectorConnectionPreferences {
                override fun isConnected(id: String) = false
                override fun setConnected(id: String, connected: Boolean) = Unit
            }, { true })
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ConversationTimeline(conversationKey = "collision-owner", events = events, isRunning = true,
                        connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {}, chatWithoutMemory = true)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Approve hydration").assertIsDisplayed()
        AgentRunUiState.completeGeneration("collision-owner", 99, "done", "STOPPED", "stopped", "m", 0)
    }

    private fun scene(language: String, dark: Boolean, scale: Float, allowed: Boolean = true,
        draft: String = if (language == "es") "Mejor revisa el archivo primero." else "Please inspect the file first.") {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocales(LocaleList(Locale(language))) }
        val context = compose.activity.createConfigurationContext(config)
        var replacements = 0; var stops = 0; var sends = 0
        val progress = AgentRunUiEvent.messageEvent(1, "assistant",
            if (language == "es") "El archivo está guardado. Estoy comprobando el resultado." else "The file is saved. I’m checking the result.", 1)
            .copyMetadata("progress-fixture", 0).copyStage("PROGRESS")
        compose.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f, scale), LocalReducedMotion provides true) {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.padding(12.dp)) { AssistantReplyView(progress, {}, false, streamActive = true) }
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            IntentMessage(AgentRunUiEvent.messageEvent(2, "user", draft.ifBlank { "Next request" }, 2)
                                .copyMetadata("pending-fixture", 0).copyStage("QUEUED"))
                        }
                        Spacer(Modifier.weight(1f))
                        ChatComposer(goal = draft, onGoalChange = {}, onSend = { sends++ }, onStop = { stops++ },
                            onSelectModel = {}, modelLabel = "GPT-5", running = true,
                            captureContextRequested = false, onCaptureContext = {}, onImportSkill = {},
                            availableSkills = emptyList(), selectedSkillIds = emptySet(), onToggleRunSkill = {},
                            onManageSkills = {}, skillEnabledCount = 0, skillTotalCount = 0,
                            crewMode = CrewMode.OFF, onCrewModeChange = {}, onOpenCrew = {},
                            canInterrupt = allowed, onInterruptAndSend = { replacements++ })
                    }
                }
            }
        }
        // Markdown parsing runs on Default; Compose idleness alone does not await publication.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(progress.text).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        compose.onNodeWithText(progress.text).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_interrupt_pending)).assertIsDisplayed()
        val stop = compose.onNodeWithTag("chat-send-stop-button")
        stop.assertIsDisplayed()
        assertTrue(stop.fetchSemanticsNode().boundsInRoot.height >= 48f)
        if (allowed && draft.isNotBlank()) {
            val action = compose.onNodeWithTag("chat-interrupt-send")
            action.assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 48f)
            assertTrue(bounds.left >= 0f && bounds.right <= 320f)
            compose.onNodeWithText(context.getString(R.string.chat_interrupt_send)).assertIsDisplayed()
            action.performClick()
            assertEquals(1, replacements)
        } else compose.onNodeWithTag("chat-interrupt-send").assertDoesNotExist()
        stop.performClick()
        assertEquals(1, stops); assertEquals(0, sends)
        val directory = System.getenv("JARVYS_UX26_CAPTURE_DIR")?.let { File(it, BuildConfig.FLAVOR) }
        if (directory != null) {
            directory.mkdirs()
            compose.runOnIdle {
                val root = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(directory, "${language}-${if (dark) "dark" else "light"}-${scale}-${allowed}-${draft.isBlank()}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
        }
    }
}
