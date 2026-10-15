package com.jarvys.agent.chatdb

/** One model response incorporation spanning transcript rows and optional input acknowledgement. */
data class ConversationModelTurnInput(
    val conversationId: String,
    val toolBatch: ModelToolBatchInput? = null,
    val lifecycles: List<ToolCallLifecycleWrite> = emptyList(),
    val toolResults: List<ModelToolResultInput> = emptyList(),
    val modelErrors: List<ModelErrorWrite> = emptyList(),
    val providerOutputs: List<ProviderOutputItemsWrite> = emptyList(),
    val inputMessageIds: List<String> = emptyList(),
    val inputState: String? = null,
)

data class ToolCallLifecycleWrite(val batchId: String, val callId: String, val lifecycle: String)

data class ModelErrorWrite(val ownerUserMessageId: String?, val messageIndex: Long, val errorCode: String)

data class ProviderOutputItemsWrite(val ownerUserMessageId: String, val messageIndex: Long, val itemsJson: String)

data class ConversationModelTurnResult(
    val toolBatch: ModelToolBatchSnapshot?,
    val lifecycleSequences: List<Long>,
    val toolResults: List<ToolResult?>,
    val modelErrorSequences: List<Long>,
    val providerOutputSequences: List<Long>,
    val inputMessageIdsChanged: List<String>,
)

/** Keeps the multi-repository write set atomic by invoking the existing repository transactions under one writer lease. */
internal class ConversationModelTurnWriter(
    private val database: JarvysChatDatabase,
    private val conversations: ConversationRepository,
    private val transcript: ModelTranscriptRepository,
) {
    suspend fun append(input: ConversationModelTurnInput): ChatDbResult<ConversationModelTurnResult> {
        if (input.conversationId.isBlank()) return ChatDbResult.Invalid("conversation id is required")
        if (input.toolBatch?.conversationId?.let { it != input.conversationId } == true ||
            input.toolResults.any { it.conversationId != input.conversationId }) {
            return ChatDbResult.Invalid("all model turn writes must name the same conversation")
        }
        if ((input.inputState == null) != input.inputMessageIds.isEmpty()) {
            return ChatDbResult.Invalid("input state and message IDs must be supplied together")
        }

        return try {
            database.withChatWriteTransaction {
                val batch = input.toolBatch?.let { requireSuccess(transcript.appendToolBatch(it)) }
                val lifecycleSequences = input.lifecycles.map { value ->
                    requireSuccess(transcript.setCallLifecycle(input.conversationId, value.batchId, value.callId, value.lifecycle))
                }
                val results = input.toolResults.map { requireSuccess(transcript.appendToolResult(it)) }
                val errors = input.modelErrors.map { value ->
                    requireSuccess(transcript.appendModelError(
                        input.conversationId, value.ownerUserMessageId, value.messageIndex, value.errorCode,
                    ))
                }
                val providerOutputs = input.providerOutputs.map { value ->
                    requireSuccess(transcript.appendProviderOutputItems(
                        input.conversationId, value.ownerUserMessageId, value.messageIndex, value.itemsJson,
                    ))
                }
                val incorporated = input.inputState?.let { state ->
                    requireSuccess(conversations.markInputState(input.conversationId, input.inputMessageIds, state))
                }.orEmpty()
                ChatDbResult.Success(ConversationModelTurnResult(
                    batch, lifecycleSequences, results, errors, providerOutputs, incorporated,
                ))
            }
        } catch (abort: CompositeWriteAbort) {
            abort.result
        }
    }

    private fun <T> requireSuccess(result: ChatDbResult<T>): T = when (result) {
        is ChatDbResult.Success -> result.value
        ChatDbResult.NotFound -> throw CompositeWriteAbort(ChatDbResult.NotFound)
        ChatDbResult.Tombstoned -> throw CompositeWriteAbort(ChatDbResult.Tombstoned)
        is ChatDbResult.Conflict -> throw CompositeWriteAbort(ChatDbResult.Conflict(result.reason))
        is ChatDbResult.Invalid -> throw CompositeWriteAbort(ChatDbResult.Invalid(result.reason))
    }

    private class CompositeWriteAbort(val result: ChatDbResult<Nothing>) : RuntimeException()
}
