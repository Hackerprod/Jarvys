package com.jarvys.agent.ui.mascot

import com.jarvys.agent.JarvysVerifiedAnimation
import java.util.Locale

/** Known original names; null export names require unambiguous native-file introspection. */
internal enum class JarvysHeaderTimeline(val artboard: String?, val animation: String?, val oneShot: Boolean, val remoteId: String? = null) {
    IDLE("IdleMascot", "Idle", false),
    SAD("SadMascot", "Sadness", true, "sad"),
    THINKING("Thinking", "thinking", false, "thinking"),
    WORKING("WorkingMascot", "Working", false, "working"),
    WAITING(null, null, false, "waiting"),
    INTERRUPTED(null, null, true, "interrupted"),
    TAUNT(null, null, true, "taunt"),
}

internal fun jarvysHeaderMayAnimate(allowed: Boolean, resumed: Boolean, visible: Boolean, reduced: Boolean): Boolean =
    allowed && resumed && visible && !reduced

/** Process-only terminal deduplication fails closed at its cap, never evicting a consumed event. */
internal class JarvysFailurePlaybackGate(private val maximum: Int = 256) {
    private val consumed = HashSet<String>()
    init { require(maximum > 0) }
    @Synchronized fun consume(runId: String?): Boolean {
        if (runId.isNullOrBlank() || runId.length > 256 || consumed.size >= maximum) return false
        return consumed.add(runId)
    }
}

internal object JarvysHeaderPlaybackMemory {
    val failures = JarvysFailurePlaybackGate()
    val interruptions = JarvysFailurePlaybackGate()
}

internal enum class JarvysHeaderActivity { WORKING, THINKING, WAITING }

internal fun jarvysHeaderActivityTimeline(activity: JarvysHeaderActivity?): JarvysHeaderTimeline? = when (activity) {
    JarvysHeaderActivity.THINKING -> JarvysHeaderTimeline.THINKING
    JarvysHeaderActivity.WORKING -> JarvysHeaderTimeline.WORKING
    JarvysHeaderActivity.WAITING -> JarvysHeaderTimeline.WAITING
    null -> null
}

internal data class JarvysHeaderAnimationIdentity(val packageId: String, val animationId: String, val bytes: Long, val sha256: String)
internal fun jarvysHeaderAnimationIdentity(asset: JarvysVerifiedAnimation?): JarvysHeaderAnimationIdentity? = asset?.let {
    JarvysHeaderAnimationIdentity(it.packageId, it.animationId, it.bytes, it.sha256)
}
internal fun jarvysHeaderSameAnimation(first: JarvysVerifiedAnimation?, second: JarvysVerifiedAnimation?): Boolean =
    first != null && second != null && jarvysHeaderAnimationIdentity(first) == jarvysHeaderAnimationIdentity(second)

internal data class JarvysHeaderActivityRequest(val timeline: JarvysHeaderTimeline, val activityKey: String, val asset: JarvysVerifiedAnimation)
internal fun jarvysHeaderActivityRequest(activity: JarvysHeaderActivity?, key: String?, asset: JarvysVerifiedAnimation?): JarvysHeaderActivityRequest? {
    val timeline = jarvysHeaderActivityTimeline(activity) ?: return null
    if (key.isNullOrBlank() || asset == null || asset.animationId != timeline.remoteId) return null
    return JarvysHeaderActivityRequest(timeline, key, asset)
}

internal fun jarvysHeaderClaimSad(runId: String?, eligible: Boolean, asset: JarvysVerifiedAnimation?, gate: JarvysFailurePlaybackGate): JarvysVerifiedAnimation? {
    val fresh = gate.consume(runId)
    return asset?.takeIf { fresh && eligible && it.animationId == "sad" }
}

internal enum class JarvysHeaderOneShotKind(val animationId: String) { SAD("sad"), INTERRUPTED("interrupted"), TAUNT("taunt") }
internal fun jarvysHeaderOneShotTimeline(kind: JarvysHeaderOneShotKind): JarvysHeaderTimeline = when (kind) {
    JarvysHeaderOneShotKind.SAD -> JarvysHeaderTimeline.SAD
    JarvysHeaderOneShotKind.INTERRUPTED -> JarvysHeaderTimeline.INTERRUPTED
    JarvysHeaderOneShotKind.TAUNT -> JarvysHeaderTimeline.TAUNT
}

/** Monotonic caller tokens per mounted header; initial token is consumed to prevent remount replay. */
internal class JarvysHeaderTapPlaybackGate(initialEventId: String? = null) {
    private var consumed = initialEventId
    fun consume(eventId: String?): Boolean {
        if (eventId.isNullOrBlank() || eventId == consumed) return false
        consumed = eventId
        return true
    }
}

internal data class JarvysHeaderOneShotRequest(val kind: JarvysHeaderOneShotKind, val eventId: String, val asset: JarvysVerifiedAnimation)
internal data class JarvysHeaderOneShotInputs(
    val eligible: Boolean,
    val failedRunId: String? = null, val sadAsset: JarvysVerifiedAnimation? = null,
    val interruptedRunId: String? = null, val interruptedAsset: JarvysVerifiedAnimation? = null,
    val tauntEventId: String? = null, val tauntAsset: JarvysVerifiedAnimation? = null,
) {
    fun eventId(kind: JarvysHeaderOneShotKind): String? = when (kind) {
        JarvysHeaderOneShotKind.SAD -> failedRunId
        JarvysHeaderOneShotKind.INTERRUPTED -> interruptedRunId
        JarvysHeaderOneShotKind.TAUNT -> tauntEventId
    }
    fun asset(kind: JarvysHeaderOneShotKind): JarvysVerifiedAnimation? = when (kind) {
        JarvysHeaderOneShotKind.SAD -> sadAsset
        JarvysHeaderOneShotKind.INTERRUPTED -> interruptedAsset
        JarvysHeaderOneShotKind.TAUNT -> tauntAsset
    }
    fun isCurrent(request: JarvysHeaderOneShotRequest): Boolean = eligible && request.eventId == eventId(request.kind) &&
        jarvysHeaderSameAnimation(request.asset, asset(request.kind))
}

/** Every event is consumed immediately; there is one current request and no deferred queue. */
internal class JarvysHeaderOneShotController(
    private val failures: JarvysFailurePlaybackGate,
    private val interruptions: JarvysFailurePlaybackGate,
    private val taps: JarvysHeaderTapPlaybackGate,
) {
    private var active: JarvysHeaderOneShotRequest? = null
    fun update(inputs: JarvysHeaderOneShotInputs): JarvysHeaderOneShotRequest? {
        val freshFailure = failures.consume(inputs.failedRunId)
        val freshInterruption = interruptions.consume(inputs.interruptedRunId)
        val freshTap = taps.consume(inputs.tauntEventId)
        active = active?.takeIf(inputs::isCurrent)
        fun request(kind: JarvysHeaderOneShotKind): JarvysHeaderOneShotRequest? {
            val id = inputs.eventId(kind) ?: return null
            val asset = inputs.asset(kind) ?: return null
            return if (inputs.eligible && asset.animationId == kind.animationId) JarvysHeaderOneShotRequest(kind, id, asset) else null
        }
        active = when {
            freshTap -> request(JarvysHeaderOneShotKind.TAUNT)
            active?.kind == JarvysHeaderOneShotKind.TAUNT -> active
            inputs.failedRunId != null && inputs.interruptedRunId != null -> null
            freshFailure -> request(JarvysHeaderOneShotKind.SAD)
            freshInterruption -> request(JarvysHeaderOneShotKind.INTERRUPTED)
            else -> active
        }
        return active
    }
    fun finish(request: JarvysHeaderOneShotRequest): JarvysHeaderOneShotRequest? {
        if (active == request) active = null
        return active
    }
    fun clear() { active = null }
}

/** A descriptive routing table, never admission for historical terminal modes. */
internal fun jarvysHeaderAnimationIdForMode(mode: Int): String? = when (mode) {
    0, 3, 6 -> "idle"
    1 -> "thinking"
    2 -> "working"
    4, 5 -> "waiting"
    7 -> "sad"
    8 -> "interrupted"
    9 -> "taunt"
    else -> null
}
internal fun jarvysHeaderSelectedTimeline(oneShot: JarvysHeaderOneShotRequest?, activity: JarvysHeaderActivityRequest?): JarvysHeaderTimeline =
    oneShot?.let { jarvysHeaderOneShotTimeline(it.kind) } ?: activity?.timeline ?: JarvysHeaderTimeline.IDLE

internal data class JarvysHeaderArtboardMetadata(val name: String, val animations: List<String>)
internal data class JarvysHeaderNativeBinding(val artboard: String, val animation: String)

/** Exact observed spelling is preserved; sole-timeline fallback requires a catalog file dedicated to the requested ID. */
internal fun jarvysHeaderDiscoverBinding(animationId: String, artboards: List<JarvysHeaderArtboardMetadata>): JarvysHeaderNativeBinding? {
    if (animationId !in setOf("waiting", "interrupted", "taunt")) return null
    if (artboards.isEmpty() || artboards.any { it.name.isBlank() } ||
        artboards.map { it.name }.distinct().size != artboards.size ||
        artboards.any { board -> board.animations.any { it.isBlank() } || board.animations.distinct().size != board.animations.size }) return null
    fun normalized(name: String) = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    val expected = normalized(animationId)
    val candidates = artboards.flatMap { board -> board.animations.map { JarvysHeaderNativeBinding(board.name, it) } }
    val namedAnimations = candidates.filter { normalized(it.animation) == expected }
    if (namedAnimations.size == 1) return namedAnimations.single()
    if (namedAnimations.isNotEmpty()) return namedAnimations.filter { normalized(it.artboard) == expected }.singleOrNull()
    val namedArtboards = candidates.filter { normalized(it.artboard) == expected }
    if (namedArtboards.isNotEmpty()) return namedArtboards.singleOrNull()
    return candidates.singleOrNull()
}
internal fun jarvysHeaderMayPublishFrame(bindingReady: Boolean, releaseRequested: Boolean): Boolean = bindingReady && !releaseRequested
