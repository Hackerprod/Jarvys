package com.jarvys.agent.coding;

import com.jarvys.agent.CrewInvocationSource;
import com.jarvys.agent.CrewInvocationWitness;
import com.jarvys.agent.EffectInvocation;
import java.lang.ref.WeakReference;
import java.util.Objects;

/** A live capture, not a decoded source DTO. Durable holders retain only metadata and an opaque nonce. */
public final class CodingJobSubmission {
    private final String jobId, submissionId, owner, scopeId, durableScopeIdentity, ownerProcessId, managerInstanceId;
    private final CrewInvocationSource crew;
    private final Object ownerNonce;
    private final CodingJobEvidence.SemanticCapture semanticCapture;
    private final CodingJobJournalStore journal;
    private final WeakReference<CrewInvocationWitness> pendingCapture;
    private CodingJobSubmission(EffectInvocation invocation, String job, String submission, String owner,
            String scope, String identity, String process, String manager,
            CodingJobEvidence.SemanticCapture semanticCapture, CodingJobJournalStore journal) {
        CrewInvocationWitness witness = Objects.requireNonNull(invocation.crewSource(), "A real Crew invocation source is required");
        witness.requireInvocationCurrent();
        crew = witness.source();
        if (!"project_exec".equals(crew.toolName)) throw new IllegalArgumentException("Only the exact project execution call may submit a job");
        jobId = CodingJobEvidence.uuid(job); submissionId = CodingJobEvidence.uuid(submission);
        this.owner = CodingJobEvidence.id(owner); scopeId = CodingJobEvidence.id(scope);
        durableScopeIdentity = CodingJobEvidence.id(identity);
        ownerProcessId = CodingJobEvidence.uuid(process); managerInstanceId = CodingJobEvidence.uuid(manager);
        if (!crew.scopeIdentity.equals(identity) || !owner.startsWith(crew.executionSessionId + "/" + crew.botId + "/"))
            throw new IllegalArgumentException("Submission scope or owner does not match the live Crew source");
        this.semanticCapture = Objects.requireNonNull(semanticCapture); this.journal = Objects.requireNonNull(journal);
        ownerNonce = invocation.asyncSubmissionOwnerIdentity();
        pendingCapture = new WeakReference<>(witness);
    }
    public static CodingJobSubmission capture(EffectInvocation invocation, String job, String submission, String owner,
            String scope, String identity, String process, String manager,
            CodingJobEvidence.SemanticCapture semanticCapture, CodingJobJournalStore journal) {
        return new CodingJobSubmission(Objects.requireNonNull(invocation),job,submission,owner,scope,identity,process,manager,semanticCapture,journal);
    }
    public void requireCaptureCurrent() {
        CrewInvocationWitness witness = pendingCapture.get();
        if (witness == null || witness.source() != crew) throw new IllegalStateException("Submission capture is no longer live");
        witness.requireInvocationCurrent();
    }
    /** Only after the reserve transaction committed. No worker retains a live guard or invocation. */
    public void captureConfirmed() { pendingCapture.clear(); }
    public void requireFor(EffectInvocation invocation) {
        if (invocation == null || invocation.asyncSubmissionOwnerIdentity() != ownerNonce)
            throw new IllegalStateException("Submission belongs to a different invocation");
        CrewInvocationWitness witness = invocation.crewSource();
        if (witness == null || witness.source() != crew) throw new IllegalStateException("Submission source was replaced");
        witness.requireInvocationCurrent();
    }
    public String jobId() { return jobId; }
    public String submissionId() { return submissionId; }
    public String owner() { return owner; }
    public String scopeId() { return scopeId; }
    public String durableScopeIdentity() { return durableScopeIdentity; }
    public String ownerProcessId() { return ownerProcessId; }
    public String managerInstanceId() { return managerInstanceId; }
    public CrewInvocationSource crew() { return crew; }
    public CodingJobEvidence.SemanticCapture semanticCapture() { return semanticCapture; }
    public CodingJobJournalStore journal() { return journal; }
}
