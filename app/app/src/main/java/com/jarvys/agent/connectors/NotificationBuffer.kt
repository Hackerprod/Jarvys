package com.jarvys.agent.connectors

import android.app.Notification
import android.app.Notification.Action
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat
import com.jarvys.agent.proactive.NotificationInput
import com.jarvys.agent.proactive.ProactivePreferences
import com.jarvys.agent.proactive.ProactiveNotificationDispatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
    val hasContentIntent: Boolean,
    val hasReplyAction: Boolean,
    val category: String? = null,
    val truncated: Boolean = false,
)

/** Bounded in-memory notification history. It has no disk-backed implementation. */
class NotificationBuffer(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, NotificationSnapshot>()
    private var expiryTask: ScheduledFuture<*>? = null

    init {
        require(maxEntries in 1..MAX_ENTRIES)
        require(ttlMillis > 0)
    }

    fun put(item: NotificationSnapshot) = synchronized(lock) {
        pruneLocked()
        entries.remove(item.key)
        entries[item.key] = item.copy(
            packageName = item.packageName.take(MAX_PACKAGE_CHARS),
            appName = item.appName.take(MAX_APP_NAME_CHARS),
            title = item.title.take(MAX_TITLE_CHARS),
            text = item.text.take(MAX_TEXT_CHARS),
            category = item.category?.take(80),
            truncated = item.truncated || item.packageName.length > MAX_PACKAGE_CHARS ||
                item.appName.length > MAX_APP_NAME_CHARS || item.title.length > MAX_TITLE_CHARS ||
                item.text.length > MAX_TEXT_CHARS || (item.category?.length ?: 0) > 80,
        )
        while (entries.size > maxEntries) entries.remove(entries.entries.first().key)
        scheduleExpiryLocked()
    }

    fun recent(appFilter: String?, limit: Int): List<NotificationSnapshot> = synchronized(lock) {
        pruneLocked()
        val filter = appFilter?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
        entries.values.asSequence()
            .filter { filter == null || it.packageName.lowercase().contains(filter) || it.appName.lowercase().contains(filter) }
            .sortedByDescending { it.postedAtMillis }
            .take(limit.coerceIn(1, maxEntries))
            .toList()
    }

    fun get(key: String): NotificationSnapshot? = synchronized(lock) {
        pruneLocked()
        entries[key]
    }

    fun keys(): Set<String> = synchronized(lock) {
        pruneLocked()
        entries.keys.toSet()
    }

    fun remove(key: String) = synchronized(lock) { entries.remove(key); scheduleExpiryLocked(); Unit }
    fun clear() = synchronized(lock) { entries.clear(); expiryTask?.cancel(false); expiryTask = null }
    fun size(): Int = synchronized(lock) { pruneLocked(); entries.size }

    private fun pruneLocked() {
        val now = clock()
        val expired = entries.values.filter { now >= it.postedAtMillis && now - it.postedAtMillis >= ttlMillis }
            .map { it.key }
        expired.forEach(entries::remove)
        scheduleExpiryLocked()
    }

    private fun scheduleExpiryLocked() {
        expiryTask?.cancel(false)
        expiryTask = null
        val oldest = entries.values.minOfOrNull { it.postedAtMillis } ?: return
        val expiresAt = runCatching { Math.addExact(oldest, ttlMillis) }.getOrDefault(Long.MAX_VALUE)
        val delay = (expiresAt - clock()).coerceAtLeast(0L)
        expiryTask = EXPIRY_EXECUTOR.schedule({ synchronized(lock) { pruneLocked() } }, delay, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val MAX_ENTRIES = 100
        const val MAX_PACKAGE_CHARS = 200
        const val MAX_APP_NAME_CHARS = 120
        const val MAX_TITLE_CHARS = 200
        const val MAX_TEXT_CHARS = 500
        const val DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000
        private val EXPIRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "JarvysNotificationExpiry").apply { isDaemon = true }
        }
    }
}

object NotificationListenerAccess {
    fun isEnabled(context: Context): Boolean = runCatching {
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }.getOrDefault(false)
}

object NotificationListenerRuntime {
    val buffer = NotificationBuffer()
    @Volatile var listener: JarvysNotificationListenerService? = null
        private set

    fun attach(service: JarvysNotificationListenerService) { listener = service }
    fun detach(service: JarvysNotificationListenerService) {
        if (listener === service) listener = null
    }
}

class JarvysNotificationListenerService : NotificationListenerService() {
    private data class Actions(val content: PendingIntent?, val actions: List<Action>)
    private val actionReferences = ConcurrentHashMap<String, Actions>()
    private val proactivePreferences by lazy(LazyThreadSafetyMode.NONE) { ProactivePreferences(this) }
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "JarvysNotificationCapture").apply { isDaemon = true }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerRuntime.attach(this)
        activeNotifications.orEmpty().forEach(::enqueueCapture)
    }

    override fun onListenerDisconnected() {
        NotificationListenerRuntime.detach(this)
        captureExecutor.execute(::clearMemoryBuffer)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        NotificationListenerRuntime.detach(this)
        captureExecutor.execute(::clearMemoryBuffer)
        captureExecutor.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) = enqueueCapture(sbn)

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        if (!captureExecutor.isShutdown) captureExecutor.execute {
            actionReferences.remove(sbn.key)
            pruneActionReferences()
        }
    }

    fun dismiss(key: String) {
        require(actionReferences.containsKey(key)) { "Notification is no longer active" }
        cancelNotification(key)
    }

    fun isActive(key: String): Boolean = actionReferences.containsKey(key)
    fun canOpen(key: String): Boolean = actionReferences[key]?.content != null
    fun canReply(key: String): Boolean = actionReferences[key]?.actions.orEmpty().any { action ->
        action.remoteInputs.orEmpty().any { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }
    }

    fun captureCurrentNotifications() { activeNotifications.orEmpty().forEach(::enqueueCapture) }

    fun clearMemoryBuffer() {
        actionReferences.clear()
        NotificationListenerRuntime.buffer.clear()
    }

    fun open(key: String) {
        val pendingIntent = requireNotNull(actionReferences[key]?.content) { "Notification has no open action or is no longer active" }
        pendingIntent.send(this, 0, null)
    }

    fun reply(key: String, text: String) {
        val actions = requireNotNull(actionReferences[key]) { "Notification is no longer active" }
        val action = actions.actions.firstOrNull { action ->
            action.remoteInputs.orEmpty().any { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }
        } ?: error("Notification does not offer a text reply action")
        val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }
        val fillIn = Intent()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), fillIn, results)
        action.actionIntent.send(this, 0, fillIn)
    }

    private fun capture(sbn: android.service.notification.StatusBarNotification) {
        if (NotificationListenerRuntime.listener !== this) return
        if (!NotificationFilter.isRestrictedTelephonyNotification(this, sbn) && NotificationListenerAccess.isEnabled(this)) {
            runCatching {
                ProactiveNotificationDispatch.onNotification(
                    this,
                    proactivePreferences,
                    { proactiveNotificationInput(sbn) },
                    packageName,
                )
            }
        }
        val prefs = getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("connected_${NotificationsConnector.ID}", false)) return
        if (!NotificationListenerAccess.isEnabled(this)) return
        if (sbn.packageName == packageName || NotificationFilter.isRestrictedTelephonyNotification(this, sbn)) return
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val appName = runCatching {
            val app = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(app).toString()
        }.getOrDefault(sbn.packageName)
        val actions = notification.actions.orEmpty().toList()
        val reply = actions.any { action ->
            action.remoteInputs.orEmpty().any { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }
        }
        NotificationListenerRuntime.buffer.put(NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            category = notification.category,
            postedAtMillis = sbn.postTime,
            hasContentIntent = notification.contentIntent != null,
            hasReplyAction = reply,
        ))
        actionReferences[sbn.key] = Actions(notification.contentIntent, actions)
        pruneActionReferences()
    }

    private fun proactiveNotificationInput(sbn: android.service.notification.StatusBarNotification): NotificationInput {
        val notification = sbn.notification
        val extras = notification.extras
        val messages = runCatching {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras?.getParcelableArray(Notification.EXTRA_MESSAGES),
            ).orEmpty()
        }.getOrDefault(emptyList())
        val sender = messages.lastOrNull()?.let { message ->
            message.sender?.toString()
        }
        val appLabel = runCatching {
            val app = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(app).toString()
        }.getOrDefault(sbn.packageName)
        val template = extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        return NotificationInput(
            receivedAtMillis = sbn.postTime,
            observedAtMillis = System.currentTimeMillis(),
            appPackage = sbn.packageName,
            appLabel = appLabel,
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            body = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            androidCategory = notification.category,
            messagingSender = sender,
            isCallStyle = notification.category == Notification.CATEGORY_CALL || template.endsWith("CallStyle"),
            isMissedCall = notification.category == "missed_call",
            ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            foregroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            notificationKey = sbn.key,
            conversationThread = notification.group,
        )
    }

    fun pruneExpiredActionReferences() {
        val retained = NotificationListenerRuntime.buffer.keys()
        actionReferences.keys.removeIf { it !in retained }
    }

    private fun pruneActionReferences() = pruneExpiredActionReferences()

    private fun enqueueCapture(sbn: android.service.notification.StatusBarNotification) {
        if (!captureExecutor.isShutdown) captureExecutor.execute { capture(sbn) }
    }
}

/** Avoid exposing the default SMS app's message previews or phone call UI through a second API. */
object NotificationFilter {
    fun isRestrictedTelephonyNotification(context: Context, sbn: android.service.notification.StatusBarNotification): Boolean {
        return isRestrictedTelephonyPackage(context, sbn.packageName, sbn.notification.category)
    }

    fun isRestrictedTelephonyPackage(context: Context, packageName: String, category: String?): Boolean {
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        if (defaultSms != null && packageName == defaultSms) return true
        if (category != Notification.CATEGORY_CALL) return false
        val defaultDialer = runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        return defaultDialer != null && packageName == defaultDialer
    }
}
