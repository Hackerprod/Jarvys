package com.jarvys.agent.chatdb

/** Shared native manual-regeneration resolution, used before hide and on exact action revalidation. */
internal object NativeRegenerationSource {
    private val terminal=setOf("COMPLETED","STOPPED","PARTIAL","FAILED","ERROR","MODEL_ERROR","TIMED_OUT","TIMEOUT","INTERRUPTED","ABORTED","CANCELLED")
    // Null is the existing retained-history representation, not an inferred COMPLETED outcome.
    // Named error outcomes mirror ConversationReader.isAssistantRunStatus.
    fun terminal(status:String?):Boolean=status==null || status in terminal
    fun position(database:JarvysChatDatabase,session:String,messageId:String):List<Long>? = database.chatSql.query(
        "SELECT i.sort_seq,i.sort_sub,i.seq FROM messages m JOIN conversation_items i ON i.conversation_id=m.conversation_id AND i.seq=m.seq WHERE m.conversation_id=? AND m.message_id=?",arrayOf(session,messageId)).use {
        if(!it.moveToFirst())null else listOf(it.getLong(0),it.getLong(1),it.getLong(2)).also {_->check(!it.moveToNext())}
    }
    fun ownerId(database:JarvysChatDatabase,session:String,assistantId:String):String? {
        val sql=database.chatSql
        val explicit=sql.query("SELECT role,owner_user_message_id FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(session,assistantId)).use {
            if(!it.moveToFirst() || it.getString(0)!="assistant")return null
            if(it.isNull(1))null else it.getString(1)
        }
        if(!explicit.isNullOrBlank())return sql.query("SELECT message_id FROM messages WHERE conversation_id=? AND message_id=? AND role='user'",arrayOf(session,explicit)).use {if(it.moveToFirst())it.getString(0)else null}
        val target=position(database,session,assistantId) ?: return null
        return sql.query("SELECT m.message_id FROM messages m JOIN conversation_items i ON i.conversation_id=m.conversation_id AND i.seq=m.seq WHERE m.conversation_id=? AND m.role='user' AND (i.sort_seq<? OR (i.sort_seq=? AND i.sort_sub<?) OR (i.sort_seq=? AND i.sort_sub=? AND i.seq<?)) ORDER BY i.sort_seq DESC,i.sort_sub DESC,i.seq DESC LIMIT 1",
            arrayOf(session,target[0],target[0],target[1],target[0],target[1],target[2])).use {if(it.moveToFirst())it.getString(0)else null}
    }
}
