package com.jarvys.agent.workspaces

/** OMB application pin7de00e4 progress-window policy, not lifetime cost/provider-call quotas. */
internal object WorkspaceTreePolicy {
    const val VERSION=1
    const val MAX_DEPTH=4
    const val MAX_REQUESTS=64L
    const val MAX_EXECUTIONS=96L
    const val MAX_WAKES=48L
    // Matching Full132/Play132 proves Registry/codec and additive Room compatibility.
    // Local acceptance only; final-source repetition and independent implementation PASS remain required.
    @JvmStatic fun admissionEnabled():Boolean=true
}
