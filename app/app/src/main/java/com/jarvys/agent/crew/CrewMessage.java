package com.jarvys.agent.crew;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable, in-memory Crew bus message. */
public final class CrewMessage {
    public enum Type { TASK, FINDING, CRITIQUE, QUESTION, ANSWER, RESULT, STATUS, USER }
    public final String id;
    public final String conversationId;
    public final String from;
    public final String to;
    public final Type type;
    public final String text;
    public final List<String> refs;
    public final long timestampMillis;
    public final com.jarvys.agent.ToolActivity activity;

    public CrewMessage(String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis) {
        this(UUID.randomUUID().toString(), conversationId, from, to, type, text, refs, timestampMillis);
    }
    public CrewMessage(String id, String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis) {
        this(id, conversationId, from, to, type, text, refs, timestampMillis, null);
    }
    public CrewMessage(String id, String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis, com.jarvys.agent.ToolActivity activity) {
        this.activity = activity;
        this.id = require(id, "id");
        this.conversationId = require(conversationId, "conversationId");
        this.from = require(from, "from");
        this.to = require(to, "to");
        this.type = java.util.Objects.requireNonNull(type, "type");
        this.text = text == null ? "" : text;
        this.refs = Collections.unmodifiableList(new ArrayList<>(refs == null ? Collections.emptyList() : refs));
        this.timestampMillis = timestampMillis;
    }
    public org.json.JSONObject toJson() {
        try { org.json.JSONObject row = new org.json.JSONObject().put("id",id).put("conversationId",conversationId)
                .put("from",from).put("to",to).put("type",type.name()).put("text",text)
                .put("refs",new org.json.JSONArray(refs)).put("timestampMillis",timestampMillis);
            if (activity != null) row.put("origin","local_activity").put("activity",activity.toJson());
            return row;
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    public static CrewMessage fromJson(org.json.JSONObject row) {
        List<String> refs=new ArrayList<>(); org.json.JSONArray items=row.optJSONArray("refs");
        if(items!=null) for(int i=0;i<items.length();i++) refs.add(items.optString(i));
        com.jarvys.agent.ToolActivity activity="local_activity".equals(row.optString("origin"))
                ? com.jarvys.agent.ToolActivity.fromJson(row.optJSONObject("activity")) : null;
        if (activity==null && "local_activity".equals(row.optString("origin"))) {
            String id=row.optString("id");
            activity=new com.jarvys.agent.ToolActivity(id,"unreadable:"+id,"","",row.optString("text"),"","",
                "tool_interrupted",row.optJSONObject("activity")==null ? "" : row.optJSONObject("activity").toString(),"","","",row.optLong("timestampMillis"));
        }
        return new CrewMessage(row.optString("id"),row.optString("conversationId"),row.optString("from"),
                row.optString("to"),Type.valueOf(row.optString("type")),row.optString("text"),refs,row.optLong("timestampMillis"),activity);
    }
    public CrewMessage withActivity(com.jarvys.agent.ToolActivity value) {
        return new CrewMessage(id,conversationId,from,to,type,text,refs,timestampMillis,value);
    }
    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
