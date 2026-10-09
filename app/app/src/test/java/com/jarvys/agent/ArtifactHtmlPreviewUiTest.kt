package com.jarvys.agent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ChatFileActions
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.ChatFileTransfers
import com.jarvys.agent.ui.chat.DeliveredArtifactEventCard
import com.jarvys.agent.ui.chat.LocalChatFileActions
import java.io.File
import java.time.Duration
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX33: edge-to-edge persisted HTML thumbnails, icon-only actions and their actual AndroidView request boundary.
 * PNG captures contain native Compose UI, including honest thumbnail fallback states. Robolectric
 * does not render or validate Chromium DOM content; these are not browser-rendering screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h900dp-port-mdpi",
    shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class ArtifactHtmlPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Fixture(
        val session: String,
        val workspace: WorkspaceStore,
        val attachment: ChatAttachment,
        val descriptor: HtmlPreviewDescriptor,
        val html: String,
        val css: String,
        val javascript: String,
        val png: ByteArray,
    )

    @Test fun restoredHtmlCardEnglishLightAt320dpAnd200PercentKeepsThumbnailAndIconActions() =
        restoredCard(false, "en-light-320dp-font200")

    @Test fun restoredHtmlCardEnglishDarkAt320dpAnd200PercentKeepsThumbnailAndIconActions() =
        restoredCard(true, "en-dark-320dp-font200")

    @Test
    @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun restoredHtmlCardSpanishLightAt320dpAnd200PercentKeepsThumbnailAndIconActions() =
        restoredCard(false, "es-light-320dp-font200")

    @Test
    @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun restoredHtmlCardSpanishDarkAt320dpAnd200PercentKeepsThumbnailAndIconActions() =
        restoredCard(true, "es-dark-320dp-font200")

    private fun restoredCard(dark: Boolean, captureName: String) {
        val fixture = snapshot()
        val originalActivity = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(originalActivity, compose.activity)
        val restoredEvent = LocalRunStore(compose.activity).readConversationTimeline(fixture.session)
            .single { it.kind == "delivered_file" }
        assertEquals(fixture.attachment, restoredEvent.deliveredArtifact)
        val downloads = mutableListOf<ChatFileRequest>()
        val shares = mutableListOf<ChatFileRequest>()
        val previews = mutableListOf<String>()
        val selected = mutableStateOf<String?>(null)
        compose.setContent {
            JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f),
                    LocalChatFileActions provides ChatFileActions(download = downloads::add, share = shares::add)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                        val token = selected.value
                        if (token == null) DeliveredArtifactEventCard(restoredEvent, fixture.session) {
                            previews += it
                            selected.value = it
                        }
                        else WorkspacePreviewScreen(token, sessionId = fixture.session)
                    }
                }
            }
        }
        awaitTag("delivered-file-preview-${fixture.attachment.id}")
        settleNativeFrame()
        compose.onAllNodesWithText(fixture.attachment.name, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("text/html", substring = true, useUnmergedTree = true).assertCountEquals(0)
        val card = compose.onNodeWithTag("chat-attachment-file-${fixture.attachment.id}").fetchSemanticsNode().boundsInRoot
        // Capture native UI even if a subsequent accessibility or geometry assertion fails.
        capture(captureName)
        val minimumTarget = 48f * compose.activity.resources.displayMetrics.density
        for (action in listOf("preview", "download", "share")) {
            val button = compose.onNodeWithTag("delivered-file-$action-${fixture.attachment.id}")
                .assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            assertTrue("$action must fit the narrow card without horizontal clipping: $button / $card",
                button.left >= card.left && button.right <= card.right)
            assertTrue("$action must stay inside the card", button.top >= card.top && button.bottom <= card.bottom)
            assertTrue("$action needs at least a 48dp-wide touch target", button.width >= minimumTarget - 1f)
            assertTrue("$action needs at least a 48dp-high touch target", button.height >= minimumTarget - 1f)
        }
        compose.onNodeWithTag("html-thumbnail-static-${fixture.attachment.id}").assertDoesNotExist()
        val thumbnail = compose.onNodeWithTag("delivered-file-preview-${fixture.attachment.id}")
            .assertContentDescriptionEquals(compose.activity.getString(R.string.chat_html_thumbnail_open, fixture.attachment.name))
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Preview pixels occupy the whole card with no header, footer or nested inset", card, thumbnail)
        assertEquals("The entire card is fixed at 3:2, independent of font size", card.width / 1.5f, card.height, 1f)
        for ((action, label) in listOf("download" to R.string.chat_download_action, "share" to R.string.image_action_share)) {
            val text = compose.activity.getString(label)
            val description = compose.activity.getString(R.string.chat_file_action_named, text, fixture.attachment.name)
            compose.onNodeWithTag("delivered-file-$action-${fixture.attachment.id}").assertContentDescriptionEquals(description)
            compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(0)
        }
        compose.onAllNodesWithText(compose.activity.getString(R.string.chat_view_in_jarvys), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("delivered-file-download-${fixture.attachment.id}").performTouchInput { click() }
        compose.onNodeWithTag("delivered-file-share-${fixture.attachment.id}").performTouchInput { click() }
        assertEquals(1, downloads.size)
        assertEquals(fixture.session, downloads.single().sessionId)
        assertEquals("delivered", downloads.single().kind)
        assertEquals(fixture.attachment, downloads.single().attachment)
        assertEquals(downloads, shares)
        compose.onNodeWithTag("delivered-file-preview-${fixture.attachment.id}").performTouchInput { click() }
        assertEquals(listOf(fixture.descriptor.token), previews)
        val web = awaitWebView()
        val response = request(web, shadowOf(web).lastLoadedUrl)
        assertEquals(200, response.statusCode)
        assertEquals(fixture.html, response.data.bufferedReader().use { it.readText() })
    }

    @Test fun thumbnailPointersExportRealImmutableBytesThenOpenAndShareThroughTheTransferViewModel() {
        val provider = FakeDownloadsProvider.install()
        val fixture = snapshot()
        val event = LocalRunStore(compose.activity).readConversationTimeline(fixture.session)
            .single { it.kind == "delivered_file" }
        val request = ChatFileRequest.attachment(fixture.session, fixture.attachment, delivered = true)
        lateinit var transfers: ChatFileTransfers
        compose.runOnUiThread {
            // Avoid AndroidViewModelFactory's process singleton retaining another Robolectric
            // Activity test's Application and therefore a different private file directory.
            transfers = ViewModelProvider(compose.activity,
                ViewModelProvider.AndroidViewModelFactory(compose.activity.application))[ChatFileTransfers::class.java]
        }
        assertSame(compose.activity.application, transfers.getApplication<android.app.Application>())
        assertEquals(compose.activity.filesDir, transfers.getApplication<android.app.Application>().filesDir)
        val downloads = mutableListOf<ChatFileRequest>()
        val previews = mutableListOf<String>()
        fixture.workspace.write(fixture.attachment.name, "<!doctype html><h1>Changed workspace bytes</h1>")
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                val states by transfers.transfers.collectAsState()
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(transfers = states,
                    download = { downloads += it; transfers.download(it) }, share = transfers::share, open = transfers::open,
                    cancel = transfers::cancel)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                        DeliveredArtifactEventCard(event, fixture.session, previews::add)
                    }
                }
            }
        }
        val downloadTag = "delivered-file-download-${fixture.attachment.id}"
        awaitTag(downloadTag)
        settleNativeFrame()
        compose.onNodeWithTag(downloadTag).performTouchInput { click() }
        assertEquals("The pointer reaches the production download callback exactly once", listOf(request), downloads)
        awaitTransfer(transfers, request) { transfers.transfers.value[request.key]?.saved == true }
        val first = requireNotNull(transfers.transfers.value[request.key]?.result)
        assertEquals(DownloadStore.Status.SAVED, first.status)
        assertEquals(1, provider.inserts)
        assertEquals(1, provider.publishes)
        assertArrayEquals("Download receives the immutable delivered bytes", fixture.html.toByteArray(),
            provider.file(provider.rows.keys.single()).readBytes())
        assertNull("Downloading never automatically launches a viewer", transfers.launch.value)
        assertTrue("Download cannot also open preview", previews.isEmpty())

        compose.onNodeWithTag(downloadTag).performTouchInput { click() }
        awaitTransfer(transfers, request) { transfers.transfers.value[request.key]?.result?.status == DownloadStore.Status.ALREADY_SAVED }
        assertEquals("Retrying an already-saved download creates no duplicate", 1, provider.inserts)
        assertEquals(first.uri, transfers.transfers.value[request.key]?.result?.uri)
        awaitTag("delivered-file-open-${fixture.attachment.id}")
        compose.onNodeWithTag("delivered-file-open-${fixture.attachment.id}").performTouchInput { click() }
        awaitTransfer(transfers, request) { transfers.launch.value != null }
        val opened = requireNotNull(transfers.launch.value)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(first.uri, opened.data)
        assertEquals("text/html", opened.type)
        assertTrue(opened.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, opened.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        compose.runOnIdle { transfers.consumeLaunch() }
        compose.onNodeWithTag("delivered-file-share-${fixture.attachment.id}").performTouchInput { click() }
        awaitTransfer(transfers, request) { transfers.launch.value != null }
        val chooser = requireNotNull(transfers.launch.value)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/html", send.type)
        val sharedUri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("${compose.activity.packageName}.chat-files", sharedUri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertArrayEquals("Share receives the same immutable source", fixture.html.toByteArray(),
            compose.activity.contentResolver.openInputStream(sharedUri)!!.use { it.readBytes() })
        assertEquals(1, provider.inserts)
        assertTrue("Saved Open and Share must not bubble to the card preview", previews.isEmpty())
        compose.onNodeWithTag("delivered-file-preview-${fixture.attachment.id}")
            .performTouchInput { click(Offset(center.x, height * .25f)) }
        assertEquals(listOf(fixture.descriptor.token), previews)
    }

    @Test fun missingDeliveredSourceHasNoPreviewOrExportControls() {
        val fixture = snapshot()
        val event = LocalRunStore(compose.activity).readConversationTimeline(fixture.session)
            .single { it.kind == "delivered_file" }
        assertTrue(DeliveredArtifactStore(compose.activity).resolve(fixture.session, fixture.attachment).delete())
        val callbacks = mutableListOf<String>()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(
                    download = { callbacks += "download" }, share = { callbacks += "share" })) {
                    DeliveredArtifactEventCard(event, fixture.session) { callbacks += "preview" }
                }
            }
        }
        awaitTag("attachment-unavailable-${fixture.attachment.id}")
        compose.onNodeWithTag("attachment-unavailable-${fixture.attachment.id}").assertIsDisplayed()
        for (name in listOf("preview", "download", "share", "open", "cancel")) {
            compose.onNodeWithTag("delivered-file-$name-${fixture.attachment.id}").assertDoesNotExist()
        }
        assertTrue(callbacks.isEmpty())
    }

    @Test fun resolvingHtmlCardNeverFlashesFilenameMimeOrStaticCaptionInObservedFrames() {
        val fixture = snapshot()
        val event = LocalRunStore(compose.activity).readConversationTimeline(fixture.session)
            .single { it.kind == "delivered_file" }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) { DeliveredArtifactEventCard(event, fixture.session) }
        }
        fun assertNoMetadata() {
            compose.onAllNodesWithText(fixture.attachment.name, useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithText("text/html", substring = true, useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithTag("html-thumbnail-static-${fixture.attachment.id}").assertDoesNotExist()
        }
        try {
            assertNoMetadata()
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                assertNoMetadata()
                compose.onAllNodesWithTag("delivered-file-preview-${fixture.attachment.id}")
                    .fetchSemanticsNodes().size == 1
            }
            assertNoMetadata()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun deliveredHtmlAndLinkedCssJavascriptAndImageStayImmutableThroughActualPreviewClient() {
        val fixture = snapshot()
        fixture.workspace.write(fixture.attachment.name, "<!doctype html><h1>Mutated workspace document</h1>")
        fixture.workspace.write("styles.css", "body { color: red; }")
        fixture.workspace.write("app.js", "window.changedAfterDelivery = true;")
        fixture.workspace.resolvePreviewPath("pixel.png").writeBytes(byteArrayOf(1, 2, 3))
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                WorkspacePreviewScreen(fixture.descriptor.token, sessionId = fixture.session)
            }
        }
        val web = awaitWebView()
        val entry = Uri.parse(shadowOf(web).lastLoadedUrl)
        val root = "${entry.scheme}://${entry.encodedAuthority}/"
        val expected = mapOf(
            fixture.attachment.name to fixture.html.toByteArray(),
            "styles.css" to fixture.css.toByteArray(),
            "app.js" to fixture.javascript.toByteArray(),
            "pixel.png" to fixture.png,
        )
        for ((path, bytes) in expected) {
            val result = request(web, root + path)
            assertEquals(path, 200, result.statusCode)
            assertArrayEquals("$path must come from the immutable delivered snapshot", bytes,
                result.data.use { it.readBytes() })
            assertEquals("no-store", result.responseHeaders["Cache-Control"])
        }
        assertEquals(403, request(web, "https://outside.invalid/private.txt").statusCode)
        assertEquals(403, request(web, root + "%2e%2e/private.txt").statusCode)
        assertEquals(404, request(web, root + "not-captured.txt").statusCode)
        assertEquals(405, request(web, root + fixture.attachment.name, "POST").statusCode)
        compose.onNodeWithTag("workspace-preview-partial").assertDoesNotExist()
    }

    @Test fun missingOrExternalAssetsKeepPreviewWithAnHonestPartialWarning() {
        val fixture = snapshot(partial = true)
        assertTrue(fixture.descriptor.warnings.isNotEmpty())
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.DARK) {
                WorkspacePreviewScreen(fixture.descriptor.token, sessionId = fixture.session)
            }
        }
        val web = awaitWebView()
        compose.onNodeWithTag("workspace-preview-partial").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_preview_partial)).assertIsDisplayed()
        val entry = Uri.parse(shadowOf(web).lastLoadedUrl)
        val root = "${entry.scheme}://${entry.encodedAuthority}/"
        assertEquals(200, request(web, root + fixture.attachment.name).statusCode)
        assertEquals(404, request(web, root + "missing.css").statusCode)
        assertEquals(403, request(web, "https://outside.invalid/remote.js").statusCode)
    }

    @Test fun nonHtmlApkAndBinaryHtmlKeepDownloadShareWithoutAnHtmlPreviewAction() {
        val session = "ux22-ineligible-${UUID.randomUUID()}"
        val workspace = workspace(session)
        val files = linkedMapOf(
            "notes.txt" to "Ordinary notes".toByteArray(),
            "generated.apk" to byteArrayOf(0x50, 0x4b, 3, 4, 0, 0, 0, 0),
            "binary.html" to byteArrayOf(0, 0xff.toByte(), 0, 0xfe.toByte(), 0),
        )
        val attachments = files.map { (name, bytes) ->
            workspace.write(name, "staged")
            workspace.resolvePreviewPath(name).writeBytes(bytes)
            DeliveredArtifactStore(compose.activity).snapshot(session, workspace, name, null,
                CancellationToken.uncancellable()).also { LocalRunStore(compose.activity).appendDeliveredFile(session, it) }
        }
        for (attachment in attachments) assertNull(DeliveredArtifactStore(compose.activity).previewForAttachment(session, attachment))
        val events = LocalRunStore(compose.activity).readConversationTimeline(session).filter { it.kind == "delivered_file" }
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                    events.forEach { DeliveredArtifactEventCard(it, session) }
                }
            }
        }
        for (attachment in attachments) {
            awaitTag("delivered-file-download-${attachment.id}")
            compose.onNodeWithText(attachment.name, useUnmergedTree = true).assertIsDisplayed()
            for ((action, label) in listOf("download" to R.string.chat_download_action,
                "share" to R.string.image_action_share)) {
                compose.onNodeWithTag("delivered-file-$action-${attachment.id}").assertIsDisplayed()
                    .assertHasClickAction().assertContentDescriptionEquals(compose.activity.getString(
                        R.string.chat_file_action_named, compose.activity.getString(label), attachment.name))
            }
            compose.onNodeWithTag("delivered-file-preview-${attachment.id}").assertDoesNotExist()
        }
        for (label in listOf(R.string.chat_download_action, R.string.image_action_share, R.string.chat_view_in_jarvys)) {
            compose.onAllNodesWithText(compose.activity.getString(label), useUnmergedTree = true).assertCountEquals(0)
        }
        assertNull(findWebView(compose.activity.window.decorView))
    }

    @Test fun anotherConversationCannotOpenATokenButTheOwningConversationStillCan() {
        val fixture = snapshot()
        val selectedSession = mutableStateOf("different-conversation-${UUID.randomUUID()}")
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                WorkspacePreviewScreen(fixture.descriptor.token, sessionId = selectedSession.value)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.workspace_preview_missing))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_preview_missing)).assertIsDisplayed()
        assertNull("Denied ownership must not instantiate a native browser", findWebView(compose.activity.window.decorView))
        compose.runOnIdle { selectedSession.value = fixture.session }
        val web = awaitWebView()
        assertEquals(200, request(web, shadowOf(web).lastLoadedUrl).statusCode)
    }

    private fun snapshot(partial: Boolean = false): Fixture {
        val session = "ux22-html-card-${UUID.randomUUID()}"
        val workspace = workspace(session)
        val name = "garden-dashboard.html"
        val css = "body { color: #14532d; } .marker { padding: 20px; }"
        val javascript = "document.documentElement.dataset.snapshot = 'original';"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="styles.css"><script src="app.js"></script>
            ${if (partial) "<link rel=\"stylesheet\" href=\"missing.css\"><script src=\"https://outside.invalid/remote.js\"></script>" else ""}
            </head><body><h1 class="marker">Original delivered garden</h1><img src="pixel.png" alt="Green marker"></body></html>
        """.trimIndent()
        workspace.write(name, html)
        workspace.write("styles.css", css)
        workspace.write("app.js", javascript)
        workspace.write("pixel.png", "staged")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        try {
            workspace.resolvePreviewPath("pixel.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val png = workspace.resolvePreviewPath("pixel.png").readBytes()
        val artifacts = DeliveredArtifactStore(compose.activity)
        val attachment = artifacts.snapshot(session, workspace, name, null, CancellationToken.uncancellable())
        LocalRunStore(compose.activity).appendDeliveredFile(session, attachment)
        val descriptor = requireNotNull(artifacts.previewForAttachment(session, attachment))
        assertEquals(4, descriptor.fileCount)
        return Fixture(session, workspace, attachment, descriptor, html, css, javascript, png)
    }

    private fun workspace(session: String) = WorkspaceStore(File(compose.activity.filesDir, "jarvys/workspaces"),
        WorkspaceStore.projectIdForSession(session), null, null, null, session, false)

    private fun request(web: WebView, url: String, method: String = "GET"): WebResourceResponse {
        var response: WebResourceResponse? = null
        compose.runOnIdle {
            response = web.webViewClient.shouldInterceptRequest(web, object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(url)
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = method
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            })
        }
        return requireNotNull(response)
    }

    private fun awaitTransfer(transfers: ChatFileTransfers, request: ChatFileRequest, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                val state = transfers.transfers.value[request.key]
                assertNull("The real transfer failed: $state; notice=${transfers.notice.value}", state?.failure)
                condition()
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("The real transfer did not finish: ${transfers.transfers.value[request.key]}; " +
                "notice=${transfers.notice.value}; launch=${transfers.launch.value}", failure)
        }
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
    }

    private fun awaitWebView(): WebView {
        var found: WebView? = null
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            compose.runOnUiThread { found = findWebView(compose.activity.window.decorView) }
            found != null
        }
        compose.waitForIdle()
        return requireNotNull(found)
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun settleNativeFrame() {
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
    }

    private fun capture(name: String) {
        settleNativeFrame()
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val directory = TestCaptureDirectories.named("ux33-html-thumbnail-${BuildConfig.FLAVOR}")
                val file = File(directory, "$name.png")
                TestCaptureDirectories.assertOwned(directory, file)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                println("UX33_CAPTURE=${file.absolutePath}")
            } finally { bitmap.recycle() }
        }
    }
}
