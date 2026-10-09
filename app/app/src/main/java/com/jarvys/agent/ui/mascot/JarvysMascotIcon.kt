package com.jarvys.agent.ui.mascot

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rive.Result as RiveResult
import app.rive.RiveFileSource
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorkerOrNull
import com.jarvys.agent.BotMascotPlaybackSupport
import com.jarvys.agent.JarvysMascotAssets
import com.jarvys.agent.R
import com.jarvys.agent.crew.MascotNativeInstance
import com.jarvys.agent.crew.MascotPilotEvidence
import com.jarvys.agent.crew.MascotPilotPreparation
import com.jarvys.agent.crew.prepareMascotPilot
import com.jarvys.agent.crew.rememberMascotResumed
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.motion.rememberMotionViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal val JarvysMascotSourceKey = SemanticsPropertyKey<String>("JarvysMascotSource")
internal val JarvysMascotPresentationKey = SemanticsPropertyKey<String>("JarvysMascotPresentation")

/**
 * Shared original identity. Defaults to non-native artwork, including for every chief/captain.
 * This renderer never queries run state. Its caller must explicitly authorize this temporary view.
 */
@Composable
internal fun JarvysMascotIcon(
    modifier: Modifier = Modifier,
    mode: Int? = null,
    animationOptedIn: Boolean = false,
    onAnimationStopped: (failed: Boolean) -> Unit = {},
    isAnimationCurrent: () -> Boolean = { animationOptedIn },
) {
    // Even lifecycle/viewport bookkeeping is unnecessary on the permanent static identity path.
    if (!animationOptedIn || !jarvysMascotKnownLiveMode(mode)) {
        Box(modifier.semantics {
            this[JarvysMascotSourceKey] = "principal:jarvys-mascot"
            this[JarvysMascotPresentationKey] = "static"
        }) { JarvysMascotStatic(Modifier.fillMaxSize()) }
        return
    }
    val viewport = rememberMotionViewport()
    val latestStopped by rememberUpdatedState(onAnimationStopped)
    val latestCurrent by rememberUpdatedState(isAnimationCurrent)
    val resumed = rememberMascotResumed { latestStopped(false) }
    val eligible = jarvysMascotMayPrepare(mode, animationOptedIn, resumed, viewport.visible)
    Box(modifier.then(viewport.modifier).semantics {
        this[JarvysMascotSourceKey] = "principal:jarvys-mascot"
        this[JarvysMascotPresentationKey] = if (eligible) "experimental" else "static"
    }) {
        if (eligible) JarvysMascotLive(checkNotNull(mode),
            isCurrent = { eligible && latestCurrent() }, onStopped = { latestStopped(true) })
        else JarvysMascotStatic(Modifier.fillMaxSize())
    }
}

@Composable
private fun JarvysMascotStatic(modifier: Modifier) {
    Image(painterResource(R.drawable.jarvys_mascot_static), contentDescription = null,
        modifier = modifier.testTag("jarvys-mascot-static"), contentScale = ContentScale.Fit)
}

/** Removed from composition for unknown activity, covered UI, background or withdrawn consent. */
@Composable
private fun JarvysMascotLive(requested: Int, isCurrent: () -> Boolean, onStopped: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val reduced = LocalReducedMotion.current
    val latestStopped by rememberUpdatedState(onStopped)
    val latestCurrent by rememberUpdatedState(isCurrent)
    // A non-saveable per-composition ticket rejects an old verified load after removal/replacement.
    val ticket = remember { LiveMascotTicket() }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var evidence by remember { mutableStateOf(MascotPilotEvidence()) }
    var failed by remember { mutableStateOf(false) }
    fun current() = ticket.active && !failed && latestCurrent() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun stop() {
        if (!current()) return
        failed = true
        bytes = null
        latestStopped()
    }
    DisposableEffect(ticket) { onDispose { ticket.active = false } }
    LaunchedEffect(ticket) {
        try {
            when (val prepared = prepareMascotPilot(
                isCurrent = { current() },
                verifiedLoad = { JarvysMascotAssets.load(context) },
                // Shared once-per-process gate; only reached after consent + verified local bytes.
                initialize = { BotMascotPlaybackSupport.initialize(context) },
            )) {
                MascotPilotPreparation.Stale -> Unit
                MascotPilotPreparation.InitFailed -> stop()
                is MascotPilotPreparation.Ready -> bytes = prepared.bytes
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (current()) stop() }
    }
    LaunchedEffect(ticket, requested, reduced, bytes) {
        if (bytes == null || failed) return@LaunchedEffect
        delay(15_000)
        if (current() && (!evidence.pixelsAvailable || evidence.nativeMode != requested || evidence.nativeReduced != reduced)) stop()
    }
    val verifiedBytes = bytes
    if (verifiedBytes == null || failed) {
        JarvysMascotStatic(Modifier.fillMaxSize())
        return
    }
    val workerError = remember { mutableStateOf<Throwable?>(null) }
    val worker = rememberRiveWorkerOrNull(errorState = workerError)
    if (worker == null) {
        LaunchedEffect(Unit) { stop() }
        JarvysMascotStatic(Modifier.fillMaxSize())
        return
    }
    val source = remember(verifiedBytes) { RiveFileSource.Bytes(verifiedBytes) }
    when (val file = rememberRiveFile(source, worker)) {
        is RiveResult.Loading -> JarvysMascotStatic(Modifier.fillMaxSize())
        is RiveResult.Error -> {
            LaunchedEffect(file) { stop() }
            JarvysMascotStatic(Modifier.fillMaxSize())
        }
        is RiveResult.Success -> MascotNativeInstance(file.value, requested, reduced,
            modifier = Modifier.fillMaxSize().testTag("jarvys-mascot-native"),
            fallback = { JarvysMascotStatic(Modifier.fillMaxSize()) },
            onEvidence = { change -> if (current()) evidence = change(evidence) },
            onFailure = { if (current()) stop() },
        )
    }
}

private class LiveMascotTicket(var active: Boolean = true)
