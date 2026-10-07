package com.jarvys.agent.ui.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import androidx.compose.ui.unit.dp
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native View.draw pixels from production bubbles. No fabricated UI or device/TalkBack claim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class MessageReactionVisualCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.named("ux14-reactions-${BuildConfig.FLAVOR}")
    private var suffix = ""
    private var expectedScale = 1f

    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun lightEnglishNormalText() = captureFlow(false, 1f)
    @Test fun darkEnglishNormalText() = captureFlow(true, 1f)
    @Test fun lightEnglishDoubleText() = captureFlow(false, 2f)
    @Test fun darkEnglishDoubleText() = captureFlow(true, 2f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun lightSpanishNormalText() = captureFlow(false, 1f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun darkSpanishNormalText() = captureFlow(true, 1f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun lightSpanishDoubleText() = captureFlow(false, 2f)
    @Test @Config(qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
    fun darkSpanishDoubleText() = captureFlow(true, 2f)

    private fun captureFlow(dark: Boolean, scale: Float) {
        expectedScale = scale
        RuntimeEnvironment.setFontScale(scale)
        for (resources in listOf(RuntimeEnvironment.getApplication().resources, compose.activity.resources).distinct()) {
            val configuration = Configuration(resources.configuration).apply { fontScale = scale }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        val spanish = compose.activity.resources.configuration.locales[0].language == "es"
        suffix = "${if (dark) "dark" else "light"}_${if (spanish) "es" else "en"}_360dp_font${scale.toInt()}"
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val image = fixtures.image("reaction-photo.png")
        val document = fixtures.document("notes.txt")
        val plainText = if (spanish) "Revisa este plan, por favor." else "Please review this plan."
        var event by mutableStateOf(AgentRunUiEvent.messageEvent(14L, "user", plainText, 14L).copyReaction("👀"))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides fixtures.context, LocalReducedMotion provides false) {
                JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    assertEquals(scale, LocalDensity.current.fontScale, 0.01f)
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("Jarvys", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                            Column(Modifier.testTag("reaction-message-layout")) { IntentMessage(event, fixtures.session) }
                        }
                    }
                }
            }
        }
        awaitText(plainText)
        assertBadge("👀")
        val originalBubble = compose.onNodeWithTag("user-message-bubble-14").fetchSemanticsNode().boundsInRoot
        val initial = capture("acknowledged")
        compose.mainClock.advanceTimeBy(1_000)
        val unchanged = capture("static")
        assertEquals("The reaction does not animate even with reduced motion disabled", initial, unchanged)

        compose.runOnIdle { event = event.copyReaction("🎉") }
        assertBadge("🎉")
        assertEquals(originalBubble, compose.onNodeWithTag("user-message-bubble-14").fetchSemanticsNode().boundsInRoot)
        val updated = capture("updated")
        assertTrue("Replacing the reaction must alter native pixels", updated != initial)

        compose.runOnIdle { event = event.copyReaction("") }
        compose.onNodeWithTag("user-message-reaction-14").assertDoesNotExist()
        val noReaction = compose.onNodeWithTag("reaction-message-layout").fetchSemanticsNode().boundsInRoot
        assertEquals("Removal reserves no row or padding", originalBubble.height, noReaction.height, 0.01f)
        val removed = capture("removed")
        assertTrue("Removing the reaction must alter native pixels", removed != updated)

        compose.runOnIdle { event = event.copy(text = "", attachments = listOf(image, document), reactionEmoji = "👍") }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("chat-attachment-image-${image.id}").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsActions.OnClick) == true
        }
        assertBadge("👍")
        val badge = compose.onNodeWithTag("user-message-reaction-14").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("chat-attachment-image-${image.id}", "chat-attachment-file-${document.id}")) {
            val attachment = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Reaction stays outside both image and document bounds", badge.top > attachment.bottom)
        }
        capture("attachments")
        println("UX14_REACTION_CAPTURE_DIR=${output.absolutePath}")
        println("UX14_REACTION_CAPTURE_LIMIT=Native Robolectric pixels and semantics; actual device TalkBack and OEM emoji require mobile verification.")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        awaitReactionDrawIdle(compose)
    }

    private fun assertBadge(emoji: String) {
        awaitReactionDrawIdle(compose)
        compose.onAllNodesWithTag("user-message-reaction-14").assertCountEquals(1)
        val badge = compose.onNodeWithTag("user-message-reaction-14").assertIsDisplayed().fetchSemanticsNode()
        val bubble = compose.onNodeWithTag("user-message-bubble-14").fetchSemanticsNode()
        assertEquals(listOf(compose.activity.getString(R.string.chat_message_reaction_accessibility, emoji)),
            badge.config[SemanticsProperties.ContentDescription])
        assertFalse(badge.config.contains(SemanticsActions.OnClick))
        assertFalse(badge.config.contains(SemanticsProperties.Role))
        assertTrue("Badge cannot overlay the bubble at either text scale", badge.boundsInRoot.top > bubble.boundsInRoot.bottom)
        assertEquals(bubble.boundsInRoot.right, badge.boundsInRoot.right, 0.01f)
        assertTrue("Badge stays entirely inside the real viewport", badge.boundsInWindow.bottom < compose.activity.window.decorView.height)
        assertTrue("Badge grows to fit text without clipping", badge.boundsInRoot.height >= 20f * expectedScale)
    }

    private fun capture(state: String): String {
        awaitReactionDrawIdle(compose)
        var hash = ""
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            assertEquals(360f, root.width / root.resources.displayMetrics.density, 1f)
            assertEquals(expectedScale, root.resources.configuration.fontScale, 0.01f)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val pixels = IntArray(root.width * root.height)
                bitmap.getPixels(pixels, 0, root.width, 0, 0, root.width, root.height)
                assertTrue("Native image must contain rendered content", pixels.toSet().size > 16)
                val file = File(output, "${state}_$suffix.png")
                TestCaptureDirectories.assertOwned(output, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                val manifest = File(output, "capture-manifest.jsonl")
                TestCaptureDirectories.assertOwned(output, manifest)
                manifest.appendText(JSONObject().put("file", file.name).put("sha256", hash).put("state", state)
                    .put("widthPixels", root.width).put("heightPixels", root.height)
                    .put("density", root.resources.displayMetrics.density).put("fontScale", expectedScale)
                    .put("locale", root.resources.configuration.locales[0].toLanguageTag()).toString() + "\n")
                println("UX14_REACTION_CAPTURE ${file.absolutePath} sha256=$hash")
            } finally { bitmap.recycle() }
        }
        return hash
    }
}
