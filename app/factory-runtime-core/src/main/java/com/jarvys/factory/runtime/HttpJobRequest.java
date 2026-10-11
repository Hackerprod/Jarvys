package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable finite HTTP payload. No URI permission, page handle, callback or executable code. */
public final class HttpJobRequest {
    public static final long MAX_DELAY_MS = 30L * 86400000;
    public final String jobId, fingerprint;
    public final NetworkRequest request;
    public final Constraints constraints;
    private final String payload;
    public static final class Constraints {
        public final String network;
        public final boolean requiresCharging;
        public final long minimumLatencyMs, overrideDeadlineMs;
        private Constraints(JSONObject value) throws Exception {
            FactoryConfig.exactKeys(value,"network","requiresCharging","minimumLatencyMs","overrideDeadlineMs");
            network=text(value,"network","any");
            if (!network.equals("any") && !network.equals("unmetered")) throw invalid();
            requiresCharging=bool(value,"requiresCharging",false);
            minimumLatencyMs=integer(value,"minimumLatencyMs",0,0,MAX_DELAY_MS);
            overrideDeadlineMs=integer(value,"overrideDeadlineMs",-1,0,MAX_DELAY_MS);
            if (overrideDeadlineMs>=0 && overrideDeadlineMs<minimumLatencyMs) throw invalid();
        }
        public JSONObject details() {
            try {
                JSONObject value=new JSONObject().put("network",network).put("requiresCharging",requiresCharging).put("minimumLatencyMs",minimumLatencyMs);
                if (overrideDeadlineMs>=0) value.put("overrideDeadlineMs",overrideDeadlineMs);
                return value;
            } catch(Exception impossible) { throw new IllegalStateException(impossible); }
        }
    }
    private HttpJobRequest(JSONObject value) throws Exception {
        FactoryConfig.exactKeys(value,"jobId","request","constraints");
        jobId=id(text(value,"jobId",null));
        JSONObject network=value.getJSONObject("request");
        request=NetworkRequest.fromRequest(network);
        constraints=new Constraints(value.has("constraints")?value.getJSONObject("constraints"):new JSONObject());
        payload=canonical(new JSONObject().put("jobId",jobId).put("request",network).put("constraints",constraints.details()),0);
        if(bytes(payload)>BridgeProtocol.MAX_MESSAGE_BYTES) throw invalid();
        fingerprint=digest("factory-http-job-payload-v1",payload);
    }
    public static HttpJobRequest parse(JSONObject value) throws FactoryException {
        try { if(value==null || bytes(value.toString())>BridgeProtocol.MAX_MESSAGE_BYTES) throw invalid(); return new HttpJobRequest(new JSONObject(value.toString())); }
        catch(FactoryException failure){throw failure;} catch(Exception invalid){throw invalid();}
    }
    public static HttpJobRequest parse(String raw) throws FactoryException { return parse(StrictJson.object(raw,BridgeProtocol.MAX_MESSAGE_BYTES)); }
    public String payloadJson(){return payload;}
    /** Complete disclosure, including full header values and body digest; do not log. */
    public JSONObject authorizationDetails() throws FactoryException { return authorizationDetails("scheduled"); }
    public JSONObject authorizationDetails(String executionMode) throws FactoryException { return authorizationDetails(executionMode,false); }
    public JSONObject authorizationDetails(String executionMode,boolean replayPossible) throws FactoryException {
        if ((!"scheduled".equals(executionMode) && !"foreground_now".equals(executionMode)) || ("scheduled".equals(executionMode) && replayPossible)) throw invalid();
        try { return new JSONObject().put("jobId",jobId).put("request",request.authorizationDetails()).put("constraints",constraints.details()).put("executionMode",executionMode).put("replayPossible",replayPossible); }
        catch(Exception failure){throw new FactoryException("UNAVAILABLE","Job review details unavailable.");}
    }
    public static String id(String value) throws FactoryException {
        if(value==null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid(); return value;
    }
    static String text(JSONObject object,String name,String fallback) throws Exception {
        if(!object.has(name)){if(fallback!=null)return fallback;throw invalid();}
        Object value=object.get(name);if(!(value instanceof String))throw invalid();return (String)value;
    }
    static boolean bool(JSONObject object,String name,boolean fallback) throws Exception {
        if(!object.has(name))return fallback;Object value=object.get(name);if(!(value instanceof Boolean))throw invalid();return (Boolean)value;
    }
    static long integer(JSONObject object,String name,long fallback,long min,long max) throws Exception {
        if(!object.has(name))return fallback;Object value=object.get(name);if(!(value instanceof Integer)&&!(value instanceof Long))throw invalid();
        long n=((Number)value).longValue();if(n<min||n>max)throw invalid();return n;
    }
    static int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    static String token(){return java.util.UUID.randomUUID().toString();}
    static String digest(String... values){
        try {MessageDigest d=MessageDigest.getInstance("SHA-256");for(String s:values){byte[] b=s.getBytes(StandardCharsets.UTF_8);d.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());d.update(b);}
            StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    static String canonical(Object value,int depth) throws Exception {
        if(depth>16)throw invalid();
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();for(Iterator<String> it=o.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);StringBuilder b=new StringBuilder("{");for(String k:keys){if(b.length()>1)b.append(',');b.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k),depth+1));}return b.append('}').toString();}
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)b.append(',');b.append(canonical(a.get(i),depth+1));}return b.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";if(value instanceof String)return JSONObject.quote((String)value);if(value instanceof Boolean)return value.toString();if(value instanceof Number)return JSONObject.numberToString((Number)value);throw invalid();
    }
    static FactoryException invalid(){return new FactoryException("INVALID_ARGUMENT","Invalid bounded HTTP job arguments.");}
}
