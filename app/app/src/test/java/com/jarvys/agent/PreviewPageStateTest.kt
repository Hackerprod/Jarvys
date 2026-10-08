package com.jarvys.agent

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** Native snapshot bounds/fallbacks. History entries are explicit Robolectric shadow fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [WorkspacePreviewRecordingShadow::class])
class PreviewPageStateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val base = "https://preview-test.jarvys.invalid/workspaces/test/"
    private val index = "${base}index.html"
    private val details = "${base}details.html"
    private val allowed: (String) -> Boolean = { it.startsWith(base) }

    @Test fun captureAndReleaseKeepHistoryAndOffsetsWithoutRetainingTheReleasedView() {
        val page = PreviewPageState()
        val first = WebView(context)
        val native = recording(first)
        page.attach(first, index, allowed)
        native.pushEntryToHistory(index)
        first.loadUrl(details)
        native.pushEntryToHistory(details)
        first.scrollTo(34, 912)
        val captured = page.capture()
        assertEquals(34, captured.getInt("x"))
        assertEquals(912, captured.getInt("y"))
        assertEquals(details, captured.getString("url"))
        assertNotNull(captured.getBundle("history"))
        page.release(first)
        assertNull("State must not retain an Activity through a released WebView", page.view)
        val savesAtRelease = native.saveCalls
        page.capture()
        page.release(first)
        assertEquals("Repeated release cannot use the detached native view", savesAtRelease, native.saveCalls)

        val restoredPage = PreviewPageState(captured)
        val restored = WebView(context)
        restoredPage.attach(restored, index, allowed)
        assertEquals(1, recording(restored).restoreCalls)
        assertTrue(recording(restored).loadedUrls.isEmpty())
        assertEquals(details, restored.copyBackForwardList().currentItem?.url)
        assertEquals(2, restored.copyBackForwardList().size)
        assertTrue(restored.canGoBack())
        restoredPage.release(restored)
        first.destroy()
        restored.destroy()
    }

    @Test fun oversizedHistoryFallsBackToTheValidatedCurrentUrlWithoutLargeSavedState() {
        val page = PreviewPageState()
        val first = WebView(context)
        val native = recording(first)
        page.attach(first, index, allowed)
        native.pushEntryToHistory(index)
        repeat(30) { native.pushEntryToHistory("${base}page-$it.html?value=${"x".repeat(3000)}") }
        first.loadUrl(details)
        native.pushEntryToHistory(details)
        val saved = page.capture()
        assertNull("Native history above the saved-state budget must be omitted", saved.getBundle("history"))
        assertEquals(details, saved.getString("url"))

        val restoredPage = PreviewPageState(saved)
        val restored = WebView(context)
        restoredPage.attach(restored, index, allowed)
        assertEquals(0, recording(restored).restoreCalls)
        assertEquals("Validated local navigation survives the bounded-history fallback",
            listOf(details), recording(restored).loadedUrls)
        page.release(first)
        restoredPage.release(restored)
        first.destroy()
        restored.destroy()
    }

    @Test fun invalidSavedUrlsAndNativeRestoreFailuresFallBackToTheApprovedEntryDocument() {
        val invalidSaved = Bundle().apply {
            putString("url", "https://untrusted.invalid/private.html")
            putBundle("history", Bundle()) // Robolectric models failed native restoration with null.
            putInt("x", 0)
            putInt("y", 0)
        }
        val page = PreviewPageState(invalidSaved)
        val web = WebView(context)
        page.attach(web, index, allowed)
        assertEquals(1, recording(web).restoreCalls)
        assertEquals(listOf(index), recording(web).loadedUrls)
        page.release(web)
        web.destroy()
    }

    @Test fun pageCacheRetainsFourMostRecentlyUsedStatesAndKeepsProjectIdentitySeparate() {
        val states = PreviewStateViewModel(SavedStateHandle())
        val first = states.page("first")
        val second = states.page("second")
        val third = states.page("third")
        val fourth = states.page("fourth")
        assertSame(first, states.page("first"))
        states.page("fifth")
        assertSame(first, states.page("first"))
        assertSame(third, states.page("third"))
        assertSame(fourth, states.page("fourth"))
        assertNotSame("The least recently used project must be evicted after four snapshots", second,
            states.page("second"))
        assertNotSame(states.page("first"), states.page("another-session:first"))
    }

    private fun recording(web: WebView): WorkspacePreviewRecordingShadow = Shadow.extract(web)
}
