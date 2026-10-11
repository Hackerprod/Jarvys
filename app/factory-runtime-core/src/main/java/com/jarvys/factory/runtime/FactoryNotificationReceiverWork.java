package com.jarvys.factory.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;

/** Receiver lifetime ends independently of a blocked host Binder; late workers are fenced. */
final class FactoryNotificationReceiverWork {
    static final long BUDGET_MS = 8000;
    // Persistence never waits behind host Binder. 1024 is the signed schema's maximum live schedules.
    private static final ThreadPoolExecutor INGRESS = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(1025));
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(16));
    private static final ScheduledThreadPoolExecutor WATCH = new ScheduledThreadPoolExecutor(1);
    private static final ConcurrentHashMap<String,Session> ACTIVE = new ConcurrentHashMap<>();
    static { INGRESS.allowCoreThreadTimeOut(true); WORK.allowCoreThreadTimeOut(true); WATCH.setRemoveOnCancelPolicy(true); }
    static void run(Context context, BroadcastReceiver.PendingResult pending, String alarmToken, boolean exactGrant) {
        Context app = context.getApplicationContext(); final Context owner = app == null ? context : app;
        Session session = new Session(FactoryNotificationServices.CLOCK,BUDGET_MS);
        String key=alarmToken == null ? "recovery" : alarmToken;
        if (ACTIVE.size() >= 1025 || ACTIVE.putIfAbsent(key,session) != null) { pending.finish(); return; }
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            session.invalidate(); ACTIVE.remove(key,session);
            if (finished.compareAndSet(false,true)) pending.finish();
        };
        java.util.concurrent.ScheduledFuture<?> timeout = WATCH.schedule(finish,BUDGET_MS,TimeUnit.MILLISECONDS);
        Runnable work = () -> {
            try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(owner)) {
                if (!session.active()) return;
                FactoryNotificationServices.Environment environment = FactoryNotificationServices.open(owner,ledger,session);
                if (environment == null || !session.active()) return;
                if (alarmToken != null) environment.engine.fire(alarmToken,session);
                else if (!exactGrant || environment.source.exactCapability) {
                    String cursor = ledger.recoveryCursor();
                    while (session.active()) {
                        String next = environment.engine.recoverPage(cursor,16,session);
                        if (next == null || next.equals(cursor)) break;
                        cursor = next;
                    }
                }
            } catch (FactoryException | RuntimeException unavailable) {
                android.util.Log.w("FactoryNotifications","Background notification work remains unconfirmed.");
            } finally { timeout.cancel(false); finish.run(); }
        };
        try {
            INGRESS.execute(() -> {
                try (NotificationScheduleLedger ledger = new NotificationScheduleLedger(owner)) {
                    // Persist even if the work budget expired while waiting: no external effect is performed here.
                    if (alarmToken != null) ledger.queueUnavailable(alarmToken,FactoryNotificationServices.PROCESS,System.currentTimeMillis());
                    if (!session.active()) return;
                    try { WORK.execute(work); }
                    catch (java.util.concurrent.RejectedExecutionException busy) { timeout.cancel(false); finish.run(); }
                } catch (FactoryException | RuntimeException unavailable) {
                    android.util.Log.w("FactoryNotifications","Notification ledger unavailable; delivery was not attempted.");
                    timeout.cancel(false); finish.run();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException impossibleAtLiveScheduleLimit) {
            // Invalid/internal broadcast floods cannot create unbounded work or effects. A storage status remains unconfirmed.
            timeout.cancel(false); finish.run();
        }
    }
    private FactoryNotificationReceiverWork() { }
}
