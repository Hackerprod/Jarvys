package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.UserDecisionOption
import com.jarvys.agent.UserDecisionRole
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.JarvysOwnTheme
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
@Config(sdk = [34], qualifiers = "es")
class LinuxFailurePresentationCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun appEnglishConsentAndCopyableExtractionFailureStayEnglishOnSpanishSystem() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        app.getSharedPreferences("jarvys_app_language", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        AppLanguageRuntime.select(app, AppLanguageChoice.ENGLISH)
        val localized = AppLanguageRuntime.localizedContext(app)
        assertTrue(localized.getString(R.string.language_title).contains("Language"))
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        val consent = AgentRunUiEvent(
            id = 1L, kind = "user_decision", stage = "PENDING",
            text = localized.getString(R.string.full_linux_setup_consent_title),
            decisionId = "lf1-linux-consent",
            decisionBody = localized.getString(R.string.full_linux_setup_consent_body,
                "24.04.5", "arm64", "29 MB", "110 MB", "8 GB"),
            decisionOptions = listOf(
                UserDecisionOption("install", localized.getString(R.string.full_linux_setup_install), role = UserDecisionRole.PRIMARY),
                UserDecisionOption("later", localized.getString(R.string.full_linux_setup_later)),
            ), decisionStatus = "PENDING", decisionAllowDismiss = false,
        )
        val failureDetail = "phase=EXTRACTING; exception=android.system.ErrnoException; message=open failed: EACCES; " +
            "errno=EACCES; function=open; tar_entry=usr/bin/chfn; tar_type=regular(0); tar_mode=4755; entries_processed=1"
        val failedTool = AgentRunUiEvent.toolEvent(2L, "tool_error", "Linux setup", failureDetail,
            "linux-setup-call", null, 2L)

        capture("consent_app_en_system_es", listOf(consent), connectors, localized)
        capture("extract_failure_detail", listOf(failedTool), connectors, localized)
    }

    private fun capture(
        name: String,
        events: List<AgentRunUiEvent>,
        connectors: ConnectorRegistry,
        localized: android.content.Context,
    ) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalConfiguration provides localized.resources.configuration,
                        LocalReducedMotion provides true,
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            ConversationTimeline(
                                conversationKey = "lf1-$name", events = events, isRunning = false,
                                connectorRegistry = connectors, onOpenPreview = {}, onOpenSkillFile = {},
                                chatWithoutMemory = false, allowMessageEntrance = false,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (name == "extract_failure_detail") {
            compose.onNodeWithTag("conversation-activity-2").assertIsDisplayed()
            compose.onNodeWithText("tar_entry=usr/bin/chfn", substring = true,
                useUnmergedTree = true).assertIsDisplayed()
        } else {
            compose.onNodeWithText("Your decision").assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.full_linux_setup_install)).assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.full_linux_setup_later)).assertIsDisplayed()
        }
        val bitmap = captureActivityBitmap()
        try {
            val directory = TestCaptureDirectories.named("linux-failure-shots")
            val output = File(directory, "$name.png")
            TestCaptureDirectories.assertOwned(directory, output)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
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
