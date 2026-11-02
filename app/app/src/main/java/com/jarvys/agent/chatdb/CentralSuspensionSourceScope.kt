package com.jarvys.agent.chatdb

import com.jarvys.agent.ConversationTurn

/** Internal projection only. Production caller supplies the actual bound claim, never a reconstructed permit. */
internal object CentralSuspensionSourceScope {
    fun select(database:JarvysChatDatabase,session:String,original:String,run:String,
        retained:List<ConversationTurn>,incomingOwners:Map<String,String>,currentCheckpointSources:Set<ConversationTurn.Source>,current:List<ConversationTurn>,initialOwner:String=original):List<ConversationTurn> {
        val baseline=retained.mapNotNull {it.source()}.toSet()
        check(baseline.all {it.conversationId==session})
        // The initial P1 owner is not a later STEER owner and is not the original USER.
        // Its ID comes only from the actual claim's hash-linked confirmed yield receipt.
        if(initialOwner!=original) {
            val source=ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,session,initialOwner,"")
            val retainedOwner=retained.singleOrNull {it.source()==source} ?: error("Suspension initial owner is not retained")
            val metadata=checkNotNull(retainedOwner.incomingMetadata)
            check(metadata.origin==com.jarvys.agent.ConversationInput.Origin.AGENT &&
                com.jarvys.agent.WorkspaceContinuationDelivery.isProtocol(metadata.provenance)) {"Suspension initial owner is not exact P1 input"}
            database.chatSql.query("SELECT role,hidden_kind,input_state FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(session,initialOwner)).use {c ->
                check(c.moveToFirst() && c.getString(0)=="agent" && c.isNull(1) && c.getString(2)=="incorporated" && !c.moveToNext()) {"Suspension initial owner is not incorporated"}
            }
            check(current.any {it.source()==source && it.incomingMetadata?.provenance?.toJson()?.toString()==metadata.provenance.toJson().toString()}) {"Suspension initial owner provenance changed"}
        }
        val owners=incomingOwners.keys+original+initialOwner
        for((id,expected) in incomingOwners) {
            database.chatSql.query("SELECT role,hidden_kind,input_state FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(session,id)).use {c ->
                check(c.moveToFirst() && c.isNull(1) && c.getString(2)=="incorporated") {"Suspension incoming owner missing"}
                val role=c.getString(0);check(role in setOf("user","agent","system"))
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                fun field(value:String){for(shift in listOf(24,16,8,0))digest.update((value.length ushr shift).toByte());value.forEach {digest.update((it.code ushr 8).toByte());digest.update(it.code.toByte())}}
                field("central-incorporated-owner-v1");field(session);field(id);field(role)
                database.chatSql.query("SELECT kind,payload FROM message_parts WHERE conversation_id=? AND message_id=? ORDER BY ordinal",arrayOf(session,id)).use {parts ->while(parts.moveToNext()){check(parts.getType(0)==android.database.Cursor.FIELD_TYPE_STRING && parts.getType(1)==android.database.Cursor.FIELD_TYPE_STRING);field(parts.getString(0));field(parts.getString(1))}}
                check(digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==expected) {"Suspension incoming owner changed"}
            }
        }
        val available=current.mapNotNull {it.source()}.toSet()
        check(available.containsAll(baseline)) {"Suspension retained source missing"}
        for(old in retained.filter {it.source()?.kind==ConversationTurn.Source.Kind.MESSAGE}) {
            check(current.any {it.source()==old.source() && it.role==old.role && it.content==old.content && it.attachments==old.attachments}) {"Suspension retained message changed"}
        }
        return current.filter {turn ->
            val source=turn.source()
            when {
                source==null -> false // Runtime-only retained enrichment is kept by the native checkpoint, not reconstructed here.
                source in baseline -> true
                source.kind==ConversationTurn.Source.Kind.MESSAGE -> database.chatSql.query("SELECT role,owner_user_message_id,run_id FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(session,source.sourceId)).use {c ->
                    c.moveToFirst() && (source.sourceId in incomingOwners || (c.getString(0)=="assistant" && c.getString(1) in owners && c.getString(2)==run))
                }
                source in currentCheckpointSources && source.kind in setOf(ConversationTurn.Source.Kind.BATCH,ConversationTurn.Source.Kind.BATCH_RESULT) ->
                    database.chatSql.query("SELECT owner_user_message_id FROM tool_batches WHERE conversation_id=? AND batch_id=?",arrayOf(session,source.sourceId)).use {c ->c.moveToFirst() && c.getString(0) in owners}
                else -> false
            }
        }
    }
    fun requirePresent(retained:List<ConversationTurn>,included:List<ConversationTurn>,projection:InteractionProtectionSnapshot) {
        val baseline=retained.mapNotNull {it.source()}.toSet()
        val present=included.mapNotNull {it.source()}.toSet()
        val required=baseline.filter {it in projection.protectedSources}.toSet()
        check(present.containsAll(required)) {"Suspension protected retained source missing"}
    }
}
