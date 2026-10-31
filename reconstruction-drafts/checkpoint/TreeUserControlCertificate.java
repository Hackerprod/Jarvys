package com.jarvys.agent;

/** Exact Registry USER claim handoff; never reconstructed from a checkpoint or serialized message. */
public final class TreeUserControlCertificate {
    final CrewCheckpointStore.ConfirmedSnapshot source;
    final String rootId,nodeId,controlId,controlType,claimSha256;
    final long observedAssignmentRevision,admittedAssignmentRevision;
    TreeUserControlCertificate(CrewCheckpointStore.ConfirmedSnapshot source,String rootId,String nodeId,String controlId,
            String controlType,long observedAssignmentRevision,long admittedAssignmentRevision,String claimSha256) {
        if(source==null||rootId==null||rootId.isEmpty()||nodeId==null||nodeId.isEmpty()||controlId==null||controlId.isEmpty()
                ||!("RESUME".equals(controlType)||"CORRECT".equals(controlType))||observedAssignmentRevision<=0
                ||admittedAssignmentRevision<=observedAssignmentRevision||claimSha256==null||!claimSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact native USER control source required");
        this.source=source;this.rootId=rootId;this.nodeId=nodeId;this.controlId=controlId;this.controlType=controlType;
        this.observedAssignmentRevision=observedAssignmentRevision;this.admittedAssignmentRevision=admittedAssignmentRevision;this.claimSha256=claimSha256;
    }
}
