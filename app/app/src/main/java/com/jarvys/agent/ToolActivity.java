package com.jarvys.agent;

import java.util.*;
import org.json.JSONObject;

/** Immutable presentation evidence. It grants no tools, permissions, or message authority. */
public final class ToolActivity {
    public final String eventId, executionId, callId, toolName, displayName, skillId, skillName;
    public final String stage, detail, previewId, auditDetail, reflectionSource;
    public final long timestampMillis;
    public final String historyDetail;
    public ToolActivity(String eventId, String executionId, String callId, String toolName, String displayName,
            String skillId, String skillName, String stage, String detail, String previewId,
            String auditDetail, String reflectionSource, long timestampMillis) {
        if (executionId == null || executionId.isEmpty() || eventId == null || eventId.isEmpty())
            throw new IllegalArgumentException("Activity requires durable event and execution identities");
        this.eventId=eventId; this.executionId=executionId; this.callId=safe(callId); this.toolName=safe(toolName);
        this.displayName=safe(displayName); this.skillId=safe(skillId); this.skillName=safe(skillName);
        this.historyDetail=""; this.stage=normalize(stage); this.detail=CrewCheckpointStore.sanitizeText(safe(detail)); this.previewId=safe(previewId);
        this.auditDetail=CrewCheckpointStore.sanitizeText(safe(auditDetail)); this.reflectionSource=safe(reflectionSource); this.timestampMillis=timestampMillis;
    }
    private ToolActivity(ToolActivity value, String history) {
        eventId=value.eventId;executionId=value.executionId;callId=value.callId;toolName=value.toolName;displayName=value.displayName;
        skillId=value.skillId;skillName=value.skillName;stage=value.stage;detail=value.detail;previewId=value.previewId;
        auditDetail=value.auditDetail;reflectionSource=value.reflectionSource;timestampMillis=value.timestampMillis;historyDetail=history;
    }
    public String allDetail() { return historyDetail.isEmpty() ? detail : historyDetail; }
    private static String safe(String value) { return value == null ? "" : value; }
    public static String normalize(String stage) {
        return Arrays.asList("tool_call","tool_progress","tool_result","tool_error","tool_interrupted","tool_not_started").contains(stage)
                ? stage : "tool_interrupted";
    }
    public static boolean definitive(String stage) {
        return "tool_result".equals(stage) || "tool_error".equals(stage) || "tool_not_started".equals(stage);
    }
    public boolean skill() { return "read_skill".equals(toolName) && !skillId.isEmpty(); }
    public ToolActivity event(String stage, String detail, String previewId) {
        return new ToolActivity(UUID.randomUUID().toString(),executionId,callId,toolName,displayName,skillId,skillName,
                stage,detail,previewId,auditDetail,reflectionSource,System.currentTimeMillis());
    }
    public ToolActivity withDetail(String detail) {
        return new ToolActivity(new ToolActivity(eventId,executionId,callId,toolName,displayName,skillId,skillName,stage,detail,previewId,auditDetail,reflectionSource,timestampMillis),historyDetail);
    }
    public ToolActivity unavailablePreview() {
        String note="The recorded preview is no longer available in this conversation.";
        ToolActivity updated=new ToolActivity(eventId,executionId,callId,toolName,displayName,skillId,skillName,stage,
            detail+"\n\n"+note,"",auditDetail,reflectionSource,timestampMillis);
        return new ToolActivity(updated,allDetail()+"\n\n"+note);
    }
    public ToolActivity interrupted() {
        return definitive(stage) ? this : new ToolActivity(new ToolActivity(eventId,executionId,callId,toolName,displayName,skillId,skillName,
                "tool_interrupted",detail,previewId,auditDetail,reflectionSource,timestampMillis),historyDetail);
    }
    /** Same invocation only. Preserve all distinct progress/result evidence, with first definitive result authoritative. */
    public static ToolActivity project(List<ToolActivity> events, boolean active) {
        if (events.isEmpty()) throw new IllegalArgumentException("Missing activity evidence");
        ToolActivity first=events.get(0), selected=first;
        Set<String> seen=new HashSet<>(); LinkedHashSet<String> evidence=new LinkedHashSet<>();
        for (ToolActivity event:events) {
            if (!first.executionId.equals(event.executionId)) throw new IllegalArgumentException("Mixed executions");
            if (!seen.add(event.eventId)) continue;
            if (!event.allDetail().isEmpty()) evidence.add(event.allDetail());
            if (!definitive(selected.stage) && (definitive(event.stage) || !"tool_call".equals(event.stage)
                    && !("tool_interrupted".equals(selected.stage) && "tool_progress".equals(event.stage)))) selected=event;
        }
        ToolActivity projected=new ToolActivity(first.eventId,first.executionId,first.callId,first.toolName,first.displayName,
                first.skillId,first.skillName,selected.stage,selected.detail,selected.previewId,
                selected.auditDetail.isEmpty()?first.auditDetail:selected.auditDetail,first.reflectionSource,first.timestampMillis);
        return new ToolActivity(active ? projected : projected.interrupted(),String.join("\n\n",evidence));
    }
    public JSONObject toJson() {
        try { return new JSONObject().put("schemaVersion",1).put("eventId",eventId).put("executionId",executionId)
            .put("callId",callId).put("toolName",toolName).put("displayName",displayName).put("skillId",skillId).put("skillName",skillName)
            .put("stage",stage).put("detail",detail).put("previewId",previewId).put("auditDetail",auditDetail)
            .put("reflectionSource",reflectionSource).put("timestampMillis",timestampMillis); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    public static ToolActivity fromJson(JSONObject value) {
        if(value==null || value.optInt("schemaVersion")!=1) return null;
        try { return new ToolActivity(value.getString("eventId"),value.getString("executionId"),value.optString("callId"),
            value.optString("toolName"),value.optString("displayName"),value.optString("skillId"),value.optString("skillName"),
            value.optString("stage"),value.optString("detail"),value.optString("previewId"),value.optString("auditDetail"),
            value.optString("reflectionSource"),value.optLong("timestampMillis")); }
        catch(Exception invalid) { return null; }
    }
}
