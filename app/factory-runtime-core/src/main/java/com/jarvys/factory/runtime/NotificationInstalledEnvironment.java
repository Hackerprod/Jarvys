package com.jarvys.factory.runtime;

import android.content.Context;
import com.jarvys.factory.contract.ResourceBudgets;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;

/** Cold-process adapter to the same authenticated source and live operation policy used by foreground APIs. */
final class NotificationInstalledEnvironment {
    static FactoryConfig configuration(Context context) throws FactoryException {
        try {
            return FactoryConfig.parse(FactoryRuntime.readConfiguration(context.getAssets().open("factory-app.json")), context.getPackageName());
        } catch (java.io.IOException | RuntimeException unavailable) {
            throw new FactoryException("SOURCE_UNAVAILABLE", "Installed notification configuration is unavailable.");
        }
    }
    static Source source(Context context, FactoryConfig config, Session session) throws FactoryException {
        session.check();
        NativeInstalledSource proof = NativeInstalledSource.describe(context, config, "notifications", session.deadlineElapsedMs, session::active);
        ResourceBudgets.Notifications limits = proof.resources.notifications;
        session.check();
        return new Source(proof.appId, proof.certificateSha256, proof.apkSha256, proof.recordSha256, proof.versionCode,
                config.capabilities.contains("notifications"), config.notifications.scheduling, config.notifications.exactAlarms,
                new Limits(limits.schedules, limits.inboxEvents, limits.tombstones, limits.storageBytes));
    }
    static FactoryNotificationServices.Environment resolve(Context context, NotificationScheduleLedger ledger, Session session) throws FactoryException {
        FactoryConfig config = configuration(context);
        Source source = source(context, config, session);
        Authority authority = (row, currentSource, mode, active) -> {
            active.check();
            if (mode != Mode.BACKGROUND_NO_UI || row == null || row.request == null ||
                    !source.fingerprint.equals(currentSource.fingerprint) || !source.ownerFingerprint.equals(row.source.ownerFingerprint))
                return new Decision(NotificationScheduleMachine.Policy.UNAVAILABLE, null);
            NativeOperationRequest request = NativeOperationRequest.parse("notifications.schedule", row.request.authorizationDetails());
            NativeBackgroundAuthorization.Result result = NativeBackgroundAuthorization.acquire(context, config, request,
                    new NativeBackgroundAuthorization.SourceIdentity(source.appId, source.certificateSha256, source.artifactSha256, source.receiptSha256, source.versionCode),
                    active.deadlineElapsedMs, active::active);
            if (result.decision == NativeBackgroundAuthorization.Decision.ASK) {
                result.close(); return new Decision(NotificationScheduleMachine.Policy.ASK, null);
            }
            if (result.decision == NativeBackgroundAuthorization.Decision.DENY) {
                result.close(); return new Decision(NotificationScheduleMachine.Policy.DENIED, null);
            }
            try {
                active.check(); result.check();
                return new Decision(NotificationScheduleMachine.Policy.ALLOWED, new Lease() {
                    public void check() { active.check(); result.check(); active.check(); }
                    public void close() { result.close(); }
                });
            } catch (RuntimeException ended) { result.close(); throw ended; }
        };
        return new FactoryNotificationServices.Environment(context, ledger, source, authority);
    }
    private NotificationInstalledEnvironment() { }
}
