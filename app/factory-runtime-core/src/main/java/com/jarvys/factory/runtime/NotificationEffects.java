package com.jarvys.factory.runtime;

import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;
import org.json.JSONException;
import org.json.JSONObject;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;

/** Foreground notification facade. Data ownership and current effect authority are separate. */
class NotificationEffects {
    interface ExactAccess {
        JSONObject request(Activity activity, NativeOperationAuthorization.Access live) throws FactoryException;
        JSONObject status() throws FactoryException;
    }
    private final Context context;
    private final FactoryConfig config;
    NotificationEffects(Context context, FactoryConfig config) {
        if (context == null || config == null || !config.appId.equals(context.getPackageName())) throw new IllegalArgumentException();
        Context app = context.getApplicationContext(); this.context = app == null ? context : app; this.config = config;
    }

    /** OS facts only. No host policy query or receipt is needed to inspect current Android settings. */
    JSONObject status(String channelId) throws FactoryException {
        JSONObject result = new NativeNotifications(context, config.appId).status(channelId);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        boolean exact = false;
        try { exact = alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()); }
        catch (RuntimeException unavailable) { /* Report the actual unavailable special access. */ }
        try {
            return result.put("schedulerAvailable", config.notifications.scheduling && alarms != null)
                    .put("exactAlarmCapabilityDeclared", config.notifications.exactAlarms)
                    .put("exactAlarmAccess", !config.notifications.exactAlarms ? "not_declared" : Build.VERSION.SDK_INT < 31 ? "not_required" : exact ? "granted" : "not_granted")
                    .put("defaultPrecision", "inexact");
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    JSONObject show(NotificationScheduleRequest.Notification notification, NativeOperationAuthorization.Access live,
                    Session session) throws FactoryException {
        session.check(); live.check();
        if (notification.actions.isEmpty()) {
            // Ordinary show has no retained generation and needs no extra source-host round trip.
            String owner = owner();
            try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
                synchronized (NotificationEffectGate.forId(config.appId, notification.show.id)) {
                    session.check(); live.check(); ledger.invalidateVisibleActions(owner, notification.show.id);
                    return new NativeNotifications(context, config.appId).show(notification.show, () -> { session.check(); live.check(); });
                }
            }
        }
        Source source = NotificationInstalledEnvironment.source(context, config, session);
        session.check(); live.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            return new NativeNotificationActions(context, source, ledger).show(notification, () -> { session.check(); live.check(); });
        }
    }

    /** Own-data cleanup is allowed even when notifications.show/schedule was revoked or the host is offline. */
    JSONObject cancelVisible(int id, Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            synchronized (NotificationEffectGate.forId(config.appId, id)) {
                session.check(); ledger.invalidateVisibleActions(owner, id);
                return new NativeNotifications(context, config.appId).cancel(id, session::check);
            }
        }
    }

    Row storedSchedule(String id, Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            Row row = ledger.get(owner, id); session.check();
            return row != null && row.kind == Kind.SCHEDULED ? row : null;
        }
    }

    JSONObject schedule(NotificationScheduleRequest.Schedule request, Row resumed, NativeOperationAuthorization.Access live,
                        Session session) throws FactoryException {
        session.check(); live.check();
        Source source = NotificationInstalledEnvironment.source(context, config, session);
        session.check(); live.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            Authority authority = foregroundAuthority(request, resumed, source, live);
            FactoryNotificationServices.Environment environment = new FactoryNotificationServices.Environment(context, ledger, source, authority);
            if (resumed != null) {
                Row current = ledger.get(source.ownerFingerprint, request.scheduleId);
                if (current == null || !resumed.token.equals(current.token) || !request.fingerprint().equals(current.fingerprint))
                    throw new FactoryException("CONFLICT", "Scheduled notification changed while its original request was being reviewed.");
            }
            Row result = resumed == null ? environment.engine.schedule(request, Mode.FOREGROUND, session)
                    : environment.engine.resume(request.scheduleId, Mode.FOREGROUND, session);
            return details(result, request.scheduleId, null);
        }
    }

    /** Borrow the one already acquired grant. Closing an engine decision never closes the caller's lease. */
    static Authority foregroundAuthority(NotificationScheduleRequest.Schedule expected, Row resumed, Source source,
                                         NativeOperationAuthorization.Access live) {
        if (expected == null || source == null || live == null) throw new IllegalArgumentException();
        final String fingerprint = expected.fingerprint();
        return (row, currentSource, mode, session) -> {
            session.check();
            if (mode != Mode.FOREGROUND || row == null || row.request == null || currentSource == null ||
                    !source.fingerprint.equals(currentSource.fingerprint) || !source.ownerFingerprint.equals(row.source.ownerFingerprint) ||
                    !fingerprint.equals(row.fingerprint) || (resumed != null && !resumed.token.equals(row.token)))
                throw new SecurityException("Notification grant does not bind this immutable schedule.");
            live.check(); session.check();
            return new Decision(NotificationScheduleMachine.SessionStatus.ACTIVE, new Lease() {
                public void check() { session.check(); live.check(); session.check(); }
                public void close() { /* NativeEffects owns the foreground lease through bridge delivery. */ }
            });
        };
    }

    JSONObject scheduleStatus(String id, Session session) throws FactoryException {
        Row row = storedSchedule(id, session);
        JSONObject result = details(row, id, null);
        try { return result.put("android", status(row == null ? null : row.notification.show.channelId)); }
        catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    JSONObject cancelScheduled(String id, Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            Row row = ledger.get(owner, id); session.check();
            return details(row == null ? null : cleanupEngine(ledger, row).cancel(id), id, null);
        }
    }

    JSONObject forgetScheduled(NotificationScheduleControl.Forget request, Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            Row row = ledger.get(owner, request.scheduleId); session.check();
            boolean forgotten = row != null && cleanupEngine(ledger, row).forget(request.scheduleId, request.acknowledgeDeliveryUnknown);
            return retention("scheduleId", request.scheduleId, forgotten);
        }
    }

    JSONObject generationStatus(String id, Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            Row row = ownGeneration(ledger, owner, id); session.check(); return details(row, null, id);
        }
    }

    JSONObject forgetGeneration(NotificationScheduleControl.Generation request, Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            Row row = ownGeneration(ledger, owner, request.generationId); session.check();
            return retention("generationId", request.generationId, row != null && ledger.forget(row, request.acknowledgeDeliveryUnknown));
        }
    }

    JSONObject pollActions(Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            NotificationActionEvents.Event event = NotificationActionEvents.poll(ledger, owner); session.check();
            return new JSONObject().put("event", event == null ? JSONObject.NULL : event.details());
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    JSONObject acknowledgeAction(String eventId, Session session) throws FactoryException {
        String owner = owner(); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            boolean acknowledged = NotificationActionEvents.acknowledge(ledger, owner, eventId);
            return new JSONObject().put("eventId", eventId).put("acknowledged", acknowledged);
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    ExactAccess exactAccess(Session session) throws FactoryException {
        Source source = NotificationInstalledEnvironment.source(context, config, session);
        NativeScheduledNotifications platform = new NativeScheduledNotifications(context, source);
        return new ExactAccess() {
            public JSONObject request(Activity activity, NativeOperationAuthorization.Access live) throws FactoryException {
                session.check(); return platform.requestExactAlarmAccess(activity, () -> { session.check(); live.check(); });
            }
            public JSONObject status() throws FactoryException { return platform.status(null); }
        };
    }

    private NotificationScheduleEngine cleanupEngine(NotificationScheduleLedger ledger, Row row) {
        // Stored provenance is used only to address the old owned alarm, never to rearm or post it.
        return new NotificationScheduleEngine(ledger, (ignored, source, mode, session) -> {
            throw new SecurityException("Cleanup cannot acquire notification authority.");
        }, new NativeScheduledNotifications(context, row.source), FactoryNotificationServices.CLOCK, row.source, FactoryNotificationServices.PROCESS);
    }

    private static Row ownGeneration(NotificationScheduleLedger ledger, String owner, String id) throws FactoryException {
        Row row = ledger.byAlarmToken(id);
        return row != null && row.kind == Kind.IMMEDIATE && owner.equals(row.source.ownerFingerprint) ? row : null;
    }

    /** Local package identity proves access to private owned data, never authority to create an effect. */
    private String owner() throws FactoryException { return owner(context, config.appId); }
    @SuppressWarnings("deprecation") static String owner(Context context, String expectedAppId) throws FactoryException {
        try {
            if (context == null || !context.getPackageName().equals(expectedAppId)) throw new SecurityException();
            PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            Signature[] signers = Build.VERSION.SDK_INT >= 28 ? own.signingInfo == null ? null : own.signingInfo.getApkContentsSigners() : own.signatures;
            if (!expectedAppId.equals(own.packageName) || signers == null || signers.length != 1) throw new SecurityException();
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
            StringBuilder certificate = new StringBuilder(64);
            for (byte value : bytes) certificate.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return NotificationScheduleIdentity.digest("factory-notification-owner-v1", expectedAppId, certificate.toString());
        } catch (Exception unavailable) {
            throw new FactoryException("SOURCE_UNAVAILABLE", "Current application signing identity is unavailable.");
        }
    }

    /** Inert action ingress works after policy revocation/host loss; extras never enter the event. */
    static NotificationActionEvents.Event acceptAction(Context context, android.content.Intent intent, Session session) throws FactoryException {
        session.check(); String token = NativeScheduledNotifications.actionToken(context, intent);
        if (token == null) return null;
        String owner = owner(context, context.getPackageName()); session.check();
        try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(context)) {
            session.check(); return NotificationActionEvents.acceptTap(ledger, owner, token, FactoryNotificationServices.CLOCK.epochMs());
        } catch (NotificationLedgerCapacityException full) {
            throw new FactoryException("CAPACITY_EXCEEDED", "Notification action inbox is full; acknowledge pending events and retry this action.");
        }
    }

    private static JSONObject details(Row row, String schedule, String generation) {
        if (row != null) return row.details();
        try {
            JSONObject result = new JSONObject().put("status", "not_found");
            return schedule != null ? result.put("scheduleId", schedule) : result.put("generationId", generation);
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }
    private static JSONObject retention(String key, String id, boolean forgotten) {
        try { return new JSONObject().put(key, id).put("forgotten", forgotten).put("status", forgotten ? "forgotten" : "not_found"); }
        catch (JSONException impossible) { throw new AssertionError(impossible); }
    }
}
