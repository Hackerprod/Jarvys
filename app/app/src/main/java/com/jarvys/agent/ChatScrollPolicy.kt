package com.jarvys.agent

enum class ChatScrollTrigger { CONVERSATION_OPENED, USER_MESSAGE_SENT, STREAM_UPDATE, USER_SCROLLED, JUMP_TO_END }

data class ChatScrollDecision(val shouldScroll: Boolean, val animate: Boolean, val followEnd: Boolean)

/** Pure follow-tail state machine used by the Compose chat timeline. */
object ChatScrollPolicy {
    fun decide(trigger: ChatScrollTrigger, wasFollowingEnd: Boolean, isAtEnd: Boolean): ChatScrollDecision = when (trigger) {
        ChatScrollTrigger.CONVERSATION_OPENED -> ChatScrollDecision(true, animate = false, followEnd = true)
        ChatScrollTrigger.USER_MESSAGE_SENT -> ChatScrollDecision(true, animate = true, followEnd = true)
        ChatScrollTrigger.STREAM_UPDATE -> ChatScrollDecision(wasFollowingEnd, animate = false, followEnd = wasFollowingEnd)
        ChatScrollTrigger.USER_SCROLLED -> ChatScrollDecision(false, animate = false, followEnd = isAtEnd)
        ChatScrollTrigger.JUMP_TO_END -> ChatScrollDecision(true, animate = true, followEnd = true)
    }
}
