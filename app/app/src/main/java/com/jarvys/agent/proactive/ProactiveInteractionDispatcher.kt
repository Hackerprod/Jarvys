package com.jarvys.agent.proactive

import android.content.Context
import com.jarvys.agent.AgentErrorReporter
import android.util.Log
import com.jarvys.agent.AgentForegroundService
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.chatdb.StoredMessage
import com.jarvys.agent.tasks.ScheduledTaskConversation
import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Routes a user-selected reply into Central as an ordinary turn, never the silent review loop. The reply row
 * carries the thread (and the notice it answers) so the turn can recover a notice that compaction has dropped.
 */
object ProactiveInteractionDispatcher {
    private const val SESSION = WorkspaceIdentity.CENTRAL_SESSION

    fun submitSuggestedReply(context: Context, messageId: String, index: Int): Boolean {
        val claim = prepareSuggestedReply(context, messageId, index) ?: return false
        return launchPreparedTurn(context.applicationContext, claim)
    }

    fun prepareSuggestedReply(context: Context, messageId: String, index: Int): ProactiveReplyClaim? {
        val app = context.applicationContext
        val store = LocalRunStore(app)
        val claim = store.claimProactiveSuggestedReply(SESSION, messageId, index) ?: return null
        AgentRunUiState.markProactiveRepliesUsed(messageId)
        val userMessageId = store.appendProactiveUserMessage(SESSION, claim.text, claim.threadKey, messageId)
        return claim.copy(userMessageId = userMessageId)
    }

    fun submitInlineReply(context: Context, messageId: String, threadToken: String, text: String): Boolean {
        val claim = prepareInlineReply(context, messageId, threadToken, text) ?: return false
        return launchPreparedTurn(context.applicationContext, claim)
    }

    /** [messageId] is a message of the notified thread; [threadToken] must match that row's thread. */
    fun prepareInlineReply(context: Context, messageId: String, threadToken: String, text: String): ProactiveReplyClaim? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null
        val app = context.applicationContext
        val store = LocalRunStore(app)
        val threadKey = store.readStoredMessage(SESSION, messageId)?.proactiveThreadKey
            ?.takeIf { ProactiveThreadKey.fingerprint(it) == threadToken }
            ?: return null
        val userMessageId = store.appendProactiveUserMessage(SESSION, normalized, threadKey, null)
        return ProactiveReplyClaim(normalized, threadKey, userMessageId)
    }

    private fun launchPreparedTurn(
        context: Context,
        claim: ProactiveReplyClaim,
    ): Boolean {
        return runCatching {
            AgentForegroundService.startRealAgentFromStoredChatMessage(context, SESSION, claim.userMessageId)
            true
        }.getOrDefault(false)
    }

    fun refreshNotification(context: Context, threadKey: String, latestMessageId: String) {
        // Task threads are notified by TaskNotifier, which does not refresh after a reply.
        if (ScheduledTaskConversation.isTaskThread(threadKey)) return
        val store = LocalRunStore(context.applicationContext)
        val thread = store.readProactiveThreadMessages(SESSION, threadKey)
        val notice = thread.lastOrNull { it.proactiveDecisionId != null }
        val latestAssistant = thread.lastOrNull { it.role == "assistant" } ?: return
        ProactiveNotifier.refreshThread(
            context.applicationContext,
            threadKey,
            latestMessageId.ifBlank { latestAssistant.messageId },
            latestAssistant.text,
            thread.map(StoredMessage::toThreadMessage),
            notice?.messageId.orEmpty(),
            notice?.suggestedReplies.orEmpty().map { ProactiveSuggestedReply(it.label, it.text) },
            notice?.suggestedRepliesConsumedSeq != null,
        )
    }
}

class ProactiveActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (!com.jarvys.agent.SingleSessionReset.isCurrent(intent)) return
        val action = intent.action
        if (action != ACTION_SUGGESTED_REPLY && action != ACTION_INLINE_REPLY) return
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        val index = intent.getIntExtra(EXTRA_REPLY_INDEX, -1)
        val threadToken = intent.getStringExtra(EXTRA_THREAD_TOKEN).orEmpty()
        val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(EXTRA_INLINE_REPLY)?.toString().orEmpty()
        val pendingResult = goAsync()
        try {
            ProactiveReplyReceiverExecutor.execute {
                try {
                    when (action) {
                        ACTION_SUGGESTED_REPLY -> {
                            ProactiveInteractionDispatcher.submitSuggestedReply(
                                context.applicationContext, messageId, index,
                            )
                        }
                        ACTION_INLINE_REPLY -> {
                            if (threadToken.isNotBlank() && messageId.isNotBlank()) {
                                ProactiveInteractionDispatcher.submitInlineReply(
                                    context.applicationContext, messageId, threadToken, text)
                            }
                        }
                    }
                } catch (failure: RuntimeException) {
                    Log.e("JarvysProactive", "Could not handle notification reply", failure)
                    if (failure !is java.util.concurrent.CancellationException) {
                        AgentErrorReporter.reportChatDatabaseFailure(context, failure)
                    }
                } finally {
                    pendingResult?.finish()
                    Thread.interrupted()
                }
            }
        } catch (failure: RuntimeException) {
            pendingResult?.finish()
            Log.e("JarvysProactive", "Could not queue notification reply", failure)
        }
    }

    companion object {
        const val ACTION_SUGGESTED_REPLY = "com.jarvys.agent.proactive.SUGGESTED_REPLY"
        const val ACTION_INLINE_REPLY = "com.jarvys.agent.proactive.INLINE_REPLY"
        const val EXTRA_MESSAGE_ID = "proactive_message_id"
        const val EXTRA_REPLY_INDEX = "proactive_reply_index"
        const val EXTRA_THREAD_TOKEN = "proactive_thread_token"
        const val EXTRA_INLINE_REPLY = "proactive_inline_reply"
    }
}

private object ProactiveReplyReceiverExecutor {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JarvysProactiveReplies").apply { isDaemon = true }
    }

    fun execute(task: Runnable) = executor.execute(task)
}
