package com.jarvys.agent.proactive

data class ProactiveSuggestedReply(val label: String, val text: String)

data class ProactiveReplyClaim(val text: String, val threadKey: String, val userMessageId: String = "")

data class ProactiveThreadMessage(val text: String, val timestampMillis: Long, val fromAssistant: Boolean)
