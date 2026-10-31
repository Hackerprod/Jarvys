package com.jarvys.agent

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.WebBackForwardList
import android.webkit.WebView
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowWebView
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Records calls at the native WebView boundary while retaining Robolectric's history implementation.
 *
 * Robolectric does not run Chromium. Returning [nativeTouchResult] supplies only the native touch
 * acceptance contract, so these tests can exercise actual Activity/Compose/View dispatch. It does
 * not simulate DOM scrolling, link activation, fling physics, rendering or pinch-zoom results.
 */
@Implements(WebView::class)
class WorkspacePreviewRecordingShadow : ShadowWebView() {
    @RealObject private lateinit var realWebView: WebView
    data class Touch(
        val action: Int,
        val actionIndex: Int,
        val pointerIds: List<Int>,
        val positions: List<Pair<Float, Float>>,
        val eventTime: Long,
    )

    val touches = mutableListOf<Touch>()
    val loadedUrls = mutableListOf<String>()
    var saveCalls = 0
        private set
    var restoreCalls = 0
        private set
    var pauseCalls = 0
        private set
    var resumeCalls = 0
        private set
    var stopCalls = 0
        private set
    var destroyCalls = 0
        private set
    var nativeTouchResult = true
    var nativeTouchFailure: RuntimeException? = null

    @Implementation
    fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Robolectric's empty Chromium provider otherwise leaves WebView measured at 0×0.
        // Honor the actual AndroidView parent's constraints to establish a native hit target;
        // this models neither DOM geometry nor scrolling and does not bypass touch dispatch.
        View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(realWebView,
            View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
    }

    @Implementation
    fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        // WebView delegates even its outer Android View frame to the absent Chromium provider.
        // Use Android View's real frame implementation, so measured native hit targets are laid
        // out by the unmodified AndroidViewHolder instead of staying at 0×0 in this JVM harness.
        Shadow.directlyOn<Boolean, View>(realWebView, View::class.java, "setFrame",
            ClassParameter.from(Int::class.javaPrimitiveType!!, left),
            ClassParameter.from(Int::class.javaPrimitiveType!!, top),
            ClassParameter.from(Int::class.javaPrimitiveType!!, right),
            ClassParameter.from(Int::class.javaPrimitiveType!!, bottom))

    @Implementation
    override fun onTouchEvent(event: MotionEvent): Boolean {
        touches += Touch(event.actionMasked, event.actionIndex,
            (0 until event.pointerCount).map(event::getPointerId),
            (0 until event.pointerCount).map { event.getX(it) to event.getY(it) }, event.eventTime)
        nativeTouchFailure?.let { throw it }
        return nativeTouchResult
    }

    @Implementation
    override fun loadUrl(url: String) {
        loadedUrls += url
        super.loadUrl(url)
    }

    @Implementation
    override fun saveState(outState: Bundle): WebBackForwardList? {
        saveCalls++
        return super.saveState(outState)
    }

    @Implementation
    override fun restoreState(inState: Bundle): WebBackForwardList? {
        restoreCalls++
        return super.restoreState(inState)
    }

    @Implementation
    override fun onPause() {
        pauseCalls++
        super.onPause()
    }

    @Implementation
    override fun onResume() {
        resumeCalls++
        super.onResume()
    }

    @Implementation
    fun stopLoading() { stopCalls++ }

    @Implementation
    override fun destroy() {
        destroyCalls++
        super.destroy()
    }
}
