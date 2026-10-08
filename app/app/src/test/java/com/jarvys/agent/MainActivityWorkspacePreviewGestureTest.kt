package com.jarvys.agent

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow

/**
 * UX22: native event dispatch through the real MainActivity, shell, AndroidView and WebView.
 * The recording shadow models native touch acceptance only. No test here claims to execute DOM,
 * measure Chromium scrolling/fling/pinch, or establish device frame-rate performance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi",
    shadows = [WorkspacePreviewRecordingShadow::class])
class MainActivityWorkspacePreviewGestureTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val session = "ux22-native-preview-${UUID.randomUUID()}"
    private val projectId = WorkspaceStore.projectIdForSession(session)
    private val previewCall = "ux22-existing-preview"
    private val draft = "Keep this unsent draft while I inspect my web preview"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store: LocalRunStore get() = LocalRunStore(context)
    private lateinit var indexUrl: String
    private val detailUrl get() = indexUrl.removeSuffix("index.html") + "details.html"
    private val secretSingleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }

    private val fixtures = object : ExternalResource() {
        override fun before() {
            ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
                .apply { isAccessible = true }.set(null, null)
            secretSingleton.set(null, SecretStore(context.getSharedPreferences("ux22-preview-secrets", 0)))
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            MemoryStore(context).markDisclosureShown()
            MemoryReflectionPreferences(context).markDisclosureShown()
            val workspace = readOnlyPreviewWorkspace(context, projectId)
            workspace.write("index.html", """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <main style="height:4000px;width:1600px"><a href="details.html">Details</a>
                <button onclick="this.textContent='Tapped'">Tap here</button><p>Long local preview</p></main>
            """.trimIndent())
            workspace.write("details.html", "<!doctype html><h1>Existing internal page</h1>")
            val user = store.appendConversationMessage(session, "user", "Open my existing local web preview")
            store.appendConversationToolPresentation(session, user, "preview_workspace", "tool_result",
                previewCall, "Existing preview is ready", projectId, "")
            store.appendConversationMessage(session, "assistant", "Your local preview is ready.",
                0L, "", user, "COMPLETED")
            store.renameConversation(session, "Native preview regression")
            AgentRunUiState.resetSession(session)
            context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE).edit()
                .putString("active_session_id", session).commit()
        }

        override fun after() {
            secretSingleton.set(null, null)
            WorkManagerTestCleanup.close(context)
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixtures).around(compose)

    @Test fun verticalDiagonalHorizontalAndFastStreamsReachNativeViewWithoutOpeningDrawer() {
        val web = openExistingPreview()
        val native = recording(web)
        data class Drag(val name: String, val start: Offset, val end: Offset, val duration: Long)
        val drags = listOf(
            Drag("vertical up", Offset(.5f, .80f), Offset(.5f, .20f), 400),
            Drag("vertical down", Offset(.5f, .20f), Offset(.5f, .80f), 400),
            Drag("diagonal right", Offset(.10f, .75f), Offset(.85f, .25f), 400),
            Drag("diagonal left", Offset(.85f, .25f), Offset(.10f, .75f), 400),
            Drag("horizontal from left edge", Offset(.01f, .5f), Offset(.90f, .5f), 400),
            Drag("horizontal left", Offset(.90f, .5f), Offset(.10f, .5f), 400),
            Drag("fast native fling input", Offset(.5f, .85f), Offset(.5f, .15f), 48),
        )
        for (drag in drags) {
            native.touches.clear()
            drag(web, drag.start, drag.end, drag.duration)
            assertEquals("${drag.name}: native view must receive the complete stream",
                listOf(MotionEvent.ACTION_DOWN) + List(8) { MotionEvent.ACTION_MOVE } + MotionEvent.ACTION_UP,
                native.touches.map { it.action })
            assertTrue("${drag.name}: retain one native pointer", native.touches.all { it.pointerIds == listOf(0) })
            assertTrue("${drag.name}: preserve event ordering",
                native.touches.zipWithNext().all { (first, next) -> next.eventTime >= first.eventTime })
            assertPreviewWithDrawerClosed(web)
        }
        assertEquals("Gestures cannot reload the initial document", listOf(indexUrl), native.loadedUrls)
    }

    @Test fun tapCancelAndMultitouchKeepNativeDispatchAndDoNotPoisonTheNextGesture() {
        val web = openExistingPreview()
        val native = recording(web)
        val center = Offset(.5f, .5f)
        val downTime = SystemClock.uptimeMillis()
        dispatch(web, downTime, 0, MotionEvent.ACTION_DOWN, center)
        dispatch(web, downTime, 16, MotionEvent.ACTION_MOVE, Offset(.6f, .45f))
        dispatch(web, downTime, 32, MotionEvent.ACTION_CANCEL, Offset(.6f, .45f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL),
            native.touches.map { it.action })
        assertPreviewWithDrawerClosed(web)

        native.touches.clear()
        tap(web, center)
        assertEquals("Native tap must retain both events after cancellation",
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), native.touches.map { it.action })
        native.touches.clear()
        val pinchTime = SystemClock.uptimeMillis()
        dispatch(web, pinchTime, 0, MotionEvent.ACTION_DOWN, Offset(.35f, .5f))
        dispatch(web, pinchTime, 16, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Offset(.35f, .5f), Offset(.65f, .5f))
        dispatch(web, pinchTime, 32, MotionEvent.ACTION_MOVE, Offset(.25f, .45f), Offset(.75f, .55f))
        dispatch(web, pinchTime, 48, MotionEvent.ACTION_MOVE, Offset(.15f, .40f), Offset(.85f, .60f))
        dispatch(web, pinchTime, 64, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Offset(.15f, .40f), Offset(.85f, .60f))
        dispatch(web, pinchTime, 80, MotionEvent.ACTION_UP, Offset(.15f, .40f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP), native.touches.map { it.action })
        assertEquals(listOf(1, 2, 2, 2, 2, 1), native.touches.map { it.pointerIds.size })
        assertEquals(listOf(0, 1), native.touches[1].pointerIds)
        assertEquals(1, native.touches[1].actionIndex)
        assertEquals(1, native.touches[4].actionIndex)
        assertPreviewWithDrawerClosed(web)
        native.touches.clear()
        tap(web, center)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), native.touches.map { it.action })
        assertPreviewWithDrawerClosed(web)
    }

    @Test fun systemBackReturnsToUnchangedChatAndChatDrawerStillAcceptsItsNativeEdgeGesture() {
        awaitChat()
        val before = transcript()
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        val web = openExistingPreview()
        val native = recording(web)
        // Native browser history must not override the app's established Back-to-chat contract.
        compose.runOnIdle {
            native.pushEntryToHistory(indexUrl)
            web.loadUrl(detailUrl)
            native.pushEntryToHistory(detailUrl)
            assertTrue(web.canGoBack())
        }
        drag(web, Offset(.01f, .55f), Offset(.90f, .55f), 400)
        assertPreviewWithDrawerClosed(web)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitChat()
        assertEquals(before, transcript())
        assertEquals(session, selectedSession())
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
        compose.runOnIdle {
            assertEquals(AppNavigationBackPolicy.CHAT_ROOT, navigation().currentDestination?.route)
            assertEquals("Navigation away releases its WebView exactly once", 1, native.destroyCalls)
            assertTrue("Release stops in-flight work", native.stopCalls > 0)
        }

        settleNativeFrame()
        compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("chat-message-list"))).performTouchInput {
            swipe(Offset(1f, height * .45f), Offset(width * .85f, height * .45f), durationMillis = 400)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-header").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
    }

    @Test fun rotationRestoresNativeHistoryAndViewScrollInsteadOfReloadingIndex() {
        val first = openExistingPreview()
        val oldNative = recording(first)
        compose.runOnIdle {
            oldNative.pushEntryToHistory(indexUrl)
            first.loadUrl(detailUrl)
            oldNative.pushEntryToHistory(detailUrl)
            first.scrollTo(48, 720)
            assertEquals(720, first.scrollY)
            assertEquals(2, first.copyBackForwardList().size)
        }
        val originalActivity = compose.activity
        RuntimeEnvironment.setQualifiers("en-rUS-w800dp-h360dp-land-mdpi")
        compose.activityRule.scenario.recreate()
        val restored = awaitWebView()
        settleNativeFrame()
        val restoredNative = recording(restored)
        compose.runOnIdle {
            assertNotSame(originalActivity, compose.activity)
            assertNotSame(first, restored)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
            assertTrue("Activity state capture must save native history", oldNative.saveCalls > 0)
            assertEquals(1, oldNative.destroyCalls)
            assertEquals("New WebView restores its history once", 1, restoredNative.restoreCalls)
            assertEquals("A successful restore must not reload index.html", emptyList<String>(), restoredNative.loadedUrls)
            assertEquals(2, restored.copyBackForwardList().size)
            assertEquals(detailUrl, restored.copyBackForwardList().currentItem?.url)
            assertTrue(restored.canGoBack())
            // Model native page completion only to let the production scroll-restoration callback run.
            // This checks View coordinates, not DOM scroll or Chromium layout.
            restored.webViewClient.onPageFinished(restored, detailUrl)
        }
        settleNativeFrame()
        compose.runOnIdle {
            assertEquals("Saved horizontal View position", 48, restored.scrollX)
            assertEquals("Saved vertical View position", 720, restored.scrollY)
        }
        assertPreviewWithDrawerClosed(restored)
        tap(restored, Offset(.5f, .5f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), restoredNative.touches.map { it.action })
        assertEquals(session, selectedSession())
    }

    @Test fun pauseResumeAndNativeConfigurationPreserveBrowserPolicyAndLoadedDocument() {
        val web = openExistingPreview()
        val native = recording(web)
        compose.runOnIdle {
            assertTrue(web.settings.javaScriptEnabled)
            assertTrue(web.settings.domStorageEnabled)
            assertTrue("Pinch remains a native WebView capability", web.settings.supportZoom())
            assertTrue(web.settings.builtInZoomControls)
            assertFalse("Do not overlay obsolete zoom buttons", web.settings.displayZoomControls)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
            assertEquals("Do not force a WebView layer override", View.LAYER_TYPE_NONE, web.layerType)
        }
        val priorPauses = native.pauseCalls
        val priorResumes = native.resumeCalls
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(native.pauseCalls > priorPauses)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settleNativeFrame()
        assertTrue(native.resumeCalls > priorResumes)
        assertSame(web, awaitWebView())
        assertEquals(listOf(indexUrl), native.loadedUrls)
        assertPreviewWithDrawerClosed(web)
    }

    @Test
    @Config(shadows = [WorkspacePreviewRecordingShadow::class, ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
    fun deliveredHtmlCardUsesItsOwningArtifactRouteAcrossRecreationBackAndReopen() {
        awaitChat()
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        val workspace = WorkspaceStore(File(context.filesDir, "jarvys/workspaces"), projectId,
            null, null, null, session, false)
        val capturedHtml = workspace.read("index.html")
        val artifacts = DeliveredArtifactStore(context)
        val attachment = artifacts.snapshot(session, workspace, "index.html", null, CancellationToken.uncancellable())
        store.appendDeliveredFile(session, attachment)
        val descriptor = requireNotNull(artifacts.previewForAttachment(session, attachment))
        workspace.write("index.html", "<!doctype html><h1>Changed after delivery</h1>")
        val before = transcript()
        compose.runOnIdle { AgentRunUiState.refreshPersistedSession(session, store.readConversationTimeline(session)) }
        val previewTag = "delivered-file-preview-${attachment.id}"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(previewTag).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(previewTag).performScrollTo()
        settleNativeFrame()
        compose.onNodeWithTag(previewTag).assertIsDisplayed().performTouchInput { click() }
        val first = awaitWebView()
        settleNativeFrame()

        fun assertOwnedRouteAndBytes(web: WebView) {
            compose.runOnIdle {
                val entry = requireNotNull(navigation().currentBackStackEntry)
                assertEquals(AppNavigationBackPolicy.ARTIFACT_PREVIEW, entry.destination.route)
                assertEquals(session, entry.arguments?.getString("sessionId"))
                assertEquals(descriptor.artifactId, entry.arguments?.getString("artifactId"))
                assertEquals(descriptor.token, HtmlPreviewDescriptor.TOKEN_PREFIX + entry.arguments?.getString("artifactId"))
                val response = web.webViewClient.shouldInterceptRequest(web, object : WebResourceRequest {
                    override fun getUrl(): Uri = Uri.parse(web.url)
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = true
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                })
                assertEquals(200, requireNotNull(response).statusCode)
                assertEquals(capturedHtml, response.data.bufferedReader().use { it.readText() })
            }
            compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        }
        assertOwnedRouteAndBytes(first)
        drag(first, Offset(.01f, .5f), Offset(.9f, .5f), 400)
        assertOwnedRouteAndBytes(first)
        compose.runOnIdle { recording(first).pushEntryToHistory(requireNotNull(first.url)) }
        compose.activityRule.scenario.recreate()
        val recreated = awaitWebView()
        assertNotSame(first, recreated)
        assertOwnedRouteAndBytes(recreated)
        assertEquals(1, recording(recreated).restoreCalls)
        assertTrue(recording(recreated).loadedUrls.isEmpty())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitChat()
        assertEquals(before, transcript())
        assertEquals(session, selectedSession())
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
        compose.onNodeWithTag(previewTag).performScrollTo()
        settleNativeFrame()
        compose.onNodeWithTag(previewTag).performTouchInput { click() }
        val reopened = awaitWebView()
        assertNotSame(recreated, reopened)
        assertOwnedRouteAndBytes(reopened)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitChat()
        assertEquals(before, transcript())
        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
    }

    private fun openExistingPreview(): WebView {
        awaitChat()
        settleNativeFrame()
        compose.onNodeWithTag("tool-preview-$previewCall").performScrollTo().performTouchInput { click() }
        val web = awaitWebView()
        settleNativeFrame()
        indexUrl = recording(web).loadedUrls.single()
        assertTrue(indexUrl.endsWith("/workspaces/$projectId/index.html"))
        assertPreviewWithDrawerClosed(web)
        return web
    }

    private fun awaitChat() {
        compose.waitUntil(10_000) {
            val count = compose.onAllNodesWithTag("chat-message-list").fetchSemanticsNodes().size
            count == 1 && selectedSession() == session && AgentRunUiState.state.value.sessionId == session &&
                AgentRunUiState.state.value.events.any { it.toolCallId == previewCall }
        }
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

    private fun assertPreviewWithDrawerClosed(web: WebView) {
        compose.waitForIdle()
        compose.onNodeWithTag("drawer-header").assertIsNotDisplayed()
        compose.runOnIdle {
            assertSame(web, findWebView(compose.activity.window.decorView))
            assertEquals(AppNavigationBackPolicy.WORKSPACE_PREVIEW, navigation().currentDestination?.route)
        }
    }

    private fun drag(web: WebView, start: Offset, end: Offset, duration: Long) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(web, downTime, 0, MotionEvent.ACTION_DOWN, start)
        repeat(8) { step ->
            val fraction = (step + 1) / 8f
            dispatch(web, downTime, duration * (step + 1) / 9, MotionEvent.ACTION_MOVE,
                Offset(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction))
        }
        dispatch(web, downTime, duration, MotionEvent.ACTION_UP, end)
        compose.waitForIdle()
    }

    private fun tap(web: WebView, position: Offset) {
        val downTime = SystemClock.uptimeMillis()
        dispatch(web, downTime, 0, MotionEvent.ACTION_DOWN, position)
        dispatch(web, downTime, 32, MotionEvent.ACTION_UP, position)
        compose.waitForIdle()
    }

    /** The only gesture entry point: never call WebView.dispatchTouchEvent directly in these tests. */
    private fun dispatch(web: WebView, downTime: Long, elapsed: Long, action: Int,
        first: Offset, second: Offset? = null) {
        val normalized = listOfNotNull(first, second)
        compose.runOnUiThread {
            val parent = web.parent as? View
            assertTrue("Native hit area must be measured before injection: web=${web.width}x${web.height}, " +
                "measured=${web.measuredWidth}x${web.measuredHeight}, parent=${parent?.width}x${parent?.height}",
                web.width > 0 && web.height > 0)
            val location = IntArray(2).also(web::getLocationInWindow)
            val properties = Array(normalized.size) { index -> MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(normalized.size) { index -> MotionEvent.PointerCoords().apply {
                x = location[0] + normalized[index].x * web.width
                y = location[1] + normalized[index].y * web.height
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(downTime, downTime + elapsed, action, normalized.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { compose.activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun selectedSession(): String? = context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
        .getString("active_session_id", null)

    private fun transcript(): List<Pair<String, String>> = store.readConversationTimeline(session)
        .map { it.messageId to "${it.kind}:${it.text}" }

    private fun navigation(): NavHostController = MainActivity::class.java.getDeclaredField("activeNavController")
        .apply { isAccessible = true }.get(compose.activity) as NavHostController

    private fun recording(web: WebView): WorkspacePreviewRecordingShadow = Shadow.extract(web)

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
}
