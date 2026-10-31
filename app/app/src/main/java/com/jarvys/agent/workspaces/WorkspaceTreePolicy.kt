package com.jarvys.agent.workspaces

/** OMB application pin7de00e4 progress-window policy, not lifetime cost/provider-call quotas. */
internal object WorkspaceTreePolicy {
    const val VERSION=1
    const val MAX_DEPTH=4
    const val MAX_REQUESTS=64L
    const val MAX_EXECUTIONS=96L
    const val MAX_WAKES=48L
    // Prior Registry/codec proof is retained, but the Room provenance extension needs fresh matching proof.
    // Local admission stays OFF until the additive Room migration/conservation gate passes.
    @JvmStatic fun admissionEnabled():Boolean=false
}
