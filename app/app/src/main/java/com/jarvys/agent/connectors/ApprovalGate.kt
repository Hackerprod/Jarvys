package com.jarvys.agent.connectors

import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.CancellationToken
import android.content.Context
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class ApprovalDecision {
    APPROVED, APPROVED_ALLOW_ALWAYS, APPROVED_ALLOW_FAILED, DENIED, PERMISSION_DENIED,
    PERMISSION_FALLBACK_LAUNCHED, ACTION_FAILED, EXPIRED, CANCELLED,
}

enum class ApprovalIntentKind { CONTACT_INSERT, DIAL, SMS_COMPOSE, EMAIL_COMPOSE }

data class ApprovalIntentSpec(
    val kind: ApprovalIntentKind,
    val dataUri: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

/** Resource-backed text for connector UI; fallback text keeps JVM connector fakes context-free. */
data class ConnectorUiText(
    val resourceId: Int = 0,
    val arguments: List<Any> = emptyList(),
    val fallback: String,
) {
    fun resolve(context: Context): String = if (resourceId == 0) fallback
        else context.getString(resourceId, *arguments.map { argument ->
            if (argument is ConnectorUiText) argument.resolve(context) else argument
        }.toTypedArray())
}

data class ApprovalSummary(
    val title: String,
    val lines: List<String>,
    val permission: String? = null,
    val activityIntent: ApprovalIntentSpec? = null,
    val permissionDeniedIntent: ApprovalIntentSpec? = null,
    val allowAlwaysAvailable: Boolean = false,
    val autonomyConnectorId: String? = null,
    val autonomyOperationName: String? = null,
    val localizedTitle: ConnectorUiText? = null,
    val localizedLines: List<ConnectorUiText>? = null,
    val permissionLabel: ConnectorUiText? = null,
    val compactSummary: ConnectorUiText? = null,
    val requester: String? = null,
    val requesterColorKey: String? = null,
)

interface ApprovalPresenter {
    fun show(id: String, summary: ApprovalSummary)
    fun update(id: String, decision: ApprovalDecision)
}

/** Blocks the tool worker, never the UI thread. Only the first resolution has effect. */
class ApprovalGate(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val presenter: ApprovalPresenter,
) {
    private class Pending(val id: String, val token: CancellationToken) {
        val latch = CountDownLatch(1)
        val decision = AtomicReference<ApprovalDecision?>(null)
        var uiActionStarted = false
    }

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    fun request(summary: ApprovalSummary, token: CancellationToken): ApprovalDecision {
        token.throwIfCancelled()
        val request = Pending(UUID.randomUUID().toString(), token)
        synchronized(lock) { pending[request.id] = request }
        try {
            presenter.show(request.id, summary)
        } catch (failure: RuntimeException) {
            synchronized(lock) { pending.remove(request.id) }
            throw failure
        }
        val unregisterCancellation = token.registerCancelAction {
            complete(request.id, ApprovalDecision.CANCELLED)
        }
        try {
            if (!request.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                complete(request.id, ApprovalDecision.EXPIRED)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            complete(request.id, ApprovalDecision.CANCELLED)
        } finally {
            unregisterCancellation.run()
            synchronized(lock) { pending.remove(request.id) }
        }
        token.throwIfCancelled()
        return request.decision.get() ?: ApprovalDecision.DENIED
    }

    fun resolve(id: String, decision: ApprovalDecision): Boolean = complete(id, decision)

    fun permissionFor(id: String): String? = synchronized(lock) {
        pending[id]?.let { request ->
            // The presenter owns the summary; the permission is also carried by the chat event.
            AgentRunUiState.state.value.events.lastOrNull { it.approvalId == request.id }?.approvalPermission
        }
    }

    fun isPending(id: String): Boolean = synchronized(lock) {
        pending[id]?.let { it.decision.get() == null && !it.token.isCancellationRequested } ?: false
    }

    /** Launch an asynchronous permission prompt once, without resolving its approval early. */
    fun beginUiAction(id: String, action: () -> Unit): Boolean = synchronized(lock) {
        val request = pending[id] ?: return@synchronized false
        if (request.decision.get() != null || request.uiActionStarted) return@synchronized false
        var launched = false
        request.token.runIfActive {
            if (!request.token.isCancellationRequested) {
                request.uiActionStarted = true
                action()
                launched = true
            }
        }
        if (!launched && request.token.isCancellationRequested) finish(request, ApprovalDecision.CANCELLED)
        launched
    }

    /** Perform optional consent changes only while this exact approval is pending. */
    fun resolveFromUi(id: String, decisionProvider: () -> ApprovalDecision): Boolean = synchronized(lock) {
        val request = pending[id] ?: return@synchronized false
        if (request.decision.get() != null) return@synchronized false
        // Cancellation can happen while the presenter is showing the card, before request()
        // has registered its cancellation callback. Never run consent side effects in that gap.
        var decision: ApprovalDecision? = null
        request.token.runIfActive {
            if (!request.token.isCancellationRequested) decision = decisionProvider()
        }
        finish(request, decision ?: ApprovalDecision.CANCELLED)
        decision != null
    }

    private fun finish(request: Pending, decision: ApprovalDecision) {
        request.decision.set(decision)
        try { presenter.update(request.id, decision) } finally { request.latch.countDown() }
    }

    private fun complete(id: String, decision: ApprovalDecision): Boolean = resolveFromUi(id) { decision }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        @JvmField val INSTANCE: ApprovalGate = ApprovalGate(presenter = object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) = AgentRunUiState.showApproval(
                id, summary.title, summary.lines, summary.permission, summary.activityIntent,
                summary.permissionDeniedIntent, summary.allowAlwaysAvailable,
                summary.autonomyConnectorId, summary.autonomyOperationName,
                summary.localizedTitle, summary.localizedLines, summary.permissionLabel,
                summary.compactSummary, summary.requester, summary.requesterColorKey,
            ).also { ApprovalNotificationCenter.show(id, summary) }

            override fun update(id: String, decision: ApprovalDecision) {
                AgentRunUiState.updateApproval(id, decision.name)
                ApprovalNotificationCenter.dismiss(id)
            }
        })
    }
}
