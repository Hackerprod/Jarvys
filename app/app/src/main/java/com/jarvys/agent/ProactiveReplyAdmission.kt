package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.proactive.refreshVisibleCentral
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import android.os.Handler
import android.os.Looper
import com.jarvys.agent.chatdb.ProactiveReplyAcceptance
import com.jarvys.agent.chatdb.ProactiveReplySourceSnapshot
import com.jarvys.agent.proactive.ProactiveActionReceiver
import com.jarvys.agent.proactive.ProactiveReplyNotificationIdentity
import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.util.Collections
import java.util.IdentityHashMap

/** Native interaction ownership only. Persisted source/action IDs are evidence, never launch permission. */
object ProactiveReplyAdmission {
    private val active = Collections.newSetFromMap(IdentityHashMap<NativeInteraction, Boolean>())
    private val lock = Any()
    private const val SESSION = WorkspaceIdentity.CENTRAL_SESSION

    class NativeInteraction private constructor(
        private val uiOwner: LifecycleOwner?,
        private val displayed: AgentRunUiEvent?,
        private val delivery: ProactiveActionReceiver.NativeDelivery?,
        private val notification: ProactiveReplyNotificationIdentity.Binding?,
        val kind: String,
        val sourceMessageId: String,
        val selectedOrdinal: Int?,
        private val enteredText: String?,
        private val uiEpoch: Long?,
        private val sessionEpoch: Long?,
    ) : AutoCloseable {
        private var bound: NativeAction? = null
        private var begun = false
        private var registeredPermit: CentralSubmissionAdmission.LivePermit? = null
        private var lifecycleObserver: LifecycleEventObserver? = null
        private var committed: com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt? = null

        fun requireCurrent() = synchronized(lock) {
            check(active.contains(this)) { "Native reply interaction expired" }
            if (uiOwner != null) {
                check(uiEpoch != null && UserDecisionUiAvailability.isCurrentVisibleEpoch(uiEpoch) &&
                    sessionEpoch != null && AgentRunUiState.isCurrentVisibleSessionEpoch(SESSION, sessionEpoch)) {
                    "Reply presentation changed since the captured tap"
                }
                check(uiOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "Reply UI is no longer active" }
                check(displayed != null && selectedOrdinal != null &&
                    AgentRunUiState.containsExactProactiveSelection(SESSION, displayed, selectedOrdinal)) {
                    "Displayed reply selection changed"
                }
            } else {
                checkNotNull(delivery).requireCurrent()
            }
        }

        /** One conversion from actual interaction to a canonically bound native persistence attempt. */
        fun bind(source: ProactiveReplySourceSnapshot): NativeAction = synchronized(lock) {
            requireCurrent()
            check(!begun && bound == null) { "Native reply already prepared" }
            check(source.sourceMessageId == sourceMessageId)
            val option = if (kind == "OPTION") source.suggestedReplies.getOrNull(checkNotNull(selectedOrdinal))
                ?: throw IllegalArgumentException("Reply option is missing") else null
            val text = option?.text?.trim() ?: checkNotNull(enteredText).trim()
            check(text.isNotEmpty()) { "Reply text is empty" }
            if (displayed != null) {
                check(displayed.proactiveThreadKey == source.threadKey && displayed.text == source.text)
                val shown = displayed.proactiveReplies.getOrNull(checkNotNull(selectedOrdinal))
                check(shown != null && shown.text.trim() == text && shown.label == option!!.label) {
                    "Displayed reply content changed"
                }
            }
            val id = ProactiveReplyAcceptance.actionId(source.physicalGeneration, SESSION,
                source.threadKey, source.sourceMessageId, kind)
            notification?.let {
                check(it.physicalGeneration == source.physicalGeneration && it.threadKey == source.threadKey &&
                    it.sourceMessageId == source.sourceMessageId && it.actionKind == kind &&
                    it.actionId == id && it.sourceFingerprint == source.sourceFingerprint && it.selectedOrdinal == selectedOrdinal) { "Notification reply source changed" }
            }
            begun = true
            NativeAction(this, kind, source.sourceMessageId, source.threadKey, source.physicalGeneration,
                selectedOrdinal, text, id, ProactiveReplyAcceptance.submissionId(id), source.sourceFingerprint)
                .also { bound = it }
        }

        internal fun requireBound(action: NativeAction) = synchronized(lock) {
            requireCurrent()
            check(begun && bound === action) { "Native reply attempt changed" }
        }

        internal fun recordCommitted(action: NativeAction, result: com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt) = synchronized(lock) {
            check(bound === action && begun)
            check(result.receipt.snapshot.submissionId == action.submissionId)
            check(committed == null || committed === result)
            committed = result // Evidence survives a later lifecycle cancellation; it is not a permit.
        }

        internal fun requireNewCommit(action: NativeAction, result: com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt) = synchronized(lock) {
            requireBound(action)
            check(committed === result && result.created) { "Only the original new native commit can dispatch" }
        }

        internal fun registerPermit(action: NativeAction, result: com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt,
            factory: java.util.function.Supplier<CentralSubmissionAdmission.LivePermit>): CentralSubmissionAdmission.LivePermit = synchronized(lock) {
            requireNewCommit(action, result)
            check(registeredPermit == null) { "Reply permit already registered" }
            val permit = factory.get()
            try {
                check(permit.submissionId == action.submissionId && permit.storageGeneration == action.physicalGeneration &&
                    permit.sourceSha256 == result.receipt.snapshot.sourceSha256)
                registeredPermit = permit
                permit
            } catch (failure: RuntimeException) {
                CentralSubmissionAdmission.invalidate(permit)
                throw failure
            }
        }

        internal fun revokePending() {
            synchronized(lock) {
                active.remove(this)
                registeredPermit?.let(CentralSubmissionAdmission::invalidate)
            }
            close()
        }

        fun committedEvidence(): com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt? = synchronized(lock) { committed }
        internal fun observeLifecycle() {
            val owner = uiOwner ?: return
            check(Looper.myLooper() == Looper.getMainLooper()) { "Displayed reply must be captured on the UI thread" }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) close()
            }
            synchronized(lock) { lifecycleObserver = observer }
            owner.lifecycle.addObserver(observer)
        }
        override fun close() {
            val observer = synchronized(lock) {
                active.remove(this)
                lifecycleObserver.also { lifecycleObserver = null }
            }
            if (observer != null && uiOwner != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) uiOwner.lifecycle.removeObserver(observer)
                else Handler(Looper.getMainLooper()).post { uiOwner.lifecycle.removeObserver(observer) }
            }
        }

        companion object {
            internal fun displayed(owner: LifecycleOwner, event: AgentRunUiEvent, index: Int,
                uiEpoch: Long, sessionEpoch: Long): NativeInteraction =
                NativeInteraction(owner, event, null, null, "OPTION", event.messageId, index, null, uiEpoch, sessionEpoch)
            internal fun receiver(delivery: ProactiveActionReceiver.NativeDelivery,
                binding: ProactiveReplyNotificationIdentity.Binding, text: String): NativeInteraction =
                NativeInteraction(null, null, delivery, binding, binding.actionKind, binding.sourceMessageId,
                    binding.selectedOrdinal, text, null, null)
        }
    }

    class NativeAction internal constructor(
        private val owner: NativeInteraction,
        val kind: String, val sourceMessageId: String, val threadKey: String,
        val physicalGeneration: String, val selectedOrdinal: Int?, val text: String,
        val actionId: String, val submissionId: String, val sourceFingerprint: String,
    ) {
        val processId: String get() = com.jarvys.agent.crew.CrewProcessIdentity.ID
        fun requireCurrent() = owner.requireBound(this)
        fun recordCommitted(result: com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt) = owner.recordCommitted(this, result)
        fun requireNewCommit(result: com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt) = owner.requireNewCommit(this, result)
        fun registerPermit(result: com.jarvys.agent.chatdb.ProactiveReplyAppendReceipt,
            factory: java.util.function.Supplier<CentralSubmissionAdmission.LivePermit>): CentralSubmissionAdmission.LivePermit =
            owner.registerPermit(this, result, factory)
    }

    /** Denial-only snapshot at native STOP ingress. Later interactions are deliberately unaffected. */
    @JvmStatic fun cancelCapturedInteractions(sessionId: String) {
        if (sessionId != SESSION) return
        synchronized(lock) {
            active.toList().forEach { it.revokePending() }
            CentralSubmissionAdmission.retireCapturedProactiveReplies(sessionId)
        }
    }

    /** Native persistence seam; caller must retain the real interaction until dispatch or failure. */
    internal fun prepare(context: Context, interaction: NativeInteraction): CentralSubmissionAdmission.LivePermit? {
        check(ProactiveReplyHandoffPolicy.admissionEnabled()) { "Native reply admission disabled" }
        interaction.requireCurrent()
        val source = LocalRunStore(context.applicationContext).readProactiveReplySource(interaction.sourceMessageId, null)
            ?: return null
        val action = interaction.bind(source)
        return AgentForegroundService.admitProactiveReply(context.applicationContext, action)
    }

    @JvmStatic fun submit(context: Context, interaction: NativeInteraction): Boolean {
        val app = context.applicationContext
        try {
            val permit = prepare(app, interaction) ?: return false
            AgentForegroundService.startCentralSubmission(app, permit, arrayListOf())
            return true
        } finally {
            // Committed acceptance survives registration/dispatch failures. Refresh never retries it.
            interaction.committedEvidence()?.let { result ->
                runCatching { LocalRunStore(app).refreshVisibleCentral() }
                runCatching { com.jarvys.agent.proactive.ProactiveInteractionDispatcher.refreshNotification(
                    app, result.threadKey, result.receipt.snapshot.userMessageId) }
            }
        }
    }

    @JvmStatic fun captureDisplayedOption(owner: LifecycleOwner, event: AgentRunUiEvent, index: Int): NativeInteraction? {
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            !AgentRunUiState.containsExactProactiveSelection(SESSION, event, index)) return null
        val uiEpoch = UserDecisionUiAvailability.captureVisibleEpoch() ?: return null
        val sessionEpoch = AgentRunUiState.captureVisibleSessionEpoch(SESSION) ?: return null
        return synchronized(lock) {
            val interaction = NativeInteraction.displayed(owner, event, index, uiEpoch, sessionEpoch)
            active.add(interaction)
            try { interaction.observeLifecycle(); interaction.requireCurrent(); interaction }
            catch (obsolete: IllegalStateException) { interaction.close(); null }
        }
    }

    @JvmStatic fun captureReceiver(delivery: ProactiveActionReceiver.NativeDelivery): NativeInteraction {
        delivery.requireCurrent()
        return synchronized(lock) {
            val interaction = NativeInteraction.receiver(delivery, delivery.binding, delivery.text)
            active.add(interaction)
            try { interaction.requireCurrent(); interaction }
            catch (obsolete: RuntimeException) { active.remove(interaction); throw obsolete }
        }
    }
}
