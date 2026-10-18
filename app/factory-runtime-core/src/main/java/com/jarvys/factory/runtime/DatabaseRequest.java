package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Closed typed database grammar. Neither identifiers nor values can introduce SQL syntax. */
public final class DatabaseRequest {
    private DatabaseRequest() { }
    // Historical defaults remain aliases for v1 backup protocols. They are not per-app admission.
    public static final int MAX_TABLES=8, MAX_COLUMNS=16, MAX_STEPS=16, MAX_OPERATIONS=64;
    // Pure implementation ceilings: never consult FactoryConfig, capabilities or mutable budgets.
    public static final int HARD_MAX_TABLES=16, HARD_MAX_COLUMNS=32;
    public static final long HARD_MAX_ROWS=1000000L, HARD_MAX_DATABASE_BYTES=1024L*1024*1024;
    public static final int MAX_ROWS=10000, MAX_CELL_BYTES=4096, MAX_ROW_BYTES=16384;
    public static final int MAX_ARGUMENT_BYTES=262144, MAX_RESULT_BYTES=262144, MAX_SELECT_ROWS=100;
    public static final long MAX_DATABASE_BYTES=16L*1024*1024, MAX_SAFE_INTEGER=9007199254740991L;
    // Complete rows must fit one byte-bounded response, including pagination metadata and envelope.
    public static final int HARD_MAX_ROW_BYTES=MAX_RESULT_BYTES-1024, HARD_MAX_CELL_BYTES=HARD_MAX_ROW_BYTES;
    // The smallest row {"id":"a"} plus its separator is eleven bytes.
    public static final int HARD_MAX_SELECT_ROWS=HARD_MAX_ROW_BYTES/11;
    // Bridge grammar: seven envelope/container tokens, then at least four tokens per delete operation.
    // Inserts and updates can hit the fixed byte/token envelope before this count is reached.
    public static final int HARD_MAX_OPERATIONS=(8192-7)/4;
    public static void validate(String method, JSONObject args) throws FactoryException {
        String request = method == null || !method.startsWith("database.") ? "database" : method;
        if (method == null) throw invalid("database.method must be info, migrate, transact, select, close, or cancel");
        try {
            if (args == null) throw invalid(request + ": args must be an object");
            if (args.toString().getBytes(StandardCharsets.UTF_8).length>MAX_ARGUMENT_BYTES)
                throw invalid(request + ": args exceeds the 262144-byte request limit");
            switch (method) {
                case "database.info": case "database.close": case "database.cancel": exactAt(args, request, "args"); break;
                case "database.migrate":
                    exactAt(args, request, "args", "fromVersion","toVersion","steps");
                    int from=integer(args,"fromVersion",0,999,request+".fromVersion must be an integer from 0 through 999");
                    int to=integer(args,"toVersion",1,1000,request+".toVersion must be the next integer schema version");
                    if(to!=from+1) throw invalid(request+".toVersion must equal fromVersion + 1");
                    JSONArray steps=array(args,"steps",request+".steps",MAX_STEPS);
                    for(int i=0;i<steps.length();i++) {
                        String path=request+".steps["+i+"]";
                        JSONObject step=object(steps,i,path+" must be an object");
                        String kind=string(step,"kind",path+".kind must be a string");
                        identifier(string(step,"table",path+".table must be a lowercase identifier"),path+".table");
                        if("createTable".equals(kind)) {
                            exactAt(step, request, path, "kind","table","columns");
                            columns(object(step,"columns",path+".columns must be an object mapping column name to {type, nullable}"),path+".columns");
                        } else if("addColumn".equals(kind)) {
                            exactAt(step, request, path, "kind","table","column","definition");
                            identifier(string(step,"column",path+".column must be a lowercase identifier"),path+".column");
                            JSONObject definition=object(step,"definition",path+".definition must be an object with type and nullable");
                            definition(definition,path+".definition");
                            if(!definition.getBoolean("nullable")) throw invalid(path+".definition.nullable must be true for addColumn");
                        } else throw invalid(path+".kind must be createTable or addColumn");
                    }
                    break;
                case "database.transact":
                    exactAt(args, request, "args","version","operations");
                    integer(args,"version",1,1000,request+".version must be an integer from 1 through 1000");
                    JSONArray operations=array(args,"operations",request+".operations",HARD_MAX_OPERATIONS);
                    for(int i=0;i<operations.length();i++) {
                        String path=request+".operations["+i+"]";
                        JSONObject operation=object(operations,i,path+" must be an object");
                        String kind=string(operation,"kind",path+".kind must be insert, update, or delete");
                        identifier(string(operation,"table",path+".table must be a lowercase identifier"),path+".table");
                        id(string(operation,"id",path+".id must be 1 to 96 letters, digits, underscores, or hyphens"),path+".id");
                        if("delete".equals(kind)) exactAt(operation, request, path,"kind","table","id");
                        else if("insert".equals(kind)||"update".equals(kind)) {
                            exactAt(operation, request, path,"kind","table","id","values");
                            JSONObject values=object(operation,"values",path+".values must be an object of scalar values");
                            values(values,path+".values");
                            if(values.length()==0) throw invalid(path+".values must contain at least one column");
                        } else throw invalid(path+".kind must be insert, update, or delete");
                    }
                    break;
                case "database.select":
                    exactAt(args, request, "args","version","table","where","afterId","limit");
                    integer(args,"version",1,1000,request+".version must be an integer from 1 through 1000");
                    identifier(string(args,"table",request+".table must be a lowercase identifier"),request+".table");
                    if(args.has("where")) {
                        JSONObject where=object(args,"where",request+".where must be an object of scalar column values");
                        if(where.length()>HARD_MAX_COLUMNS || bytes(where)>HARD_MAX_ROW_BYTES)
                            throw invalid(request+".where must fit the column and row byte limits");
                        for(Iterator<String> it=where.keys();it.hasNext();) {
                            String key=it.next();
                            if("id".equals(key)) id(string(where,key,request+".where.id must be 1 to 96 letters, digits, underscores, or hyphens"),request+".where.id");
                            else {
                                try { identifier(key); }
                                catch(FactoryException invalid) { throw invalid(request+".where keys must be id or lowercase column identifiers"); }
                                scalar(where.get(key),request+".where values must be scalar JSON values");
                            }
                        }
                    }
                    if(args.has("afterId")) id(string(args,"afterId",request+".afterId must be 1 to 96 letters, digits, underscores, or hyphens"),request+".afterId");
                    if(args.has("limit")) integer(args,"limit",1,HARD_MAX_SELECT_ROWS,request+".limit must be an integer from 1 through "+HARD_MAX_SELECT_ROWS);
                    break;
                default: throw invalid(request+": method must be info, migrate, transact, select, close, or cancel");
            }
        } catch(JSONException e) { throw invalid(request+": request fields have invalid JSON types or shapes"); }
    }
    static void columns(JSONObject columns) throws JSONException,FactoryException { columns(columns,"columns"); }
    private static void columns(JSONObject columns,String path) throws JSONException,FactoryException {
        if(columns.length()<1 || columns.length()>HARD_MAX_COLUMNS) throw invalid(path+" must contain 1 to "+HARD_MAX_COLUMNS+" column definitions");
        for(Iterator<String> it=columns.keys();it.hasNext();) {
            String key=it.next();
            try { identifier(key); } catch(FactoryException invalid) { throw invalid(path+" keys must be lowercase column identifiers"); }
            definition(object(columns,key,path+" values must be {type, nullable} objects"),path+".<column>");
        }
    }
    static void definition(JSONObject value) throws JSONException,FactoryException {
        definition(value,"definition");
    }
    private static void definition(JSONObject value,String path) throws JSONException,FactoryException {
        exactAt(value,"database.migrate",path,"type","nullable"); String type=string(value,"type",path+".type must be text, integer, real, or boolean");
        if(!"text".equals(type)&&!"integer".equals(type)&&!"real".equals(type)&&!"boolean".equals(type)) throw invalid(path+".type must be text, integer, real, or boolean");
        if(!value.has("nullable") || !(value.get("nullable") instanceof Boolean)) throw invalid(path+".nullable must be a boolean");
    }
    static void values(JSONObject values) throws JSONException,FactoryException { values(values,"values"); }
    private static void values(JSONObject values,String path) throws JSONException,FactoryException {
        if(values.length()>HARD_MAX_COLUMNS || bytes(values)>HARD_MAX_ROW_BYTES) throw invalid(path+" must fit the column and row byte limits");
        for(Iterator<String> it=values.keys();it.hasNext();) {
            String key=it.next();
            try { identifier(key); } catch(FactoryException invalid) { throw invalid(path+" keys must be lowercase column identifiers"); }
            scalar(values.get(key),path+" values must be string, safe integer, finite number, boolean, or null");
        }
    }
    static void scalar(Object value) throws FactoryException { scalar(value,HARD_MAX_CELL_BYTES); }
    static void scalar(Object value,int maxCellBytes) throws FactoryException {
        scalar(value,"database value",maxCellBytes);
    }
    private static void scalar(Object value,String expectation) throws FactoryException { scalar(value,expectation,HARD_MAX_CELL_BYTES); }
    private static void scalar(Object value,String expectation,int maxCellBytes) throws FactoryException {
        if(value==JSONObject.NULL || value instanceof Boolean) return;
        if(value instanceof String) { try { text((String)value,maxCellBytes); } catch(FactoryException invalid) { throw invalid(expectation+" must be valid UTF-8 text within the cell byte limit"); } return; }
        if(value instanceof Integer || value instanceof Long) {
            long number=((Number)value).longValue(); if(number < -MAX_SAFE_INTEGER || number>MAX_SAFE_INTEGER) throw invalid(expectation+" integer must be within the JavaScript safe integer range"); return;
        }
        if(value instanceof Double || value instanceof Float) {
            double number=((Number)value).doubleValue(); if(Double.isNaN(number)||Double.isInfinite(number)) throw invalid(expectation+" number must be finite"); return;
        }
        throw invalid(expectation+" must be string, safe integer, finite number, boolean, or null");
    }
    static void typed(Object value,JSONObject definition) throws JSONException,FactoryException { typed(value,definition,"database value",HARD_MAX_CELL_BYTES); }
    static void typed(Object value,JSONObject definition,int maxCellBytes) throws JSONException,FactoryException { typed(value,definition,"database value",maxCellBytes); }
    static void typed(Object value,JSONObject definition,String path) throws JSONException,FactoryException { typed(value,definition,path,HARD_MAX_CELL_BYTES); }
    static void typed(Object value,JSONObject definition,String path,int maxCellBytes) throws JSONException,FactoryException {
        scalar(value,path+" must be a supported scalar within the cell byte limit",maxCellBytes);
        if(value==JSONObject.NULL) { if(!definition.getBoolean("nullable")) throw invalid(path+" must not be null because the declared column is not nullable"); return; }
        String type=definition.getString("type");
        boolean valid="text".equals(type)?value instanceof String:"boolean".equals(type)?value instanceof Boolean:
                "integer".equals(type)?value instanceof Integer||value instanceof Long:value instanceof Number;
        if(!valid) throw invalid(path+" must match the declared "+type+" column type");
    }
    static String identifier(String value) throws FactoryException { return identifier(value,"database identifier"); }
    private static String identifier(String value,String path) throws FactoryException {
        if(value==null || !value.matches("[a-z][a-z0-9_]{0,31}") || "id".equals(value) || value.startsWith("sqlite_") || value.startsWith("factory_")) throw invalid(path+" must be a lowercase identifier of at most 32 characters and cannot be id, sqlite_*, or factory_*");
        return value;
    }
    static String id(String value) throws FactoryException { return id(value,"database id"); }
    private static String id(String value,String path) throws FactoryException {
        if(value==null || !value.matches("[A-Za-z0-9_-]{1,96}")) throw invalid(path+" must be 1 to 96 letters, digits, underscores, or hyphens"); return value;
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
        return integer(object,key,min,max,"database."+key+" must be an integer from "+min+" through "+max);
    }
    private static int integer(JSONObject object,String key,int min,int max,String expectation) throws JSONException,FactoryException {
        Object value; try { value=object.get(key); } catch(JSONException missing) { throw invalid(expectation); }
        if(!(value instanceof Integer)&&!(value instanceof Long)) throw invalid(expectation);
        long number=((Number)value).longValue(); if(number<min || number>max) throw invalid(expectation); return (int)number;
    }
    static int bytes(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    static String string(JSONObject object,String key) throws JSONException,FactoryException { return string(object,key,"database."+key+" must be a string"); }
    private static String string(JSONObject object,String key,String expectation) throws JSONException,FactoryException {
        Object value; try { value=object.get(key); } catch(JSONException missing) { throw invalid(expectation); }
        if(!(value instanceof String) || ((String)value).isEmpty()) throw invalid(expectation); return (String)value;
    }
    private static JSONObject object(JSONObject object,String key,String expectation) throws JSONException,FactoryException {
        Object value; try { value=object.get(key); } catch(JSONException missing) { throw invalid(expectation); }
        if(!(value instanceof JSONObject)) throw invalid(expectation); return (JSONObject)value;
    }
    private static JSONObject object(JSONArray array,int index,String expectation) throws JSONException,FactoryException {
        Object value=array.get(index); if(!(value instanceof JSONObject)) throw invalid(expectation); return (JSONObject)value;
    }
    private static JSONArray array(JSONObject object,String key,String path,int max) throws JSONException,FactoryException {
        Object value; try { value=object.get(key); } catch(JSONException missing) { throw invalid(path+" is required and must be a non-empty array"); }
        if(!(value instanceof JSONArray)) throw invalid(path+" must be a non-empty array");
        array((JSONArray)value,max,path); return (JSONArray)value;
    }
    static void array(JSONArray value,int max) throws FactoryException { array(value,max,"database.steps"); }
    private static void array(JSONArray value,int max,String path) throws FactoryException {
        if(value.length()<1 || value.length()>max) throw invalid(path+" must contain 1 to "+max+" items");
    }
    static void exactAt(JSONObject object,String method,String path,String...keys) throws FactoryException {
        closed(object,method,path,keys);
    }
    static void exact(JSONObject object,String...keys) throws FactoryException {
        closed(object,"database.backup","args",keys);
    }
    private static void closed(JSONObject object,String method,String path,String[] keys) throws FactoryException {
        java.util.HashSet<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList(keys));
        String fieldPath=path.startsWith(method+".") ? path : method+"."+path;
        for(Iterator<String> it=object.keys();it.hasNext();) if(!allowed.contains(it.next()))
            throw invalid(fieldPath+" may contain only "+(keys.length==0?"no fields":String.join(", ",java.util.Arrays.asList(keys)))+".");
    }
    static FactoryException invalid() { return invalid("database: value, schema, or archive must match the documented types and limits"); }
    static FactoryException invalid(String message) { return new FactoryException("INVALID_ARGUMENT",message); }
}
