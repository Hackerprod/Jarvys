package com.jarvys.agent;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.jarvys.agent.EffectInvocation.*;

public class EffectReceiptSemanticsTest {
    private EffectInvocation invocation(boolean admit) {
        EffectInvocation context = new EffectInvocation(value -> {});
        if (admit) context.admit(new Admission(Authorization.ALLOW, "native-central", "delegate", "1", "test"));
        return context;
    }
    private EffectReference assignment() { return new EffectReference(EffectReferenceKind.ASSIGNMENT, "workspace-1", "assignment-1"); }
    @Test public void normalizedCoordinationPreservesOwnerBoundMeaningAndReferenceThroughCopies() {
        EffectInvocation context = invocation(true);
        CoreToolResult result = CoreToolResult.success("accepted").withEffectReceipt(
                context.receipt(Outcome.OK, CompletionSemantics.COORDINATION_ACCEPTED, assignment())).withContent("bounded");
        Receipt normalized = context.resolve(result, true);
        assertEquals(Outcome.OK, normalized.outcome);
        assertEquals(CompletionSemantics.COORDINATION_ACCEPTED, normalized.completionSemantics);
        assertEquals(assignment(), normalized.reference);
        assertSame(result.effectReceipt, result.withContent("another copy").effectReceipt);
    }
    @Test public void foreignOwnerWithSameAdmissionCannotForgeLocalCompletion() {
        EffectInvocation producer = invocation(true), other = invocation(true);
        Receipt receipt = other.resolve(CoreToolResult.success("accepted").withEffectReceipt(
                producer.receipt(Outcome.OK, CompletionSemantics.COORDINATION_ACCEPTED, assignment())), true);
        assertEquals(Outcome.UNCERTAIN, receipt.outcome);
        assertEquals(CompletionSemantics.EXTERNAL_EFFECT, receipt.completionSemantics);
        assertNull(receipt.reference);
    }
    @Test public void failedResultCannotConfirmCoordinationEvenWithOwnedReceipt() {
        EffectInvocation context = invocation(true);
        Receipt receipt = context.resolve(CoreToolResult.failure("failed").withEffectReceipt(
                context.receipt(Outcome.OK, CompletionSemantics.COORDINATION_ACCEPTED, assignment())), true);
        assertEquals(Outcome.UNCERTAIN, receipt.outcome);
        assertEquals(CompletionSemantics.EXTERNAL_EFFECT, receipt.completionSemantics);
        assertNull(receipt.reference);
    }
    @Test public void existingFastPathIsAdministrativeSuccessWithoutAdmissionOrNewExecution() {
        EffectInvocation context = invocation(false);
        Receipt receipt = context.resolve(CoreToolResult.success("original receipt").withEffectReceipt(
                context.receipt(Outcome.NOT_EXECUTED, CompletionSemantics.EXISTING_REFERENCE, assignment())), true);
        assertFalse(context.admitted());
        assertEquals(Outcome.NOT_EXECUTED, receipt.outcome);
        assertEquals(CompletionSemantics.EXISTING_REFERENCE, receipt.completionSemantics);
        assertEquals(assignment(), receipt.reference);
    }
    @Test public void duplicateDiscoveredAfterAdmissionRemainsUncertainWithOriginalReference() {
        EffectInvocation context = invocation(true);
        assertThrows(IllegalArgumentException.class, () -> context.receipt(Outcome.NOT_EXECUTED,
                CompletionSemantics.EXISTING_REFERENCE, assignment()));
        Receipt receipt = context.resolve(CoreToolResult.success("original receipt").withEffectReceipt(
                context.receipt(Outcome.UNCERTAIN, CompletionSemantics.EXISTING_REFERENCE, assignment())), true);
        assertTrue(context.admitted());
        assertEquals(Outcome.UNCERTAIN, receipt.outcome);
        assertEquals(CompletionSemantics.EXISTING_REFERENCE, receipt.completionSemantics);
        assertEquals(assignment(), receipt.reference);
    }
    @Test public void impossibleOutcomeReferenceAndAdmissionCombinationsAreRejected() {
        EffectInvocation absent = invocation(false), admitted = invocation(true);
        assertThrows(IllegalArgumentException.class, () -> absent.receipt(Outcome.OK, CompletionSemantics.COORDINATION_ACCEPTED, assignment()));
        assertThrows(IllegalArgumentException.class, () -> admitted.receipt(Outcome.OK, CompletionSemantics.EXISTING_REFERENCE, assignment()));
        assertThrows(IllegalArgumentException.class, () -> admitted.receipt(Outcome.OK, CompletionSemantics.LOCAL_RESOURCE_CREATED, assignment()));
        assertThrows(IllegalArgumentException.class, () -> admitted.receipt(Outcome.OK, CompletionSemantics.LOCAL_DELIVERY_CONFIRMED, assignment()));
        assertThrows(IllegalArgumentException.class, () -> admitted.receipt(Outcome.OK, CompletionSemantics.COORDINATION_ACCEPTED, null));
    }
    @Test public void resourceAndDeliveryHaveTheirOwnConfirmedSemantics() {
        EffectInvocation context = invocation(true);
        for (CompletionSemantics semantics : new CompletionSemantics[]{CompletionSemantics.LOCAL_RESOURCE_CREATED, CompletionSemantics.LOCAL_DELIVERY_CONFIRMED}) {
            EffectReference ref = new EffectReference(semantics == CompletionSemantics.LOCAL_RESOURCE_CREATED
                    ? EffectReferenceKind.WORKSPACE : EffectReferenceKind.DELIVERY, "central", "local-id");
            Receipt receipt = context.resolve(CoreToolResult.success("local").withEffectReceipt(context.receipt(Outcome.OK, semantics, ref)), true);
            assertEquals(semantics, receipt.completionSemantics);
            assertEquals(ref, receipt.reference);
        }
    }
    @Test public void legacyDefaultAndUnboundReceiptsRetainConservativeBehavior() {
        EffectInvocation context = invocation(true);
        assertEquals(CompletionSemantics.EXTERNAL_EFFECT, context.receipt(Outcome.OK).completionSemantics);
        Receipt normalized = context.resolve(CoreToolResult.success("legacy").withEffectReceipt(new Receipt(Outcome.OK, context.admission())), true);
        assertEquals(Outcome.UNCERTAIN, normalized.outcome);
        assertNull(normalized.reference);
    }
    @Test public void referencesRejectPathsAndFreeTextAndHaveValueEquality() {
        assertEquals(assignment(), assignment());
        assertEquals(assignment().hashCode(), assignment().hashCode());
        assertThrows(IllegalArgumentException.class, () -> new EffectReference(EffectReferenceKind.ASSIGNMENT, "/private/path", "id"));
        assertThrows(IllegalArgumentException.class, () -> new EffectReference(EffectReferenceKind.ASSIGNMENT, "scope", "free text"));
    }
    @Test public void referenceMetadataHasABoundedIdentifierBudget() {
        assertEquals(512, new EffectReference(EffectReferenceKind.WORKSPACE, "x".repeat(512), "id").scopeId.length());
        assertThrows(IllegalArgumentException.class, () -> new EffectReference(EffectReferenceKind.WORKSPACE, "x".repeat(513), "id"));
        assertThrows(IllegalArgumentException.class, () -> new EffectReference(EffectReferenceKind.DELIVERY, "central", "x".repeat(513)));
    }

}
