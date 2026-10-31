package com.jarvys.agent.workspaces;

import com.jarvys.agent.CentralInvocationSource;
import com.jarvys.agent.CentralInvocationWitness;
import com.jarvys.agent.CoreToolResult;
import com.jarvys.agent.EffectInvocation;
import java.util.Objects;

/** One synchronous native operation. Producer confirmations are local metadata, never Room IO. */
public final class WorkspaceMutationContext {
    public final String action;
    public final String workspaceId;
    public final CentralInvocationSource rootSource;
    public final com.jarvys.agent.CentralTreeAdmission treeAdmission;
    private final com.jarvys.agent.CancellationToken treeToken;
    private final EffectInvocation invocation;
    private final CentralInvocationWitness sourceWitness;
    private final Runnable checkAccess;
    private final EffectInvocation.Admission decision;
    private EffectInvocation.Receipt completion;
    private volatile boolean closed;

    WorkspaceMutationContext(String action, String workspaceId, CentralInvocationWitness sourceWitness,
            EffectInvocation invocation, Runnable checkAccess) {
        this(action,workspaceId,sourceWitness,invocation,checkAccess,null,null);
    }
    WorkspaceMutationContext(String action,String workspaceId,CentralInvocationWitness sourceWitness,
            EffectInvocation invocation,Runnable checkAccess,com.jarvys.agent.CentralTreeAdmission treeAdmission,
            com.jarvys.agent.CancellationToken treeToken) {
        this.treeAdmission=treeAdmission;this.treeToken=treeToken;
        this.action = Objects.requireNonNull(action);
        this.workspaceId = workspaceId;
        this.sourceWitness = sourceWitness;
        this.rootSource = sourceWitness == null ? null : sourceWitness.source();
        this.invocation = Objects.requireNonNull(invocation);
        if (invocation.centralSource() != sourceWitness)
            throw new IllegalArgumentException("Workspace source belongs to another invocation");
        this.checkAccess = Objects.requireNonNull(checkAccess);
        expectedKind(); // Reject read/unknown contexts before any admission.
        if ("continue_result".equals(action) && rootSource == null)
            throw new IllegalArgumentException("Explicit continuation requires a current Central source");
        if (!"create".equals(action) && (workspaceId == null || workspaceId.isEmpty()))
            throw new IllegalArgumentException("An exact workspace scope is required");
        decision = new EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
                "native_contract", "workspaces:" + action + ":" + (workspaceId == null ? "central" : workspaceId),
                null, "central_workspace");
    }

    public void requireTreeAdmission() {
        if(closed || treeAdmission==null || treeToken==null || sourceWitness==null)
            throw new IllegalStateException("Fresh native Central tree authority required");
        treeAdmission.requireFreshDelegation(treeToken);sourceWitness.requireInvocationCurrent();
        if(!rootSource.ownerCentralSessionId.equals(treeAdmission.ownerCentralSessionId()) ||
            !rootSource.storageGeneration.equals(treeAdmission.storageGeneration()) ||
            !rootSource.sourceMessageId.equals(treeAdmission.originUserMessageId()))
            throw new IllegalStateException("Original tree request source changed");
    }

    /** Only the assignment/source insertion: no bridge, spawn, listeners or model work in this lease. */
    public <T> T withCurrentWorkspaceTransaction(java.util.concurrent.Callable<T> transaction) throws Exception {
        if (closed) throw new IllegalStateException("Workspace operation has returned");
        return sourceWitness == null ? transaction.call() : sourceWitness.withCurrentWorkspaceTransaction(transaction);
    }
    public void requireInsideCurrentLease() {
        if (sourceWitness != null) sourceWitness.requireInsideCurrentLease();
    }

    /** Must be called outside producer SQLite/Crew/STOP locks, then access rechecked under its lock. */
    public void admitMutation() {
        if (closed) throw new IllegalStateException("Workspace operation has returned");
        checkAccess.run();
        invocation.admit(decision);
        checkAccess.run();
    }

    public void createdWorkspace(String id) {
        confirm(EffectInvocation.CompletionSemantics.LOCAL_RESOURCE_CREATED,
                EffectInvocation.EffectReferenceKind.WORKSPACE, id, id);
    }
    public void acceptedAssignment(String workspace, String id) {
        confirm(EffectInvocation.CompletionSemantics.COORDINATION_ACCEPTED,
                EffectInvocation.EffectReferenceKind.ASSIGNMENT, workspace, id);
    }
    public void acceptedInput(String workspace, String id) {
        confirm(EffectInvocation.CompletionSemantics.COORDINATION_ACCEPTED,
                EffectInvocation.EffectReferenceKind.WORKSPACE_INPUT, workspace, id);
    }
    public void delivered(String workspace, String id) {
        confirm(EffectInvocation.CompletionSemantics.LOCAL_DELIVERY_CONFIRMED,
                EffectInvocation.EffectReferenceKind.DELIVERY, workspace, id);
    }
    /** Before admission requires no new action; after admission confirms only a known reference, never no-effect. */
    public synchronized void existing(EffectInvocation.EffectReferenceKind kind, String scope, String id) {
        validateReference(kind, scope, id);
        store(invocation.receipt(invocation.admitted() ? EffectInvocation.Outcome.UNCERTAIN
                : EffectInvocation.Outcome.NOT_EXECUTED,
                EffectInvocation.CompletionSemantics.EXISTING_REFERENCE,
                new EffectInvocation.EffectReference(kind, scope, id)));
    }
    private synchronized void confirm(EffectInvocation.CompletionSemantics semantics,
            EffectInvocation.EffectReferenceKind kind, String scope, String id) {
        validateReference(kind, scope, id);
        store(invocation.receipt(EffectInvocation.Outcome.OK, semantics,
                new EffectInvocation.EffectReference(kind, scope, id)));
    }
    private void validateReference(EffectInvocation.EffectReferenceKind kind, String scope, String id) {
        if (kind != expectedKind() || (workspaceId != null && !workspaceId.equals(scope))
                || ("create".equals(action) && !Objects.equals(scope, id)))
            throw new IllegalArgumentException("Completion does not match this workspace operation");
    }
    private EffectInvocation.EffectReferenceKind expectedKind() {
        switch (action) {
            case "create": return EffectInvocation.EffectReferenceKind.WORKSPACE;
            case "cancel": case "delegate": return EffectInvocation.EffectReferenceKind.ASSIGNMENT;
            case "reply": case "message": case "continue_result": return EffectInvocation.EffectReferenceKind.WORKSPACE_INPUT;
            case "deliver": return EffectInvocation.EffectReferenceKind.DELIVERY;
            default: throw new IllegalArgumentException("Not a workspace mutation");
        }
    }
    private void store(EffectInvocation.Receipt receipt) {
        if (closed) throw new IllegalStateException("Workspace operation has returned");
        if (completion != null && (completion.outcome != receipt.outcome
                || completion.completionSemantics != receipt.completionSemantics
                || !Objects.equals(completion.reference, receipt.reference)))
            throw new IllegalStateException("Workspace completion cannot be replaced");
        completion = receipt;
    }
    synchronized void close() { closed = true; }
    /** Does not seal the shared invocation; Registry owns terminalization. */
    synchronized CoreToolResult finish(CoreToolResult result) {
        CoreToolResult candidate = completion != null ? result.withEffectReceipt(completion)
                : !invocation.admitted() ? result.withEffectReceipt(invocation.receipt(EffectInvocation.Outcome.NOT_EXECUTED))
                : result;
        return candidate.withEffectReceipt(invocation.resolve(candidate, true));
    }
}
