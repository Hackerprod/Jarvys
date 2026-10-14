package com.jarvys.factory.runtime;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;
import static com.jarvys.factory.runtime.NotificationScheduleMachine.*;

/** One-shot durable scheduler. All host, OS and persistence effects are explicit injected boundaries. */
public final class NotificationScheduleEngine {
    public interface Clock { long epochMs(); long elapsedMs(); }
    public enum Mode { BACKGROUND_NO_UI, FOREGROUND }
    public enum Kind { SCHEDULED, IMMEDIATE }
    public interface Lease extends AutoCloseable {
        /** A live authenticated Binder CHECK, never a cached boolean. */
        void check();
        @Override void close();
    }
    public static final class Decision implements AutoCloseable {
        public final SessionStatus sessionStatus;
        public final Lease lease;
        public Decision(SessionStatus sessionStatus, Lease lease) {
            if (sessionStatus == null || (sessionStatus == SessionStatus.ACTIVE) != (lease != null)) throw new IllegalArgumentException();
            this.sessionStatus = sessionStatus; this.lease = lease;
        }
        @Override public void close() { if (lease != null) lease.close(); }
    }
    public interface Authority {
        /** Must bind notifications.schedule, the complete stored request and verified source. No UI in BACKGROUND_NO_UI. */
        Decision acquire(Row row, Source currentSource, Mode mode, Session session) throws FactoryException;
    }
    public interface Store {
        Row insert(Source source, NotificationScheduleRequest.Schedule request, long now) throws FactoryException;
        Row insertImmediate(Source source, NotificationScheduleRequest.Notification notification, String process, long now) throws FactoryException;
        Row get(String artifact, String id) throws FactoryException;
        Row byAlarmToken(String token) throws FactoryException;
        /** One durable CAS. DISPATCH_RESERVED also persists action tokens/event IDs atomically. */
        Row change(Row expected, State next, String reason, String process, String attempt, long now) throws FactoryException;
        List<ActionToken> actions(Row reservation) throws FactoryException;
        boolean forget(Row expected, boolean acknowledgeUnknown) throws FactoryException;
        /** Bounded stable keyset page, including tombstones for identity-safe cleanup. */
        List<Row> page(String afterToken, int limit) throws FactoryException;
        String recoveryCursor() throws FactoryException;
        void checkpointRecovery(String token) throws FactoryException;
    }
    public interface Platform {
        boolean exactAvailable();
        boolean notificationsReady(NotificationScheduleRequest.Notification notification);
        void arm(Row row, Checkpoint checkpoint) throws FactoryException;
        void cancelAlarm(Row row) throws FactoryException;
        /** Implementations must invoke checkpoint immediately before notify. */
        void post(Row row, List<ActionToken> actions, Checkpoint checkpoint) throws FactoryException;
    }
    public interface Checkpoint { void check(); }
    /** Receipt-authenticated budgets only. Retained rows include tombstones; no automatic eviction. */
    public static final class Limits {
        public final int schedules, inboxEvents, tombstones;
        public final long ledgerBytes;
        public Limits(int schedules, int events, int tombstones, long bytes) {
            if (schedules < 1 || schedules > 1024 || events < 1 || events > 1024 || tombstones < 1 || tombstones > 4096
                    || bytes < 65536 || bytes > 16777216) throw new IllegalArgumentException("Unsupported notification budgets");
            this.schedules = schedules; inboxEvents = events; this.tombstones = tombstones; ledgerBytes = bytes;
        }
    }
    /** Construct only from the trusted installed-artifact verifier, never from JS arguments or Intent extras. */
    public static final class Source {
        public final String appId, certificateSha256, artifactSha256, receiptSha256, fingerprint, ownerFingerprint;
        public final long versionCode;
        public final boolean notificationsCapability, schedulingCapability, exactCapability;
        public final Limits limits;
        public Source(String appId, String certificate, String artifact, String receipt, long version,
                      boolean exactCapability, Limits limits) {
            this(appId,certificate,artifact,receipt,version,true,true,exactCapability,limits);
        }
        public Source(String appId, String certificate, String artifact, String receipt, long version,
                      boolean notificationsCapability, boolean exactCapability, Limits limits) {
            this(appId,certificate,artifact,receipt,version,notificationsCapability,notificationsCapability,exactCapability,limits);
        }
        public Source(String appId, String certificate, String artifact, String receipt, long version,
                      boolean notificationsCapability, boolean schedulingCapability, boolean exactCapability, Limits limits) {
            if (appId == null || !appId.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") || version < 1
                    || !NotificationActionEvents.validToken(certificate) || !NotificationActionEvents.validToken(artifact)
                    || !NotificationActionEvents.validToken(receipt) || limits == null || (exactCapability && !schedulingCapability) || (schedulingCapability && !notificationsCapability)) throw new IllegalArgumentException("Verified source required");
            this.appId = appId; certificateSha256 = certificate; artifactSha256 = artifact; receiptSha256 = receipt;
            versionCode = version; this.notificationsCapability = notificationsCapability; this.schedulingCapability=schedulingCapability; this.exactCapability = exactCapability; this.limits = limits;
            ownerFingerprint = NotificationScheduleIdentity.digest("factory-notification-owner-v1",appId,certificate);
            fingerprint = NotificationScheduleIdentity.digest("factory-notification-source-v1", appId, certificate,
                    artifact, receipt, String.valueOf(version));
        }
    }
    public static final class Row {
        public final Source source;
        public final Kind kind;
        public final NotificationScheduleRequest.Schedule request;
        public final NotificationScheduleRequest.Notification notification;
        public final String recordId, scheduleId, token, fingerprint, reason, process, attempt;
        public final State state;
        public final long revision, createdAt, updatedAt;
        public Row(Source source, NotificationScheduleRequest.Schedule request, String token, State state, long revision,
                   String reason, String process, String attempt, long createdAt, long updatedAt) {
            this(source,request,request==null?null:request.notification,token,state,revision,reason,process,attempt,createdAt,updatedAt);
        }
        public Row(Source source, NotificationScheduleRequest.Notification notification, String token, State state, long revision,
                   String reason, String process, String attempt, long createdAt, long updatedAt) {
            this(source,null,notification,token,state,revision,reason,process,attempt,createdAt,updatedAt);
        }
        private Row(Source source, NotificationScheduleRequest.Schedule request, NotificationScheduleRequest.Notification notification,
                    String token, State state, long revision, String reason, String process, String attempt, long createdAt, long updatedAt) {
            if (source==null || notification==null || !NotificationActionEvents.validToken(token) || state==null || revision<0) throw new IllegalArgumentException();
            this.source=source;this.request=request;this.notification=notification;this.token=token;this.state=state;this.revision=revision;
            this.kind=request==null?Kind.IMMEDIATE:Kind.SCHEDULED;this.scheduleId=request==null?null:request.scheduleId;
            this.recordId=request==null?"@"+token:request.scheduleId;
            this.reason=reason;this.process=process;this.attempt=attempt;this.createdAt=createdAt;this.updatedAt=updatedAt;
            fingerprint=request==null?immediateFingerprint(notification):request.fingerprint();
        }
        public static String immediateFingerprint(NotificationScheduleRequest.Notification notification) {
            return NotificationScheduleIdentity.digest("factory-immediate-notification-v1",NotificationScheduleRequest.canonical(notification.details(),0));
        }
        public JSONObject details() {
            try {
                JSONObject result=new JSONObject().put("status",state.name().toLowerCase(java.util.Locale.ROOT)).put("kind",kind==Kind.SCHEDULED?"scheduled":"immediate")
                        .put("reason",reason).put("revision",revision).put("createdAtEpochMs",createdAt).put("updatedAtEpochMs",updatedAt);
                if(kind==Kind.SCHEDULED)result.put("scheduleId",scheduleId).put("request",request.authorizationDetails());
                else result.put("generationId",token).put("id",notification.show.id).put("notification",notification.details());
                return result;
            } catch (JSONException impossible) { throw new AssertionError(impossible); }
        }
    }
    public static final class ActionToken {
        public final String token, eventId;
        public final NotificationScheduleRequest.Action action;
        public ActionToken(String token, String event, NotificationScheduleRequest.Action action) {
            if (!NotificationActionEvents.validToken(token) || !NotificationActionEvents.validToken(event) || action == null)
                throw new IllegalArgumentException();
            this.token = token; eventId = event; this.action = action;
        }
    }
    /** Timeout cancellation is irreversible. A late Binder result never re-opens this generation. */
    public static final class Session {
        public final String attempt = NotificationActionEvents.randomToken();
        public final long deadlineElapsedMs;
        private final Clock clock;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public Session(Clock clock, long budgetMs) {
            if (clock == null || budgetMs < 1 || budgetMs > 300000) throw new IllegalArgumentException();
            this.clock = clock; deadlineElapsedMs = Math.addExact(clock.elapsedMs(), budgetMs);
        }
        public boolean active() { return !cancelled.get() && clock.elapsedMs() < deadlineElapsedMs; }
        public void invalidate() { cancelled.set(true); }
        public void check() { if (!active()) throw new SecurityException("Notification work generation ended"); }
    }
    private final Store store;
    private final Authority authority;
    private final Platform platform;
    private final Clock clock;
    private final Source source;
    private final String process;
    public NotificationScheduleEngine(Store store, Authority authority, Platform platform, Clock clock, Source source, String process) {
        if (store == null || authority == null || platform == null || clock == null || source == null || !NotificationActionEvents.validToken(process))
            throw new IllegalArgumentException();
        this.store = store; this.authority = authority; this.platform = platform; this.clock = clock; this.source = source; this.process = process;
    }
    public Row schedule(NotificationScheduleRequest.Schedule request, Mode mode, Session session) throws FactoryException {
        if (request == null) throw new IllegalArgumentException();
        Row existing = store.get(source.ownerFingerprint, request.scheduleId);
        if (existing != null) {
            if (!existing.fingerprint.equals(request.fingerprint())) throw conflict();
            return existing; // Never reauthorize/re-arm accepted or queued work merely because the caller retries.
        }
        request.requireFuture(clock.epochMs());
        if (!source.notificationsCapability || !source.schedulingCapability) throw new FactoryException("CAPABILITY_REQUIRED", "Notification scheduling is not declared in the current artifact.");
        if (request.precision == NotificationScheduleRequest.Precision.EXACT && !source.exactCapability)
            throw new FactoryException("CAPABILITY_REQUIRED", "Exact alarm scheduling was not declared in this artifact.");
        session.check();
        Row created = store.insert(source, request, clock.epochMs());
        if (created.state != State.PENDING || created.revision != 0) return created;
        return run(created, false, mode, session);
    }
    public Row status(String id) throws FactoryException { return store.get(source.ownerFingerprint, id); }
    public Row resume(String id, Mode mode, Session session) throws FactoryException {
        Row row = status(id); return row == null ? null : run(recoverPriorProcess(row), false, mode, session);
    }
    public Row fire(String token, Session session) throws FactoryException {
        synchronized (NotificationEffectGate.forSchedule(token)) {
            Row row = store.byAlarmToken(token);
            if (row == null || row.kind!=Kind.SCHEDULED) return null;
            row = recoverPriorProcess(row);
            if (row == null) return null;
            if (!source.ownerFingerprint.equals(row.source.ownerFingerprint)) return revokeOld(row);
            return run(row, true, Mode.BACKGROUND_NO_UI, session);
        }
    }
    public Row cancel(String id) throws FactoryException {
        for (int retry=0; retry<32; retry++) {
            Row row = status(id); if (row == null) return null;
            Plan plan = NotificationScheduleMachine.cancel(row.state);
            if (plan.effect == Effect.NONE) return row;
            Row changed = transition(row, plan.state, plan.reason, row.attempt);
            if (changed == null) continue;
            if (plan.effect == Effect.CANCEL_ALARM) platform.cancelAlarm(changed);
            return changed;
        }
        throw new FactoryException("BUSY", "Notification cancellation is contended; no cancellation was confirmed.");
    }
    /** Explicit finite-history boundary. New reuse gets fresh tokens; no pending event is discarded. */
    public boolean forget(String id, boolean acknowledgeDeliveryUnknown) throws FactoryException {
        Row row = status(id); if (row == null) return false;
        if (!terminal(row.state) || (row.state == State.DELIVERY_UNKNOWN && !acknowledgeDeliveryUnknown))
            throw new FactoryException("UNRESOLVED_NOTIFICATION", "Only terminal records may be forgotten; uncertain delivery needs explicit acknowledgment.");
        platform.cancelAlarm(row);
        return store.forget(row, acknowledgeDeliveryUnknown);
    }
    /** Only SCHEDULED/ARMING-from-a-dead-process are restored automatically. Queued work needs explicit resume. */
    public String recoverPage(String afterToken, int limit, Session session) throws FactoryException {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException();
        List<Row> rows = store.page(afterToken, limit); String cursor = afterToken;
        if (rows.isEmpty()) store.checkpointRecovery(null);
        for (Row original : rows) {
            if (!session.active()) break;
            cursor = original.token;
            store.checkpointRecovery(cursor); // Make progress survive a slow host, timeout or process death.
            Row row = recoverPriorProcess(original);
            if (row == null) continue;
            if (!source.ownerFingerprint.equals(row.source.ownerFingerprint)) { revokeOld(row); continue; }
            if (row.kind==Kind.IMMEDIATE) continue;
            if (terminal(row.state)) { platform.cancelAlarm(row); continue; }
            if (row.state == State.SCHEDULED || row.state == State.SCHEDULE_UNKNOWN)
                run(row, false, Mode.BACKGROUND_NO_UI, session);
        }
        return cursor;
    }
    private Row revokeOld(Row row) throws FactoryException {
        if (row.state == State.DISPATCH_RESERVED) return row; // Recovery converts old-process reservation separately, never recalls it.
        if (row.state != State.ACCEPTED && row.state != State.DELIVERY_UNKNOWN) {
            Row changed = transition(row, State.REVOKED, "package_or_signer_changed", row.attempt);
            if (changed != null) row = changed;
        }
        if(row.kind==Kind.SCHEDULED)platform.cancelAlarm(row); return row;
    }
    private Row recoverPriorProcess(Row row) throws FactoryException {
        if(row.kind==Kind.IMMEDIATE && row.state==State.PENDING && !process.equals(row.process)) {
            Row cancelled=transition(row,State.CANCELLED,"process_ended_before_immediate_dispatch",row.attempt);
            if(cancelled!=null)store.forget(cancelled,false); // No effect/receipt/event was ever exposed.
            return null;
        }
        if ((row.state == State.DISPATCH_RESERVED || row.state == State.ARMING) && !process.equals(row.process)) {
            Plan recovered = NotificationScheduleMachine.recover(row.state);
            Row changed = transition(row, recovered.state, recovered.reason, row.attempt);
            return changed == null ? store.get(row.source.ownerFingerprint, row.recordId) : changed;
        }
        return row;
    }
    private Row run(Row row, boolean firing, Mode mode, Session session) throws FactoryException {
        if (row == null || terminal(row.state) || row.state == State.DISPATCH_RESERVED || row.state == State.ARMING) return row;
        if (!source.notificationsCapability || !source.schedulingCapability || (row.request.precision == NotificationScheduleRequest.Precision.EXACT && !source.exactCapability))
            return queueUnavailable(row,"current_artifact_capability_unavailable",session);
        if (!session.active()) return queueUnavailable(row, "receiver_deadline", session);
        if (firing && clock.epochMs() < row.request.atEpochMs) return row;
        if (clock.epochMs() > row.request.expiresAtEpochMs) return transitionOrCurrent(row, State.EXPIRED, "delivery_window_ended", session);
        // Persist a safe observable state BEFORE a host IPC can hang or the process can die.
        Row pending = transition(row, State.QUEUED_SESSION_UNAVAILABLE, "acquiring_host_session", session.attempt);
        if (pending == null) return current(row);
        Decision decision;
        try { decision = authority.acquire(pending, source, mode, session); }
        catch (FactoryException | RuntimeException unavailable) { return queueUnavailable(pending, "authenticated_host_unavailable", session); }
        if (decision == null) return queueUnavailable(pending, "authenticated_host_unavailable", session);
        try (Decision active = decision) {
            if (!session.active()) return queueUnavailable(pending, "receiver_deadline", session);
            boolean ready = platform.notificationsReady(pending.notification);
            Plan plan = firing ? NotificationScheduleMachine.fire(pending.request, pending.state, clock.epochMs(), active.sessionStatus, ready)
                    : NotificationScheduleMachine.resume(pending.request, pending.state, clock.epochMs(), active.sessionStatus, platform.exactAvailable(), ready);
            if (plan.effect == Effect.NONE) return transitionOrCurrent(pending, plan.state, plan.reason, session);
            try { active.lease.check(); session.check(); }
            catch (RuntimeException unavailable) { return queueUnavailable(pending, "live_session_check_failed", session); }
            if (plan.effect == Effect.POST) {
                synchronized (NotificationEffectGate.forId(source.appId, pending.notification.show.id)) {
                    session.check();
                    Row reserved = transition(pending, plan.state, plan.reason, session.attempt);
                    return reserved == null ? current(pending) : post(reserved, active.lease, session);
                }
            }
            synchronized (NotificationEffectGate.forSchedule(pending.token)) {
                session.check();
                Row reserved = transition(pending, plan.state, plan.reason, session.attempt);
                return reserved == null ? current(pending) : arm(reserved, active.lease, session);
            }
        }
    }
    private Row arm(Row row, Lease lease, Session session) throws FactoryException {
        try {
            lease.check(); session.check();
            Row before = current(row); if (!same(before, row)) return before;
            if (row.request.precision == NotificationScheduleRequest.Precision.EXACT && !platform.exactAvailable())
                return transitionOrCurrent(row, State.QUEUED_EXACT_PERMISSION, "android_exact_alarm_unavailable", session);
            platform.arm(row, () -> {
                lease.check(); session.check();
                try { if (!same(current(row),row)) throw new SecurityException("Stale alarm reservation"); }
                catch (FactoryException unavailable) { throw new SecurityException("Ledger unavailable",unavailable); }
                session.check();
            });
            Row after = transition(row, State.SCHEDULED, "alarm_manager_accepted", session.attempt);
            if (after == null) { platform.cancelAlarm(row); return current(row); }
            return after;
        } catch (FactoryException | RuntimeException uncertain) {
            boolean exactDenied = uncertain instanceof FactoryException && "EXACT_ALARM_PERMISSION_REQUIRED".equals(((FactoryException) uncertain).code);
            Row after = transition(row, exactDenied ? State.QUEUED_EXACT_PERMISSION : State.SCHEDULE_UNKNOWN,
                    exactDenied ? "android_exact_alarm_unavailable" : "alarm_result_uncertain_same_identity_only", session.attempt);
            Row current = after == null ? current(row) : after;
            if (current != null && terminal(current.state)) platform.cancelAlarm(row);
            return current;
        }
    }
    private Row post(Row row,Lease lease,Session session) throws FactoryException {
        return NotificationDispatch.post(store,platform,clock,process,row,lease::check,session);
    }
    private Row queueUnavailable(Row row, String reason, Session session) throws FactoryException {
        return transitionOrCurrent(row, State.QUEUED_SESSION_UNAVAILABLE, reason, session);
    }
    private Row transitionOrCurrent(Row row, State state, String reason, Session session) throws FactoryException {
        Row changed = transition(row, state, reason, session.attempt); return changed == null ? current(row) : changed;
    }
    private Row transition(Row row, State state, String reason, String attempt) throws FactoryException {
        return store.change(row, state, reason, process, attempt, clock.epochMs());
    }
    private Row current(Row row) throws FactoryException { return store.get(row.source.ownerFingerprint, row.recordId); }
    private static boolean same(Row a, Row b) { return a != null && a.revision == b.revision && a.token.equals(b.token); }
    private static FactoryException conflict() { return new FactoryException("CONFLICT", "Schedule ID already binds different content."); }
}
