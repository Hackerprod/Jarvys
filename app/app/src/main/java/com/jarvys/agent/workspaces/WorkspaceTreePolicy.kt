package com.jarvys.agent.workspaces

/** OMB application pin7de00e4 progress-window policy, not lifetime cost/provider-call quotas. */
internal object WorkspaceTreePolicy {
    const val VERSION=1
    const val MAX_DEPTH=4
    const val MAX_REQUESTS=64L
    const val MAX_EXECUTIONS=96L
    const val MAX_WAKES=48L
    // Existing pre-write99 passed. Admission OFF again pending additive TREE_TASK codec proof.
    // Final-source acceptance and independent implementation PASS remain mandatory.
    @JvmStatic fun admissionEnabled():Boolean=false
}
