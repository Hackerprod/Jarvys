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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.jarvys.agent.MainActivity
import com.jarvys.agent.R
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.proactive.ProactiveTextSanitizer
import com.jarvys.agent.proactive.ProactiveRedactor
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveThreadMessage
import com.jarvys.agent.proactive.toThreadMessage
import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface ScheduledTaskNotifications {
    fun result(context: Context, store: com.jarvys.agent.LocalRunStore, task: ScheduledTask,
               messageId: String, title: String, body: String, urgency: String,
               suggestedReplies: List<ProactiveSuggestedReply>): Boolean
    fun attention(context: Context, task: ScheduledTask, cause: TaskRunCause): Boolean
    fun recovered(context: Context, task: ScheduledTask): Boolean
    fun pendingAction(context: Context, task: ScheduledTask, action: TaskPendingAction): Boolean = false
}

/** Own task-result channel. Conversation persistence is performed before this best-effort delivery. */
object TaskNotifier : ScheduledTaskNotifications {
    const val CHANNEL_ID = "jarvys_scheduled_tasks"
    private const val NOTIFICATION_TAG = "jarvys_task"

    override fun result(context: Context, store: com.jarvys.agent.LocalRunStore, task: ScheduledTask,
                        messageId: String, title: String, body: String, urgency: String,
                        suggestedReplies: List<ProactiveSuggestedReply>): Boolean {
        val messages = store.readProactiveThreadMessages(
            WorkspaceIdentity.CENTRAL_SESSION, ScheduledTaskConversation.threadKey(task.id))
            .map(StoredMessage::toThreadMessage)
        return post(context, task, messageId, messages, title, body, urgency, suggestedReplies)
    }

    override fun attention(context: Context, task: ScheduledTask, cause: TaskRunCause): Boolean {
        val localized = AppLanguageRuntime.localizedContext(context.applicationContext)
        return post(localized, task, "attention-${task.id}", emptyList(),
            localized.getString(R.string.scheduled_tasks_attention_title, ProactiveTextSanitizer.sanitize(task.name)),
            localized.getString(R.string.scheduled_tasks_attention_body, cause.localized(localized)),
            "high", emptyList(), openTask = true)
    }

    override fun recovered(context: Context, task: ScheduledTask): Boolean = post(
        context, task, "recovered-${task.id}", emptyList(),
        context.getString(R.string.scheduled_tasks_recovered_title, ProactiveTextSanitizer.sanitize(task.name)),
        context.getString(R.string.scheduled_tasks_recovered_body), "normal", emptyList(), openTask = true,
    )

    override fun pendingAction(context: Context, task: ScheduledTask, action: TaskPendingAction): Boolean {
        val app = context.applicationContext
        if (!notificationPermissionGranted(app) || !NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        return runCatching {
            ensureChannel(app)
            val taskIntent = com.jarvys.agent.FreshStorageBootstrap.stamp(Intent(app, MainActivity::class.java))
                .putExtra(MainActivity.EXTRA_OPEN_TASK, task.id)
                .setData(Uri.parse("jarvys://task/detail/${Uri.encode(task.id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPending = PendingIntent.getActivity(app, task.id.hashCode(), taskIntent, pendingIntentFlags())
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.task_pending_action_notification_title,
                    ProactiveTextSanitizer.sanitize(task.name)))
                .setContentText(app.getString(R.string.task_pending_action_notification_body,
                    ProactiveTextSanitizer.sanitize(task.name), ProactiveTextSanitizer.sanitize(action.toolName)))
                .setContentIntent(openPending)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPublicVersion(NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(app.getString(R.string.task_pending_action_notification_public_title))
                    .setContentText(app.getString(R.string.task_pending_action_notification_public_body))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build())
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                app.getString(R.string.task_pending_action_reject),
                pendingActionIntent(app, action, TaskPendingActionReceiver.ACTION_REJECT))
            builder.addAction(android.R.drawable.ic_menu_save,
                app.getString(R.string.task_pending_action_approve),
                pendingActionIntent(app, action, TaskPendingActionReceiver.ACTION_APPROVE))
            NotificationManagerCompat.from(app).notify(
                "$PENDING_ACTION_TAG:${action.id}", action.id.hashCode(), builder.build())
            true
        }.getOrDefault(false)
    }

    fun pendingActionExpired(context: Context, task: ScheduledTask, action: TaskPendingAction): Boolean {
        val app = context.applicationContext
        if (!notificationPermissionGranted(app) || !NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        return runCatching {
            ensureChannel(app)
            val taskIntent = com.jarvys.agent.FreshStorageBootstrap.stamp(Intent(app, MainActivity::class.java))
                .putExtra(MainActivity.EXTRA_OPEN_TASK, task.id)
                .setData(Uri.parse("jarvys://task/detail/${Uri.encode(task.id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPending = PendingIntent.getActivity(app, task.id.hashCode(), taskIntent, pendingIntentFlags())
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.task_pending_action_expired_notification_title))
                .setContentText(app.getString(R.string.task_pending_action_expired_notification_body,
                    ProactiveTextSanitizer.sanitize(task.name), ProactiveTextSanitizer.sanitize(action.toolName)))
                .setContentIntent(openPending)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPublicVersion(NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(app.getString(R.string.task_pending_action_expired_notification_public_title))
                    .setContentText(app.getString(R.string.task_pending_action_expired_notification_public_body))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build())
                .build()
            NotificationManagerCompat.from(app).notify(
                "$PENDING_ACTION_TAG:${action.id}", action.id.hashCode(), notification)
            true
        }.getOrDefault(false)
    }

    fun dismissPendingAction(context: Context, actionId: String) {
        NotificationManagerCompat.from(context.applicationContext)
            .cancel("$PENDING_ACTION_TAG:$actionId", actionId.hashCode())
    }

    private fun pendingActionIntent(context: Context, action: TaskPendingAction, actionName: String): PendingIntent {
        val intent = com.jarvys.agent.FreshStorageBootstrap.stamp(Intent(context, TaskPendingActionReceiver::class.java))
            .setAction(actionName)
            .putExtra(TaskPendingActionReceiver.EXTRA_ACTION_ID, action.id)
            .putExtra(TaskPendingActionReceiver.EXTRA_TASK_ID, action.taskId)
            .setData(Uri.parse("jarvys://task/pending/${Uri.encode(action.id)}/${Uri.encode(actionName)}"))
        return PendingIntent.getBroadcast(context, action.id.hashCode(), intent, pendingIntentFlags())
    }

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
            // A result lives in Central: its intent carries the anchor message; incidents open the task instead.
            val openIntent = com.jarvys.agent.FreshStorageBootstrap.stamp(Intent(app, MainActivity::class.java))
                .putExtra(if (openTask) MainActivity.EXTRA_OPEN_TASK else MainActivity.EXTRA_OPEN_PROACTIVE_MESSAGE_ID,
                    if (openTask) task.id else messageId)
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
                val detailIntent = com.jarvys.agent.FreshStorageBootstrap.stamp(Intent(app, MainActivity::class.java))
                    .putExtra(MainActivity.EXTRA_OPEN_TASK, task.id)
                    .setData(Uri.parse("jarvys://task/view/${Uri.encode(task.id)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                builder.addAction(android.R.drawable.ic_menu_view, app.getString(R.string.task_view_task),
                    PendingIntent.getActivity(app, task.id.hashCode() xor VIEW_TASK_REQUEST_SUFFIX,
                        detailIntent, pendingIntentFlags()))
            }
            suggestedReplies.take(MAX_NOTIFICATION_REPLY_ACTIONS).forEachIndexed { index, reply ->
                val actionIntent = com.jarvys.agent.FreshStorageBootstrap.stamp(Intent(app, TaskActionReceiver::class.java))
                    .setAction(TaskActionReceiver.ACTION_SUGGESTED_REPLY)
                    .putExtra(TaskActionReceiver.EXTRA_TASK_ID, task.id)
                    .putExtra(TaskActionReceiver.EXTRA_MESSAGE_ID, messageId)
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
    private const val PENDING_ACTION_TAG = "jarvys_task_pending"
}

class TaskActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!com.jarvys.agent.FreshStorageBootstrap.isCurrent(intent)) return
        if (intent.action != ACTION_SUGGESTED_REPLY) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank) ?: return
        val text = intent.getStringExtra(EXTRA_REPLY_TEXT)?.trim()?.takeIf(String::isNotBlank) ?: return
        val app = context.applicationContext
        val pendingResult = goAsync()
        try {
            TaskReplyReceiverExecutor.execute {
                try {
                    val store = com.jarvys.agent.LocalRunStore(app)
                    val sessionId = WorkspaceIdentity.CENTRAL_SESSION
                    // The reply is an ordinary Central turn; its thread and the result it answers let the turn
                    // recover that result if compaction has already dropped it.
                    val messageId = try {
                        store.appendProactiveUserMessage(sessionId, text, ScheduledTaskConversation.threadKey(taskId),
                            intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf(String::isNotBlank))
                    } catch (failure: RuntimeException) {
                        Log.e("JarvysTasks", "Could not persist suggested task reply", failure)
                        return@execute
                    }
                    runCatching {
                        com.jarvys.agent.AgentForegroundService.startRealAgentFromStoredChatMessage(
                            app, sessionId, messageId,
                        )
                    }
                } catch (failure: RuntimeException) {
                    Log.e("JarvysTasks", "Could not handle suggested task reply", failure)
                } finally {
                    pendingResult?.finish()
                }
            }
        } catch (failure: RuntimeException) {
            pendingResult?.finish()
            Log.e("JarvysTasks", "Could not queue suggested task reply", failure)
        }
    }

    companion object {
        const val ACTION_SUGGESTED_REPLY = "com.jarvys.agent.tasks.SUGGESTED_REPLY"
        const val EXTRA_TASK_ID = "scheduled_task_id"
        const val EXTRA_MESSAGE_ID = "scheduled_task_message_id"
        const val EXTRA_REPLY_TEXT = "scheduled_task_reply_text"
    }
}

private object TaskReplyReceiverExecutor {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysTaskReplies").apply { isDaemon = true }
    }

    fun execute(task: Runnable) = executor.execute(task)
}

fun interface TaskPendingActionHandler {
    /** Queue execution of this exact approved action without starting the chat UI or a model run. */
    fun approve(context: Context, action: TaskPendingAction): Boolean
}

fun interface TaskPendingActionExecutor {
    /** Invoke the already-approved registered tool directly; this must not start a model run. */
    fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction): com.jarvys.agent.CoreToolResult
    /** Legacy callers keep execute3, but a context-aware approval must never silently bypass evidence. */
    fun execute(context: Context, task: ScheduledTask, action: TaskPendingAction,
                invocation: com.jarvys.agent.EffectInvocation): com.jarvys.agent.CoreToolResult =
        throw IllegalStateException("Task action executor does not support durable effect admission")
    /** Unknown metadata is not permission to claim a synchronous action. */
    fun completionMode(context: Context, task: ScheduledTask, action: TaskPendingAction): com.jarvys.agent.CoreTool.CompletionMode? = null
    fun hasCompleteEffectAdmissionCoverage(): Boolean = false
    fun effectClassification(context: Context, task: ScheduledTask, action: TaskPendingAction): com.jarvys.agent.CoreTool.EffectClass? = null
    /** Compatibility bridge preserves unknown legacy metadata; memory-aware providers override this. */
    fun actionEffectClassification(context: Context, task: ScheduledTask, action: TaskPendingAction): TaskActionEffectClassification? =
        when (effectClassification(context, task, action)) {
            com.jarvys.agent.CoreTool.EffectClass.READ -> TaskActionEffectClassification.READ
            com.jarvys.agent.CoreTool.EffectClass.WRITE -> TaskActionEffectClassification.WRITE
            null -> null
        }
}

/** Implemented by the process Application so notification broadcasts work after a cold start. */
interface TaskPendingActionHandlerProvider {
    fun taskPendingActionHandler(): TaskPendingActionHandler
    fun taskPendingActionExecutor(): TaskPendingActionExecutor
}

class TaskPendingActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!com.jarvys.agent.FreshStorageBootstrap.isCurrent(intent)) return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)?.takeIf(String::isNotBlank) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf(String::isNotBlank) ?: return
        val app = context.applicationContext
        val store = TaskPendingActionStore(app)
        val pending = store.get(actionId)?.takeIf { it.taskId == taskId } ?: return
        when (intent.action) {
            ACTION_REJECT -> {
                store.reject(actionId, pending.actionBinding)
                TaskNotifier.dismissPendingAction(app, actionId)
                TaskScheduler.pendingActionResolved(app, taskId)
            }
            ACTION_APPROVE -> {
                if (pending.status != TaskPendingActionStatus.PENDING) return
                val handler = (app as? TaskPendingActionHandlerProvider)?.taskPendingActionHandler()
                    ?: return
                if (handler.approve(app, pending)) TaskNotifier.dismissPendingAction(app, actionId)
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.jarvys.agent.tasks.APPROVE_PENDING_ACTION"
        const val ACTION_REJECT = "com.jarvys.agent.tasks.REJECT_PENDING_ACTION"
        const val EXTRA_ACTION_ID = "pending_action_id"
        const val EXTRA_TASK_ID = "scheduled_task_id"

    }
}
