package com.jarvys.agent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.crew.CrewBotAvatar
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionCard
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.crew.CrewRoleTemplates
import com.jarvys.agent.ui.chat.AgentPresenceIndicator
import com.jarvys.agent.ui.chat.ConversationTimeline
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class A1VisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.create("a1-shots")

    private enum class Gallery { PRESENCE, BOT_GRID, MISSION, TIMELINE }

    @Test fun captureA1LightAndDarkStatesBestEffort() {
        val errors = mutableListOf<String>()
        TestCaptureDirectories.delete(output, File(output, "capture-errors.txt"))
        TestCaptureDirectories.delete(output, File(output, "crew_light.png"))
        TestCaptureDirectories.delete(output, File(output, "crew_dark.png"))
        val registry = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.mainClock.autoAdvance = false
        for (isDark in listOf(false, true)) {
            for (scene in Gallery.entries) {
                val sceneName = when (scene) {
                    Gallery.BOT_GRID -> "bots_grid"
                    else -> scene.name.lowercase(Locale.ROOT)
                }
                val fileName = "${sceneName}_${if (isDark) "dark" else "light"}.png"
                runCatching {
                    installGallery(scene, isDark, registry)
                    repeat(5) { compose.mainClock.advanceTimeByFrame() }
                    captureActivityBitmap().useBitmap { bitmap ->
                        File(output, fileName).outputStream().use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoder returned false" }
                        }
                    }
                }.onFailure { failure ->
                    errors += "$fileName:\n${failure.stackTraceToString()}"
                }
            }
        }
        if (errors.isNotEmpty()) File(output, "capture-errors.txt").writeText(errors.joinToString("\n"))
    }

    private fun installGallery(scene: Gallery, dark: Boolean, registry: ConnectorRegistry) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {
                        when (scene) {
                            Gallery.PRESENCE -> PresenceGallery()
                            Gallery.BOT_GRID -> CrewGallery()
                            Gallery.MISSION -> MissionGallery()
                            Gallery.TIMELINE -> TimelineGallery(registry, dark)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun PresenceGallery() {
        val cases = listOf(
            AgentRunUiSnapshot(),
            AgentRunUiSnapshot(running = true),
            AgentRunUiSnapshot(running = true, events = listOf(
                AgentRunUiEvent.toolEvent(3L, "tool_call", "Calendar Search", null, "c", null, 3L))),
            AgentRunUiSnapshot(events = listOf(AgentRunUiEvent(4L, "approval", "PENDING", "Confirm",
                approvalStatus = "PENDING"))),
            AgentRunUiSnapshot(outcome = "FAILED"),
            AgentRunUiSnapshot(outcome = "COMPLETED"),
        )
        Column(Modifier.fillMaxSize().testTag("a1-shot-presence")
            .background(MaterialTheme.colorScheme.background).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("Agent presence · six states", style = MaterialTheme.typography.titleMedium)
            cases.forEach { snapshot ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface) {
                    AgentPresenceIndicator(snapshot, Modifier.padding(8.dp), showLabel = true)
                }
            }
        }
    }

    @Composable
    private fun CrewGallery() {
        val roles = listOf(
            CrewRoleTemplates.EXPLORER to "Explore",
            CrewRoleTemplates.ANALYST to "Analyze",
            CrewRoleTemplates.CRITIC to "Review",
            CrewRoleTemplates.WRITER to "Write",
            CrewRoleTemplates.OPERATOR to "Operate",
        )
        val states = listOf(
            Triple("QUEUED", "", R.string.crew_status_queued),
            Triple("RUNNING", "", R.string.crew_status_working),
            Triple("WAITING", "límite del proveedor", R.string.crew_waiting_provider),
            Triple("WAITING", "respuesta del capitán", R.string.crew_waiting_captain),
            Triple("DONE", "", R.string.crew_status_done),
            Triple("FAILED", "", R.string.crew_status_failed),
            Triple("INTERRUPTED", "", R.string.crew_status_interrupted),
        )
        Column(Modifier.fillMaxSize().testTag("a1-shot-bots")
            .background(MaterialTheme.colorScheme.background).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Crew avatar matrix · role × state", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                roles.forEach { (_, title) -> Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
            }
            states.forEach { (status, reason, labelResource) ->
                val stateLabel = stringResource(labelResource)
                Row(Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    roles.forEach { (role, title) ->
                        Column(Modifier.weight(1f).padding(horizontal = 1.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            CrewBotAvatar(title, role, roleColor(role), status, reason,
                                modifier = Modifier.size(72.dp))
                            Text("$title\n$stateLabel", Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MissionGallery() {
        val bots = listOf(
            CrewBotSnapshot("bot-explore", CrewRoleTemplates.EXPLORER, "Explorer", "Mara", "blue",
                "Find sources", "DONE", "", "", "", emptyList(), 1L, 2L),
            CrewBotSnapshot("bot-review", CrewRoleTemplates.CRITIC, "Critic", "Sol", "rose",
                "Check assumptions", "RUNNING", "", "", "", emptyList(), 1L, 0L),
            CrewBotSnapshot("bot-wait", CrewRoleTemplates.ANALYST, "Analyst", "Ivo", "violet",
                "Check one source", "WAITING", "", "", "respuesta del capitán", emptyList(), 1L, 0L),
        )
        val mission = CrewMissionSnapshot("mission-a1", "shot", "shot", "Plan a careful launch",
            "SYNTHESIZED", "Keep the next action reviewable.", 1L, 2L, bots, emptyList())
        Box(Modifier.fillMaxSize().testTag("a1-shot-mission")
            .background(MaterialTheme.colorScheme.background).padding(14.dp),
            contentAlignment = Alignment.Center) {
            CrewMissionCard(mission, onOpen = {}, reducedMotionOverride = true)
        }
    }

    @Composable
    private fun TimelineGallery(registry: ConnectorRegistry, dark: Boolean) {
        val messages = listOf(
            AgentRunUiEvent.messageEvent(1L, "user", "Please map the options.", 1L),
            AgentRunUiEvent.messageEvent(2L, "assistant", "I have assembled the current findings and next steps.", 2L),
        )
        Box(Modifier.fillMaxSize().testTag("a1-shot-timeline")
            .background(MaterialTheme.colorScheme.background)) {
            ConversationTimeline(
                conversationKey = "a1-new-message-shot-$dark",
                events = messages,
                isRunning = false,
                runSnapshot = AgentRunUiSnapshot(events = messages),
                allowMessageEntrance = true,
                connectorRegistry = registry,
                onOpenPreview = {},
                onOpenSkillFile = {},
                chatWithoutMemory = false,
            )
        }
    }

    private fun roleColor(role: String) = when (role) {
        CrewRoleTemplates.EXPLORER -> "blue"
        CrewRoleTemplates.ANALYST -> "violet"
        CrewRoleTemplates.CRITIC -> "rose"
        CrewRoleTemplates.WRITER -> "green"
        else -> "operator"
    }

    private fun captureActivityBitmap(): Bitmap {
        var captured: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = compose.activity.window.decorView
            val density = root.resources.displayMetrics.density
            val widthPx = (411f * density).toInt()
            val heightPx = (891f * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, widthPx, heightPx)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            captured = bitmap
        }
        return requireNotNull(captured)
    }

    private inline fun <T> T.useBitmap(block: (T) -> Unit) {
        try { block(this) } finally { (this as? Bitmap)?.recycle() }
    }
}
