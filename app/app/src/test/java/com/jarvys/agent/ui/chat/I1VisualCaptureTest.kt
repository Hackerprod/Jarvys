package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.GeneratedImageStore
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.platform.app.InstrumentationRegistry

/** One activity/Compose root per capture scene; PNG fixtures are generated at runtime. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class I1VisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun timelineLight() = capture("timeline_light.png", false, "timeline")
    @Test fun timelineDark() = capture("timeline_dark.png", true, "timeline")
    @Test fun viewerLight() = capture("viewer_light.png", false, "viewer")
    @Test fun viewerDark() = capture("viewer_dark.png", true, "viewer")
    @Test fun generatingLight() = capture("generating_light.png", false, "generating")
    @Test fun generatingDark() = capture("generating_dark.png", true, "generating")
    @Test fun errorStatesLight() = capture("errors_light.png", false, "errors")
    @Test fun errorStatesDark() = capture("errors_dark.png", true, "errors")

    private fun capture(fileName: String, dark: Boolean, scene: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val session = "i1-capture-session"
        val relative = GeneratedImageStore(context).save(session, UUID.randomUUID().toString(), pngFixture())
        val prompt = "A cobalt lighthouse on winter waves"
        val image = AgentRunUiEvent.generatedImageEvent(71L, relative, prompt,
            "A cobalt lighthouse above winter waves", "image/png", "1024x1024", "COMPLETED", null,
            System.currentTimeMillis())
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = false
            override fun setConnected(id: String, connected: Boolean) = Unit
        }, { true })
        compose.setContent {
                JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (scene) {
                        "timeline" -> ConversationTimeline(
                            conversationKey = "i1-capture-$fileName", generatedImageSessionId = session,
                            events = listOf(AgentRunUiEvent.messageEvent(1L, "user", "Create a lighthouse image.", 1L), image),
                            isRunning = false, connectorRegistry = connectors,
                            onOpenPreview = {}, onOpenSkillFile = {}, chatWithoutMemory = false,
                        )
                        "generating" -> {
                            val events = listOf(
                                AgentRunUiEvent.messageEvent(1L, "user", "Create a lighthouse image.", 1L),
                                AgentRunUiEvent.toolEvent(2L, "tool_call", "Generate image", null, "image-call", null, 2L),
                            )
                            ConversationTimeline(
                                conversationKey = "i1-generating-$fileName", generatedImageSessionId = session,
                                events = events, isRunning = true, connectorRegistry = connectors,
                                onOpenPreview = {}, onOpenSkillFile = {}, chatWithoutMemory = false,
                                runSnapshot = AgentRunUiSnapshot(sessionId = session, running = true, events = events),
                            )
                        }
                        "viewer" -> GeneratedImageEventCard(image, session, onSave = {}, onShare = {})
                        else -> ErrorStateGallery()
                    }
                    }
                    }
            }
        }
        compose.waitForIdle()
        if (scene == "timeline" || scene == "viewer") {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("generated-image-thumbnail-71", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
                        && compose.onAllNodesWithTag("generated-image-loading-71", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            }
        }
        if (scene == "viewer") {
            compose.onNodeWithTag("generated-image-open-71").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("generated-image-viewer-image").fetchSemanticsNodes().isNotEmpty()
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        val bitmap = captureActivityBitmap()
        try {
            writeCapture(fileName, bitmap)
        } finally { bitmap.recycle() }
    }

    @Composable
    private fun ErrorStateGallery() {
        val messages = listOf(
            "Session expired · HTTP_401 · sign in again",
            "Account or plan unavailable · model_not_found",
            "Quota reached · rate_limit_exceeded · Retry-After 8 seconds",
            "Content policy rejected request · content_policy_violation",
            "Incomplete stream · no_image_in_stream · response.completed",
            "Network error · connection unavailable",
            "Invalid PNG · image decode failed",
            "API error · HTTP_400",
            "Storage error · private image could not be saved",
        )
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Image generation · error states", style = MaterialTheme.typography.titleSmall)
            messages.forEachIndexed { index, message ->
                Surface(Modifier.fillMaxWidth().testTag("image-error-capture-$index"),
                    shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(message, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 2)
                }
            }
        }
    }

    private fun pngFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) for (x in 0 until 64) {
            bitmap.setPixel(x, y, android.graphics.Color.rgb(x * 4, y * 4, (x + y) * 2))
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
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            captured = bitmap
        }
        return requireNotNull(captured)
    }

    private fun writeCapture(name: String, bitmap: Bitmap) {
        val directory = outputDirectory()
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        TestCaptureDirectories.assertOwned(directory, temporary)
        temporary.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        if (target.exists()) TestCaptureDirectories.delete(directory, target)
        if (!temporary.renameTo(target)) throw IllegalStateException("Could not finish capture $name")
    }

    private fun outputDirectory() = outputDirectoryForFlavor()

    companion object {
        private val expectedFiles = listOf("timeline_light.png", "timeline_dark.png", "viewer_light.png", "viewer_dark.png",
            "generating_light.png", "generating_dark.png", "errors_light.png", "errors_dark.png")

        @BeforeClass @JvmStatic
        fun clearOldCaptures() {
            val output = outputDirectoryForFlavor()
            output.listFiles()?.filter { it.extension == "png" || it.name == "capture-errors.txt" }
                ?.forEach { TestCaptureDirectories.delete(output, it) }
        }

        @AfterClass @JvmStatic
        fun verifyCaptureFilesAndHashes() {
            val output = outputDirectoryForFlavor()
            val files = expectedFiles.map { File(output, it) }
            assertTrue("I1 screenshots missing: ${files.filterNot(File::isFile).map { it.name }}", files.all(File::isFile))
            val hashes = files.map { file -> MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList() }
            assertEquals("I1 screenshot PNGs must have distinct SHA-256 hashes", files.size, hashes.toSet().size)
        }

        private fun outputDirectoryForFlavor(): File = if (BuildConfig.FLAVOR == "play")
            TestCaptureDirectories.named("i1-shots-play") else TestCaptureDirectories.named("i1-shots-full")
    }
}
