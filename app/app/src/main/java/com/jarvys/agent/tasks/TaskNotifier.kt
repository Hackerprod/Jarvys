package com.jarvys.agent.tasks

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.jarvys.agent.MainActivity
import com.jarvys.agent.R
import com.jarvys.agent.proactive.ProactiveTextSanitizer
import com.jarvys.agent.proactive.ProactiveRedactor
import com.jarvys.agent.proactive.ProactiveThreadMessage
import com.jarvys.agent.proactive.ProactiveSuggestedReply

interface ScheduledTaskNotifications {
    fun result(context: Context, store: com.jarvys.agent.LocalRunStore, task: ScheduledTask,
               messageId: String, title: String, body: String, urgency: String,
               suggestedReplies: List<ProactiveSuggestedReply>): Boolean
    fun attention(context: Context, task: ScheduledTask, reason: String): Boolean
    fun recovered(context: Context, task: ScheduledTask): Boolean
}

/** Own task-result channel. Conversation persistence is performed before this best-effort delivery. */
object TaskNotifier : ScheduledTaskNotifications {
    const val CHANNEL_ID = "jarvys_scheduled_tasks"
    private const val NOTIFICATION_TAG = "jarvys_task"

    override fun result(context: Context, store: com.jarvys.agent.LocalRunStore, task: ScheduledTask,
                        messageId: String, title: String, body: String, urgency: String,
                        suggestedReplies: List<ProactiveSuggestedReply>): Boolean {
        val threadKey = ScheduledTaskConversation.threadKey(task.id)
        val messages = store.readConversationTimeline(ScheduledTaskConversation.SESSION_ID)
            .filter { it.proactiveThreadKey == threadKey }
            .map { ProactiveThreadMessage(it.text, it.timestampMillis, it.kind == "assistant") }
        return post(context, task, messageId, messages, title, body, urgency, suggestedReplies)
    }

    override fun attention(context: Context, task: ScheduledTask, reason: String): Boolean = post(
        context, task, "attention-${task.id}", emptyList(),
        context.getString(R.string.scheduled_tasks_attention_title, ProactiveTextSanitizer.sanitize(task.name)),
        context.getString(R.string.scheduled_tasks_attention_body, ProactiveTextSanitizer.sanitize(reason)),
        "high", emptyList(), openTask = true,
    )

    override fun recovered(context: Context, task: ScheduledTask): Boolean = post(
        context, task, "recovered-${task.id}", emptyList(),
        context.getString(R.string.scheduled_tasks_recovered_title, ProactiveTextSanitizer.sanitize(task.name)),
        context.getString(R.string.scheduled_tasks_recovered_body), "normal", emptyList(), openTask = true,
    )

    fun post(
        context: Context,
        task: ScheduledTask,
        messageId: String,
        messages: List<ProactiveThreadMessage>,
        title: String,
        body: String,
        urgency: String,
        suggestedReplies: List<ProactiveSuggestedReply>,
        openTask: Boolean = false,
    ): Boolean {
        val app = context.applicationContext
        if (!notificationPermissionGranted(app) || !NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        return runCatching {
            ensureChannel(app)
            val taskTag = "$NOTIFICATION_TAG:${task.id}"
            val notificationId = task.id.hashCode()
            val openIntent = Intent(app, MainActivity::class.java)
                .putExtra(if (openTask) MainActivity.EXTRA_OPEN_TASK else MainActivity.EXTRA_OPEN_CHAT_SESSION,
                    if (openTask) task.id else ScheduledTaskConversation.SESSION_ID)
                .setData(Uri.parse(if (openTask) "jarvys://task/detail/${Uri.encode(task.id)}"
                    else "jarvys://task/${Uri.encode(task.id)}/$messageId"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPending = PendingIntent.getActivity(app, task.id.hashCode(), openIntent, pendingIntentFlags())
            val appIcon = app.applicationInfo.icon.takeIf { it != 0 }
            val jarvys = Person.Builder().setName(app.getString(R.string.app_name))
                .setIcon(appIcon?.let { androidx.core.graphics.drawable.IconCompat.createWithResource(app, it) }).build()
            val user = Person.Builder().setName(app.getString(R.string.scheduled_tasks_user_person)).build()
            val style = NotificationCompat.MessagingStyle(jarvys)
                .setConversationTitle(ProactiveTextSanitizer.sanitize(task.name))
                .setGroupConversation(true)
            messages.forEach { line ->
                style.addMessage(ProactiveTextSanitizer.sanitize(line.text), line.timestampMillis,
                    if (line.fromAssistant) jarvys else user)
            }
            val priority = when (urgency) {
                "high" -> NotificationCompat.PRIORITY_HIGH
                "low" -> NotificationCompat.PRIORITY_LOW
                else -> NotificationCompat.PRIORITY_DEFAULT
            }
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(ProactiveTextSanitizer.sanitize(title))
                .setContentText(ProactiveTextSanitizer.sanitize(body))
                .setStyle(style)
                .setContentIntent(openPending)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(priority)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPublicVersion(NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(app.getString(R.string.scheduled_tasks_public_title))
                    .setContentText(app.getString(R.string.scheduled_tasks_public_body))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build())
            if (urgency != "high") builder.setSilent(true)
            if (!openTask) {
                val detailIntent = Intent(app, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_TASK, task.id)
                    .setData(Uri.parse("jarvys://task/view/${Uri.encode(task.id)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                builder.addAction(android.R.drawable.ic_menu_view, app.getString(R.string.task_view_task),
                    PendingIntent.getActivity(app, task.id.hashCode() xor VIEW_TASK_REQUEST_SUFFIX,
                        detailIntent, pendingIntentFlags()))
            }
            suggestedReplies.take(MAX_NOTIFICATION_REPLY_ACTIONS).forEachIndexed { index, reply ->
                val actionIntent = Intent(app, TaskActionReceiver::class.java)
                    .setAction(TaskActionReceiver.ACTION_SUGGESTED_REPLY)
                    .putExtra(TaskActionReceiver.EXTRA_TASK_ID, task.id)
                    .putExtra(TaskActionReceiver.EXTRA_REPLY_TEXT,
                        ProactiveTextSanitizer.sanitize(ProactiveRedactor.redactForModel(reply.text)))
                    .setData(Uri.parse("jarvys://task/reply/${Uri.encode(task.id)}/$messageId/$index"))
                val action = PendingIntent.getBroadcast(app, notificationId + index + 1,
                    actionIntent, pendingIntentFlags())
                builder.addAction(R.drawable.ic_jarvys,
                    ProactiveTextSanitizer.sanitize(ProactiveRedactor.redactForModel(reply.label)), action)
            }
            NotificationManagerCompat.from(app).notify(taskTag, notificationId, builder.build())
            true
        }.getOrDefault(false)
    }

    fun notificationPermissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.scheduled_tasks_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.scheduled_tasks_channel_description)
        })
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private const val MAX_NOTIFICATION_REPLY_ACTIONS = 3
    private const val VIEW_TASK_REQUEST_SUFFIX = 0x51A7
}

class TaskActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SUGGESTED_REPLY) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank) ?: return
        val text = intent.getStringExtra(EXTRA_REPLY_TEXT)?.trim()?.takeIf(String::isNotBlank) ?: return
        val app = context.applicationContext
        val store = com.jarvys.agent.LocalRunStore(app)
        val sessionId = ScheduledTaskConversation.SESSION_ID
        val messageId = runCatching { store.appendConversationMessage(sessionId, "user", text) }.getOrNull() ?: return
        runCatching { store.appendConversationTitleIfAbsent(sessionId, ScheduledTaskConversation.title(app)) }
        runCatching { com.jarvys.agent.AgentForegroundService.startRealAgentFromStoredChatMessage(app, sessionId, messageId) }
    }

    companion object {
        const val ACTION_SUGGESTED_REPLY = "com.jarvys.agent.tasks.SUGGESTED_REPLY"
        const val EXTRA_TASK_ID = "scheduled_task_id"
        const val EXTRA_REPLY_TEXT = "scheduled_task_reply_text"
    }
}
