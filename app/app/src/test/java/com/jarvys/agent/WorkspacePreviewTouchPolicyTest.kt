package com.jarvys.agent

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowView

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

    @Test fun performClickForwardsOnceThroughNormalViewAccessibilityAndTouchWrapperAddsNoClicks() {
        val parent = RecordingParent(context)
        val web = WorkspacePreviewWebView(context)
        parent.addView(web)
        val native = Shadow.extract<WorkspacePreviewRecordingShadow>(web)
        var listenerClicks = 0
        var frameworkClickCalls = 0
        var accessibilityClickEvents = 0
        web.setOnClickListener { listenerClicks++ }
        web.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) accessibilityClickEvents++
                super.sendAccessibilityEvent(host, eventType)
            }
        }
        val observer = View.OnClickListener { clicked -> if (clicked === web) frameworkClickCalls++ }
        ShadowView.addGlobalPerformClickListener(observer)
        try {
            assertTrue("Preserve the inherited performClick return value", web.performClick())
            assertEquals("Delegate to View.performClick exactly once", 1, frameworkClickCalls)
            assertEquals("The normal View click listener receives one activation", 1, listenerClicks)
            assertEquals("Keep the normal View accessibility click event", 1, accessibilityClickEvents)

            for (terminal in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                assertTrue(send(web, MotionEvent.ACTION_DOWN))
                assertTrue(send(web, terminal))
                assertEquals("The native touch wrapper must not synthesize performClick", 1, frameworkClickCalls)
                assertEquals(1, listenerClicks)
                assertEquals(1, accessibilityClickEvents)
                assertEquals(false, parent.requests.last())
            }
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), native.touches.map { it.action })
            web.setOnClickListener(null)
            assertFalse("An unhandled framework click stays unhandled", web.performClick())
            assertEquals("The second explicit request is forwarded once", 2, frameworkClickCalls)
            assertEquals(1, listenerClicks)
        } finally {
            ShadowView.removeGlobalPerformClickListener(observer)
            web.destroy()
        }
    }

    private fun send(web: WorkspacePreviewWebView, action: Int): Boolean {
        val event = MotionEvent.obtain(1L, 20L + action, action, 50f, 80f, 0)
        return try { web.onTouchEvent(event) } finally { event.recycle() }
    }
}
