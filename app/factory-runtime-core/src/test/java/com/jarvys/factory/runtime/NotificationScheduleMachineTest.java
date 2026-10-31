package com.jarvys.factory.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static com.jarvys.factory.runtime.NotificationScheduleMachine.*;
import static org.junit.Assert.*;

/** Synthetic only: no Android AlarmManager, receiver, notification, filesystem ledger or host. */
public class NotificationScheduleMachineTest {
    private NotificationScheduleRequest.Schedule request() throws Exception { return NotificationScheduleRequestTest.request(); }
    private void check(Plan p, State state, Effect effect) { assertEquals(state, p.state); assertEquals(effect, p.effect); }
    @Test public void activeSessionScheduleRequiresDurableArmTransitionAndActualAcceptance() throws Exception {
        check(resume(request(), State.PENDING, 1000, SessionStatus.ACTIVE, false, true), State.ARMING, Effect.ARM);
        check(armed(State.ARMING, true), State.SCHEDULED, Effect.NONE);
        check(armed(State.ARMING, false), State.SCHEDULE_UNKNOWN, Effect.NONE);
    }
    @Test public void exactUnavailableDoesNotArmAnInexactFallback() throws Exception {
        NotificationScheduleRequest.Schedule exact = NotificationScheduleRequest.parseSchedule(new JSONObject(NotificationScheduleRequestTest.SCHEDULE).put("precision", "exact"));
        check(resume(exact, State.PENDING, 1000, SessionStatus.ACTIVE, false, true), State.QUEUED_EXACT_PERMISSION, Effect.NONE);
        check(resume(exact, State.QUEUED_EXACT_PERMISSION, 1000, SessionStatus.ACTIVE, true, true), State.ARMING, Effect.ARM);
    }
    @Test public void unavailableHostSessionCannotProduceNativeEffectsAtAdmission() throws Exception {
        check(resume(request(), State.PENDING, 1000, SessionStatus.UNAVAILABLE, true, true), State.QUEUED_SESSION_UNAVAILABLE, Effect.NONE);
    }
    @Test public void fireUsesLiveSessionAndActualOsReadiness() throws Exception {
        check(fire(request(), State.SCHEDULED, 2000, SessionStatus.UNAVAILABLE, true), State.QUEUED_SESSION_UNAVAILABLE, Effect.NONE);
        check(fire(request(), State.SCHEDULED, 2000, SessionStatus.ACTIVE, false), State.QUEUED_OS_BLOCKED, Effect.NONE);
        check(fire(request(), State.SCHEDULED, 2000, SessionStatus.ACTIVE, true), State.DISPATCH_RESERVED, Effect.POST);
    }
    @Test public void notDueAndExpiredNeverPost() throws Exception {
        check(fire(request(), State.SCHEDULED, 1999, SessionStatus.ACTIVE, true), State.SCHEDULED, Effect.NONE);
        check(fire(request(), State.SCHEDULED, 5001, SessionStatus.ACTIVE, true), State.EXPIRED, Effect.NONE);
        check(fire(request(), State.SCHEDULED, 5000, SessionStatus.ACTIVE, true), State.DISPATCH_RESERVED, Effect.POST);
    }
    @Test public void queuedRequestsCanBeExplicitlyResumedWithinTheirWindow() throws Exception {
        for (State state : new State[]{State.QUEUED_SESSION_UNAVAILABLE, State.QUEUED_OS_BLOCKED, State.QUEUED_EXACT_PERMISSION})
            check(resume(request(), state, 3000, SessionStatus.ACTIVE, true, true), State.DISPATCH_RESERVED, Effect.POST);
        check(resume(request(), State.QUEUED_SESSION_UNAVAILABLE, 5001, SessionStatus.ACTIVE, true, true), State.EXPIRED, Effect.NONE);
    }
    @Test public void acceptedMeansNotifyReturnedNotDisplayReceipt() {
        Plan result = posted(State.DISPATCH_RESERVED, true);
        check(result, State.ACCEPTED, Effect.NONE); assertTrue(result.reason.contains("no_display_receipt"));
    }
    @Test public void uncertainDispatchIsTerminalAndNeverAutoReplayed() throws Exception {
        check(recover(State.DISPATCH_RESERVED), State.DELIVERY_UNKNOWN, Effect.NONE);
        check(posted(State.DISPATCH_RESERVED, false), State.DELIVERY_UNKNOWN, Effect.NONE);
        check(resume(request(), State.DELIVERY_UNKNOWN, 3000, SessionStatus.ACTIVE, true, true), State.DELIVERY_UNKNOWN, Effect.NONE);
        check(fire(request(), State.DELIVERY_UNKNOWN, 3000, SessionStatus.ACTIVE, true), State.DELIVERY_UNKNOWN, Effect.NONE);
    }
    @Test public void uncertainArmCanResumeSameStableAlarmIdentity() throws Exception {
        check(recover(State.ARMING), State.SCHEDULE_UNKNOWN, Effect.NONE);
        check(resume(request(), State.SCHEDULE_UNKNOWN, 1500, SessionStatus.ACTIVE, true, true), State.ARMING, Effect.ARM);
    }
    @Test public void onlyProvenPreEffectFailureMayReleaseDispatchReservation() {
        check(blockedBeforePost(State.DISPATCH_RESERVED, SessionStatus.UNAVAILABLE, true), State.QUEUED_SESSION_UNAVAILABLE, Effect.NONE);
        check(blockedBeforePost(State.DISPATCH_RESERVED, SessionStatus.ACTIVE, false), State.QUEUED_OS_BLOCKED, Effect.NONE);
        check(blockedBeforePost(State.DISPATCH_RESERVED, SessionStatus.ACTIVE, true), State.DISPATCH_RESERVED, Effect.NONE);
    }
    @Test public void tombstoneWinsLateCallbacksAndCancellationCleanupIsRepeatable() throws Exception {
        check(cancel(State.SCHEDULED), State.CANCELLED, Effect.CANCEL_ALARM);
        check(fire(request(), State.CANCELLED, 2000, SessionStatus.ACTIVE, true), State.CANCELLED, Effect.NONE);
        check(cancel(State.CANCELLED), State.CANCELLED, Effect.CANCEL_ALARM);
        check(armed(State.CANCELLED, true), State.CANCELLED, Effect.NONE);
    }
    @Test public void cancellationCannotClaimToRecallAnInFlightPost() {
        for (State state : new State[]{State.DISPATCH_RESERVED, State.ACCEPTED, State.DELIVERY_UNKNOWN}) {
            Plan result = cancel(state); check(result, state, Effect.NONE); assertTrue(result.reason.startsWith("too_late"));
        }
    }
    @Test public void everyTerminalStateAndReservationBlocksDuplicateDispatch() throws Exception {
        for (State state : State.values()) if (terminal(state) || state == State.DISPATCH_RESERVED) {
            check(fire(request(), state, 2000, SessionStatus.ACTIVE, true), state, Effect.NONE);
            check(resume(request(), state, 2000, SessionStatus.ACTIVE, true, true), state, Effect.NONE);
        }
    }
    @Test public void syntheticAtomicCommitAllowsOnlyOneConcurrentPostPlan() throws Exception {
        NotificationScheduleRequest.Schedule request = request();
        AtomicReference<State> persisted = new AtomicReference<>(State.SCHEDULED);
        AtomicInteger posts = new AtomicInteger(); CountDownLatch planned = new CountDownLatch(2), release = new CountDownLatch(1);
        Runnable task = () -> {
            State before = persisted.get(); Plan next = fire(request, before, 2000, SessionStatus.ACTIVE, true);
            planned.countDown(); try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            if (persisted.compareAndSet(before, next.state) && next.effect == Effect.POST) posts.incrementAndGet();
        };
        Thread a = new Thread(task), b = new Thread(task); a.start(); b.start(); planned.await(); release.countDown(); a.join(); b.join();
        assertEquals(1, posts.get()); assertEquals(State.DISPATCH_RESERVED, persisted.get());
        // A restarted process sees an uncertain dispatch and performs no second external call.
        persisted.set(recover(persisted.get()).state);
        check(fire(request, persisted.get(), 3000, SessionStatus.ACTIVE, true), State.DELIVERY_UNKNOWN, Effect.NONE);
    }
    @Test public void malformedInputsFailBeforePlanningAnyEffect() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> fire(request(), State.SCHEDULED, -1, SessionStatus.ACTIVE, true));
        assertThrows(IllegalArgumentException.class, () -> resume(request(), null, 1000, SessionStatus.ACTIVE, true, true));
        assertThrows(IllegalArgumentException.class, () -> fire(request(), State.SCHEDULED, 3000, null, true));
    }
}
