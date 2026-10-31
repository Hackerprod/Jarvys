package com.jarvys.agent.workspaces

/** OMB application pin7de00e4 progress-window policy, not lifetime cost/provider-call quotas. */
internal object WorkspaceTreePolicy {
    const val VERSION=1
    const val MAX_DEPTH=4
    const val MAX_REQUESTS=64L
    const val MAX_EXECUTIONS=96L
    const val MAX_WAKES=48L
    // Local reconstruction candidate: design PASS + matching Full/Play pre-write99 passed.
    // Delivery still requires full final-source acceptance and independent implementation PASS.
    @JvmStatic fun admissionEnabled():Boolean=true
}
