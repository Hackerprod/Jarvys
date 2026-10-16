package com.jarvys.agent.modules

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreToolResult
import org.json.JSONObject
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
            conversationId = CONVERSATION_ID,
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
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
