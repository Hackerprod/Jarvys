package com.jarvys.agent.chatdb

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Positive NEW_TURN evidence. A persisted row is never a live dispatch capability. */
@Entity(tableName = "central_turn_submissions",
    primaryKeys = ["conversation_id", "submission_id"],
    foreignKeys = [ForeignKey(entity = Message::class,
        parentColumns = ["conversation_id", "message_id"],
        childColumns = ["conversation_id", "user_message_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(name = "idx_central_submission_user", value = ["conversation_id", "user_message_id"], unique = true),
        Index(name = "idx_central_submission_pending", value = ["conversation_id", "state", "current_admission_process", "user_seq"]),
    ])
internal data class CentralTurnSubmission(
    val conversation_id: String,
    val submission_id: String,
    val user_message_id: String,
    val user_seq: Long,
    val source_sha256: String,
    val storage_generation: String,
    val original_admission_process: String,
    val current_admission_process: String,
    val revision: Long,
    val state: String,
    val claim_process: String?,
    val claim_run_id: String?,
    val claim_token_generation: Long?,
    val terminal_evidence: String?,
    val recovery_receipt: String?,
)

/** Independent gates: the existing v1→v2 gate does not authorize this extension. */
internal object RoomCentralSubmissionSchema {
    const val V2_IDENTITY = "a48c4a703630b98aa0e0c9ccb54d0912"
    const val CREATE_SQL = "CREATE TABLE IF NOT EXISTS `central_turn_submissions` (`conversation_id` TEXT NOT NULL, `submission_id` TEXT NOT NULL, `user_message_id` TEXT NOT NULL, `user_seq` INTEGER NOT NULL, `source_sha256` TEXT NOT NULL, `storage_generation` TEXT NOT NULL, `original_admission_process` TEXT NOT NULL, `current_admission_process` TEXT NOT NULL, `revision` INTEGER NOT NULL, `state` TEXT NOT NULL, `claim_process` TEXT, `claim_run_id` TEXT, `claim_token_generation` INTEGER, `terminal_evidence` TEXT, `recovery_receipt` TEXT, PRIMARY KEY(`conversation_id`, `submission_id`), FOREIGN KEY(`conversation_id`, `user_message_id`) REFERENCES `messages`(`conversation_id`, `message_id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    val INDEX_SQL = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_central_submission_user` ON `central_turn_submissions` (`conversation_id`, `user_message_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_central_submission_pending` ON `central_turn_submissions` (`conversation_id`, `state`, `current_admission_process`, `user_seq`)"
    )
}

/** Read-only evidence for UI and exact CAS. Constructors do not confer authority. */
data class CentralSubmissionSnapshot(
    val sessionId: String, val submissionId: String, val userMessageId: String, val userSeq: Long,
    val sourceSha256: String, val storageGeneration: String,
    val originalAdmissionProcess: String, val currentAdmissionProcess: String,
    val revision: Long, val state: String, val claimProcess: String?, val claimRunId: String?,
    val claimTokenGeneration: Long?, val terminalEvidence: String?, val recoveryReceipt: String?,
)

/** Actual committed source plus its native destination lifetime; still not permission to execute. */
class CentralSubmissionAppendReceipt internal constructor(
    val snapshot: CentralSubmissionSnapshot, val source: CentralUserRootCheckpoint,
    selectedSkillIds:List<String>,
) {
    val selectedSkillIds:List<String> = java.util.Collections.unmodifiableList(ArrayList(selectedSkillIds))
}

/** Actual read-only STOP capture. Native STOP authority is separately required to mutate it. */
class CentralSubmissionCohortSnapshot internal constructor(
    rows:List<CentralSubmissionSnapshot>,
    val storageGeneration:String?,
    val lifecycle:com.jarvys.agent.connectors.ConnectionStateCoordinator.DestinationEvidence?,
) {
    val rows:List<CentralSubmissionSnapshot> = java.util.Collections.unmodifiableList(ArrayList(rows))
}
