package com.jarvys.agent

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** Native ownership/reset policy; Chromium scroll/zoom behavior requires device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [WorkspacePreviewRecordingShadow::class])
class WorkspacePreviewTouchPolicyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class RecordingParent(context: Context) : FrameLayout(context) {
        val requests = mutableListOf<Boolean>()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            requests += disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    @Test fun nativeStreamClaimsParentOnDownAndReleasesAfterUpOrCancelWithoutConsumingNativeEvents() {
        for (terminal in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            val parent = RecordingParent(context)
            val web = WorkspacePreviewWebView(context)
            parent.addView(web)
            val native = Shadow.extract<WorkspacePreviewRecordingShadow>(web)

            assertTrue(send(web, MotionEvent.ACTION_DOWN))
            assertEquals(true, parent.requests.last())
            assertTrue(send(web, MotionEvent.ACTION_MOVE))
            assertEquals("Moves must leave the native child in control", true, parent.requests.last())
            assertTrue(send(web, terminal))
            assertEquals("Every terminal event releases interception", false, parent.requests.last())
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, terminal), native.touches.map { it.action })

            // A canceled stream must not poison the next normal tap.
            assertTrue(send(web, MotionEvent.ACTION_DOWN))
            assertEquals(true, parent.requests.last())
            assertTrue(send(web, MotionEvent.ACTION_UP))
            assertEquals(false, parent.requests.last())
            web.destroy()
        }
    }

    @Test fun nativeRejectionOrFailureCannotLeaveParentInterceptionLocked() {
        val parent = RecordingParent(context)
        val web = WorkspacePreviewWebView(context)
        parent.addView(web)
        val native = Shadow.extract<WorkspacePreviewRecordingShadow>(web)
        native.nativeTouchResult = false
        assertFalse(send(web, MotionEvent.ACTION_DOWN))
        assertEquals("A rejected DOWN must not reserve the parent", false, parent.requests.last())

        native.nativeTouchResult = true
        native.nativeTouchFailure = IllegalStateException("Test native dispatch failure")
        assertThrows(IllegalStateException::class.java) { send(web, MotionEvent.ACTION_DOWN) }
        assertEquals("Exceptional dispatch must also release the parent", false, parent.requests.last())
        native.nativeTouchFailure = null
        assertTrue(send(web, MotionEvent.ACTION_DOWN))
        assertTrue(send(web, MotionEvent.ACTION_UP))
        assertEquals(false, parent.requests.last())
        web.destroy()
    }

    private fun send(web: WorkspacePreviewWebView, action: Int): Boolean {
        val event = MotionEvent.obtain(1L, 20L + action, action, 50f, 80f, 0)
        return try { web.onTouchEvent(event) } finally { event.recycle() }
    }
}
