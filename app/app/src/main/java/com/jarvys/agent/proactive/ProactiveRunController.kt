package com.jarvys.agent.proactive

/** Compatibility facade for existing Proactive callers; coordination is shared with task runs. */
object ProactiveRunController {
    fun tryStart(): com.jarvys.agent.CancellationToken? = BackgroundRunController.tryStart(BackgroundRunKind.PROACTIVE)

    fun finish(token: com.jarvys.agent.CancellationToken) =
        BackgroundRunController.finish(BackgroundRunKind.PROACTIVE, token)

    fun cancelAll() = BackgroundRunController.cancel(BackgroundRunKind.PROACTIVE)
}
