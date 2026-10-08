package com.jarvys.agent

import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Small native navigation snapshots; never retain an Activity/WebView after its preview is released. */
internal class PreviewStateViewModel(handle: SavedStateHandle) : ViewModel() {
    private val pages = LinkedHashMap<String, PreviewPageState>()

    init {
        handle.get<Bundle>(STATE_KEY)?.let { saved ->
            saved.keySet().toList().takeLast(MAX_PAGES).forEach { key ->
                saved.getBundle(key)?.let { pages[key] = PreviewPageState(it) }
            }
        }
        handle.setSavedStateProvider(STATE_KEY) {
            Bundle().apply { pages.forEach { (key, page) -> putBundle(key, page.capture()) } }
        }
    }

    fun page(key: String): PreviewPageState {
        val value = pages.remove(key) ?: PreviewPageState()
        pages[key] = value
        while (pages.size > MAX_PAGES) pages.remove(pages.keys.first())
        return value
    }

    private companion object {
        const val STATE_KEY = "web-preview-pages"
        const val MAX_PAGES = 4
    }
}

internal class PreviewPageState(private var saved: Bundle = Bundle()) {
    var view: WebView? = null
        private set
    private var restoreScroll = false

    fun attach(web: WebView, initialUrl: String, isAllowed: (String) -> Boolean) {
        view = web
        restoreScroll = saved.containsKey("x")
        val native = saved.getBundle("history")
        val restored = native?.let { runCatching { web.restoreState(it) }.getOrNull() }
        // Native state originates only from the restricted client. Check the restored current page
        // as defense in depth before falling back to a validated saved URL.
        if (restored == null || restored.currentItem?.url?.let(isAllowed) != true
            || (0 until restored.size).any { restored.getItemAtIndex(it)?.url?.let(isAllowed) != true }) {
            web.clearHistory()
            val prior = saved.getString("url")?.takeIf(isAllowed)
            web.loadUrl(prior ?: initialUrl)
        }
    }

    fun userInteracted() { restoreScroll = false }

    fun pageFinished(web: WebView) {
        if (!restoreScroll || view !== web) return
        restoreScroll = false
        val x = saved.getInt("x").coerceAtLeast(0)
        val y = saved.getInt("y").coerceAtLeast(0)
        web.postOnAnimation { if (view === web) web.scrollTo(x, y) }
    }

    fun capture(): Bundle {
        view?.let { web ->
            val next = Bundle().apply {
                putInt("x", web.scrollX)
                putInt("y", web.scrollY)
                web.url?.takeIf { it.length <= 4096 }?.let { putString("url", it) }
            }
            val native = Bundle()
            if (runCatching { web.saveState(native) != null && native.parcelBytes() <= MAX_HISTORY_BYTES }.getOrDefault(false)) {
                next.putBundle("history", native)
            }
            saved = next
        }
        return Bundle(saved)
    }

    fun release(web: WebView) {
        if (view !== web) return
        try { capture() } finally { view = null }
    }

    private fun Bundle.parcelBytes(): Int {
        val parcel = Parcel.obtain()
        return try { parcel.writeBundle(this); parcel.dataSize() } finally { parcel.recycle() }
    }

    private companion object { const val MAX_HISTORY_BYTES = 48 * 1024 }
}
