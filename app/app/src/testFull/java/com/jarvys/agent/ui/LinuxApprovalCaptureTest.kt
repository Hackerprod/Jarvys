package com.jarvys.agent.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.JarvysPalette
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.UserDecisionRole
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorUiText
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ConversationTimeline
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
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
class LinuxApprovalCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureConsentApprovalProgressAndUninstallInLightAndDarkThemes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val command = "printf 'approval literal must remain exact: " + "x".repeat(140) + "' && id"
        val consent = AgentRunUiEvent(
            id = 1L, kind = "user_decision", stage = "PENDING",
            text = context.getString(R.string.full_linux_setup_consent_title),
            decisionId = "linux-consent-capture",
            decisionBody = context.getString(R.string.full_linux_setup_consent_body,
                "24.04.5", "arm64", "29 MB", "110 MB", "8 GB"),
            decisionOptions = listOf(
                UserDecisionOption("install", context.getString(R.string.full_linux_setup_install), role = UserDecisionRole.PRIMARY),
                UserDecisionOption("later", context.getString(R.string.full_linux_setup_later)),
            ),
            decisionAllowDismiss = false,
            decisionStatus = "PENDING",
        )
        val approvalLines = listOf(
            ConnectorUiText(R.string.full_linux_exec_approval_command, listOf(command), "Command (literal):\n$command"),
            ConnectorUiText(R.string.full_linux_exec_approval_cwd, listOf("/workspace/demo"), "Working directory: /workspace/demo"),
            ConnectorUiText(R.string.full_linux_exec_approval_warning,
                fallback = "PRoot is not a sandbox. The command runs with this app's access and network permissions."),
        )
        val approval = AgentRunUiEvent(
            id = 2L, kind = "approval", stage = "PENDING", text = "Approve Linux command",
            approvalId = "linux-long-command", approvalLines = approvalLines.map { it.fallback },
            approvalStatus = "PENDING", approvalAllowAlwaysAvailable = false,
            approvalLocalizedTitle = ConnectorUiText(R.string.full_linux_exec_approval_title,
                fallback = "Approve Linux command"), approvalLocalizedLines = approvalLines,
        )
        val progress = AgentRunUiEvent.toolEvent(3L, "tool_progress", "Linux setup",
            "Downloading Ubuntu Base: 14 MB / 29 MB", "linux-setup-call", null, 3L)
        val uninstall = consent.copy(
            id = 4L, decisionId = "linux-uninstall-capture",
            text = context.getString(R.string.full_linux_uninstall_title),
            decisionBody = context.getString(R.string.full_linux_uninstall_body),
            decisionOptions = listOf(
                UserDecisionOption("keep_workspace", context.getString(R.string.full_linux_uninstall_keep_workspace), role = UserDecisionRole.PRIMARY),
                UserDecisionOption("delete_workspace", context.getString(R.string.full_linux_uninstall_delete_workspace), role = UserDecisionRole.DESTRUCTIVE),
                UserDecisionOption("cancel", context.getString(R.string.full_linux_uninstall_cancel)),
            ),
            decisionAllowDismiss = false,
        )

        capture("consent_light", listOf(consent), connectors, JarvysThemeMode.LIGHT) {
            compose.onNodeWithTag("user-decision-card-linux-consent-capture").assertIsDisplayed()
        }
        capture("command_approval_light", listOf(approval), connectors, JarvysThemeMode.LIGHT) {
            compose.onNodeWithTag("approval-card-linux-long-command").assertIsDisplayed()
        }
        capture("setup_progress_dark", listOf(progress), connectors, JarvysThemeMode.DARK)
        capture("uninstall_dark", listOf(uninstall), connectors, JarvysThemeMode.DARK) {
            compose.onNodeWithTag("user-decision-card-linux-uninstall-capture").assertIsDisplayed()
        }
    }

    private fun capture(
        name: String,
        events: List<AgentRunUiEvent>,
        connectors: ConnectorRegistry,
        mode: JarvysThemeMode,
        assertVisible: () -> Unit = {},
    ) {
        compose.activity.runOnUiThread {
            compose.activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
            compose.activity.window.navigationBarColor = if (mode == JarvysThemeMode.DARK)
                JarvysPalette.CanvasDark.toArgb() else JarvysPalette.CanvasLight.toArgb()
            compose.activity.window.decorView.systemUiVisibility = if (mode == JarvysThemeMode.DARK) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            compose.activity.setContent {
                JarvysOwnTheme(mode) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            ConversationTimeline(
                                conversationKey = "l1-$name", generatedImageSessionId = "l1-capture",
                                events = events, isRunning = true, connectorRegistry = connectors,
                                onOpenPreview = {}, onOpenSkillFile = {}, chatWithoutMemory = false,
                                allowMessageEntrance = false,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertVisible()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val bitmap = captureActivityBitmap()
        try {
            val directory = TestCaptureDirectories.named("linux-approval-shots")
            val output = File(directory, "$name.png")
            TestCaptureDirectories.assertOwned(directory, output)
            output.outputStream().use { stream -> assertTrue("failed to write $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        } finally { bitmap.recycle() }
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
}
