package com.jarvys.agent.workspaces;

import com.jarvys.agent.CoreToolResult;
import com.jarvys.agent.EffectInvocation;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceMutationContextTest {
    private WorkspaceMutationContext context(String action, EffectInvocation invocation) {
        return new WorkspaceMutationContext(action, "create".equals(action) ? null : "workspace-1",
                null, invocation, () -> {});
    }
    @Test public void confirmedAssignmentIsAcceptanceNotGoalAndRequiresAdmission() {
        EffectInvocation invocation = new EffectInvocation(a -> {});
        WorkspaceMutationContext context = context("delegate", invocation);
        assertThrows(IllegalArgumentException.class, () -> context.acceptedAssignment("workspace-1", "assignment-1"));
        context.admitMutation();
        context.acceptedAssignment("workspace-1", "assignment-1");
        EffectInvocation.Receipt receipt = context.finish(CoreToolResult.success("display")).effectReceipt;
        assertEquals(EffectInvocation.Outcome.OK, receipt.outcome);
        assertEquals(EffectInvocation.CompletionSemantics.COORDINATION_ACCEPTED, receipt.completionSemantics);
        assertEquals("assignment-1", receipt.reference.id);
    }
    @Test public void failedWriterPreventsProducerAdmission() {
        EffectInvocation invocation = new EffectInvocation(a -> { throw new IllegalStateException("storage unavailable"); });
        WorkspaceMutationContext context = context("deliver", invocation);
        assertThrows(IllegalStateException.class, context::admitMutation);
        assertFalse(invocation.admitted());
        assertEquals(EffectInvocation.Outcome.NOT_EXECUTED,
                context.finish(CoreToolResult.failure("not dispatched")).effectReceipt.outcome);
    }
    @Test public void pureGuardRunsBeforeWriterAndAgainAfterWriter() {
        int[] guard = {0};
        EffectInvocation invocation = new EffectInvocation(a -> assertEquals(1, guard[0]));
        WorkspaceMutationContext context = new WorkspaceMutationContext("message", "workspace-1", null,
                invocation, () -> guard[0]++);
        context.admitMutation();
        assertEquals(2, guard[0]);
    }
    @Test public void duplicateBeforeAndAfterAdmissionAreDifferentConservativeOutcomes() {
        EffectInvocation first = new EffectInvocation(a -> {});
        WorkspaceMutationContext fast = context("create", first);
        fast.existing(EffectInvocation.EffectReferenceKind.WORKSPACE, "workspace-1", "workspace-1");
        assertEquals(EffectInvocation.Outcome.NOT_EXECUTED, fast.finish(CoreToolResult.success("existing")).effectReceipt.outcome);
        assertFalse(first.admitted());
        EffectInvocation second = new EffectInvocation(a -> {});
        WorkspaceMutationContext raced = context("create", second);
        raced.admitMutation();
        raced.existing(EffectInvocation.EffectReferenceKind.WORKSPACE, "workspace-1", "workspace-1");
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, raced.finish(CoreToolResult.success("existing")).effectReceipt.outcome);
        assertTrue(second.admitted());
    }
    @Test public void differentOperationOrScopeCannotReuseCompletion() {
        WorkspaceMutationContext context = context("delegate", new EffectInvocation(a -> {}));
        context.admitMutation();
        assertThrows(IllegalArgumentException.class, () -> context.delivered("workspace-1", "delivery-1"));
        assertThrows(IllegalArgumentException.class, () -> context.acceptedAssignment("workspace-2", "assignment-1"));
        context.acceptedAssignment("workspace-1", "assignment-1");
        assertThrows(IllegalStateException.class, () -> context.acceptedAssignment("workspace-1", "assignment-2"));
    }
    @Test public void postCommitFailureCannotBecomeConfirmedOrNotExecuted() {
        WorkspaceMutationContext context = context("create", new EffectInvocation(a -> {}));
        context.admitMutation();
        context.createdWorkspace("workspace-1");
        EffectInvocation.Receipt receipt = context.finish(CoreToolResult.failure("later failure")).effectReceipt;
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, receipt.outcome);
        assertEquals(EffectInvocation.CompletionSemantics.EXTERNAL_EFFECT, receipt.completionSemantics);
        assertNull(receipt.reference);
    }
    @Test public void legacySuccessfulBodyWithoutProducerProofRemainsUncertain() {
        WorkspaceMutationContext context = context("message", new EffectInvocation(a -> {}));
        context.admitMutation();
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, context.finish(CoreToolResult.success("accepted text")).effectReceipt.outcome);
    }
    @Test public void createCannotChangeResourceIdentityBetweenScopeAndReference() {
        WorkspaceMutationContext context = context("create", new EffectInvocation(a -> {}));
        assertThrows(IllegalArgumentException.class, () -> context.existing(
                EffectInvocation.EffectReferenceKind.WORKSPACE, "workspace-1", "workspace-2"));
    }
    @Test public void returnedContextCannotAdmitOrReplaceCompletion() {
        EffectInvocation invocation = new EffectInvocation(a -> {});
        WorkspaceMutationContext context = context("deliver", invocation);
        context.admitMutation();
        context.delivered("workspace-1", "delivery-1");
        context.close();
        assertThrows(IllegalStateException.class, context::admitMutation);
        assertThrows(IllegalStateException.class, () -> context.delivered("workspace-1", "delivery-1"));
        assertEquals(EffectInvocation.Outcome.OK, context.finish(CoreToolResult.success("saved")).effectReceipt.outcome);
    }

}
