package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.json.JSONObject
import java.security.MessageDigest

/** Read-only source projection. This value cannot authorize a write or a provider start. */
data class ProactiveReplySourceSnapshot(
    val physicalGeneration:String,
    val sourceMessageId:String,
    val seq:Long,
    val role:String,
    val threadKey:String,
    val decisionId:String?,
    val text:String,
    val sourceFingerprint:String,
    val currentEligible:Boolean,
    val suggestedReplies:List<SuggestedReplyInput>,
    val consumed:Boolean,
    val inlineActionId:String,
)

/** Immutable exact acceptance evidence, never a live native interaction or inherited authority. */
data class ProactiveReplyAcceptanceValue(
    val physicalGeneration:String,
    val sessionId:String,
    val kind:String,
    val actionId:String,
    val sourceMessageId:String,
    val sourceFingerprint:String,
    val threadKey:String,
    val replyToMessageId:String?,
    val selectedOrdinal:Int?,
    val textSha256:String,
    val referentMessageId:String?,
    val referentFingerprint:String?,
)

/** Canonical schema has one spelling; duplicate keys, unknown keys and JSON coercions are rejected. */
object ProactiveReplyAcceptance {
    const val PART_KIND="proactive_reply_acceptance_v1"
    const val OPTION="OPTION"
    const val INLINE="INLINE"
    private val digestPattern=Regex("[a-f0-9]{64}")
    private val keys=setOf("version","physicalGeneration","sessionId","kind","actionId","sourceMessageId",
        "sourceFingerprint","threadKey","replyToMessageId","selectedOrdinal","textSha256","referentMessageId","referentFingerprint")

    /** Fixed UTF-16 code units, not platform UTF-8 replacement of unpaired surrogates. */
    @JvmStatic fun digestFields(vararg fields:String?):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fields.forEach { value ->
            val size=value?.length ?: -1
            for(shift in listOf(24,16,8,0))digest.update((size ushr shift).toByte())
            value?.forEach { ch ->digest.update((ch.code ushr 8).toByte());digest.update(ch.code.toByte()) }
        }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    @JvmStatic fun actionId(generation:String,session:String,thread:String,source:String,kind:String):String {
        require(generation.isNotBlank() && WorkspaceIdentity.isCentral(session) && thread.isNotBlank() && source.isNotBlank())
        require(kind==OPTION || kind==INLINE)
        return if(kind==OPTION)digestFields("jarvys-proactive-reply-action-v1",generation,session,source,kind)
        else digestFields("jarvys-proactive-reply-action-v1",generation,session,thread,source,kind)
    }
    @JvmStatic fun submissionId(actionId:String):String {
        require(digestPattern.matches(actionId));return "proactive-reply-v1:$actionId"
    }
    @JvmStatic fun textDigest(text:String):String=digestFields("jarvys-proactive-reply-text-v1",text)
    private fun checked(value:ProactiveReplyAcceptanceValue):ProactiveReplyAcceptanceValue {
        require(value.physicalGeneration.isNotBlank() && WorkspaceIdentity.isCentral(value.sessionId))
        require(value.threadKey.isNotBlank() && value.sourceMessageId.isNotBlank())
        require(digestPattern.matches(value.sourceFingerprint) && digestPattern.matches(value.textSha256))
        require(value.actionId==actionId(value.physicalGeneration,value.sessionId,value.threadKey,value.sourceMessageId,value.kind))
        if(value.kind==OPTION)require(value.selectedOrdinal!=null && value.selectedOrdinal>=0 && value.replyToMessageId==value.sourceMessageId)
        else require(value.kind==INLINE && value.selectedOrdinal==null && value.replyToMessageId==null)
        require((value.referentMessageId==null)==(value.referentFingerprint==null))
        value.referentFingerprint?.let {require(digestPattern.matches(it))}
        value.referentMessageId?.let {require(it.isNotBlank())}
        if(value.kind==OPTION)require(value.referentMessageId==value.sourceMessageId && value.referentFingerprint==value.sourceFingerprint)
        return value
    }
    // Escape every code unit to conserve exact UTF-16, including unpaired surrogates, on every JVM.
    private fun quote(value:String?):String=if(value==null)"null" else buildString {
        append('"');value.forEach {append("\\u");append(it.code.toString(16).padStart(4,'0'))};append('"')
    }
    @JvmStatic fun encode(value:ProactiveReplyAcceptanceValue):String {
        checked(value)
        return "{\"version\":1,\"physicalGeneration\":"+quote(value.physicalGeneration)+
            ",\"sessionId\":"+quote(value.sessionId)+",\"kind\":"+quote(value.kind)+
            ",\"actionId\":"+quote(value.actionId)+",\"sourceMessageId\":"+quote(value.sourceMessageId)+
            ",\"sourceFingerprint\":"+quote(value.sourceFingerprint)+",\"threadKey\":"+quote(value.threadKey)+
            ",\"replyToMessageId\":"+quote(value.replyToMessageId)+",\"selectedOrdinal\":"+(value.selectedOrdinal?.toString() ?: "null")+
            ",\"textSha256\":"+quote(value.textSha256)+",\"referentMessageId\":"+quote(value.referentMessageId)+",\"referentFingerprint\":"+quote(value.referentFingerprint)+"}"
    }
    @JvmStatic fun decode(payload:String):ProactiveReplyAcceptanceValue {
        val json=JSONObject(payload)
        require(json.keys().asSequence().toSet()==keys)
        require(json.get("version") is Int && json.getInt("version")==1)
        fun text(key:String):String {val value=json.get(key);require(value is String);return value}
        fun optionalText(key:String):String? {val value=json.get(key);if(value===JSONObject.NULL)return null;require(value is String);return value}
        val ordinal=json.get("selectedOrdinal").let {if(it===JSONObject.NULL)null else {require(it is Int);it}}
        val reply=json.get("replyToMessageId").let {if(it===JSONObject.NULL)null else {require(it is String);it}}
        val value=checked(ProactiveReplyAcceptanceValue(text("physicalGeneration"),text("sessionId"),text("kind"),text("actionId"),
            text("sourceMessageId"),text("sourceFingerprint"),text("threadKey"),reply,ordinal,text("textSha256"),optionalText("referentMessageId"),optionalText("referentFingerprint")))
        require(encode(value)==payload) {"Noncanonical or duplicate proactive acceptance fields"}
        return value
    }
}

/** All callers own a native Room lease. Never invokes root capture or recursively validates ancestors. */
internal object ProactiveReplySourceValidation {
    private data class Row(val id:String,val stable:String,val seq:Long,val role:String,val status:String?,val thread:String?,
        val decision:String?,val replyTo:String?,val hidden:String?,val consumed:Long?,val interrupt:String?)
    private fun string(c:Cursor,index:Int):String {check(c.getType(index)==Cursor.FIELD_TYPE_STRING);return c.getString(index)}
    private fun nullable(c:Cursor,index:Int):String?=if(c.isNull(index))null else string(c,index)
    private fun readRow(database:JarvysChatDatabase,session:String,id:String):Row? = database.chatSql.query(
        "SELECT message_id,stable_id,seq,role,status,proactive_thread_key,proactive_decision_id,proactive_reply_to_message_id,hidden_kind,suggested_replies_consumed_seq,interrupt_request_id FROM messages WHERE conversation_id=? AND message_id=?",
        arrayOf(session,id)).use { c ->
        if(!c.moveToFirst())return@use null
        check(c.getType(2)==Cursor.FIELD_TYPE_INTEGER)
        val consumed=if(c.isNull(9))null else {check(c.getType(9)==Cursor.FIELD_TYPE_INTEGER);c.getLong(9)}
        Row(string(c,0),string(c,1),c.getLong(2),string(c,3),nullable(c,4),nullable(c,5),nullable(c,6),nullable(c,7),nullable(c,8),consumed,nullable(c,10))
            .also {check(it.seq>0 && !c.moveToNext())}
    }
    private fun parts(database:JarvysChatDatabase,session:String,id:String):List<MessagePartInput> = database.chatSql.query(
        "SELECT ordinal,kind,payload FROM message_parts WHERE conversation_id=? AND message_id=? ORDER BY ordinal",arrayOf(session,id)).use { c ->
        buildList {while(c.moveToNext()) {check(c.getType(0)==Cursor.FIELD_TYPE_INTEGER);add(MessagePartInput(string(c,1),string(c,2)))}}
    }
    private fun options(database:JarvysChatDatabase,session:String,id:String):List<SuggestedReplyInput> = database.chatSql.query(
        "SELECT ordinal,label,text FROM proactive_suggested_replies WHERE conversation_id=? AND message_id=? ORDER BY ordinal",arrayOf(session,id)).use { c ->
        buildList {while(c.moveToNext()) {check(c.getType(0)==Cursor.FIELD_TYPE_INTEGER && c.getLong(0)==size.toLong());add(SuggestedReplyInput(string(c,1),string(c,2)))}}
    }
    fun generation(database:JarvysChatDatabase):String=database.chatSql.query("SELECT value FROM storage_meta WHERE key=?",
        arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { c ->
        check(c.moveToFirst());string(c,0).also {check(runCatching {java.util.UUID.fromString(it).toString()==it}.getOrDefault(false) && !c.moveToNext())}
    }
    private fun eligible(row:Row,parts:List<MessagePartInput>):Boolean = row.thread?.isNotBlank()==true &&
        (row.role=="user" && row.status==null || row.role=="assistant" && NativeRegenerationSource.terminal(row.status)) &&
        row.interrupt==null && parts.none {it.kind==ConversationInput.PROVENANCE_PART_KIND}
    private fun fingerprint(row:Row,parts:List<MessagePartInput>,options:List<SuggestedReplyInput>):String {
        val fields=mutableListOf<String?>("jarvys-proactive-reply-source-v1",row.id,row.stable,row.seq.toString(),row.role,row.status,row.thread,row.decision,row.replyTo)
        parts.filter {it.kind==MessagePartKinds.TEXT}.forEachIndexed {index,part ->fields.addAll(listOf("text",index.toString(),part.payload))}
        options.forEachIndexed {index,option ->fields.addAll(listOf("option",index.toString(),option.label,option.text))}
        return ProactiveReplyAcceptance.digestFields(*fields.toTypedArray())
    }
    private fun latestId(database:JarvysChatDatabase,session:String,thread:String):String? {
        val ids=database.chatSql.query("SELECT message_id FROM messages WHERE conversation_id=? AND proactive_thread_key=? AND hidden_kind IS NULL AND role IN ('user','assistant') ORDER BY seq DESC",arrayOf(session,thread)).use { c ->buildList {while(c.moveToNext())add(string(c,0))}}
        return ids.firstOrNull { id ->val row=checkNotNull(readRow(database,session,id));eligible(row,parts(database,session,id))}
    }
    /** Visibility/latest status is a first-admission check only, not part of immutable fingerprint. */
    fun read(database:JarvysChatDatabase,sourceId:String?,thread:String?,admissionState:Boolean=true):ProactiveReplySourceSnapshot? {
        val session=WorkspaceIdentity.CENTRAL_SESSION
        require(!sourceId.isNullOrBlank() || !thread.isNullOrBlank())
        val id=sourceId ?: latestId(database,session,checkNotNull(thread)) ?: return null
        val row=readRow(database,session,id) ?: return null
        val sourceThread=row.thread?.takeIf(String::isNotBlank) ?: return null
        if(thread!=null)check(thread==sourceThread) {"Proactive source thread changed"}
        val parts=parts(database,session,id)
        check(eligible(row,parts)) {"Proactive source is not a settled genuine thread message"}
        val generation=generation(database)
        val replies=options(database,session,id)
        return ProactiveReplySourceSnapshot(generation,id,row.seq,row.role,sourceThread,row.decision,
            parts.filter {it.kind==MessagePartKinds.TEXT}.joinToString(""){it.payload},fingerprint(row,parts,replies),
            admissionState && row.hidden==null && latestId(database,session,sourceThread)==id,java.util.Collections.unmodifiableList(replies),row.consumed!=null,
            ProactiveReplyAcceptance.actionId(generation,session,sourceThread,id,ProactiveReplyAcceptance.INLINE))
    }
    fun requireAdmission(database:JarvysChatDatabase,source:ProactiveReplySourceSnapshot,kind:String,ordinal:Int?) {
        val row=checkNotNull(readRow(database,WorkspaceIdentity.CENTRAL_SESSION,source.sourceMessageId))
        check(row.hidden==null) {"Proactive source was hidden before admission"}
        when(kind) {
            ProactiveReplyAcceptance.OPTION -> {
                check(source.role=="assistant" && !source.decisionId.isNullOrBlank() && ordinal!=null && ordinal>=0 && ordinal<source.suggestedReplies.size)
                check(!source.consumed && source.suggestedReplies[ordinal].text.trim().isNotEmpty()) {"Proactive option was consumed or empty"}
            }
            ProactiveReplyAcceptance.INLINE ->check(ordinal==null && source.currentEligible) {"Inline source is no longer the current thread head"}
            else ->error("Unknown proactive reply kind")
        }
    }
    /** Same latest-prior-notice semantics, pinned once without inheriting any ancestor authority. */
    fun referent(database:JarvysChatDatabase,source:ProactiveReplySourceSnapshot,kind:String):ProactiveReplySourceSnapshot? {
        if(kind==ProactiveReplyAcceptance.OPTION)return source
        val id=database.chatSql.query("SELECT message_id FROM messages WHERE conversation_id=? AND proactive_thread_key=? AND proactive_decision_id IS NOT NULL AND seq<=? ORDER BY seq DESC LIMIT 1",
            arrayOf(WorkspaceIdentity.CENTRAL_SESSION,source.threadKey,source.seq)).use {c ->if(c.moveToFirst())string(c,0) else null} ?: return null
        val notice=checkNotNull(read(database,id,source.threadKey,false))
        check(notice.role=="assistant" && !notice.decisionId.isNullOrBlank())
        return notice
    }
    /** Context readers also compare the full stored root, without a validator -> digest -> validator cycle. */
    fun validateContext(database:JarvysChatDatabase,session:String,userId:String):ProactiveReplyAcceptanceValue? {
        val proof=validateUser(database,session,userId)
        if(proof!=null)captureCentralUserRootDigestInTransaction(database,session,userId)
        return proof
    }
    /** Called before every root hash and before referent reads, including the thread-null path. */
    fun validateUser(database:JarvysChatDatabase,session:String,userId:String,allowUnregisteredAcceptance:Boolean=false):ProactiveReplyAcceptanceValue? {
        val row=readRow(database,session,userId) ?: error("Proactive reply USER is missing")
        val parts=parts(database,session,userId)
        val proofs=parts.filter {it.kind==ProactiveReplyAcceptance.PART_KIND}
        val request=parts.any {it.kind==CentralSubmissionRequest.PART_KIND}
        val submissions=CentralTurnSubmissionRepository(database)
        val submission=if(submissions.available())database.chatSql.query("SELECT submission_id,storage_generation,user_seq FROM central_turn_submissions WHERE conversation_id=? AND user_message_id=?",arrayOf(session,userId)).use {c ->
            if(!c.moveToFirst())null else {
                check(c.getType(2)==Cursor.FIELD_TYPE_INTEGER)
                Triple(string(c,0),string(c,1),c.getLong(2)).also {check(!c.moveToNext())}
            }
        } else null
        val positive=submission!=null
        if(proofs.isEmpty()) {
            check(!(row.thread!=null && (positive || request)) && !row.stable.startsWith("proactive-reply-v1:")) {"Positive threaded USER acceptance is missing"}
            return null // Retained non-positive thread history is not backfilled or upgraded.
        }
        check(proofs.size==1 && row.role=="user" && row.status==null && row.decision==null && row.interrupt==null && WorkspaceIdentity.isCentral(session))
        val proof=ProactiveReplyAcceptance.decode(proofs.single().payload)
        check(proof.sessionId==session && proof.physicalGeneration==generation(database) && proof.threadKey==row.thread && proof.replyToMessageId==row.replyTo)
        check(row.stable==ProactiveReplyAcceptance.submissionId(proof.actionId))
        check(positive || allowUnregisteredAcceptance) {"Typed proactive USER has no positive submission receipt"}
        if(submission!=null)check(submission.first==row.stable && submission.second==proof.physicalGeneration && submission.third==row.seq)
        check(parts.count {it.kind==CentralSubmissionRequest.PART_KIND}==1 && CentralSubmissionRequest.read(database,session,userId).isEmpty())
        check(parts.none {it.kind==ConversationInput.PROVENANCE_PART_KIND})
        val text=parts.filter {it.kind==MessagePartKinds.TEXT}.joinToString(""){it.payload}
        check(text.isNotBlank() && text==text.trim() && proof.textSha256==ProactiveReplyAcceptance.textDigest(text))
        check(!database.chatSql.query("SELECT 1 FROM message_attachments WHERE conversation_id=? AND message_id=? LIMIT 1",arrayOf(session,userId)).use {it.moveToFirst()})
        val source=checkNotNull(read(database,proof.sourceMessageId,null,false)) {"Accepted proactive source is missing"}
        check(source.seq<row.seq && source.threadKey==proof.threadKey && source.sourceFingerprint==proof.sourceFingerprint) {"Accepted proactive source changed"}
        val referent=referent(database,source,proof.kind)
        check(referent?.sourceMessageId==proof.referentMessageId && referent?.sourceFingerprint==proof.referentFingerprint) {"Accepted proactive referent changed"}
        if(proof.kind==ProactiveReplyAcceptance.OPTION) {
            val ordinal=checkNotNull(proof.selectedOrdinal)
            check(source.role=="assistant" && !source.decisionId.isNullOrBlank() && ordinal<source.suggestedReplies.size && source.suggestedReplies[ordinal].text.trim()==text)
            val consumed=checkNotNull(readRow(database,session,source.sourceMessageId)?.consumed) {"Accepted option marker is missing"}
            check(consumed>row.seq && database.chatSql.query("SELECT item_kind,item_key FROM conversation_items WHERE conversation_id=? AND seq=?",arrayOf(session,consumed)).use { c ->
                c.moveToFirst() && string(c,0)=="proactive_reply_used" && string(c,1)==source.sourceMessageId && !c.moveToNext()
            }) {"Accepted option marker changed"}
        }
        return proof
    }
}
