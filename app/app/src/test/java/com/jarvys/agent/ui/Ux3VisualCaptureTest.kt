package com.jarvys.agent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.UserDecisionRole
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.ui.chat.ChatComposer
import com.jarvys.agent.ui.chat.ConversationTimeline
import com.jarvys.agent.ui.chat.UserDecisionEventCard
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.shell.JarvysShellFrame
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Ux3VisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.named("ux3-shots")

    @Test fun captureDecisionStatesHeaderAndFloatingComposerInLightAndDark() {
        captureDecision("decision_pending_light.png", dark = false, resolved = false)
        captureDecision("decision_compact_light.png", dark = false, resolved = true)
        installDecision(dark = false, resolved = true)
        compose.onNodeWithTag("user-decision-resolved-ux3-decision", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        capture("decision_expanded_light.png")

        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
            installTopBar(dark, fontScale)
            capture("header_no_orbe_${if (dark) "dark" else "light"}_fontscale${fontScale.toInt()}.png")
        }

        installFloatingComposerScene(dark = true, scrollHistory = true)
        capture("floating_chat_scrolled_dark.png")
        installFloatingComposerScene(dark = true, scrollHistory = false, composerText = "Checking the release plan now.")
        capture("floating_chat_tail_dark.png")
        installFloatingComposerScene(dark = false, scrollHistory = true)
        capture("floating_chat_scrolled_light.png")

        val names = listOf("decision_pending_light.png", "decision_compact_light.png",
            "decision_expanded_light.png", "header_no_orbe_light_fontscale1.png", "header_no_orbe_light_fontscale2.png",
            "header_no_orbe_dark_fontscale1.png", "header_no_orbe_dark_fontscale2.png",
            "floating_chat_scrolled_dark.png", "floating_chat_tail_dark.png", "floating_chat_scrolled_light.png")
        val hashes = names.map { name ->
            val file = File(output, name)
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            digest.joinToString("") { "%02x".format(it) }
        }
        assertEquals("UX3 native captures must be visually distinct", names.size, hashes.toSet().size)
        println("UX3_CAPTURE_DIR=${output.absolutePath}")
        names.zip(hashes).forEach { (name, hash) -> println("UX3_CAPTURE $name sha256=$hash") }
    }

    private fun installDecision(dark: Boolean, resolved: Boolean) {
        val event = if (resolved) decision("SELECTED") else decision("PENDING")
        compose.activity.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 18.dp, vertical = 80.dp)) {
                        UserDecisionEventCard(event)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun installTopBar(dark: Boolean, fontScale: Float) {
        compose.activity.setContent {
            val original = LocalDensity.current
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true,
                    LocalDensity provides Density(original.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        com.jarvys.agent.ui.shell.JarvysRouteTopBar(
                            route = AppRouteMeta("A long conversation title at large text size", true,
                                action = AppRouteAction.CHAT),
                            agentSnapshot = AgentRunUiSnapshot(running = true),
                            onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                            onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                            canCompact = false, compacting = false, canReflect = false,
                            onToggleMemory = {}, onCompact = {}, onReflect = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun installFloatingComposerScene(dark: Boolean, scrollHistory: Boolean,
                                            composerText: String = "Draft a response with multiple\nlines visible behind the overlay") {
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val events = buildList {
            repeat(6) { index ->
                add(AgentRunUiEvent.messageEvent(index * 2L + 1L, "user",
                    "Earlier context ${index + 1}: compare rollout risks, mention the verified fallback, and keep the decision history readable beneath the floating composer.",
                    index * 2L + 1L))
                add(AgentRunUiEvent.messageEvent(index * 2L + 2L, "assistant",
                    "The earlier summary ${index + 1} remains in the conversation history and can scroll behind the composer.",
                    index * 2L + 2L))
            }
            add(AgentRunUiEvent(25L, "approval", "APPROVED", "Calendar Search",
                approvalId = "ux3-approved", approvalStatus = "APPROVED", approvalLines = listOf("Read calendar events")))
            add(decision("SELECTED", id = "ux3-decision").copy(id = 26L))
            repeat(4) { index ->
                val sequence = index + 7
                add(AgentRunUiEvent.messageEvent(26L + index * 2L + 1L, "user",
                    "Earlier context $sequence continues beneath the floating composer while the viewport stays full height.",
                    26L + index * 2L + 1L))
                add(AgentRunUiEvent.messageEvent(26L + index * 2L + 2L, "assistant",
                    "The earlier summary $sequence can still be read by scrolling.", 26L + index * 2L + 2L))
            }
            add(AgentRunUiEvent.messageEvent(35L, "assistant", "The selected action is visible in compact form, and its details remain available on demand.", 35L))
        }
        compose.activity.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    JarvysShellFrame(
                        drawerState = rememberDrawerState(DrawerValue.Closed), drawerContent = {},
                        route = AppRouteMeta("Conversation about rollout", true, action = AppRouteAction.CHAT),
                        agentSnapshot = AgentRunUiSnapshot(running = true, events = events),
                        onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                        onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                        canCompact = false, compacting = false, canReflect = false,
                        onToggleMemory = {}, onCompact = {}, onReflect = {},
                        bottomBar = {
                            ChatComposer(
                                goal = composerText,
                                onGoalChange = {}, onSend = {}, onStop = {}, onSelectModel = {},
                                modelLabel = "GPT-5.4", running = true, captureContextRequested = false,
                                onCaptureContext = {}, onImportSkill = {}, availableSkills = emptyList(),
                                selectedSkillIds = emptySet(), onToggleRunSkill = {}, onManageSkills = {},
                                skillEnabledCount = 0, skillTotalCount = 0, crewMode = CrewMode.OFF,
                                onCrewModeChange = {}, onOpenCrew = {},
                            )
                        },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                ConversationTimeline(
                                    conversationKey = "ux3-floating-chat", events = events, isRunning = false,
                                    connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                                    chatWithoutMemory = false, runSnapshot = AgentRunUiSnapshot(events = events),
                                    allowMessageEntrance = false,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (scrollHistory) {
            compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
    }

    private fun captureDecision(name: String, dark: Boolean, resolved: Boolean) {
        installDecision(dark, resolved)
        capture(name)
    }

    private fun capture(name: String) {
        val bitmap = captureActivityBitmap()
        try {
            val file = File(output, name)
            if (file.exists()) TestCaptureDirectories.delete(output, file)
            TestCaptureDirectories.assertOwned(output, file)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun captureActivityBitmap(): Bitmap {
        var captured: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = compose.activity.window.decorView
            val density = root.resources.displayMetrics.density
            val width = (360f * density).toInt()
            val height = (800f * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            captured = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        return requireNotNull(captured)
    }

    private fun decision(status: String, id: String = "ux3-decision") = AgentRunUiEvent.userDecisionEvent(
        4L, id, "Install the optional Linux environment?", "The rootfs adds an Ubuntu environment for approved commands.\n\nThe environment is stored privately and can be removed later.",
        listOf(UserDecisionOption("install", "Install", role = UserDecisionRole.PRIMARY),
            UserDecisionOption("later", "Not now")), false, status,
        if (status == "SELECTED") "install" else null,
        if (status == "SELECTED") "Install" else null, 4L,
    )
}
