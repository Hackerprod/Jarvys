package com.jarvys.agent

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * The extracted snapshot viewer keeps its live native WebView across ordinary recomposition and
 * scopes its saved history to the snapshot it shows. A real parameter change forces the check, so a
 * skipped Compose group cannot hide an AndroidView reload.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [WorkspacePreviewRecordingShadow::class, ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class ArtifactSnapshotPreviewRecompositionTest {
    private val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @OptIn(ExperimentalTestApi::class)
    private val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    @get:Rule val rules: RuleChain = RuleChain.outerRule(chatTestStore).around(compose)

    private data class Delivered(val session: String, val token: String)

    @Before fun storageReady() {
        val filesDir = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        chatTestStore.onDatabaseWorker { SingleSessionReset.awaitReadyBlocking(filesDir) }
    }

    @Test fun changingModifierRecomposesAndroidViewWithoutResettingInternalNavigationHistoryOrScroll() {
        val delivered = deliver("recomposition")
        val revision = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                ArtifactSnapshotPreviewScreen(delivered.token, delivered.session,
                    Modifier.testTag("preview-revision-${revision.value}"))
            }
        }
        val web = awaitWebView()
        val native = Shadow.extract<WorkspacePreviewRecordingShadow>(web)
        val index = native.loadedUrls.single()
        val details = index.removeSuffix("index.html") + "details.html"
        assertTrue(index.endsWith("/index.html"))
        compose.runOnIdle {
            native.pushEntryToHistory(index)
            web.loadUrl(details)
            native.pushEntryToHistory(details)
            web.scrollTo(21, 530)
        }

        repeat(3) { cycle ->
            compose.runOnIdle { revision.value = cycle + 1 }
            compose.onNodeWithTag("preview-revision-${cycle + 1}").assertExists()
            compose.waitForIdle()
            compose.runOnIdle {
                // Compose may legitimately skip a stable inner AndroidView; run the holder's installed
                // update callback too, so that optimization cannot hide a reload from update.
                val holder = requireNotNull(web.parent)
                val nativeUpdate = holder.javaClass.getMethod("getUpdate").invoke(holder) as Function0<*>
                nativeUpdate.invoke()
                assertSame("Ordinary recomposition retains the live native WebView", web,
                    findWebView(compose.activity.window.decorView))
                assertEquals("Never compare the current navigation URL to the initial index URL",
                    listOf(index, details), native.loadedUrls)
                assertEquals(details, web.url)
                assertEquals(2, web.copyBackForwardList().size)
                assertEquals(21, web.scrollX)
                assertEquals(530, web.scrollY)
                assertEquals(0, native.destroyCalls)
            }
        }
    }

    @Test fun changingSnapshotUsesItsOwnDocumentAndReturningRestoresOnlyThatSnapshotsSavedHistory() {
        val first = deliver("first")
        val second = deliver("second")
        val selected = mutableStateOf(first)
        compose.setContent {
            MaterialTheme { ArtifactSnapshotPreviewScreen(selected.value.token, selected.value.session) }
        }
        val firstWeb = awaitWebView()
        val firstNative = Shadow.extract<WorkspacePreviewRecordingShadow>(firstWeb)
        val firstIndex = firstNative.loadedUrls.single()
        val firstDetails = firstIndex.removeSuffix("index.html") + "details.html"
        compose.runOnIdle {
            firstNative.pushEntryToHistory(firstIndex)
            firstWeb.loadUrl(firstDetails)
            firstNative.pushEntryToHistory(firstDetails)
            firstWeb.scrollTo(12, 345)
            selected.value = second
        }
        val secondWeb = awaitWebView(excluding = firstWeb)
        val secondNative = Shadow.extract<WorkspacePreviewRecordingShadow>(secondWeb)
        compose.runOnIdle {
            assertNotSame("A different snapshot needs a correctly scoped native client", firstWeb, secondWeb)
            assertEquals(1, firstNative.destroyCalls)
            assertTrue(firstNative.saveCalls > 0)
            assertEquals(0, secondNative.restoreCalls)
            selected.value = first
        }
        val returned = awaitWebView(excluding = secondWeb)
        val returnedNative = Shadow.extract<WorkspacePreviewRecordingShadow>(returned)
        compose.runOnIdle {
            assertNotSame(firstWeb, returned)
            assertEquals(1, secondNative.destroyCalls)
            assertEquals(1, returnedNative.restoreCalls)
            assertEquals(emptyList<String>(), returnedNative.loadedUrls)
            assertEquals(firstDetails, returned.copyBackForwardList().currentItem?.url)
            assertEquals(2, returned.copyBackForwardList().size)
            assertTrue(returned.canGoBack())
        }
    }

    /** Delivers one HTML snapshot into its own conversation, as the thread card does before opening it. */
    private fun deliver(label: String): Delivered {
        val session = "ux22-$label-${UUID.randomUUID()}"
        val workspace = WorkspaceStore(File(compose.activity.filesDir, "jarvys/workspaces"),
            WorkspaceStore.projectIdForSession(session), null, null, null, session, false)
        workspace.write("index.html", "<!doctype html><a href='details.html'>$label</a>")
        val artifacts = DeliveredArtifactStore(compose.activity)
        val attachment = artifacts.snapshot(session, workspace, "index.html", null, CancellationToken.uncancellable())
        LocalRunStore(compose.activity).appendDeliveredFile(session, attachment)
        return Delivered(session, requireNotNull(artifacts.previewForAttachment(session, attachment)).token)
    }

    private fun awaitWebView(excluding: WebView? = null): WebView {
        var found: WebView? = null
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            compose.runOnUiThread { found = findWebView(compose.activity.window.decorView) }
            found != null && found !== excluding
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
}
