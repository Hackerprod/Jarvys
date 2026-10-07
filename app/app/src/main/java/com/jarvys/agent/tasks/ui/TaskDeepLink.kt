package com.jarvys.agent.tasks.ui

import android.content.Context
import com.jarvys.agent.tasks.TaskStore

/** Validates an external task ID using the conversation-session character/length contract and live store. */
object TaskDeepLink {
    private val idPattern = Regex("[A-Za-z0-9_.-]{1,100}")

    fun existingTaskId(context: Context, candidate: String?): String? {
        if (candidate == null || !idPattern.matches(candidate)) return null
        return runCatching { TaskStore(context.applicationContext).get(candidate)?.id }.getOrNull()
    }
}
