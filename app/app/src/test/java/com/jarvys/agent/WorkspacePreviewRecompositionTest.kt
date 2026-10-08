package com.jarvys.agent

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** Forces a real parameter change so a skipped Compose group cannot hide an AndroidView reload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [WorkspacePreviewRecordingShadow::class])
class WorkspacePreviewRecompositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun changingModifierRecomposesAndroidViewWithoutResettingInternalNavigationHistoryOrScroll() {
        val projectId = createWorkspace("recomposition")
        val revision = mutableStateOf(0)
        compose.setContent {
            MaterialTheme {
                WorkspacePreviewScreen(projectId, modifier = Modifier.testTag("preview-revision-${revision.value}"))
            }
        }
        val web = awaitWebView()
        val native = Shadow.extract<WorkspacePreviewRecordingShadow>(web)
        val index = native.loadedUrls.single()
        val details = index.removeSuffix("index.html") + "details.html"
        assertTrue(index.endsWith("/workspaces/$projectId/index.html"))
        compose.runOnIdle {
            assertEquals(listOf(index), native.loadedUrls)
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
                // The screen parameter changed, but Compose may legitimately skip a stable inner
                // AndroidView. Exercise the real holder's installed update callback as well, so
                // that optimization cannot conceal a regression that reloads index from update.
                val holder = requireNotNull(web.parent)
                val nativeUpdate = holder.javaClass.getMethod("getUpdate").invoke(holder) as Function0<*>
                nativeUpdate.invoke()
                assertSame("Ordinary recomposition retains the live native WebView", web,
                    findWebView(compose.activity.window.decorView))
                assertEquals("Never compare the current navigation URL to the initial index URL",
                    listOf(index, details), native.loadedUrls)
                assertEquals(details, web.url)
                assertEquals(2, web.copyBackForwardList().size)
                assertEquals(details, web.copyBackForwardList().currentItem?.url)
                assertEquals(21, web.scrollX)
                assertEquals(530, web.scrollY)
                assertEquals(0, native.destroyCalls)
            }
        }
    }

    @Test fun changingProjectUsesItsOwnDocumentAndReturningRestoresOnlyThatProjectsSavedHistory() {
        val firstProject = createWorkspace("first")
        val secondProject = createWorkspace("second")
        val selected = mutableStateOf(firstProject)
        compose.setContent {
            MaterialTheme { WorkspacePreviewScreen(selected.value) }
        }
        val first = awaitWebView()
        val firstNative = Shadow.extract<WorkspacePreviewRecordingShadow>(first)
        val firstIndex = firstNative.loadedUrls.single()
        val firstDetails = firstIndex.removeSuffix("index.html") + "details.html"
        compose.runOnIdle {
            firstNative.pushEntryToHistory(firstIndex)
            first.loadUrl(firstDetails)
            firstNative.pushEntryToHistory(firstDetails)
            first.scrollTo(12, 345)
            selected.value = secondProject
        }
        val second = awaitWebView(excluding = first)
        val secondNative = Shadow.extract<WorkspacePreviewRecordingShadow>(second)
        compose.runOnIdle {
            assertNotSame("A different project needs a correctly scoped native client", first, second)
            assertEquals(1, firstNative.destroyCalls)
            assertTrue(firstNative.saveCalls > 0)
            assertTrue(secondNative.loadedUrls.single().endsWith("/workspaces/$secondProject/index.html"))
            assertEquals(0, secondNative.restoreCalls)
            selected.value = firstProject
        }
        val returned = awaitWebView(excluding = second)
        val returnedNative = Shadow.extract<WorkspacePreviewRecordingShadow>(returned)
        compose.runOnIdle {
            assertNotSame(first, returned)
            assertNotSame(second, returned)
            assertEquals(1, secondNative.destroyCalls)
            assertEquals(1, returnedNative.restoreCalls)
            assertEquals(emptyList<String>(), returnedNative.loadedUrls)
            assertEquals(firstDetails, returned.copyBackForwardList().currentItem?.url)
            assertEquals(2, returned.copyBackForwardList().size)
            assertTrue(returned.canGoBack())
        }
    }

    private fun createWorkspace(label: String): String {
        val project = WorkspaceStore.projectIdForSession("ux22-$label-${UUID.randomUUID()}")
        readOnlyPreviewWorkspace(context, project).apply {
            write("index.html", "<!doctype html><a href='details.html'>$label</a>")
            write("details.html", "<!doctype html><h1>$label details</h1>")
        }
        return project
    }

    private fun awaitWebView(excluding: WebView? = null): WebView {
        var found: WebView? = null
        compose.waitUntil(10_000) {
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
