package com.jarvys.agent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteMeta
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.shell.JarvysRouteTopBar
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RouteTopBarResponsiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.create("f1-shots")

    private val titles = listOf("Chat", "A very long conversation title that must stay on one line")
    private val fontScales = listOf(1f, 1.3f, 2f)
    private val presences = listOf(
        AgentRunUiSnapshot(),
        AgentRunUiSnapshot(running = true),
        AgentRunUiSnapshot(running = true, events = listOf(
            AgentRunUiEvent.toolEvent(3L, "tool_call", "Calendar Search", null, "c", null, 3L))),
        AgentRunUiSnapshot(events = listOf(AgentRunUiEvent(4L, "approval", "PENDING", "Confirm",
            approvalStatus = "PENDING"))),
        AgentRunUiSnapshot(outcome = "FAILED"),
        AgentRunUiSnapshot(outcome = "COMPLETED"),
    )

    @Test fun narrowHeaderKeepsTitleAvatarActionsAndOmitsPresenceOrbeAtLargeFontScales() {
        for (fontScale in fontScales) {
            val titleHeights = mutableMapOf<String, Float>()
            for (title in titles) {
                installTopBar(360, fontScale, title, presences.first())
                val titleNode = compose.onNodeWithTag("jarvys-topbar-title").assertIsDisplayed().fetchSemanticsNode()
                titleHeights[title] = titleNode.boundsInRoot.height
                compose.onAllNodesWithTag("agent-presence-idle").assertCountEquals(0)
                compose.onAllNodesWithTag("agent-presence-glyph", useUnmergedTree = true).assertCountEquals(0)
                compose.onNodeWithTag("jarvys-topbar-mascot", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.drawer_new_chat)).assertIsDisplayed()
                compose.onAllNodesWithText(compose.activity.getString(R.string.agent_presence_idle)).assertCountEquals(0)
                val bitmap = captureActivityBitmap(360, 90)
                try {
                    val filename = "topbar_360_fs${fontScale.toString().replace('.', '_')}_${if (title == "Chat") "short" else "long"}.png"
                    File(output, filename).outputStream().use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            assertEquals("title remains one line at fontScale=$fontScale",
                requireNotNull(titleHeights[titles.first()]), requireNotNull(titleHeights[titles.last()]), 1.5f)
        }
        for ((index, snapshot) in presences.withIndex()) {
            installTopBar(360, 1f, titles.last(), snapshot)
            compose.onNodeWithTag("jarvys-topbar-title").assertIsDisplayed()
            compose.onAllNodesWithTag("agent-presence-${listOf("idle", "thinking", "working", "waiting_user", "error", "done")[index]}")
                .assertCountEquals(0)
        }
    }

    @Test fun wideHeaderKeepsTitleAndDoesNotRestorePresenceOrbe() {
        installTopBar(600, 1f, "Chat", presences.first(), forceWidth = true)
        compose.onNodeWithTag("jarvys-topbar-title").assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.agent_presence_idle)).assertCountEquals(0)
        val bitmap = captureActivityBitmap(600, 90)
        try {
            File(output, "topbar_600_fs1_0_short.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun installTopBar(width: Int, fontScale: Float, title: String, snapshot: AgentRunUiSnapshot,
                              forceWidth: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val baseDensity = compose.activity.resources.displayMetrics.density
            compose.activity.setContent {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(
                        LocalReducedMotion provides true,
                        LocalDensity provides Density(baseDensity, fontScale),
                    ) {
                        Box((if (forceWidth) Modifier.requiredWidth(width.dp) else Modifier.width(width.dp)).height(88.dp)
                            .background(MaterialTheme.colorScheme.background).testTag("f1-topbar-viewport")) {
                            JarvysRouteTopBar(
                                route = AppRouteMeta(title, isRoot = true, action = AppRouteAction.CHAT),
                                agentSnapshot = snapshot,
                                onBack = {}, onOpenDrawer = {}, onOpenSettings = {}, onNewChat = {},
                                onAddServer = {}, onImportSkill = {}, chatWithoutMemory = false,
                                canCompact = false, compacting = false, canReflect = false,
                                onToggleMemory = {}, onCompact = {}, onReflect = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun captureActivityBitmap(widthDp: Int, heightDp: Int): Bitmap {
        var captured: Bitmap? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = compose.activity.window.decorView
            val density = root.resources.displayMetrics.density
            val widthPx = (widthDp * density).toInt()
            val heightPx = (heightDp * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, widthPx, heightPx)
            captured = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                root.draw(Canvas(bitmap))
            }
        }
        return requireNotNull(captured)
    }
}
