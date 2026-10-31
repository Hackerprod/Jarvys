package com.jarvys.agent.crew;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable, in-memory Crew bus message. */
public final class CrewMessage {
    public enum Type { TASK, FINDING, CRITIQUE, QUESTION, BLOCKER, ANSWER, RESULT, STATUS, PROGRESS, MESSAGE, USER }
    public final String id;
    public final String conversationId;
    public final String from;
    public final String to;
    public final Type type;
    public final String text;
    public final List<String> refs;
    public final long timestampMillis;
    public final com.jarvys.agent.ToolActivity activity;
    /** Exact source reference; text/refs in snapshots may be sanitized historical copies. */
    public final CrewCoordinationEvidence.Reference coordination;
    public final CrewInputBinding inputBinding;
    /** Captured when a RESULT is emitted; replay never borrows the bot's current cycle. */
    public final String resultCycleId;
    /** Runtime-authored typed provenance for supervisor input; model arguments cannot select it. */
    public final com.jarvys.agent.ConversationInput.Provenance inputProvenance;

    /** Actual text plus its structured references for an attributed model input. */
    public String conversationContent() {
        if (refs.isEmpty()) return text;
        StringBuilder content = new StringBuilder(text).append("\n\nReferences:\n");
        for (String ref : refs) content.append("- ").append(ref).append('\n');
        return content.toString();
    }

    public CrewMessage(String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis) {
        this(UUID.randomUUID().toString(), conversationId, from, to, type, text, refs, timestampMillis);
    }
    public CrewMessage(String id, String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis) {
        this(id, conversationId, from, to, type, text, refs, timestampMillis, null);
    }
    public CrewMessage(String id, String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis, com.jarvys.agent.ToolActivity activity) {
        this(id, conversationId, from, to, type, text, refs, timestampMillis, activity, null, null, null);
    }
    private CrewMessage(String id, String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis,
                       com.jarvys.agent.ToolActivity activity, CrewCoordinationEvidence.Reference coordination,
                       CrewInputBinding inputBinding, com.jarvys.agent.ConversationInput.Provenance inputProvenance) {
        this(id, conversationId, from, to, type, text, refs, timestampMillis, activity,
                coordination, inputBinding, inputProvenance, "");
    }
    private CrewMessage(String id, String conversationId, String from, String to, Type type,
                       String text, List<String> refs, long timestampMillis,
                       com.jarvys.agent.ToolActivity activity, CrewCoordinationEvidence.Reference coordination,
                       CrewInputBinding inputBinding, com.jarvys.agent.ConversationInput.Provenance inputProvenance,
                       String resultCycleId) {
        this.resultCycleId = resultCycleId == null ? "" : resultCycleId;
        if (!this.resultCycleId.isEmpty()) {
            CrewResultEvidence.requireCycleId(this.resultCycleId);
            if (type != Type.RESULT || !"chief".equals(to) || activity != null)
                throw new IllegalArgumentException("Result cycle requires a worker result input");
        }
        this.activity = activity;
        this.coordination = coordination;
        this.inputBinding = inputBinding;
        this.inputProvenance = inputProvenance;
        this.id = require(id, "id");
        this.conversationId = require(conversationId, "conversationId");
        this.from = require(from, "from");
        this.to = require(to, "to");
        this.type = java.util.Objects.requireNonNull(type, "type");
        this.text = text == null ? "" : text;
        this.refs = Collections.unmodifiableList(new ArrayList<>(refs == null ? Collections.emptyList() : refs));
        this.timestampMillis = timestampMillis;
        if (inputBinding != null && !inputBinding.matchesRouting(this))
            throw new IllegalArgumentException("Input binding does not match mailbox routing");
        if (coordination != null) {
            boolean source = coordination.botId.equals(from) && "chief".equals(to)
                    && coordination.messageId.equals(id)
                    && (type == Type.QUESTION || type == Type.BLOCKER || type == Type.FINDING);
            boolean answer = "chief".equals(from) && coordination.botId.equals(to) && type == Type.ANSWER;
            if (activity != null || !conversationId.equals(coordination.conversationId) || (!source && !answer))
                throw new IllegalArgumentException("Crew causal reference does not match message routing");
        }
        com.jarvys.agent.ConversationInput.Kind expectedInputKind = inputKindForCrewType(type);
        boolean treeInput=inputBinding!=null && (inputBinding.sourceKind==CrewInputBinding.SourceKind.TREE_TASK
                || inputBinding.sourceKind==CrewInputBinding.SourceKind.TREE_REPORT || inputBinding.sourceKind==CrewInputBinding.SourceKind.TREE_ANSWER);
        if(inputProvenance!=null && treeInput && !validTreeProvenance())
            throw new IllegalArgumentException("Tree input provenance does not match exact routing");
        if (inputProvenance != null && !treeInput && (!"chief".equals(from)
                || !com.jarvys.agent.workspaces.WorkspaceIdentity.isExecutionSession(conversationId)
                || !com.jarvys.agent.workspaces.WorkspaceIdentity.workspaceId(conversationId).equals(inputProvenance.workspaceId)
                || !inputProvenance.botId.equals(to) || inputProvenance.assignmentId.isEmpty()
                || !com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION.equals(inputProvenance.sessionId)
                || inputProvenance.principalRole != com.jarvys.agent.ConversationInput.Provenance.PrincipalRole.SUPERVISOR
                || expectedInputKind == null || inputProvenance.kind != expectedInputKind
                || inputProvenance.assignmentRevision < 1 || inputProvenance.requestId.isEmpty()
                || inputProvenance.missionId.isEmpty() || !inputProvenance.messageId.equals(id)))
            throw new IllegalArgumentException("Supervisor input provenance does not match message routing");
    }

    /** Structural correspondence only. Registry/checkpoint/native token validators remain mandatory. */
    private boolean validTreeProvenance() {
        CrewInputBinding b=inputBinding;
        com.jarvys.agent.ConversationInput.Provenance p=inputProvenance;
        if(b==null || p==null || !(b.sourceKind==CrewInputBinding.SourceKind.TREE_TASK
                || b.sourceKind==CrewInputBinding.SourceKind.TREE_REPORT || b.sourceKind==CrewInputBinding.SourceKind.TREE_ANSWER)) return false;
        if(!b.matchesDelivery(this) || !com.jarvys.agent.workspaces.WorkspaceIdentity.isExecutionSession(conversationId)
                || !com.jarvys.agent.workspaces.WorkspaceIdentity.workspaceId(conversationId).equals(b.workspaceId)
                || !com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION.equals(b.ownerCentralSessionId)
                || !p.workspaceId.equals(b.workspaceId) || !p.sessionId.equals(conversationId)
                || !p.messageId.equals(id) || !p.missionId.equals(b.missionId) || p.requestId.isEmpty()
                || p.assignmentId.isEmpty() || p.kind!=inputKindForCrewType(type)) return false;
        CrewCoordinationEvidence.requireCycleId(p.cycleId);
        if(b.sourceKind==CrewInputBinding.SourceKind.TREE_TASK) return p.principalRole==com.jarvys.agent.ConversationInput.Provenance.PrincipalRole.PEER
                && p.botId.equals(from) && p.assignmentId.equals(b.assignmentId) && p.assignmentRevision>0
                && b.sourceId.equals(b.assignmentId) && b.sourcePayloadSha256.equals(CrewInputBinding.hashText("tree-task-provenance-v1",p.toJson().toString()));
        if(!p.contentSha256.equals(b.sourcePayloadSha256) || p.correlationMessageId.isEmpty())return false;
        if(b.sourceKind==CrewInputBinding.SourceKind.TREE_ANSWER) return p.principalRole==com.jarvys.agent.ConversationInput.Provenance.PrincipalRole.PEER
                && p.botId.equals(to) && p.assignmentId.equals(b.assignmentId) && p.assignmentRevision>0;
        return p.principalRole==com.jarvys.agent.ConversationInput.Provenance.PrincipalRole.SUBORDINATE
                && p.assignmentRevision==0 && (p.botId.equals(from) || p.botId.isEmpty() && from.startsWith("registry:"));
    }

    private static com.jarvys.agent.ConversationInput.Kind inputKindForCrewType(Type type) {
        try { return com.jarvys.agent.ConversationInput.Kind.valueOf(type.name()); }
        catch (IllegalArgumentException unsupported) { return null; }
    }
    public org.json.JSONObject toJson() {
        try { org.json.JSONObject row = new org.json.JSONObject().put("id",id).put("conversationId",conversationId)
                .put("from",from).put("to",to).put("type",type.name()).put("text",text)
                .put("refs",new org.json.JSONArray(refs)).put("timestampMillis",timestampMillis);
            if (coordination != null) row.put("coordination", coordination.toJson());
            if (inputBinding != null) row.put("inputBinding", inputBinding.toJson());
            if (!resultCycleId.isEmpty()) row.put("resultCycleId", resultCycleId);
            if (inputProvenance != null) row.put("inputProvenance", inputProvenance.toJson());
            if (activity != null) row.put("origin","local_activity").put("activity",activity.toJson());
            return row;
        } catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    public static CrewMessage fromJson(org.json.JSONObject row) {
        if (row.has("coordination") || row.has("inputBinding") || row.has("inputProvenance") || row.has("resultCycleId")) validateCausalCopy(row);
        List<String> refs=new ArrayList<>(); org.json.JSONArray items=row.optJSONArray("refs");
        if(items!=null) for(int i=0;i<items.length();i++) refs.add(items.optString(i));
        com.jarvys.agent.ToolActivity activity="local_activity".equals(row.optString("origin"))
                ? com.jarvys.agent.ToolActivity.fromJson(row.optJSONObject("activity")) : null;
        if (activity==null && "local_activity".equals(row.optString("origin"))) {
            String id=row.optString("id");
            activity=new com.jarvys.agent.ToolActivity(id,"unreadable:"+id,"","",row.optString("text"),"","",
                "tool_interrupted",row.optJSONObject("activity")==null ? "" : row.optJSONObject("activity").toString(),"","","",row.optLong("timestampMillis"));
        }
        return new CrewMessage(row.optString("id"),row.optString("conversationId"),row.optString("from"),
                row.optString("to"),Type.valueOf(row.optString("type")),row.optString("text"),refs,row.optLong("timestampMillis"),activity,
                row.has("coordination") ? CrewCoordinationEvidence.Reference.fromJsonStrict(row.optJSONObject("coordination")) : null,
                row.has("inputBinding") ? CrewInputBinding.fromJsonStrict(row.optJSONObject("inputBinding")) : null,
                row.has("inputProvenance") ? com.jarvys.agent.ConversationInput.Provenance.fromJson(row.optJSONObject("inputProvenance")) : null,
                row.optString("resultCycleId", ""));
    }
    /** New causal copies are strict structurally, but copied text/refs are never rehashed as source. */
    private static void validateCausalCopy(org.json.JSONObject row) {
        java.util.Set<String> fields = new java.util.HashSet<>(java.util.Arrays.asList(
                "id", "conversationId", "from", "to", "type", "text", "refs", "timestampMillis"));
        if (row.has("coordination")) fields.add("coordination");
        if (row.has("inputBinding")) fields.add("inputBinding");
        if (row.has("inputProvenance")) fields.add("inputProvenance");
        if (row.has("resultCycleId")) {
            fields.add("resultCycleId");
            if (!(row.opt("resultCycleId") instanceof String)) throw new IllegalArgumentException("Invalid result cycle");
        }
        if (row.length() != fields.size()) throw new IllegalArgumentException("Invalid causal message fields");
        java.util.Iterator<String> keys = row.keys();
        while (keys.hasNext()) if (!fields.contains(keys.next()))
            throw new IllegalArgumentException("Unexpected causal message field");
        for (String key : java.util.Arrays.asList("id", "conversationId", "from", "to", "type", "text"))
            if (!(row.opt(key) instanceof String)) throw new IllegalArgumentException("Invalid causal message " + key);
        Object timestamp = row.opt("timestampMillis");
        if (!(timestamp instanceof Byte) && !(timestamp instanceof Short)
                && !(timestamp instanceof Integer) && !(timestamp instanceof Long))
            throw new IllegalArgumentException("Invalid causal message timestamp");
        if (((Number) timestamp).longValue() <= 0L) throw new IllegalArgumentException("Invalid causal message timestamp");
        org.json.JSONArray refs = row.optJSONArray("refs");
        if (refs == null) throw new IllegalArgumentException("Invalid causal message refs");
        for (int index = 0; index < refs.length(); index++) if (!(refs.opt(index) instanceof String))
            throw new IllegalArgumentException("Invalid causal message ref");
    }
    public CrewMessage withActivity(com.jarvys.agent.ToolActivity value) {
        return new CrewMessage(id,conversationId,from,to,type,text,refs,timestampMillis,value,coordination,inputBinding,inputProvenance,resultCycleId);
    }
    public CrewMessage withCoordination(CrewCoordinationEvidence evidence) {
        return new CrewMessage(id,conversationId,from,to,type,text,refs,timestampMillis,activity,evidence.reference(),inputBinding,inputProvenance,resultCycleId);
    }
    public CrewMessage withInputBinding(CrewInputBinding binding) {
        if (binding == null || !binding.matchesDelivery(this))
            throw new IllegalArgumentException("Input binding does not match exact delivery");
        return new CrewMessage(id,conversationId,from,to,type,text,refs,timestampMillis,activity,coordination,binding,inputProvenance,resultCycleId);
    }
    public CrewMessage withInputProvenance(com.jarvys.agent.ConversationInput.Provenance provenance) {
        if (provenance == null) throw new IllegalArgumentException("Typed supervisor provenance is required");
        return new CrewMessage(id,conversationId,from,to,type,text,refs,timestampMillis,
                activity,coordination,inputBinding,provenance,resultCycleId);
    }
    public CrewMessage withResultCycle(String cycleId) {
        return new CrewMessage(id, conversationId, from, to, type, text, refs, timestampMillis,
                activity, coordination, inputBinding, inputProvenance, cycleId);
    }
    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
