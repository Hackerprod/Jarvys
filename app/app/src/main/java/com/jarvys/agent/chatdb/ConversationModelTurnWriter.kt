package com.jarvys.agent.chatdb

/** One persisted transcript row in original checkpoint order. */
sealed interface ModelTranscriptWrite

data class ModelTranscriptBatchWrite(val input: ModelToolBatchInput) : ModelTranscriptWrite
data class ModelTranscriptLifecycleWrite(val input: ToolCallLifecycleWrite) : ModelTranscriptWrite
data class ModelTranscriptResultWrite(val input: ModelToolResultInput) : ModelTranscriptWrite
data class ModelTranscriptErrorWrite(val input: ModelErrorWrite) : ModelTranscriptWrite
data class ModelTranscriptProviderOutputWrite(val input: ProviderOutputItemsWrite) : ModelTranscriptWrite
data class ModelArtifactWrite(
    val artifactId: String,
    val relativePath: String,
    val sha256: String,
    val chars: Long,
    val bytes: Long,
    val mimeType: String = "text/plain",
    val state: String = ModelArtifactState.RETAINED,
    val stateReason: String? = null,
)
data class ModelTranscriptArtifactWrite(val input: ModelArtifactWrite) : ModelTranscriptWrite

/** One model response incorporation spanning ordered transcript rows and optional input ACK. */
data class ConversationModelTurnInput(
    val conversationId: String,
    val writes: List<ModelTranscriptWrite> = emptyList(),
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

/** One terminal STOP/failure checkpoint for a queued submission and its follow-up receipt. */
data class StoppedSubmissionInput(
    val conversationId: String,
    val followUpRequestId: String? = null,
    val ownerUserMessageId: String? = null,
    val userMessageContentIfMissing: String? = null,
    val assistantMessage: String,
    val conversationKind: String = "chat",
)

data class StoppedSubmissionResult(
    val ownerUserMessageId: String?,
    val assistantMessage: StoredMessage,
)

/** Keeps the multi-repository write set atomic by invoking the existing repository transactions under one writer lease. */
internal class ConversationModelTurnWriter(
    private val database: JarvysChatDatabase,
    private val conversations: ConversationRepository,
    private val transcript: ModelTranscriptRepository,
) {
    /** Commits steering admission and its user-row dispatch before any provider callback runs. */
    suspend fun appendAndDispatchInterruptRequest(
        conversationId: String,
        content: String,
        skills: List<String>,
    ): ChatDbResult<DispatchedInterrupt> {
        return try {
            database.withChatWriteTransaction {
                val request = requireSuccess(conversations.appendInterruptRequest(conversationId, content, skills))
                ChatDbResult.Success(requireSuccess(conversations.dispatchInterruptRequest(conversationId, request.requestId)))
            }
        } catch (abort: CompositeWriteAbort) {
            abort.result
        }
    }

    /**
     * Atomically dispatches an optional steering receipt, records the failed assistant result, and
     * resolves both the owning user row and the receipt. Keeping these operations together means a
     * stopped follow-up cannot be recovered after its failure bubble was written.
     */
    suspend fun persistStoppedSubmission(input: StoppedSubmissionInput): ChatDbResult<StoppedSubmissionResult> {
        if (input.conversationId.isBlank()) return ChatDbResult.Invalid("conversation id is required")
        if (input.assistantMessage.isBlank()) return ChatDbResult.Invalid("terminal assistant message is required")
        if (input.conversationKind.isBlank()) return ChatDbResult.Invalid("conversation kind is required")

        return try {
            database.withChatWriteTransaction {
                var ownerMessageId = input.ownerUserMessageId?.takeIf(String::isNotBlank)
                if (ownerMessageId == null && input.userMessageContentIfMissing != null) {
                    ownerMessageId = requireSuccess(conversations.appendMessage(AppendMessageInput(
                        conversationId = input.conversationId,
                        role = "user",
                        parts = listOf(MessagePartInput("text", input.userMessageContentIfMissing)),
                        conversationKind = input.conversationKind,
                    ))).messageId
                }

                val followUpRequestId = input.followUpRequestId?.takeIf(String::isNotBlank)
                if (followUpRequestId != null) {
                    when (val dispatched = conversations.dispatchInterruptRequest(input.conversationId, followUpRequestId)) {
                        is ChatDbResult.Success -> Unit
                        ChatDbResult.NotFound -> Unit // It was already removed or never admitted.
                        ChatDbResult.Tombstoned -> throw CompositeWriteAbort(ChatDbResult.Tombstoned)
                        is ChatDbResult.Conflict -> throw CompositeWriteAbort(dispatched)
                        is ChatDbResult.Invalid -> throw CompositeWriteAbort(dispatched)
                    }
                }

                val failed = requireSuccess(conversations.appendMessage(AppendMessageInput(
                    conversationId = input.conversationId,
                    role = "assistant",
                    parts = listOf(MessagePartInput("text", input.assistantMessage)),
                    status = "FAILED",
                    ownerUserMessageId = ownerMessageId,
                    conversationKind = input.conversationKind,
                )))
                val resolvedIds = listOfNotNull(ownerMessageId, followUpRequestId).distinct()
                if (resolvedIds.isNotEmpty())
                    requireSuccess(conversations.markInputState(input.conversationId, resolvedIds, "resolved"))
                ChatDbResult.Success(StoppedSubmissionResult(ownerMessageId, failed))
            }
        } catch (abort: CompositeWriteAbort) {
            abort.result
        }
    }

    suspend fun append(input:ConversationModelTurnInput):ChatDbResult<ConversationModelTurnResult> = appendForMission(input,null)

    internal suspend fun appendForMission(input: ConversationModelTurnInput,missionAuthorization:MissionReceiptAuthorization?): ChatDbResult<ConversationModelTurnResult> {
        if (input.conversationId.isBlank()) return ChatDbResult.Invalid("conversation id is required")
        if ((input.inputState == null) != input.inputMessageIds.isEmpty()) {
            return ChatDbResult.Invalid("input state and message IDs must be supplied together")
        }

        return try {
            database.withChatWriteTransaction {
                var batch: ModelToolBatchSnapshot? = null
                val lifecycleSequences = mutableListOf<Long>()
                val results = mutableListOf<ToolResult?>()
                val errors = mutableListOf<Long>()
                val providerOutputs = mutableListOf<Long>()
                input.writes.forEach { write ->
                    when (write) {
                        is ModelTranscriptBatchWrite -> {
                            if (write.input.conversationId != input.conversationId)
                                throw CompositeWriteAbort(ChatDbResult.Invalid("all model turn writes must name the same conversation"))
                            batch = requireSuccess(transcript.appendToolBatch(write.input))
                        }
                        is ModelTranscriptLifecycleWrite -> lifecycleSequences += requireSuccess(
                            transcript.setCallLifecycle(input.conversationId, write.input.batchId,
                                write.input.callId, write.input.lifecycle),
                        )
                        is ModelTranscriptResultWrite -> {
                            if (write.input.conversationId != input.conversationId)
                                throw CompositeWriteAbort(ChatDbResult.Invalid("all model turn writes must name the same conversation"))
                            results += requireSuccess(transcript.appendToolResult(write.input))
                        }
                        is ModelTranscriptErrorWrite -> errors += requireSuccess(transcript.appendModelError(
                            input.conversationId, write.input.ownerUserMessageId,
                            write.input.messageIndex, write.input.errorCode,
                        ))
                        is ModelTranscriptProviderOutputWrite -> providerOutputs += requireSuccess(
                            transcript.appendProviderOutputItems(input.conversationId,
                                write.input.ownerUserMessageId, write.input.messageIndex, write.input.itemsJson),
                        )
                        is ModelTranscriptArtifactWrite -> requireSuccess(transcript.appendModelArtifact(
                            input.conversationId, write.input.artifactId, write.input.relativePath,
                            write.input.sha256, write.input.chars, write.input.bytes, write.input.mimeType,
                            write.input.state, write.input.stateReason,
                        ))
                    }
                }
                val incorporated = input.inputState?.let { state ->
                    requireSuccess(conversations.markInputStateForMission(input.conversationId, input.inputMessageIds, state,missionAuthorization))
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
