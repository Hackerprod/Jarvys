package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ChatFileActions
import com.jarvys.agent.ui.chat.ChatFileButtons
import com.jarvys.agent.ui.chat.ChatFileIconButtons
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.ChatFileTransfer
import com.jarvys.agent.ui.chat.DeliveredArtifactEventCard
import com.jarvys.agent.ui.chat.LocalChatFileActions
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX28 Compose contract tests. Transfer results are presentation-state fixtures, not proof of a
 * download. Native captures verify card geometry and fallback UI, never Chromium-rendered pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h900dp-port-mdpi",
    shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class HtmlThumbnailCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun iconOnlyActionsHaveNamedEnglishDescriptionsAndKeepTheirSourceRequest() = idleActions()

    @Test
    @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun iconOnlyActionsHaveNamedSpanishDescriptionsAndKeepTheirSourceRequest() = idleActions()

    private fun idleActions() {
        val request = request()
        val downloads = mutableListOf<ChatFileRequest>()
        val shares = mutableListOf<ChatFileRequest>()
        showIcons(request, ChatFileActions(download = downloads::add, share = shares::add))
        assertIcon(request, "download", R.string.chat_download_action).performClick()
        assertIcon(request, "share", R.string.image_action_share).performClick()
        assertEquals(listOf(request), downloads)
        assertEquals(listOf(request), shares)
        assertNoActionText()
        compose.onNodeWithTag(tag(request, "preview")).assertDoesNotExist()
        compose.onNodeWithTag(tag(request, "cancel")).assertDoesNotExist()
        compose.onNodeWithTag(tag(request, "open")).assertDoesNotExist()
    }

    @Test fun busyDownloadIsDisabledAndHasAnAccessibleCancelAction() = busyActions(waitingForPermission = false)

    @Test fun pendingPermissionPreservesBusyAndCancelWithoutStartingAnotherDownload() =
        busyActions(waitingForPermission = true)

    private fun busyActions(waitingForPermission: Boolean) {
        val request = request()
        val downloads = mutableListOf<ChatFileRequest>()
        val cancels = mutableListOf<ChatFileRequest>()
        val state = ChatFileTransfer(request, busy = !waitingForPermission, waitingForPermission = waitingForPermission)
        showIcons(request, ChatFileActions(transfers = mapOf(request.key to state),
            download = downloads::add, cancel = cancels::add))
        assertIcon(request, "download", R.string.chat_download_action)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                compose.activity.getString(R.string.chat_download_saving)))
        assertIcon(request, "cancel", R.string.settings_cancel).assertIsEnabled().performClick()
        assertEquals(emptyList<ChatFileRequest>(), downloads)
        assertEquals(listOf(request), cancels)
        compose.onNodeWithTag(tag(request, "share")).assertDoesNotExist()
        compose.onNodeWithTag(tag(request, "open")).assertDoesNotExist()
        assertNoActionText()
    }

    @Test fun savedDownloadRetainsDownloadShareAndAccessibleOpen() = savedActions(DownloadStore.Status.SAVED)

    @Test fun alreadySavedDownloadStillHasAccessibleOpen() = savedActions(DownloadStore.Status.ALREADY_SAVED)

    private fun savedActions(status: DownloadStore.Status) {
        val request = request()
        val opens = mutableListOf<ChatFileRequest>()
        val downloads = mutableListOf<ChatFileRequest>()
        val shares = mutableListOf<ChatFileRequest>()
        val state = ChatFileTransfer(request, result = savedResult(request, status))
        showIcons(request, ChatFileActions(transfers = mapOf(request.key to state),
            download = downloads::add, share = shares::add, open = opens::add))
        assertIcon(request, "download", R.string.chat_download_action).assertIsEnabled().performClick()
        assertIcon(request, "share", R.string.image_action_share).performClick()
        assertIcon(request, "open", R.string.chat_download_open).performClick()
        assertEquals(listOf(request), downloads)
        assertEquals(listOf(request), shares)
        assertEquals(listOf(request), opens)
        compose.onNodeWithTag(tag(request, "cancel")).assertDoesNotExist()
        assertNoActionText()
    }

    @Test fun failedDownloadRestoresRetryAndShareWithoutAdvertisingSavedOpen() {
        val request = request()
        val retries = mutableListOf<ChatFileRequest>()
        showIcons(request, ChatFileActions(transfers = mapOf(request.key to
            ChatFileTransfer(request, failure = ChatFileTransfer.Failure.DOWNLOAD)), download = retries::add))
        assertIcon(request, "download", R.string.chat_download_action).assertIsEnabled().performClick()
        assertIcon(request, "share", R.string.image_action_share).assertIsEnabled()
        assertEquals(listOf(request), retries)
        compose.onNodeWithTag(tag(request, "open")).assertDoesNotExist()
        compose.onNodeWithTag(tag(request, "cancel")).assertDoesNotExist()
    }

    @Test fun identicalArtifactIdsDoNotLeakTransferStateOrCallbacksAcrossConversations() {
        val original = request()
        val other = original.copy(sessionId = "another-conversation")
        val selected = mutableStateOf(original)
        val downloads = mutableListOf<ChatFileRequest>()
        val shares = mutableListOf<ChatFileRequest>()
        val cancels = mutableListOf<ChatFileRequest>()
        val actions = ChatFileActions(transfers = mapOf(original.key to ChatFileTransfer(original, busy = true)),
            download = downloads::add, share = shares::add, cancel = cancels::add)
        compose.setContent {
            CardTestTheme {
                CompositionLocalProvider(LocalChatFileActions provides actions) { ChatFileIconButtons(selected.value) }
            }
        }
        assertIcon(original, "download", R.string.chat_download_action).assertIsNotEnabled()
        compose.runOnIdle { selected.value = other }
        assertIcon(other, "download", R.string.chat_download_action).assertIsEnabled().performClick()
        assertIcon(other, "share", R.string.image_action_share).performClick()
        compose.onNodeWithTag(tag(other, "cancel")).assertDoesNotExist()
        assertEquals(listOf(other), downloads)
        assertEquals(listOf(other), shares)
        compose.runOnIdle { selected.value = original }
        assertIcon(original, "download", R.string.chat_download_action).assertIsNotEnabled()
        assertIcon(original, "cancel", R.string.settings_cancel).performClick()
        assertEquals(listOf(original), cancels)
    }

    @Test fun imageViewerButtonHelperRetainsItsLabeledDownloadAndShareControls() {
        val request = request().copy(mimeType = "image/png", displayName = "landscape.png")
        val downloads = mutableListOf<ChatFileRequest>()
        compose.setContent {
            CardTestTheme {
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(download = downloads::add)) {
                    ChatFileButtons(request, tagPrefix = "chat-viewer")
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.chat_download_action)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.image_action_share)).assertIsDisplayed()
        compose.onNodeWithTag("chat-viewer-download-${request.artifactId}").performClick()
        assertEquals(listOf(request), downloads)
    }

    @Test fun longFilenameEnglishLightKeepsThreeLineHeaderAndCompactMetadataAtFont200() =
        longFilenameCard(false, "en-light-long-filename-320dp-font200")

    @Test
    @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun longFilenameSpanishDarkKeepsThreeLineHeaderAndCompactMetadataAtFont200() =
        longFilenameCard(true, "es-dark-long-filename-320dp-font200")

    private fun longFilenameCard(dark: Boolean, captureName: String) {
        val (session, attachment) = delivered("quarterly-garden-performance-dashboard-final-export-for-review.html")
        val event = LocalRunStore(compose.activity).readConversationTimeline(session).single { it.kind == "delivered_file" }
        val previews = mutableListOf<String>()
        compose.setContent { CardTestTheme(dark) { DeliveredArtifactEventCard(event, session, previews::add) } }
        awaitTag("delivered-file-preview-${attachment.id}")
        capture(captureName)
        val card = compose.onNodeWithTag("chat-attachment-file-${attachment.id}").fetchSemanticsNode().boundsInRoot
        val filename = compose.onNodeWithText(attachment.name, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        filename.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("A long filename is capped at three lines", 3, layouts.single().layoutInput.maxLines)
        assertTrue(layouts.single().lineCount <= 3)
        // More than three lines may intentionally ellipsize; the rendered three-line node must fit.
        val filenameBounds = filename.fetchSemanticsNode().boundsInRoot
        assertEquals("The visible filename height is not clipped", layouts.single().size.height.toFloat(),
            filenameBounds.height, 1f)
        val metadata = compose.onNodeWithText("text/html", substring = true, useUnmergedTree = true).assertIsDisplayed()
        val metadataText = metadata.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
        assertTrue("Size and MIME share one compact metadata text", metadataText.contains("•"))
        val metadataBounds = metadata.fetchSemanticsNode().boundsInRoot
        assertTrue("Metadata stays inside the card", metadataBounds.left >= card.left && metadataBounds.right <= card.right)
        compose.onNodeWithTag("html-thumbnail-static-${attachment.id}")
            .assertIsDisplayed().assertTextEquals(compose.activity.getString(R.string.chat_html_thumbnail_static))
        val preview = compose.onNodeWithTag("delivered-file-preview-${attachment.id}")
            .assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.chat_html_thumbnail_open, attachment.name))
        val bounds = preview.fetchSemanticsNode().boundsInRoot
        assertEquals("Large text never changes the thumbnail aspect ratio", bounds.width / 1.5f, bounds.height, 1f)
        assertTrue("The card remains completely visible at 320dp and 200% font",
            card.bottom <= compose.activity.window.decorView.height.toFloat())
        val request = ChatFileRequest.attachment(session, attachment, delivered = true)
        assertIcon(request, "download", R.string.chat_download_action, "delivered-file")
        assertIcon(request, "share", R.string.image_action_share, "delivered-file")
        assertNoActionText()
        preview.performTouchInput { click() }
        val descriptor = requireNotNull(DeliveredArtifactStore(compose.activity).previewForAttachment(session, attachment))
        assertEquals(listOf(descriptor.token), previews)
    }

    @Test fun deliveredHtmlCardInAnotherConversationHasNoPreviewOrExportControls() {
        val (session, attachment) = delivered("private-preview.html")
        val event = LocalRunStore(compose.activity).readConversationTimeline(session).single { it.kind == "delivered_file" }
        compose.setContent { CardTestTheme { DeliveredArtifactEventCard(event, "different-conversation") } }
        awaitTag("attachment-unavailable-${attachment.id}")
        compose.onNodeWithTag("attachment-unavailable-${attachment.id}").assertIsDisplayed()
        for (action in listOf("preview", "download", "share", "open", "cancel")) {
            compose.onNodeWithTag("delivered-file-$action-${attachment.id}").assertDoesNotExist()
        }
    }

    @Composable
    private fun CardTestTheme(dark: Boolean = false, content: @Composable () -> Unit) {
        JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) { content() }
            }
        }
    }

    private fun showIcons(request: ChatFileRequest, actions: ChatFileActions) {
        compose.setContent {
            CardTestTheme {
                CompositionLocalProvider(LocalChatFileActions provides actions) { ChatFileIconButtons(request) }
            }
        }
    }

    private fun assertIcon(request: ChatFileRequest, action: String, label: Int,
        tagPrefix: String = "chat-file"): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag(request, action, tagPrefix)).assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.chat_file_action_named,
                compose.activity.getString(label), request.displayName))
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val minimumTarget = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("$action width is at least 48dp: $bounds", bounds.width >= minimumTarget - 1f)
        assertTrue("$action height is at least 48dp: $bounds", bounds.height >= minimumTarget - 1f)
        return node
    }

    private fun assertNoActionText() {
        for (label in listOf(R.string.chat_download_action, R.string.image_action_share, R.string.settings_cancel,
            R.string.chat_download_open, R.string.chat_download_saving, R.string.chat_view_in_jarvys)) {
            compose.onAllNodesWithText(compose.activity.getString(label), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun request() = ChatFileRequest("source-conversation", "delivered", UUID.randomUUID().toString(),
        "garden-dashboard.html", "text/html")

    private fun tag(request: ChatFileRequest, action: String, prefix: String = "chat-file") =
        "$prefix-$action-${request.artifactId}"

    /** Construct only an action-state fixture; DownloadStore has its own real export tests. */
    private fun savedResult(request: ChatFileRequest, status: DownloadStore.Status): DownloadStore.Result =
        DownloadStore.Result::class.java.getDeclaredConstructor(DownloadStore.Status::class.java, Uri::class.java,
            String::class.java, String::class.java, Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
            .newInstance(status, Uri.parse("content://downloads/test-fixture"), request.displayName, request.mimeType, 128L, null)

    private fun delivered(name: String): Pair<String, ChatAttachment> {
        val session = "ux28-thumbnail-card-${UUID.randomUUID()}"
        val workspace = WorkspaceStore(File(compose.activity.filesDir, "jarvys/workspaces"),
            WorkspaceStore.projectIdForSession(session), null, null, null, session, false)
        workspace.write(name, "<!doctype html><html><body><h1>Immutable garden report</h1></body></html>")
        val attachment = DeliveredArtifactStore(compose.activity).snapshot(session, workspace, name, null,
            CancellationToken.uncancellable())
        LocalRunStore(compose.activity).appendDeliveredFile(session, attachment)
        return session to attachment
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                val root = compose.activity.window.decorView
                val config = root.resources.configuration
                val density = root.resources.displayMetrics.density
                val width = (config.screenWidthDp * density).toInt()
                val height = (config.screenHeightDp * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val directory = TestCaptureDirectories.named("ux28-html-thumbnail-${BuildConfig.FLAVOR}")
                val file = File(directory, "$name.png")
                TestCaptureDirectories.assertOwned(directory, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("UX28_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }
}
