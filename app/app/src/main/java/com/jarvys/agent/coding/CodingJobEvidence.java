package com.jarvys.agent.coding;

import com.jarvys.agent.CentralInvocationSource;
import com.jarvys.agent.CrewInputContext;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure schema-3 / envelope-version-2 evidence metadata. No execution, storage, memory opt-in or authorization.
 * Neither a Record nor a ValidatedRecord proves fsync, attribution, process liveness or effects.
 * Durable journal witnesses must be minted separately by the eventual IO producer. In particular
 * submissionCommitted is a reported fact, never a receipt; terminal SUCCEEDED describes exit zero,
 * never a successful build, mission or user goal. No mapping to synchronous EffectInvocation exists.
 * Opaque identifiers have no invented global length/format cap. Constructors retain fixed fields;
 * the decoder requires a caller budget, and the future source provider enforces its existing ID
 * constraints and exact checkpoint bindings. Valid syntax and hashes do not establish provenance.
 */
public final class CodingJobEvidence {
    private CodingJobEvidence() { }
    public enum SourceKind { CREW_CHECKPOINT }
    /**
     * Immutable policy observed at submission, not live eligibility or a narration permit.
     * ENABLED alone authorizes nothing: future semantic writes must recheck memory-off, reset and
     * forget fences. DISABLED is not eligible for retrospective backfill. Neither value suppresses
     * required technical audit; the captured value cannot change across producer revisions.
     */
    public enum SemanticCapture { ENABLED, DISABLED }
    public enum Phase { SUBMISSION_INTENT, PREPARATION_INTENT, LAUNCH_INTENT }
    public enum Authorization { ALLOW, ASK_APPROVED }
    public enum TerminalState { SUCCEEDED, FAILED, NOT_STARTED, CANCELLED, TIMED_OUT, INTERRUPTED, UNCERTAIN }
    public enum AbsenceCoverage { NOT_PROVEN, NO_PREPARATION_OR_DISPATCH }
    public enum Advance { NEXT, IDEMPOTENT }

    public abstract static class Value {
        abstract List<?> values();
        @Override public final boolean equals(Object other) {
            return other != null && getClass() == other.getClass() && values().equals(((Value) other).values());
        }
        @Override public final int hashCode() { return Objects.hash(getClass(), values()); }
    }

    public static final class CrewSource extends Value {
        public final SourceKind kind = SourceKind.CREW_CHECKPOINT;
        public final String executionSessionId, botId, missionId, cycleId,
                scopeIdentity, checkpointPayloadSha256, callId, toolName;
        public final CrewInputContext inputContext;
        public CrewSource(String session, String bot, String mission, String cycle,
                String scope, String digest, String call, String tool, CrewInputContext inputContext) {
            executionSessionId = id(session); botId = id(bot);
            missionId = id(mission); cycleId = id(cycle); scopeIdentity = id(scope);
            if (digest == null || !digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid checkpoint digest");
            checkpointPayloadSha256 = digest; callId = id(call); toolName = id(tool);
            this.inputContext = Objects.requireNonNull(inputContext);
            for (CrewInputContext.Reference reference : inputContext.references) {
                if (!executionSessionId.equals(reference.scope.executionSessionId))
                    throw new IllegalArgumentException("Input context belongs to a different execution session");
            }
        }
        @Override List<?> values() { return Arrays.asList(kind, executionSessionId,
                botId, missionId, cycleId, scopeIdentity, checkpointPayloadSha256, callId, toolName, inputContext); }
    }
    public static final class WorkspaceBinding extends Value {
        public final String workspaceId, assignmentId, requestId;
        public WorkspaceBinding(String workspace, String assignment, String request) {
            workspaceId = id(workspace); assignmentId = id(assignment); requestId = id(request);
        }
        @Override List<?> values() { return Arrays.asList(workspaceId, assignmentId, requestId); }
    }
    public static final class Source extends Value {
        public final CrewSource crew;
        public final WorkspaceBinding workspace;
        /** Historical root attribution; presence does not prove current Room/source eligibility. */
        public final CentralInvocationSource rootCentral;
        public Source(CrewSource crew, WorkspaceBinding workspace, CentralInvocationSource rootCentral) {
            this.crew = Objects.requireNonNull(crew); this.workspace = workspace; this.rootCentral = rootCentral;
            if (rootCentral != null && workspace == null)
                throw new IllegalArgumentException("Historical Central root requires its workspace binding");
        }
        @Override List<?> values() { return Arrays.asList(crew, workspace, rootCentral); }
    }
    public static final class Binding extends Value {
        /** Future technical Room reservation identity, a value only: no reservation/proof is minted here. */
        public final String technicalStoreGeneration;
        public final String jobId, submissionId, owner, scopeId, durableScopeIdentity,
                ownerProcessId, managerInstanceId;
        public final Source source;
        public final SemanticCapture semanticCapture;
        public Binding(String technicalStoreGeneration, String job, String submission, String owner, String scopeId, String scopeIdentity,
                String process, String manager, Source source, SemanticCapture semanticCapture) {
            this.technicalStoreGeneration = uuid(technicalStoreGeneration);
            jobId = uuid(job); submissionId = uuid(submission); this.owner = id(owner);
            this.scopeId = id(scopeId); durableScopeIdentity = id(scopeIdentity);
            ownerProcessId = uuid(process); managerInstanceId = uuid(manager);
            this.source = Objects.requireNonNull(source); this.semanticCapture = Objects.requireNonNull(semanticCapture);
            if (!durableScopeIdentity.equals(source.crew.scopeIdentity))
                throw new IllegalArgumentException("Scope identity mismatch");
        }
        @Override List<?> values() { return Arrays.asList(technicalStoreGeneration, jobId, submissionId, owner, scopeId,
                durableScopeIdentity, ownerProcessId, managerInstanceId, source, semanticCapture); }
    }
    public static final class AuthorizationSnapshot extends Value {
        public final Authorization authorization;
        public final String authority, actionId, revision, origin;
        public AuthorizationSnapshot(Authorization authorization, String authority, String action,
                String revision, String origin) {
            this.authorization = Objects.requireNonNull(authorization); this.authority = id(authority);
            actionId = id(action); this.revision = id(revision); this.origin = id(origin);
        }
        @Override List<?> values() { return Arrays.asList(authorization, authority, actionId, revision, origin); }
    }
    public static final class TerminalFacts extends Value {
        public final TerminalState state;
        public final Integer exitCode;
        public final boolean cancellationObserved, timeoutObserved, uncertaintyObserved;
        public final AbsenceCoverage absenceCoverage;
        public TerminalFacts(TerminalState state, Integer exitCode, boolean cancelled, boolean timedOut,
                boolean uncertain, AbsenceCoverage coverage) {
            this.state = Objects.requireNonNull(state); this.exitCode = exitCode;
            cancellationObserved = cancelled; timeoutObserved = timedOut; uncertaintyObserved = uncertain;
            absenceCoverage = Objects.requireNonNull(coverage);
            if (state == TerminalState.SUCCEEDED && (exitCode == null || exitCode != 0 || cancelled || timedOut || uncertain))
                throw new IllegalArgumentException("Contradictory successful process facts");
            if (state == TerminalState.FAILED && (exitCode == null || exitCode == 0 || cancelled || timedOut || uncertain))
                throw new IllegalArgumentException("Failed process requires unambiguous nonzero exit");
            if (state == TerminalState.NOT_STARTED && exitCode != null)
                throw new IllegalArgumentException("An unstarted process cannot have an exit code");
            if (coverage == AbsenceCoverage.NO_PREPARATION_OR_DISPATCH && state != TerminalState.NOT_STARTED)
                throw new IllegalArgumentException("Absence coverage belongs to an unstarted process");
            if (state == TerminalState.CANCELLED && !cancelled || state == TerminalState.TIMED_OUT && !timedOut)
                throw new IllegalArgumentException("Missing terminal cause fact");
        }
        @Override List<?> values() { return Arrays.asList(state, exitCode, cancellationObserved,
                timeoutObserved, uncertaintyObserved, absenceCoverage); }
    }
    public static final class Record extends Value {
        public final Binding binding;
        public final long revision;
        public final Phase highestEffectPhase;
        public final boolean submissionCommitted;
        public final AuthorizationSnapshot authorization;
        public final TerminalFacts terminal;
        public Record(Binding binding, long revision, Phase phase, boolean submitted,
                AuthorizationSnapshot authorization, TerminalFacts terminal) {
            this.binding = Objects.requireNonNull(binding);
            if (revision <= 0) throw new IllegalArgumentException("Journal revision must be positive");
            this.revision = revision; highestEffectPhase = Objects.requireNonNull(phase);
            submissionCommitted = submitted; this.authorization = authorization; this.terminal = terminal;
            if (phase != Phase.SUBMISSION_INTENT && (!submitted || authorization == null))
                throw new IllegalArgumentException("Preparation/launch intent requires accepted submission and authorization metadata");
            if (terminal != null && terminal.exitCode != null && phase != Phase.LAUNCH_INTENT)
                throw new IllegalArgumentException("Process exit requires launch intent");
        }
        @Override List<?> values() { return Arrays.asList(binding, revision, highestEffectPhase,
                submissionCommitted, authorization, terminal); }
    }
    /** Syntactically and structurally validated only; no storage/provenance witness. */
    public static final class ValidatedRecord {
        public final Record record;
        ValidatedRecord(Record record) { this.record = Objects.requireNonNull(record); }
    }
    /**
     * Producer transition validation only. Caller supplies its exact expected record and must still
     * perform the durable CAS. Revisions advance exactly one, with no skipped preparation phase.
     * This is NOT a recovery/projection comparator: a lagging projector may observe N to N+3 and
     * needs separate monotonic snapshot checks; rejection here proves neither absence nor replay.
     */
    public static Advance validateAdvance(Record expected, Record next) {
        Objects.requireNonNull(expected); Objects.requireNonNull(next);
        if (expected.equals(next)) return Advance.IDEMPOTENT;
        if (!expected.binding.equals(next.binding)) throw new IllegalArgumentException("Job binding changed");
        if (expected.terminal != null) throw new IllegalArgumentException("Terminal record is immutable");
        if (expected.revision == Long.MAX_VALUE || next.revision != expected.revision + 1)
            throw new IllegalArgumentException("Expected exact next journal revision");
        if (next.highestEffectPhase.ordinal() < expected.highestEffectPhase.ordinal()
                || next.highestEffectPhase.ordinal() > expected.highestEffectPhase.ordinal() + 1
                || expected.submissionCommitted && !next.submissionCommitted
                || expected.authorization != null && !expected.authorization.equals(next.authorization))
            throw new IllegalArgumentException("Journal facts cannot regress or replace authorization");
        return Advance.NEXT;
    }
    static String id(String value) {
        Objects.requireNonNull(value, "Identifier");
        if (value.trim().isEmpty() || value.equals("null") || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid identifier");
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("Control character in identifier");
        }
        return value;
    }
    static String uuid(String value) {
        id(value);
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid canonical UUID"); }
        return value;
    }
}
