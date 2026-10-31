package com.jarvys.agent;

import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native coordination metadata, never a model-mintable permission or a second input outbox. */
public final class CentralContinuationRecord {
    public static final String FIELD = "centralContinuationV1";
    private static final String[] IDENTITY = {"version", "waitId", "ownerCentralSessionId", "executionSessionId",
        "workspaceId", "assignmentId", "requestId", "missionId", "botId", "initialCycleId", "continuationKey",
        "inputSourceId", "inputStableId"};
    private final String encoded;

    private CentralContinuationRecord(JSONObject value) {
        validate(value);
        encoded = value.toString();
    }

    public static CentralContinuationRecord fromJson(JSONObject value) {
        if (value == null) throw invalid("Missing continuation metadata");
        return new CentralContinuationRecord(copy(value));
    }

    public static CentralContinuationRecord create(String workspaceId, String assignmentId, String requestId,
            String missionId, String botId, String cycleId) {
        try {
            String wait = waitId(workspaceId, assignmentId);
            String key = "central-continuation:" + wait;
            String source = uuidFor(key);
            String session = WorkspaceIdentity.executionSessionId(workspaceId);
            return fromJson(new JSONObject().put("version", 1).put("revision", 1)
                .put("waitId", wait).put("ownerCentralSessionId", WorkspaceIdentity.CENTRAL_SESSION)
                .put("executionSessionId", session).put("workspaceId", workspaceId).put("assignmentId", assignmentId)
                .put("requestId", requestId).put("missionId", missionId).put("botId", botId)
                .put("initialCycleId", cycleId).put("expectedCycleId", cycleId)
                .put("links", new JSONArray()).put("answerBindings", new JSONArray()).put("centralAdmissions", new JSONArray())
                .put("selectedResult", JSONObject.NULL).put("consumedResults", new JSONArray())
                .put("continuationKey", key).put("inputSourceId", source)
                .put("inputStableId", ConversationInput.stableId(ConversationInput.Origin.AGENT, session, source))
                .put("inputPayloadSha256", "").put("dispatchState", "NONE")
                .put("dispatchOwnerProcessId", "").put("dispatchGenerationId", "")
                .put("assistantMessageStableId", "").put("generationRunId", ""));
        } catch (org.json.JSONException failure) { throw invalid(failure.getMessage()); }
    }

    public JSONObject toJson() { return parse(encoded); }
    public long revision() { return toJson().optLong("revision"); }
    public String waitId() { return toJson().optString("waitId"); }
    public String expectedCycleId() { return toJson().optString("expectedCycleId"); }
    public String dispatchState() { return toJson().optString("dispatchState"); }
    public static String waitId(String workspaceId, String assignmentId) {
        WorkspaceIdentity.requireWorkspaceId(workspaceId);
        requireText(assignmentId, "assignmentId");
        return uuidFor("jarvys-central-wait-v1\u0000" + workspaceId + "\u0000" + assignmentId);
    }
    private static String uuidFor(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }

    /** Validate physical checkpoint identity. Registry/current-policy validation belongs to the coordinator. */
    public void requireCheckpoint(String session, String bot, String scope, JSONObject metadata) {
        JSONObject v = toJson();
        require(session.equals(v.optString("executionSessionId")) && bot.equals(v.optString("botId")), "Checkpoint owner mismatch");
        require(scope.equals("native-workspace:" + v.optString("workspaceId")), "Native scope mismatch");
        require(Boolean.TRUE.equals(metadata.opt("nativeWorkspaceCheckpoint")), "Not a native checkpoint");
        for (String key : Arrays.asList("conversationId", "botId", "assignmentId", "missionId")) {
            String expected = "conversationId".equals(key) ? session : v.optString(key);
            require(expected.equals(metadata.optString(key)), "Checkpoint " + key + " mismatch");
        }
        require(v.optString("expectedCycleId").equals(metadata.optString("cycleId")), "Checkpoint cycle mismatch");
    }

    /** No mutation may discard lineage, consume twice, reset a claim, or manufacture a cycle replacement. */
    public void requireSuccessor(CentralContinuationRecord next) {
        JSONObject a = toJson(), b = next.toJson();
        require(revision() < Long.MAX_VALUE && next.revision() == revision() + 1, "Continuation revision conflict");
        for (String key : IDENTITY) require(equal(a.opt(key), b.opt(key)), "Immutable continuation identity: " + key);
        JSONArray oldLinks = a.optJSONArray("links"), links = b.optJSONArray("links");
        require(links.length() >= oldLinks.length() && links.length() <= oldLinks.length() + 1, "Lineage cannot be removed or batched");
        for (int i = 0; i < oldLinks.length(); i++) {
            JSONObject old = oldLinks.optJSONObject(i), value = links.optJSONObject(i);
            if (equal(old, value)) continue;
            require(i == oldLinks.length() - 1 && "PREPARED".equals(old.optString("state"))
                && "BOUND".equals(value.optString("state")), "Only the last prepared link may bind");
            for (Iterator<String> it = old.keys(); it.hasNext();) {
                String key = it.next();
                if (!"state".equals(key) && !"newCycleId".equals(key) && !"admittedAssignmentRevision".equals(key))
                    require(equal(old.opt(key), value.opt(key)), "Lineage source certificate changed");
            }
        }
        String cycle = a.optString("expectedCycleId");
        if (!cycle.equals(b.optString("expectedCycleId"))) {
            require(links.length() == oldLinks.length() && links.length() > 0, "Missing prepared successor");
            JSONObject old = oldLinks.optJSONObject(oldLinks.length() - 1), last = links.optJSONObject(links.length() - 1);
            require("PREPARED".equals(old.optString("state")) && "BOUND".equals(last.optString("state"))
                && cycle.equals(last.optString("oldCycleId"))
                && b.optString("expectedCycleId").equals(last.optString("newCycleId")), "Unlinked successor cycle");
            require(a.isNull("selectedResult"), "A consumed episode cannot change producer cycle");
        }
        if (links.length() > oldLinks.length()) {
            JSONObject last = links.optJSONObject(links.length() - 1);
            require("PREPARED".equals(last.optString("state")) && cycle.equals(last.optString("oldCycleId")), "Invalid prepared link");
            require(oldLinks.length() == 0 || "BOUND".equals(oldLinks.optJSONObject(oldLinks.length()-1).optString("state")), "Unsettled prior link");
        }
        if (!a.isNull("selectedResult")) {
            require(equal(a.opt("selectedResult"), b.opt("selectedResult"))
                && equal(a.opt("consumedResults"), b.opt("consumedResults")), "Consumed result is immutable");
            require(a.optString("inputPayloadSha256").equals(b.optString("inputPayloadSha256")), "Continuation input changed");
        }
        String before = a.optString("dispatchState"), after = b.optString("dispatchState");
        boolean allowed = before.equals(after) || "NONE".equals(before) && ("PREPARED".equals(after) || "CANCELLED".equals(after))
            || "PREPARED".equals(before) && ("STARTED".equals(after) || "CANCELLED".equals(after))
            || "STARTED".equals(before) && "ACKNOWLEDGED".equals(after);
        require(allowed, "Dispatch transition cannot replay or reset");
        if ("STARTED".equals(before) || "ACKNOWLEDGED".equals(before))
            for (String key : Arrays.asList("dispatchOwnerProcessId", "dispatchGenerationId", "assistantMessageStableId", "generationRunId"))
                require(a.optString(key).equals(b.optString(key)), "Claim owner is immutable");
        if ("CANCELLED".equals(before)) require(equalWithoutRevision(a, b), "Cancelled episode is immutable");
        JSONArray oldAdmissions = a.optJSONArray("centralAdmissions"), admissions = b.optJSONArray("centralAdmissions");
        require(admissions.length() >= oldAdmissions.length() && admissions.length() <= oldAdmissions.length() + 1,
            "Central admissions are append-only, one source at a time");
        for (int i = 0; i < oldAdmissions.length(); i++)
            require(equal(oldAdmissions.opt(i), admissions.opt(i)), "Central admission source is immutable");
        if (admissions.length() != oldAdmissions.length())
            require("PREPARED".equals(before) && "PREPARED".equals(after), "Central admission requires an unclaimed prepared episode");
        preserveAnswers(a.optJSONArray("answerBindings"), b.optJSONArray("answerBindings"));
    }

    private static void preserveAnswers(JSONArray a, JSONArray b) {
        require(b.length() >= a.length() && b.length() <= a.length() + 1, "Answer bindings cannot be discarded");
        for (int i = 0; i < a.length(); i++) {
            JSONObject old = a.optJSONObject(i), value = b.optJSONObject(i);
            for (Iterator<String> it = old.keys(); it.hasNext();) {
                String key = it.next();
                if (!"deliveryState".equals(key) && !"admissionCycleId".equals(key) && !"admittedAssignmentRevision".equals(key))
                    require(equal(old.opt(key), value.opt(key)), "Answer source changed");
            }
            String from = old.optString("deliveryState"), to = value.optString("deliveryState");
            require(from.equals(to) || "PREPARED".equals(from) && "STARTED".equals(to)
                || "STARTED".equals(from) && "INCORPORATED".equals(to), "Answer cannot be replayed");
            if (!old.optString("admissionCycleId").isEmpty())
                require(old.optString("admissionCycleId").equals(value.optString("admissionCycleId"))
                    && old.optLong("admittedAssignmentRevision") == value.optLong("admittedAssignmentRevision"), "Answer admission changed");
        }
    }

    private static void validate(JSONObject v) {
        try {
            require(integer(v, "version") == 1, "Unknown continuation metadata version");
            require(integer(v, "revision") > 0, "Invalid continuation revision");
            for (String key : IDENTITY) if (!"version".equals(key)) text(v, key);
            WorkspaceIdentity.requireWorkspaceId(text(v, "workspaceId"));
            require(WorkspaceIdentity.CENTRAL_SESSION.equals(text(v, "ownerCentralSessionId")), "Invalid Central owner");
            require(WorkspaceIdentity.executionSessionId(text(v,"workspaceId")).equals(text(v,"executionSessionId")), "Invalid execution session");
            require(waitId(text(v,"workspaceId"), text(v,"assignmentId")).equals(text(v,"waitId")), "Invalid wait identity");
            String key = "central-continuation:" + text(v,"waitId"), source = uuidFor(key);
            require(key.equals(text(v,"continuationKey")) && source.equals(text(v,"inputSourceId"))
                && ConversationInput.stableId(ConversationInput.Origin.AGENT, text(v,"executionSessionId"), source).equals(text(v,"inputStableId")), "Invalid continuation input identity");
            uuid(text(v,"initialCycleId")); uuid(text(v,"expectedCycleId"));
            JSONArray links = array(v,"links");
            Set<String> cycles = new HashSet<>(), controls = new HashSet<>();
            String cycle = text(v,"initialCycleId"); cycles.add(cycle);
            for (int i=0;i<links.length();i++) {
                JSONObject link = links.getJSONObject(i);
                require(controls.add(text(link,"controlId")), "Duplicate control link");
                oneOf(text(link,"controlType"), "RESUME", "CORRECT");
                require(integer(link,"observedAssignmentRevision") > 0, "Invalid observed revision");
                require(cycle.equals(text(link,"oldCycleId")), "Disconnected cycle lineage");
                digest(text(link,"originCheckpointSha256"));
                require(("native-workspace:"+text(v,"workspaceId")).equals(text(link,"originScopeIdentity")), "Wrong source scope");
                require(integer(link,"originSavedAtMs") > 0, "Invalid source checkpoint time");
                for(String name:Arrays.asList("profileSha256","transcriptSha256","toolLifecycleSha256","artifactOwnershipSha256")) digest(text(link,name));
                require(Boolean.TRUE.equals(link.opt("safePoint")), "Missing safe-point certificate");
                String state=text(link,"state"); oneOf(state,"PREPARED","BOUND");
                if("BOUND".equals(state)) {
                    require(integer(link,"admittedAssignmentRevision") > integer(link,"observedAssignmentRevision"), "Invalid admitted revision");
                    cycle=text(link,"newCycleId");uuid(cycle); require(cycles.add(cycle), "Cycle reused");
                } else require(i==links.length()-1 && "".equals(link.getString("newCycleId")), "Invalid pending link");
            }
            require(cycle.equals(text(v,"expectedCycleId")), "Expected cycle not linked");
            JSONArray consumed=array(v,"consumedResults");
            String state=text(v,"dispatchState"); oneOf(state,"NONE","PREPARED","STARTED","ACKNOWLEDGED","CANCELLED");
            require(v.has("selectedResult"), "Missing selected result field");
            if(v.isNull("selectedResult")) {
                require(consumed.length()==0 && ("NONE".equals(state)||"CANCELLED".equals(state)), "Unselected continuation claimed");
                require("".equals(v.getString("inputPayloadSha256")), "Unselected payload");
            } else {
                JSONObject result=v.getJSONObject("selectedResult");
                text(result,"resultReferenceId"); text(result,"evidenceId");digest(text(result,"contentSha256"));
                require(cycle.equals(text(result,"producerCycleId")), "Result belongs to another cycle");
                require(consumed.length()==1 && equal(result,consumed.getJSONObject(0)), "Consumption does not match selection");
                require(!"NONE".equals(state), "Result without continuation state");digest(text(v,"inputPayloadSha256"));
            }
            for(String name:Arrays.asList("dispatchOwnerProcessId","dispatchGenerationId","assistantMessageStableId","generationRunId")) {
                String value=v.getString(name);
                if("STARTED".equals(state)||"ACKNOWLEDGED".equals(state)) requireText(value,name);
                else require(value.isEmpty(),"Unclaimed dispatch owner");
            }
            JSONArray admissions = array(v, "centralAdmissions");
            require(admissions.length() == 0 || !"NONE".equals(state), "Central admission without selected result");
            Set<String> admissionIds = new HashSet<>();
            for (int i = 0; i < admissions.length(); i++) {
                JSONObject admission = admissions.getJSONObject(i);
                require(admission.length() == 6, "Invalid Central admission source fields");
                for (String field : Arrays.asList("userMessageId", "sourceBatchId", "sourceCallId", "sourceRunId",
                        "sourceProcessId", "storageGeneration")) text(admission, field);
                // Include lengths so arbitrary source identifiers cannot collide by separator injection.
                String call = text(admission, "sourceCallId"), run = text(admission, "sourceRunId");
                require(admissionIds.add(call.length() + ":" + call + run), "Duplicate Central admission source");
            }
            Set<String> answerIds=new HashSet<>();
            JSONArray answers=array(v,"answerBindings");
            for(int i=0;i<answers.length();i++) {
                JSONObject answer=answers.getJSONObject(i);
                require(answerIds.add(text(answer,"questionMessageId")),"Duplicate answer binding");
                text(answer,"questionReferenceId");digest(text(answer,"questionSha256"));uuid(text(answer,"originalCycleId"));
                require(cycles.contains(answer.getString("originalCycleId")),"Question cycle not in lineage");
                text(answer,"outboxMessageId");text(answer,"outboxStableId");digest(text(answer,"outboxPayloadSha256"));
                require(integer(answer,"assignmentRevision")>0,"Missing original answer revision");
                oneOf(text(answer,"deliveryState"),"PREPARED","STARTED","INCORPORATED");
                String admitted=answer.getString("admissionCycleId");
                if(!admitted.isEmpty()) {uuid(admitted);require(cycles.contains(admitted),"Unlinked answer cycle");require(integer(answer,"admittedAssignmentRevision")>0,"Missing answer revision");}
                else require("PREPARED".equals(answer.getString("deliveryState")),"Dispatched answer without admission");
            }
        } catch(org.json.JSONException invalid) { throw invalid("Malformed continuation metadata: " + invalid.getMessage()); }
    }

    private static JSONObject copy(JSONObject v) { return parse(v.toString()); }
    private static JSONObject parse(String s) { try{return new JSONObject(s);}catch(org.json.JSONException e){throw invalid(e.getMessage());} }
    private static JSONArray array(JSONObject v,String key) { JSONArray a=v.optJSONArray(key);require(a!=null,"Missing array: "+key);return a; }
    private static long integer(JSONObject v,String key) { Object n=v.opt(key);require(n instanceof Integer||n instanceof Long,"Not an integer: "+key);return ((Number)n).longValue(); }
    private static String text(JSONObject v,String key) { Object s=v.opt(key);require(s instanceof String,"Not text: "+key);requireText((String)s,key);return (String)s; }
    private static void requireText(String s,String key) { require(s!=null&&!s.isEmpty()&&s.length()<=1024,"Invalid text: "+key); }
    private static void uuid(String s) { try{require(UUID.fromString(s).toString().equals(s),"Invalid cycle UUID");}catch(RuntimeException e){throw invalid("Invalid cycle UUID");} }
    private static void digest(String s) { require(s.matches("[a-f0-9]{64}"),"Invalid SHA-256"); }
    private static void oneOf(String s,String...values) {require(Arrays.asList(values).contains(s),"Invalid state: "+s);}
    private static void require(boolean condition,String message) { if(!condition)throw invalid(message); }
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
    static boolean equal(Object a,Object b) {
        if(a==b)return true;if(a==null||b==null)return false;
        if(a instanceof JSONObject&&b instanceof JSONObject) {
            JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;
            for(Iterator<String> it=x.keys();it.hasNext();) {String k=it.next();if(!y.has(k)||!equal(x.opt(k),y.opt(k)))return false;}return true;
        }
        if(a instanceof JSONArray&&b instanceof JSONArray) {JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!equal(x.opt(i),y.opt(i)))return false;return true;}
        return a.equals(b);
    }
    private static boolean equalWithoutRevision(JSONObject a,JSONObject b) {JSONObject x=copy(a),y=copy(b);x.remove("revision");y.remove("revision");return equal(x,y);}
}
