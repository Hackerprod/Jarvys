package com.jarvys.factory.runtime;

import android.content.Context;
import android.os.SystemClock;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;

/** Closed native integration seam. It must resolve on a cold receiver process, before any Activity. */
public final class FactoryNotificationServices {
    public static final String PROCESS = NotificationActionEvents.randomToken();
    public static final Clock CLOCK = new Clock() {
        @Override public long epochMs() { return System.currentTimeMillis(); }
        @Override public long elapsedMs() { return SystemClock.elapsedRealtime(); }
    };
    public interface Resolver {
        /** Resolve this app's package identity and packaged capabilities/budgets; no UI or WebView. */
        Environment resolve(Context application, NotificationScheduleLedger ledger, Session session) throws FactoryException;
    }
    public static final class Environment {
        public final Source source;
        public final NotificationScheduleEngine engine;
        public final NativeScheduledNotifications platform;
        public Environment(Context context, NotificationScheduleLedger ledger, Source source, Authority authority) {
            this.source = source; ledger.bindCurrentSource(source); platform = new NativeScheduledNotifications(context,source);
            engine = new NotificationScheduleEngine(ledger,authority,platform,CLOCK,source,PROCESS);
        }
    }
    /** Package-private test seam only; production must wire resolveInstalled directly, not rely on Activity registration. */
    static volatile Resolver testResolver;
    static Environment open(Context context, NotificationScheduleLedger ledger, Session session) throws FactoryException {
        session.check(); Resolver resolver = testResolver;
        Environment result = resolver == null ? resolveInstalled(context,ledger,session) : resolver.resolve(context,ledger,session);
        session.check(); return result;
    }
    private static Environment resolveInstalled(Context context, NotificationScheduleLedger ledger, Session session) throws FactoryException {
        return NotificationInstalledEnvironment.resolve(context, ledger, session);
    }
    private FactoryNotificationServices() { }
}
