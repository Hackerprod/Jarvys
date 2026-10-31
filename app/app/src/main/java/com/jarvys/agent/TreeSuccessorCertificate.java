package com.jarvys.agent;

import org.json.JSONObject;

/** Typed local handoff after Registry STARTED validation; not deserializable and not a dispatch permit. */
public final class TreeSuccessorCertificate {
    final CrewCheckpointStore.ConfirmedSnapshot source;
    final String rootId,nodeId,waitId,claimSha256;
    final long waitRevision,observedAssignmentRevision,admittedAssignmentRevision;
    TreeSuccessorCertificate(CrewCheckpointStore.ConfirmedSnapshot source,String rootId,String nodeId,String waitId,
            long waitRevision,long observedAssignmentRevision,long admittedAssignmentRevision,String claimSha256) {
        if(source==null||rootId==null||rootId.isEmpty()||nodeId==null||nodeId.isEmpty()||waitId==null||waitId.isEmpty()
                ||waitRevision<=0||observedAssignmentRevision<=0||observedAssignmentRevision==Long.MAX_VALUE||admittedAssignmentRevision!=observedAssignmentRevision+1
                ||claimSha256==null||!claimSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact tree successor source required");
        try {
            JSONObject ticket=WorkerTreeWaitRecord.fromJson(source.metadataJson().getJSONObject(WorkerTreeWaitRecord.FIELD)).current();
            if(!rootId.equals(ticket.getString("rootId"))||!nodeId.equals(ticket.getString("nodeId"))||!waitId.equals(ticket.getString("waitId"))
                    ||!source.metadataJson().getString("cycleId").equals(ticket.getString("cycleId"))||waitRevision<=ticket.getLong("revision"))
                throw new IllegalArgumentException("Tree claim does not match retained wait source");
        }catch(org.json.JSONException e){throw new IllegalArgumentException("Missing retained tree wait source",e);}
        this.source=source;this.rootId=rootId;this.nodeId=nodeId;this.waitId=waitId;this.waitRevision=waitRevision;
        this.observedAssignmentRevision=observedAssignmentRevision;this.admittedAssignmentRevision=admittedAssignmentRevision;this.claimSha256=claimSha256;
    }
    JSONObject preparedLink() {
        try{return CrewCheckpointStore.continuationSourceCertificate(source)
            .put("controlId","child-wait:"+rootId+":"+waitId).put("controlType","CHILD_WAIT")
            .put("observedAssignmentRevision",observedAssignmentRevision).put("admittedAssignmentRevision",0)
            .put("oldCycleId",source.metadataJson().getString("cycleId")).put("newCycleId","")
            .put("state","PREPARED").put("safePoint",true).put("treeRootId",rootId).put("treeNodeId",nodeId)
            .put("treeWaitId",waitId).put("treeWaitRevision",waitRevision).put("treeClaimSha256",claimSha256);
        }catch(org.json.JSONException e){throw new IllegalArgumentException("Malformed tree source",e);}
    }
}
