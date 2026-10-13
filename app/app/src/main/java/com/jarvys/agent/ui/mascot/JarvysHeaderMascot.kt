package com.jarvys.agent.ui.mascot

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.RiveViewLifecycleObserver
import app.rive.runtime.kotlin.controllers.RiveFileController
import app.rive.runtime.kotlin.core.Alignment
import app.rive.runtime.kotlin.core.FileAsset
import app.rive.runtime.kotlin.core.FileAssetLoader
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.ImageAsset
import app.rive.runtime.kotlin.core.Loop
import app.rive.runtime.kotlin.core.PlayableInstance
import app.rive.runtime.kotlin.core.RendererType
import app.rive.runtime.kotlin.core.RefCount
import app.rive.runtime.kotlin.renderers.RenderContextEventType
import com.jarvys.agent.BotMascotPlaybackSupport
import com.jarvys.agent.JarvysVerifiedAnimation
import com.jarvys.agent.LocalDiagnostics
import com.jarvys.agent.LocalDiagnostics.AnimationStage
import com.jarvys.agent.LocalDiagnostics.AnimationStatus
import com.jarvys.agent.crew.rememberMascotResumed
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.motion.rememberMotionViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Main-header renderer reconstructed from APK95 contracts, not a claim of historical source identity. */
@Composable
internal fun JarvysHeaderMascot(
    modifier: Modifier = Modifier,
    failedRunId: String? = null,
    motionAllowed: Boolean = true,
    sadAsset: JarvysVerifiedAnimation? = null,
    requestedActivity: JarvysHeaderActivity? = null,
    activityKey: String? = null,
    activityAsset: JarvysVerifiedAnimation? = null,
    interruptedRunId: String? = null,
    interruptedAsset: JarvysVerifiedAnimation? = null,
    tauntEventId: String? = null,
    tauntAsset: JarvysVerifiedAnimation? = null,
    requestedAnimationEventId: String? = null,
    requestedAnimationId: String? = null,
    requestedAnimationAsset: JarvysVerifiedAnimation? = null,
    onRequestedAnimationFinished: (String) -> Unit = {},
    onNativePlaybackChanged: (Boolean) -> Unit = {},
) {
    val viewport = rememberMotionViewport()
    val resumed = rememberMascotResumed {}
    val reduced = LocalReducedMotion.current
    val eligible = jarvysHeaderMayAnimate(motionAllowed, resumed, viewport.visible, reduced)
    val oneShots = remember {
        JarvysHeaderOneShotController(JarvysHeaderPlaybackMemory.failures,
            JarvysHeaderPlaybackMemory.interruptions, JarvysHeaderTapPlaybackGate(tauntEventId),
            JarvysHeaderPlaybackMemory.requests)
    }
    var oneShotRequest by remember { mutableStateOf<JarvysHeaderOneShotRequest?>(null) }
    val oneShotInputs = JarvysHeaderOneShotInputs(eligible, failedRunId, sadAsset,
        interruptedRunId, interruptedAsset, tauntEventId, tauntAsset,
        requestedAnimationEventId, requestedAnimationId, requestedAnimationAsset)
    LaunchedEffect(oneShotInputs) { oneShotRequest = oneShots.update(oneShotInputs) }
    DisposableEffect(oneShots) { onDispose { oneShots.clear() } }
    val activeOneShot = oneShotRequest?.takeIf(oneShotInputs::isCurrent)
    var failedActivity by remember { mutableStateOf<JarvysHeaderActivityRequest?>(null) }
    val activeActivity = jarvysHeaderActivityRequest(requestedActivity, activityKey, activityAsset)
        ?.takeUnless { it == failedActivity }
    val timeline = jarvysHeaderSelectedTimeline(activeOneShot, activeActivity)
    val remoteAsset = activeOneShot?.asset ?: activeActivity?.asset
    val requestKey = activeOneShot?.let { it.kind to it.eventId } ?: activeActivity?.activityKey
    val latestNative by rememberUpdatedState(onNativePlaybackChanged)
    DisposableEffect(Unit) { onDispose { latestNative(false) } }
    Box(modifier.then(viewport.modifier).semantics {
        this[JarvysMascotSourceKey] = "principal:jarvys-mascot"
        this[JarvysMascotPresentationKey] = if (eligible) "original-animation" else "static"
    }) {
        if (eligible) {
            key(timeline, requestKey, jarvysHeaderAnimationIdentity(remoteAsset)) {
                HeaderTimeline(
                    timeline = timeline,
                    remoteAsset = remoteAsset,
                    onRemoteStopped = {
                        if (activeOneShot != null) {
                            oneShotRequest = oneShots.finish(activeOneShot)
                            if (activeOneShot.kind == JarvysHeaderOneShotKind.REQUESTED)
                                onRequestedAnimationFinished(activeOneShot.eventId)
                        }
                        else failedActivity = activeActivity
                    },
                    onNativePlaybackChanged = { latestNative(it) },
                )
            }
        } else {
            LaunchedEffect(Unit) { latestNative(false) }
            JarvysMascotIcon(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun HeaderTimeline(
    timeline: JarvysHeaderTimeline,
    remoteAsset: JarvysVerifiedAnimation?,
    onRemoteStopped: () -> Unit,
    onNativePlaybackChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ticket = remember { HeaderPlaybackTicket() }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    var drawn by remember { mutableStateOf(false) }
    val latestFinished by rememberUpdatedState(onRemoteStopped)
    val latestNative by rememberUpdatedState(onNativePlaybackChanged)
    fun current() = ticket.active && !failed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun fail() {
        if (!ticket.active || failed) return
        failed = true
        bytes = null
        drawn = false
        latestNative(false)
        if (timeline.remoteId != null || timeline.oneShot) latestFinished()
    }
    DisposableEffect(ticket) { onDispose { ticket.active = false; latestNative(false) } }
    LaunchedEffect(ticket) {
        var stage = AnimationStage.INTEGRITY
        try {
            val loaded = if (timeline.remoteId != null) {
                val asset = checkNotNull(remoteAsset)
                require(asset.animationId == timeline.remoteId)
                JarvysHeaderAnimationAssets.remote(context, asset)
            } else JarvysHeaderAnimationAssets.idle(context)
            if (!current()) return@LaunchedEffect
            headerDiagnostic(context, timeline, stage, AnimationStatus.READY)
            stage = AnimationStage.INIT
            headerDiagnostic(context, timeline, stage, AnimationStatus.STARTED)
            if (!BotMascotPlaybackSupport.initialize(context)) {
                headerDiagnostic(context, timeline, stage, AnimationStatus.FAILED)
                fail(); return@LaunchedEffect
            }
            headerDiagnostic(context, timeline, stage, AnimationStatus.READY)
            if (current()) bytes = loaded
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { headerDiagnostic(context, timeline, stage, AnimationStatus.FAILED); fail() }
        catch (_: LinkageError) { headerDiagnostic(context, timeline, stage, AnimationStatus.FAILED); fail() }
        catch (_: OutOfMemoryError) { headerDiagnostic(context, timeline, stage, AnimationStatus.FAILED); fail() }
    }
    LaunchedEffect(bytes) {
        if (bytes == null) return@LaunchedEffect
        delay(15_000)
        if (current() && !drawn) {
            headerDiagnostic(context, timeline, AnimationStage.TIMEOUT, AnimationStatus.FAILED)
            fail()
        }
    }
    val loaded = bytes
    if (loaded != null && !failed) AndroidView(
        modifier = Modifier.fillMaxSize().testTag("jarvys-header-rive"),
        factory = { androidContext ->
            try {
                HeaderRiveView(androidContext, owner, timeline,
                    onFrame = { if (current()) {
                        drawn = true; latestNative(true)
                        headerDiagnostic(context, timeline, AnimationStage.FIRST_FRAME, AnimationStatus.READY)
                    } },
                    onFinished = { if (current()) latestFinished() },
                    onFailure = { fail() },
                ).also { it.load(loaded) }
            } catch (_: Exception) { headerDiagnostic(context, timeline, AnimationStage.IMPORT, AnimationStatus.FAILED); fail(); View(androidContext) }
            catch (_: LinkageError) { headerDiagnostic(context, timeline, AnimationStage.IMPORT, AnimationStatus.FAILED); fail(); View(androidContext) }
            catch (_: OutOfMemoryError) { headerDiagnostic(context, timeline, AnimationStage.IMPORT, AnimationStatus.FAILED); fail(); View(androidContext) }
        },
        onRelease = { (it as? HeaderRiveView)?.releaseFromComposition() },
        update = {},
    )
    if (!drawn || failed) JarvysMascotIcon(Modifier.fillMaxSize())
}

private class HeaderPlaybackTicket(var active: Boolean = true)

/** Only original in-band images are handled. A failed callback never delegates to a CDN. */
private class HeaderEmbeddedAssetLoader(context: Context, private val timeline: JarvysHeaderTimeline) : FileAssetLoader() {
    private val appContext = context.applicationContext
    var failed = false
        private set
    var count = 0
        private set
    override fun loadContents(asset: FileAsset, inBandBytes: ByteArray): Boolean {
        count++
        val decoded = try { asset is ImageAsset && inBandBytes.isNotEmpty() && asset.decode(inBandBytes) }
        catch (_: Exception) { false }
        catch (_: LinkageError) { false }
        catch (_: OutOfMemoryError) { false }
        if (!decoded) {
            if (!failed) headerDiagnostic(appContext, timeline, AnimationStage.DECODE, AnimationStatus.FAILED)
            failed = true
        }
        return true
    }
}

private class HeaderRiveView private constructor(
    context: Context,
    private val owner: LifecycleOwner,
    private val timeline: JarvysHeaderTimeline,
    private val embedded: HeaderEmbeddedAssetLoader,
    private val onFrame: () -> Unit,
    private val onFinished: () -> Unit,
    private val onFailure: () -> Unit,
) : RiveAnimationView(Builder(context)
    .setRendererType(RendererType.Rive).setAutoplay(false).setAutoBind(false)
    .setShouldLoadCDNAssets(false).setAssetLoader(embedded).setTouchPassThrough(true)) {
    constructor(context: Context, owner: LifecycleOwner, timeline: JarvysHeaderTimeline,
        onFrame: () -> Unit, onFinished: () -> Unit, onFailure: () -> Unit,
    ) : this(context, owner, timeline, HeaderEmbeddedAssetLoader(context, timeline), onFrame, onFinished, onFailure)

    private var disposed = false
    private var releaseRequested = false
    @Volatile private var bindingReady = false
    private var frameSeen = false
    private var finished = false
    private var playingAnimationName: String? = timeline.animation
    private val listener = object : RiveFileController.Listener {
        override fun notifyPlay(animation: PlayableInstance) = Unit
        override fun notifyPause(animation: PlayableInstance) = Unit
        override fun notifyStop(animation: PlayableInstance) {
            if (bindingReady && timeline.oneShot && animation.name == playingAnimationName && !finished && !releaseRequested) {
                finished = true
                post { if (!releaseRequested) onFinished() }
            }
        }
        override fun notifyLoop(animation: PlayableInstance) = Unit
        override fun notifyStateChanged(stateMachineName: String, stateName: String) = Unit
        override fun notifyAdvance(elapsed: Float) = Unit
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; registerListener(listener) }

    override fun createObserver(): LifecycleObserver = HeaderViewLifecycleObserver(
        mutableListOf<RefCount>(controller).apply { rendererAttributes.assetLoader?.let(::add) },
    )

    fun load(bytes: ByteArray) {
        var stage = AnimationStage.IMPORT
        headerDiagnostic(context, timeline, stage, AnimationStatus.STARTED)
        try {
            val knownBinding = timeline.artboard != null && timeline.animation != null
            setRiveBytes(bytes = bytes, artboardName = timeline.artboard, animationName = timeline.animation,
                stateMachineName = null, autoplay = knownBinding, autoBind = false, fit = Fit.CONTAIN,
                alignment = Alignment.CENTER, loop = if (timeline.oneShot) Loop.ONESHOT else Loop.LOOP)
            stage = AnimationStage.DECODE
            check(!embedded.failed && embedded.count > 0) { "Embedded image decode failed" }
            headerDiagnostic(context, timeline, stage, AnimationStatus.READY)
            stage = AnimationStage.IMPORT
            if (!knownBinding) {
                val imported = checkNotNull(file)
                // Introspection artboards are File dependencies and must not be separately released.
                val metadata = (0 until imported.artboardCount).map { index ->
                    val artboard = imported.artboard(index)
                    JarvysHeaderArtboardMetadata(artboard.name, artboard.animationNames)
                }
                val binding = checkNotNull(jarvysHeaderDiscoverBinding(checkNotNull(timeline.remoteId), metadata)) {
                    "No unambiguous original timeline"
                }
                playingAnimationName = binding.animation
                setRiveFile(file = imported, artboardName = binding.artboard, animationName = binding.animation,
                    stateMachineName = null, autoplay = true, autoBind = false, fit = Fit.CONTAIN,
                    alignment = Alignment.CENTER, loop = if (timeline.oneShot) Loop.ONESHOT else Loop.LOOP)
            }
            bindingReady = true
            headerDiagnostic(context, timeline, stage, AnimationStatus.READY)
        } catch (failure: Throwable) {
            headerDiagnostic(context, timeline, stage, AnimationStatus.FAILED)
            releaseFromComposition()
            throw failure
        }
    }

    override fun onAttachedToWindow() {
        if (releaseRequested) return
        try {
            super.onAttachedToWindow()
            headerDiagnostic(context, timeline, AnimationStage.ATTACH, AnimationStatus.READY)
            artboardRenderer?.onRenderContextEvent = { event ->
                if (event.type == RenderContextEventType.Lost) post { if (!releaseRequested) {
                    headerDiagnostic(context, timeline, AnimationStage.CONTEXT_LOSS, AnimationStatus.UNAVAILABLE)
                    onFailure()
                } }
            }
        } catch (_: Exception) { attachFailed() }
        catch (_: LinkageError) { attachFailed() }
        catch (_: OutOfMemoryError) { attachFailed() }
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (releaseRequested) return
        try {
            super.onSurfaceTextureAvailable(surface, width, height)
            headerDiagnostic(context, timeline, AnimationStage.SURFACE, AnimationStatus.READY)
        }
        catch (_: Exception) { surfaceFailed() }
        catch (_: LinkageError) { surfaceFailed() }
        catch (_: OutOfMemoryError) { surfaceFailed() }
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (releaseRequested) return
        try {
            super.onSurfaceTextureUpdated(surface)
            if (!frameSeen && jarvysHeaderMayPublishFrame(bindingReady, releaseRequested)) { frameSeen = true; onFrame() }
        } catch (_: Exception) { headerDiagnostic(context, timeline, AnimationStage.SURFACE, AnimationStatus.FAILED); onFailure() }
        catch (_: LinkageError) { headerDiagnostic(context, timeline, AnimationStage.SURFACE, AnimationStatus.FAILED); onFailure() }
        catch (_: OutOfMemoryError) { headerDiagnostic(context, timeline, AnimationStage.SURFACE, AnimationStatus.FAILED); onFailure() }
    }
    private fun attachFailed() {
        headerDiagnostic(context, timeline, AnimationStage.ATTACH, AnimationStatus.FAILED)
        post { if (!releaseRequested) onFailure() }
    }
    private fun surfaceFailed() {
        headerDiagnostic(context, timeline, AnimationStage.SURFACE, AnimationStatus.FAILED)
        post { if (!releaseRequested) onFailure() }
    }
    fun releaseFromComposition() {
        if (releaseRequested) return
        releaseRequested = true
        headerBestEffortCleanup { unregisterListener(listener) }
        headerBestEffortCleanup { pause() }
        if (!isAttachedToWindow) disposeReferences()
    }
    override fun onDetachedFromWindow() {
        try { if (renderer != null) headerBestEffortCleanup { super.onDetachedFromWindow() } }
        finally { if (releaseRequested) disposeReferences() }
    }
    private fun disposeReferences() {
        if (disposed) return
        disposed = true
        headerBestEffortCleanup { controller.isActive = false }
        val observer = lifecycleObserver as? HeaderViewLifecycleObserver ?: return
        headerBestEffortCleanup { owner.lifecycle.removeObserver(observer) }
        headerBestEffortCleanup { (activity as? LifecycleOwner)?.lifecycle?.removeObserver(observer) }
        observer.onDestroy(owner)
    }
}

internal fun headerBestEffortCleanup(action: () -> Unit) {
    try { action() }
    catch (_: Exception) { }
    catch (_: LinkageError) { }
    catch (_: OutOfMemoryError) { }
}

internal class HeaderViewLifecycleObserver(dependencies: MutableList<RefCount>) : RiveViewLifecycleObserver(dependencies) {
    override fun onDestroy(owner: LifecycleOwner) {
        while (dependencies.isNotEmpty()) {
            val dependency = dependencies.removeAt(0)
            if (dependency is RiveFileController) headerBestEffortCleanup { dependency.isActive = false }
            headerBestEffortCleanup { dependency.release() }
        }
        // Calling super after the drain cannot release an uncertain native reference twice.
        headerBestEffortCleanup { super.onDestroy(owner) }
    }
}

/** Header animation observations are intentionally not persisted; playback callbacks stay inert. */
private fun headerDiagnostic(context: Context, timeline: JarvysHeaderTimeline, stage: AnimationStage, status: AnimationStatus) {
}
