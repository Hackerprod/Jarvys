package com.jarvys.agent.coding;

import java.util.Objects;

/** Operational display facts, separate from evidence phases. No process or durability proof. */
public final class CodingJobSnapshot {
    public enum State { LAUNCH_INTENT, RUNNING, CANCELLING, SUCCEEDED, FAILED, NOT_STARTED, CANCELLED, TIMED_OUT, INTERRUPTED, UNCERTAIN }
    public final String id, owner, scopeId, cwd, redactedCommand, projectRoot, conversationId, logArtifact, error;
    public final State state;
    public final long createdAt, logBytes;
    public final Long startedAt, finishedAt;
    public final Integer exitCode;
    public final boolean logComplete;
    public CodingJobSnapshot(String id, String owner, String scopeId, String cwd, String command,
            String projectRoot, String conversationId, State state, long createdAt, Long startedAt, Long finishedAt,
            Integer exitCode, String logArtifact, String error, long logBytes, boolean logComplete) {
        this.id = CodingJobEvidence.uuid(id); this.owner = CodingJobEvidence.id(owner); this.scopeId = CodingJobEvidence.id(scopeId);
        this.cwd = Objects.requireNonNull(cwd); redactedCommand = Objects.requireNonNull(command);
        this.projectRoot = CodingJobEvidence.id(projectRoot); this.conversationId = CodingJobEvidence.id(conversationId);
        this.state = Objects.requireNonNull(state); this.createdAt = createdAt; this.startedAt = startedAt;
        this.finishedAt = finishedAt; this.exitCode = exitCode; this.logArtifact = CodingJobEvidence.id(logArtifact);
        this.error = error; this.logBytes = logBytes; this.logComplete = logComplete;
        if (logBytes < 0) throw new IllegalArgumentException("Negative log size");
    }
    public void requireMatches(CodingJobEvidence.Record record) {
        CodingJobEvidence.Binding b = record.binding;
        if (!id.equals(b.jobId) || !owner.equals(b.owner) || !scopeId.equals(b.scopeId)
                || !conversationId.equals(b.source.crew.executionSessionId) || !logArtifact.equals("coding-job:"+id+"/log"))
            throw new IllegalArgumentException("Operational snapshot does not match evidence binding");
        boolean terminal = state != State.LAUNCH_INTENT && state != State.RUNNING && state != State.CANCELLING;
        if (terminal != (record.terminal != null)) throw new IllegalArgumentException("Operational and evidence terminal facts differ");
        if (terminal && (!state.name().equals(record.terminal.state.name()) || !Objects.equals(exitCode,record.terminal.exitCode)))
            throw new IllegalArgumentException("Operational terminal result differs from evidence");
        if (!terminal && (exitCode != null || finishedAt != null)) throw new IllegalArgumentException("Nonterminal snapshot has terminal facts");
        if (state == State.RUNNING && record.highestEffectPhase != CodingJobEvidence.Phase.LAUNCH_INTENT)
            throw new IllegalArgumentException("RUNNING is only a launch intent, not a process witness");
        if (state == State.LAUNCH_INTENT && record.highestEffectPhase == CodingJobEvidence.Phase.LAUNCH_INTENT)
            throw new IllegalArgumentException("Operational launch display must agree with the persisted phase");
        if (startedAt != null && record.highestEffectPhase != CodingJobEvidence.Phase.LAUNCH_INTENT)
            throw new IllegalArgumentException("Started timestamp without launch intent");
    }
}
