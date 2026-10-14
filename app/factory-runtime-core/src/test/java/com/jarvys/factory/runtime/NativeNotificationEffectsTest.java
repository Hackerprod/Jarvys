package com.jarvys.factory.runtime;

import android.app.Activity;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static com.jarvys.factory.runtime.NativeOperationSessionsTest.idle;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;
import static org.junit.Assert.*;

/** Real foreground lease/Job wiring with a synthetic adapter; no alarms, notifications or settings UI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeNotificationEffectsTest {
    private NativeOperationSessionsTest.Fixture fixture;
    private FactoryConfig config;
    private NativeEffects effects;
    private Adapter adapter;
    private int sequence;

    @Before public void setup() throws Exception {
        fixture = new NativeOperationSessionsTest.Fixture();
        config = FactoryConfig.parse(new JSONObject(FactoryDispatcherTest.configuration("[\"notifications\"]"))
                .put("appId", fixture.config.appId).put("documentBroker", new JSONObject()
                        .put("packageName", fixture.config.documentBroker.packageName)
                        .put("certificateSha256", fixture.config.documentBroker.certificateSha256))
                .put("notifications", new JSONObject().put("scheduling", true).put("exactAlarms", true)).toString(), fixture.config.appId);
        effects = new NativeEffects(fixture.screen, config, fixture.sessions, fixture.io, true);
        adapter = new Adapter(); ReflectionHelpers.setField(effects, "notificationEffects", adapter);
    }
    @After public void finish() throws Exception { effects.close(); idle(); fixture.close(); }

    @Test public void scheduleBindsOneCompleteImmutableGrantWithoutUi() throws Exception {
        JSONObject args = schedule(); BridgeProtocol.Request request = request("notifications.schedule", args);
        Result result = new Result(); effects.perform(request, result);
        NativeOperationSessions.Lease lease = lease();
        assertEquals("notifications.schedule", lease.request.operation);
        String bound = lease.request.canonicalJson();
        request.args.getJSONObject("notification").getJSONArray("actions").getJSONObject(0).getJSONObject("data").put("route", "changed");
        request.args.put("atEpochMs", 1);
        fixture.pump();
        assertEquals(1, adapter.schedules); assertEquals(bound,
                NativeOperationRequest.parse("notifications.schedule", adapter.scheduled.authorizationDetails()).canonicalJson());
        assertEquals(1, fixture.provider.issued.size()); assertEquals(0, result.claims); assertEquals(1, result.successes);
        assertTrue(lease.ended.get()); assertFalse(adapter.lastSession.active()); assertTrue(jobs().isEmpty());
    }

    @Test public void showDisclosesActionsUsingTheExistingShowGrantOnly() throws Exception {
        Result result = perform("notifications.show", notification());
        NativeOperationSessions.Lease lease = lease();
        assertEquals("notifications.show", lease.request.operation);
        assertEquals("details", lease.request.details().getJSONArray("actions").getJSONObject(0).getString("id"));
        fixture.pump();
        assertEquals(1, adapter.shows); assertEquals(1, fixture.provider.issued.size());
        assertEquals(1, adapter.shown.actions.size()); assertEquals(1, result.successes); assertEquals(0, result.claims);
    }

    @Test public void resumeLoadsOriginalThenAuthorizesScheduleRatherThanResume() throws Exception {
        adapter.original = original();
        Result result = perform("notifications.resumeScheduled", new JSONObject().put("scheduleId", adapter.original.scheduleId));
        assertTrue(fixture.provider.issued.isEmpty());
        fixture.io.runNext(); idle(); // Read-only original request lookup, then foreground authorization.
        NativeOperationSessions.Lease lease = lease();
        assertEquals("notifications.schedule", lease.request.operation);
        assertEquals(adapter.original.request.fingerprint(), NotificationScheduleRequest.parseSchedule(lease.request.details()).fingerprint());
        fixture.pump();
        assertEquals(1, adapter.reads); assertEquals(1, adapter.schedules); assertSame(adapter.original, adapter.resumed);
        assertEquals(1, fixture.provider.issued.size()); assertEquals(1, result.successes);
    }

    @Test public void resumeRequiresAuthenticatedHostSession() throws Exception {
        adapter.original = original(); fixture.provider.unavailable = true;
        Result result = perform("notifications.resumeScheduled", new JSONObject().put("scheduleId", adapter.original.scheduleId));
        fixture.pump();
        assertEquals(1, adapter.reads); assertEquals(0, adapter.schedules); assertEquals("UNAVAILABLE", result.error);
        assertTrue(fixture.provider.issued.isEmpty()); assertEquals(0, result.claims); assertTrue(jobs().isEmpty());
    }

    @Test public void statusCleanupAndInboxRemainUsableWithoutExternalHostSession() throws Exception {
        fixture.provider.unavailable = true;
        Result[] results = {
                perform("notifications.cancel", new JSONObject().put("id", 7)),
                perform("notifications.scheduleStatus", new JSONObject().put("scheduleId", "reminder")),
                perform("notifications.cancelScheduled", new JSONObject().put("scheduleId", "reminder")),
                perform("notifications.forgetScheduled", new JSONObject().put("scheduleId", "reminder")),
                perform("notifications.generationStatus", new JSONObject().put("generationId", token('1'))),
                perform("notifications.forgetGeneration", new JSONObject().put("generationId", token('1'))),
                perform("notifications.pollActions", new JSONObject()),
                perform("notifications.ackAction", new JSONObject().put("eventId", token('2')))
        };
        fixture.pump();
        for (Result result : results) { assertEquals(1, result.successes); assertNull(result.error); assertEquals(0, result.claims); }
        assertEquals(8, adapter.cleanups); assertTrue(fixture.provider.issued.isEmpty()); assertTrue(jobs().isEmpty());
    }

    @Test public void pauseInvalidatesApprovedSessionBeforeTheQueuedEffect() throws Exception {
        Result result = perform("notifications.schedule", schedule());
        fixture.io.runNext(); idle(); // Approval posts the effect worker but has not run it yet.
        Session session = ReflectionHelpers.getField(job(), "notificationSession"); assertNotNull(session);
        effects.onPause(); fixture.sessions.onPause(); fixture.pump();
        assertFalse(session.active()); assertEquals(0, adapter.schedules); assertEquals("CANCELLED", result.error);
        assertEquals(0, result.successes); assertTrue(jobs().isEmpty());
    }

    @Test public void closeAfterEffectSuppressesQueuedReceiptWithoutClaimingRollback() throws Exception {
        Result result = perform("notifications.schedule", schedule());
        fixture.io.runNext(); idle(); fixture.io.runNext(); // Synthetic durable acceptance, reply still queued.
        assertEquals(1, adapter.schedules); assertEquals(0, result.successes);
        effects.close(); idle();
        assertEquals("CANCELLED", result.error); assertEquals(0, result.successes); assertFalse(adapter.lastSession.active());
        assertEquals(1, adapter.schedules); assertTrue(jobs().isEmpty());
    }

    @Test public void exactAccessWaitsForRealPauseResumeThenRereadsStatus() throws Exception {
        Result result = perform("notifications.requestExactAlarmAccess", new JSONObject()); fixture.pump();
        assertEquals(1, adapter.settings); assertEquals(1, result.claims); assertTrue(effects.hasPendingActivity());
        assertEquals(0, result.successes); effects.onResume(); fixture.pump(); assertEquals(0, adapter.settingsReads);
        effects.onPause(); fixture.sessions.onPause(); adapter.exactGranted = true;
        fixture.sessions.onResume(); effects.onResume(); fixture.pump();
        assertEquals("granted", result.value.getString("exactAlarmAccess")); assertEquals(1, adapter.settingsReads);
        assertEquals(1, result.releases); assertEquals(1, result.successes); assertFalse(effects.hasPendingActivity());
        assertEquals(1, fixture.provider.issued.size());
    }

    @Test public void revokedSettingsRetainsUiTombstoneUntilReturnAndNeverSucceedsLate() throws Exception {
        Result result = perform("notifications.requestExactAlarmAccess", new JSONObject()); fixture.pump();
        effects.onPause(); fixture.sessions.onPause(); fixture.sessions.cancelAll(); idle();
        assertEquals("CANCELLED", result.error); assertTrue(effects.hasPendingActivity());
        assertEquals(0, result.releases); assertTrue(result.held);
        fixture.sessions.onResume(); effects.onResume(); fixture.pump();
        assertFalse(effects.hasPendingActivity()); assertEquals(1, result.releases); assertEquals(0, result.successes);
        assertEquals(0, adapter.settingsReads); assertTrue(jobs().isEmpty());
    }

    @Test public void foregroundAuthorityChecksOriginalSourcePayloadGenerationAndLiveSession() throws Exception {
        Row row = original(); AtomicInteger checks = new AtomicInteger();
        Authority authority = NotificationEffects.foregroundAuthority(row.request, row, row.source, checks::incrementAndGet);
        Session session = new Session(FactoryNotificationServices.CLOCK, 300_000);
        Decision decision = authority.acquire(row, row.source, Mode.FOREGROUND, session);
        decision.lease.check(); decision.close();
        assertEquals(2, checks.get()); // Borrowed session is live-checked; closing it does not end caller ownership.
        assertThrows(SecurityException.class, () -> authority.acquire(row, row.source, Mode.BACKGROUND_NO_UI, session));
        Row otherGeneration = new Row(row.source, row.request, token('9'), row.state, 1, "test", "", "", 1, 1);
        assertThrows(SecurityException.class, () -> authority.acquire(otherGeneration, row.source, Mode.FOREGROUND, session));
        Source otherSource = source(token('8'));
        assertThrows(SecurityException.class, () -> authority.acquire(row, otherSource, Mode.FOREGROUND, session));
        NotificationScheduleRequest.Schedule changed = NotificationScheduleRequest.parseSchedule(schedule().put("scheduleId", "another"));
        Row otherPayload = new Row(row.source, changed, row.token, row.state, 1, "test", "", "", 1, 1);
        assertThrows(SecurityException.class, () -> authority.acquire(otherPayload, row.source, Mode.FOREGROUND, session));
        session.invalidate(); assertThrows(SecurityException.class, decision.lease::check);
    }

    private JSONObject schedule() throws Exception {
        long at = System.currentTimeMillis() + 60000;
        return new JSONObject().put("scheduleId", "reminder").put("atEpochMs", at).put("expiresAtEpochMs", at + 60000).put("notification", notification());
    }
    private JSONObject notification() throws Exception {
        return new JSONObject().put("id", 7).put("channelId", "general").put("title", "Synthetic reminder").put("body", "Synthetic body")
                .put("actions", new JSONArray().put(new JSONObject().put("id", "details").put("title", "Open")
                        .put("data", new JSONObject().put("route", "details").put("filters", new JSONArray().put("one").put(JSONObject.NULL)))));
    }
    private Row original() throws Exception {
        return new Row(source(token('3')), NotificationScheduleRequest.parseSchedule(schedule()), token('4'),
                NotificationScheduleMachine.State.QUEUED_SESSION_UNAVAILABLE, 1, "authenticated_host_unavailable", "", "", 1, 1);
    }
    private Source source(String apk) { return new Source(config.appId, token('1'), apk, token('2'), 1, true, true, true, new Limits(8, 8, 32, 131072)); }
    private static String token(char value) { return new String(new char[64]).replace('\0', value); }
    private Map<?, ?> jobs() { return ReflectionHelpers.getField(effects, "jobs"); }
    private Object job() { assertEquals(1, jobs().size()); return jobs().values().iterator().next(); }
    private NativeOperationSessions.Lease lease() { return ReflectionHelpers.getField(job(), "lease"); }
    private Result perform(String method, JSONObject args) throws Exception { Result result = new Result(); effects.perform(request(method, args), result); return result; }
    private BridgeProtocol.Request request(String method, JSONObject args) throws Exception {
        return BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, new JSONObject().put("v", 1).put("id", "notification_" + ++sequence)
                .put("method", method).put("args", args).toString(), config);
    }
    private static JSONObject result(String status) {
        try { return new JSONObject().put("status", status); } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }

    private final class Adapter extends NotificationEffects {
        int schedules, shows, reads, cleanups, settings, settingsReads;
        boolean exactGranted; Row original, resumed; Session lastSession;
        NotificationScheduleRequest.Schedule scheduled; NotificationScheduleRequest.Notification shown;
        Adapter() { super(NativeNotificationEffectsTest.this.fixture.screen, NativeNotificationEffectsTest.this.config); }
        @Override JSONObject show(NotificationScheduleRequest.Notification notification, NativeOperationAuthorization.Access live, Session session) {
            live.check(); session.check(); shows++; shown = notification; lastSession = session; return result("accepted");
        }
        @Override Row storedSchedule(String id, Session session) { session.check(); reads++; return original; }
        @Override JSONObject schedule(NotificationScheduleRequest.Schedule request, Row resume, NativeOperationAuthorization.Access live, Session session) {
            live.check(); session.check(); schedules++; scheduled = request; resumed = resume; lastSession = session; return result("scheduled");
        }
        @Override JSONObject cancelVisible(int id, Session session) { return cleanup(session); }
        @Override JSONObject scheduleStatus(String id, Session session) { return cleanup(session); }
        @Override JSONObject cancelScheduled(String id, Session session) { return cleanup(session); }
        @Override JSONObject forgetScheduled(NotificationScheduleControl.Forget request, Session session) { return cleanup(session); }
        @Override JSONObject generationStatus(String id, Session session) { return cleanup(session); }
        @Override JSONObject forgetGeneration(NotificationScheduleControl.Generation request, Session session) { return cleanup(session); }
        @Override JSONObject pollActions(Session session) { return cleanup(session); }
        @Override JSONObject acknowledgeAction(String id, Session session) { return cleanup(session); }
        private JSONObject cleanup(Session session) { session.check(); cleanups++; lastSession = session; return result("ok"); }
        @Override ExactAccess exactAccess(Session session) {
            session.check(); lastSession = session;
            return new ExactAccess() {
                public JSONObject request(Activity activity, NativeOperationAuthorization.Access live) {
                    live.check(); session.check(); settings++;
                    try { return result("permission_required").put("exactAlarmAccessRequested", true); }
                    catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
                }
                public JSONObject status() {
                    settingsReads++;
                    try { return result("ready").put("exactAlarmAccess", exactGranted ? "granted" : "not_granted"); }
                    catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
                }
            };
        }
    }
    private static final class Result implements NativeEffects.Reply {
        JSONObject value; String error; int claims, releases, successes; boolean current = true, held;
        public boolean current() { return current; }
        public boolean claimUi() { assertFalse(held); held = true; claims++; return true; }
        public void releaseUi() { if (held) { held = false; releases++; } }
        public void ok(Object result) { assertTrue(current); value = (JSONObject) result; successes++; }
        public void fail(String code, String message) { error = code; }
    }
}
