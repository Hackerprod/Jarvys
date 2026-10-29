package com.jarvys.agent.chatdb

import com.jarvys.agent.CentralInvocationWitness
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Only the Bridge supplies a live invocation witness; a request/fingerprint alone is not authority. */
class MemorySaveOperationInput internal constructor(internal val request: MemorySaveRequest,
    internal val witness: CentralInvocationWitness, internal val action: String = "memory_save")
internal enum class MemorySaveOperationStatus { NOT_RECORDED, COMMITTED_REFERENCE, EXPIRED }
internal data class MemorySaveOperationReference(val status: MemorySaveOperationStatus, val id: String? = null)

internal object MemorySaveOperations {
    const val INPUT_DIGEST_PREFIX = "memory-save-input-v2:"
    fun isInputDigest(value: String?): Boolean = value != null && value.startsWith(INPUT_DIGEST_PREFIX) &&
        value.length == INPUT_DIGEST_PREFIX.length + 64 && value.substring(INPUT_DIGEST_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' }

    internal data class Prepared(val source: MemorySaveDurableSource, val fingerprint: String, val extension: String)
    private data class Row(val id: String?, val fingerprint: String, val extension: String,
        val project: String, val scope: String, val generation: String, val session: String,
        val owner: String, val batch: String, val call: String, val digest: String, val inputDigest: String, val memorySessionId: String) {
        fun matches(source: MemorySaveDurableSource) = generation == source.generation && session == source.sessionId &&
            owner == source.ownerMessageId && batch == source.batchId && call == source.callId && digest == source.argumentsDigest && inputDigest == source.inputDigest && memorySessionId == source.memorySessionId
    }
    fun operationId(source: MemorySaveDurableSource): String = JSONArray().put(1).put("central-memory-save")
        .put(source.generation).put(source.sessionId).put(source.batchId).put(source.callId).toString()

    /** Exact upstream algorithm over already prepared fields, independently testable from the native adapter. */
    fun coreFingerprint(session: String, type: String, title: String, content: String, tool: String,
        requestedProject: String, scope: String, topic: String): String = coreFingerprintRaw(session, type, title, content, tool, requestedProject, scope, topic.toByteArray(Charsets.UTF_8))
    fun coreFingerprintRaw(session: String, type: String, title: String, content: String, tool: String,
        requestedProject: String, scope: String, topic: ByteArray?): String = "v1:" + framedBytesHash(
            listOf(session, type, title, content, tool, requestedProject, scope).map { it.toByteArray(Charsets.UTF_8) } + listOf(topic ?: byteArrayOf()))
    private fun framedHash(fields: List<String>): String {
        return framedBytesHash(fields.map { it.toByteArray(Charsets.UTF_8) })
    }
    private fun framedBytesHash(fields: List<ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { bytes ->
            digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun nullable(value: Any?): String = JSONArray().put(if (value == null) "null" else "value")
        .put(value ?: JSONObject.NULL).toString()
    fun fingerprints(request: MemorySaveRequest, memorySessionId: String = request.callerConversation): Pair<String, String> = fingerprintsPrepared(effective(request), memorySessionId)
    private fun fingerprintsPrepared(request: MemorySaveRequest, memorySessionId: String, action: String = "memory_save"): Pair<String, String> {
        val options = request.options
        val core = coreFingerprintRaw(memorySessionId, requireNotNull(options.type), requireNotNull(options.title),
            request.text, action, request.requestedProject.orEmpty(), requireNotNull(request.rawScope), MemoryObservationContent.normalizeTopicBytes(options.topicKey))
        val extension = "jarvys-save-v1:" + framedHash(listOf(request.layer, nullable(request.category),
            java.lang.Double.toHexString(request.importance), nullable(options.eventAtMs), nullable(options.supersedesId),
            JSONArray(options.relatedIds).toString(), options.relation, options.actor, nullable(request.sourceRef),
            nullable(options.sourceConversationId), nullable(options.sourceMessageId)))
        return core to extension
    }
    /** One shared pure preparation for checkpoint proof, submitted proof and both fingerprints. */
    private fun effective(request: MemorySaveRequest): MemorySaveRequest {
        require(request.requestedProject == null || MemoryNamespace.normalizeProject(request.requestedProject).isNotEmpty()) { "memory_project_empty" }
        val value = request.copy(
            text = MemoryObservationContent.prepareContent(request.text).content,
            options = request.options.copy(type = request.options.type?.takeUnless(String::isEmpty) ?: "manual",
                title = MemoryObservationContent.prepareTitle(requireNotNull(request.options.title) { "memory_title_required" })),
            requestedProject = request.requestedProject?.let(MemoryNamespace::normalizeProject).orEmpty(),
            rawScope = MemoryNamespace.normalizeScope(request.rawScope),
        )
        require(value.text.isNotEmpty()) { "memory_empty" }
        require(!value.options.title.isNullOrEmpty()) { "memory_title_empty" }
        require(value.options.actor in setOf("user", "agent")) { "memory_actor_invalid" }
        require(value.layer in setOf("curated", "daily", "session_snapshot")) { "memory_layer_invalid" }
        require(value.options.relation in setOf("related", "compatible", "conflicts_with")) { "memory_relation_invalid" }
        return value
    }
    /** Exact typed proof, issued before retention; no UTF-8 replacement or JSON key-order dependence. */
    @JvmStatic @JvmOverloads fun inputDigest(request: MemorySaveRequest, memorySessionId: String = request.callerConversation): String = inputDigestPrepared(effective(request), memorySessionId)
    private fun inputDigestPrepared(value: MemorySaveRequest, memorySessionId: String): String {
        val fields = listOf("v2", memorySessionId, "memory_save", value.options.type,
            value.options.title, value.text, value.requestedProject, value.rawScope, MemoryObservationContent.normalizeTopicBytes(value.options.topicKey),
            value.layer, value.category, java.lang.Double.toHexString(value.importance),
            value.options.eventAtMs?.toString(), value.options.supersedesId, value.options.relatedIds,
            value.options.relation, value.options.actor, value.sourceRef,
            value.options.sourceConversationId, value.options.sourceMessageId)
        val digest = MessageDigest.getInstance("SHA-256")
        fun length(count: Int) = digest.update(ByteBuffer.allocate(8).putLong(count.toLong()).array())
        fun encode(item: Any?) {
            when (item) {
                null -> digest.update(0.toByte())
                is String -> {
                    digest.update(1.toByte())
                    val bytes = ExactUtf16.encode(item)
                    length(bytes.size); digest.update(bytes)
                }
                is List<*> -> { digest.update(2.toByte()); length(item.size); item.forEach(::encode) }
                is ByteArray -> { digest.update(3.toByte()); length(item.size); digest.update(item) }
                else -> error("Unsupported memory input proof field")
            }
        }
        encode(fields)
        return INPUT_DIGEST_PREFIX + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun prepare(database: JarvysChatDatabase, input: MemorySaveOperationInput): Prepared {
        // Pure validation preserves canonical input errors; it grants no authority and performs no IO.
        val effective = effective(input.request)
        input.witness.requireInvocationCurrent()
        val claimed = input.witness.source()
        val source = MemorySaveSourceIdentity.read(database, claimed.storageGeneration, claimed.ownerCentralSessionId,
            claimed.sourceBatchId, claimed.sourceCallId, input.action) ?: throw IllegalStateException("memory_save_source_invalid")
        check(source.ownerMessageId == claimed.sourceMessageId) { "memory_save_source_invalid" }
        val actualPrints = fingerprintsPrepared(effective, source.memorySessionId, input.action)
        val actualProof = if(input.action=="memory_save") inputDigestPrepared(effective, source.memorySessionId)
            else MemorySessionCommands.inputDigest(input.action,source.memorySessionId,effective.text)
        check(actualProof == source.inputDigest) { "memory_save_source_arguments_mismatch" }
        return Prepared(source, actualPrints.first, actualPrints.second)
    }
    /** Only new effects require a live execution session; authenticated replay remains read-only. */
    fun requireSessionForWrite(database: JarvysChatDatabase, input: MemorySaveOperationInput, prepared: Prepared) {
        input.witness.requireInvocationCurrent()
        val claimed = input.witness.source()
        val source = prepared.source
        MemorySessionRepository(database).renewCurrent(MemorySessionBinding(source.memorySessionId, MemoryNamespace.CENTRAL,
            source.generation, source.sessionId, source.ownerMessageId, claimed.sourceRunId, claimed.sourceProcessId))
    }
    /** Post-save candidates may only use the exact target of this current durable save operation. */
    fun requireCommittedCandidateSource(database:JarvysChatDatabase,input:MemorySaveOperationInput,id:String,namespace:MemoryNamespace):Prepared {
        val prepared=prepare(database,input)
        val row=row(database,prepared.source) ?: error("memory_save_source_invalid")
        check(row.matches(prepared.source) && row.id==id && row.fingerprint==prepared.fingerprint && row.extension==prepared.extension) { "memory_save_source_invalid" }
        namespace.requireOwner(row.project)
        if(target(database,row,namespace)!=id) throw MemoryRelationSourceChanged()
        val source=prepared.source; val claimed=input.witness.source()
        MemorySessionRepository(database).requireCurrent(MemorySessionBinding(source.memorySessionId,MemoryNamespace.CENTRAL,
            source.generation,source.sessionId,source.ownerMessageId,claimed.sourceRunId,claimed.sourceProcessId))
        return prepared
    }
    private fun row(database: JarvysChatDatabase, source: MemorySaveDurableSource): Row? = database.chatSql.query(
        "SELECT observation_id,fingerprint,extension_fingerprint,project,scope,source_generation,source_session_id," +
            "source_owner_message_id,source_batch_id,source_call_id,source_arguments_digest,source_input_digest,memory_session_id FROM memory_save_operations WHERE operation_id=?",
        arrayOf(operationId(source)),
    ).use { c -> if (!c.moveToFirst()) null else Row(if(c.isNull(0)) null else c.getString(0), c.getString(1),
        c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7),
        c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12)) }
    private fun target(database: JarvysChatDatabase, row: Row, namespace: MemoryNamespace): String? {
        if (row.id == null) return null
        val (where, args) = namespace.ids().clause()
        return database.chatSql.query("SELECT id FROM memory_entries WHERE id=? AND deleted_at IS NULL AND $where",
            (listOf(row.id) + args).toTypedArray()).use { if(it.moveToFirst()) it.getString(0) else null }
    }
    /** Read transaction owned by the real restore caller; unknown/mismatched authority never reveals an ID. */
    fun lookup(database: JarvysChatDatabase, namespace: MemoryNamespace, source: MemorySaveDurableSource): MemorySaveOperationReference {
        val row = row(database, source) ?: return MemorySaveOperationReference(MemorySaveOperationStatus.NOT_RECORDED)
        if (!row.matches(source) || !namespace.owns(row.project)) return MemorySaveOperationReference(MemorySaveOperationStatus.NOT_RECORDED)
        val id = target(database, row, namespace)
        return if (id == null) MemorySaveOperationReference(MemorySaveOperationStatus.EXPIRED)
        else MemorySaveOperationReference(MemorySaveOperationStatus.COMMITTED_REFERENCE, id)
    }
    /** No mutation; called before references, topic/dedupe or clock sampling in the save transaction. */
    fun replay(database: JarvysChatDatabase, namespace: MemoryNamespace, prepared: Prepared): PersonalMemoryReceipt? {
        val row = row(database, prepared.source) ?: return null
        check(row.matches(prepared.source)) { "memory_save_source_invalid" }
        namespace.requireOwner(row.project)
        check(row.fingerprint.startsWith("v1:") && row.extension.startsWith("jarvys-save-v1:")) { "memory_save_operation_expired" }
        check(row.fingerprint == prepared.fingerprint && row.extension == prepared.extension) { "memory_save_operation_conflict" }
        val id = target(database, row, namespace) ?: throw IllegalStateException("memory_save_operation_expired")
        return database.chatSql.query("SELECT revision_count,observation_type,duplicate_count,last_seen_at,pinned,project,scope FROM memory_entries WHERE id=?", arrayOf(id)).use { c ->
            check(c.moveToFirst()) { "memory_save_operation_expired" }
            PersonalMemoryReceipt(id, "operation_replayed", c.getLong(0), project = c.getString(5), scope = c.getString(6),
                observationType = c.getString(1), duplicateCount = c.getLong(2), lastSeenAt = if(c.isNull(3)) null else c.getLong(3),
                pinned = c.getInt(4) != 0, operationReplayed = true, metadataReadback = "current_authorized_readback")
        }
    }
    fun record(database: JarvysChatDatabase, prepared: Prepared, id: String, namespace: MemoryNamespace, now: Long) {
        val s = prepared.source
        database.chatSql.execSQL("INSERT INTO memory_save_operations(operation_id,fingerprint,extension_fingerprint,observation_id,project,scope," +
            "source_generation,source_session_id,source_owner_message_id,source_batch_id,source_call_id,source_arguments_digest,source_input_digest,created_at,memory_session_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf(operationId(s), prepared.fingerprint, prepared.extension, id, namespace.project, namespace.scope,
                s.generation, s.sessionId, s.ownerMessageId, s.batchId, s.callId, s.argumentsDigest, s.inputDigest, now, s.memorySessionId))
    }
}
