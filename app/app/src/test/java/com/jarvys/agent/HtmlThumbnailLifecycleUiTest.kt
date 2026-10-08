package com.jarvys.agent

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.chat.ChatFileActions
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.DeliveredArtifactEventCard
import com.jarvys.agent.ui.chat.LocalChatFileActions
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowWebView

/**
 * Actual Compose cards, Activity lifecycle and renderer coroutine cancellation. The WebView shadow
 * records native lifecycle calls and holds visual-state callbacks. It never produces browser pixels
 * or substitutes cancellation/queue logic, so these tests do not claim Chromium rendering coverage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h900dp-port-mdpi",
    shadows = [HtmlThumbnailLifecycleShadow::class, ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class HtmlThumbnailLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val shown = mutableStateOf(emptyList<Fixture>())
    private val recompositions = mutableStateOf(0)
    private val downloads = mutableListOf<ChatFileRequest>()
    private val shares = mutableListOf<ChatFileRequest>()
    private var contentInstalled = false

    @Before fun verifyNoLeakedWorkBeforeResettingTheRecorder() {
        assertEquals("A previous test must not leave queued renderer work", 0, queued())
        val memory = HtmlThumbnailRenderer::class.java.getDeclaredField("memory")
            .apply { isAccessible = true }.get(HtmlThumbnailRenderer) as ThumbnailMemoryBudget
        assertEquals("A previous test must not retain a render reservation", 0, memory.retainedBytes)
        compose.runOnUiThread {
            HtmlThumbnailLifecycleShadow.records.forEach { record ->
                assertEquals("A previous admitted view was destroyed exactly once", 1, record.destroyCalls)
                assertTrue("A previous admitted view was stopped", record.stopCalls > 0)
                assertTrue("A previous admitted view was paused", record.pauseCalls > 0)
                assertNull("A previous admitted view has no parent", record.view.parent)
                assertFalse(record.view.isAttachedToWindow)
            }
            // Custom @Resetter methods are not automatically registered by this Robolectric setup.
            // Clear only test observations after proving that production resources were released.
            HtmlThumbnailLifecycleShadow.resetRecorder()
        }
    }

    @After fun releaseEveryPendingRender() {
        if (!contentInstalled) return
        compose.runOnUiThread { shown.value = emptyList() }
        awaitQueue(0)
        val memory = HtmlThumbnailRenderer::class.java.getDeclaredField("memory")
            .apply { isAccessible = true }.get(HtmlThumbnailRenderer) as ThumbnailMemoryBudget
        assertEquals("All cancelled render reservations are released", 0, memory.retainedBytes)
        compose.runOnUiThread {
            assertTrue("Disposing all cards releases their native WebViews", renderers().isEmpty())
            assertTrue("Every admitted view is destroyed exactly once",
                HtmlThumbnailLifecycleShadow.records.all { it.destroyCalls == 1 })
        }
        assertTrue("Thumbnail work never downloads an artifact", downloads.isEmpty())
        assertTrue("Thumbnail work never shares an artifact", shares.isEmpty())
    }

    @Test fun disposingACardCancelsItsPendingRendererAndIgnoresALateVisualCallback() {
        val fixture = fixture("dispose.html")
        startCards(fixture)
        val record = awaitRenderer(fixture)
        holdVisualCallback(record)
        compose.runOnIdle { shown.value = emptyList() }
        awaitQueue(0)
        assertReleased(record)
        completeLateCallback(record)
        assertNoCache(fixture)
        compose.onNodeWithTag("delivered-file-preview-${fixture.attachment.id}").assertDoesNotExist()
        assertEquals(1, HtmlThumbnailLifecycleShadow.records.size)
    }

    @Test fun backgroundingCancelsAndResumeStartsAFreshRendererWithoutUsingStalePixels() {
        val fixture = fixture("pause-resume.html")
        startCards(fixture)
        val first = awaitRenderer(fixture)
        holdVisualCallback(first)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitQueue(0)
        assertReleased(first)
        completeLateCallback(first)
        assertNoCache(fixture)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val resumed = awaitRenderer(fixture, excluding = first)
        assertNotSame(first.view, resumed.view)
        assertEquals(2, HtmlThumbnailLifecycleShadow.records.size)
        compose.onNodeWithTag("html-thumbnail-status-${fixture.attachment.id}", useUnmergedTree = true).assertExists()
        assertNoCache(fixture)
    }

    @Test fun replacingTheConversationReleasesTheOldRendererBeforeAdmittingTheNewOne() {
        val firstFixture = fixture("same-name.html")
        val nextFixture = fixture("same-name.html")
        startCards(firstFixture)
        val first = awaitRenderer(firstFixture)
        holdVisualCallback(first)
        compose.runOnIdle { shown.value = listOf(nextFixture) }
        val next = awaitRenderer(nextFixture)
        assertReleased(first)
        completeLateCallback(first)
        assertEquals(nextFixture.entryUrl, next.loadedUrls.single())
        assertEquals(1, HtmlThumbnailLifecycleShadow.maximumActive)
        compose.onNodeWithTag("delivered-file-preview-${firstFixture.attachment.id}").assertDoesNotExist()
        compose.onNodeWithTag("delivered-file-preview-${nextFixture.attachment.id}").assertIsDisplayed()
        compose.onNodeWithTag("html-thumbnail-status-${nextFixture.attachment.id}", useUnmergedTree = true).assertExists()
        assertNoCache(firstFixture)
        assertNoCache(nextFixture)
    }

    @Test fun deletingAConversationWhileItsRenderIsPendingCannotRecreateItsFilesOnLateCompletion() {
        val fixture = fixture("delete-pending.html")
        startCards(fixture)
        val first = awaitRenderer(fixture)
        holdVisualCallback(first)
        val deliveredDirectory = deliveredDirectory(fixture)
        assertTrue(deliveredDirectory.isDirectory)
        compose.runOnIdle {
            assertTrue(LocalRunStore(compose.activity).deleteConversation(fixture.session))
            shown.value = emptyList()
        }
        awaitQueue(0)
        assertReleased(first)
        assertFalse(deliveredDirectory.exists())
        completeLateCallback(first)
        assertFalse("A callback after deletion cannot recreate its delivered directory", deliveredDirectory.exists())
        assertNull(LocalRunStore(compose.activity).findChatFile(fixture.session, "delivered", fixture.attachment.id))
        val survivor = fixture("surviving-conversation.html")
        compose.runOnIdle { shown.value = listOf(survivor) }
        awaitRenderer(survivor)
        assertFalse("New queue work cannot resurrect the deleted conversation", deliveredDirectory.exists())
    }

    @Test fun repeatedUnrelatedRecompositionKeepsOnePendingWebViewAndOneQueueSlot() {
        val fixture = fixture("recompose.html")
        startCards(fixture)
        val first = awaitRenderer(fixture)
        holdVisualCallback(first)
        repeat(12) { index -> compose.runOnIdle { recompositions.value = index + 1 } }
        compose.runOnIdle {
            assertEquals(1, HtmlThumbnailLifecycleShadow.records.size)
            assertEquals(listOf(fixture.entryUrl), first.loadedUrls)
            assertEquals(0, first.destroyCalls)
            assertEquals(1, renderers().size)
            assertEquals(1, queued())
        }
        assertNoCache(fixture)
    }

    @Test fun twoVisibleCardsSerializeRenderersAndCancellingTheFirstAdmitsTheWaitingCard() {
        val one = fixture("first-visible.html")
        val two = fixture("second-visible.html")
        startCards(one, two)
        awaitQueue(2)
        var active: HtmlThumbnailLifecycleShadow? = null
        awaitLifecycle("Waiting for the single admitted renderer") {
            compose.runOnUiThread {
                active = HtmlThumbnailLifecycleShadow.records.singleOrNull { it.destroyCalls == 0 }
            }
            active != null
        }
        val first = requireNotNull(active)
        val waiting = if (first.loadedUrls.single() == one.entryUrl) two else one
        holdVisualCallback(first)
        compose.runOnIdle {
            assertEquals("The renderer mutex admits only one native view", 1, renderers().size)
            shown.value = listOf(waiting)
        }
        val next = awaitRenderer(waiting)
        assertReleased(first)
        completeLateCallback(first)
        assertEquals(1, HtmlThumbnailLifecycleShadow.maximumActive)
        assertEquals(waiting.entryUrl, next.loadedUrls.single())
        assertEquals(1, queued())
        assertNoCache(one)
        assertNoCache(two)
        compose.runOnIdle { shown.value = emptyList() }
        awaitQueue(0)
        assertReleased(next)
    }

    @Test fun missingVisualCompletionTimesOutToAnHonestFallbackAndReleasesTheNativeRenderer() {
        val fixture = fixture("timeout.html")
        startCards(fixture)
        val record = awaitRenderer(fixture)
        holdVisualCallback(record)
        assertEquals(1, queued())
        // Advance real coroutine/Android scheduling through the production five-second limit.
        // No synthetic completion, timeout result or pixels are supplied by the test.
        compose.mainClock.advanceTimeBy(HtmlThumbnailRenderer.TIMEOUT_MS + 50)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HtmlThumbnailRenderer.TIMEOUT_MS + 50))
        awaitQueue(0)
        assertReleased(record)
        compose.onNodeWithText(compose.activity.getString(R.string.chat_html_thumbnail_unavailable)).assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.chat_html_thumbnail_loading)).assertCountEquals(0)
        compose.onNodeWithTag("delivered-file-preview-${fixture.attachment.id}").assertHasClickAction().assertIsEnabled()
        compose.onNodeWithTag("delivered-file-download-${fixture.attachment.id}").assertIsEnabled()
        compose.onNodeWithTag("delivered-file-share-${fixture.attachment.id}").assertIsEnabled()
        completeLateCallback(record)
        assertEquals("Timeout does not silently create another hidden renderer", 1, HtmlThumbnailLifecycleShadow.records.size)
        assertNoCache(fixture)
    }

    private data class Fixture(val session: String, val attachment: ChatAttachment,
        val event: AgentRunUiEvent, val entryUrl: String)

    private fun fixture(name: String): Fixture {
        val session = "ux28-lifecycle-${UUID.randomUUID()}"
        val workspace = WorkspaceStore(File(compose.activity.filesDir, "jarvys/workspaces"),
            WorkspaceStore.projectIdForSession(session), null, null, null, session, false)
        workspace.write(name, "<!doctype html><html><body><h1>Immutable pending page</h1></body></html>")
        val artifacts = DeliveredArtifactStore(compose.activity)
        val attachment = artifacts.snapshot(session, workspace, name, null, CancellationToken.uncancellable())
        val store = LocalRunStore(compose.activity)
        store.appendConversationMessage(session, "user", "Show the delivered page")
        store.appendDeliveredFile(session, attachment)
        val descriptor = requireNotNull(artifacts.previewForAttachment(session, attachment))
        return Fixture(session, attachment, store.readConversationTimeline(session).single { it.kind == "delivered_file" },
            WorkspacePreviewContent(compose.activity, null, session, descriptor, thumbnail = true).entryUrl)
    }

    private fun startCards(vararg fixtures: Fixture) {
        shown.value = fixtures.toList()
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalChatFileActions provides ChatFileActions(download = downloads::add, share = shares::add)) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                        Text("Recomposition ${recompositions.value}")
                        shown.value.forEach { fixture ->
                            key(fixture.session, fixture.attachment.id) { DeliveredArtifactEventCard(fixture.event, fixture.session) }
                        }
                    }
                }
            }
        }
        contentInstalled = true
        awaitLifecycle("Waiting for the delivered thumbnail card") {
            compose.onAllNodesWithTag("delivered-file-preview-${fixtures.first().attachment.id}").fetchSemanticsNodes().size == 1
        }
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
            }
        }
    }

    private fun awaitRenderer(fixture: Fixture, excluding: HtmlThumbnailLifecycleShadow? = null): HtmlThumbnailLifecycleShadow {
        var found: HtmlThumbnailLifecycleShadow? = null
        awaitLifecycle("Waiting for renderer ${fixture.attachment.name} in ${fixture.session}") {
            compose.runOnUiThread {
                found = HtmlThumbnailLifecycleShadow.records.singleOrNull {
                    it !== excluding && it.destroyCalls == 0 && it.loadedUrls == listOf(fixture.entryUrl) && it.view.parent != null
                }
            }
            found != null
        }
        val record = requireNotNull(found)
        compose.runOnUiThread {
            assertTrue("The renderer is attached to the actual Activity root", record.view.isAttachedToWindow)
            assertTrue(renderers().contains(record.view))
        }
        return record
    }

    private fun holdVisualCallback(record: HtmlThumbnailLifecycleShadow) {
        compose.runOnIdle { record.view.webViewClient.onPageFinished(record.view, record.loadedUrls.single()) }
        assertEquals("The real client requests readiness without completing a render", 1, record.visualCallbacks.size)
    }

    private fun completeLateCallback(record: HtmlThumbnailLifecycleShadow) {
        compose.runOnUiThread { record.visualCallbacks.single().let { (id, callback) -> callback.onComplete(id) } }
        compose.waitForIdle()
    }

    private fun assertReleased(record: HtmlThumbnailLifecycleShadow) {
        compose.runOnUiThread {
            assertEquals("Every admitted renderer is destroyed once", 1, record.destroyCalls)
            assertTrue("Disposal stops native loading", record.stopCalls > 0)
            assertTrue("Disposal pauses native execution", record.pauseCalls > 0)
            assertNull("Disposal removes the native view from its holder", record.view.parent)
            assertFalse(record.view.isAttachedToWindow)
        }
    }

    private fun deliveredDirectory(fixture: Fixture) = File(compose.activity.filesDir, "jarvys/delivered/${fixture.session}")

    private fun assertNoCache(fixture: Fixture) {
        val directory = File(deliveredDirectory(fixture), ".html-thumbnails")
        assertFalse("An unfinished or cancelled render cannot persist a derivative",
            directory.exists() && directory.walkTopDown().any { it.isFile })
    }

    private fun awaitQueue(expected: Int) {
        awaitLifecycle("Waiting for renderer queue size $expected") {
            // Let real Compose effects and main-dispatcher cancellation run without changing renderer state.
            compose.mainClock.advanceTimeByFrame()
            queued() == expected
        }
    }

    private fun awaitLifecycle(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                // Renderer cancellation and timeout resume on the actual Android Main dispatcher,
                // which is separate from Compose's controlled frame clock in this JVM harness.
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                condition()
            }
        } catch (failure: ComposeTimeoutException) {
            val diagnostic = "$description: ${lifecycleDiagnostics()}"
            println("UX28_LIFECYCLE_TIMEOUT=$diagnostic")
            throw AssertionError(diagnostic, failure)
        }
    }

    private fun lifecycleDiagnostics(): String = runCatching {
        var result = ""
        compose.runOnUiThread {
            val memory = HtmlThumbnailRenderer::class.java.getDeclaredField("memory")
                .apply { isAccessible = true }.get(HtmlThumbnailRenderer) as ThumbnailMemoryBudget
            val records = HtmlThumbnailLifecycleShadow.records.map { record ->
                "{urls=${record.loadedUrls}, destroyed=${record.destroyCalls}, stopped=${record.stopCalls}, " +
                    "paused=${record.pauseCalls}, attached=${record.view.isAttachedToWindow}, " +
                    "parent=${record.view.parent != null}, visualCallbacks=${record.visualCallbacks.size}}"
            }
            result = "queued=${queued()}, retainedBytes=${memory.retainedBytes}, " +
                "rootRenderers=${renderers().size}, lifecycle=${compose.activity.lifecycle.currentState}, " +
                "shown=${shown.value.map { it.session + "/" + it.attachment.name }}, " +
                "maximumActive=${HtmlThumbnailLifecycleShadow.maximumActive}, records=$records"
        }
        result
    }.getOrElse { "diagnostics unavailable: ${it.javaClass.simpleName}: ${it.message}" }

    private fun queued(): Int = (HtmlThumbnailRenderer::class.java.getDeclaredField("queued")
        .apply { isAccessible = true }.get(HtmlThumbnailRenderer) as AtomicInteger).get()

    private fun renderers(): List<WebView> {
        fun visit(view: View): List<WebView> = when (view) {
            is WebView -> if (view.tag == "html-thumbnail-renderer") listOf(view) else emptyList()
            is ViewGroup -> (0 until view.childCount).flatMap { visit(view.getChildAt(it)) }
            else -> emptyList()
        }
        return visit(compose.activity.window.decorView)
    }
}

/** Native lifecycle recorder only. No pixels, coroutine cancellation or completion are fabricated. */
@Implements(WebView::class)
class HtmlThumbnailLifecycleShadow : ShadowWebView() {
    @RealObject private lateinit var realWebView: WebView
    val view: WebView get() = realWebView
    val loadedUrls = mutableListOf<String>()
    val visualCallbacks = mutableListOf<Pair<Long, WebView.VisualStateCallback>>()
    var destroyCalls = 0
        private set
    var stopCalls = 0
        private set
    var pauseCalls = 0
        private set

    @Implementation override fun loadUrl(url: String) {
        if (loadedUrls.isEmpty()) {
            records += this
            maximumActive = maxOf(maximumActive, records.count { it.destroyCalls == 0 })
        }
        loadedUrls += url
        super.loadUrl(url)
    }

    @Implementation fun postVisualStateCallback(requestId: Long, callback: WebView.VisualStateCallback) {
        visualCallbacks += requestId to callback
    }

    @Implementation fun stopLoading() { stopCalls++ }

    @Implementation override fun onPause() {
        pauseCalls++
        super.onPause()
    }

    @Implementation override fun destroy() {
        destroyCalls++
        super.destroy()
    }

    companion object {
        val records = mutableListOf<HtmlThumbnailLifecycleShadow>()
        var maximumActive = 0
            private set
        @JvmStatic @Resetter fun resetThumbnailLifecycle() {
            records.clear()
            maximumActive = 0
        }
    }
}
