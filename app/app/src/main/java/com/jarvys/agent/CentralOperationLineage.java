package com.jarvys.agent;

import org.json.JSONObject;
import java.util.*;

/** Hash-bound provenance, never a dispatch permit. Native claim/source/owner checks remain mandatory. */
final class CentralOperationLineage {
    static final String FIELD="operationLineageV1";
    static final String USER="ORIGINAL_USER", INTERNAL="INTERNAL_CONTINUATION", LEGACY="RESTRICTED_LEGACY";
    final String kind,rootId,userId,originDigest;
    CentralOperationLineage(String kind,String rootId,String userId,String originDigest) {
        if(!Arrays.asList(USER,INTERNAL,LEGACY).contains(kind) || rootId==null || rootId.isEmpty() || userId==null || userId.isEmpty()
            || originDigest==null || !originDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid operation lineage");
        this.kind=kind;this.rootId=rootId;this.userId=userId;this.originDigest=originDigest;
    }
    JSONObject toJson() {
        try{return new JSONObject().put("version",1).put("kind",kind).put("rootId",rootId)
            .put("originalUserMessageId",userId).put("originDigest",originDigest);}
        catch(Exception invalid){throw new IllegalArgumentException(invalid);}
    }
    static CentralOperationLineage readOptional(JSONObject identity) {
        if(!identity.has(FIELD))return null; // Historical identity is readable, never promoted to USER authority.
        try {
            Object raw=identity.get(FIELD);if(!(raw instanceof JSONObject))throw new IllegalArgumentException("Invalid operation lineage type");
            JSONObject value=(JSONObject)raw;
            if(value.length()!=5 || !(value.get("version") instanceof Integer) || value.getInt("version")!=1)
                throw new IllegalArgumentException("Unknown operation lineage version/shape");
            for(String key:new String[]{"kind","rootId","originalUserMessageId","originDigest"})
                if(!(value.get(key) instanceof String))throw new IllegalArgumentException("Invalid operation lineage scalar");
            CentralOperationLineage result=new CentralOperationLineage(value.getString("kind"),value.getString("rootId"),
                value.getString("originalUserMessageId"),value.getString("originDigest"));
            if(!result.rootId.equals(identity.getString("rootId")))throw new IllegalArgumentException("Cross-root operation lineage");
            return result;
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid operation lineage",invalid);}
    }
    void requireSource(String root,String user,String digest) {
        if(!rootId.equals(root) || !userId.equals(user) || !originDigest.equals(digest))
            throw new IllegalStateException("Operation lineage original source changed");
    }
}
