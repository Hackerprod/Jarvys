package com.jarvys.agent.crew

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.rive.Fit
import app.rive.Result as RiveResult
import app.rive.Rive
import app.rive.RiveFile
import app.rive.RiveFileSource
import app.rive.RiveFrameRate
import app.rive.RivePointerInputMode
import app.rive.ViewModelSource
import app.rive.rememberArtboardResult
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorkerOrNull
import app.rive.rememberStateMachineResult
import app.rive.rememberViewModelInstanceResult
import com.jarvys.agent.BotMascotPlaybackSupport
import com.jarvys.agent.R
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.motion.rememberMotionViewport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * Add only to the saved custom-bot editor. It changes no catalog avatar or runtime activity.
 * Verification is non-native; there is no Rive worker/file/JNI call before an explicit Start.
 */
@Composable
internal fun BotMascotPilotEntry(bot: BotDefinition, isNew: Boolean, enabled: Boolean = true) {
    val descriptor = bot.mascot ?: return
    if (isNew || immutableCatalogBot(bot)) return
    key(bot.id, descriptor) {
        val context = LocalContext.current.applicationContext
        var verified by remember { mutableStateOf(false) }
        var verificationFailed by remember { mutableStateOf(false) }
        var open by remember { mutableStateOf(false) }
        LaunchedEffect(context, bot.id, descriptor) {
            verified = false
            verificationFailed = false
            try {
                // This trusted helper does bounded store validation/recompilation on Dispatchers.IO.
                // Discard probe bytes. Start below must perform a fresh read/verification.
                BotMascotPlaybackSupport.load(context, bot.id, descriptor)
                verified = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { verificationFailed = true }
        }
        if (verificationFailed) Text(stringResource(R.string.mascot_pilot_unavailable),
            Modifier.testTag("mascot-pilot-unavailable"), style = MaterialTheme.typography.bodySmall)
        if (verified) OutlinedButton(
            onClick = { open = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mascot-pilot-open"),
        ) { Text(stringResource(R.string.mascot_pilot_open)) }
        if (verified && open) Dialog(
            onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                MascotPilotScreen(
                    verifiedLoad = { BotMascotPlaybackSupport.load(context, bot.id, descriptor) },
                    fallback = { modifier -> BotIdentityIcon(BotIconIdentity(bot.id, bot.iconRef), modifier) },
                    isOpen = { open }, onClose = { open = false },
                )
            }
        }
    }
}

@Composable
internal fun MascotPilotScreen(
    verifiedLoad: suspend () -> ByteArray,
    fallback: @Composable (Modifier) -> Unit,
    isOpen: () -> Boolean,
    onClose: () -> Unit,
    titleRes: Int = R.string.mascot_pilot_title,
) {
    // Trusted call sites supply a verified loader and static identity; never fabricate a bot profile.
    // This subtree is keyed by identity at the entry. Never rememberSaveable.
    val context = LocalContext.current.applicationContext
    val latestVerifiedLoad by rememberUpdatedState(verifiedLoad)
    val clipboard = LocalClipboardManager.current
    val viewport = rememberMotionViewport()
    var session by remember { mutableStateOf(MascotPilotSession()) }
    val optedIn = session.optedIn
    var verifiedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var verifiedByteCount by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(MascotPilotPhase.STATIC) }
    var manualReduced by remember { mutableStateOf(true) }
    val reduced = mascotPilotEffectiveReduced(manualReduced, LocalReducedMotion.current)
    var controls by remember { mutableStateOf(MascotPilotControlsState()) }
    val requested = controls.modes()
    val evidence = remember { mutableStateListOf(MascotPilotEvidence(), MascotPilotEvidence()) }
    val manualChecks = remember { mutableStateListOf(emptySet<MascotPilotVisualCheck>(), emptySet()) }
    var copied by remember { mutableStateOf(false) }

    fun stop(reason: MascotPilotPhase) {
        session = session.stop()
        verifiedBytes = null
        phase = reason
        // Old surface availability/readback must not masquerade as a live renderer after Stop.
        repeat(2) { evidence[it] = MascotPilotEvidence() }
    }
    DisposableEffect(Unit) {
        onDispose { session = session.stop(); verifiedBytes = null }
    }
    val resumed = rememberMascotResumed {
        if (session.optedIn) stop(MascotPilotPhase.STOPPED_REQUIRES_START)
    }
    LaunchedEffect(viewport.visible) {
        if (!viewport.visible && session.optedIn) stop(MascotPilotPhase.STOPPED_REQUIRES_START)
    }
    val latestResumed by rememberUpdatedState(resumed)
    val latestVisible by rememberUpdatedState(viewport.visible)
    LaunchedEffect(session.generation, optedIn, resumed, viewport.visible) {
        if (!optedIn || !resumed || !viewport.visible) return@LaunchedEffect
        val ticket = session.generation
        try {
            phase = MascotPilotPhase.VERIFYING
            when (val prepared = prepareMascotPilot(
                isCurrent = { isOpen() && session.accepts(ticket, latestResumed, latestVisible) },
                verifiedLoad = { latestVerifiedLoad() },
                // UI/Main thread, after validated load; no suspension between final gate and init.
                initialize = { BotMascotPlaybackSupport.initialize(context) },
            )) {
                MascotPilotPreparation.Stale -> Unit
                MascotPilotPreparation.InitFailed -> stop(MascotPilotPhase.INIT_FAILED)
                is MascotPilotPreparation.Ready -> {
                    verifiedByteCount = prepared.bytes.size
                    verifiedBytes = prepared.bytes // Stable identity until Stop; never clone in composition.
                    phase = MascotPilotPhase.NATIVE_TEST
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (isOpen() && session.accepts(ticket, latestResumed, latestVisible)) stop(MascotPilotPhase.VERIFY_FAILED)
        }
    }
    val render = mascotPilotMayRender(optedIn, verifiedBytes != null, resumed, viewport.visible)
    LaunchedEffect(session.generation, render, requested, reduced) {
        if (!render) return@LaunchedEffect
        val ticket = session.generation
        delay(15_000)
        if (isOpen() && session.accepts(ticket, latestResumed, latestVisible) && evidence.indices.any { slot ->
                val item = evidence[slot]
                !item.pixelsAvailable || item.nativeMode != requested[slot].wire || item.nativeReduced != reduced
            }) {
            stop(MascotPilotPhase.READBACK_OR_FRAME_TIMEOUT)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().testTag("mascot-pilot-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(titleRes), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { stop(MascotPilotPhase.STOPPED_REQUIRES_START); onClose() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("mascot-pilot-close")) { Text(stringResource(R.string.mascot_pilot_close)) }
        }
        // Keep both previews on screen while scrolling through all 18 manual checks per instance.
        // Scrolling the controls must not hide and tear down the preview being inspected.
        Box(Modifier.fillMaxWidth().height(128.dp).padding(horizontal = 16.dp)
            .then(viewport.modifier).testTag("mascot-pilot-previews")) {
            if (render) {
                val renderEpoch = session.generation
                NativePilotPair(checkNotNull(verifiedBytes), requested.toList(), reduced, fallback,
                    onEvidence = { slot, change ->
                        if (isOpen() && session.accepts(renderEpoch, latestResumed, latestVisible) && phase == MascotPilotPhase.NATIVE_TEST) {
                            evidence[slot] = change(evidence[slot])
                        }
                    }, onFailure = { if (isOpen() && session.accepts(renderEpoch, latestResumed, latestVisible)) stop(it) })
            } else StaticPilotPair(fallback)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mascot_pilot_manual_notice))
            Text(stringResource(R.string.mascot_pilot_risk),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mascot_pilot_phase, phase.name), Modifier.testTag("mascot-pilot-phase"), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!optedIn && resumed && viewport.visible) {
                        copied = false
                        verifiedByteCount = 0
                        repeat(2) { evidence[it] = MascotPilotEvidence(); manualChecks[it] = emptySet() }
                        session = session.start(resumed, viewport.visible)
                    }
                }, enabled = !optedIn && resumed && viewport.visible,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mascot-pilot-start")) { Text(stringResource(R.string.mascot_pilot_start)) }
                OutlinedButton(onClick = { stop(MascotPilotPhase.STOPPED_REQUIRES_START) }, enabled = optedIn,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mascot-pilot-stop")) { Text(stringResource(R.string.mascot_pilot_stop)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mascot_pilot_reduce), Modifier.weight(1f))
                val reducedDescription = stringResource(R.string.mascot_pilot_reduce_accessibility)
                Switch(checked = manualReduced, onCheckedChange = { manualReduced = it },
                    modifier = Modifier.testTag("mascot-pilot-reduced").semantics { contentDescription = reducedDescription })
            }
            Text(stringResource(R.string.mascot_pilot_reduced_effective, reduced.toString()), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mascot_pilot_evidence_notice),
                style = MaterialTheme.typography.bodySmall)
            repeat(2) { slot ->
                PilotControls(slot, requested[slot], reduced, evidence[slot], manualChecks[slot],
                    onMode = { controls = controls.withMode(slot, it) },
                    onCheck = { manualChecks[slot] = manualChecks[slot] + MascotPilotVisualCheck(requested[slot], reduced) })
            }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(mascotPilotDiagnostics(Build.VERSION.SDK_INT, phase,
                    verifiedByteCount, requested.toList(), reduced, evidence.toList(), manualChecks.toList(),
                    mascotPilotAbis(Build.SUPPORTED_ABIS.asList()))))
                copied = true
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mascot-pilot-copy")) { Text(stringResource(R.string.mascot_pilot_copy)) }
            if (copied) Text(stringResource(R.string.mascot_pilot_copied))
            Text(stringResource(R.string.mascot_pilot_lifecycle_notice),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun rememberMascotResumed(onLeaveResumed: () -> Unit): Boolean {
    val owner = LocalLifecycleOwner.current
    val latestOnLeave by rememberUpdatedState(onLeaveResumed)
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { source, _ ->
            resumed = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) latestOnLeave()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

@Composable
private fun StaticPilotPair(fallback: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) { slot ->
            val description = stringResource(R.string.mascot_pilot_static_description, if (slot == 0) "A" else "B")
            Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description },
                contentAlignment = Alignment.Center) { fallback(Modifier.size(88.dp)) }
        }
    }
}

@Composable
private fun NativePilotPair(
    bytes: ByteArray,
    requested: List<MascotPilotMode>,
    reduced: Boolean,
    fallback: @Composable (Modifier) -> Unit,
    onEvidence: (Int, (MascotPilotEvidence) -> MascotPilotEvidence) -> Unit,
    onFailure: (MascotPilotPhase) -> Unit,
) {
    val workerError = remember { mutableStateOf<Throwable?>(null) }
    val worker = rememberRiveWorkerOrNull(errorState = workerError)
    if (worker == null) {
        PilotFailure(MascotPilotPhase.WORKER_FAILED, onFailure)
        StaticPilotPair(fallback)
        return
    }
    val source = remember(bytes) { RiveFileSource.Bytes(bytes) }
    when (val file = rememberRiveFile(source, worker)) {
        is RiveResult.Loading -> StaticPilotPair(fallback)
        is RiveResult.Error -> {
            PilotFailure(MascotPilotPhase.FILE_FAILED, onFailure)
            StaticPilotPair(fallback)
        }
        is RiveResult.Success -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { slot ->
                // Separate composition keys => independent Artboard, StateMachine and VMI instances.
                key(slot) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val description = stringResource(R.string.mascot_pilot_native_description,
                            if (slot == 0) "A" else "B", requested[slot].wireName)
                        MascotNativeInstance(file.value, requested[slot].wire, reduced,
                            modifier = Modifier.fillMaxSize().testTag("mascot-pilot-native-$slot")
                                .semantics { contentDescription = description },
                            fallback = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                fallback(Modifier.size(88.dp))
                            } },
                            onEvidence = { change -> onEvidence(slot, change) }, onFailure = onFailure)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MascotNativeInstance(
    file: RiveFile,
    requested: Int,
    reduced: Boolean,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
    onEvidence: ((MascotPilotEvidence) -> MascotPilotEvidence) -> Unit,
    onFailure: (MascotPilotPhase) -> Unit,
) {
    if (requested !in 0..8) {
        PilotFailure(MascotPilotPhase.WRITE_FAILED, onFailure)
        fallback()
        return
    }
    val artboardResult = rememberArtboardResult(file, BotMascotDescriptor.ARTBOARD)
    val source = remember { ViewModelSource.Named("MascotState").defaultInstance() }
    val vmiResult = rememberViewModelInstanceResult(file, source)
    val artboard = when (artboardResult) {
        is RiveResult.Loading -> null
        is RiveResult.Error -> { PilotFailure(MascotPilotPhase.ARTBOARD_FAILED, onFailure); null }
        is RiveResult.Success -> artboardResult.value
    }
    val stateMachineResult = if (artboard == null) null else rememberStateMachineResult(artboard, BotMascotDescriptor.STATE_MACHINE)
    val stateMachine = when (stateMachineResult) {
        null, is RiveResult.Loading -> null
        is RiveResult.Error -> { PilotFailure(MascotPilotPhase.STATE_MACHINE_FAILED, onFailure); null }
        is RiveResult.Success -> stateMachineResult.value
    }
    val vmi = when (vmiResult) {
        is RiveResult.Loading -> null
        is RiveResult.Error -> { PilotFailure(MascotPilotPhase.VIEW_MODEL_FAILED, onFailure); null }
        is RiveResult.Success -> vmiResult.value
    }
    if (artboard == null || stateMachine == null || vmi == null) {
        fallback()
        return
    }
    val latestFailure by rememberUpdatedState(onFailure)
    val latestEvidence by rememberUpdatedState(onEvidence)
    LaunchedEffect(vmi) {
        try {
            vmi.getNumberFlow("mode").collect { raw ->
                val value = mascotPilotNativeMode(raw)
                if (value == null) latestFailure(MascotPilotPhase.READBACK_FAILED)
                else latestEvidence { it.copy(nativeMode = value) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { latestFailure(MascotPilotPhase.READBACK_FAILED) }
    }
    LaunchedEffect(vmi) {
        try {
            vmi.getBooleanFlow("reducedMotion").collect { value -> latestEvidence { it.copy(nativeReduced = value) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { latestFailure(MascotPilotPhase.READBACK_FAILED) }
    }
    LaunchedEffect(vmi, requested, reduced) {
        try {
            vmi.setBoolean("reducedMotion", reduced)
            vmi.setNumber("mode", requested.toFloat())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { latestFailure(MascotPilotPhase.WRITE_FAILED) }
    }
    Rive(
        file = file,
        modifier = modifier,
        artboard = artboard,
        stateMachine = stateMachine,
        viewModelInstance = vmi,
        playing = !reduced,
        frameRate = RiveFrameRate.Capped(30f),
        pointerInputMode = RivePointerInputMode.Observe,
        fit = Fit.Contain(),
        onBitmapAvailable = { _ -> latestEvidence { it.copy(pixelsAvailable = true) } },
    )
}

@Composable
private fun PilotFailure(reason: MascotPilotPhase, onFailure: (MascotPilotPhase) -> Unit) {
    val latestFailure by rememberUpdatedState(onFailure)
    LaunchedEffect(reason) { latestFailure(reason) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PilotControls(
    slot: Int,
    requested: MascotPilotMode,
    reduced: Boolean,
    evidence: MascotPilotEvidence,
    checks: Set<MascotPilotVisualCheck>,
    onMode: (MascotPilotMode) -> Unit,
    onCheck: () -> Unit,
) {
    val label = if (slot == 0) "A" else "B"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.mascot_pilot_instance, label), style = MaterialTheme.typography.titleMedium)
        val pending = stringResource(R.string.mascot_pilot_pending)
        Text(stringResource(R.string.mascot_pilot_readback, requested.wireName, requested.wire,
            evidence.nativeMode?.toString() ?: pending, evidence.nativeReduced?.toString() ?: pending),
            Modifier.testTag("mascot-pilot-readback-$slot"), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.mascot_pilot_pixels_checks, evidence.pixelsAvailable.toString(), checks.size),
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MascotPilotMode.entries.forEach { mode ->
                FilterChip(selected = requested == mode, onClick = { onMode(mode) },
                    label = { Text(mode.wireName) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mascot-pilot-mode-$slot-${mode.wire}"))
            }
        }
        OutlinedButton(onClick = onCheck,
            enabled = evidence.pixelsAvailable && evidence.nativeMode == requested.wire && evidence.nativeReduced == reduced,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mascot-pilot-check-$slot")) {
            Text(stringResource(if (MascotPilotVisualCheck(requested, reduced) in checks)
                R.string.mascot_pilot_checked else R.string.mascot_pilot_mark_check))
        }
    }
}
