package com.jarvys.agent.proactive

import android.content.Context
import android.util.Log
import com.jarvys.agent.AgentForegroundService
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.LocalRunStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Routes a user-selected reply into the ordinary chat/service run path, never the silent review loop. */
object ProactiveInteractionDispatcher {
    fun submitSuggestedReply(context: Context, messageId: String, index: Int): Boolean {
        val claim = prepareSuggestedReply(context, messageId, index) ?: return false
        return launchPreparedTurn(context.applicationContext, claim)
    }

    fun prepareSuggestedReply(context: Context, messageId: String, index: Int): ProactiveReplyClaim? {
        val app = context.applicationContext
        val store = LocalRunStore(app)
        val claim = store.claimProactiveSuggestedReply(ProactiveConversation.SESSION_ID, messageId, index) ?: return null
        AgentRunUiState.markProactiveRepliesUsed(messageId)
        val userMessageId = store.appendProactiveUserMessage(
            ProactiveConversation.SESSION_ID, claim.text, claim.threadKey, messageId,
        )
        return claim.copy(userMessageId = userMessageId)
    }

    fun submitInlineReply(context: Context, threadToken: String, text: String): Boolean {
        val claim = prepareInlineReply(context, threadToken, text) ?: return false
        return launchPreparedTurn(context.applicationContext, claim)
    }

    fun prepareInlineReply(context: Context, threadToken: String, text: String): ProactiveReplyClaim? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null
        val app = context.applicationContext
        val store = LocalRunStore(app)
        val threadKey = store.readConversationTimeline(ProactiveConversation.SESSION_ID)
            .lastOrNull { it.proactiveThreadKey?.let(ProactiveThreadKey::fingerprint) == threadToken }
            ?.proactiveThreadKey
            ?: return null
        val userMessageId = store.appendProactiveUserMessage(
            ProactiveConversation.SESSION_ID, normalized, threadKey, null,
        )
        return ProactiveReplyClaim(normalized, threadKey, userMessageId)
    }

    private fun launchPreparedTurn(
        context: Context,
        claim: ProactiveReplyClaim,
    ): Boolean {
        return runCatching {
            AgentForegroundService.startRealAgentFromStoredChatMessage(
                context, ProactiveConversation.SESSION_ID, claim.userMessageId,
            )
            true
        }.getOrDefault(false)
    }

    fun refreshNotification(context: Context, threadKey: String, latestMessageId: String) {
        val store = LocalRunStore(context.applicationContext)
        val timeline = store.readConversationTimeline(ProactiveConversation.SESSION_ID)
            .filter { it.proactiveThreadKey == threadKey }
        val messages = timeline.map { ProactiveThreadMessage(it.text, it.timestampMillis, it.kind == "assistant") }
        val notice = timeline.lastOrNull { it.proactiveNotice }
        val latestAssistant = timeline.lastOrNull { it.kind == "assistant" } ?: return
        ProactiveNotifier.refreshThread(
            context.applicationContext,
            threadKey,
            latestMessageId.ifBlank { latestAssistant.messageId },
            latestAssistant.text,
            messages,
            notice?.messageId.orEmpty(),
            notice?.proactiveReplies.orEmpty(),
            notice?.proactiveRepliesUsed ?: false,
        )
    }
}

class ProactiveActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
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
                            if (threadToken.isNotBlank()) {
                                ProactiveInteractionDispatcher.submitInlineReply(context.applicationContext, threadToken, text)
                            }
                        }
                    }
                } catch (failure: RuntimeException) {
                    Log.e("JarvysProactive", "Could not handle notification reply", failure)
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (failure: RuntimeException) {
            pendingResult.finish()
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
