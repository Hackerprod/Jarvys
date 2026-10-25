package com.jarvys.agent.tasks

/** Task results are written into Central; this names the thread that groups the results of one task. */
object ScheduledTaskConversation {
    private const val THREAD_PREFIX = "task:"

    @JvmStatic fun threadKey(taskId: String): String = "$THREAD_PREFIX$taskId"

    @JvmStatic fun isTaskThread(threadKey: String): Boolean = threadKey.startsWith(THREAD_PREFIX)

    /** The task a thread belongs to, or null for a proactive thread. */
    @JvmStatic fun taskId(threadKey: String): String? = threadKey.takeIf(::isTaskThread)?.removePrefix(THREAD_PREFIX)
}
