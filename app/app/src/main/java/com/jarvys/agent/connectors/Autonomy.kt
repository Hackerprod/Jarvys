package com.jarvys.agent.connectors

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvys.agent.R
import java.util.UUID

enum class AutonomyPolicy { ASK, ALLOW, DENY }


interface ConnectorAutonomyStore {
    fun policy(connectorId: String, operationName: String): AutonomyPolicy
    /** A failed store must render as restricted; execution separately fails closed at admission. */
    fun unavailablePolicy(): AutonomyPolicy = AutonomyPolicy.ASK
    /** Null means no saved choice. Older/custom stores conservatively retain their effective policy. */
    fun savedPolicy(connectorId: String, operationName: String): AutonomyPolicy? = policy(connectorId, operationName)
    fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy)
    fun setPolicyChecked(connectorId: String, operationName: String, policy: AutonomyPolicy) =
        setPolicy(connectorId, operationName, policy)
    /** Old/custom stores retain their effective policy, but cannot promise atomic admission. */
    fun snapshot(connectorId: String, operationName: String): ActionPolicySnapshot = ActionPolicySnapshot(
        connectorId, operationName, policy(connectorId, operationName), 0L, ActionPolicyOrigin.LEGACY_UNKNOWN, this, "unsupported",
    )
    fun snapshot(connectorId: String, operationName: String, fallbackPolicy: AutonomyPolicy): ActionPolicySnapshot =
        snapshot(connectorId, operationName)
    fun snapshotMatching(anchor: ActionPolicySnapshot): ActionPolicySnapshot {
        throw IllegalStateException("This policy store does not support exact action snapshot matching")
    }
    fun setPolicyAndSnapshot(connectorId: String, operationName: String, policy: AutonomyPolicy): ActionPolicySnapshot {
        throw IllegalStateException("This policy store does not support atomic policy write receipts")
    }
    /** approvedAsk may only represent the caller's exact request-bound approval of this ASK snapshot. */
    fun admit(snapshot: ActionPolicySnapshot, approvedAsk: Boolean = false, validate: () -> Unit = {}) {
        throw IllegalStateException("This policy store does not support atomic action admission")
    }
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
    private data class State(val policy: AutonomyPolicy?, val revision: Long, val token: String)
    private val lock = Any()
    private val policies = mutableMapOf<Pair<String, String>, State>()
    private val audit = mutableListOf<AutonomyAuditRecord>()

    override fun policy(connectorId: String, operationName: String): AutonomyPolicy =
        snapshot(connectorId, operationName).policy

    override fun savedPolicy(connectorId: String, operationName: String): AutonomyPolicy? = synchronized(lock) {
        policies[connectorId to operationName]?.policy
    }

    override fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy) =
        setPolicyChecked(connectorId, operationName, policy)

    override fun setPolicyChecked(connectorId: String, operationName: String, policy: AutonomyPolicy) = synchronized(lock) {
        ActionPolicyDefaults.descriptor(connectorId, operationName)
        val key = connectorId to operationName
        val revision = policies[key]?.revision ?: 0L
        check(revision < Long.MAX_VALUE) { "Action policy revision is exhausted" }
        policies[key] = State(policy, revision + 1L, UUID.randomUUID().toString())
    }

    override fun snapshot(connectorId: String, operationName: String): ActionPolicySnapshot = synchronized(lock) {
        snapshotLocked(connectorId, operationName, null)
    }

    override fun snapshot(connectorId: String, operationName: String, fallbackPolicy: AutonomyPolicy): ActionPolicySnapshot = synchronized(lock) {
        snapshotLocked(connectorId, operationName, fallbackPolicy)
    }

    private fun snapshotLocked(id: String, operation: String, fallback: AutonomyPolicy?): ActionPolicySnapshot {
        val descriptor = ActionPolicyDefaults.descriptor(id, operation)
        val state = policies.getOrPut(id to operation) { State(null, 0L, UUID.randomUUID().toString()) }
        val origin = if (state.policy != null) ActionPolicyOrigin.USER else if (fallback != null)
            ActionPolicyOrigin.INHERITED_SERVER_DEFAULT else ActionPolicyOrigin.CATALOG_DEFAULT
        return ActionPolicySnapshot(id, operation, state.policy ?: fallback ?: descriptor.defaultPolicy,
            state.revision, origin, this, state.token, fallback)
    }

    override fun snapshotMatching(anchor: ActionPolicySnapshot): ActionPolicySnapshot = synchronized(lock) {
        check(anchor.owner === this) { "Action snapshot belongs to a different policy store" }
        snapshotLocked(anchor.connectorId, anchor.operationName, anchor.fallbackPolicy).also {
            check(anchor.sameAuthorizationAs(it)) { "Action policy changed; prepare and authorize a new action" }
        }
    }

    override fun setPolicyAndSnapshot(connectorId: String, operationName: String, policy: AutonomyPolicy): ActionPolicySnapshot = synchronized(lock) {
        setPolicyChecked(connectorId, operationName, policy)
        snapshotLocked(connectorId, operationName, null)
    }

    override fun admit(snapshot: ActionPolicySnapshot, approvedAsk: Boolean, validate: () -> Unit) = synchronized(lock) {
        check(snapshot.owner === this) { "Action snapshot belongs to a different policy store" }
        snapshot.requireUnconsumed()
        check(snapshot.sameAuthorizationAs(snapshotLocked(snapshot.connectorId, snapshot.operationName, snapshot.fallbackPolicy))) {
            "Action policy changed; prepare and authorize a new action"
        }
        snapshot.requireDecision(approvedAsk)
        validate()
        check(snapshot.sameAuthorizationAs(snapshotLocked(snapshot.connectorId, snapshot.operationName, snapshot.fallbackPolicy))) {
            "Action policy changed during admission"
        }
        snapshot.consume()
    }

    override fun clearPolicies(connectorId: String) = synchronized(lock) {
        val affected = policies.filterKeys { it.first == connectorId }
        check(affected.values.all { it.revision < Long.MAX_VALUE }) { "Action policy revision is exhausted" }
        affected.forEach { (key, state) -> policies[key] = State(null, state.revision + 1L, UUID.randomUUID().toString()) }
    }

    override fun appendAudit(record: AutonomyAuditRecord) { synchronized(lock) { audit.add(record.bounded()) } }
    override fun recentAudit(connectorId: String, limit: Int): List<AutonomyAuditRecord> = synchronized(lock) {
        audit.asReversed().filter { it.connectorId == connectorId }.take(limit.coerceAtLeast(0))
    }
}

/** User settings and the append-only audit share jarvys_connectors. */
class SharedPreferencesConnectorAutonomyStore internal constructor(
    private val preferences: SharedPreferences,
    authorityScope: String,
    private val auditDatabase: com.jarvys.agent.chatdb.ChatDatabaseBridge? = null,
) : ConnectorAutonomyStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE),
        context.applicationContext.packageName + ":" + ConnectorStateStore.PREFERENCES,
        com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context.applicationContext),
    )
    private val actionPolicies = PersistedActionPolicies(preferences, authorityScope)
    private val lock = actionPolicies.lock
    private val fallbackAudit = mutableListOf<AutonomyAuditRecord>()

    init {
        ActionPolicyDefaults.bindDefaultPolicyPreferences(preferences)
    }

    override fun policy(connectorId: String, operationName: String): AutonomyPolicy = snapshot(connectorId, operationName).policy
    override fun savedPolicy(connectorId: String, operationName: String): AutonomyPolicy? = actionPolicies.savedPolicy(connectorId, operationName)
    override fun setPolicy(connectorId: String, operationName: String, policy: AutonomyPolicy) = setPolicyChecked(connectorId, operationName, policy)
    override fun setPolicyChecked(connectorId: String, operationName: String, policy: AutonomyPolicy) = actionPolicies.setPolicy(connectorId, operationName, policy)
    override fun snapshot(connectorId: String, operationName: String): ActionPolicySnapshot = actionPolicies.snapshot(connectorId, operationName)
    override fun snapshot(connectorId: String, operationName: String, fallbackPolicy: AutonomyPolicy): ActionPolicySnapshot =
        actionPolicies.snapshot(connectorId, operationName, fallbackPolicy)
    override fun admit(snapshot: ActionPolicySnapshot, approvedAsk: Boolean, validate: () -> Unit) = actionPolicies.admit(snapshot, approvedAsk, validate)
    override fun snapshotMatching(anchor: ActionPolicySnapshot): ActionPolicySnapshot = actionPolicies.snapshotMatching(anchor)
    override fun setPolicyAndSnapshot(connectorId: String, operationName: String, policy: AutonomyPolicy): ActionPolicySnapshot =
        actionPolicies.setPolicyAndSnapshot(connectorId, operationName, policy)
    override fun clearPolicies(connectorId: String) { actionPolicies.clearPolicies(connectorId) }

    override fun appendAudit(record: AutonomyAuditRecord) {
        val bounded = record.bounded()
        val database = auditDatabase
        if (database != null) {
            database.appendConnectorAuditSync(bounded)
        } else synchronized(lock) { fallbackAudit.add(bounded) }
    }

    override fun recentAudit(connectorId: String, limit: Int): List<AutonomyAuditRecord> {
        val database = auditDatabase
        if (database != null) {
            return database.recentConnectorAuditSync(connectorId, limit.coerceAtLeast(0))
        }
        return synchronized(lock) {
            fallbackAudit.asReversed().filter { it.connectorId == connectorId }.take(limit.coerceAtLeast(0))
        }
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
