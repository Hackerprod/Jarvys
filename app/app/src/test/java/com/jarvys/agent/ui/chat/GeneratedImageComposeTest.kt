package com.jarvys.agent.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.GeneratedImageIntents
import com.jarvys.agent.GeneratedImageStore
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GeneratedImageComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val session = "compose-image-session"

    @Test
    fun successfulImageDecodesOffMainOpensViewerAndInvokesSaveShareActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = GeneratedImageStore(context)
        val path = store.save(session, UUID.randomUUID().toString(), pngFixture())
        assertTrue(store.resolve(session, path).isFile)
        val prompt = "A cobalt lighthouse on winter waves"
        val event = successEvent(path, prompt)
        var saveEvent: AgentRunUiEvent? = null
        var shareEvent: AgentRunUiEvent? = null
        var shareIntent: Intent? = null
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    GeneratedImageEventCard(event, session, onSave = {
                        saveEvent = it
                    }, onShare = {
                        shareEvent = it
                        shareIntent = GeneratedImageIntents.shareIntent(context, store.resolve(session, path))
                    })
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("generated-image-open-${event.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("generated-image-${event.id}").assertIsDisplayed()
        compose.onNodeWithTag("generated-image-open-${event.id}").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("generated-image-thumbnail-${event.id}", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
                    && compose.onAllNodesWithTag("generated-image-loading-${event.id}", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        assertPromptLabelsNotVisible(prompt, event.generatedImageRevisedPrompt!!)
        assertTrue(compose.onNodeWithTag("generated-image-open-${event.id}").fetchSemanticsNode().config[
            SemanticsProperties.ContentDescription].any { it.contains(prompt) })
        compose.onNodeWithTag("generated-image-open-${event.id}").performClick()
        compose.onNodeWithTag("generated-image-viewer-${event.id}").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("generated-image-viewer-image").fetchSemanticsNodes().isNotEmpty()
        }
        val imageSemantics = compose.onNodeWithTag("generated-image-viewer-image").fetchSemanticsNode().config
        assertTrue(imageSemantics[SemanticsProperties.ContentDescription].any { it.contains(prompt) })
        assertPromptLabelsNotVisible(prompt, event.generatedImageRevisedPrompt!!)
        compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val directory = TestCaptureDirectories.named("ux16-file-delivery-${BuildConfig.FLAVOR}")
                val file = File(directory, "generated-image-viewer-save-share.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                println("UX16_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }

        compose.onNodeWithTag("generated-image-save").performClick()
        assertEquals(event.id, saveEvent?.id)
        // The production Activity/backend export path is covered in MainActivityDownloadFlowTest.

        compose.onNodeWithTag("generated-image-share").performClick()
        assertEquals(event.id, shareEvent?.id)
        val send = requireNotNull(shareIntent)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(context.packageName + ".generated-images",
            send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.authority)

        compose.onNodeWithTag("generated-image-close").performClick()
        compose.onAllNodesWithTag("generated-image-viewer-${event.id}").assertCountEquals(0)
    }

    @Test
    fun restoredEventWithMissingImageShowsMarkerInsteadOfCrashing() {
        val missing = successEvent(UUID.randomUUID().toString() + ".png", "A missing fixture")
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.DARK) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    GeneratedImageEventCard(missing, session, onSave = {}, onShare = {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("generated-image-missing-${missing.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("generated-image-missing-${missing.id}").assertIsDisplayed()
        compose.onNodeWithTag("generated-image-missing-${missing.id}")
            .assertTextContains(ApplicationProvider.getApplicationContext<Context>().getString(R.string.image_missing_file))
        assertPromptLabelsNotVisible("A missing fixture", "An adjusted version of the image prompt")
    }

    @Test
    fun fixturePngCanBeDecodedByTheBackgroundDecoder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = GeneratedImageStore(context)
        val path = store.save(session, UUID.randomUUID().toString(), pngFixture())
        var decodeThread: String? = null
        val bitmap = runBlocking {
            decodeGeneratedImage(store.resolve(session, path), 64, 64) { decodeThread = it }
        }
        assertNotNull(bitmap)
        assertEquals(64, requireNotNull(bitmap).width)
        assertEquals(64, requireNotNull(bitmap).height)
        assertEquals(android.graphics.Color.rgb(20, 36, 28), requireNotNull(bitmap).getPixel(5, 9))
        assertTrue("Bitmap decode must leave the calling thread", !requireNotNull(decodeThread).equals("main", ignoreCase = true))
    }

    @Test
    fun providerFailureStateRendersItsDiagnostic() {
        val failed = AgentRunUiEvent.generatedImageEvent(72L, null, "Rejected request", null,
            "image/png", null, "FAILED", "Content policy rejected this request (content_policy_violation).", 0L)
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.DARK) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    GeneratedImageEventCard(failed, session, onSave = {}, onShare = {})
                }
            }
        }
        val errorNode = compose.onNodeWithTag("generated-image-error-${failed.id}")
        errorNode.assertIsDisplayed()
        assertTrue(errorNode.fetchSemanticsNode().config[SemanticsProperties.Text]
            .any { it.text.contains("content_policy_violation") })
        assertPromptLabelsNotVisible("Rejected request", "An adjusted version of the image prompt")
    }

    @Test
    fun thumbnailFrameUsesDecodedAspectRatioForLandscapePortraitAndSquarePngs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = GeneratedImageStore(context)
        val sizes = listOf(Triple("3:2", 96, 64), Triple("2:3", 64, 96), Triple("1:1", 72, 72))
        val events = sizes.mapIndexed { index, (ratio, width, height) ->
            val path = store.save(session, UUID.randomUUID().toString(), pngFixture(width, height))
            AgentRunUiEvent.generatedImageEvent(201L + index, path, "Private aspect prompt", "", "image/png",
                ratio, "COMPLETED", null, 201L + index)
        }
        val roomyConfiguration = android.content.res.Configuration(context.resources.configuration).apply {
            screenHeightDp = 1200
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides roomyConfiguration) {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        Box(Modifier.width(240.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                events.forEach { event -> GeneratedImageEventCard(event, session, onSave = {}, onShare = {}) }
                            }
                        }
                    }
                }
            }
        }
        events.forEach { event ->
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("generated-image-thumbnail-${event.id}", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
                    && compose.onAllNodesWithTag("generated-image-loading-${event.id}", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            }
        }
        events.zip(sizes).forEach { (event, size) ->
            val imageNode = compose.onNodeWithTag("generated-image-open-${event.id}").fetchSemanticsNode()
            val measuredWidth = imageNode.layoutInfo.width.toDouble()
            val measuredHeight = imageNode.layoutInfo.height.toDouble()
            assertTrue("${size.first} image should have measurable width", measuredWidth > 0.0)
            assertEquals(size.third.toDouble() / size.second, measuredHeight / measuredWidth, 0.02)
        }
    }

    private fun assertPromptLabelsNotVisible(prompt: String, revisedPrompt: String) {
        (listOf("Generated image", "Imagen generada", "Prompt", "Instrucción", "Revised prompt",
            "Instrucción revisada", prompt, revisedPrompt).distinct()).forEach { text ->
            compose.onAllNodesWithText(text, substring = true, ignoreCase = false).assertCountEquals(0)
        }
    }

    private fun successEvent(path: String, prompt: String) = AgentRunUiEvent.generatedImageEvent(
        71L, path, prompt, "An adjusted version of the image prompt", "image/png", "1024x1024",
        "COMPLETED", null, System.currentTimeMillis())

    private fun pngFixture(width: Int = 64, height: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            bitmap.setPixel(x, y, android.graphics.Color.rgb(x * 4, y * 4, (x + y) * 2))
        }
        return ByteArrayOutputStream().also { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
        }.toByteArray()
    }
}
