package com.jarvys.agent.canvas

import com.jarvys.agent.AgentRunUiEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Verifies the production asset-only canvas page in Android's system WebView. */
@RunWith(AndroidJUnit4::class)
class ChatCanvasWebViewInstrumentedTest {
    @Test
    fun canvasBootstrapsARealMessageAndBridgeAllowsOnlyPermittedActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pageReady = CountDownLatch(1)
        val pageProbeResult = AtomicReference<String?>()
        val webViewRef = AtomicReference<ChatCanvasWebView>()
        val attempts = AtomicInteger()
        val actionReceived = CountDownLatch(1)
        val receivedActions = CopyOnWriteArrayList<Triple<String, Long, String>>()
        val event = AgentRunUiEvent(id = 42L, kind = "assistant", text = "Canvas bootstrap message", messageId = "assistant-42")
        val bridge = ChatCanvasNativeBridge(
            sessionId = "canvas-instrumented-test",
            currentEvents = { listOf(event) },
            running = { false },
            allowRetry = { true },
            hasOlder = { false },
            loadingOlder = { false },
            benchmarkStartedAtNanos = null,
            callbacks = ChatCanvasCallbacks(action = { action, clickedEvent, value ->
                receivedActions += Triple(action, clickedEvent.id, value)
                actionReceived.countDown()
            }),
        )
        val page = ChatCanvasEventSerializer.page(listOf(event), running = false)
        val update = ChatCanvasUpdate(
            bootstrapScript = "window.ChatCanvas.bootstrap(JSON.parse(${org.json.JSONObject.quote(page.toString())}));",
            patchScript = "",
            patchNeeded = false,
            benchmark = false,
        )
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var probe: Runnable

        instrumentation.runOnMainSync {
            val view = ChatCanvasWebView(instrumentation.targetContext.applicationContext)
            webViewRef.set(view)
            view.setBridge(bridge)
            // Production's first update is queued until onPageFinished, then runs the real bootstrap.
            view.update(update)
            probe = object : Runnable {
                override fun run() {
                    view.evaluateJavascript(
                        "(function(){var body=document.querySelector('#timeline .message .body');return typeof window.ChatCanvas === 'object' && body !== null && body.textContent === 'Canvas bootstrap message';})()",
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
            assertTrue("Chat canvas did not render the bootstrapped event", pageReady.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("The production canvas must render a real event through bootstrap", "true", pageProbeResult.get())

            val allowedActionResult = AtomicReference<String?>()
            val allowedActionReady = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webViewRef.get()!!.evaluateJavascript(
                    "(function(){var button=document.querySelector('#timeline .message .actions button');if(!button)return false;button.click();return true;})()",
                ) { value -> allowedActionResult.set(value); allowedActionReady.countDown() }
            }
            assertTrue("The rendered event must expose its allowed copy action", allowedActionReady.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("true", allowedActionResult.get())
            assertTrue("The bridge must dispatch the permitted action", actionReceived.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf(Triple("copy", event.id, "")), receivedActions.toList())

            val deniedActionReady = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webViewRef.get()!!.evaluateJavascript("window.Android.action('download', 42, ''); true") {
                    deniedActionReady.countDown()
                }
            }
            assertTrue("The bridge call must complete", deniedActionReady.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { }
            assertEquals("The bridge must deny download when the event has no transferable artifact",
                listOf(Triple("copy", event.id, "")), receivedActions.toList())
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
