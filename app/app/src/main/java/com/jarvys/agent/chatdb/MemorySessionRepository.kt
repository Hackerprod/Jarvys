package com.jarvys.agent.chatdb

import org.json.JSONArray
import java.util.UUID

open class MemorySessionUnavailableException : IllegalStateException("memory_session_unavailable")

class MemorySessionFinalizedException : MemorySessionUnavailableException()

/** Issued by the admitted producer, not from model arguments or a displayed session ID. */
class MemorySessionBinding internal constructor(val id: String, val project: String,
    val sourceGeneration: String, val ownerConversation: String, val ownerMessageId: String,
    val runId: String, val processId: String)
data class PersonalMemorySessionView(val id: String, val project: String, val state: String,
    val startedAt: Long, val endedAt: Long?, val ownerMessageId: String?,
    val observationCount: Long, val promptCount: Long)
data class PersonalMemoryPromptView(val id: String, val sessionId: String, val project: String,
    val content: String, val createdAt: Long)
data class PersonalMemoryDomainDeletionReceipt(val id: String, val project: String, val domain: String)

internal class MemorySessionRepository(private val database: JarvysChatDatabase,
    private val clock: java.time.Clock = java.time.Clock.systemUTC()) {
    private fun now() = clock.millis()
    private fun expiry() = Math.addExact(now(), 30L * 60L * 1000L)
    private val sql get() = database.chatSql

    private fun visibleUser(conversation: String, owner: String) {
        check(conversation == MemoryNamespace.CENTRAL && sql.query(
            "SELECT 1 FROM messages WHERE conversation_id=? AND message_id=? AND role='user' AND hidden_kind IS NULL",
            arrayOf(conversation, owner)).use { it.moveToFirst() }) { "memory_session_source_invalid" }
    }
    suspend fun begin(id: String, conversation: String, owner: String, run: String, process: String): MemorySessionBinding =
        database.withChatWriteTransaction {
            require(listOf(id, owner, run, process).none(String::isBlank)) { "memory_session_identity_invalid" }
            check(!sql.query("SELECT 1 FROM memory_session_tombstones WHERE id=?",arrayOf(id)).use { it.moveToFirst() }) { "memory_session_deleted" }
            visibleUser(conversation, owner)
            var generation = MemorySaveSourceIdentity.existingGeneration(database)
            if (generation == null) {
                sql.execSQL("INSERT OR IGNORE INTO storage_meta(key,value) VALUES(?,?)",
                    arrayOf(ConnectionProjectionRepository.GENERATION_KEY, UUID.randomUUID().toString()))
                generation = requireNotNull(MemorySaveSourceIdentity.existingGeneration(database))
            }
            val binding = MemorySessionBinding(id, MemoryNamespace.CENTRAL, generation, conversation, owner, run, process)
            val exists = sql.query("SELECT 1 FROM memory_sessions WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
            if (exists) renewCurrent(binding)
            else sql.execSQL("INSERT INTO memory_sessions(id,project,ownership_mode,state,started_at,source_generation,owner_conversation_id,owner_message_id,run_id,process_id,runtime_lease_expires_at,runtime_root_id) VALUES(?,?,'shared','active',?,?,?,?,?,?,?,?)",
                arrayOf(id, binding.project, now(), generation, conversation, owner, run, process, expiry(), id))
            binding
        }

    private fun eventKey(receipt: MemorySessionResumeReceipt): String = JSONArray().put(1)
        .put(receipt.sourceGeneration).put(receipt.conversationId).put(receipt.assistantMessageId)
        .put(receipt.hiddenSeq).toString()

    /** Identity revalidation only, never a host admission capability. Caller holds a read/write fence. */
    fun validResumeReceipt(receipt: MemorySessionResumeReceipt): Boolean {
        if (receipt.conversationId != MemoryNamespace.CENTRAL || receipt.hiddenSeq <= 0 ||
            MemorySaveSourceIdentity.existingGeneration(database) != receipt.sourceGeneration) return false
        val source = sql.query("SELECT 1 FROM messages a JOIN messages u ON u.conversation_id=a.conversation_id AND u.message_id=a.owner_user_message_id WHERE a.conversation_id=? AND a.message_id=? AND a.role='assistant' AND a.owner_user_message_id=? AND a.run_id=? AND a.hidden_kind='assistant_regenerated' AND a.hidden_seq=? AND u.role='user' AND u.hidden_kind IS NULL AND EXISTS(SELECT 1 FROM conversation_items i WHERE i.conversation_id=a.conversation_id AND i.seq=a.hidden_seq AND i.item_kind='assistant_regenerated' AND i.item_key=a.message_id) AND a.hidden_seq=(SELECT MAX(seq) FROM conversation_items WHERE conversation_id=a.conversation_id AND item_kind='assistant_regenerated') AND NOT EXISTS(SELECT 1 FROM messages later WHERE later.conversation_id=a.conversation_id AND later.role='assistant' AND later.hidden_kind IS NULL AND later.seq>a.hidden_seq)",
            arrayOf(receipt.conversationId,receipt.assistantMessageId,receipt.ownerMessageId,receipt.priorRunId,receipt.hiddenSeq)).use { it.moveToFirst() }
        if (!source || sql.query("SELECT 1 FROM memory_session_tombstones WHERE id=?",arrayOf(receipt.rootSessionId)).use { it.moveToFirst() }) return false
        val consumed=sql.query("SELECT COUNT(*) FROM memory_sessions WHERE ownership_mode='shared' AND project=? AND source_generation=? AND owner_conversation_id=? AND owner_message_id=? AND COALESCE(runtime_root_id,id)=? AND resume_event_key=?",
            arrayOf(MemoryNamespace.CENTRAL,receipt.sourceGeneration,receipt.conversationId,receipt.ownerMessageId,receipt.rootSessionId,eventKey(receipt))).use { it.moveToFirst(); it.getLong(0) }
        if(consumed==1L) return true // Exact same run/process retry is separately required by resume().
        return sql.query("SELECT COUNT(*) FROM memory_sessions WHERE ownership_mode='shared' AND project=? AND source_generation=? AND owner_conversation_id=? AND owner_message_id=? AND COALESCE(runtime_root_id,id)=? AND run_id=?",
            arrayOf(MemoryNamespace.CENTRAL,receipt.sourceGeneration,receipt.conversationId,receipt.ownerMessageId,receipt.rootSessionId,receipt.priorRunId)).use { it.moveToFirst() && it.getLong(0)==1L }
    }

    suspend fun resume(receipt: MemorySessionResumeReceipt, run: String, process: String): MemorySessionBinding = database.withChatWriteTransaction {
        require(run.isNotBlank() && process.isNotBlank()) { "memory_session_identity_invalid" }
        check(validResumeReceipt(receipt)) { "memory_session_resume_source_invalid" }
        val key=eventKey(receipt)
        val prior=sql.query("SELECT id,run_id,process_id FROM memory_sessions WHERE resume_event_key=?",arrayOf(key)).use { c ->
            if(c.moveToFirst()) Triple(c.getString(0),c.getString(1),c.getString(2)) else null
        }
        if(prior != null) {
            check(prior.second==run && prior.third==process) { "memory_session_resume_consumed" }
            val binding=MemorySessionBinding(prior.first,MemoryNamespace.CENTRAL,receipt.sourceGeneration,receipt.conversationId,receipt.ownerMessageId,run,process)
            requireCurrent(binding)
            return@withChatWriteTransaction binding
        }
        data class Row(val id:String,val ended:Boolean,val project:String,val generation:String?,val owner:String?,val conversation:String?,val run:String?,val process:String?,val mode:String)
        fun row(id:String):Row? = sql.query("SELECT id,ended_at,project,source_generation,owner_message_id,owner_conversation_id,run_id,process_id,ownership_mode FROM memory_sessions WHERE id=?",arrayOf(id)).use { c ->
            if(!c.moveToFirst()) null else Row(c.getString(0),!c.isNull(1),c.getString(2),if(c.isNull(3))null else c.getString(3),if(c.isNull(4))null else c.getString(4),if(c.isNull(5))null else c.getString(5),if(c.isNull(6))null else c.getString(6),if(c.isNull(7))null else c.getString(7),c.getString(8))
        }
        val root=row(receipt.rootSessionId) ?: throw MemorySessionUnavailableException()
        fun ownerMatches(value:Row) = value.mode=="shared" && value.project==MemoryNamespace.CENTRAL && value.generation==receipt.sourceGeneration && value.owner==receipt.ownerMessageId && value.conversation==receipt.conversationId
        check(ownerMatches(root)) { "memory_session_owner_conflict" }
        var selected=root.id
        if(root.ended) {
            val prefix=root.id+":resume:"
            val candidates=sql.query("SELECT id,ended_at FROM memory_sessions WHERE id>=? AND id<? ORDER BY id COLLATE BINARY",arrayOf(prefix+"0",prefix+":")).use { c -> buildList {
                while(c.moveToNext()) { val id=c.getString(0); val suffix=id.removePrefix(prefix)
                    if(id.startsWith(prefix) && suffix.isNotEmpty() && suffix.all { it in '0'..'9' }) add(Triple(id,java.math.BigInteger(suffix),c.isNull(1)))
                }
            } }
            selected=candidates.filter { it.third }.minByOrNull { it.second }?.first
                ?: prefix+((candidates.maxOfOrNull { it.second } ?: java.math.BigInteger.ONE).max(java.math.BigInteger.ONE)+java.math.BigInteger.ONE).toString()
        }
        check(!sql.query("SELECT 1 FROM memory_session_tombstones WHERE id=?",arrayOf(selected)).use { it.moveToFirst() }) { "memory_session_deleted" }
        val old=row(selected)
        val binding=MemorySessionBinding(selected,root.project,receipt.sourceGeneration,receipt.conversationId,receipt.ownerMessageId,run,process)
        if(old==null) sql.execSQL("INSERT INTO memory_sessions(id,project,ownership_mode,state,started_at,source_generation,owner_conversation_id,owner_message_id,run_id,process_id,runtime_lease_expires_at,runtime_root_id,resume_event_key) VALUES(?,?,'shared','active',?,?,?,?,?,?,?,?,?)",
            arrayOf(selected,root.project,now(),receipt.sourceGeneration,receipt.conversationId,receipt.ownerMessageId,run,process,expiry(),root.id,key))
        else {
            check(ownerMatches(old) && !old.ended) { "memory_session_owner_conflict" }
            val changed=sql.compileStatement("UPDATE memory_sessions SET run_id=?,process_id=?,runtime_lease_expires_at=?,runtime_root_id=?,resume_event_key=? WHERE id=? AND ended_at IS NULL AND source_generation=? AND run_id IS ? AND process_id IS ?").use { statement ->
                val values=arrayOf(run,process,expiry(),root.id,key,selected,receipt.sourceGeneration,old.run,old.process)
                values.forEachIndexed { index,value -> when(value) { null->statement.bindNull(index+1); is Long->statement.bindLong(index+1,value); else->statement.bindString(index+1,value.toString()) } }
                statement.executeUpdateDelete()
            }
            check(changed==1) { "memory_session_resume_changed" }
        }
        binding
    }

    /** Caller holds its transaction and gate. A missing/deleted binding is never reconstructed. */
    fun requireCurrent(binding: MemorySessionBinding) {
        if (MemorySaveSourceIdentity.existingGeneration(database) != binding.sourceGeneration) throw MemorySessionUnavailableException()
        if (!sql.query("SELECT 1 FROM memory_sessions WHERE id=? AND project=? AND state='active' AND ended_at IS NULL AND ownership_mode='shared' AND source_generation=? AND owner_conversation_id=? AND owner_message_id=? AND run_id=? AND process_id=?",
            arrayOf(binding.id, binding.project, binding.sourceGeneration, binding.ownerConversation, binding.ownerMessageId, binding.runId, binding.processId)).use { it.moveToFirst() }) {
            if(isFinalized(binding)) throw MemorySessionFinalizedException()
            throw MemorySessionUnavailableException()
        }
    }
    /** Passive final answers require the original human source still visible now, not only a retained session row. */
    fun requirePassiveCurrent(binding:MemorySessionBinding) {
        requireCurrent(binding)
        visibleUser(binding.ownerConversation,binding.ownerMessageId)
    }
    /** Bound automatic mutations only. No new ledger or argument proof is minted here. Caller TX. */
    fun requireInvocationForWrite(action:String, witness:com.jarvys.agent.CentralInvocationWitness, reviewTargetId:String?=null, reviewProject:String?=null) {
        require(action in setOf("user_profile_set","memory_update","memory_pin","memory_unpin","memory_forget","memory_review")) { "memory_session_action_invalid" }
        witness.requireInvocationCurrent()
        val source=witness.source()
        check(source.ownerCentralSessionId==MemoryNamespace.CENTRAL &&
            MemorySaveSourceIdentity.existingGeneration(database)==source.storageGeneration) { "memory_save_source_invalid" }
        val session=sql.query("SELECT b.memory_session_id,c.memory_save_input_digest FROM tool_batches b JOIN tool_calls c ON c.conversation_id=b.conversation_id AND c.batch_id=b.batch_id JOIN messages m ON m.conversation_id=b.conversation_id AND m.message_id=b.owner_user_message_id WHERE b.conversation_id=? AND b.batch_id=? AND c.call_id=? AND c.name=? AND c.lifecycle='STARTED' AND b.owner_user_message_id=? AND b.calls_decode_error IS NULL AND m.role='user' AND m.hidden_kind IS NULL AND NOT EXISTS(SELECT 1 FROM tool_results r WHERE r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id)",
            arrayOf(source.ownerCentralSessionId,source.sourceBatchId,source.sourceCallId,action,source.sourceMessageId)).use { c ->
            check(c.moveToFirst() && !c.isNull(0)) { "memory_save_source_invalid" }
            val value=c.getString(0)
            if(action=="memory_review") {
                // A mixed read tool's source witness is not mutation authority. Proof is made by Main
                // before boundedArguments, never recovered from a retained preview or model caller data.
                val proof=if(c.isNull(1)) null else c.getString(1)
                check(reviewTargetId!=null && reviewProject!=null && MemoryReviewArguments.isInputDigest(proof) &&
                    proof==MemoryReviewArguments.selectorDigest(value,reviewTargetId,reviewProject)) { "memory_save_source_invalid" }
            }
            check(!c.moveToNext()) { "memory_save_source_invalid" };value
        }
        witness.requireInvocationCurrent()
        if(action=="memory_review") {
            val unexpired=sql.query("SELECT 1 FROM memory_sessions WHERE id=? AND runtime_lease_expires_at>?",arrayOf(session,now())).use { it.moveToFirst() }
            if(!unexpired) throw MemorySessionUnavailableException()
        }
        renewCurrent(MemorySessionBinding(session,MemoryNamespace.CENTRAL,source.storageGeneration,
            source.ownerCentralSessionId,source.sourceMessageId,source.sourceRunId,source.sourceProcessId))
    }
    /** Whole prepared request is certified before retention; selected batch items are derived from it. */
    internal fun requireJudgmentSource(request:MemoryJudgmentRequest,witness:com.jarvys.agent.CentralInvocationWitness):MemorySessionBinding {
        require(MemoryJudgmentArguments.isTool(request.toolName)) { "memory_judgment_source_invalid" }
        witness.requireInvocationCurrent();val source=witness.source()
        check(source.ownerCentralSessionId==MemoryNamespace.CENTRAL && MemorySaveSourceIdentity.existingGeneration(database)==source.storageGeneration) { "memory_judgment_source_invalid" }
        val session=sql.query("SELECT b.memory_session_id,c.memory_save_input_digest FROM tool_batches b JOIN tool_calls c ON c.conversation_id=b.conversation_id AND c.batch_id=b.batch_id JOIN messages m ON m.conversation_id=b.conversation_id AND m.message_id=b.owner_user_message_id WHERE b.conversation_id=? AND b.batch_id=? AND c.call_id=? AND c.name=? AND c.lifecycle='STARTED' AND b.owner_user_message_id=? AND b.calls_decode_error IS NULL AND m.role='user' AND m.hidden_kind IS NULL AND NOT EXISTS(SELECT 1 FROM tool_results r WHERE r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id)",
            arrayOf(source.ownerCentralSessionId,source.sourceBatchId,source.sourceCallId,request.toolName,source.sourceMessageId)).use { c ->
            check(c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) { "memory_judgment_source_invalid" }
            val current=c.getString(0);val proof=c.getString(1)
            check(MemoryJudgmentArguments.isInputDigest(proof) && proof==MemoryJudgmentArguments.digestTree(MemoryJudgmentArguments.tree(request,current))) { "memory_judgment_source_invalid" }
            check(request.sessionAssertion==null || request.sessionAssertion==current) { "memory_judgment_session_invalid" }
            check(!c.moveToNext()) { "memory_judgment_source_invalid" };current
        }
        val binding=MemorySessionBinding(session,MemoryNamespace.CENTRAL,source.storageGeneration,source.ownerCentralSessionId,
            source.sourceMessageId,source.sourceRunId,source.sourceProcessId)
        requireCurrent(binding);witness.requireInvocationCurrent();return binding
    }

    internal fun requireConsolidationSource(request:MemoryConsolidationRequest,witness:com.jarvys.agent.CentralInvocationWitness):MemorySessionBinding {
        require(request.confirm) { "memory_consolidation_source_invalid" }
        witness.requireInvocationCurrent();val source=witness.source()
        check(source.ownerCentralSessionId==MemoryNamespace.CENTRAL && MemorySaveSourceIdentity.existingGeneration(database)==source.storageGeneration) { "memory_consolidation_source_invalid" }
        val session=sql.query("SELECT b.memory_session_id,c.memory_save_input_digest FROM tool_batches b JOIN tool_calls c ON c.conversation_id=b.conversation_id AND c.batch_id=b.batch_id JOIN messages m ON m.conversation_id=b.conversation_id AND m.message_id=b.owner_user_message_id WHERE b.conversation_id=? AND b.batch_id=? AND c.call_id=? AND c.name=? AND c.lifecycle='STARTED' AND b.owner_user_message_id=? AND b.calls_decode_error IS NULL AND m.role='user' AND m.hidden_kind IS NULL AND NOT EXISTS(SELECT 1 FROM tool_results r WHERE r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id)",
            arrayOf(source.ownerCentralSessionId,source.sourceBatchId,source.sourceCallId,MemoryConsolidationArguments.TOOL,source.sourceMessageId)).use { c ->
            check(c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) { "memory_consolidation_source_invalid" }
            val current=c.getString(0);val proof=c.getString(1)
            check(MemoryConsolidationArguments.isInputDigest(proof) && proof==MemoryConsolidationCodec.digest(MemoryConsolidationArguments.PROOF_PREFIX,MemoryConsolidationArguments.proofTree(request,current))) { "memory_consolidation_source_invalid" }
            check(!c.moveToNext()) { "memory_consolidation_source_invalid" };current
        }
        val binding=MemorySessionBinding(session,MemoryNamespace.CENTRAL,source.storageGeneration,source.ownerCentralSessionId,
            source.sourceMessageId,source.sourceRunId,source.sourceProcessId)
        requireCurrent(binding);witness.requireInvocationCurrent();return binding
    }

    /** Same current producer may renew an expired but un-ended lease; expiry grants no authority. */
    fun renewCurrent(binding: MemorySessionBinding) {
        requireCurrent(binding)
        sql.execSQL("UPDATE memory_sessions SET runtime_lease_expires_at=? WHERE id=?", arrayOf(expiry(), binding.id))
    }
    suspend fun renew(binding: MemorySessionBinding): Boolean = database.withChatWriteTransaction {
        if (isFinalized(binding)) return@withChatWriteTransaction false
        renewCurrent(binding)
        true
    }
    private fun isFinalized(binding: MemorySessionBinding): Boolean {
        if (MemorySaveSourceIdentity.existingGeneration(database) != binding.sourceGeneration) return false
        return sql.query("SELECT 1 FROM memory_sessions s JOIN memory_session_finalizations f ON f.session_id=s.id WHERE s.id=? AND s.project=? AND s.source_generation=? AND s.owner_conversation_id=? AND s.owner_message_id=? AND s.run_id=? AND s.process_id=? AND s.ownership_mode='shared' AND s.state='completed' AND s.ended_at IS NOT NULL",
            arrayOf(binding.id,binding.project,binding.sourceGeneration,binding.ownerConversation,binding.ownerMessageId,binding.runId,binding.processId)).use { it.moveToFirst() }
    }
    suspend fun finish(binding: MemorySessionBinding, outcome: String) = database.withChatWriteTransaction {
        require(outcome in setOf("completed", "cancelled", "failed")) { "memory_session_outcome_invalid" }
        if (!isFinalized(binding)) {
            requireCurrent(binding)
            sql.execSQL("UPDATE memory_sessions SET state=?,ended_at=? WHERE id=?", arrayOf(outcome, now(), binding.id))
        }
    }
    suspend fun finalize(input: MemorySaveOperationInput, namespace: MemoryNamespace): MemorySessionFinalizeReceipt = database.withChatWriteTransaction {
        require(input.action==MemorySessionCommands.FINALIZE) { "memory_session_command_invalid" }
        val prepared=MemorySaveOperations.prepare(database,input)
        val source=prepared.source
        val content=MemoryObservationContent.prepareContent(input.request.text).content
        require(content.isNotEmpty()) { "memory_session_summary_empty" }
        val project=sessionOwner(source.memorySessionId,namespace)
        namespace.requireOwner(project)
        val prior=sql.query("SELECT summary_id FROM memory_session_finalizations WHERE session_id=?",arrayOf(source.memorySessionId)).use { c -> if(c.moveToFirst()) c.getString(0) else null }
        if(prior != null) return@withChatWriteTransaction MemorySessionFinalizeReceipt(source.memorySessionId,prior,true,true)
        MemorySaveOperations.requireSessionForWrite(database,input,prepared)
        val id=UUID.randomUUID().toString()
        val now=now()
        val title=MemoryObservationContent.prepareTitle("Session summary: $project")
        // Forced insertion is intentional: atomic finalize never enters topic/content dedupe.
        sql.execSQL("INSERT INTO memory_entries(id,layer,day,text,category,importance,triggers,provenance,source_ref,usage_count,last_used_at,created_at,updated_at,deleted_at,kind,topic_key,title,narration,event_at,source_conversation_id,source_message_id,through_seq,project,scope,observation_type,normalized_hash,last_seen_at,memory_session_id,review_after) VALUES(?,'curated',?,?,NULL,0.5,'','agent',?,0,NULL,?,?,NULL,'note',NULL,?,'none',NULL,?,?,NULL,?,'project','session_summary',?,?,?,?)",
            arrayOf(id,java.time.LocalDate.now(clock).toString(),content,source.ownerMessageId,now,now,title,source.sessionId,source.ownerMessageId,project,MemoryObservationContent.normalizedHash(content),now,source.memorySessionId, MemoryReview.defaultAfter("session_summary", now)))
        sql.execSQL("INSERT INTO memory_revisions(id,target_type,target_id,before_content,after_content,actor,cause,created_at) VALUES(?,'memory',?,NULL,?,'agent','save',?)",arrayOf(UUID.randomUUID().toString(),id,content,now))
        // The separate free-text summary column is deliberately preserved.
        sql.execSQL("UPDATE memory_sessions SET state='completed',ended_at=? WHERE id=?",arrayOf(now,source.memorySessionId))
        sql.execSQL("INSERT INTO memory_session_finalizations(session_id,summary_id,created_at) VALUES(?,?,?)",arrayOf(source.memorySessionId,id,now))
        MemorySessionFinalizeReceipt(source.memorySessionId,id,false,false)
    }

    suspend fun capturePrompt(binding: MemorySessionBinding): String = database.withChatWriteTransaction {
        requireCurrent(binding); visibleUser(binding.ownerConversation, binding.ownerMessageId)
        val inbox = JSONArray().put(1).put(binding.sourceGeneration).put(binding.ownerConversation).put(binding.ownerMessageId).toString()
        check(!sql.query("SELECT 1 FROM memory_prompt_tombstones WHERE session_id=? AND source_inbox_id=?", arrayOf(binding.id, inbox)).use { it.moveToFirst() }) { "memory_prompt_inbox_deleted" }
        val old = sql.query("SELECT id FROM memory_prompts WHERE session_id=? AND source_inbox_id=?", arrayOf(binding.id, inbox)).use { if(it.moveToFirst()) it.getString(0) else null }
        if(old != null) return@withChatWriteTransaction old
        val text = sql.query("SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind='text' ORDER BY ordinal",
            arrayOf(binding.ownerConversation, binding.ownerMessageId)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) }.joinToString("\n") }
        val prepared = MemoryObservationContent.prepareContent(text).content
        require(prepared.isNotEmpty()) { "memory_prompt_content_required" }
        val id = UUID.randomUUID().toString()
        sql.execSQL("INSERT INTO memory_prompts(id,session_id,project,content,source_inbox_id,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf(id, binding.id, binding.project, prepared, inbox, System.currentTimeMillis()))
        id
    }

    /** Manual save has its own project-owned registration, never repairs a missing runtime binding. Caller TX. */
    fun manualSession(project: String): String {
        fun register(id: String): Boolean {
            val old = sql.query("SELECT project,ownership_mode FROM memory_sessions WHERE id=?", arrayOf(id)).use { if(it.moveToFirst()) it.getString(0) to it.getString(1) else null }
            if (old != null) return old.first == project
            sql.execSQL("INSERT INTO memory_sessions(id,project,ownership_mode,state,started_at) VALUES(?,?,'project_owned','manual',?)", arrayOf(id, project, System.currentTimeMillis()))
            return true
        }
        val preferred = "manual-save-$project"
        if(register(preferred)) return preferred
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(project.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val fallback = "manual-fallback-$hash"
        check(register(fallback)) { "memory_session_owner_conflict" }
        return fallback
    }

    suspend fun searchPrompts(query:String, limit:Long, namespace:MemoryNamespace,
        token:com.jarvys.agent.CancellationToken?=null):List<PersonalMemoryPromptView> = database.withChatReadTransaction {
        token?.throwIfCancelled()
        MemoryPromptSearchIndex.requireCanonical(sql)
        val (guard,ownerArgs)=namespace.projectOnly().clause("p")
        val terms=CanonicalMemorySearch.terms(query)
        val args=ArrayList<Any?>()
        val statement=if(CanonicalMemorySearch.usesLike(terms)) {
            val groups=terms.map { term ->
                val pattern=CanonicalMemorySearch.likeTerm(term); args.add(pattern);args.add(pattern)
                "(p.content LIKE ? ESCAPE '\\' OR p.project LIKE ? ESCAPE '\\')"
            }
            "SELECT p.id,p.session_id,p.project,p.content,p.created_at FROM memory_prompts p WHERE (" +
                groups.joinToString(" AND ") + ") AND $guard ORDER BY p.created_at DESC,p.rowid DESC LIMIT ?"
        } else {
            args.add(CanonicalPromptSearch.matchExpression(query))
            "SELECT p.id,p.session_id,p.project,p.content,p.created_at FROM memory_prompts_fts fts CROSS JOIN memory_prompts p ON p.rowid=fts.rowid WHERE memory_prompts_fts MATCH ? AND $guard ORDER BY fts.rank LIMIT ?"
        }
        args.addAll(ownerArgs);args.add(if(limit<=0) 10L else limit)
        sql.query(statement,args.toTypedArray()).use { c -> buildList {
            while(c.moveToNext()) {
                token?.throwIfCancelled()
                add(PersonalMemoryPromptView(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4)))
            }
        } }.also { token?.throwIfCancelled() }
    }

    suspend fun sessions(namespace: MemoryNamespace): List<PersonalMemorySessionView> = database.withChatReadTransaction {
        val (guard,args)=namespace.ids().clause("s")
        sql.query("SELECT s.id,s.project,s.state,s.started_at,s.ended_at,s.owner_message_id,(SELECT COUNT(*) FROM memory_entries e WHERE e.memory_session_id=s.id),(SELECT COUNT(*) FROM memory_prompts p WHERE p.session_id=s.id) FROM memory_sessions s WHERE $guard ORDER BY s.started_at DESC,s.id",args.toTypedArray()).use { c -> buildList {
            while(c.moveToNext()) add(PersonalMemorySessionView(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),if(c.isNull(4)) null else c.getLong(4),if(c.isNull(5)) null else c.getString(5),c.getLong(6),c.getLong(7)))
        } }
    }
    private fun sessionOwner(id:String,namespace:MemoryNamespace):String {
        val (guard,args)=namespace.ids().clause()
        return sql.query("SELECT project FROM memory_sessions WHERE id=? AND $guard",(listOf(id)+args).toTypedArray()).use { c -> check(c.moveToFirst()) { "memory_session_not_found" };c.getString(0) }
    }
    suspend fun prompts(sessionId:String,namespace:MemoryNamespace):List<PersonalMemoryPromptView> = database.withChatReadTransaction {
        sessionOwner(sessionId,namespace)
        val (guard,args)=namespace.ids().clause()
        sql.query("SELECT id,session_id,project,content,created_at FROM memory_prompts WHERE session_id=? AND $guard ORDER BY created_at,id",(listOf(sessionId)+args).toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(PersonalMemoryPromptView(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4))) } }
    }
    /** Caller TX. Keeps local inbox deletion evidence even after a session is deleted/recreated. */
    fun purgePrompt(id:String, now:Long) {
        val matches = "t.prompt_id=p.id AND COALESCE(t.portable_sync_id,t.prompt_id)=COALESCE(p.portable_sync_id,p.id) " +
            "AND t.project=p.project AND t.session_id IS p.session_id AND t.source_inbox_id IS p.source_inbox_id"
        check(!sql.query("SELECT 1 FROM memory_prompt_tombstones t JOIN memory_prompts p ON p.id=? " +
            "WHERE (t.prompt_id=p.id OR COALESCE(t.portable_sync_id,t.prompt_id)=COALESCE(p.portable_sync_id,p.id)) AND NOT ($matches) LIMIT 1",arrayOf(id)).use { it.moveToFirst() }) {
            "memory_prompt_tombstone_identity_conflict"
        }
        sql.execSQL("INSERT OR IGNORE INTO memory_prompt_tombstones(session_id,source_inbox_id,prompt_id,project,deleted_at,portable_sync_id) SELECT session_id,source_inbox_id,id,project,?,COALESCE(portable_sync_id,id) FROM memory_prompts WHERE id=?",arrayOf(now,id))
        check(sql.query("SELECT 1 FROM memory_prompt_tombstones t JOIN memory_prompts p ON p.id=? WHERE $matches",arrayOf(id)).use { it.moveToFirst() }) {
            "memory_prompt_tombstone_readback_failed"
        }
        sql.execSQL("DELETE FROM memory_prompts WHERE id=?",arrayOf(id))
    }
    suspend fun deletePrompt(id:String,expectedProject:String,namespace:MemoryNamespace):PersonalMemoryDomainDeletionReceipt = database.withChatWriteTransaction {
        val (guard,args)=namespace.ids().clause()
        val project=sql.query("SELECT project FROM memory_prompts WHERE id=? AND $guard",(listOf(id)+args).toTypedArray()).use { c -> check(c.moveToFirst()) { "memory_prompt_not_found" };c.getString(0) }
        require(project == MemoryObservationContent.trimSpace(expectedProject)) { "memory_expected_project_mismatch" }
        purgePrompt(id,System.currentTimeMillis());PersonalMemoryDomainDeletionReceipt(id,project,"prompt")
    }
    suspend fun deleteSession(id:String,expectedProject:String,namespace:MemoryNamespace):PersonalMemoryDomainDeletionReceipt = database.withChatWriteTransaction {
        val project=sessionOwner(id,namespace)
        require(project == MemoryObservationContent.trimSpace(expectedProject)) { "memory_expected_project_mismatch" }
        check(!sql.query("SELECT 1 FROM memory_entries WHERE memory_session_id=? LIMIT 1",arrayOf(id)).use { it.moveToFirst() }) { "memory_session_has_observations" }
        val promptIds=sql.query("SELECT id,project FROM memory_prompts WHERE session_id=?",arrayOf(id)).use { c -> buildList {
            while(c.moveToNext()) { namespace.requireOwner(c.getString(1)); add(c.getString(0)) }
        } }
        val now=System.currentTimeMillis();promptIds.forEach { purgePrompt(it,now) }
        sql.execSQL("INSERT OR IGNORE INTO memory_session_tombstones(id,project,deleted_at) VALUES(?,?,?)",arrayOf(id,project,now))
        sql.execSQL("DELETE FROM memory_sessions WHERE id=?",arrayOf(id))
        PersonalMemoryDomainDeletionReceipt(id,project,"session")
    }
}
