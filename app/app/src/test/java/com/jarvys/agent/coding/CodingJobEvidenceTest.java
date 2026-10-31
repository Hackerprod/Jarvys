package com.jarvys.agent.coding;

import com.jarvys.agent.CentralInvocationSource;
import com.jarvys.agent.CrewInputContext;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.jarvys.agent.coding.CodingJobEvidence.*;

/** Pure metadata tests: deliberately no manager, IO, receipt minting or effect authority. */
public class CodingJobEvidenceTest {
    private static final String JOB = "00000000-0000-0000-0000-000000000001";
    private static final String SUBMISSION = "00000000-0000-0000-0000-000000000002";
    private static final String PROCESS = "00000000-0000-0000-0000-000000000003";
    private static final String MANAGER = "00000000-0000-0000-0000-000000000004";
    private static final String GENERATION = "00000000-0000-0000-0000-000000000005";
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static CrewSource crew(String cycle, String call) {
        return new CrewSource("workspace-execution", "bot", "mission", cycle, "scope-identity", DIGEST, call, "project_exec",
                inputContext());
    }
    private static CrewInputContext inputContext() {
        CrewInputContext.Scope scope = new CrewInputContext.Scope("central", "workspace-execution", "workspace", "assignment", "bot", "mission");
        CrewInputContext.Reference reference = CrewInputContext.Reference.binding("input", "REPLY", "reply", scope,
                "prior-cycle", "prior-cycle", "INBOX", DIGEST, DIGEST, DIGEST);
        return CrewInputContext.resolve(java.util.Arrays.asList("input", "unresolved"), Collections.singletonList(reference));
    }
    private static CentralInvocationSource central(String call) {
        return new CentralInvocationSource("g3", "central", "message", "central-batch", call, "central-run", "central-process");
    }
    private static Binding binding(SemanticCapture mode) {
        Source source = new Source(crew("cycle", "child-call"), new WorkspaceBinding("workspace", "assignment", "request"),
                central("delegate-call"));
        return bind(source, mode);
    }
    private static Binding bind(Source source, SemanticCapture mode) {
        return new Binding(GENERATION, JOB, SUBMISSION, "owner", "scope", "scope-identity", PROCESS, MANAGER, source, mode);
    }
    private static AuthorizationSnapshot authorization() {
        return new AuthorizationSnapshot(Authorization.ASK_APPROVED, "coding", "project_exec", "7", "approved_snapshot");
    }
    private static CodingJobEvidence.Record record(long revision, Phase phase, boolean accepted, TerminalFacts terminal) {
        return new CodingJobEvidence.Record(binding(SemanticCapture.ENABLED), revision, phase, accepted,
                phase == Phase.SUBMISSION_INTENT ? null : authorization(), terminal);
    }
    private static TerminalFacts terminal(TerminalState state, Integer code) {
        return new TerminalFacts(state, code, state == TerminalState.CANCELLED,
                state == TerminalState.TIMED_OUT, state == TerminalState.UNCERTAIN, AbsenceCoverage.NOT_PROVEN);
    }
    private static CodingJobEvidence.Record roundTrip(CodingJobEvidence.Record record) {
        String json = CodingJobEvidenceCodec.encode(record);
        return CodingJobEvidenceCodec.decode(json, json.length()).record;
    }
    private static void reject(Runnable action) {
        try { action.run(); fail("Malformed or conflicting metadata must be rejected"); }
        catch (IllegalArgumentException | NullPointerException expected) { }
    }
    private static void rejectJson(String json) { reject(() -> CodingJobEvidenceCodec.decode(json, Math.max(1,json.length()))); }

    @Test public void sourceValuesPreserveDistinctCentralAndCrewCallsWithoutSyntheticChildBatch() {
        CodingJobEvidence.Record original = record(1, Phase.SUBMISSION_INTENT, false, null);
        CodingJobEvidence.Record decoded = roundTrip(original);
        assertEquals(original, decoded); assertEquals(original.hashCode(), decoded.hashCode());
        assertEquals("child-call", decoded.binding.source.crew.callId);
        assertEquals("delegate-call", decoded.binding.source.rootCentral.sourceCallId);
        assertEquals("central-batch", decoded.binding.source.rootCentral.sourceBatchId);
        assertEquals(central("delegate-call"), decoded.binding.source.rootCentral);
        assertEquals(inputContext(), decoded.binding.source.crew.inputContext);
        assertEquals(CrewInputContext.Coverage.PARTIAL, decoded.binding.source.crew.inputContext.coverage);
        assertEquals(Collections.singleton("unresolved"), decoded.binding.source.crew.inputContext.unresolvedInputIds);
        assertEquals("prior-cycle", decoded.binding.source.crew.inputContext.references.get(0).admissionCycleId);
        assertNotSame(original, decoded);
    }
    @Test public void semanticCaptureIsIndependentOfHistoricalCentralRoot() {
        Source operational = new Source(crew("cycle", "call"), null, null);
        Binding enabled = bind(operational, SemanticCapture.ENABLED);
        Binding disabled = bind(operational, SemanticCapture.DISABLED);
        assertNotEquals(enabled, disabled);
        for (Binding b : new Binding[]{enabled, disabled, binding(SemanticCapture.ENABLED), binding(SemanticCapture.DISABLED)}) {
            CodingJobEvidence.Record r = new CodingJobEvidence.Record(b,1,Phase.SUBMISSION_INTENT,false,null,null);
            assertEquals(r, roundTrip(r));
        }
        reject(() -> new Source(crew("cycle","call"), null, central("call")));
        CodingJobEvidence.Record before = new CodingJobEvidence.Record(enabled,1,Phase.SUBMISSION_INTENT,false,null,null);
        reject(() -> validateAdvance(before,new CodingJobEvidence.Record(disabled,2,Phase.SUBMISSION_INTENT,true,null,null)));
    }
    @Test public void partialAttributionAndForeignInputExecutionAreRejected() {
        reject(() -> new WorkspaceBinding("workspace", null, "request"));
        reject(() -> new CentralInvocationSource("g3","central","message",null,"call","run","process"));
        CrewInputContext.Scope scope = new CrewInputContext.Scope("central", "other-execution", "workspace", "assignment", "bot", "mission");
        CrewInputContext.Reference reference = CrewInputContext.Reference.binding("input", "REPLY", "reply", scope,
                "cycle", "cycle", "INBOX", DIGEST, DIGEST, DIGEST);
        CrewInputContext input = CrewInputContext.resolve(Collections.singleton("input"), Collections.singletonList(reference));
        reject(() -> new CrewSource("workspace-execution", "bot", "mission", "cycle", "scope-identity", DIGEST,
                "call", "project_exec", input));
        // Historical root generation is retained metadata, not this technical store's authority.
        CentralInvocationSource historical = new CentralInvocationSource("g4","central","message","batch","call","run","process");
        Source source = new Source(crew("cycle","call"),new WorkspaceBinding("workspace","assignment","request"),historical);
        assertEquals(historical,roundTrip(new CodingJobEvidence.Record(bind(source, SemanticCapture.ENABLED),1,
                Phase.SUBMISSION_INTENT,false,null,null)).binding.source.rootCentral);
    }
    @Test public void acceptanceAndLaunchIntentRemainNonterminalMetadata() {
        CodingJobEvidence.Record initial = record(1,Phase.SUBMISSION_INTENT,false,null);
        CodingJobEvidence.Record accepted = record(2,Phase.SUBMISSION_INTENT,true,null);
        CodingJobEvidence.Record prep = record(3,Phase.PREPARATION_INTENT,true,null);
        CodingJobEvidence.Record launch = record(4,Phase.LAUNCH_INTENT,true,null);
        assertEquals(Advance.NEXT, validateAdvance(initial,accepted));
        assertEquals(Advance.NEXT, validateAdvance(accepted,prep));
        assertEquals(Advance.NEXT, validateAdvance(prep,launch));
        reject(() -> validateAdvance(accepted,record(3,Phase.LAUNCH_INTENT,true,null)));
        assertNull(roundTrip(accepted).terminal); assertNull(roundTrip(launch).terminal);
    }
    @Test public void exactRevisionReplayIsIdempotentButChangedPayloadAndGapsAreRejected() {
        CodingJobEvidence.Record one = record(1,Phase.SUBMISSION_INTENT,false,null);
        assertEquals(Advance.IDEMPOTENT,validateAdvance(one,roundTrip(one)));
        reject(() -> validateAdvance(one,record(1,Phase.SUBMISSION_INTENT,true,null)));
        reject(() -> validateAdvance(one,record(3,Phase.SUBMISSION_INTENT,true,null)));
        CodingJobEvidence.Record max = record(Long.MAX_VALUE,Phase.SUBMISSION_INTENT,true,null);
        assertEquals(max,roundTrip(max));
        reject(() -> validateAdvance(max,record(1,Phase.SUBMISSION_INTENT,true,null)));
    }
    @Test public void jobSubmissionOwnerAndCrewCycleCannotChangeAcrossRevisions() {
        CodingJobEvidence.Record one = record(1,Phase.SUBMISSION_INTENT,false,null);
        Binding b = one.binding;
        for (Binding foreign : new Binding[]{
                new Binding(PROCESS,JOB,SUBMISSION,b.owner,b.scopeId,b.durableScopeIdentity,PROCESS,MANAGER,b.source,b.semanticCapture),
                new Binding(GENERATION,SUBMISSION,SUBMISSION,b.owner,b.scopeId,b.durableScopeIdentity,PROCESS,MANAGER,b.source,b.semanticCapture),
                new Binding(GENERATION,JOB,JOB,b.owner,b.scopeId,b.durableScopeIdentity,PROCESS,MANAGER,b.source,b.semanticCapture),
                new Binding(GENERATION,JOB,SUBMISSION,"other",b.scopeId,b.durableScopeIdentity,PROCESS,MANAGER,b.source,b.semanticCapture),
                new Binding(GENERATION,JOB,SUBMISSION,b.owner,b.scopeId,b.durableScopeIdentity,MANAGER,PROCESS,b.source,b.semanticCapture),
                bind(new Source(crew("other-cycle","child-call"),b.source.workspace,b.source.rootCentral),b.semanticCapture),
                bind(new Source(crew("cycle","other-call"),b.source.workspace,b.source.rootCentral),b.semanticCapture)}) {
            reject(() -> validateAdvance(one,new CodingJobEvidence.Record(foreign,2,Phase.SUBMISSION_INTENT,true,null,null)));
        }
    }
    @Test public void phaseAcceptanceAndAuthorizationCannotRegress() {
        CodingJobEvidence.Record prep = record(2,Phase.PREPARATION_INTENT,true,null);
        reject(() -> validateAdvance(prep,record(3,Phase.SUBMISSION_INTENT,true,null)));
        reject(() -> new CodingJobEvidence.Record(prep.binding,3,Phase.PREPARATION_INTENT,false,authorization(),null));
        reject(() -> new CodingJobEvidence.Record(prep.binding,3,Phase.PREPARATION_INTENT,true,null,null));
        AuthorizationSnapshot changed = new AuthorizationSnapshot(Authorization.ALLOW,"coding","project_exec","8","new_policy");
        reject(() -> validateAdvance(prep,new CodingJobEvidence.Record(prep.binding,3,Phase.LAUNCH_INTENT,true,changed,null)));
        reject(() -> validateAdvance(record(1,Phase.SUBMISSION_INTENT,true,null),record(2,Phase.SUBMISSION_INTENT,false,null)));
    }
    @Test public void everyTerminalStateRoundTripsWithoutLosingHighestIntent() {
        for (TerminalState state : TerminalState.values()) {
            Integer code = state == TerminalState.SUCCEEDED ? Integer.valueOf(0) : state == TerminalState.FAILED ? Integer.valueOf(2) : null;
            CodingJobEvidence.Record r = record(5,Phase.LAUNCH_INTENT,true,terminal(state,code));
            assertEquals(r,roundTrip(r)); assertEquals(Phase.LAUNCH_INTENT,roundTrip(r).highestEffectPhase);
        }
    }
    @Test public void terminalCannotBeReopenedRevisedOrContradicted() {
        CodingJobEvidence.Record done = record(5,Phase.LAUNCH_INTENT,true,terminal(TerminalState.SUCCEEDED,0));
        assertEquals(Advance.IDEMPOTENT,validateAdvance(done,roundTrip(done)));
        reject(() -> validateAdvance(done,record(6,Phase.LAUNCH_INTENT,true,null)));
        reject(() -> validateAdvance(done,record(6,Phase.LAUNCH_INTENT,true,terminal(TerminalState.SUCCEEDED,0))));
        reject(() -> validateAdvance(done,record(5,Phase.LAUNCH_INTENT,true,terminal(TerminalState.FAILED,1))));
    }
    @Test public void contradictoryProcessExitFactsCannotClaimSuccessOrFailure() {
        reject(() -> terminal(TerminalState.SUCCEEDED,null)); reject(() -> terminal(TerminalState.SUCCEEDED,2));
        reject(() -> terminal(TerminalState.FAILED,0)); reject(() -> terminal(TerminalState.FAILED,null));
        reject(() -> new TerminalFacts(TerminalState.SUCCEEDED,0,true,false,false,AbsenceCoverage.NOT_PROVEN));
        reject(() -> new TerminalFacts(TerminalState.FAILED,2,false,false,true,AbsenceCoverage.NOT_PROVEN));
        reject(() -> record(1,Phase.SUBMISSION_INTENT,true,terminal(TerminalState.SUCCEEDED,0)));
        for (TerminalState state : new TerminalState[]{TerminalState.UNCERTAIN, TerminalState.CANCELLED, TerminalState.TIMED_OUT}) {
            reject(() -> record(2,Phase.PREPARATION_INTENT,true,terminal(state,0)));
            assertEquals(Integer.valueOf(0),roundTrip(record(3,Phase.LAUNCH_INTENT,true,terminal(state,0))).terminal.exitCode);
        }
        // An observed exit does not erase uncertainty about surrounding effects/cleanup.
        assertEquals(Integer.valueOf(0),roundTrip(record(5,Phase.LAUNCH_INTENT,true,terminal(TerminalState.UNCERTAIN,0))).terminal.exitCode);
    }
    @Test public void notStartedDoesNotInferAbsenceFromMissingAuthorizationOrIntent() {
        TerminalFacts unknown = terminal(TerminalState.NOT_STARTED,null);
        assertEquals(AbsenceCoverage.NOT_PROVEN,roundTrip(record(1,Phase.SUBMISSION_INTENT,false,unknown)).terminal.absenceCoverage);
        TerminalFacts explicit = new TerminalFacts(TerminalState.NOT_STARTED,null,false,false,false,AbsenceCoverage.NO_PREPARATION_OR_DISPATCH);
        assertEquals(explicit,roundTrip(record(3,Phase.PREPARATION_INTENT,true,explicit)).terminal);
        reject(() -> terminal(TerminalState.NOT_STARTED,0));
        reject(() -> new TerminalFacts(TerminalState.SUCCEEDED,0,false,false,false,AbsenceCoverage.NO_PREPARATION_OR_DISPATCH));
    }
    @Test public void decoderRejectsUnknownMissingPartialAndLegacyFields() {
        String json = CodingJobEvidenceCodec.encode(record(1,Phase.SUBMISSION_INTENT,false,null));
        rejectJson(json.replace("\"schema\":3","\"schema\":2"));
        rejectJson(json.replace("\"envelopeVersion\":2", "\"envelopeVersion\":1"));
        rejectJson(json.replace("\"inputContext\":", "\"legacyInput\":"));
        rejectJson(json.replace("\"semanticCapture\":\"ENABLED\"", "\"captureMode\":\"CENTRAL_ENABLED\""));
        rejectJson(json.replace("\"schema\":3","\"schema\":3,\"command\":\"unexpected\""));
        rejectJson(json.replace("\"requestId\":\"request\"","\"requestId\":null"));
        rejectJson(json.replace("\"sourceRunId\":\"central-run\",", ""));
        rejectJson(json.replace("CREW_CHECKPOINT","CENTRAL_BATCH"));
        rejectJson(json.replace("SUBMISSION_INTENT","RUNNING"));
    }
    @Test public void decoderRejectsDuplicateKeysIncludingEscapedAliasesAndTrailingData() {
        String json = CodingJobEvidenceCodec.encode(record(1,Phase.SUBMISSION_INTENT,false,null));
        rejectJson(json.replace("\"schema\":3","\"schema\":3,\"schema\":3"));
        rejectJson(json.replace("\"schema\":3","\"schema\":3,\"sch\\u0065ma\":3"));
        rejectJson(json.replace("\"cycleId\":\"cycle\"","\"cycleId\":\"cycle\",\"cycleId\":\"other\""));
        rejectJson(json + "{}"); rejectJson(json.substring(0,json.length()-1));
        rejectJson(json.replace("\"jobId\":", "'jobId':"));
    }
    @Test public void numbersAndBooleansAreExactAndNeverCoerced() {
        String json = CodingJobEvidenceCodec.encode(record(1,Phase.SUBMISSION_INTENT,false,null));
        for (String invalid : new String[]{"1.0","1e0","\"1\"","01","9223372036854775808","null","true"})
            rejectJson(json.replace("\"revision\":1", "\"revision\":"+invalid));
        rejectJson(json.replace("\"submissionCommitted\":false","\"submissionCommitted\":\"false\""));
        String terminal = CodingJobEvidenceCodec.encode(record(2,Phase.LAUNCH_INTENT,true,terminal(TerminalState.FAILED,1)));
        rejectJson(terminal.replace("\"exitCode\":1","\"exitCode\":2147483648"));
    }
    @Test public void parserBudgetNestingAndIdentifierValidationFailClosed() {
        String json = CodingJobEvidenceCodec.encode(record(1,Phase.SUBMISSION_INTENT,false,null));
        reject(() -> CodingJobEvidenceCodec.decode(json,json.length()-1));
        rejectJson("{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":{}}}}}}}");
        rejectJson(json.replace(JOB,"1-1-1-1-1"));
        rejectJson(json.replace(DIGEST,"not-a-digest"));
        rejectJson(json.replace("\"botId\":\"bot\"","\"botId\":\"\\u0000\""));
    }
    @Test public void escapedIdentifiersRoundTripWithoutMutableCollections() {
        CrewSource crew = crew("cycle\\quoted\"é", "child-call");
        CodingJobEvidence.Record r = new CodingJobEvidence.Record(bind(new Source(crew,null,null),SemanticCapture.ENABLED),1,Phase.SUBMISSION_INTENT,false,null,null);
        assertEquals(r,roundTrip(r));
        assertNotEquals(central("call1"),central("call2"));
    }
    @Test public void identifierControlsAreRejectedWithoutInventingAnOpaqueIdLengthCap() {
        for (String control : new String[]{"\n", "\r", "\t", "\u007f", "\u0085"}) {
            reject(() -> crew("cycle"+control,"call"));
            reject(() -> central("call"+control));
        }
        StringBuilder longId = new StringBuilder();
        for (int i=0; i<5000; i++) longId.append('x');
        assertEquals(longId.toString(),central(longId.toString()).sourceCallId);
    }

}
