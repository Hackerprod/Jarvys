package com.jarvys.agent.connectors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.MainActivity
import com.jarvys.agent.R

/** Renders the existing ApprovalGate request; approve/reject actions resolve that same gate. */
object ApprovalNotificationCenter {
    private const val CHANNEL_ID = "jarvys_action_approvals"
    private const val TAG = "jarvys_action_approval"
    @Volatile private var appContext: Context? = null

    fun attach(context: Context) { appContext = context.applicationContext }

    fun show(id: String, summary: ApprovalSummary) {
        val app = appContext ?: return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        runCatching {
            val localized = AppLanguageRuntime.localizedContext(app)
            ensureChannel(app, localized)
            val title = summary.localizedTitle?.resolve(localized) ?: summary.title
            val lines = summary.localizedLines?.map { it.resolve(localized) } ?: summary.lines
            val detail = (listOf(title) + lines + localized.getString(R.string.proactive_notification_approval_warning))
                .filter(String::isNotBlank).joinToString("\n\n")
            val openIntent = Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, AgentRunUiState.state.value.sessionId.orEmpty())
                .putExtra(EXTRA_APPROVAL_ID, id)
                .setData(Uri.parse("jarvys://approval/${Uri.encode(id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPendingIntent = PendingIntent.getActivity(app, id.hashCode(), openIntent, pendingFlags())
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvys)
                .setContentTitle(localized.getString(R.string.proactive_notification_approval_title))
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openPendingIntent)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addAction(approvalAction(app, id, ApprovalDecision.APPROVED,
                    localized.getString(R.string.proactive_notification_approve)))
                .addAction(approvalAction(app, id, ApprovalDecision.DENIED,
                    localized.getString(R.string.proactive_notification_reject)))
                .build()
            NotificationManagerCompat.from(app).notify(TAG, id.hashCode(), notification)
        }
    }

    fun dismiss(id: String) {
        appContext?.let { NotificationManagerCompat.from(it).cancel(TAG, id.hashCode()) }
    }

    private fun approvalAction(app: Context, id: String, decision: ApprovalDecision, label: String) =
        NotificationCompat.Action.Builder(0, label,
            PendingIntent.getBroadcast(app, (id.hashCode() * 31) + decision.ordinal,
                Intent(app, ApprovalActionReceiver::class.java)
                    .setAction(ApprovalActionReceiver.ACTION_RESOLVE)
                    .putExtra(EXTRA_APPROVAL_ID, id)
                    .putExtra(ApprovalActionReceiver.EXTRA_DECISION, decision.name)
                    .setData(Uri.parse("jarvys://approval/${Uri.encode(id)}/${decision.name}")), pendingFlags()))
            .build()

    private fun ensureChannel(context: Context, localized: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            localized.getString(R.string.proactive_notification_approval_title), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    const val EXTRA_APPROVAL_ID = "approval_request_id"
}

object ApprovalNotificationActionResolver {
    fun resolve(
        gate: ApprovalGate,
        id: String,
        decision: ApprovalDecision,
        performApprovedAction: () -> Boolean = { true },
    ): Boolean = gate.resolveFromUi(id) {
        if (decision == ApprovalDecision.APPROVED && !performApprovedAction()) {
            ApprovalDecision.ACTION_FAILED
        } else decision
    }
}

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESOLVE) return
        val id = intent.getStringExtra(ApprovalNotificationCenter.EXTRA_APPROVAL_ID).orEmpty()
        val decision = when (intent.getStringExtra(EXTRA_DECISION)) {
            ApprovalDecision.APPROVED.name -> ApprovalDecision.APPROVED
            ApprovalDecision.DENIED.name -> ApprovalDecision.DENIED
            else -> return
        }
        val event = AgentRunUiState.state.value.events.lastOrNull { it.kind == "approval" && it.approvalId == id }
        val resolved = ApprovalNotificationActionResolver.resolve(ApprovalGate.INSTANCE, id, decision) {
            if (decision == ApprovalDecision.DENIED) true
            else {
                val spec = event?.approvalIntent
                if (spec == null) true else runCatching {
                    context.startActivity(com.jarvys.agent.approvalIntent(spec).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            }
        }
        if (resolved) ApprovalNotificationCenter.dismiss(id)
    }

    companion object {
        const val ACTION_RESOLVE = "com.jarvys.agent.connectors.RESOLVE_APPROVAL"
        const val EXTRA_DECISION = "approval_decision"
    }
}
