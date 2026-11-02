package com.jarvys.agent;

import org.junit.Test;
import static org.junit.Assert.*;

/** Result labels/reasons are not native admission or checkpoint authority. */
public class CoreAgentLoopOrdinaryInterruptionResultTest {
    @Test public void interruptionReasonAloneCannotMintOrdinaryOrTreeReceipt() {
        CoreAgentLoop.Result labelled = new CoreAgentLoop.Result("run", "old text", 2,
                "PARTIAL", 42L, CoreAgentLoop.InterruptionReason.SUPERSEDED_BY_NEW_TURN);
        assertNoNativeAuthority(labelled);
        assertEquals(CoreAgentLoop.InterruptionReason.SUPERSEDED_BY_NEW_TURN,
                labelled.interruptionReason);
    }

    @Test public void withTextConservesFieldsWithoutUpgradingUnconfirmedReason() {
        CoreAgentLoop.Result labelled = new CoreAgentLoop.Result("run", "old text", 2,
                "PARTIAL", 42L, CoreAgentLoop.InterruptionReason.SUPERSEDED_BY_NEW_TURN);
        CoreAgentLoop.Result replaced = labelled.withText("replacement");
        assertNotSame(labelled, replaced);
        assertEquals("old text", labelled.text);
        assertEquals("replacement", replaced.text);
        assertEquals(labelled.runId, replaced.runId);
        assertEquals(labelled.turns, replaced.turns);
        assertEquals(labelled.outcome, replaced.outcome);
        assertEquals(labelled.durationMs, replaced.durationMs);
        assertEquals(labelled.interruptionReason, replaced.interruptionReason);
        assertNoNativeAuthority(replaced);
    }

    @Test public void yieldedOutcomeStringCannotMintEitherReceipt() {
        assertNoNativeAuthority(new CoreAgentLoop.Result("run", "", 0, "YIELDED", 0L,
                CoreAgentLoop.InterruptionReason.SUPERSEDED_BY_NEW_TURN));
    }

    private static void assertNoNativeAuthority(CoreAgentLoop.Result result) {
        assertFalse(result.isOrdinaryInterrupted());
        assertFalse(result.isYielded());
        try { result.ordinaryInterruptionReceipt(); fail("Reason minted ordinary proof"); }
        catch (IllegalStateException expected) { }
        try { result.yieldReceipt(); fail("Reason minted tree proof"); }
        catch (IllegalStateException expected) { }
    }
}
