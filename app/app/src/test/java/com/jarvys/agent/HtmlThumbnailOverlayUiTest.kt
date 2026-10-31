package com.jarvys.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ChatFileActions
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.ChatFileTransfer
import com.jarvys.agent.ui.chat.HtmlThumbnailCardSurface
import com.jarvys.agent.ui.chat.LocalChatFileActions
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX33 interaction and native Compose pixel checks. The opaque bitmap is a presentation fixture,
 * not Chromium output. Actual HTML source authorization and export are covered by the card tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h900dp-port-mdpi")
class HtmlThumbnailOverlayUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val request = ChatFileRequest("ux33-overlay", "delivered", "opaque-fixture",
        "quarterly-garden-performance-dashboard-final-export-for-review.html", "text/html")
    private val fixtureColor = android.graphics.Color.rgb(64, 200, 160)
    private val previews = mutableListOf<String>()
    private val downloads = mutableListOf<ChatFileRequest>()
    private val shares = mutableListOf<ChatFileRequest>()
    private val cancels = mutableListOf<ChatFileRequest>()
    private val opens = mutableListOf<ChatFileRequest>()

    @Test fun pointerCentersAndOuterFourDpHitTheActionsWhileTheGapOpensPreview() {
        show()
        assertGeometry()
        for (action in listOf("download", "share")) {
            val node = action(action)
            node.performTouchInput { click() }
            // 22dp lies outside the 20dp visual radius but within the 24dp target radius.
            node.performTouchInput { click(Offset(center.x + dp(22f), center.y)) }
            node.performTouchInput { click(Offset(center.x - dp(22f), center.y)) }
        }
        assertEquals(List(3) { request }, downloads)
        assertEquals(List(3) { request }, shares)
        assertTrue("Child action pointers must never open the preview", previews.isEmpty())
        clickGap()
        preview().performTouchInput { click(Offset(center.x, height * .25f)) }
        assertEquals(listOf(request.artifactId, request.artifactId), previews)
        assertEquals(3, downloads.size)
        assertEquals(3, shares.size)
    }

    @Test fun disabledBusyDownloadConsumesCenterAndOuterTargetPointersWithoutOpeningPreview() =
        assertBusyPointers(waitingForPermission = false)

    @Test fun permissionWaitKeepsDownloadDisabledAndCancelReachableWithoutOpeningPreview() =
        assertBusyPointers(waitingForPermission = true)

    private fun assertBusyPointers(waitingForPermission: Boolean) {
        show(ChatFileTransfer(request, busy = !waitingForPermission, waitingForPermission = waitingForPermission))
        assertGeometry(secondAction = "cancel")
        action("download").assertIsNotEnabled().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, compose.activity.getString(R.string.chat_download_saving)))
        for (offset in listOf(0f, -22f, 22f)) {
            action("download").performTouchInput { click(Offset(center.x + dp(offset), center.y)) }
        }
        assertTrue("A disabled Download must not bubble pointers to preview", previews.isEmpty())
        assertTrue(downloads.isEmpty())
        assertTrue(shares.isEmpty())
        action("cancel").performTouchInput { click(Offset(center.x + dp(22f), center.y)) }
        assertEquals(listOf(request), cancels)
        assertTrue(previews.isEmpty())
        compose.onNodeWithTag(tag("share")).assertDoesNotExist()
        clickGap(secondAction = "cancel")
        assertEquals(listOf(request.artifactId), previews)
    }

    @Test fun failureRestoresRetryAndShareWithTheirOriginalSourceRequest() {
        val state = mutableStateOf<ChatFileTransfer?>(ChatFileTransfer(request, busy = true))
        compose.setContent {
            Scene {
                CompositionLocalProvider(LocalChatFileActions provides actions(state.value)) {
                    HtmlThumbnailCardSurface(request, image = null, loading = false,
                        onOpen = { previews += request.artifactId })
                }
            }
        }
        action("download").assertIsNotEnabled()
        compose.runOnIdle { state.value = ChatFileTransfer(request, failure = ChatFileTransfer.Failure.DOWNLOAD) }
        action("download").assertIsEnabled().performTouchInput { click() }
        action("share").assertIsEnabled().performTouchInput { click() }
        assertEquals(listOf(request), downloads)
        assertEquals(listOf(request), shares)
        compose.onNodeWithTag(tag("open")).assertDoesNotExist()
        compose.onNodeWithTag(tag("cancel")).assertDoesNotExist()
        assertTrue(previews.isEmpty())
    }

    @Test fun savedAndAlreadySavedKeepThreeNamedTargetsAndBothGapsOpenOnlyPreview() {
        val state = mutableStateOf(ChatFileTransfer(request, result = savedResult(DownloadStore.Status.SAVED)))
        compose.setContent {
            Scene {
                CompositionLocalProvider(LocalChatFileActions provides actions(state.value)) {
                    HtmlThumbnailCardSurface(request, image = null, loading = false,
                        onOpen = { previews += request.artifactId })
                }
            }
        }
        for ((index, status) in listOf(DownloadStore.Status.SAVED, DownloadStore.Status.ALREADY_SAVED).withIndex()) {
            compose.runOnIdle { state.value = ChatFileTransfer(request, result = savedResult(status)) }
            assertGeometry(saved = true)
            for ((name, label) in listOf("download" to R.string.chat_download_action,
                "share" to R.string.image_action_share, "open" to R.string.chat_download_open)) {
                action(name).assertIsEnabled().assertContentDescriptionEquals(compose.activity.getString(
                    R.string.chat_file_action_named, compose.activity.getString(label), request.displayName))
                    .performTouchInput { click(Offset(center.x + dp(22f), center.y)) }
            }
            assertEquals(List(index + 1) { request }, downloads)
            assertEquals(List(index + 1) { request }, shares)
            assertEquals(List(index + 1) { request }, opens)
            assertEquals("Saved action pointers do not open preview", index * 2, previews.size)
            clickGap()
            clickGap(firstAction = "share", secondAction = "open")
            assertEquals(List((index + 1) * 2) { request.artifactId }, previews)
            compose.onNodeWithTag(tag("cancel")).assertDoesNotExist()
        }
        capture("en-light-saved-three-actions-320dp-font200").recycle()
    }

    @Test fun loadedEnglishLightAtFont200FillsTheCardWithOneClipAndFadesUpward() =
        assertLoadedPixels(false, "en-light-loaded-320dp-font200")

    @Test fun loadedEnglishDarkAtFont200FillsTheCardWithOneClipAndFadesUpward() =
        assertLoadedPixels(true, "en-dark-loaded-320dp-font200")

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun loadedSpanishLightAtFont200FillsTheCardWithOneClipAndFadesUpward() =
        assertLoadedPixels(false, "es-light-loaded-320dp-font200")

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun loadedSpanishDarkAtFont200FillsTheCardWithOneClipAndFadesUpward() =
        assertLoadedPixels(true, "es-dark-loaded-320dp-font200")

    private fun assertLoadedPixels(dark: Boolean, name: String) {
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(fixtureColor) }
        show(dark = dark, image = source)
        assertGeometry()
        assertNoMetadata()
        val screenshot = capture(name)
        try {
            val bounds = cardWindowBounds()
            fun pixel(x: Float, y: Float) = screenshot.getPixel(x.toInt(), y.toInt())
            val background = pixel(bounds.left - dp(3f), bounds.top + dp(3f))
            assertColorNear("Outer 16dp corner exposes the background", background,
                pixel(bounds.left + dp(2f), bounds.top + dp(2f)))
            assertColorNear("Pixels reach the left edge with no nested inset", fixtureColor,
                pixel(bounds.left + dp(3f), bounds.top + dp(35f)))
            assertColorNear("Pixels reach the right edge with no nested inset", fixtureColor,
                pixel(bounds.right - dp(4f), bounds.top + dp(35f)))
            assertColorNear("Pixels reach the top edge after the rounded corner", fixtureColor,
                pixel(bounds.center.x, bounds.top + dp(3f)))
            // Keep this probe away from both glyphs and circles. Compare with source rather than
            // asserting an implementation-specific gradient height or alpha.
            val x = bounds.left + dp(24f)
            val top = distance(pixel(x, bounds.top + dp(35f)), fixtureColor)
            val middle = distance(pixel(x, bounds.bottom - dp(40f)), fixtureColor)
            val bottom = distance(pixel(x, bounds.bottom - dp(8f)), fixtureColor)
            assertTrue("Gradient is transparent toward the image top", top <= 6)
            assertTrue("The bottom shade grows smoothly beneath the actions: $top/$middle/$bottom",
                bottom > middle + 8 && middle > top + 8)
            for (name in listOf("download", "share")) {
                val target = action(name).fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()
                val inside = pixel(target.center.x + dp(17f), target.center.y)
                val outside = pixel(target.center.x + dp(22f), target.center.y)
                val nearbyImage = pixel(target.center.x + dp(27f), target.center.y)
                assertTrue("$name paints a visible circle within 40dp", distance(inside, nearbyImage) > 8)
                assertColorNear("$name leaves its outer touch area visually transparent", nearbyImage, outside)
            }
        } finally {
            screenshot.recycle()
            // The native Image still owns the source until composition is removed.
            compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
            source.recycle()
        }
    }

    @Test fun loadingThenFallbackEnglishLightKeepsMetadataAbsentAndActionsInsideAtFont200() =
        assertLoadingFallback(false, "en-light")

    @Test fun loadingThenFallbackEnglishDarkKeepsMetadataAbsentAndActionsInsideAtFont200() =
        assertLoadingFallback(true, "en-dark")

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun loadingThenFallbackSpanishLightKeepsMetadataAbsentAndActionsInsideAtFont200() =
        assertLoadingFallback(false, "es-light")

    @Test @Config(qualifiers = "es-rES-w320dp-h900dp-port-mdpi")
    fun loadingThenFallbackSpanishDarkKeepsMetadataAbsentAndActionsInsideAtFont200() =
        assertLoadingFallback(true, "es-dark")

    private fun assertLoadingFallback(dark: Boolean, name: String) {
        val loading = mutableStateOf(true)
        compose.setContent {
            Scene(dark) {
                CompositionLocalProvider(LocalChatFileActions provides actions()) {
                    HtmlThumbnailCardSurface(request, image = null, loading = loading.value,
                        onOpen = { previews += request.artifactId })
                }
            }
        }
        assertGeometry()
        assertNoMetadata()
        assertStatusAboveActions(R.string.chat_html_thumbnail_loading)
        capture("$name-loading-320dp-font200").recycle()
        compose.runOnIdle { loading.value = false }
        assertGeometry()
        assertNoMetadata()
        compose.onAllNodesWithText(compose.activity.getString(R.string.chat_html_thumbnail_loading),
            useUnmergedTree = true).assertCountEquals(0)
        assertStatusAboveActions(R.string.chat_html_thumbnail_unavailable)
        capture("$name-fallback-320dp-font200").recycle()
        action("download").performTouchInput { click(Offset(center.x + dp(22f), center.y)) }
        action("share").performTouchInput { click() }
        assertEquals(listOf(request), downloads)
        assertEquals(listOf(request), shares)
        assertTrue(previews.isEmpty())
        clickGap()
        assertEquals(listOf(request.artifactId), previews)
    }

    @Test fun unresolvedHtmlShellNeverShowsMetadataOrUnverifiedActions() {
        compose.setContent {
            Scene {
                HtmlThumbnailCardSurface(request, image = null, loading = true, showActions = false, onOpen = null)
            }
        }
        assertNoMetadata()
        val bounds = compose.onNodeWithTag(cardTag()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width / 1.5f, bounds.height, 1f)
        for (name in listOf("preview", "download", "share", "cancel", "open")) {
            compose.onNodeWithTag(tag(name)).assertDoesNotExist()
        }
        capture("en-light-unresolved-html-shell-320dp-font200").recycle()
    }

    private fun assertStatusAboveActions(label: Int) {
        val status = compose.onNodeWithText(compose.activity.getString(label), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val download = action("download").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag(cardTag()).fetchSemanticsNode().boundsInRoot
        assertTrue("Status fits inside the narrow card", status.left >= card.left && status.right <= card.right)
        assertTrue("200% status text never collides with overlay targets", status.bottom <= download.top)
    }

    private fun assertGeometry(secondAction: String = "share", saved: Boolean = false) {
        val card = compose.onNodeWithTag(cardTag()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val preview = preview().assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals(compose.activity.getString(R.string.chat_html_thumbnail_open, request.displayName))
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Preview and outer surface have identical bounds", card, preview)
        assertEquals("Only one edge-to-edge 3:2 region determines the card height", card.width / 1.5f, card.height, 1f)
        val targets = (listOf("download", secondAction) + if (saved) listOf("open") else emptyList()).map { name ->
            val target = action(name).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            val visual = compose.onNodeWithTag("delivered-file-$name-visual-${request.artifactId}", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("$name target is 48dp", dp(48f), target.width, 1f)
            assertEquals("$name target is 48dp", dp(48f), target.height, 1f)
            assertEquals("$name visible circle is 40dp", dp(40f), visual.width, 1f)
            assertEquals("$name visible circle is 40dp", dp(40f), visual.height, 1f)
            assertEquals(target.center, visual.center)
            assertTrue("$name remains inside the card", target.left >= card.left && target.right <= card.right &&
                target.top >= card.top && target.bottom <= card.bottom)
            target
        }
        targets.zipWithNext().forEach { (left, right) ->
            assertEquals("The existing 64dp center spacing is preserved", dp(64f), right.center.x - left.center.x, 1f)
        }
        assertEquals("Actions are centered as a group", card.center.x,
            (targets.first().center.x + targets.last().center.x) / 2f, 1f)
        assertTrue("Actions are anchored in the bottom third", targets[0].center.y > card.top + card.height * 2f / 3f)
    }

    private fun assertNoMetadata() {
        compose.onAllNodesWithText(request.displayName, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("text/html", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("html-thumbnail-static-${request.artifactId}").assertDoesNotExist()
        for (caption in listOf("Static preview · scripts disabled", "Vista estática · scripts desactivados")) {
            compose.onAllNodesWithText(caption, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun clickGap(firstAction: String = "download", secondAction: String = "share") {
        val first = action(firstAction).fetchSemanticsNode().boundsInRoot
        val second = action(secondAction).fetchSemanticsNode().boundsInRoot
        val preview = preview().fetchSemanticsNode().boundsInRoot
        val gap = Offset((first.right + second.left) / 2f - preview.left, first.center.y - preview.top)
        preview().performTouchInput { click(gap) }
    }

    private fun show(transfer: ChatFileTransfer? = null, dark: Boolean = false, image: Bitmap? = null) {
        compose.setContent {
            Scene(dark) {
                CompositionLocalProvider(LocalChatFileActions provides actions(transfer)) {
                    HtmlThumbnailCardSurface(request, image = image, loading = false,
                        onOpen = { previews += request.artifactId })
                }
            }
        }
    }

    private fun actions(transfer: ChatFileTransfer? = null) = ChatFileActions(
        transfers = transfer?.let { mapOf(request.key to it) } ?: emptyMap(),
        download = downloads::add, share = shares::add, cancel = cancels::add, open = opens::add)

    /** Presentation state only; ArtifactHtmlPreviewUiTest verifies an actual exported result. */
    private fun savedResult(status: DownloadStore.Status): DownloadStore.Result =
        DownloadStore.Result::class.java.getDeclaredConstructor(DownloadStore.Status::class.java, Uri::class.java,
            String::class.java, String::class.java, Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
            .newInstance(status, Uri.parse("content://downloads/ux33-fixture"), request.displayName, request.mimeType, 128L, null)

    @Composable private fun Scene(dark: Boolean = false, content: @Composable () -> Unit) {
        JarvysOwnTheme(if (dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) { content() }
            }
        }
    }

    private fun action(name: String) = compose.onNodeWithTag(tag(name))
    private fun preview() = compose.onNodeWithTag(tag("preview"))
    private fun tag(name: String) = "delivered-file-$name-${request.artifactId}"
    private fun cardTag() = "chat-attachment-file-${request.artifactId}"
    private fun dp(value: Float) = value * compose.activity.resources.displayMetrics.density
    private fun cardWindowBounds(): Rect = compose.onNodeWithTag(cardTag()).fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()
    private fun distance(a: Int, b: Int) = listOf(16, 8, 0).sumOf { abs(((a shr it) and 255) - ((b shr it) and 255)) }
    private fun assertColorNear(message: String, expected: Int, actual: Int) = assertTrue(
        "$message: expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}", distance(expected, actual) <= 6)

    private fun capture(name: String): Bitmap {
        var result: Bitmap? = null
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle {
                val root = compose.activity.window.decorView
                val configuration = root.resources.configuration
                val width = dp(configuration.screenWidthDp.toFloat()).toInt()
                val height = dp(configuration.screenHeightDp.toFloat()).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                result?.recycle()
                result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        }
        compose.waitForIdle()
        return requireNotNull(result).also { bitmap ->
            val directory = TestCaptureDirectories.named("ux33-html-thumbnail-${BuildConfig.FLAVOR}")
            val file = File(directory, "$name.png")
            TestCaptureDirectories.assertOwned(directory, file)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("UX33_CAPTURE=${file.absolutePath}")
        }
    }
}
