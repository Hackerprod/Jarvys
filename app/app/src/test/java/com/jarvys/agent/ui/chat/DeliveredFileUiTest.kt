package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ChatFileActions
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.ChatFileTransfer
import com.jarvys.agent.ui.chat.DeliveredArtifactEventCard
import com.jarvys.agent.ui.chat.LocalChatFileActions
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real native cards rebuilt from the persisted transcript, including their restored controls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi",
    shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class DeliveredFileUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun restoredFileShowsMetadataAndWorkingScopedControlsInLightTheme() = restoredFile(false)
    @Test fun restoredFileShowsMetadataAndWorkingScopedControlsInDarkTheme() = restoredFile(true)

    private fun restoredFile(dark: Boolean) {
        val session = "delivered-ui-${UUID.randomUUID()}"
        val attachment = deliver(session, "research-notes.txt", "The exported notes remain immutable.")
        val event = LocalRunStore(compose.activity).readConversationTimeline(session).single { it.kind == "delivered_file" }
        val downloads = mutableListOf<ChatFileRequest>()
        val shares = mutableListOf<ChatFileRequest>()
        var states by mutableStateOf(emptyMap<String, ChatFileTransfer>())
        compose.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(states, downloads::add, shares::add)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) { DeliveredArtifactEventCard(event, session) }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("delivered-file-download-${attachment.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(attachment.name).assertIsDisplayed()
        compose.onNodeWithText(attachment.mimeType, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("delivered-file-download-${attachment.id}").performClick()
        compose.onNodeWithTag("delivered-file-share-${attachment.id}").performClick()
        assertEquals(session, downloads.single().sessionId)
        assertEquals("delivered", downloads.single().kind)
        assertEquals(attachment, downloads.single().attachment)
        assertEquals(downloads, shares)
        capture(if (dark) "delivered-file-dark" else "delivered-file-light")
        compose.runOnIdle { states = mapOf(downloads.single().key to ChatFileTransfer(downloads.single(), busy = true)) }
        compose.onNodeWithTag("delivered-file-download-${attachment.id}").assertIsNotEnabled()
    }

    @Test fun deliveredImageRestoresAsImageAndViewerHasDownloadShareWithoutTinyFilename() {
        val session = "delivered-image-${UUID.randomUUID()}"
        val workspace = WorkspaceStore(File(compose.activity.filesDir, "jarvys/workspaces"),
            WorkspaceStore.projectIdForSession(session), null, null, null, session, false)
        workspace.write("landscape.png", "staged")
        val image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        workspace.resolvePreviewPath("landscape.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val attachment = DeliveredArtifactStore(compose.activity).snapshot(session, workspace, "landscape.png", null, CancellationToken.uncancellable())
        LocalRunStore(compose.activity).appendDeliveredFile(session, attachment)
        val event = LocalRunStore(compose.activity).readConversationTimeline(session).single { it.kind == "delivered_file" }
        val downloaded = mutableListOf<ChatFileRequest>()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.DARK) {
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(download = downloaded::add)) {
                    DeliveredArtifactEventCard(event, session)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("delivered-attachment-image-${attachment.id}").fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) == true
        }
        assertTrue(compose.onAllNodesWithText(attachment.name, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("delivered-attachment-image-${attachment.id}").performClick()
        compose.onNodeWithTag("chat-viewer-download-${attachment.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-viewer-share-${attachment.id}").assertIsDisplayed()
        assertEquals(session, downloaded.single().sessionId)
        assertTrue(compose.onAllNodesWithText(attachment.name, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("chat-attachment-viewer-close").performClick()
        compose.onNodeWithTag("chat-attachment-viewer-${attachment.id}").assertDoesNotExist()
    }

    @Test fun undecodableImageStillExposesOriginalFileActions() {
        val session = "delivered-svg-${UUID.randomUUID()}"
        val attachment = deliver(session, "diagram.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\"/>")
        assertTrue(attachment.isImage)
        val event = LocalRunStore(compose.activity).readConversationTimeline(session).single { it.kind == "delivered_file" }
        val requests = mutableListOf<ChatFileRequest>()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(download = requests::add)) {
                    DeliveredArtifactEventCard(event, session)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat-preview-download-${attachment.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-preview-download-${attachment.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chat-preview-share-${attachment.id}").assertIsDisplayed()
        assertEquals(attachment.id, requests.single().artifactId)
        capture("unsupported-image-actions")
    }

    @Test fun missingRestoredFileHasHonestUnavailableStateWithoutEnabledDownload() {
        val session = "delivered-missing-${UUID.randomUUID()}"
        val attachment = deliver(session, "missing.txt", "gone")
        val store = DeliveredArtifactStore(compose.activity)
        val file = store.resolve(session, attachment)
        check(file.delete())
        val event = LocalRunStore(compose.activity).readConversationTimeline(session).single { it.kind == "delivered_file" }
        compose.setContent { JarvysOwnTheme(JarvysThemeMode.LIGHT) { DeliveredArtifactEventCard(event, session) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment-unavailable-${attachment.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment-unavailable-${attachment.id}").assertIsDisplayed()
        compose.onNodeWithTag("delivered-file-download-${attachment.id}").assertDoesNotExist()
    }

    private fun deliver(session: String, filename: String, body: String): ChatAttachment {
        val workspace = WorkspaceStore(File(compose.activity.filesDir, "jarvys/workspaces"),
            WorkspaceStore.projectIdForSession(session), null, null, null, session, false)
        workspace.write(filename, body)
        return DeliveredArtifactStore(compose.activity).snapshot(session, workspace, filename, null, CancellationToken.uncancellable())
            .also { LocalRunStore(compose.activity).appendDeliveredFile(session, it) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val directory = TestCaptureDirectories.named("ux16-file-delivery-${BuildConfig.FLAVOR}")
                val file = File(directory, "$name.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                println("UX16_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }
}
