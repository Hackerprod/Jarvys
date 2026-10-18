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
        exact(args,"incomingShare.take","id","attachmentIndices");String id=id(args,"incomingShare.take");int[] indices=null;
        if(args.has("attachmentIndices")) {
            Object raw=args.opt("attachmentIndices");if(!(raw instanceof JSONArray))throw invalid("incomingShare.take","attachmentIndices must be an array of unique integers from 0 through 31");
            JSONArray array=(JSONArray)raw;if(array.length()>32)throw invalid("incomingShare.take","attachmentIndices must contain at most 32 unique integers from 0 through 31");indices=new int[array.length()];
            for(int i=0;i<indices.length;i++) {
                Object value=array.opt(i);if(!(value instanceof Integer) && !(value instanceof Long))throw invalid("incomingShare.take","attachmentIndices entries must be integers from 0 through 31");
                long index=((Number)value).longValue();if(index<0 || index>=32)throw invalid("incomingShare.take","attachmentIndices entries must be integers from 0 through 31");indices[i]=(int)index;
            }
            Arrays.sort(indices);for(int i=1;i<indices.length;i++)if(indices[i]==indices[i-1])throw invalid("incomingShare.take","attachmentIndices entries must be unique");
        }
        return new IncomingShareRequest(id,indices);
    }
    public static String discard(JSONObject args) throws FactoryException { exact(args,"incomingShare.discard","id");return id(args,"incomingShare.discard"); }
    public static void list(JSONObject args) throws FactoryException { exact(args,"incomingShare.list"); }
    private static String id(JSONObject args,String method) throws FactoryException {
        Object value=args.opt("id");if(!(value instanceof String) || !((String)value).matches("[a-f0-9]{64}"))throw invalid(method,"id must be a 64-character lowercase hexadecimal envelope token");return (String)value;
    }
    private static void exact(JSONObject args,String method,String... keys)throws FactoryException {
        if(args==null)throw invalid(method,"arguments must be an object with only the documented fields");HashSet<String> allowed=new HashSet<>(Arrays.asList(keys));
        for(Iterator<String> actual=args.keys();actual.hasNext();)if(!allowed.contains(actual.next()))throw invalid(method,"arguments must be an object with only the documented fields");
    }
    private static FactoryException invalid(String method,String expectation){return new FactoryException("INVALID_ARGUMENT",method+": "+expectation+".");}
}
