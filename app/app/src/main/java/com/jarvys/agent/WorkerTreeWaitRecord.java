package com.jarvys.agent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conserved evidence only. A decoded wait can never recreate a live park/dispatch capability. */
public final class WorkerTreeWaitRecord {
    public static final String FIELD = "workerTreeWaitV1";
    private final JSONObject value;
    private WorkerTreeWaitRecord(JSONObject source) {
        // Validate the caller's original number types before JSONObject can normalize 1.0 to 1.
        fields(source,"version","current","history"); integer(source,"version",1);
        JSONObject current=source.optJSONObject("current"); validateTicket(current);
        JSONArray history=source.optJSONArray("history"); require(history!=null,"Missing wait history");
        Set<String> ids=new HashSet<>(); ids.add(text(current,"waitId"));
        for(int i=0;i<history.length();i++) {
            JSONObject prior=history.optJSONObject(i);validateTicket(prior);
            require(ids.add(text(prior,"waitId")),"Duplicate wait history");
            require(text(prior,"rootId").equals(text(current,"rootId")) && text(prior,"nodeId").equals(text(current,"nodeId")),"Wait owner changed");
        }
        value=copy(source);
    }
    public static WorkerTreeWaitRecord fromJson(JSONObject value) {return new WorkerTreeWaitRecord(value);}
    public static WorkerTreeWaitRecord create(JSONObject ticket) {
        try{return fromJson(new JSONObject().put("version",1).put("current",ticket).put("history",new JSONArray()));}
        catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
    }
    public WorkerTreeWaitRecord withTicket(JSONObject ticket) {
        validateTicket(ticket); JSONObject current=current();
        if(text(current,"waitId").equals(text(ticket,"waitId"))) {
            require(equal(current,ticket),"Same wait has conflicting payload"); return this;
        }
        require(text(current,"rootId").equals(text(ticket,"rootId")) && text(current,"nodeId").equals(text(ticket,"nodeId")),"Wait owner changed");
        try {JSONObject next=toJson();next.getJSONArray("history").put(current);next.put("current",copy(ticket));return fromJson(next);}
        catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
    }
    public JSONObject current(){return copy(value.optJSONObject("current"));}
    public JSONObject toJson(){return copy(value);}
    public static void validateTicket(JSONObject ticket) {
        fields(ticket,"version","rootId","nodeId","waitId","cycleId","revision","mode","implicit","draft");
        integer(ticket,"version",1);
        for(String key:Arrays.asList("rootId","nodeId","waitId","cycleId")) {String s=text(ticket,key);require(!s.isEmpty()&&s.length()<=1024,"Invalid wait identity");}
        String cycle=text(ticket,"cycleId");require(UUID.fromString(cycle).toString().equals(cycle),"Invalid wait cycle");
        require(number(ticket,"revision")>0,"Invalid wait revision");
        String mode=text(ticket,"mode");require("ANY".equals(mode)||"ALL".equals(mode),"Invalid wait join");
        require(ticket.opt("implicit") instanceof Boolean,"Invalid implicit wait");
        require(!Boolean.TRUE.equals(ticket.opt("implicit"))||"ALL".equals(mode),"Implicit wait must join ALL");
        text(ticket,"draft");
    }
    static boolean equal(Object a,Object b) {
        if(a==b)return true;if(a==null||b==null)return false;
        if(a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;
            for(Iterator<String> it=x.keys();it.hasNext();) {String key=it.next();if(!y.has(key)||!equal(x.opt(key),y.opt(key)))return false;}return true;
        }
        if(a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;
            for(int i=0;i<x.length();i++)if(!equal(x.opt(i),y.opt(i)))return false;return true;
        }
        if((a instanceof Integer||a instanceof Long)&&(b instanceof Integer||b instanceof Long))return ((Number)a).longValue()==((Number)b).longValue();
        return a.equals(b);
    }
    private static void fields(JSONObject object,String... keys) {
        require(object!=null&&object.length()==keys.length,"Invalid wait fields");
        Set<String> allowed=new HashSet<>(Arrays.asList(keys));for(Iterator<String> it=object.keys();it.hasNext();)require(allowed.contains(it.next()),"Unknown wait field");
    }
    private static long number(JSONObject object,String key) {Object v=object.opt(key);require(v instanceof Integer||v instanceof Long,"Invalid wait integer");return ((Number)v).longValue();}
    private static void integer(JSONObject object,String key,long expected){require(number(object,key)==expected,"Unsupported wait version");}
    private static String text(JSONObject object,String key){Object v=object.opt(key);require(v instanceof String,"Invalid wait text");return (String)v;}
    private static JSONObject copy(JSONObject value){require(value!=null,"Missing wait record");try{return new JSONObject(value.toString());}catch(org.json.JSONException e){throw new IllegalArgumentException(e);}}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
