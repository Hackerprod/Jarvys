package com.jarvys.agent.canvas

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Verifies the production asset-only canvas page in Android's system WebView. */
@RunWith(AndroidJUnit4::class)
class ChatCanvasWebViewInstrumentedTest {
    @Test
    fun canvasLoadsItsDocumentStylesheetAndScriptWithNetworkDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pageReady = CountDownLatch(1)
        val pageProbeResult = AtomicReference<String?>()
        val webViewRef = AtomicReference<ChatCanvasWebView>()
        val attempts = AtomicInteger()
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var probe: Runnable

        instrumentation.runOnMainSync {
            val view = ChatCanvasWebView(instrumentation.targetContext.applicationContext)
            webViewRef.set(view)
            probe = object : Runnable {
                override fun run() {
                    view.evaluateJavascript(
                        "(typeof window.ChatCanvas === 'object' && document.querySelector('#timeline') !== null && document.styleSheets.length > 0)",
                    ) { value ->
                        pageProbeResult.set(value)
                        if (value == "true" || attempts.incrementAndGet() >= MAX_PROBES) {
                            pageReady.countDown()
                        } else {
                            mainHandler.postDelayed(this, PROBE_INTERVAL_MS)
                        }
                    }
                }
            }
            view.loadUrl("${ChatCanvasWebView.ORIGIN}/index.html")
            mainHandler.postDelayed(probe, PROBE_INTERVAL_MS)
        }

        try {
            assertTrue("Chat canvas assets did not finish loading", pageReady.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("Chat canvas production assets did not load", "true", pageProbeResult.get())
        } finally {
            instrumentation.runOnMainSync {
                webViewRef.get()?.let { view ->
                    mainHandler.removeCallbacks(probe)
                    view.stopLoading()
                    view.removeJavascriptInterface("Android")
                    view.webChromeClient = null
                    view.destroy()
                }
            }
        }
    }

    private companion object {
        const val MAX_PROBES = 100
        const val PROBE_INTERVAL_MS = 100L
        const val PAGE_TIMEOUT_SECONDS = 15L
    }
}
