package com.jarvys.agent.canvas

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.PresentationArtifact
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.ui.chat.ConversationTimeline
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import kotlin.math.ceil

private const val SYNTHETIC_MESSAGE_COUNT = 5_000
internal const val CHAT_CANVAS_BENCHMARK_SCROLL_VIEWPORT_FRACTION = 0.75f

private data class ComposeCanvasMeasurements(
    val firstPaintMs: Double,
    val meanFrameMs: Double?,
    val p95FrameMs: Double?,
    val visibleItems: Int,
    val processPssKb: Int,
    val streamDeltaMs: Double,
    val paginationPaintMs: Double,
    val anchorPreserved: Boolean,
)

private data class ProcessMemory(val processPssKb: Int)

/** Choreographer samples actual display frames during the programmatic Compose scroll. */
private class CanvasFrameSampler {
    private val choreographer = Choreographer.getInstance()
    private val gaps = ArrayList<Double>()
    private var active = false
    private var lastFrameNanos = 0L
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            if (lastFrameNanos != 0L) gaps += (frameTimeNanos - lastFrameNanos) / 1_000_000.0
            lastFrameNanos = frameTimeNanos
            choreographer.postFrameCallback(this)
        }
    }

    fun start() {
        active = true
        gaps.clear()
        lastFrameNanos = 0L
        choreographer.postFrameCallback(callback)
    }

    fun stop(): Pair<Double?, Double?> {
        active = false
        choreographer.removeFrameCallback(callback)
        val sorted = gaps.sorted()
        val mean = if (sorted.isEmpty()) null else sorted.average()
        val p95 = if (sorted.isEmpty()) null else sorted[ceil(sorted.size * .95).toInt().coerceAtLeast(1) - 1]
        return mean to p95
    }
}

/** In-memory comparison screen. It never inserts the synthetic conversation into Room or UI state. */
@Composable
internal fun ChatCanvasBenchmarkScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val events = remember { syntheticConversation() }
    var timelineEvents by remember { mutableStateOf(events) }
    var phase by remember { mutableStateOf(BenchmarkPhase.IDLE) }
    var report by remember { mutableStateOf("Pulsa Ejecutar benchmark para medir este dispositivo.") }
    var composeStartNanos by remember { mutableLongStateOf(0L) }
    var firstDrawNanos by remember { mutableLongStateOf(0L) }
    var canvasReport by remember { mutableStateOf<JSONObject?>(null) }
    var canvasFirstPaintMs by remember { mutableStateOf<Double?>(null) }
    var canvasStartedAtNanos by remember { mutableLongStateOf(0L) }
    var composeMetrics by remember { mutableStateOf<ComposeCanvasMeasurements?>(null) }
    var visibleCount by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val frameSampler = remember { CanvasFrameSampler() }
    var firstDraw by remember { mutableStateOf(CompletableDeferred<Long>()) }

    LaunchedEffect(phase) {
        if (phase != BenchmarkPhase.COMPOSE) return@LaunchedEffect
        val first = firstDraw.await()
        val firstPaintMs = (first - composeStartNanos) / 1_000_000.0
        listState.scrollToItem(0)
        withFrameNanos { }
        frameSampler.start()
        val scrollStepPx = listState.layoutInfo.viewportSize.height * CHAT_CANVAS_BENCHMARK_SCROLL_VIEWPORT_FRACTION
        while (true) {
            withFrameNanos { }
            if (listState.scrollBy(scrollStepPx) <= 0f) break
        }
        val (meanFrameMs, p95FrameMs) = frameSampler.stop()
        val streamStart = SystemClock.elapsedRealtimeNanos()
        val latestAssistantIndex = timelineEvents.indexOfLast { it.kind == "assistant" }
        if (latestAssistantIndex >= 0) {
            timelineEvents = timelineEvents.toMutableList().also { rows ->
                val current = rows[latestAssistantIndex]
                rows[latestAssistantIndex] = current.copy(text = current.text + "\n\nDelta de benchmark.")
            }
        }
        withFrameNanos { }
        val streamDeltaMs = (SystemClock.elapsedRealtimeNanos() - streamStart) / 1_000_000.0
        val oldAnchorInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val anchorKey = oldAnchorInfo?.key
        val anchorOffset = oldAnchorInfo?.offset
        val pageSize = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val older = (0 until pageSize).map { index -> syntheticMessage(-pageSize + index, old = true) }
        val pagingStart = SystemClock.elapsedRealtimeNanos()
        timelineEvents = older + timelineEvents
        withFrameNanos { }
        val paginationPaintMs = (SystemClock.elapsedRealtimeNanos() - pagingStart) / 1_000_000.0
        val afterAnchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchorKey }
        val anchorPreserved = anchorKey == null || (afterAnchor != null && afterAnchor.offset == anchorOffset)
        val memory = processMemory()
        visibleCount = listState.layoutInfo.visibleItemsInfo.size
        composeMetrics = ComposeCanvasMeasurements(
            firstPaintMs, meanFrameMs, p95FrameMs, visibleCount,
            memory.processPssKb, streamDeltaMs, paginationPaintMs, anchorPreserved,
        )
        canvasStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        phase = BenchmarkPhase.CANVAS
    }

    val modifier = Modifier.fillMaxSize()
    when (phase) {
        BenchmarkPhase.IDLE, BenchmarkPhase.DONE -> Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Lienzo de chat · benchmark", style = MaterialTheme.typography.titleLarge)
            Text("Genera 5 000 eventos variados en memoria (sin escribir en Room ni alterar el historial). Compara la lista Compose actual con el lienzo en este mismo dispositivo.")
            Text(report, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    timelineEvents = events
                    canvasReport = null
                    canvasFirstPaintMs = null
                    composeMetrics = null
                    firstDrawNanos = 0L
                    firstDraw = CompletableDeferred()
                    composeStartNanos = SystemClock.elapsedRealtimeNanos()
                    phase = BenchmarkPhase.COMPOSE
                }) { Text(if (phase == BenchmarkPhase.DONE) "Ejecutar de nuevo" else "Ejecutar benchmark") }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Lienzo de chat benchmark", report))
                }) { Text("Copiar informe") }
                OutlinedButton(onClick = onClose) { Text("Cerrar") }
            }
        }
        BenchmarkPhase.COMPOSE -> {
            val drawModifier = Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (firstDrawNanos == 0L) Handler(Looper.getMainLooper()).post {
                    if (firstDrawNanos == 0L) {
                        firstDrawNanos = SystemClock.elapsedRealtimeNanos()
                        firstDraw.complete(firstDrawNanos)
                    }
                }
            }
            ConversationTimeline(
                conversationKey = "chat-canvas-benchmark",
                events = timelineEvents,
                isRunning = false,
                connectorRegistry = ConnectorRegistry.get(context),
                onOpenPreview = {},
                onOpenSkillFile = {},
                chatWithoutMemory = false,
                onLoadOlderHistory = {},
                hasOlderHistory = false,
                benchmarkListState = listState,
                modifier = drawModifier,
                onSaveGeneratedImage = {},
                onShareGeneratedImage = {},
            )
        }
        BenchmarkPhase.CANVAS -> ChatCanvasTimeline(
            sessionId = "chat-canvas-benchmark-memory-only",
            events = timelineEvents,
            running = false,
            allowRetry = false,
            hasOlder = false,
            loadingOlder = false,
            benchmarkMode = true,
            benchmarkStartedAtNanos = canvasStartedAtNanos,
            callbacks = ChatCanvasCallbacks(benchmark = { raw ->
                canvasReport = runCatching { JSONObject(raw) }.getOrNull()
                val compose = composeMetrics
                val canvas = canvasReport
                val memory = processMemory()
                report = buildReport(compose, canvas, canvasFirstPaintMs, memory)
                phase = BenchmarkPhase.DONE
            }, benchmarkFirstPaint = { canvasFirstPaintMs = it }),
            modifier = modifier,
        )
    }
}

private enum class BenchmarkPhase { IDLE, COMPOSE, CANVAS, DONE }

private fun syntheticConversation(): List<AgentRunUiEvent> =
    (0 until SYNTHETIC_MESSAGE_COUNT).map { syntheticMessage(it + 1) }

private fun syntheticMessage(seq: Int, old: Boolean = false): AgentRunUiEvent {
    val id = seq.toLong()
    val prefix = if (old) "Mensaje anterior" else "Mensaje"
    return when (Math.floorMod(seq, 13)) {
        2 -> AgentRunUiEvent.toolEvent(id, "tool_result", "crear_grafica", "Tarjeta de herramienta sintética · $seq", "benchmark-$seq", null, id)
        5 -> AgentRunUiEvent.artifactEvent(id, PresentationArtifact(
            id = "benchmark:$seq", artifactType = "image/png", moduleId = "benchmark", name = "grafica-$seq.png",
            title = "Artefacto sintético $seq", mimeType = "image/png", sizeBytes = 0, relativePath = null,
            sha256 = null, manifestJson = null, thumbnailRef = "benchmark", storageKind = "DELIVERED",
        ), id).copy(generatedImageStatus = "SYNTHETIC")
        8 -> AgentRunUiEvent.messageEvent(id, "assistant",
            "$prefix $seq\n\n```kotlin\nval seq = $seq\nprintln(seq)\n```", id).copy(messageId = "benchmark-$seq")
        11 -> AgentRunUiEvent.messageEvent(id, "assistant", "$prefix $seq · imagen sintética", id)
            .copy(messageId = "benchmark-$seq", generatedImageStatus = "SYNTHETIC")
        else -> AgentRunUiEvent.messageEvent(id, if (seq % 2 == 0) "user" else "assistant",
            "$prefix $seq. Texto **Markdown** con `código` y contenido para medir el scroll de una conversación larga.", id)
            .copy(messageId = "benchmark-$seq")
    }
}

private fun processMemory(): ProcessMemory {
    val ownInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(ownInfo)
    return ProcessMemory(ownInfo.totalPss)
}

private fun buildReport(
    compose: ComposeCanvasMeasurements?,
    canvas: JSONObject?,
    canvasFirstPaintMs: Double?,
    memory: ProcessMemory,
): String {
    if (compose == null || canvas == null) return "Benchmark incompleto: faltan mediciones de Compose o lienzo."
    val composePss = ChatCanvasMemoryPolicy.composeTotalPssKb(compose.processPssKb)
    val comparisons = listOf(
        gate("primer pintado", canvasFirstPaintMs?.let { it <= compose.firstPaintMs }),
        gate("scroll media", canvas.optDoubleOrNull("meanFrameMs")?.let { mean -> compose.meanFrameMs?.let { mean <= it } }),
        gate("scroll p95", canvas.optDoubleOrNull("p95FrameMs")?.let { p95 -> compose.p95FrameMs?.let { p95 <= it } }),
        gate("virtualización 5 000", canvas.optInt("loadedMessages") >= SYNTHETIC_MESSAGE_COUNT &&
            canvas.optInt("virtualizedNodes") < canvas.optInt("loadedMessages")),
        gate("delta de streaming", canvas.optDoubleOrNull("streamDeltaPaintMs")?.let { it <= compose.streamDeltaMs }),
        gate("paginación y ancla", canvas.optDoubleOrNull("pagingPaintMs")?.let { it <= compose.paginationPaintMs }
            ?.and(canvas.optBoolean("anchorPreserved"))),
    )
    return buildString {
        appendLine("Benchmark lienzo de chat · carga sintética en memoria · ${SYNTHETIC_MESSAGE_COUNT} mensajes")
        appendLine("Compose: primer pintado ${compose.firstPaintMs.fmt()} ms; frames media/p95 ${compose.meanFrameMs.fmt()} / ${compose.p95FrameMs.fmt()} ms; delta ${compose.streamDeltaMs.fmt()} ms; página ${compose.paginationPaintMs.fmt()} ms; ancla ${compose.anchorPreserved}; visibles ${compose.visibleItems}; proceso ${compose.processPssKb} KB; renderer 0 KB (sin WebView durante Compose); total ${composePss} KB.")
        appendLine("Lienzo: primer pintado ${canvasFirstPaintMs.fmt()} ms; frames media/p95 ${canvas.optDoubleOrNull("meanFrameMs").fmt()} / ${canvas.optDoubleOrNull("p95FrameMs").fmt()} ms; delta ${canvas.optDoubleOrNull("streamDeltaPaintMs").fmt()} ms; página ${canvas.optDoubleOrNull("pagingPaintMs").fmt()} ms; ancla ${canvas.optBoolean("anchorPreserved")}; DOM visible ${canvas.optInt("virtualizedNodes")}; mensajes cargados ${canvas.optInt("loadedMessages")}; proceso ${memory.processPssKb} KB; renderer NO MEDIDO — medir con adb shell dumpsys meminfo; PSS total no medido.")
        appendLine("Memoria: repetir el benchmark antes de 5 min puede devolver datos de memoria antiguos.")
        comparisons.forEach { appendLine("${it.first}: ${it.second} (umbral = medición Compose de esta ejecución).") }
        append("Selección/copia real y TalkBack: PENDIENTE de prueba manual en móvil.")
    }
}

private fun gate(name: String, passed: Boolean?): Pair<String, String> = name to when (passed) {
    true -> "PASA"
    false -> "NO PASA"
    null -> "NO MEDIDO"
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (!has(name) || isNull(name)) null else optDouble(name).takeIf(Double::isFinite)

private fun Double?.fmt(): String = this?.let { "%.1f".format(it) } ?: "n/d"
