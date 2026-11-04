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
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ProactiveReplyAcceptance
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.workspaces.WorkspaceIdentity
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
    // All entry paths serialize canonical capture through publication, so a delayed old post cannot
    // overwrite a newer reply affordance. Room reads finish before Android notification calls.
    private val publicationLock = Any()

    /** Returns false when notification permission or app-level notifications are unavailable. */
    @Suppress("UNUSED_PARAMETER") // Retained caller signature; action identity and content are re-read from Room below.
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
    ): Boolean = synchronized(publicationLock) {
        postCanonical(context, threadKey, urgency)
    }

    private fun postCanonical(context: Context, threadKey: String, urgency: String): Boolean {
        val app = context.applicationContext
        if (!ProactiveNotificationPermission.isGranted(app) || !NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            return false
        }
        return runCatching {
            val bridge = ChatDatabaseBridge.getInstance(app)
            val latest = bridge.captureLatestProactiveReplySourceSync(threadKey) ?: return false
            val canonicalThread = LocalRunStore(app).readProactiveThreadMessages(WorkspaceIdentity.CENTRAL_SESSION, threadKey)
            val notice = canonicalThread.lastOrNull { it.proactiveDecisionId != null }
            ensureChannel(app, AppLanguageRuntime.localizedContext(app))
            // The notice lives in Central: the intent only carries the anchor message to open it at.
            val openChat = com.jarvys.agent.FreshStorageBootstrap.stamp(Intent(app, MainActivity::class.java))
                .putExtra(MainActivity.EXTRA_OPEN_PROACTIVE_MESSAGE_ID, latest.sourceMessageId)
                .setData(Uri.parse("jarvys://proactive/${ProactiveThreadKey.fingerprint(threadKey)}/${Uri.encode(latest.sourceMessageId)}"))
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
            canonicalThread.map(StoredMessage::toThreadMessage).forEach { message ->
                val sender = if (message.fromAssistant) person else user
                style.addMessage(ProactiveTextSanitizer.sanitize(message.text), message.timestampMillis, sender)
            }
            val notificationId = threadKey.hashCode()
            val notificationTag = "$NOTIFICATION_TAG:${ProactiveThreadKey.fingerprint(threadKey)}"
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.proactive_chat_title))
                .setContentText(ProactiveTextSanitizer.sanitize(latest.text))
                .setStyle(style)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(priority)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            val actions = mutableListOf<NotificationCompat.Action>()
            if (notice != null && notice.suggestedRepliesConsumedSeq == null) {
                notice.suggestedReplies.take((MAX_PLATFORM_ACTION_BUTTONS - 1).coerceAtLeast(0)).forEachIndexed { index, _ ->
                    val option = bridge.captureProactiveReplySourceSync(notice.messageId, "OPTION", index)
                        ?: return@forEachIndexed
                    val reply = option.suggestedReplies.getOrNull(index) ?: return@forEachIndexed
                    val binding = ProactiveReplyNotificationIdentity.Binding(
                        option.physicalGeneration, option.sourceMessageId, option.threadKey, "OPTION",
                        ProactiveReplyAcceptance.actionId(option.physicalGeneration, WorkspaceIdentity.CENTRAL_SESSION,
                            option.threadKey, option.sourceMessageId, "OPTION"), index, option.sourceFingerprint,
                    )
                    val actionPendingIntent = PendingIntent.getBroadcast(app, notificationId + index + 1,
                        ProactiveReplyNotificationIdentity.createIntent(app, binding), pendingIntentFlags())
                    actions += NotificationCompat.Action.Builder(R.drawable.ic_jarvys, reply.label, actionPendingIntent).build()
                }
            }
            val inlineBinding = ProactiveReplyNotificationIdentity.Binding(
                latest.physicalGeneration, latest.sourceMessageId, latest.threadKey, "INLINE", latest.inlineActionId, null, latest.sourceFingerprint,
            )
            val replyPendingIntent = PendingIntent.getBroadcast(app, notificationId,
                ProactiveReplyNotificationIdentity.createIntent(app, inlineBinding), remoteInputPendingIntentFlags())
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

    // RemoteInput must be fillable, but only this explicit broadcast PendingIntent is mutable.
    private fun remoteInputPendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)

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
