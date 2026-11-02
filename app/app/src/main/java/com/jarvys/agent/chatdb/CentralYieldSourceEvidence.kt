package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationTurn
import com.jarvys.agent.CrewContextArtifacts
import com.jarvys.agent.CentralContinuationRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only current-source material. Never a permission or a replacement for the retained checkpoint. */
internal class CentralYieldSourceEvidence private constructor(
    private val session:String, private val retained:List<ConversationTurn>,
    private val current:Map<ConversationTurn.Source,List<ConversationTurn>>,
    private val artifacts:List<Artifact>, private val attachments:List<Attachment>, val fingerprint:String,
) {
    private data class Artifact(val owner:String,val batch:String,val call:String?,val id:String,val state:String,val metadata:JSONObject)
    private data class Attachment(val message:String,val path:String,val size:Long,val sha256:String)
    fun validate(files:File) {
        for(attachment in attachments) {
            check(attachment.sha256.matches(Regex("[a-f0-9]{64}")) && attachment.size>=0)
            val root=File(com.jarvys.agent.StorageGeneration(files).chatRoot(),"attachments/$session")
            val file=File(root,attachment.path)
            check(root.absoluteFile==root.canonicalFile && file.absoluteFile==file.canonicalFile && file.parentFile==root) {"Yield attachment path changed"}
            val before=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
            check(before.isRegularFile && !before.isSymbolicLink && before.size()==attachment.size)
            val hash=java.security.MessageDigest.getInstance("SHA-256");var count=0L
            java.nio.file.Files.newByteChannel(file.toPath(),setOf<java.nio.file.OpenOption>(java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)).use { channel ->
                val buffer=java.nio.ByteBuffer.allocate(8192)
                while(channel.read(buffer)>=0) {buffer.flip();count+=buffer.remaining();check(count<=attachment.size);hash.update(buffer);buffer.clear()}
            }
            val after=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
            check(file.absoluteFile==file.canonicalFile && after.isRegularFile && !after.isSymbolicLink && count==attachment.size && after.size()==before.size() && after.fileKey()==before.fileKey() && after.lastModifiedTime()==before.lastModifiedTime())
            check(hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==attachment.sha256) {"Yield attachment digest changed"}
        }
        val content=hashMapOf<String,String>()
        fun full(owner:String,batch:String,call:String?):List<String> = artifacts.filter {it.owner==owner && it.batch==batch && it.call==call}.map { artifact ->
            check(artifact.state==ModelArtifactState.RETAINED) {"Yield full source artifact is unavailable or bounded"}
            content.getOrPut(artifact.id) {
                CrewContextArtifacts.readConfirmedSource(files,session,artifact.id,artifact.metadata)
            }
        }
        for(turn in retained) {
            val source=turn.source()
            if(source==null) {
                check(turn.kind!=ConversationTurn.Kind.TOOL_CALLS && turn.kind!=ConversationTurn.Kind.TOOL_RESULT &&
                    !(turn.kind==ConversationTurn.Kind.MESSAGE && turn.role=="user")) {"Yield execution-bearing source missing"}
                continue // Inert runtime-only enrichment remains authenticated by the confirmed native checkpoint.
            }
            check(source.conversationId==session)
            val candidates=checkNotNull(current[source]) {"Yield source no longer available"}
            check(candidates.any { candidate -> equivalent(turn,candidate,source,::full) }) {"Yield source payload changed or full artifact unavailable"}
        }
    }
    private fun equivalent(a:ConversationTurn,b:ConversationTurn,source:ConversationTurn.Source,
        full:(String,String,String?)->List<String>):Boolean {
        if(a.kind!=b.kind || a.role!=b.role || a.toolCallId!=b.toolCallId || a.toolName!=b.toolName ||
            a.summarizedMessageCount!=b.summarizedMessageCount || a.attachments!=b.attachments || incoming(a)!=incoming(b) || outputs(a)!=outputs(b))return false
        if(a.content!=b.content) {
            val owner=if(source.kind==ConversationTurn.Source.Kind.BATCH_RESULT)"tool_result" else if(source.kind==ConversationTurn.Source.Kind.BATCH)"batch_text" else return false
            if(full(owner,source.sourceId,source.callId.takeIf {it.isNotEmpty()}).none {it==a.content})return false
        }
        if(a.toolCalls.size!=b.toolCalls.size)return false
        for(i in a.toolCalls.indices) {
            val x=a.toolCalls[i];val y=b.toolCalls[i]
            if(x.id!=y.id || x.name!=y.name || x.invalidArgumentsReason!=y.invalidArgumentsReason)return false
            val expected=JSONObject(x.arguments);val actual=JSONObject(y.arguments)
            if(!CentralContinuationRecord.equal(expected,actual)) {
                if(source.kind!=ConversationTurn.Source.Kind.BATCH || !actual.has("_retained_arguments"))return false
                if(full("tool_call_args",source.sourceId,x.id).none {CentralContinuationRecord.equal(expected,JSONObject(it))})return false
            }
        }
        return true
    }
    companion object {
        private fun incoming(turn:ConversationTurn):String?=turn.incomingMetadata?.let {m ->JSONArray().put(m.id).put(m.origin.name).put(m.source).put(m.sender).put(m.type).put(m.untrusted).put(JSONArray(m.references)).put(m.provenance?.toJson() ?: JSONObject.NULL).toString()}
        private fun outputs(turn:ConversationTurn)=JSONArray().also { array ->turn.providerOutputItems.forEach {array.put(JSONArray().put(it.kind.name).put(it.value).put(it.provider).put(it.api).put(it.model))}}.toString()
        private fun describe(turn:ConversationTurn)=JSONArray().put(turn.kind.name).put(turn.role).put(turn.content)
            .put(turn.toolCallId).put(turn.toolName).put(turn.summarizedMessageCount).put(incoming(turn)).put(outputs(turn))
            .put(JSONArray().also {a ->turn.attachments.forEach {a.put(it.toJson())}})
            .put(JSONArray().also {a ->turn.toolCalls.forEach {a.put(JSONArray().put(it.id).put(it.name).put(it.invalidArgumentsReason).put(JSONObject(it.arguments)))}})
        fun capture(database:JarvysChatDatabase,origin:CentralUserRootCheckpoint,retained:List<ConversationTurn>,context:List<ConversationTurn>):CentralYieldSourceEvidence {
            val sources=retained.mapNotNull {it.source()}.toSet()
            val candidates=context.filter {it.source() in sources}.groupBy {checkNotNull(it.source())}
            for(batch in sources.filter {it.kind in setOf(ConversationTurn.Source.Kind.BATCH,ConversationTurn.Source.Kind.BATCH_RESULT)}.map {it.sourceId}.toSet()) {
                database.chatSql.query("SELECT c.call_id,c.lifecycle,r.lifecycle,c.name,r.tool_name,c.lifecycle_seq,r.seq FROM tool_calls c LEFT JOIN tool_results r ON r.conversation_id=c.conversation_id AND r.batch_id=c.batch_id AND r.call_id=c.call_id WHERE c.conversation_id=? AND c.batch_id=?",arrayOf(origin.ownerCentralSessionId,batch)).use { c ->
                    var found=false
                    while(c.moveToNext()) {
                        found=true
                        // Native call rows retain dispatch state; terminal receipts live in tool_results.
                        val terminalPair=(c.getString(1)=="STARTED" && c.getString(2)=="RESULT") ||
                            (c.getString(1)=="NEVER_LAUNCHED" && c.getString(2)=="NEVER_LAUNCHED")
                        check(terminalPair && c.getString(3)==c.getString(4) &&
                            c.getType(5)==android.database.Cursor.FIELD_TYPE_INTEGER &&
                            c.getType(6)==android.database.Cursor.FIELD_TYPE_INTEGER && c.getLong(5)<c.getLong(6)) {
                            "Yield source has unresolved tool effects"
                        }
                    }
                    check(found) {"Yield tool batch missing"}
                }
            }
            val rows=mutableListOf<Artifact>()
            database.chatSql.query("SELECT r.owner_kind,r.batch_id,r.call_id,a.artifact_id,a.state,a.path,a.sha256,a.chars,a.bytes FROM transcript_artifact_refs r JOIN model_artifacts a ON a.conversation_id=r.conversation_id AND a.artifact_id=r.artifact_id WHERE r.conversation_id=? ORDER BY r.owner_kind,r.batch_id,r.call_id,a.artifact_id",arrayOf(origin.ownerCentralSessionId)).use {c ->
                while(c.moveToNext())if(sources.any {it.sourceId==c.getString(1) && it.kind in setOf(ConversationTurn.Source.Kind.BATCH,ConversationTurn.Source.Kind.BATCH_RESULT)}) {
                    for (column in listOf(0,1,3,4,5,6)) check(c.getType(column)==android.database.Cursor.FIELD_TYPE_STRING) {"Yield artifact metadata type changed"}
                    check(c.isNull(2) || c.getType(2)==android.database.Cursor.FIELD_TYPE_STRING)
                    check(c.getType(7)==android.database.Cursor.FIELD_TYPE_INTEGER && c.getType(8)==android.database.Cursor.FIELD_TYPE_INTEGER)
                    rows.add(Artifact(c.getString(0),c.getString(1),if(c.isNull(2))null else c.getString(2),c.getString(3),c.getString(4),
                        JSONObject().put("path",c.getString(5)).put("sha256",c.getString(6)).put("chars",c.getLong(7)).put("bytes",c.getLong(8))))
                }
            }
            val attachments=mutableListOf<Attachment>()
            for(source in sources.filter {it.kind==ConversationTurn.Source.Kind.MESSAGE}) {
                database.chatSql.query("SELECT relative_path,size_bytes,sha256 FROM message_attachments WHERE conversation_id=? AND message_id=? ORDER BY ordinal",arrayOf(origin.ownerCentralSessionId,source.sourceId)).use {c ->
                    while(c.moveToNext()) {
                        check(c.getType(0)==android.database.Cursor.FIELD_TYPE_STRING && c.getType(1)==android.database.Cursor.FIELD_TYPE_INTEGER && c.getType(2)==android.database.Cursor.FIELD_TYPE_STRING)
                        attachments.add(Attachment(source.sourceId,c.getString(0),c.getLong(1),c.getString(2)))
                    }
                }
            }
            val proof=JSONArray().put(origin.originDigest)
            attachments.forEach {proof.put(JSONArray().put(it.message).put(it.path).put(it.size).put(it.sha256))}
            candidates.forEach {(source,turns) ->proof.put(JSONArray().put(source.kind.name).put(source.sourceId).put(source.callId).put(JSONArray().also {a ->turns.forEach {a.put(describe(it))}}))}
            rows.forEach {proof.put(JSONArray().put(it.owner).put(it.batch).put(it.call).put(it.id).put(it.state).put(it.metadata))}
            return CentralYieldSourceEvidence(origin.ownerCentralSessionId,retained.toList(),candidates,rows,attachments,proof.toString())
        }
    }
}
