package com.jarvys.agent.modules

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.ConversationModuleCapabilityAccess
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.R
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import com.jarvys.agent.chatdb.ChatFileOwnership
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import kotlin.math.abs

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
                "background" to "FFFFFF",
                "elements" to listOf(mapOf(
                    "type" to "text",
                    "text" to "WebView regression slide",
                    "x" to 0.8,
                    "y" to 0.8,
                    "w" to 8.0,
                    "h" to 1.0,
                    "fontSize" to 30,
                    "fontFace" to "Arial",
                    "color" to "000000",
                    "bold" to true,
                    "margin" to 0,
                ), mapOf(
                    "type" to "text",
                    "text" to "Rendered through the Android system WebView",
                    "x" to 0.8,
                    "y" to 2.0,
                    "w" to 8.0,
                    "h" to 0.8,
                    "fontSize" to 18,
                    "fontFace" to "Arial",
                    "color" to "000000",
                    "margin" to 0,
                )),
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
        val toolContent = JSONObject(result.content)
        val measurementReport = toolContent.getJSONObject("measurementReport")
        assertEquals("LAYOUT_WIDE", measurementReport.getJSONObject("layout").getString("name"))
        assertEquals(13.333333333333334, measurementReport.getJSONObject("layout").getDouble("width_in"), 0.000001)
        val measuredSlide = measurementReport.getJSONArray("slides").getJSONObject(0)
        val measuredTitle = measuredSlide.getJSONArray("elements").getJSONObject(0)
        assertEquals("canvas-font-metrics", measurementReport.getString("metrics_source"))
        assertTrue("the requested x/y in EMU must remain exact", requireNotNull(slideXml).contains("<a:off x=\"731520\" y=\"731520\"/>"))
        assertTrue("the requested w/h in EMU must remain exact", requireNotNull(slideXml).contains("<a:ext cx=\"7315200\" cy=\"914400\"/>"))
        assertTrue("canvas measurement must return a positive required text height", measuredTitle.getDouble("needed_h") > 0.0)
        assertFalse("a fitting title omits the overflow finding", measuredTitle.has("overflow"))
        assertFalse("a clean element omits its box", measuredTitle.has("box"))
        assertTrue(measuredTitle.getDouble("contrast") > 20.0)
        assertEquals(2, measuredSlide.getJSONArray("elements").length())
        assertFalse("a clean slide omits the overlap list", measuredSlide.has("overlaps"))
        assertRenderedPng(delivery.thumbnailMimeType, delivery.thumbnailBytes)
    }

    @Test
    fun createPresentationReturnsCorrectableValidationMessageThroughCoreToolResult() {
        val module = ModuleRegistry.fromAssets(context).module("com.jarvys.module.presentation")!!
        val tool = module.tools.single { it.name == "create_presentation" }
        val access = CapturingModuleAccess(CONVERSATION_ID)

        val result = executeInWebView(module, tool, mapOf(
            "title" to "Invalid element type",
            "slides" to listOf(mapOf("elements" to listOf(mapOf(
                "type" to "audio",
                "x" to 0,
                "y" to 0,
                "w" to 1,
                "h" to 1,
            )))),
        ), access)

        assertFalse("invalid input must fail through the normal tool result", result.success)
        assertEquals("Slide 1, element 0 (audio): type is unknown.", result.content)
        assertEquals("invalid input must not deliver a partial artifact", null, access.delivery.get())
    }

    @Test
    fun createPresentationKeepsUnexpectedHandlerExceptionOpaque() {
        val module = ModuleRegistry.fromAssets(context).module("com.jarvys.module.presentation")!!
        val tool = module.tools.single { it.name == "create_presentation" }
        val access = CapturingModuleAccess(CONVERSATION_ID).apply { omitArtifactId = true }

        val result = executeInWebView(module, tool, mapOf(
            "title" to "Unexpected handler error",
            "slides" to listOf(mapOf("elements" to listOf(mapOf(
                "type" to "text",
                "text" to "Valid element",
                "x" to 0.8,
                "y" to 0.8,
                "w" to 4.0,
                "h" to 0.8,
            )))),
        ), access)

        assertFalse("the handler exception must remain a failed tool result", result.success)
        assertEquals("Module handler raised JavaScript error: logic/tools.js#createPresentation", result.content)
        assertFalse("the exception message must not reach the tool result", result.content.contains("The host did not confirm"))
    }

    @Test
    fun createPresentationMeasuresRealMarginsBulletsAndEffectiveContrast() {
        val module = ModuleRegistry.fromAssets(context).module("com.jarvys.module.presentation")!!
        val tool = module.tools.single { it.name == "create_presentation" }
        val access = CapturingModuleAccess(CONVERSATION_ID)

        val result = executeInWebView(module, tool, mapOf(
            "title" to "Canvas measurement regressions",
            "slides" to listOf(mapOf(
                "background" to "FFFFFF",
                "elements" to listOf(
                    mapOf("type" to "shape", "shape" to "rect", "x" to 0.8, "y" to 1.0, "w" to 3.2, "h" to 1.4, "fill" to mapOf("color" to "112244")),
                    mapOf("type" to "text", "text" to "White on dark card", "x" to 1.0, "y" to 1.2, "w" to 2.8, "h" to 0.6, "fontSize" to 18, "color" to "FFFFFF", "margin" to 0),
                    mapOf("type" to "text", "text" to "Light grey on white", "x" to 4.5, "y" to 1.2, "w" to 2.8, "h" to 0.6, "fontSize" to 18, "color" to "D8D8D8", "margin" to 0),
                    mapOf("type" to "text", "text" to "X", "x" to 0.8, "y" to 3.0, "w" to 0.6, "h" to 0.6, "fontSize" to 18, "color" to "000000", "margin" to listOf(0, 0, 18, 18)),
                    mapOf("type" to "text", "text" to "Scalar inset", "x" to 2.0, "y" to 3.0, "w" to 4.0, "h" to 1.0, "fontSize" to 18, "color" to "000000", "margin" to 6),
                    mapOf("type" to "text", "text" to "A long bullet paragraph wraps after the PptxGenJS hanging indent", "x" to 7.0, "y" to 1.0, "w" to 1.2, "h" to 2.0, "fontSize" to 16, "color" to "000000", "margin" to 0, "bullet" to true),
                ),
            )),
        ), access)

        assertTrue("create_presentation failed in Android WebView: ${result.content}", result.success)
        val delivery = requireNotNull(access.delivery.get()) { "create_presentation did not deliver an artifact" }
        val content = JSONObject(result.content)
        val report = content.getJSONObject("measurementReport")
        assertEquals("canvas-font-metrics", report.getString("metrics_source"))
        val elements = report.getJSONArray("slides").getJSONObject(0).getJSONArray("elements")
        assertTrue("white text must use the dark rectangle beneath it", elements.getJSONObject(1).getDouble("contrast") > 7.0)
        assertTrue("light grey text on white must report low contrast", elements.getJSONObject(2).getDouble("contrast") < 4.5)
        val exactMargin = elements.getJSONObject(3)
        assertTrue("18 pt font with 18 pt top and bottom margins must overflow a 0.6 in box", exactMargin.getBoolean("overflow"))
        assertTrue(exactMargin.getDouble("needed_h") > 0.6)

        val slideXml = requireNotNull(readZipEntry(delivery.bytes, "ppt/slides/slide1.xml"))
        assertTrue(slideXml.contains("bIns=\"228600\""))
        assertTrue(slideXml.contains("tIns=\"228600\""))
        assertTrue(slideXml.contains("lIns=\"76200\""))
        assertTrue(slideXml.contains("rIns=\"76200\""))
        assertTrue(slideXml.contains("marL=\"342900\""))
        assertTrue(slideXml.contains("indent=\"-342900\""))
        assertRenderedPng(delivery.thumbnailMimeType, delivery.thumbnailBytes)
    }

    @Test
    fun presentationViewerHarnessCreatesOwnerDecksAndOpensProductionScreen() {
        val registry = ModuleRegistry.fromAssets(context)
        val module = registry.module("com.jarvys.module.presentation")!!
        val presentationTool = module.tools.single { it.name == "create_presentation" }
        val chartModule = registry.module("com.jarvys.module.chart")!!
        val chartTool = chartModule.tools.single { it.name == "create_chart" }
        val conversationId = "viewer-harness-${UUID.randomUUID()}"
        val database = ChatDatabaseBridge.getInstance(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "Could not create the temporary harness conversation",
            database.ensureConversationSync(conversationId) is ChatDbResult.Success,
        )
        val cleanupIds = InstrumentationRegistry.getArguments()
            .getString("cleanupHarnessConversations").orEmpty().split(',')
            .filter { it.startsWith("viewer-harness-") }
        cleanupIds.forEach { staleId ->
            assertTrue(
                "Could not clean an earlier temporary harness conversation",
                database.deleteConversationSync(staleId) is ChatDbResult.Success,
            )
        }
        val openedActivities = mutableListOf<android.app.Activity>()
        try {
            val pssBefore = appProcessPssKb()

            fun createAndPersist(
                selectedModule: ModuleManifest,
                tool: ModuleToolDeclaration,
                label: String,
                arguments: Map<String, Any?>,
            ): String {
                val access = CapturingModuleAccess(conversationId)
                val startedAt = SystemClock.elapsedRealtime()
                val result = executeInWebView(selectedModule, tool, arguments, access)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                assertTrue("$label creation failed in Android WebView: ${result.content}", result.success)
                val delivery = requireNotNull(access.delivery.get()) { "$label did not deliver an artifact" }
                assertRenderedPng(delivery.thumbnailMimeType, delivery.thumbnailBytes)
                val artifactId = persistDelivery(conversationId, selectedModule, delivery)
                Log.i(
                    "ArtifactViewerHarness",
                    "$label handler complete elapsedMs=$elapsed appPssKb=${appProcessPssKb()} " +
                        "artifactBytes=${delivery.bytes.size} thumbnailBytes=${delivery.thumbnailBytes.size} artifactId=$artifactId",
                )
                return artifactId
            }

            val deckArtifactId = createAndPersist(module, presentationTool, "owner-three-slide", mapOf(
                "title" to "Regional growth",
                "slides" to listOf(
                    mapOf(
                        "background" to "F4F6FB",
                        "elements" to listOf(
                            mapOf("type" to "shape", "shape" to "ellipse", "x" to 10.8, "y" to 0.5, "w" to 1.5, "h" to 1.5, "fill" to mapOf("color" to "DDEBFF"), "line" to mapOf("color" to "DDEBFF")),
                            mapOf("type" to "text", "text" to "Regional growth accelerated as field teams adapted the shared playbook", "x" to 0.8, "y" to 0.55, "w" to 9.5, "h" to 1.25, "fontSize" to 29, "fontFace" to "Arial", "color" to "18253A", "bold" to true, "margin" to 0),
                            mapOf("type" to "text", "text" to "Revenue rose 22% this quarter, led by the North and South teams.", "x" to 0.8, "y" to 1.95, "w" to 9.5, "h" to 0.5, "fontSize" to 18, "fontFace" to "Arial", "color" to "344054", "margin" to 0),
                            mapOf("type" to "text", "text" to "North finished at 42", "x" to 0.95, "y" to 2.85, "w" to 7.5, "h" to 0.55, "fontSize" to 20, "fontFace" to "Arial", "color" to "18253A", "bullet" to true, "margin" to 0),
                            mapOf("type" to "text", "text" to "South finished at 57", "x" to 0.95, "y" to 3.6, "w" to 7.5, "h" to 0.55, "fontSize" to 20, "fontFace" to "Arial", "color" to "18253A", "bullet" to true, "margin" to 0),
                            mapOf("type" to "text", "text" to "West held a steady result", "x" to 0.95, "y" to 4.35, "w" to 7.5, "h" to 0.55, "fontSize" to 20, "fontFace" to "Arial", "color" to "18253A", "bullet" to true, "margin" to 0),
                            mapOf("type" to "text", "text" to "Each region stayed within its cost plan", "x" to 0.95, "y" to 5.1, "w" to 7.5, "h" to 0.55, "fontSize" to 20, "fontFace" to "Arial", "color" to "18253A", "bullet" to true, "margin" to 0),
                        ),
                    ),
                    mapOf(
                        "elements" to listOf(
                            mapOf("type" to "text", "text" to "North and South lead growth", "x" to 0.8, "y" to 0.7, "w" to 11.5, "h" to 0.9, "fontSize" to 30, "fontFace" to "Arial", "color" to "18253A", "bold" to true, "margin" to 0),
                            mapOf("type" to "text", "text" to "Both regions grew, with the strongest gains in the latest quarter.", "x" to 0.8, "y" to 2.0, "w" to 9.0, "h" to 1.0, "fontSize" to 22, "fontFace" to "Arial", "color" to "344054", "margin" to 0),
                        ),
                    ),
                    mapOf(
                        "background" to "14233B",
                        "elements" to listOf(
                            mapOf("type" to "shape", "shape" to "ellipse", "x" to 0.8, "y" to 5.5, "w" to 1.2, "h" to 1.2, "fill" to mapOf("color" to "2459D3"), "line" to mapOf("color" to "2459D3")),
                            mapOf("type" to "text", "text" to "Keep the momentum", "x" to 0.8, "y" to 0.8, "w" to 10.5, "h" to 1.0, "fontSize" to 34, "fontFace" to "Arial", "color" to "FFFFFF", "bold" to true, "margin" to 0),
                            mapOf("type" to "text", "text" to "Invest in the teams and markets that drove this quarter's result.", "x" to 0.8, "y" to 2.25, "w" to 9.4, "h" to 1.0, "fontSize" to 22, "fontFace" to "Arial", "color" to "FFFFFF", "margin" to 0),
                        ),
                    ),
                ),
            ))
            val chartTableArtifactId = createAndPersist(module, presentationTool, "chart-table", mapOf(
                "title" to "Regional sales details",
                "slides" to listOf(mapOf(
                    "background" to "F4F6FB",
                    "elements" to listOf(
                        mapOf("type" to "text", "text" to "Sales by region", "x" to 0.8, "y" to 0.45, "w" to 11.7, "h" to 0.8, "fontSize" to 29, "fontFace" to "Arial", "color" to "18253A", "bold" to true, "margin" to 0),
                        mapOf("type" to "chart", "chartType" to "bar", "labels" to listOf("North", "South"), "datasets" to listOf(mapOf("name" to "Sales", "values" to listOf(42, 57), "color" to "2459D3")), "x" to 0.8, "y" to 1.6, "w" to 5.5, "h" to 4.7, "options" to mapOf("title" to "Revenue", "showTitle" to true, "showValue" to true, "showLegend" to false)),
                        mapOf("type" to "table", "rows" to listOf(listOf("Region", "Revenue"), listOf("North", "42"), listOf("South", "57")), "x" to 7.0, "y" to 1.75, "w" to 5.4, "colW" to listOf(2.8, 2.6), "rowH" to listOf(0.75, 0.75, 0.75), "fontSize" to 18, "fontFace" to "Arial", "color" to "18253A", "margin" to 0.08),
                    ),
                )),
            ))
            val chartArtifactId = createAndPersist(chartModule, chartTool, "chart-viewer", mapOf(
                "title" to "Revenue by quarter",
                "chartType" to "bar",
                "labels" to listOf("Q1", "Q2", "Q3"),
                "datasets" to listOf(mapOf("label" to "Revenue", "data" to listOf(42, 57, 51))),
            ))
            val chartArtifact = database.findChatFileSync(conversationId, "delivered", chartArtifactId)
                as? ChatFileOwnership.Delivered
            assertNotNull("Chart viewer artifact should be stored as a delivered artifact", chartArtifact)
            assertEquals("com.jarvys.module.chart", requireNotNull(chartArtifact).value.module_id)
            assertEquals("application/vnd.jarvys.chart+json", chartArtifact.value.artifact_type)

            Log.i(
                "ArtifactViewerHarness",
                "fixtures conversationId=$conversationId threeSlideArtifactId=$deckArtifactId " +
                    "chartTableArtifactId=$chartTableArtifactId chartArtifactId=$chartArtifactId " +
                    "appPssBeforeKb=$pssBefore appPssAfterKb=${appProcessPssKb()}",
            )
            fun launchViewer(artifactId: String) = Intent(context, ArtifactViewerDebugActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ArtifactViewerDebugActivity.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(ArtifactViewerDebugActivity.EXTRA_ARTIFACT_ID, artifactId)

            fun startViewer(artifactId: String): android.app.Activity =
                instrumentation.startActivitySync(launchViewer(artifactId)).also(openedActivities::add)

            fun observePresentation(label: String, activity: android.app.Activity, expectedCount: String? = null): Pair<WebView, JSONObject> {
                val webView = awaitViewerWebView(activity)
                val viewerStartedAt = SystemClock.elapsedRealtime()
                val state = awaitViewerState(webView, VIEWER_OBSERVE_TIMEOUT_SECONDS, expectedCount)
                val bodyHeight = state.optJSONArray("bodyRect")?.optDouble(3, 0.0) ?: 0.0
                val stageHeight = state.optJSONArray("stageRect")?.optDouble(3, 0.0) ?: 0.0
                val navigationHeight = state.optJSONArray("navigationRect")?.optDouble(3, 0.0) ?: 0.0
                Log.i(
                    "ArtifactViewerHarness",
                    "$label viewer observed elapsedMs=${SystemClock.elapsedRealtime() - viewerStartedAt} state=$state " +
                        "appPssKb=${appProcessPssKb()} rendererAlive=${webView.parent != null} ${nativeViewGeometry(webView)}",
                )
                if (expectedCount != null) {
                    assertEquals("$label should show the requested slide", expectedCount, state.optString("counter"))
                }
                assertTrue("$label WebView body should occupy the viewport: $state", bodyHeight > 0)
                assertTrue("$label slide should be visible: $state", stageHeight > 0)
                assertTrue("$label navigation should be visible: $state", navigationHeight > 0)
                return webView to state
            }

            val deckActivity = startViewer(deckArtifactId)
            val (deckWebView, initialDeckState) = observePresentation("owner-three-slide", deckActivity)
            val spanish = initialDeckState.optString("language").startsWith("es", ignoreCase = true)
            val firstSlideLabel = slideCounter(1, 3, spanish)
            val secondSlideLabel = slideCounter(2, 3, spanish)
            assertEquals("The initial slide counter should report one of three", firstSlideLabel, initialDeckState.optString("counter"))
            captureUiIfRequested(
                "presentation-three-slides",
                deckWebView,
                initialDeckState,
                Color.rgb(244, 246, 251),
            )
            evaluateJavascript(instrumentation, deckWebView, "document.getElementById('next').click()")
            val secondSlide = awaitViewerState(deckWebView, VIEWER_OBSERVE_TIMEOUT_SECONDS, secondSlideLabel)
            assertEquals("Next should show slide two", secondSlideLabel, secondSlide.optString("counter"))
            evaluateJavascript(instrumentation, deckWebView, "document.getElementById('previous').click()")
            val firstSlideAgain = awaitViewerState(deckWebView, VIEWER_OBSERVE_TIMEOUT_SECONDS, firstSlideLabel)
            assertEquals("Previous should return to slide one", firstSlideLabel, firstSlideAgain.optString("counter"))

            val chartTableActivity = startViewer(chartTableArtifactId)
            val (_, chartTableState) = observePresentation("chart-table", chartTableActivity)
            assertEquals("Chart and table deck should report one of one", slideCounter(1, 1, spanish), chartTableState.optString("counter"))
            val chartTableWebView = awaitViewerWebView(chartTableActivity)
            captureUiIfRequested(
                "presentation-chart-table",
                chartTableWebView,
                chartTableState,
                Color.rgb(244, 246, 251),
            )

            val chartActivity = startViewer(chartArtifactId)
            val chartWebView = awaitViewerWebView(chartActivity)
            val chartState = awaitChartViewerState(chartWebView, VIEWER_OBSERVE_TIMEOUT_SECONDS, "Revenue by quarter")
            assertTrue("Chart canvas should have a visible native-sized drawing surface: $chartState", chartState.optInt("canvasWidth") > 0 && chartState.optInt("canvasHeight") > 0)
            assertTrue("Chart plot should occupy the viewport: $chartState", chartState.optDouble("plotHeight") > 0)
            assertTrue("Chart.js should have painted the canvas: $chartState", chartState.optInt("paintedPixels") > 0)
            Log.i("ArtifactViewerHarness", "chart viewer observed state=$chartState ${nativeViewGeometry(chartWebView)}")
            captureUiIfRequested("chart-module-viewer", chartWebView)

            val fallbackActivity = startViewer(deckArtifactId)
            val fallbackWebView = awaitViewerWebView(fallbackActivity)
            observePresentation("fallback-source", fallbackActivity, firstSlideLabel)
            val terminated = AtomicReference(false)
            instrumentation.runOnMainSync {
                terminated.set(fallbackWebView.webViewRenderProcess?.terminate() == true)
            }
            assertTrue("Could not terminate the viewer renderer for fallback coverage", terminated.get())
            awaitViewerFallback(fallbackActivity, VIEWER_OBSERVE_TIMEOUT_SECONDS)
        } finally {
            instrumentation.runOnMainSync { openedActivities.forEach(android.app.Activity::finish) }
            val deleted = database.deleteConversationSync(conversationId)
            assertTrue("Could not clean the temporary harness conversation", deleted is ChatDbResult.Success)
        }
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

    private fun slideCounter(index: Int, total: Int, spanish: Boolean): String =
        if (spanish) "Diapositiva $index de $total" else "Slide $index of $total"

    private fun awaitViewerState(webView: WebView, timeoutSeconds: Long, expectedCounter: String? = null): JSONObject {
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
                val rendered = last.optInt("imageWidth") > 0 && last.optInt("imageHeight") > 0 &&
                    (expectedCounter == null || last.optString("counter") == expectedCounter)
                val failed = last.has("status") && !last.isNull("status") &&
                    !last.optBoolean("statusHidden", true) && last.optString("status").isNotBlank()
                if (rendered || failed) return last
            }
            SystemClock.sleep(250)
        }
        return last
    }

    private fun awaitChartViewerState(webView: WebView, timeoutSeconds: Long, expectedTitle: String): JSONObject {
        val script = """JSON.stringify({
            canvasWidth: document.getElementById('chart') ? document.getElementById('chart').width : 0,
            canvasHeight: document.getElementById('chart') ? document.getElementById('chart').height : 0,
            plotHeight: document.querySelector('.plot') ? document.querySelector('.plot').getBoundingClientRect().height : 0,
            title: document.getElementById('title') ? document.getElementById('title').textContent : null,
            bodyHeight: document.body ? document.body.getBoundingClientRect().height : 0,
            paintedPixels: (() => {
                const canvas = document.getElementById('chart');
                if (!canvas || canvas.width === 0 || canvas.height === 0) return 0;
                const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
                let count = 0;
                for (let index = 3; index < data.length; index += 128) if (data[index] > 0) count++;
                return count;
            })()
        })"""
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000L
        var last = JSONObject()
        while (SystemClock.elapsedRealtime() < deadline) {
            val encoded = evaluateJavascript(instrumentation, webView, script)
            if (!encoded.isNullOrBlank() && encoded != "null") {
                last = parseJavascriptObject(encoded)
                if (last.optString("title") == expectedTitle && last.optInt("canvasWidth") > 0 &&
                    last.optInt("canvasHeight") > 0 && last.optDouble("plotHeight") > 0 &&
                    last.optInt("paintedPixels") > 0) {
                    return last
                }
            }
            SystemClock.sleep(250)
        }
        return last
    }

    private fun awaitViewerFallback(activity: android.app.Activity, timeoutSeconds: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val removed = java.util.concurrent.CountDownLatch(1)
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val decor = activity.window.decorView
                if (decor.findWebView() == null) {
                    if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    removed.countDown()
                }
            }
        }
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            if (decor.findWebView() == null) removed.countDown()
            else decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
        assertTrue(
            "Renderer failure did not remove the dead WebView from the host hierarchy",
            removed.await(timeoutSeconds, TimeUnit.SECONDS),
        )
        val expectedFallback = context.getString(R.string.module_artifact_viewer_renderer_failed)
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000L
        var fallbackVisible = false
        while (SystemClock.elapsedRealtime() < deadline && !fallbackVisible) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            fallbackVisible = root?.containsVisibleText(expectedFallback) == true
            root?.recycle()
            if (!fallbackVisible) SystemClock.sleep(100)
        }
        assertTrue("The host fallback message was not visible after renderer removal", fallbackVisible)
        instrumentation.waitForIdleSync()
        val fallbackDrawn = java.util.concurrent.CountDownLatch(1)
        val firstDraw = java.util.concurrent.atomic.AtomicBoolean(false)
        val observerReference = AtomicReference<ViewTreeObserver?>()
        val drawListener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (firstDraw.compareAndSet(false, true)) {
                    activity.window.decorView.postOnAnimation {
                        val observer = observerReference.get()
                        if (observer?.isAlive == true) observer.removeOnDrawListener(this)
                        fallbackDrawn.countDown()
                    }
                }
            }
        }
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            observerReference.set(decor.viewTreeObserver)
            decor.viewTreeObserver.addOnDrawListener(drawListener)
            decor.invalidate()
        }
        assertTrue("The host fallback did not draw after renderer removal", fallbackDrawn.await(timeoutSeconds, TimeUnit.SECONDS))
        captureUiIfRequested("renderer-fallback")
    }

    private fun AccessibilityNodeInfo.containsVisibleText(expected: String): Boolean {
        if (isVisibleToUser && text?.toString() == expected) return true
        for (index in 0 until childCount) {
            val child = getChild(index) ?: continue
            val matches = child.containsVisibleText(expected)
            child.recycle()
            if (matches) return true
        }
        return false
    }

    private fun captureUiIfRequested(
        name: String,
        webView: WebView? = null,
        renderedSlideState: JSONObject? = null,
        expectedSlideBackground: Int? = null,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (!InstrumentationRegistry.getArguments().getString("captureUi").equals("true", ignoreCase = true)) return
        val directory = context.cacheDir
        check(directory.isDirectory) { "The app cache directory is unavailable" }
        val deadline = SystemClock.elapsedRealtime() + VIEWER_OBSERVE_TIMEOUT_SECONDS * 1_000L
        var screenshot: Bitmap
        while (true) {
            if (webView != null) awaitWebViewVisualState(webView)
            screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (expectedSlideBackground == null ||
                screenshotShowsSlideBackground(screenshot, webView, renderedSlideState, expectedSlideBackground)) break
            screenshot.recycle()
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError("The captured screen did not include the rendered slide surface")
            }
        }
        val file = File(directory, "$name.png")
        try {
            file.outputStream().use { output ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode the UI capture" }
            }
        } finally {
            screenshot.recycle()
        }
        Log.i("ArtifactViewerHarness", "UI capture saved in test app storage: ${file.name}")
    }

    private fun screenshotShowsSlideBackground(
        screenshot: Bitmap,
        webView: WebView?,
        state: JSONObject?,
        expectedColor: Int,
    ): Boolean {
        val imageRect = state?.optJSONArray("imageRect") ?: return false
        val viewport = state.optJSONArray("viewport") ?: return false
        val view = webView ?: return false
        val location = IntArray(2)
        val geometry = AtomicReference(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.getLocationOnScreen(location)
            geometry.set(view.width > 0 && view.height > 0)
        }
        if (!geometry.get()) return false
        val scaleX = view.width.toFloat() / viewport.optDouble(0).toFloat()
        val scaleY = view.height.toFloat() / viewport.optDouble(1).toFloat()
        val x = location[0] + ((imageRect.optDouble(0) + imageRect.optDouble(2) * 0.05) * scaleX).toInt()
        val y = location[1] + ((imageRect.optDouble(1) + imageRect.optDouble(3) * 0.05) * scaleY).toInt()
        if (x !in 0 until screenshot.width || y !in 0 until screenshot.height) return false
        val actual = screenshot.getPixel(x, y)
        return abs(Color.red(actual) - Color.red(expectedColor)) <= 2 &&
            abs(Color.green(actual) - Color.green(expectedColor)) <= 2 &&
            abs(Color.blue(actual) - Color.blue(expectedColor)) <= 2
    }

    private fun awaitWebViewVisualState(webView: WebView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val visualReady = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    visualReady.countDown()
                }
            })
        }
        assertTrue(
            "WebView did not submit the rendered visual state",
            visualReady.await(VIEWER_OBSERVE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        val drawReady = java.util.concurrent.CountDownLatch(1)
        val drawReported = java.util.concurrent.atomic.AtomicBoolean(false)
        val listenerReference = AtomicReference<ViewTreeObserver.OnDrawListener?>()
        instrumentation.runOnMainSync {
            val listener = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (drawReported.compareAndSet(false, true)) {
                        webView.post {
                            val listenerToRemove = listenerReference.get()
                            if (listenerToRemove != null && webView.viewTreeObserver.isAlive) {
                                webView.viewTreeObserver.removeOnDrawListener(listenerToRemove)
                            }
                        }
                        drawReady.countDown()
                    }
                }
            }
            listenerReference.set(listener)
            webView.viewTreeObserver.addOnDrawListener(listener)
            webView.invalidate()
        }
        assertTrue(
            "WebView did not draw the submitted visual state",
            drawReady.await(VIEWER_OBSERVE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    private fun parseJavascriptObject(encoded: String): JSONObject = runCatching {
        val value = JSONTokener(encoded).nextValue()
        JSONObject(value as? String ?: encoded)
    }.getOrDefault(JSONObject().put("raw", encoded))

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
        var omitArtifactId = false

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
            return if (omitArtifactId) JSONObject() else JSONObject().put("id", "instrumented-artifact")
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
