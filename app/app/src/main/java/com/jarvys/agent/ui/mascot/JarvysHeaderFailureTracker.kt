package com.jarvys.agent.ui.mascot

/** Process-local observation: entering an old terminal run is never a fresh one-shot event. */
internal class JarvysHeaderFailureTracker {
    private var observedRunning = false
    private var observedIdentity: String? = null
    private var handledId: String? = null
    var failedRunId: String? = null
        private set
    var interruptedRunId: String? = null
        private set

    fun observe(runId: String?, running: Boolean, outcome: String?, eligible: Boolean,
        liveRunIdentity: String? = null): String? {
        val identity = liveRunIdentity ?: runId
        if (!eligible) {
            observedRunning = false
            observedIdentity = null
            failedRunId = null
            interruptedRunId = null
        } else if (running) {
            observedRunning = true
            observedIdentity = liveRunIdentity
            failedRunId = null
            interruptedRunId = null
        } else if (observedRunning) {
            observedRunning = false
            val sameGeneration = observedIdentity == null || observedIdentity == liveRunIdentity
            val fresh = !identity.isNullOrBlank() && sameGeneration && identity != handledId
            failedRunId = identity.takeIf { fresh && outcome == "FAILED" }
            interruptedRunId = identity.takeIf { fresh && outcome in interruptionOutcomes }
            if (!identity.isNullOrBlank()) handledId = identity
            observedIdentity = null
        } else if (identity != failedRunId || outcome != "FAILED") {
            failedRunId = null
        }
        if (identity != interruptedRunId || outcome !in interruptionOutcomes) interruptedRunId = null
        return failedRunId
    }

    private companion object {
        val interruptionOutcomes = setOf("STOPPED", "PARTIAL", "TIMED_OUT", "INTERRUPTED")
    }
}
