package com.jarvys.agent.connectors

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvys.agent.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AutonomyPolicy { ASK, ALLOW, DENY }

data class AutonomyAuditRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long,
    val connectorId: String,
    val connectorName: String,
    val operationName: String,
    val operationLabel: String,
    val summary: String,
)

interface ConnectorAutonomyStore {
    fun policy(connectorId: String, operationName: String): AutonomyPolicy
    fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy)
    fun setPolicyChecked(connectorId: String, operationName: String, policy: AutonomyPolicy) =
        setPolicy(connectorId, operationName, policy)
    fun clearPolicies(connectorId: String) = Unit
    fun appendAudit(record: AutonomyAuditRecord)
    fun recentAudit(connectorId: String, limit: Int): List<AutonomyAuditRecord>
}

interface AutonomyActionNotifier {
    fun canPost(): Boolean
    fun missingRuntimePermission(): String?
    fun post(record: AutonomyAuditRecord)
}

object NoopAutonomyActionNotifier : AutonomyActionNotifier {
    override fun canPost() = true
    override fun missingRuntimePermission(): String? = null
    override fun post(record: AutonomyAuditRecord) = Unit
}

class InMemoryConnectorAutonomyStore : ConnectorAutonomyStore {
    private val lock = Any()
    private val policies = mutableMapOf<Pair<String, String>, AutonomyPolicy>()
    private val audit = mutableListOf<AutonomyAuditRecord>()

    override fun policy(connectorId: String, operationName: String): AutonomyPolicy = synchronized(lock) {
        policies[connectorId to operationName] ?: AutonomyPolicy.ASK
    }

    override fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy) = synchronized(lock) {
        policies[connectorId to operationName] = policy
    }

    override fun clearPolicies(connectorId: String) {
        synchronized(lock) { policies.keys.removeAll { it.first == connectorId } }
    }

    override fun appendAudit(record: AutonomyAuditRecord) {
        synchronized(lock) { audit.add(record.bounded()) }
    }

    override fun recentAudit(connectorId: String, limit: Int): List<AutonomyAuditRecord> = synchronized(lock) {
        audit.asReversed().filter { it.connectorId == connectorId }
            .take(limit.coerceAtLeast(0))
    }
}

/** User settings and the append-only audit share jarvys_connectors. */
class SharedPreferencesConnectorAutonomyStore(context: Context) : ConnectorAutonomyStore {
    private val preferences = context.applicationContext.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    init { preferences.edit().remove("autonomy_hourly_reservations").apply() }

    override fun policy(connectorId: String, operationName: String): AutonomyPolicy = runCatching {
        AutonomyPolicy.valueOf(preferences.getString(policyKey(connectorId, operationName), null) ?: "ASK")
    }.getOrDefault(AutonomyPolicy.ASK)

    override fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy) {
        preferences.edit().putString(policyKey(connectorId, operationName), policy.name).apply()
    }

    override fun setPolicyChecked(connectorId: String, operationName: String, policy: AutonomyPolicy) {
        synchronized(lock) {
            val key = policyKey(connectorId, operationName)
            val previous = preferences.getString(key, null)
            if (!preferences.edit().putString(key, policy.name).commit()) {
                // A failed commit can still alter the in-memory preferences map.
                preferences.edit().apply { if (previous == null) remove(key) else putString(key, previous) }.commit()
                throw IllegalStateException("Could not persist connector action policy")
            }
        }
    }

    override fun clearPolicies(connectorId: String) {
        val prefix = "autonomy_${connectorId}_"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        check(editor.commit()) { "Could not remove saved connector action policies" }
    }

    override fun appendAudit(record: AutonomyAuditRecord) {
        synchronized(lock) {
            val allRecords = readAudit() + record.bounded()
            val saved = preferences.edit()
                .putString(AUDIT_KEY, JSONArray().apply { allRecords.forEach { put(it.toJson()) } }.toString())
                .commit()
            check(saved) { "Could not persist autonomous action audit" }
        }
    }

    override fun recentAudit(connectorId: String, limit: Int): List<AutonomyAuditRecord> = synchronized(lock) {
        readAudit().asReversed().filter { it.connectorId == connectorId }
            .take(limit.coerceAtLeast(0))
    }

    private fun readAudit(): List<AutonomyAuditRecord> = runCatching {
        val array = JSONArray(preferences.getString(AUDIT_KEY, "[]"))
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toAuditRecord() }
    }.getOrDefault(emptyList())

    private fun policyKey(id: String, operation: String) = "autonomy_${id}_$operation"

    private fun AutonomyAuditRecord.toJson() = JSONObject()
        .put("id", id).put("timestampMillis", timestampMillis).put("connectorId", connectorId)
        .put("connectorName", connectorName).put("operationName", operationName)
        .put("operationLabel", operationLabel).put("summary", summary)

    private fun JSONObject.toAuditRecord() = AutonomyAuditRecord(
        id = optString("id"), timestampMillis = optLong("timestampMillis"), connectorId = optString("connectorId"),
        connectorName = optString("connectorName"), operationName = optString("operationName"),
        operationLabel = optString("operationLabel"), summary = optString("summary"),
    )

    companion object {
        private const val AUDIT_KEY = "autonomy_audit"
    }
}

class AndroidAutonomyActionNotifier(context: Context) : AutonomyActionNotifier {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    override fun missingRuntimePermission(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return Manifest.permission.POST_NOTIFICATIONS.takeIf {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    override fun canPost(): Boolean {
        if (missingRuntimePermission() != null || !notificationManager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun post(record: AutonomyAuditRecord) {
        check(canPost()) { "Jarvys notifications are disabled" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
                ?: error("Notification manager is unavailable")
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Autonomous actions", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Silent notices when Jarvys performs an action in Allow mode."
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                })
            }
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvys)
            .setContentTitle("Jarvys action: ${record.operationLabel}")
            .setContentText("${record.connectorName} · ${record.summary}")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(record.id.hashCode(), notification)
    }

    companion object { private const val CHANNEL_ID = "jarvys_autonomous_actions" }
}

private fun AutonomyAuditRecord.bounded() = copy(
    connectorName = connectorName.take(100),
    operationLabel = operationLabel.take(100),
    summary = summary.take(120),
)
