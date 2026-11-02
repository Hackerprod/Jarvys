package com.jarvys.agent.chatdb

import org.json.JSONArray
import org.json.JSONObject

/** Immutable native request evidence, never a skill/tool grant. Included in the original USER digest. */
object CentralSubmissionRequest {
    const val PART_KIND="central_submission_request_v1"
    @JvmStatic fun encode(selectedSkillIds:List<String>):String {
        val values=validated(selectedSkillIds)
        return JSONObject().put("version",1).put("selectedSkillIds",JSONArray(values)).toString()
    }
    @JvmStatic fun decode(payload:String):List<String> {
        val value=JSONObject(payload)
        require(value.keys().asSequence().toSet()==setOf("version","selectedSkillIds"))
        require(value.get("version") is Int && value.getInt("version")==1)
        val ids=value.get("selectedSkillIds");require(ids is JSONArray)
        return validated((0 until ids.length()).map { index -> val id=ids.get(index);require(id is String);id })
    }
    private fun validated(ids:List<String>):List<String> {
        require(ids.all {it.isNotBlank()} && ids.toSet().size==ids.size)
        return java.util.Collections.unmodifiableList(ArrayList(ids))
    }
    internal fun read(database:JarvysChatDatabase,session:String,message:String):List<String> =
        database.chatSql.query("SELECT payload FROM message_parts WHERE conversation_id=? AND message_id=? AND kind=?",arrayOf(session,message,PART_KIND)).use { c ->
            check(c.moveToFirst() && c.getType(0)==android.database.Cursor.FIELD_TYPE_STRING) {"Native submission request evidence missing"}
            val result=decode(c.getString(0));check(!c.moveToNext()) {"Duplicate native submission request evidence"};result
        }
}
