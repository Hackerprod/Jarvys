package com.jarvys.agent.crew

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Manual visual inputs only. These values are not an agent/run-state projection. */
internal enum class MascotPilotMode(val wire: Int, val wireName: String) {
    IDLE(0, "Idle"), THINKING(1, "Thinking"), WORKING(2, "Working"), QUEUED(3, "Queued"),
    WAITING_PROVIDER(4, "WaitingProvider"), WAITING_USER(5, "WaitingUser"), DONE(6, "Done"),
    ERROR(7, "Error"), INTERRUPTED(8, "Interrupted");
}

internal enum class MascotPilotPhase {
    STATIC, VERIFYING, NATIVE_TEST, STOPPED_REQUIRES_START, VERIFY_FAILED, INIT_FAILED,
    WORKER_FAILED, FILE_FAILED, ARTBOARD_FAILED, STATE_MACHINE_FAILED, VIEW_MODEL_FAILED,
    WRITE_FAILED, READBACK_FAILED, READBACK_OR_FRAME_TIMEOUT,
}

/** Ephemeral ticket policy. Construct afresh on close/reopen, identity replacement and recreation. */
internal data class MascotPilotSession(val generation: Long = 0, val optedIn: Boolean = false) {
    fun start(resumed: Boolean, visible: Boolean): MascotPilotSession =
        if (optedIn || !resumed || !visible) this else copy(generation = generation + 1, optedIn = true)
    fun stop(): MascotPilotSession = copy(generation = generation + 1, optedIn = false)
    fun accepts(ticket: Long, resumed: Boolean, visible: Boolean): Boolean =
        optedIn && generation == ticket && resumed && visible
}

internal data class MascotPilotControlsState(
    val a: MascotPilotMode = MascotPilotMode.IDLE,
    val b: MascotPilotMode = MascotPilotMode.WORKING,
) {
    fun modes(): List<MascotPilotMode> = listOf(a, b)
    fun withMode(slot: Int, mode: MascotPilotMode): MascotPilotControlsState = when (slot) {
        0 -> copy(a = mode)
        1 -> copy(b = mode)
        else -> throw IllegalArgumentException("Invalid visual-test slot")
    }
}

internal sealed interface MascotPilotPreparation {
    data class Ready(val bytes: ByteArray) : MascotPilotPreparation
    object Stale : MascotPilotPreparation
    object InitFailed : MascotPilotPreparation
}

/** Trusted internal seam: only the production loader can supply bytes at the actual UI call site. */
internal suspend fun prepareMascotPilot(
    isCurrent: () -> Boolean,
    verifiedLoad: suspend () -> ByteArray,
    initialize: () -> Boolean,
): MascotPilotPreparation {
    if (!isCurrent()) return MascotPilotPreparation.Stale
    val bytes = verifiedLoad() // A rejected/throwing package never reaches initialize.
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return MascotPilotPreparation.Stale
    if (!initialize()) return MascotPilotPreparation.InitFailed
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return MascotPilotPreparation.Stale
    return MascotPilotPreparation.Ready(bytes)
}

internal data class MascotPilotEvidence(
    val nativeMode: Int? = null,
    val nativeReduced: Boolean? = null,
    val pixelsAvailable: Boolean = false,
)

internal data class MascotPilotVisualCheck(val mode: MascotPilotMode, val reduced: Boolean)

internal fun mascotPilotMayRender(optedIn: Boolean, verifiedBytes: Boolean, resumed: Boolean, visible: Boolean) =
    optedIn && verifiedBytes && resumed && visible

internal fun mascotPilotEffectiveReduced(manual: Boolean, system: Boolean) = manual || system

/** Reject bad native values instead of rounding/clamping them into a successful mode. */
internal fun mascotPilotNativeMode(value: Float): Int? =
    value.takeIf { it.isFinite() && it >= 0f && it <= 8f && it == it.toInt().toFloat() }?.toInt()

internal enum class MascotPilotAbi(val wireName: String) {
    ARM64("arm64-v8a"), ARMV7("armeabi-v7a"), X86_64("x86_64"), X86("x86"), OTHER("other"),
}

internal fun mascotPilotAbis(values: List<String>): Set<MascotPilotAbi> = values.map { value ->
    MascotPilotAbi.entries.firstOrNull { it.wireName == value } ?: MascotPilotAbi.OTHER
}.toSet().ifEmpty { setOf(MascotPilotAbi.OTHER) }

/** No string supplied by a bot, file, path, exception, or conversation enters this export. */
internal fun mascotPilotDiagnostics(
    apiLevel: Int,
    phase: MascotPilotPhase,
    byteCount: Int,
    requested: List<MascotPilotMode>,
    reduced: Boolean,
    evidence: List<MascotPilotEvidence>,
    manualChecks: List<Set<MascotPilotVisualCheck>>,
    abis: Set<MascotPilotAbi> = setOf(MascotPilotAbi.OTHER),
): String = buildString {
    appendLine("schema=ux40-manual-v1")
    appendLine("purpose=VISUAL_TEST_ONLY")
    appendLine("ux40Complete=false")
    appendLine("runtime=rive-android-11.14.1")
    appendLine("contract=bot-mascot-v1")
    appendLine("renderer=Rive; frameRateCap=30; pointer=Observe; fit=Contain")
    appendLine("apiLevel=${apiLevel.coerceIn(1, 100)}")
    appendLine("abis=" + abis.map { it.wireName }.sorted().joinToString(","))
    appendLine("phase=${phase.name}")
    appendLine("verifiedRivBytes=${byteCount.coerceIn(0, 65536)}")
    appendLine("effectiveReducedMotion=$reduced")
    appendLine("activeStateObserver=UNAVAILABLE")
    appendLine("pixelsMeaning=surface_available_not_per_mode_or_visual_correctness")
    repeat(2) { index ->
        val slot = if (index == 0) "A" else "B"
        val request = requested.getOrNull(index) ?: MascotPilotMode.IDLE
        val item = evidence.getOrNull(index) ?: MascotPilotEvidence()
        appendLine("$slot.requestedMode=${request.wire}:${request.wireName}")
        appendLine("$slot.nativeVmiMode=${item.nativeMode?.takeIf { it in 0..8 } ?: "unknown"}")
        appendLine("$slot.nativeVmiReduced=${item.nativeReduced ?: "unknown"}")
        appendLine("$slot.pixelsAvailable=${item.pixelsAvailable}")
        appendLine("$slot.manualVisualChecks=" + manualChecks.getOrNull(index).orEmpty()
            .sortedWith(compareBy<MascotPilotVisualCheck> { it.mode.wire }.thenBy { it.reduced })
            .joinToString(",") { "${it.mode.wire}:${if (it.reduced) "reduced" else "normal"}" })
    }
}
