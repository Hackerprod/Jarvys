package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** `user_decision_request` and last-wins `user_decision_resolution` rows. */
@Entity(
    tableName = "user_decisions",
    primaryKeys = ["conversation_id", "decision_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_user_decisions_seq", value = ["conversation_id", "seq"])],
)
data class UserDecision(
    val conversation_id: String,
    val decision_id: String,
    val seq: Long,
    val title: String,
    val body: String,
    val allow_dismiss: Boolean,
    val status: String? = null,
    val option_id: String? = null,
    val option_label: String? = null,
    val resolved_seq: Long? = null,
    val created_at: Double,
)

/** Options nested under `user_decision_request` rows. */
@Entity(
    tableName = "user_decision_options",
    primaryKeys = ["conversation_id", "decision_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = UserDecision::class, parentColumns = ["conversation_id", "decision_id"], childColumns = ["conversation_id", "decision_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_user_decision_options_id", value = ["conversation_id", "decision_id", "option_id"])],
)
data class UserDecisionOption(
    val conversation_id: String,
    val decision_id: String,
    val ordinal: Int,
    val option_id: String,
    val label: String,
    val description: String? = null,
    val role: String? = null,
)

/** `main_interrupt_request` rows; a null dispatch position means still pending. */
@Entity(
    tableName = "interrupt_requests",
    primaryKeys = ["conversation_id", "request_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_interrupt_requests_pending", value = ["conversation_id", "dispatched_seq"]),
        Index(name = "idx_interrupt_requests_dispatched_seq", value = ["dispatched_seq"]),
        Index(name = "idx_interrupt_requests_input_state", value = ["conversation_id", "input_state", "input_state_seq"]),
        Index(name = "idx_interrupt_requests_seq", value = ["conversation_id", "seq"]),
    ],
)
data class InterruptRequest(
    val conversation_id: String,
    val request_id: String,
    val seq: Long,
    val content: String,
    val dispatched_message_id: String? = null,
    val dispatched_seq: Long? = null,
    /** Input status can precede dispatch when the receipt names the request rather than a message. */
    val input_state: String? = null,
    val input_state_seq: Long? = null,
    val created_at: Double,
)

/** Skill identifiers nested under `main_interrupt_request` rows. */
@Entity(
    tableName = "interrupt_request_skills",
    primaryKeys = ["conversation_id", "request_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = InterruptRequest::class, parentColumns = ["conversation_id", "request_id"], childColumns = ["conversation_id", "request_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class InterruptRequestSkill(
    val conversation_id: String,
    val request_id: String,
    val ordinal: Int,
    val skill_id: String,
)

/** `model_tool_calls` batches and opaque provider output; malformed call arrays are retained verbatim. */
@Entity(
    tableName = "tool_batches",
    primaryKeys = ["conversation_id", "batch_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_tool_batches_owner", value = ["conversation_id", "owner_user_message_id", "seq"]),
        Index(name = "idx_tool_batches_seq", value = ["conversation_id", "seq"]),
    ],
)
data class ToolBatch(
    val conversation_id: String,
    val batch_id: String,
    val seq: Long,
    val owner_user_message_id: String? = null,
    val message_index: Long,
    val assistant_text: String,
    val assistant_progress: Boolean,
    val created_at: Double,
    /** Latest source event position, including duplicate result events ignored by first-wins result storage. */
    val last_activity_seq: Long? = null,
    val calls_decode_error: String? = null,
    val raw_calls: String? = null,
    /** Provider-specific output item shapes have no common schema and are retained opaquely. */
    val provider_output_items_json: String? = null,
)

/** Calls from `model_tool_calls`, with `model_tool_started`/`model_tool_not_started` stored as lifecycle state. Arguments JSON is opaque by tool contract. */
@Entity(
    tableName = "tool_calls",
    primaryKeys = ["conversation_id", "batch_id", "call_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ToolBatch::class, parentColumns = ["conversation_id", "batch_id"], childColumns = ["conversation_id", "batch_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_tool_calls_ordinal", value = ["conversation_id", "batch_id", "ordinal"], unique = true),
        Index(name = "idx_tool_calls_call", value = ["conversation_id", "call_id"]),
    ],
)
data class ToolCall(
    val conversation_id: String,
    val batch_id: String,
    val ordinal: Int,
    val call_id: String,
    val name: String,
    /** Arguments are intentionally opaque JSON because tool contracts vary by tool and version. */
    val arguments_json: String,
    val lifecycle: String,
    val lifecycle_seq: Long? = null,
)

/** First-wins `model_tool_result` row for a batch call. */
@Entity(
    tableName = "tool_results",
    primaryKeys = ["conversation_id", "batch_id", "call_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ToolCall::class, parentColumns = ["conversation_id", "batch_id", "call_id"], childColumns = ["conversation_id", "batch_id", "call_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_tool_results_seq", value = ["conversation_id", "seq"]), Index(name = "idx_tool_results_artifact", value = ["conversation_id", "file_artifact_id"])],
)
data class ToolResult(
    val conversation_id: String,
    val batch_id: String,
    val call_id: String,
    val seq: Long,
    val tool_name: String,
    val lifecycle: String,
    val output: String,
    val created_at: Double,
    /** Ownership key for output archived by `MainChatTranscriptStore.retain`; null for inline-only output. */
    val file_artifact_id: String? = null,
)

/** Append-only lifecycle rows; duplicate event_id imports within one conversation are first-wins (`INSERT OR IGNORE`). */
@Entity(
    tableName = "tool_activities",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ToolBatch::class, parentColumns = ["conversation_id", "batch_id"], childColumns = ["conversation_id", "source_batch_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_tool_activities_event", value = ["conversation_id", "event_id"], unique = true),
        Index(name = "idx_tool_activities_owner_execution", value = ["conversation_id", "owner_user_message_id", "execution_id", "seq"]),
        Index(name = "idx_tool_activities_batch_call", value = ["conversation_id", "source_batch_id", "call_id", "seq"]),
        Index(name = "idx_tool_activities_execution_call", value = ["conversation_id", "execution_id", "call_id", "seq"]),
    ],
)
data class ToolActivity(
    val conversation_id: String,
    val seq: Long,
    val event_id: String,
    val owner_user_message_id: String? = null,
    val source_batch_id: String? = null,
    val execution_id: String? = null,
    val call_id: String? = null,
    val tool_name: String,
    val display_name: String? = null,
    val skill_id: String? = null,
    val skill_name: String? = null,
    val stage: String,
    val detail: String? = null,
    val preview_id: String? = null,
    val audit_detail: String? = null,
    val reflection_source: String? = null,
    val timestamp_millis: Long,
    val created_at: Double,
)

/** Last-wins `tool_presentation` rows used by the conversation timeline. */
@Entity(
    tableName = "tool_presentations",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ToolBatch::class, parentColumns = ["conversation_id", "batch_id"], childColumns = ["conversation_id", "batch_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_tool_presentations_batch_call", value = ["conversation_id", "batch_id", "call_id", "seq"]),
        Index(name = "idx_tool_presentations_owner", value = ["conversation_id", "owner_user_message_id", "seq"]),
    ],
)
data class ToolPresentation(
    val conversation_id: String,
    val seq: Long,
    val owner_user_message_id: String? = null,
    val batch_id: String? = null,
    val call_id: String? = null,
    val tool_name: String,
    val stage: String,
    val detail: String? = null,
    val audit_detail: String? = null,
    val preview_expected: Boolean = false,
    val preview_id: String? = null,
    val created_at: Double,
)

/** `reflection_tool` lifecycle ledger rows. */
@Entity(
    tableName = "reflection_tool_markers",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_reflection_tool_owner", value = ["conversation_id", "owner_user_message_id", "seq"]),
        Index(name = "idx_reflection_tool_batch_call", value = ["conversation_id", "batch_id", "call_id"]),
    ],
)
data class ReflectionToolMarker(
    val conversation_id: String,
    val seq: Long,
    val owner_user_message_id: String? = null,
    val batch_id: String? = null,
    val call_id: String? = null,
    val tool_name: String,
    val source: String,
    val stage: String,
    val marker: String,
    val created_at: Double,
)

/** `model_error` rows, including failures excluded from conversation context. */
@Entity(
    tableName = "model_errors",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "idx_model_errors_owner", value = ["conversation_id", "owner_user_message_id", "seq"])],
)
data class ModelError(
    val conversation_id: String,
    val seq: Long,
    val owner_user_message_id: String? = null,
    val message_index: Long,
    val error_code: String,
)

/** `model_provider_output_items`; provider output item payload is opaque and follows last-wins semantics per owner. */
@Entity(
    tableName = "model_provider_outputs",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "idx_model_provider_outputs_owner", value = ["conversation_id", "owner_user_message_id", "seq"]),
        Index(name = "idx_model_provider_outputs_message_index", value = ["conversation_id", "message_index"]),
    ],
)
data class ModelProviderOutput(
    val conversation_id: String,
    val seq: Long,
    val owner_user_message_id: String,
    val message_index: Long,
    /** Provider-specific item kinds and values have no shared normalized contract. */
    val items_json: String,
)

/** `model_artifact` metadata and its disk-file reference. */
@Entity(
    tableName = "model_artifacts",
    primaryKeys = ["conversation_id", "artifact_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FileRef::class, parentColumns = ["id"], childColumns = ["file_ref_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(name = "idx_model_artifacts_seq", value = ["conversation_id", "seq"]), Index(name = "idx_model_artifacts_file", value = ["file_ref_id"])],
)
data class ModelArtifact(
    val conversation_id: String,
    val artifact_id: String,
    val seq: Long,
    val path: String,
    val sha256: String,
    val chars: Long,
    val bytes: Long,
    val file_ref_id: String? = null,
    /** `retained` (complete), `bounded` (truncated), `expelled` (removed under storage pressure) or `unavailable` (never kept). */
    @androidx.room.ColumnInfo(defaultValue = "'retained'") val state: String = ModelArtifactState.RETAINED,
    val state_reason: String? = null,
)

/** Retention states of one conversation artifact; the model-visible text and the metadata both carry them. */
object ModelArtifactState {
    const val RETAINED = "retained"
    const val BOUNDED = "bounded"
    const val EXPELLED = "expelled"
    const val UNAVAILABLE = "unavailable"
    val ALL = setOf(RETAINED, BOUNDED, EXPELLED, UNAVAILABLE)

    /** Whether the artifact text is on disk and can be read back. */
    fun hasContent(state: String): Boolean = state == RETAINED || state == BOUNDED
}

/** `delivered_file` ownership rows; the file itself remains on disk. */
@Entity(
    tableName = "artifacts",
    primaryKeys = ["conversation_id", "artifact_id"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FileRef::class, parentColumns = ["id"], childColumns = ["file_ref_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = FileRef::class, parentColumns = ["id"], childColumns = ["thumbnail_ref"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(name = "idx_artifacts_seq", value = ["conversation_id", "seq"]),
        Index(name = "idx_artifacts_file", value = ["file_ref_id"]),
        Index(name = "idx_artifacts_thumbnail", value = ["thumbnail_ref"]),
        Index(name = "idx_artifacts_type", value = ["conversation_id", "artifact_type", "seq"]),
        Index(name = "idx_artifacts_module", value = ["conversation_id", "module_id", "seq"]),
    ],
)
data class ConversationArtifact(
    val conversation_id: String,
    val artifact_id: String,
    val seq: Long,
    val name: String,
    val mime_type: String?,
    val size_bytes: Long,
    val kind: String,
    val relative_path: String,
    val sha256: String? = null,
    val file_ref_id: String? = null,
    /** MIME type by default; presentation modules may define values such as `x-jarvys/chart`. */
    val artifact_type: String,
    val module_id: String? = null,
    /** Opaque module-owned manifest JSON, retained without interpreting module-specific fields. */
    val manifest_json: String? = null,
    val title: String? = null,
    /** Optional `file_refs.id` for a thumbnail that remains on disk. */
    val thumbnail_ref: String? = null,
    val created_at: Double,
)

/** `generated_image` ledger row and optional disk-file reference. */
@Entity(
    tableName = "generated_images",
    primaryKeys = ["conversation_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationItem::class, parentColumns = ["conversation_id", "seq"], childColumns = ["conversation_id", "seq"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FileRef::class, parentColumns = ["id"], childColumns = ["file_ref_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(name = "idx_generated_images_path", value = ["image_path"]), Index(name = "idx_generated_images_file", value = ["file_ref_id"])],
)
data class GeneratedImage(
    val conversation_id: String,
    val seq: Long,
    val image_path: String? = null,
    val prompt: String,
    val revised_prompt: String? = null,
    val size: String? = null,
    val mime_type: String? = null,
    val status: String,
    val error: String? = null,
    val file_ref_id: String? = null,
    val created_at: Double,
)

