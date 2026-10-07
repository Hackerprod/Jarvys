package com.jarvys.agent.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.GeneratedImageStore
import com.jarvys.agent.JarvysPalette
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.crew.CrewMode
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Ux1VisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.create("ux1-shots")

    @Test fun captureChatWithAssistantTextAndThreeByTwoGeneratedImage() {
        if (BuildConfig.FLAVOR != "play") return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = "ux1-chat-image-capture"
        val imagePath = GeneratedImageStore(context).save(session, UUID.randomUUID().toString(), pngFixture())
        val events = listOf(
            AgentRunUiEvent.messageEvent(1L, "user", "Genera una ilustración de faros en la costa.", 1L),
            AgentRunUiEvent.messageEvent(2L, "assistant", "Aquí tienes la ilustración solicitada.", 2L),
            AgentRunUiEvent.generatedImageEvent(3L, imagePath, "Un faro sobre olas azules", "Faro costero",
                "image/png", "3:2", "COMPLETED", null, 3L),
        )
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val stage = System.getenv("UX1_CAPTURE_STAGE") ?: "after"
        require(stage == "before" || stage == "after")
        compose.activity.runOnUiThread {
            compose.activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
            compose.activity.window.navigationBarColor = JarvysPalette.CanvasLight.toArgb()
            compose.activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            compose.activity.setContent {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Box(Modifier.weight(1f)) {
                                ConversationTimeline(
                                    conversationKey = "ux1-chat-image-$stage",
                                    generatedImageSessionId = session,
                                    events = events,
                                    isRunning = false,
                                    connectorRegistry = connectors,
                                    onOpenPreview = {}, onOpenSkillFile = {}, chatWithoutMemory = false,
                                    allowMessageEntrance = false,
                                )
                            }
                            com.jarvys.agent.ui.chat.ChatComposer(
                                goal = "", onGoalChange = {}, onSend = {}, onStop = {}, onSelectModel = {},
                                modelLabel = "GPT-5.4", running = false, captureContextRequested = false,
                                onCaptureContext = {}, onImportSkill = {}, availableSkills = emptyList(),
                                selectedSkillIds = emptySet(), onToggleRunSkill = {}, onManageSkills = {},
                                skillEnabledCount = 0, skillTotalCount = 0, crewMode = CrewMode.OFF,
                                onCrewModeChange = {}, onOpenCrew = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("generated-image-thumbnail-3", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        layoutActivityForCapture()
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("generated-image-thumbnail-3", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
                    && compose.onAllNodesWithTag("generated-image-loading-3", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val bitmap = captureActivityBitmap()
        try {
            val target = File(output, "${stage}_chat_image.png")
            TestCaptureDirectories.assertOwned(output, target)
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun pngFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 96) {
            bitmap.setPixel(x, y, android.graphics.Color.rgb(x * 2, y * 3, (x + y) * 2))
        }
        return ByteArrayOutputStream().also { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
    }

    private fun captureActivityBitmap(): Bitmap {
        var captured: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = compose.activity.window.decorView
            val density = root.resources.displayMetrics.density
            val width = (411f * density).toInt()
            val height = (891f * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            captured = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        return requireNotNull(captured)
    }

    private fun layoutActivityForCapture() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = compose.activity.window.decorView
            val density = root.resources.displayMetrics.density
            val width = (411f * density).toInt()
            val height = (891f * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
    }
}
