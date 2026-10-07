package com.jarvys.agent.tasks

import android.content.Context

object ScheduledTaskConversation {
    const val SESSION_ID = "jarvys-tasks"
    fun title(context: Context): String = context.getString(com.jarvys.agent.R.string.scheduled_tasks_conversation_title)
    fun threadKey(taskId: String): String = "task:$taskId"
}
