package com.jarvys.agent.chatdb

import org.json.JSONArray
import org.json.JSONObject

data class ImportLineIssue(val lineNumber: Long, val reason: String, val bytes: Long)

data class ImportValidation(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/** Diagnostic result deliberately excludes message, tool, and journal payload contents. */
data class ImportReport(
    val conversationId: String,
    val state: String,
    val startedAtMillis: Long,
    val elapsedMillis: Long,
    val ledgerBytes: Long,
    val databaseBytesBefore: Long,
    val databaseBytesAfter: Long,
    val physicalLines: Long,
    val importedLines: Long,
    val skippedLines: Long,
    val unknownLines: Long,
    val rowsByType: Map<String, Long>,
    val issues: List<ImportLineIssue>,
    val validations: List<ImportValidation>,
    val failureReason: String? = null,
    val ledgerRowsByType: Map<String, Long> = emptyMap(),
    val databaseRowsByType: Map<String, Long> = emptyMap(),
    val unknownRowsByType: Map<String, Long> = emptyMap(),
    /** Logical UTF-8/BLOB payload bytes stored for this conversation, excluding page/index overhead. */
    val conversationPayloadBytesBefore: Long = 0L,
    val conversationPayloadBytesAfter: Long = 0L,
) {
    fun toDiagnosticText(): String {
        val root = JSONObject()
            .put("conversationId", conversationId)
            .put("state", state)
            .put("startedAtMillis", startedAtMillis)
            .put("elapsedMillis", elapsedMillis)
            .put("ledgerBytes", ledgerBytes)
            .put("databaseBytesBefore", databaseBytesBefore)
            .put("databaseBytesAfter", databaseBytesAfter)
            .put("conversationPayloadBytesBefore", conversationPayloadBytesBefore)
            .put("conversationPayloadBytesAfter", conversationPayloadBytesAfter)
            .put("physicalLines", physicalLines)
            .put("importedLines", importedLines)
            .put("skippedLines", skippedLines)
            .put("unknownLines", unknownLines)
            .put("failureReason", failureReason ?: JSONObject.NULL)
        val types = JSONObject()
        rowsByType.toSortedMap().forEach { (type, count) -> types.put(type, count) }
        root.put("rowsByType", types)
        fun counts(name: String, values: Map<String, Long>) {
            val encoded = JSONObject()
            values.toSortedMap().forEach { (type, count) -> encoded.put(type, count) }
            root.put(name, encoded)
        }
        counts("ledgerRowsByType", ledgerRowsByType)
        counts("databaseRowsByType", databaseRowsByType)
        counts("unknownRowsByType", unknownRowsByType)
        val issueRows = JSONArray()
        issues.forEach { issue ->
            issueRows.put(JSONObject().put("lineNumber", issue.lineNumber).put("reason", issue.reason).put("bytes", issue.bytes))
        }
        root.put("issues", issueRows)
        val validationRows = JSONArray()
        validations.forEach { validation ->
            validationRows.put(JSONObject().put("name", validation.name).put("passed", validation.passed).put("detail", validation.detail))
        }
        root.put("validations", validationRows)
        return root.toString()
    }
}

/** Batch size changes transaction overhead only; every positive value is accepted. */
data class LedgerImportOptions(
    val batchSize: Int = 500,
    val beforeBatchCommit: (suspend (batchNumber: Int) -> Unit)? = null,
    val onProgress: (suspend (ImportProgress) -> Unit)? = null,
) {
    init { require(batchSize > 0) { "batchSize must be positive" } }
}

data class ImportProgress(
    val conversationId: String,
    val state: String,
    val bytesProcessed: Long,
    val totalBytes: Long,
)
