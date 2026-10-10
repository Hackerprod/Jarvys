package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Closed typed database grammar. Neither identifiers nor values can introduce SQL syntax. */
public final class DatabaseRequest {
    private DatabaseRequest() { }
    public static final int MAX_TABLES=8, MAX_COLUMNS=16, MAX_STEPS=16, MAX_OPERATIONS=64;
    public static final int MAX_ROWS=10000, MAX_CELL_BYTES=4096, MAX_ROW_BYTES=16384;
    public static final int MAX_ARGUMENT_BYTES=262144, MAX_RESULT_BYTES=262144, MAX_SELECT_ROWS=100;
    public static final long MAX_DATABASE_BYTES=16L*1024*1024, MAX_SAFE_INTEGER=9007199254740991L;
    public static void validate(String method, JSONObject args) throws FactoryException {
        try {
            if (args.toString().getBytes(StandardCharsets.UTF_8).length>MAX_ARGUMENT_BYTES) throw invalid();
            switch (method) {
                case "database.info": case "database.close": case "database.cancel": exact(args); break;
                case "database.migrate":
                    exact(args,"fromVersion","toVersion","steps");
                    int from=integer(args,"fromVersion",0,999), to=integer(args,"toVersion",1,1000);
                    if(to!=from+1) throw invalid();
                    JSONArray steps=args.getJSONArray("steps"); array(steps,MAX_STEPS);
                    for(int i=0;i<steps.length();i++) {
                        JSONObject step=steps.getJSONObject(i); String kind=string(step,"kind"); identifier(string(step,"table"));
                        if("createTable".equals(kind)) {
                            exact(step,"kind","table","columns"); columns(step.getJSONObject("columns"));
                        } else if("addColumn".equals(kind)) {
                            exact(step,"kind","table","column","definition"); identifier(string(step,"column"));
                            definition(step.getJSONObject("definition"));
                            if(!step.getJSONObject("definition").getBoolean("nullable")) throw invalid();
                        } else throw invalid();
                    }
                    break;
                case "database.transact":
                    exact(args,"version","operations"); integer(args,"version",1,1000);
                    JSONArray operations=args.getJSONArray("operations"); array(operations,MAX_OPERATIONS);
                    for(int i=0;i<operations.length();i++) {
                        JSONObject operation=operations.getJSONObject(i); String kind=string(operation,"kind");
                        identifier(string(operation,"table")); id(string(operation,"id"));
                        if("delete".equals(kind)) exact(operation,"kind","table","id");
                        else if("insert".equals(kind)||"update".equals(kind)) {
                            exact(operation,"kind","table","id","values"); values(operation.getJSONObject("values"));
                            if(operation.getJSONObject("values").length()==0) throw invalid();
                        } else throw invalid();
                    }
                    break;
                case "database.select":
                    exact(args,"version","table","where","afterId","limit"); integer(args,"version",1,1000);
                    identifier(string(args,"table"));
                    if(args.has("where")) {
                        JSONObject where=args.getJSONObject("where");
                        if(where.length()>MAX_COLUMNS || bytes(where)>MAX_ROW_BYTES) throw invalid();
                        for(Iterator<String> it=where.keys();it.hasNext();) {
                            String key=it.next();
                            if("id".equals(key)) id(string(where,key));
                            else { identifier(key); scalar(where.get(key)); }
                        }
                    }
                    if(args.has("afterId")) id(string(args,"afterId"));
                    if(args.has("limit")) integer(args,"limit",1,MAX_SELECT_ROWS);
                    break;
                default: throw invalid();
            }
        } catch(JSONException e) { throw invalid(); }
    }
    static void columns(JSONObject columns) throws JSONException,FactoryException {
        if(columns.length()<1 || columns.length()>MAX_COLUMNS) throw invalid();
        for(Iterator<String> it=columns.keys();it.hasNext();) { String key=it.next(); identifier(key); definition(columns.getJSONObject(key)); }
    }
    static void definition(JSONObject value) throws JSONException,FactoryException {
        exact(value,"type","nullable"); String type=string(value,"type");
        if(!"text".equals(type)&&!"integer".equals(type)&&!"real".equals(type)&&!"boolean".equals(type)) throw invalid();
        if(!(value.get("nullable") instanceof Boolean)) throw invalid();
    }
    static void values(JSONObject values) throws JSONException,FactoryException {
        if(values.length()>MAX_COLUMNS || bytes(values)>MAX_ROW_BYTES) throw invalid();
        for(Iterator<String> it=values.keys();it.hasNext();) { String key=it.next(); identifier(key); scalar(values.get(key)); }
    }
    static void scalar(Object value) throws FactoryException {
        if(value==JSONObject.NULL || value instanceof Boolean) return;
        if(value instanceof String) { text((String)value,MAX_CELL_BYTES); return; }
        if(value instanceof Integer || value instanceof Long) {
            long number=((Number)value).longValue(); if(number < -MAX_SAFE_INTEGER || number>MAX_SAFE_INTEGER) throw invalid(); return;
        }
        if(value instanceof Double || value instanceof Float) {
            double number=((Number)value).doubleValue(); if(Double.isNaN(number)||Double.isInfinite(number)) throw invalid(); return;
        }
        throw invalid();
    }
    static void typed(Object value,JSONObject definition) throws JSONException,FactoryException {
        scalar(value);
        if(value==JSONObject.NULL) { if(!definition.getBoolean("nullable")) throw invalid(); return; }
        String type=definition.getString("type");
        boolean valid="text".equals(type)?value instanceof String:"boolean".equals(type)?value instanceof Boolean:
                "integer".equals(type)?value instanceof Integer||value instanceof Long:value instanceof Number;
        if(!valid) throw invalid();
    }
    static String identifier(String value) throws FactoryException {
        if(!value.matches("[a-z][a-z0-9_]{0,31}") || "id".equals(value) || value.startsWith("sqlite_") || value.startsWith("factory_")) throw invalid();
        return value;
    }
    static String id(String value) throws FactoryException {
        if(!value.matches("[A-Za-z0-9_-]{1,96}")) throw invalid(); return value;
    }
    static void text(String value,int max) throws FactoryException {
        if(value.length()>max || value.indexOf('\0')>=0 || value.getBytes(StandardCharsets.UTF_8).length>max) throw invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) { if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(); }
            else if(Character.isLowSurrogate(c)) throw invalid();
        }
    }
    static int integer(JSONObject object,String key,int min,int max) throws JSONException,FactoryException {
        Object value=object.get(key); if(!(value instanceof Integer)&&!(value instanceof Long)) throw invalid();
        long number=((Number)value).longValue(); if(number<min || number>max) throw invalid(); return (int)number;
    }
    static int bytes(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    static String string(JSONObject object,String key) throws JSONException,FactoryException { return FactoryConfig.string(object,key); }
    static void exact(JSONObject object,String...keys) throws FactoryException { FactoryConfig.exactKeys(object,keys); }
    static void array(JSONArray value,int max) throws FactoryException { if(value.length()<1 || value.length()>max) throw invalid(); }
    static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT","Invalid typed database arguments or limit exceeded."); }
}
