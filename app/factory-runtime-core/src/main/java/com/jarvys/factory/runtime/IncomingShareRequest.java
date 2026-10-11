package com.jarvys.factory.runtime;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Closed renderer request grammar. MIME policy and authorization details are native-owned. */
public final class IncomingShareRequest {
    public final String id;
    public final int[] attachmentIndices;
    private IncomingShareRequest(String id,int[] indices) { this.id=id;attachmentIndices=indices; }
    public static IncomingShareRequest take(JSONObject args) throws FactoryException {
        exact(args,"id","attachmentIndices");String id=id(args);int[] indices=null;
        if(args.has("attachmentIndices")) {
            Object raw=args.opt("attachmentIndices");if(!(raw instanceof JSONArray))throw invalid();
            JSONArray array=(JSONArray)raw;if(array.length()>32)throw invalid();indices=new int[array.length()];
            for(int i=0;i<indices.length;i++) {
                Object value=array.opt(i);if(!(value instanceof Integer) && !(value instanceof Long))throw invalid();
                long index=((Number)value).longValue();if(index<0 || index>=32)throw invalid();indices[i]=(int)index;
            }
            Arrays.sort(indices);for(int i=1;i<indices.length;i++)if(indices[i]==indices[i-1])throw invalid();
        }
        return new IncomingShareRequest(id,indices);
    }
    public static String discard(JSONObject args) throws FactoryException { exact(args,"id");return id(args); }
    public static void list(JSONObject args) throws FactoryException { exact(args); }
    private static String id(JSONObject args) throws FactoryException {
        Object value=args.opt("id");if(!(value instanceof String) || !((String)value).matches("[a-f0-9]{64}"))throw invalid();return (String)value;
    }
    private static void exact(JSONObject args,String... keys)throws FactoryException {
        if(args==null)throw invalid();HashSet<String> allowed=new HashSet<>(Arrays.asList(keys));
        for(Iterator<String> actual=args.keys();actual.hasNext();)if(!allowed.contains(actual.next()))throw invalid();
    }
    private static FactoryException invalid(){return new FactoryException("INVALID_ARGUMENT","Incoming sharing request requires an opaque envelope ID and optional unique attachment indices.");}
}
