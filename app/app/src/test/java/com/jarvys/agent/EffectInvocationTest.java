package com.jarvys.agent;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.jarvys.agent.EffectInvocation.Authorization.*;
import static com.jarvys.agent.EffectInvocation.Outcome.*;

public class EffectInvocationTest {
    private EffectInvocation.Admission admission(EffectInvocation.Authorization auth) {
        return new EffectInvocation.Admission(auth, "test-gate", "action-1", "revision-1", "test");
    }
    @Test public void durableWriterCompletesBeforeAdmissionAndExactRepeatsAreIdempotent() {
        AtomicInteger writes = new AtomicInteger();
        EffectInvocation[] context = new EffectInvocation[1];
        context[0] = new EffectInvocation(value -> {
            assertFalse(context[0].admitted());
            assertEquals(ALLOW, value.authorization);
            writes.incrementAndGet();
        });
        context[0].admit(admission(ALLOW));
        context[0].admit(admission(ALLOW));
        assertTrue(context[0].admitted());
        assertEquals(1, writes.get());
        assertEquals(admission(ALLOW), context[0].admission());
    }
    @Test public void persistenceFailurePreventsDispatchAndAllowsAnExplicitRetryOfPersistence() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        EffectInvocation invocation = new EffectInvocation(value -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("storage failure");
        });
        assertThrows(IllegalStateException.class, () -> { invocation.admit(admission(ALLOW)); effects.incrementAndGet(); });
        assertFalse(invocation.admitted());
        assertEquals(0, effects.get());
        invocation.admit(admission(ALLOW));
        assertTrue(invocation.admitted());
        assertEquals(2, attempts.get());
    }
    @Test public void multipleWritersAllFinishBeforeAdmissionAndFailureNeverDispatches() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger effect = new AtomicInteger();
        EffectInvocation invocation = new EffectInvocation(value -> first.incrementAndGet());
        invocation.addAdmissionWriter(value -> { throw new IllegalStateException("second store unavailable"); });
        assertThrows(IllegalStateException.class, () -> { invocation.admit(admission(ALLOW)); effect.incrementAndGet(); });
        assertEquals(1, first.get());
        assertEquals(0, effect.get());
        assertFalse(invocation.admitted());
    }
    @Test public void writerCannotBeAddedAfterAdmissionOrReentrantly() {
        EffectInvocation[] invocation = new EffectInvocation[1];
        invocation[0] = new EffectInvocation(value -> {
            assertThrows(IllegalStateException.class, () -> invocation[0].addAdmissionWriter(next -> {}));
            assertThrows(IllegalStateException.class, () -> invocation[0].admit(value));
        });
        invocation[0].admit(admission(ALLOW));
        assertThrows(IllegalStateException.class, () -> invocation[0].addAdmissionWriter(value -> {}));
    }
    @Test public void pendingDecisionCanChangeBeforeAdmissionButNeverAfterIt() {
        EffectInvocation invocation = new EffectInvocation(value -> {});
        invocation.noteDecision(admission(ASK_PENDING));
        assertEquals(ASK_PENDING, invocation.resolveFailure(true).admission.authorization);
        invocation.noteDecision(admission(ALLOW));
        invocation.admit(admission(ALLOW));
        assertThrows(IllegalStateException.class, () -> invocation.noteDecision(admission(DENY)));
        assertThrows(IllegalStateException.class, () -> invocation.admit(admission(ASK_APPROVED)));
        assertTrue(invocation.admitted());
        assertEquals(ALLOW, invocation.decision().authorization);
    }
    @Test public void nonAdmittingDecisionsNeverInvokeWriter() {
        AtomicInteger writes = new AtomicInteger();
        EffectInvocation invocation = new EffectInvocation(value -> writes.incrementAndGet());
        for (EffectInvocation.Authorization auth : new EffectInvocation.Authorization[]{DENY, ASK_PENDING, UNSPECIFIED}) {
            invocation.noteDecision(admission(auth));
            assertThrows(IllegalArgumentException.class, () -> invocation.admit(admission(auth)));
            assertEquals(NOT_EXECUTED, invocation.resolveFailure(true).outcome);
            assertEquals(auth, invocation.resolveFailure(true).admission.authorization);
        }
        assertEquals(0, writes.get());
    }
    @Test public void ordinarySuccessIsNeverProofOfAnEffectAndLegacyAbsenceIsUncertain() {
        EffectInvocation invocation = new EffectInvocation(value -> {});
        assertEquals(UNCERTAIN, invocation.resolve(CoreToolResult.success("done"), false).outcome);
        assertEquals(NOT_EXECUTED, invocation.resolve(CoreToolResult.success("pending"), true).outcome);
        invocation.admit(admission(ALLOW));
        assertEquals(UNCERTAIN, invocation.resolve(CoreToolResult.success("done"), true).outcome);
        assertEquals(UNCERTAIN, invocation.resolveFailure(true).outcome);
    }
    @Test public void explicitResultsNeedMatchingAdmissionAndCannotErasePriorEffects() {
        EffectInvocation invocation = new EffectInvocation(value -> {});
        invocation.noteDecision(admission(ALLOW));
        assertEquals(UNCERTAIN, invocation.resolve(CoreToolResult.success("done")
                .withEffectReceipt(invocation.receipt(OK)), true).outcome);
        invocation.admit(admission(ALLOW));
        for (EffectInvocation.Outcome outcome : new EffectInvocation.Outcome[]{OK, ERROR, UNCERTAIN}) {
            assertEquals(outcome, invocation.resolve(CoreToolResult.success("body")
                    .withEffectReceipt(invocation.receipt(outcome)), true).outcome);
        }
        for (EffectInvocation.Outcome outcome : new EffectInvocation.Outcome[]{NOT_EXECUTED, DEFERRED}) {
            assertEquals(UNCERTAIN, invocation.resolve(CoreToolResult.success("body")
                    .withEffectReceipt(invocation.receipt(outcome)), true).outcome);
        }
        assertEquals(UNCERTAIN, invocation.resolve(CoreToolResult.success("body").withEffectReceipt(
                new EffectInvocation.Receipt(OK, admission(ASK_APPROVED))), true).outcome);
        assertTrue(invocation.admitted());
    }
    @Test public void explicitDeferredAndDeniedReceiptCarryObservedDecisionWithoutDispatch() {
        EffectInvocation invocation = new EffectInvocation(value -> {});
        invocation.noteDecision(admission(ASK_APPROVED));
        assertEquals(DEFERRED, invocation.resolve(CoreToolResult.success("wait")
                .withEffectReceipt(invocation.receipt(DEFERRED)), true).outcome);
        invocation.noteDecision(admission(DENY));
        EffectInvocation.Receipt denied = invocation.resolve(CoreToolResult.failure("denied")
                .withEffectReceipt(invocation.receipt(NOT_EXECUTED)), true);
        assertEquals(NOT_EXECUTED, denied.outcome);
        assertEquals(DENY, denied.admission.authorization);
        assertFalse(invocation.admitted());
    }
    @Test public void concurrentCallbacksPersistExactlyOnceWithoutCrossInvocationState() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        EffectInvocation invocation = new EffectInvocation(value -> writes.incrementAndGet());
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Void> admit = () -> { start.await(); invocation.admit(admission(ALLOW)); return null; };
            java.util.concurrent.Future<Void> a = pool.submit(admit), b = pool.submit(admit);
            start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            assertEquals(1, writes.get());
            assertFalse(new EffectInvocation(value -> {}).admitted());
        } finally { pool.shutdownNow(); }
    }
    @Test public void completionReceiptCannotCrossInvocationsWithIdenticalAuthorization() {
        EffectInvocation first = new EffectInvocation(value -> {});
        EffectInvocation second = new EffectInvocation(value -> {});
        first.admit(admission(ALLOW)); second.admit(admission(ALLOW));
        CoreToolResult completed = CoreToolResult.success("done").withEffectReceipt(first.receipt(OK));
        assertEquals(OK, first.resolve(completed, true).outcome);
        assertEquals(UNCERTAIN, second.resolve(completed, true).outcome);
        assertEquals(UNCERTAIN, second.resolve(CoreToolResult.success("done")
                .withEffectReceipt(new EffectInvocation.Receipt(OK, admission(ALLOW))), true).outcome);
    }
    @Test public void failureFlagCannotClaimSuccessfulEffectAndSealingRejectsLateCallbacks() {
        EffectInvocation invocation = new EffectInvocation(value -> {});
        invocation.admit(admission(ALLOW));
        assertEquals(UNCERTAIN, invocation.resolve(CoreToolResult.failure("interrupted")
                .withEffectReceipt(invocation.receipt(OK)), true).outcome);
        invocation.seal();
        assertThrows(IllegalStateException.class, () -> invocation.admit(admission(ALLOW)));
        assertThrows(IllegalStateException.class, () -> invocation.noteDecision(admission(ALLOW)));
        assertThrows(IllegalStateException.class, () -> invocation.addAdmissionWriter(value -> {}));
        assertTrue(invocation.admitted());
        assertEquals(UNCERTAIN, invocation.resolveFailure(true).outcome);
    }
    @Test public void administrativeReadCompletionIsOwnerBoundAndNeverAWriteReceipt() {
        EffectInvocation first = new EffectInvocation(value -> {});
        EffectInvocation second = new EffectInvocation(value -> {});
        EffectInvocation.AdministrativeReceipt completed = first.readCompleted();
        assertTrue(first.ownsReadCompletion(completed));
        assertFalse(second.ownsReadCompletion(completed));
        CoreToolResult copied = CoreToolResult.preview("read", "preview").withAdministrativeReceipt(completed)
                .withEffectReceipt(first.receipt(NOT_EXECUTED)).withContent("redacted");
        assertSame(completed, copied.administrativeReceipt);
        assertEquals("preview", copied.previewId);
        assertEquals(NOT_EXECUTED, first.resolve(copied, true).outcome);
        first.admit(admission(ALLOW));
        assertFalse(first.ownsReadCompletion(completed));
        assertThrows(IllegalStateException.class, first::readCompleted);
    }
    @Test public void memoryCompletionIsDistinctFromReadAndBoundToItsOwner() {
        EffectInvocation invocation = new EffectInvocation(value -> {});
        EffectInvocation other = new EffectInvocation(value -> {});
        EffectInvocation.AdministrativeReceipt memory = invocation.memoryCompleted();
        assertEquals(EffectInvocation.AdministrativeKind.MEMORY_COMPLETED, memory.kind);
        assertTrue(invocation.ownsMemoryCompletion(memory));
        assertFalse(invocation.ownsReadCompletion(memory));
        assertFalse(other.ownsMemoryCompletion(memory));
        assertFalse(invocation.ownsMemoryCompletion(invocation.readCompleted()));
        CoreToolResult copy = CoreToolResult.persisted("stored", "local revision")
                .withAdministrativeReceipt(memory).withContent("redacted")
                .withEffectReceipt(invocation.receipt(NOT_EXECUTED));
        assertSame(memory, copy.administrativeReceipt);
        assertEquals("local revision", copy.persistenceEvidence);
        assertEquals(NOT_EXECUTED, invocation.resolve(copy, true).outcome);
        invocation.admit(admission(ALLOW));
        assertFalse(invocation.ownsMemoryCompletion(memory));
        assertThrows(IllegalStateException.class, invocation::memoryCompleted);
    }
    @Test public void contentCopiesPreserveEveryExistingFlagAndBothReceipts() {
        EffectInvocation.Receipt receipt = new EffectInvocation.Receipt(ERROR, admission(ALLOW));
        CoreToolResult[] originals = {CoreToolResult.success("old"), CoreToolResult.failure("old"),
                CoreToolResult.complete("old"), CoreToolResult.finish("old"), CoreToolResult.preview("old", "preview"),
                CoreToolResult.persisted("old", "durable-memory-evidence")};
        for (CoreToolResult original : originals) {
            CoreToolResult withReceipt = original.withEffectReceipt(receipt);
            CoreToolResult copy = withReceipt.withContent("redacted");
            assertEquals("old", original.content);
            assertEquals("redacted", copy.content);
            assertEquals(original.success, copy.success);
            assertEquals(original.completeContentRequired, copy.completeContentRequired);
            assertEquals(original.finishRun, copy.finishRun);
            assertEquals(original.previewId, copy.previewId);
            assertEquals(original.persistenceEvidence, copy.persistenceEvidence);
            assertSame(receipt, copy.effectReceipt);
            assertEquals("", copy.withContent(null).content);
        }
    }
    @Test public void equivalentConnectionObservationCopiesAreIdempotent() {
        com.jarvys.agent.connectors.ConnectionStateCoordinator.Observation first =
            com.jarvys.agent.connectors.ConnectionObservationTestSupport.observation("mail", true);
        com.jarvys.agent.connectors.ConnectionStateCoordinator.Observation copy =
            com.jarvys.agent.connectors.ConnectionObservationTestSupport.observation("mail", true);
        assertNotSame(first, copy); assertEquals(first, copy);
        EffectInvocation.Admission a = new EffectInvocation.Admission(ALLOW, "connector:mail", "send", "1", "test", first);
        EffectInvocation.Admission b = new EffectInvocation.Admission(ALLOW, "connector:mail", "send", "1", "test", copy);
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode());
        AtomicInteger writes = new AtomicInteger();
        EffectInvocation invocation = new EffectInvocation(value -> writes.incrementAndGet());
        invocation.admit(a); invocation.admit(b);
        assertEquals(1, writes.get());
    }

    @Test public void changedConnectionObservationCannotMixWithAdmittedSnapshot() {
        EffectInvocation invocation = new EffectInvocation(value -> { });
        invocation.admit(new EffectInvocation.Admission(ALLOW, "connector:mail", "send", "1", "test",
            com.jarvys.agent.connectors.ConnectionObservationTestSupport.observation("mail", true)));
        assertThrows(IllegalStateException.class, () -> invocation.admit(
            new EffectInvocation.Admission(ALLOW, "connector:mail", "send", "1", "test",
                com.jarvys.agent.connectors.ConnectionObservationTestSupport.observation("mail", false))));
        assertTrue(invocation.admitted());
        assertTrue(invocation.admission().connectionObservation.selected);
    }

}
