package com.jarvys.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.format.Formatter
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import com.jarvys.agent.BuildConfig
import com.jarvys.agent.ChatAttachment
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.TestCaptureDirectories
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Native production attachment views and pointer events, recovered against original v28. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class AttachmentDisplayRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val output = TestCaptureDirectories.named("recovered-attachment-display-${BuildConfig.FLAVOR}")

    @Test fun lightImagesHideMetadataAndDocumentsRetainIt() = checkReadyAttachments(false)
    @Test fun darkImagesHideMetadataAndDocumentsRetainIt() = checkReadyAttachments(true)

    private fun checkReadyAttachments(dark: Boolean) {
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val image = fixtures.image("image-metadata-must-stay-hidden.png")
        val document = fixtures.document("review-notes.txt")
        var pending by mutableStateOf(listOf(image.pending(), document.pending()))
        val removed = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides fixtures.context, LocalReducedMotion provides true) {
                JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PendingChatAttachments(pending, fixtures.session, onRemove = { id ->
                            removed += id
                            pending = pending.filterNot { it.id == id }
                        })
                        UserChatAttachments(listOf(image, document), fixtures.session)
                    }
                }
            }
        }
        awaitImage(image)
        awaitReactionDrawIdle(compose)
        assertHiddenMetadata(image)
        compose.onAllNodesWithText(document.name, useUnmergedTree = true).assertCountEquals(2)
        val documentSize = Formatter.formatShortFileSize(fixtures.context, document.sizeBytes)
        // Pending files retain their standalone size; sent file cards use compact size + MIME.
        compose.onAllNodesWithText(documentSize, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("$documentSize • ${document.mimeType}", useUnmergedTree = true).assertIsDisplayed()
        val preview = compose.onNodeWithTag("pending-attachment-${image.id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(136f * density, preview.width, 0.1f)
        assertEquals(96f * density, preview.height, 0.1f)
        val thumbnail = compose.onNodeWithTag("pending-attachment-image-${image.id}").fetchSemanticsNode()
        assertFalse("v28 pending thumbnails do not open a viewer", thumbnail.config.contains(SemanticsActions.OnClick))
        val remove = compose.onNodeWithTag("chat-attachment-remove-${image.id}").fetchSemanticsNode()
        assertEquals(listOf(compose.activity.getString(R.string.chat_attachment_remove, image.name)),
            remove.config[SemanticsProperties.ContentDescription])
        assertTrue(remove.boundsInRoot.width >= 48f * density)
        capture(if (dark) "dark-ready-images-and-files" else "light-ready-images-and-files")

        compose.onNodeWithTag("chat-attachment-remove-${image.id}").performTouchInput { click() }
        assertEquals(listOf(image.id), removed)
        compose.onNodeWithTag("pending-attachment-${image.id}").assertDoesNotExist()
        compose.onNodeWithTag("pending-attachment-${document.id}").assertIsDisplayed()
        compose.onNodeWithTag("chat-attachment-image-${image.id}").assertIsDisplayed()
        compose.onNodeWithTag("chat-attachment-viewer-${image.id}").assertDoesNotExist()
        assertTrue("UI removal does not delete the stored image", fixtures.store.resolve(fixtures.session, image).isFile)
        compose.onNodeWithTag("chat-attachment-remove-${document.id}").performTouchInput { click() }
        assertEquals(listOf(image.id, document.id), removed)
    }

    @Test fun copyingAndFailedImagesHideMetadataButExposeStatusAndRemainRemovable() {
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val image = fixtures.image("private-image-name.png")
        var pending by mutableStateOf(image.pending().copy(attachment = null, copying = true))
        var removed: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides fixtures.context) {
                JarvysOwnTheme(JarvysThemeMode.DARK) {
                    PendingChatAttachments(listOf(pending), fixtures.session, { removed = it })
                }
            }
        }
        compose.onNodeWithTag("chat-attachment-copying-${image.id}").assertIsDisplayed()
        assertHiddenMetadata(image)
        assertFalse(canSendWithAttachments("Draft", listOf(pending)))
        compose.runOnIdle { pending = pending.copy(copying = false, error = "") }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_attachment_copy_failed)).assertIsDisplayed()
        assertHiddenMetadata(image)
        assertFalse(canSendWithAttachments("Draft", listOf(pending)))
        compose.runOnIdle { pending = pending.copy(error = "Image import interrupted") }
        compose.onNodeWithText("Image import interrupted").assertIsDisplayed()
        assertHiddenMetadata(image)
        compose.onNodeWithTag("chat-attachment-remove-${image.id}").performTouchInput { click() }
        assertEquals(image.id, removed)
        compose.runOnIdle { pending = image.pending() }
        assertTrue(canSendWithAttachments("", listOf(pending)))
        compose.onNodeWithTag("pending-attachment-image-${image.id}").assertIsDisplayed()
        assertHiddenMetadata(image)
    }

    @Test fun pendingDocumentsKeepNamesDuringCopyAndFailure() {
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val file = fixtures.document("important-file.txt")
        var pending by mutableStateOf(file.pending().copy(attachment = null, copying = true))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides fixtures.context) {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) { PendingChatAttachments(listOf(pending), fixtures.session, {}) }
            }
        }
        compose.onNodeWithText(file.name).assertIsDisplayed()
        compose.onNodeWithTag("chat-attachment-copying-${file.id}").assertIsDisplayed()
        compose.runOnIdle { pending = pending.copy(copying = false, error = "File import interrupted") }
        compose.onNodeWithText(file.name).assertIsDisplayed()
        compose.onNodeWithText("File import interrupted").assertIsDisplayed()
        compose.runOnIdle { pending = file.pending() }
        compose.onNodeWithText(file.name).assertIsDisplayed()
        compose.onNodeWithText(Formatter.formatShortFileSize(fixtures.context, file.sizeBytes)).assertIsDisplayed()
        assertEquals(168f, compose.onNodeWithTag("pending-attachment-${file.id}").fetchSemanticsNode().boundsInRoot.width, 0.1f)
    }

    @Test fun sentImageViewerKeepsMetadataHiddenAndSupportsPinchCloseAndReopen() {
        val fixtures = ReactionAttachmentFixtures(compose.activity)
        val image = fixtures.image("uploaded-photo.png")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides fixtures.context) {
                JarvysOwnTheme(JarvysThemeMode.DARK) { UserChatAttachments(listOf(image), fixtures.session) }
            }
        }
        awaitImage(image)
        assertHiddenMetadata(image)
        compose.onNodeWithTag("chat-attachment-image-${image.id}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat-attachment-viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-attachment-viewer-${image.id}").assertIsDisplayed()
        assertHiddenMetadata(image)
        compose.waitForIdle()
        val fitted = capture("uploaded-viewer-fit", ShadowDialog.getLatestDialog().window!!.decorView)
        compose.onNodeWithTag("chat-attachment-viewport").performTouchInput {
            pinch(center + Offset(-30f, 0f), center + Offset(30f, 0f),
                center + Offset(-100f, 0f), center + Offset(100f, 0f))
        }
        compose.waitForIdle()
        val zoomed = capture("uploaded-viewer-pinched", ShadowDialog.getLatestDialog().window!!.decorView)
        assertTrue("Actual two-finger input changes the uploaded-image pixels", fitted != zoomed)
        assertHiddenMetadata(image)
        compose.onNodeWithTag("chat-attachment-viewer-close").performClick()
        compose.onNodeWithTag("chat-attachment-viewer-${image.id}").assertDoesNotExist()
        compose.onNodeWithTag("chat-attachment-image-${image.id}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat-attachment-viewport").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("Reopening resets the transform", fitted,
            capture("uploaded-viewer-reopened", ShadowDialog.getLatestDialog().window!!.decorView))
        compose.onNodeWithTag("chat-attachment-viewer-close").performClick()
        assertTrue(fixtures.store.resolve(fixtures.session, image).isFile)
    }

    private fun ChatAttachment.pending() = PendingChatAttachment(id, name, mimeType, sizeBytes, kind, this)

    private fun awaitImage(image: ChatAttachment) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("chat-attachment-image-${image.id}").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsActions.OnClick) == true
        }
    }

    private fun assertHiddenMetadata(image: ChatAttachment) {
        compose.onAllNodesWithText(image.name, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText(Formatter.formatShortFileSize(compose.activity, image.sizeBytes), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText(image.mimeType, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun capture(name: String, view: View = compose.activity.window.decorView): String {
        var digest = ""
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val file = File(output, "$name.png")
                TestCaptureDirectories.assertOwned(output, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                println("ATTACHMENT_RECOVERY_CAPTURE=${file.absolutePath} sha256=$digest")
            } finally { bitmap.recycle() }
        }
        return digest
    }
}
