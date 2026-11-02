package com.jarvys.agent.chatdb

/** Canonical evidence only, called under the existing native database transaction. */
internal object CentralIncomingOwnerEvidence {
    suspend fun confirm(database:JarvysChatDatabase,conversationId:String,incoming:com.jarvys.agent.CoreAgentLoop.IncomingMessage):String {
            check(com.jarvys.agent.workspaces.WorkspaceIdentity.isCentral(conversationId) && incoming.sourceKind=="main_chat")
            val role=incoming.origin.name.lowercase(java.util.Locale.ROOT)
            check(role in setOf("user","agent","system"))
            database.chatSql.query("SELECT role,hidden_kind,input_state FROM messages WHERE conversation_id=? AND message_id=?",arrayOf(conversationId,incoming.id)).use { c ->
                check(c.moveToFirst() && c.getString(0)==role && c.isNull(1) && c.getString(2)=="incorporated" && !c.moveToNext()) {
                    "Central final owner is not an exact incorporated input"
                }
            }
            val row=requireNotNull(com.jarvys.agent.ConversationReader(database).readMessage(conversationId,incoming.id))
            check(row.text==incoming.content) { "Central incorporated content changed" }
            val parts=row.parts.filter { it.kind==com.jarvys.agent.ConversationInput.PROVENANCE_PART_KIND }
            if(incoming.origin==com.jarvys.agent.ConversationInput.Origin.USER) {
                check(incoming.genuineUser && incoming.provenance==null && parts.isEmpty())
            } else {
                check(!incoming.genuineUser && incoming.provenance!=null && parts.size==1)
                val encoded=org.json.JSONObject(parts.single().payload)
                check(encoded.getString("origin")==incoming.origin.name)
                val canonical=requireNotNull(com.jarvys.agent.ConversationInput.Provenance.fromJson(encoded.getJSONObject("provenance")))
                check(canonical.toJson().toString()==incoming.provenance.toJson().toString()) { "Central incorporated provenance changed" }
            }
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            fun field(value:String) {
                for(shift in listOf(24,16,8,0))digest.update((value.length ushr shift).toByte())
                value.forEach { ch -> digest.update((ch.code ushr 8).toByte());digest.update(ch.code.toByte()) }
            }
            field("central-incorporated-owner-v1");field(conversationId);field(incoming.id);field(role)
            row.parts.forEach { field(it.kind);field(it.payload) }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
