package com.jarvys.agent.chatdb

/** Durable regeneration locator only. It is never a runtime/lane admission capability. */
data class MemorySessionResumeReceipt internal constructor(
    val sourceGeneration: String,
    val conversationId: String,
    val ownerMessageId: String,
    val assistantMessageId: String,
    val priorRunId: String,
    val hiddenSeq: Long,
    val rootSessionId: String,
)
