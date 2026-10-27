package com.jarvys.agent.coding;

import com.jarvys.agent.CentralInvocationSource;
import com.jarvys.agent.CrewInputContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import static com.jarvys.agent.coding.CodingJobEvidence.*;

/** Strict v2 evidence-envelope codec, not the operational journal or a persistence witness issuer.
 * schema=3 identifies the planned envelope family; envelopeVersion=2 explicitly rejects the former
 * unwired shape. No operational job schema 1/2 decoder is changed or invoked here.
 * Input arrays use lexical input-ID order and fixed CREW_BINDING-before-CONVERSATION_INPUT order;
 * duplicates, conflicting coverage and noncanonical ordering are rejected rather than normalized.
 */
public final class CodingJobEvidenceCodec {
    private CodingJobEvidenceCodec() { }
    public static String encode(CodingJobEvidence.Record r) {
        Objects.requireNonNull(r);
        return json(object("schema", 3L, "envelopeVersion", 2L, "binding", binding(r.binding), "revision", r.revision,
                "highestEffectPhase", r.highestEffectPhase.name(), "submissionCommitted", r.submissionCommitted,
                "authorization", auth(r.authorization), "terminal", terminal(r.terminal)));
    }
    /** The caller supplies its existing journal input budget; no product resource limit is added. */
    public static ValidatedRecord decode(String encoded, int maxChars) {
        if (maxChars <= 0 || encoded == null || encoded.length() > maxChars)
            throw new IllegalArgumentException("Envelope exceeds supplied parse budget");
        Map<String,Object> root = map(new Parser(encoded).parse());
        keys(root, "schema", "envelopeVersion", "binding", "revision", "highestEffectPhase", "submissionCommitted", "authorization", "terminal");
        if (number(root, "schema") != 3 || number(root, "envelopeVersion") != 2) throw new IllegalArgumentException("Unsupported evidence schema");
        return new ValidatedRecord(new CodingJobEvidence.Record(readBinding(map(root.get("binding"))),
                number(root, "revision"), enumeration(root, "highestEffectPhase", Phase.class),
                bool(root, "submissionCommitted"), readAuth(root.get("authorization")), readTerminal(root.get("terminal"))));
    }
    private static Map<String,Object> binding(Binding b) {
        CrewSource c = b.source.crew; WorkspaceBinding w = b.source.workspace;
        CentralInvocationSource a = b.source.rootCentral;
        return object("technicalStoreGeneration", b.technicalStoreGeneration, "jobId", b.jobId, "submissionId", b.submissionId, "owner", b.owner,
                "scopeId", b.scopeId, "durableScopeIdentity", b.durableScopeIdentity,
                "ownerProcessId", b.ownerProcessId, "managerInstanceId", b.managerInstanceId,
                "semanticCapture", b.semanticCapture.name(), "source", object(
                    "crew", object("kind", c.kind.name(),
                        "executionSessionId", c.executionSessionId, "botId", c.botId, "missionId", c.missionId,
                        "cycleId", c.cycleId, "scopeIdentity", c.scopeIdentity,
                        "checkpointPayloadSha256", c.checkpointPayloadSha256, "callId", c.callId, "toolName", c.toolName, "inputContext", inputContext(c.inputContext)),
                    "workspace", w == null ? null : object("workspaceId", w.workspaceId, "assignmentId", w.assignmentId, "requestId", w.requestId),
                    "rootCentral", a == null ? null : object("storageGeneration", a.storageGeneration,
                        "ownerCentralSessionId", a.ownerCentralSessionId, "sourceMessageId", a.sourceMessageId,
                        "sourceBatchId", a.sourceBatchId, "sourceCallId", a.sourceCallId,
                        "sourceRunId", a.sourceRunId, "sourceProcessId", a.sourceProcessId)));
    }
    private static Binding readBinding(Map<String,Object> b) {
        keys(b, "technicalStoreGeneration", "jobId", "submissionId", "owner", "scopeId", "durableScopeIdentity", "ownerProcessId", "managerInstanceId", "semanticCapture", "source");
        Map<String,Object> s = map(b.get("source")); keys(s, "crew", "workspace", "rootCentral");
        Map<String,Object> c = map(s.get("crew"));
        keys(c, "kind", "executionSessionId", "botId", "missionId", "cycleId", "scopeIdentity", "checkpointPayloadSha256", "callId", "toolName", "inputContext");
        enumeration(c, "kind", SourceKind.class);
        CrewSource crew = new CrewSource(string(c,"executionSessionId"),
                string(c,"botId"), string(c,"missionId"), string(c,"cycleId"), string(c,"scopeIdentity"),
                string(c,"checkpointPayloadSha256"), string(c,"callId"), string(c,"toolName"), readInputContext(map(c.get("inputContext"))));
        WorkspaceBinding workspace = null;
        if (s.get("workspace") != null) {
            Map<String,Object> w = map(s.get("workspace")); keys(w, "workspaceId", "assignmentId", "requestId");
            workspace = new WorkspaceBinding(string(w,"workspaceId"), string(w,"assignmentId"), string(w,"requestId"));
        }
        CentralInvocationSource central = null;
        if (s.get("rootCentral") != null) {
            Map<String,Object> a = map(s.get("rootCentral"));
            keys(a, "storageGeneration", "ownerCentralSessionId", "sourceMessageId", "sourceBatchId", "sourceCallId", "sourceRunId", "sourceProcessId");
            central = new CentralInvocationSource(string(a,"storageGeneration"), string(a,"ownerCentralSessionId"),
                    string(a,"sourceMessageId"), string(a,"sourceBatchId"), string(a,"sourceCallId"),
                    string(a,"sourceRunId"), string(a,"sourceProcessId"));
        }
        return new Binding(string(b,"technicalStoreGeneration"), string(b,"jobId"), string(b,"submissionId"), string(b,"owner"), string(b,"scopeId"),
                string(b,"durableScopeIdentity"), string(b,"ownerProcessId"), string(b,"managerInstanceId"),
                new Source(crew, workspace, central), enumeration(b,"semanticCapture",SemanticCapture.class));
    }
    private static Map<String,Object> inputContext(CrewInputContext context) {
        List<Object> refs = new ArrayList<>();
        for (CrewInputContext.Reference ref : context.references) {
            Map<String,Object> item = object("channel", ref.channel.name(), "inputId", ref.inputId,
                    "sourceKind", ref.sourceKind, "sourceId", ref.sourceId, "scope", scope(ref.scope));
            if (ref.channel == CrewInputContext.Channel.CREW_BINDING) {
                item.putAll(object("admissionCycleId", ref.admissionCycleId,
                        "firstIncorporationCycleId", ref.firstIncorporationCycleId, "transportKind", ref.transportKind,
                        "sourcePayloadSha256", ref.sourcePayloadSha256, "deliverySha256", ref.deliverySha256,
                        "transportSha256", ref.transportSha256));
            } else {
                item.putAll(object("principalRole", ref.principalRole, "correlationMessageId", ref.correlationMessageId,
                        "correlationReferenceId", ref.correlationReferenceId, "correlationSha256", ref.correlationSha256,
                        "assignmentRevision", ref.assignmentRevision, "inputOrigin", ref.inputOrigin,
                        "recordedRequestId", ref.recordedRequestId, "recordedProvenanceCycleId", ref.recordedProvenanceCycleId));
            }
            refs.add(item);
        }
        return object("coverage", context.coverage.name(), "references", refs,
                "unresolvedInputIds", new ArrayList<>(context.unresolvedInputIds));
    }
    private static Map<String,Object> scope(CrewInputContext.Scope s) {
        return object("sourceSessionId", s.sourceSessionId, "executionSessionId", s.executionSessionId,
                "workspaceId", s.workspaceId, "assignmentId", s.assignmentId,
                "recordedProvenanceBotId", s.recordedProvenanceBotId, "missionId", s.missionId);
    }
    private static CrewInputContext.Scope readScope(Object value) {
        Map<String,Object> s=map(value);
        keys(s,"sourceSessionId","executionSessionId","workspaceId","assignmentId","recordedProvenanceBotId","missionId");
        return new CrewInputContext.Scope(string(s,"sourceSessionId"),string(s,"executionSessionId"),
                string(s,"workspaceId"),string(s,"assignmentId"),string(s,"recordedProvenanceBotId"),string(s,"missionId"));
    }
    private static CrewInputContext.Reference readReference(Object value) {
        Map<String,Object> r=map(value);
        CrewInputContext.Channel channel=enumeration(r,"channel",CrewInputContext.Channel.class);
        if(channel==CrewInputContext.Channel.CREW_BINDING) {
            keys(r,"channel","inputId","sourceKind","sourceId","scope","admissionCycleId",
                    "firstIncorporationCycleId","transportKind","sourcePayloadSha256","deliverySha256","transportSha256");
            return CrewInputContext.Reference.binding(string(r,"inputId"),string(r,"sourceKind"),string(r,"sourceId"),
                    readScope(r.get("scope")),string(r,"admissionCycleId"),string(r,"firstIncorporationCycleId"),
                    string(r,"transportKind"),string(r,"sourcePayloadSha256"),string(r,"deliverySha256"),string(r,"transportSha256"));
        }
        keys(r,"channel","inputId","sourceKind","sourceId","scope","principalRole","correlationMessageId",
                "correlationReferenceId","correlationSha256","assignmentRevision","inputOrigin","recordedRequestId","recordedProvenanceCycleId");
        return CrewInputContext.Reference.conversation(string(r,"inputId"),string(r,"sourceKind"),string(r,"sourceId"),
                readScope(r.get("scope")),string(r,"principalRole"),string(r,"correlationMessageId"),string(r,"correlationReferenceId"),
                string(r,"correlationSha256"),number(r,"assignmentRevision"),string(r,"inputOrigin"),
                string(r,"recordedRequestId"),string(r,"recordedProvenanceCycleId"));
    }
    private static CrewInputContext readInputContext(Map<String,Object> c) {
        keys(c,"coverage","references","unresolvedInputIds");
        CrewInputContext.Coverage coverage=enumeration(c,"coverage",CrewInputContext.Coverage.class);
        List<CrewInputContext.Reference> refs=new ArrayList<>();
        Set<String> applied=new TreeSet<>();
        String previousId=null; int previousChannel=-1;
        for(Object raw:list(c.get("references"))) {
            CrewInputContext.Reference ref=readReference(raw);
            // Version 2 canonical order: lexical input ID, then CREW_BINDING before CONVERSATION_INPUT.
            int channel=ref.channel==CrewInputContext.Channel.CREW_BINDING?0:1;
            if(previousId!=null && (ref.inputId.compareTo(previousId)<0 ||
                    ref.inputId.equals(previousId) && channel<=previousChannel))
                throw new IllegalArgumentException("Noncanonical or duplicate recorded input reference");
            refs.add(ref);applied.add(ref.inputId);previousId=ref.inputId;previousChannel=channel;
        }
        List<String> unresolved=new ArrayList<>(); String previous=null;
        for(Object raw:list(c.get("unresolvedInputIds"))) {
            if(!(raw instanceof String))throw new IllegalArgumentException("Expected unresolved input identity");
            String id=(String)raw;
            if(previous!=null && id.compareTo(previous)<=0 || applied.contains(id))
                throw new IllegalArgumentException("Duplicate, overlapping or noncanonical unresolved identity");
            unresolved.add(id);previous=id;
        }
        applied.addAll(unresolved);
        CrewInputContext result=CrewInputContext.resolve(applied,refs);
        if(result.coverage!=coverage || !result.references.equals(refs) ||
                !new ArrayList<>(result.unresolvedInputIds).equals(unresolved))
            throw new IllegalArgumentException("Recorded input context does not match canonical coverage");
        return result;
    }
    private static List<?> list(Object value) {
        if(!(value instanceof List))throw new IllegalArgumentException("Expected envelope array");
        return (List<?>)value;
    }
    private static Map<String,Object> auth(AuthorizationSnapshot a) {
        return a == null ? null : object("authorization", a.authorization.name(), "authority", a.authority,
                "actionId", a.actionId, "revision", a.revision, "origin", a.origin);
    }
    private static AuthorizationSnapshot readAuth(Object value) {
        if (value == null) return null;
        Map<String,Object> a = map(value); keys(a, "authorization", "authority", "actionId", "revision", "origin");
        return new AuthorizationSnapshot(enumeration(a,"authorization",Authorization.class), string(a,"authority"),
                string(a,"actionId"), string(a,"revision"), string(a,"origin"));
    }
    private static Map<String,Object> terminal(TerminalFacts t) {
        return t == null ? null : object("state", t.state.name(), "exitCode", t.exitCode,
                "cancellationObserved", t.cancellationObserved, "timeoutObserved", t.timeoutObserved,
                "uncertaintyObserved", t.uncertaintyObserved, "absenceCoverage", t.absenceCoverage.name());
    }
    private static TerminalFacts readTerminal(Object value) {
        if (value == null) return null;
        Map<String,Object> t = map(value); keys(t, "state", "exitCode", "cancellationObserved", "timeoutObserved", "uncertaintyObserved", "absenceCoverage");
        Integer exit = null;
        if (t.get("exitCode") != null) {
            long code = number(t,"exitCode");
            if (code < Integer.MIN_VALUE || code > Integer.MAX_VALUE) throw new IllegalArgumentException("Exit code overflow");
            exit = (int) code;
        }
        return new TerminalFacts(enumeration(t,"state",TerminalState.class), exit, bool(t,"cancellationObserved"),
                bool(t,"timeoutObserved"), bool(t,"uncertaintyObserved"), enumeration(t,"absenceCoverage",AbsenceCoverage.class));
    }
    private static Map<String,Object> object(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected envelope object");
        return (Map<String,Object>)value;
    }
    private static void keys(Map<String,Object> value, String... names) {
        if (value.size() != names.length || !value.keySet().containsAll(Arrays.asList(names)))
            throw new IllegalArgumentException("Unexpected or missing envelope fields");
    }
    private static String string(Map<String,Object> value, String key) {
        Object field = value.get(key);
        if (!(field instanceof String)) throw new IllegalArgumentException("Expected string field");
        return (String)field;
    }
    private static long number(Map<String,Object> value, String key) {
        Object field = value.get(key);
        if (!(field instanceof Long)) throw new IllegalArgumentException("Expected exact integer field");
        return (Long)field;
    }
    private static boolean bool(Map<String,Object> value, String key) {
        Object field = value.get(key);
        if (!(field instanceof Boolean)) throw new IllegalArgumentException("Expected boolean field");
        return (Boolean)field;
    }
    private static <T extends Enum<T>> T enumeration(Map<String,Object> value, String key, Class<T> type) {
        try { return Enum.valueOf(type, string(value,key)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown envelope enum value"); }
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : ((String)value).toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                // Preserve UTF-16 identity through UTF-8 transport, including isolated surrogate units.
                else if (c < 32 || Character.isSurrogate(c)) out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{"); boolean first = true;
            for (Map.Entry<String,Object> entry : map(value).entrySet()) {
                if (!first) out.append(','); first = false;
                out.append(json(entry.getKey())).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("["); boolean first=true;
            for (Object item : (List<?>)value) { if(!first)out.append(',');first=false;out.append(json(item)); }
            return out.append(']').toString();
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer) return value.toString();
        throw new IllegalArgumentException("Unsupported envelope value");
    }
    /** Only schema-supported JSON types; rejects duplicate keys, coercions, decimals and trailing data. */
    private static final class Parser {
        // root/binding/source/crew/inputContext/references/reference/scope/scalar = depth 8.
        // This is the v2 shape boundary, not a user-data count/length limit.
        static final int MAX_SCHEMA_DEPTH = 8;
        final String input; int at;
        Parser(String input) { this.input = input; }
        Object parse() { Object result = value(0); space(); if (at != input.length()) fail(); return result; }
        void space() { while (at < input.length() && " \r\n\t".indexOf(input.charAt(at)) >= 0) at++; }
        void fail() { throw new IllegalArgumentException("Malformed evidence envelope"); }
        char take() { if (at >= input.length()) fail(); return input.charAt(at++); }
        boolean eat(char expected) { space(); if (at < input.length() && input.charAt(at) == expected) { at++; return true; } return false; }
        Object value(int depth) {
            space(); if (at >= input.length() || depth > MAX_SCHEMA_DEPTH) { fail(); return null; }
            char c = input.charAt(at);
            if (c == '"') return text();
            if (c == '{') {
                at++; Map<String,Object> result = new LinkedHashMap<>();
                if (eat('}')) return result;
                do {
                    space(); if (at >= input.length() || input.charAt(at) != '"') fail();
                    String key = text(); if (result.containsKey(key) || !eat(':')) fail();
                    result.put(key, value(depth+1));
                    if (eat('}')) return result;
                } while (eat(','));
                fail(); return null;
            }
            if (c == '[') {
                at++; List<Object> result = new ArrayList<>();
                if (eat(']')) return result;
                do { result.add(value(depth+1)); if(eat(']'))return result; } while(eat(','));
                fail(); return null;
            }
            if (input.startsWith("true", at)) { at += 4; return true; }
            if (input.startsWith("false", at)) { at += 5; return false; }
            if (input.startsWith("null", at)) { at += 4; return null; }
            int start = at;
            if (c == '-') at++;
            if (at >= input.length() || input.charAt(at) < '0' || input.charAt(at) > '9') fail();
            if (input.charAt(at) == '0') at++;
            else while (at < input.length() && input.charAt(at) >= '0' && input.charAt(at) <= '9') at++;
            try { return Long.parseLong(input.substring(start,at)); }
            catch (NumberFormatException bad) { throw new IllegalArgumentException("Invalid exact envelope integer"); }
        }
        String text() {
            if (take() != '"') fail(); StringBuilder out = new StringBuilder();
            while (true) {
                char c = take(); if (c == '"') return out.toString(); if (c < 32) fail();
                if (c == '\\') {
                    c = take();
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                        case 'u': int number = 0; for (int i=0;i<4;i++) { char hex = take(); int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex)); if (digit < 0) fail(); number = number*16+digit; } c=(char)number; break;
                        default: fail();
                    }
                }
                out.append(c);
            }
        }
    }
}
