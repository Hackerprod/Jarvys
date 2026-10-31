package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.ConversationInput
import com.jarvys.agent.connectors.ConnectionStateCoordinator
import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.security.MessageDigest

/** Data plus exact database lifetime, never a serialized execution permission. */
class CentralUserRootCheckpoint internal constructor(
    val storageGeneration:String, val ownerCentralSessionId:String, val originUserMessageId:String,
    val originDigest:String, val lifecycle:ConnectionStateCoordinator.DestinationEvidence,
)

internal suspend fun captureCentralUserRootDigest(database:JarvysChatDatabase,conversationId:String,messageId:String):String =
    database.withChatReadTransaction {
        check(WorkspaceIdentity.isCentral(conversationId) && messageId.isNotBlank())
        val args=arrayOf(conversationId,messageId)
        database.chatSql.query("SELECT m.role,m.hidden_kind FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.conversation_id=? AND m.message_id=?",args).use { c ->
            check(c.moveToFirst() && c.getString(0)=="user" && c.isNull(1) && !c.moveToNext()) {
                "Tree origin is not an exact visible USER row"
            }
        }
        val digest=MessageDigest.getInstance("SHA-256")
        fun field(value:String?) {
            val size=value?.length ?: -1
            for(shift in listOf(24,16,8,0)) digest.update((size ushr shift).toByte())
            value?.forEach { ch -> digest.update((ch.code ushr 8).toByte());digest.update(ch.code.toByte()) }
        }
        field("jarvys-central-user-origin-v1")
        database.chatSql.query("SELECT ordinal,kind,payload FROM message_parts WHERE conversation_id=? AND message_id=? ORDER BY ordinal",args).use { c ->
            while(c.moveToNext()) {
                check(c.getType(0)==Cursor.FIELD_TYPE_INTEGER && c.getType(1)==Cursor.FIELD_TYPE_STRING && c.getType(2)==Cursor.FIELD_TYPE_STRING)
                check(c.getString(1)!=ConversationInput.PROVENANCE_PART_KIND) { "Internal provenance cannot create a USER root" }
                field("part");field(c.getLong(0).toString());field(c.getString(1));field(c.getString(2))
            }
        }
        database.chatSql.query("SELECT ordinal,attachment_id,name,mime_type,size_bytes,kind,relative_path,sha256,file_ref_id FROM message_attachments WHERE conversation_id=? AND message_id=? ORDER BY ordinal",args).use { c ->
            while(c.moveToNext()) { field("attachment");for(i in 0 until c.columnCount) field(if(c.isNull(i)) null else c.getString(i)) }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
