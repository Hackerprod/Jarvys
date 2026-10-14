package com.jarvys.agent.tasks

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreToolResult

/** Persists an exact unattended action and returns its structured, non-executed result. */
fun interface ScheduledTaskWriteDeferrer {
    fun defer(toolName: String, arguments: Map<String, Any>, token: CancellationToken): CoreToolResult
}
