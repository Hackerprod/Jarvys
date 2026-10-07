package com.jarvys.agent.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.MainActivity
import com.jarvys.agent.R

object ProactiveNotificationPermission {
    fun isGranted(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun shouldRequestOnOptIn(context: Context, enabling: Boolean): Boolean = enabling &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted(context)
}

object ProactiveNotifier {
    private const val CHANNEL_ID = "jarvys_proactive_review"
    private const val NOTIFICATION_TAG = "jarvys_proactive_thread"
    private const val MAX_PLATFORM_ACTION_BUTTONS = 3
    private const val REMOTE_INPUT_KEY = ProactiveActionReceiver.EXTRA_INLINE_REPLY

    /** Returns false when notification permission or app-level notifications are unavailable. */
    fun post(
        context: Context,
        threadKey: String,
        messageId: String,
        body: String,
        urgency: String,
        threadMessages: List<ProactiveThreadMessage>,
        sourceMessageId: String,
        suggestedReplies: List<ProactiveSuggestedReply>,
        suggestedRepliesUsed: Boolean,
    ): Boolean {
        val app = context.applicationContext
        if (!ProactiveNotificationPermission.isGranted(app) || !NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            return false
        }
        return runCatching {
            ensureChannel(app, AppLanguageRuntime.localizedContext(app))
            val openChat = Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, ProactiveConversation.SESSION_ID)
                .putExtra(MainActivity.EXTRA_OPEN_PROACTIVE_MESSAGE_ID, messageId)
                .setData(Uri.parse("jarvys://proactive/${ProactiveThreadKey.fingerprint(threadKey)}/${Uri.encode(messageId)}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pendingIntent = PendingIntent.getActivity(app, threadKey.hashCode(), openChat, flags)
            val priority = when (urgency) {
                "high" -> NotificationCompat.PRIORITY_HIGH
                "low" -> NotificationCompat.PRIORITY_LOW
                else -> NotificationCompat.PRIORITY_DEFAULT
            }
            val appIcon = app.applicationInfo.icon.takeIf { it != 0 }
            val person = Person.Builder().setName(app.getString(R.string.app_name))
                .setIcon(appIcon?.let { IconCompat.createWithResource(app, it) }).build()
            val style = NotificationCompat.MessagingStyle(person)
                .setConversationTitle(app.getString(R.string.proactive_chat_title))
                .setGroupConversation(true)
            val user = Person.Builder().setName(app.getString(R.string.proactive_user_person)).build()
            threadMessages.forEach { message ->
                val sender = if (message.fromAssistant) person else user
                style.addMessage(ProactiveTextSanitizer.sanitize(message.text), message.timestampMillis, sender)
            }
            val notificationId = threadKey.hashCode()
            val notificationTag = "$NOTIFICATION_TAG:${ProactiveThreadKey.fingerprint(threadKey)}"
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.proactive_chat_title))
                .setContentText(ProactiveTextSanitizer.sanitize(body))
                .setStyle(style)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(priority)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            val actions = mutableListOf<NotificationCompat.Action>()
            if (!suggestedRepliesUsed && sourceMessageId.isNotBlank()) {
                suggestedReplies.take((MAX_PLATFORM_ACTION_BUTTONS - 1).coerceAtLeast(0)).forEachIndexed { index, reply ->
                    val actionIntent = Intent(app, ProactiveActionReceiver::class.java)
                        .setAction(ProactiveActionReceiver.ACTION_SUGGESTED_REPLY)
                        .putExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID, sourceMessageId)
                        .putExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX, index)
                        .setData(Uri.parse("jarvys://proactive/chip/${Uri.encode(sourceMessageId)}/$index"))
                    val actionPendingIntent = PendingIntent.getBroadcast(app, notificationId + index + 1,
                        actionIntent, pendingIntentFlags())
                    actions += NotificationCompat.Action.Builder(R.drawable.ic_jarvys, reply.label, actionPendingIntent).build()
                }
            }
            val replyIntent = Intent(app, ProactiveActionReceiver::class.java)
                .setAction(ProactiveActionReceiver.ACTION_INLINE_REPLY)
                .putExtra(ProactiveActionReceiver.EXTRA_THREAD_TOKEN, ProactiveThreadKey.fingerprint(threadKey))
                .setData(Uri.parse("jarvys://proactive/reply/$notificationId"))
            val replyPendingIntent = PendingIntent.getBroadcast(app, notificationId,
                replyIntent, pendingIntentFlags())
            val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
                .setLabel(app.getString(R.string.proactive_notification_reply_action)).build()
            actions += NotificationCompat.Action.Builder(R.drawable.ic_jarvys,
                app.getString(R.string.proactive_notification_reply_action), replyPendingIntent)
                .addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build()
            actions.forEach(builder::addAction)
            val notification = builder.build()
            NotificationManagerCompat.from(app).notify(notificationTag, notificationId, notification)
            true
        }.getOrDefault(false)
    }

    fun refreshThread(
        context: Context,
        threadKey: String,
        latestMessageId: String,
        latestText: String,
        threadMessages: List<ProactiveThreadMessage>,
        sourceMessageId: String,
        suggestedReplies: List<ProactiveSuggestedReply>,
        suggestedRepliesUsed: Boolean,
    ): Boolean = post(context, threadKey, latestMessageId, latestText, "normal",
        threadMessages, sourceMessageId, suggestedReplies, suggestedRepliesUsed)

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun ensureChannel(context: Context, localizedContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            localizedContext.getString(R.string.proactive_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = localizedContext.getString(R.string.proactive_notification_channel_description)
        })
    }
}
