package com.jarvys.agent.modules

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ConversationModuleCapabilityAccess
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import java.util.UUID
import java.io.File

/** Exercises the production asset bundles in the Android system WebView, not a JS test engine. */
@RunWith(AndroidJUnit4::class)
class WebViewModuleRuntimeInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun createChartLoadsProductionBundleAndDeliversRenderedArtifactAndThumbnail() {
        val module = ModuleRegistry.fromAssets(context).module("com.jarvys.module.chart")!!
        val tool = module.tools.single { it.name == "create_chart" }
        val access = CapturingModuleAccess(CONVERSATION_ID)

        val result = executeInWebView(module, tool, mapOf(
            "title" to "Instrumented WebView chart",
            "chartType" to "bar",
            "labels" to listOf("Q1", "Q2", "Q3"),
            "datasets" to listOf(mapOf("label" to "Revenue", "data" to listOf(4, 7, 5))),
        ), access)

        assertTrue("create_chart failed in Android WebView: ${result.content}", result.success)
        val delivery = requireNotNull(access.delivery.get()) { "create_chart did not deliver an artifact" }
        assertEquals("application/vnd.jarvys.chart+json", delivery.artifactType)
        val chart = JSONObject(String(delivery.bytes, Charsets.UTF_8))
        assertEquals(1, chart.getInt("schemaVersion"))
        assertEquals("Instrumented WebView chart", chart.getString("title"))
        assertEquals("bar", chart.getString("chartType"))
        assertEquals(3, chart.getJSONObject("data").getJSONArray("labels").length())
        assertRenderedPng(delivery.thumbnailMimeType, delivery.thumbnailBytes)
    }

    @Test
    fun createPresentationLoadsProductionBundleAndDeliversPptxAndRenderedThumbnail() {
        val module = ModuleRegistry.fromAssets(context).module("com.jarvys.module.presentation")!!
        val tool = module.tools.single { it.name == "create_presentation" }
        val access = CapturingModuleAccess(CONVERSATION_ID)

        val result = executeInWebView(module, tool, mapOf(
            "title" to "Instrumented WebView presentation",
            "slides" to listOf(mapOf(
                "title" to "WebView regression slide",
                "body" to "Rendered through the Android system WebView",
            )),
        ), access)

        assertTrue("create_presentation failed in Android WebView: ${result.content}", result.success)
        val delivery = requireNotNull(access.delivery.get()) { "create_presentation did not deliver an artifact" }
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            delivery.artifactType,
        )
        val slideXml = readZipEntry(delivery.bytes, "ppt/slides/slide1.xml")
        assertNotNull("PPTX bundle is missing slide1.xml", slideXml)
        assertTrue("PPTX slide does not contain the generated title", requireNotNull(slideXml).contains("WebView regression slide"))
        assertRenderedPng(delivery.thumbnailMimeType, delivery.thumbnailBytes)
    }

    @Test
    fun presentationViewerHarnessCreatesOwnerDecksAndOpensProductionScreen() {
        val module = ModuleRegistry.fromAssets(context).module("com.jarvys.module.presentation")!!
        val tool = module.tools.single { it.name == "create_presentation" }
        val conversationId = "viewer-harness-${UUID.randomUUID()}"
        assertTrue(
            "Could not create the temporary harness conversation",
            ChatDatabaseBridge.getInstance(context).ensureConversationSync(conversationId) is ChatDbResult.Success,
        )
        val pssBefore = appProcessPssKb()

        fun createAndPersist(label: String, arguments: Map<String, Any?>): String {
            val access = CapturingModuleAccess(conversationId)
            val startedAt = SystemClock.elapsedRealtime()
            val result = executeInWebView(module, tool, arguments, access)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            assertTrue("create_presentation failed in Android WebView: ${result.content}", result.success)
            val delivery = requireNotNull(access.delivery.get()) { "create_presentation did not deliver an artifact" }
            assertRenderedPng(delivery.thumbnailMimeType, delivery.thumbnailBytes)
            val thumbnailDirectory = File(requireNotNull(context.getExternalFilesDir(null)), "viewer-harness")
            check(thumbnailDirectory.mkdirs() || thumbnailDirectory.isDirectory) { "Could not create the thumbnail capture directory" }
            val thumbnailFile = File(thumbnailDirectory, "$label-thumbnail.png").apply { writeBytes(delivery.thumbnailBytes) }
            val artifactId = persistDelivery(conversationId, module, delivery)
            Log.i(
                "ArtifactViewerHarness",
                "handler complete elapsedMs=$elapsed appPssKb=${appProcessPssKb()} pptxBytes=${delivery.bytes.size} " +
                    "thumbnailBytes=${delivery.thumbnailBytes.size} thumbnailFile=${thumbnailFile.absolutePath} artifactId=$artifactId",
            )
            return artifactId
        }

        val deckArtifactId = createAndPersist("owner-three-slide", mapOf(
            "title" to "Regional growth",
            "slides" to listOf(
                mapOf(
                    "title" to "Regional growth accelerated",
                    "body" to "Revenue rose 22% this quarter, led by the North and South teams.",
                    "backgroundColor" to "F4F6FB",
                    "bullets" to listOf("North finished at 42", "South finished at 57"),
                    "shapes" to listOf(
                        mapOf("type" to "ellipse", "x" to 10.7, "y" to 0.5, "w" to 1.6, "h" to 1.6, "fillColor" to "536DFE", "lineColor" to "536DFE"),
                        mapOf("type" to "ellipse", "x" to 11.55, "y" to 1.3, "w" to 0.8, "h" to 0.8, "fillColor" to "18A999", "lineColor" to "18A999"),
                    ),
                ),
                mapOf(
                    "title" to "North and South lead growth",
                    "body" to "Both regions grew, with the strongest gains in the latest quarter.",
                    "backgroundColor" to "FFFFFF",
                ),
                mapOf(
                    "title" to "Keep the momentum",
                    "body" to "Invest in the teams and markets that drove this quarter's result.",
                    "backgroundColor" to "14233B",
                    "shapes" to listOf(
                        mapOf("type" to "ellipse", "x" to 0.7, "y" to 5.5, "w" to 1.2, "h" to 1.2, "fillColor" to "2459D3", "lineColor" to "2459D3"),
                    ),
                ),
            ),
        ))
        val chartTableArtifactId = createAndPersist("chart-table", mapOf(
            "title" to "Regional sales details",
            "slides" to listOf(mapOf(
                "title" to "Sales by region",
                "backgroundColor" to "F4F6FB",
                "chart" to mapOf(
                    "title" to "Revenue",
                    "labels" to listOf("North", "South"),
                    "datasets" to listOf(mapOf("name" to "Sales", "values" to listOf(42, 57), "color" to "2459D3")),
                ),
                "table" to mapOf(
                    "headers" to listOf("Region", "Revenue"),
                    "rows" to listOf(listOf("North", "42"), listOf("South", "57")),
                ),
            )),
        ))

        Log.i(
            "ArtifactViewerHarness",
            "fixtures conversationId=$conversationId threeSlideArtifactId=$deckArtifactId chartTableArtifactId=$chartTableArtifactId " +
                "appPssBeforeKb=$pssBefore appPssAfterKb=${appProcessPssKb()}",
        )
        fun launchViewer(artifactId: String) = Intent(context, ArtifactViewerDebugActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ArtifactViewerDebugActivity.EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(ArtifactViewerDebugActivity.EXTRA_ARTIFACT_ID, artifactId)

        fun openAndObserve(label: String, artifactId: String, expectedCount: String): android.app.Activity {
            val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(launchViewer(artifactId))
            val webView = awaitViewerWebView(activity)
            val viewerStartedAt = SystemClock.elapsedRealtime()
            val state = awaitViewerState(webView, VIEWER_OBSERVE_TIMEOUT_SECONDS)
            val bodyHeight = state.optJSONArray("bodyRect")?.optDouble(3, 0.0) ?: 0.0
            val stageHeight = state.optJSONArray("stageRect")?.optDouble(3, 0.0) ?: 0.0
            val navigationHeight = state.optJSONArray("navigationRect")?.optDouble(3, 0.0) ?: 0.0
            Log.i(
                "ArtifactViewerHarness",
                "$label viewer observed elapsedMs=${SystemClock.elapsedRealtime() - viewerStartedAt} state=$state " +
                    "appPssKb=${appProcessPssKb()} rendererAlive=${webView.parent != null} ${nativeViewGeometry(webView)}",
            )
            Log.i("ArtifactViewerHarness", "capture window open label=$label for 10 seconds")
            SystemClock.sleep(10_000)
            assertEquals("$label should show its first slide", expectedCount, state.optString("counter"))
            assertTrue("$label WebView body should occupy the viewport: $state", bodyHeight > 0)
            assertTrue("$label slide should be visible: $state", stageHeight > 0)
            assertTrue("$label navigation should be visible: $state", navigationHeight > 0)
            return activity
        }

        val ownerActivity = openAndObserve("owner-three-slide", deckArtifactId, "Slide 1 of 3")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ownerActivity.finish() }
        openAndObserve("chart-table", chartTableArtifactId, "Slide 1 of 1")
    }

    private fun executeInWebView(
        module: ModuleManifest,
        tool: ModuleToolDeclaration,
        arguments: Map<String, Any?>,
        access: CapturingModuleAccess,
    ): CoreToolResult {
        val token = CancellationToken.cancellable()
        val request = ModuleExecutionRequest(
            module = module,
            tool = tool,
            arguments = arguments,
            conversationId = access.conversationId,
            requester = "instrumented-test",
            requesterColorKey = null,
        )
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<CoreToolResult> {
            check(Looper.myLooper() != Looper.getMainLooper())
            WebViewModuleRuntime(context) { _, _, _ -> access }.execute(request, token, null)
        }
        try {
            return future.get(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            token.cancel()
            future.cancel(true)
            throw AssertionError("Module WebView execution exceeded the instrumentation timeout", timeout)
        } finally {
            token.cancel()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun persistDelivery(
        conversationId: String,
        module: ModuleManifest,
        delivery: CapturedDelivery,
    ): String {
        val token = CancellationToken.cancellable()
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<String> {
                ConversationModuleCapabilityAccess(context, conversationId, module, token, null)
                    .deliverArtifact(
                        delivery.bytes,
                        delivery.name,
                        delivery.artifactType,
                        delivery.title,
                        delivery.thumbnailBytes,
                        delivery.thumbnailMimeType,
                        delivery.deliveryId,
                    ).getString("id")
            }.get(PERSIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            token.cancel()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun awaitViewerWebView(activity: android.app.Activity): WebView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + VIEWER_OBSERVE_TIMEOUT_SECONDS * 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val found = AtomicReference<WebView?>()
            instrumentation.runOnMainSync { found.set(activity.window.decorView.findWebView()) }
            found.get()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("The production viewer screen did not attach its WebView")
    }

    private fun awaitViewerState(webView: WebView, timeoutSeconds: Long): JSONObject {
        val script = """JSON.stringify({
            readyState: document.readyState,
            title: document.title,
            language: document.documentElement.lang,
            pageUrl: location.href,
            bodyText: document.body ? document.body.innerText : '',
            navigation: !!document.getElementById('navigation'),
            counter: document.getElementById('counter') ? document.getElementById('counter').textContent : null,
            status: document.getElementById('status') ? document.getElementById('status').textContent : null,
            statusHidden: document.getElementById('status') ? document.getElementById('status').hidden : null,
            imageWidth: document.getElementById('slide') ? document.getElementById('slide').naturalWidth : 0,
            imageHeight: document.getElementById('slide') ? document.getElementById('slide').naturalHeight : 0,
            viewport: [innerWidth, innerHeight, devicePixelRatio],
            documentClientHeight: document.documentElement ? document.documentElement.clientHeight : 0,
            htmlHeight: document.documentElement ? getComputedStyle(document.documentElement).height : null,
            htmlRect: document.documentElement ? (() => { const r = document.documentElement.getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; })() : null,
            bodyHeight: document.body ? getComputedStyle(document.body).height : null,
            bodyRect: document.body ? (() => { const r = document.body.getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; })() : null,
            stageRect: document.getElementById('stage') ? (() => { const r = document.getElementById('stage').getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; })() : null,
            stageHeight: document.getElementById('stage') ? getComputedStyle(document.getElementById('stage')).height : null,
            imageRect: document.getElementById('slide') ? (() => { const r = document.getElementById('slide').getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; })() : null,
            navigationRect: document.getElementById('navigation') ? (() => { const r = document.getElementById('navigation').getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; })() : null,
            scrollHeight: document.documentElement ? document.documentElement.scrollHeight : 0,
            background: document.body ? getComputedStyle(document.body).backgroundColor : null,
            stageBackground: document.getElementById('stage') ? getComputedStyle(document.getElementById('stage')).backgroundColor : null,
            navigationDisplay: document.getElementById('navigation') ? getComputedStyle(document.getElementById('navigation')).display : null,
            imageDisplay: document.getElementById('slide') ? getComputedStyle(document.getElementById('slide')).display : null
        })"""
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000L
        var last = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            val encoded = evaluateJavascript(instrumentation, webView, script)
            if (!encoded.isNullOrBlank() && encoded != "null") {
                last = runCatching {
                    val value = JSONTokener(encoded).nextValue()
                    JSONObject(value as? String ?: encoded)
                }.getOrDefault(JSONObject().put("raw", encoded))
                val rendered = last.optInt("imageWidth") > 0 && last.optInt("imageHeight") > 0
                val failed = last.has("status") && !last.isNull("status") &&
                    !last.optBoolean("statusHidden", true) && last.optString("status").isNotBlank()
                if (rendered || failed) return last
            }
            SystemClock.sleep(250)
        }
        return last
    }

    private fun nativeViewGeometry(webView: WebView): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val geometry = AtomicReference<String>()
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            webView.getLocationOnScreen(location)
            geometry.set("nativeView=${webView.width}x${webView.height} shown=${webView.isShown} " +
                "visibility=${webView.visibility} screenX=${location[0]} screenY=${location[1]}")
        }
        return geometry.get() ?: "nativeView=unavailable"
    }

    private fun evaluateJavascript(
        instrumentation: android.app.Instrumentation,
        webView: WebView,
        script: String,
    ): String? {
        val result = AtomicReference<String?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { value -> result.set(value); latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).findWebView()?.let { return it }
        }
        return null
    }

    private fun appProcessPssKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    private fun assertRenderedPng(mimeType: String, bytes: ByteArray) {
        assertEquals("image/png", mimeType)
        assertTrue("Thumbnail is unexpectedly small", bytes.size > 100)
        assertArrayEquals(PNG_SIGNATURE, bytes.copyOfRange(0, PNG_SIGNATURE.size))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Thumbnail is not a decodable PNG", bitmap)
        val image = requireNotNull(bitmap)
        try {
            assertEquals(1280, image.width)
            assertEquals(720, image.height)
            val background = image.getPixel(0, 0)
            var changedSamples = 0
            for (y in 0 until image.height step 4) {
                for (x in 0 until image.width step 4) {
                    if (image.getPixel(x, y) != background) changedSamples++
                }
            }
            assertTrue("Rendered thumbnail is blank", changedSamples > 10)
        } finally {
            image.recycle()
        }
    }

    private fun readZipEntry(bytes: ByteArray, expectedName: String): String? =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found: String? = null
            while (found == null) {
                val entry = zip.nextEntry ?: break
                if (entry.name == expectedName) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    found = output.toString(Charsets.UTF_8.name())
                }
            }
            found
        }

    private class CapturingModuleAccess(override val conversationId: String) : ModuleCapabilityAccess {
        val delivery = AtomicReference<CapturedDelivery?>()

        override fun readWorkspace(path: String): String = error("Workspace access is not expected in this test")
        override fun writeWorkspace(path: String, content: String): Unit = error("Workspace access is not expected in this test")
        override fun deliverArtifact(path: String, name: String, artifactType: String, title: String?): JSONObject =
            error("Path-based artifact delivery is not expected in this test")

        override fun deliverArtifact(
            bytes: ByteArray,
            name: String,
            artifactType: String,
            title: String?,
            thumbnailBytes: ByteArray,
            thumbnailMimeType: String,
            deliveryId: String,
        ): JSONObject {
            check(delivery.compareAndSet(null, CapturedDelivery(
                bytes.copyOf(), name, artifactType, title, thumbnailBytes.copyOf(), thumbnailMimeType, deliveryId,
            ))) { "Module delivered more than one artifact" }
            return JSONObject().put("id", "instrumented-artifact")
        }

        override fun reportProgress(message: String): Unit = error("Progress is not expected in this test")
        override fun log(message: String) = Unit
        override fun fetchNetwork(url: String): String = error("Network access is not expected in this test")
    }

    private data class CapturedDelivery(
        val bytes: ByteArray,
        val name: String,
        val artifactType: String,
        val title: String?,
        val thumbnailBytes: ByteArray,
        val thumbnailMimeType: String,
        val deliveryId: String,
    )

    private companion object {
        const val CONVERSATION_ID = "instrumented-webview-conversation"
        const val EXECUTION_TIMEOUT_SECONDS = 90L
        const val PERSIST_TIMEOUT_SECONDS = 45L
        const val VIEWER_OBSERVE_TIMEOUT_SECONDS = 45L
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
