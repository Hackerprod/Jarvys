package com.jarvys.factory.runtime;

import android.Manifest;
import androidx.annotation.RequiresPermission;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import java.util.List;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;

/** Actual AlarmManager and own-Activity actions. No background application callbacks or network. */
public final class NativeScheduledNotifications implements Platform {
    public static final String ACTIVITY = "com.jarvys.factory.runtime.FactoryActivity";
    public static final String ALARM_RECEIVER = "com.jarvys.factory.runtime.FactoryNotificationAlarmReceiver";
    public static final String RECOVERY_RECEIVER = "com.jarvys.factory.runtime.FactoryNotificationRecoveryReceiver";
    private static final String SCHEME = "factory-notification";
    private final Context context;
    private final Source source;
    private final NativeNotifications immediate;
    public NativeScheduledNotifications(Context context, Source source) {
        if (context == null || source == null || !context.getPackageName().equals(source.appId)) throw new IllegalArgumentException();
        Context app = context.getApplicationContext(); this.context = app == null ? context : app;
        this.source = source; immediate = new NativeNotifications(this.context, source.appId);
    }
    /** Factual OS snapshot, not a policy grant, display receipt or timing guarantee. */
    public org.json.JSONObject status(String channelId) throws FactoryException {
        org.json.JSONObject result=immediate.status(channelId);
        try { return result.put("schedulerAvailable",source.schedulingCapability&&alarms()!=null).put("exactAlarmCapabilityDeclared",source.exactCapability)
                .put("exactAlarmAccess",!source.exactCapability?"not_declared":Build.VERSION.SDK_INT<31?"not_required":exactAvailable()?"granted":"not_granted")
                .put("backgroundApprovalUi",false).put("defaultPrecision","inexact"); }
        catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }
    /** Genuine foreground special-access screen; caller must reread status in onResume. */
    public org.json.JSONObject requestExactAlarmAccess(android.app.Activity activity, NativeOperationAuthorization.Access authorization) throws FactoryException {
        if (authorization==null) throw new SecurityException("Live authorization required");
        if (!source.exactCapability) throw new FactoryException("CAPABILITY_REQUIRED","Exact alarms are not declared by this artifact.");
        if (Build.VERSION.SDK_INT<31 || exactAvailable()) return status(null);
        if (activity==null || !source.appId.equals(activity.getPackageName()) || activity.isFinishing() || activity.isDestroyed()
                || android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) throw unavailable();
        Intent settings=new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:"+source.appId));
        authorization.check();
        try { activity.startActivity(settings); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        org.json.JSONObject result=status(null);
        try { return result.put("exactAlarmAccessRequested",true); }
        catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }
    @Override public boolean exactAvailable() {
        if (!source.exactCapability) return false;
        AlarmManager alarms = alarms();
        try { return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()); }
        catch (RuntimeException denied) { return false; }
    }
    @Override public boolean notificationsReady(NotificationScheduleRequest.Notification notification) {
        try { return immediate.status(notification.show.channelId).optBoolean("ready",false); }
        catch (FactoryException | RuntimeException unavailable) { return false; }
    }
    @Override public void arm(Row row, Checkpoint checkpoint) throws FactoryException {
        own(row); AlarmManager alarms = alarms();
        if (alarms == null) throw unavailable();
        boolean exact = row.request.precision == NotificationScheduleRequest.Precision.EXACT;
        if (exact && !exactAvailable()) throw exactRequired();
        PendingIntent operation;
        try {
            operation = PendingIntent.getBroadcast(context,0,alarmIntent(context,row.token),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            checkpoint.check();
            if (exact && row.request.allowWhileIdle) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,row.request.atEpochMs,operation);
            else if (exact) alarms.setExact(AlarmManager.RTC_WAKEUP,row.request.atEpochMs,operation);
            else if (row.request.allowWhileIdle) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,row.request.atEpochMs,operation);
            else alarms.set(AlarmManager.RTC_WAKEUP,row.request.atEpochMs,operation);
        } catch (SecurityException denied) { if (exact && !exactAvailable()) throw exactRequired(); throw unavailable(); }
        catch (RuntimeException unavailable) { throw unavailable(); }
    }
    @Override public void cancelAlarm(Row row) throws FactoryException {
        // Cleanup uses stable private identity even for a replaced artifact; no authorization grant is needed.
        if (row == null || !context.getPackageName().equals(row.source.appId)) throw new SecurityException();
        try {
            PendingIntent operation = PendingIntent.getBroadcast(context,0,alarmIntent(context,row.token),PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
            if (operation == null) return;
            AlarmManager alarms = alarms(); if (alarms == null) throw unavailable();
            alarms.cancel(operation); operation.cancel();
        } catch (RuntimeException unavailable) { throw unavailable(); }
    }
    // POST_NOTIFICATIONS applies on API 33+; both requireReady checks preserve the real OS gate.
    @RequiresPermission(value = Manifest.permission.POST_NOTIFICATIONS, conditional = true)
    @Override @SuppressWarnings("deprecation") public void post(Row row, List<ActionToken> actions, Checkpoint checkpoint) throws FactoryException {
        own(row); if (checkpoint == null || actions == null || actions.size() != row.notification.actions.size()) throw new IllegalArgumentException();
        NotificationRequest.Show request = row.notification.show;
        requireReady(row.notification);
        PendingIntent content = PendingIntent.getActivity(context,0,ownActivity(null),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context,NativeNotifications.platformChannelId(request.channelId)) : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(request.title).setContentText(request.body)
                .setStyle(new Notification.BigTextStyle().bigText(request.body)).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPriority(priority(request.priority)).setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(content);
        if (Build.VERSION.SDK_INT < 26 && request.priority != NotificationRequest.Priority.LOW) builder.setDefaults(Notification.DEFAULT_SOUND);
        for (ActionToken action : actions) {
            PendingIntent tap = PendingIntent.getActivity(context,0,ownActivity(action.token),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view,action.action.title,tap).build());
        }
        Notification built = builder.build();
        requireReady(row.notification);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) throw unavailable();
        checkpoint.check(); // Live host Binder then cancellation/revision gate, immediately before the single effect.
        manager.notify("factory:"+source.appId,request.id,built);
    }
    /** Cold onCreate and warm onNewIntent must call this after trusted artifact verification. */
    public NotificationActionEvents.Event acceptAction(Intent incoming, NotificationScheduleLedger ledger, long now) throws FactoryException {
        String token = actionToken(context,incoming);
        try { return token == null ? null : NotificationActionEvents.acceptTap(ledger,source.ownerFingerprint,token,now); }
        catch (NotificationLedgerCapacityException full) {
            throw new FactoryException("CAPACITY_EXCEEDED","Notification action inbox is full; acknowledge pending events and retry this action.");
        }
    }
    private Intent ownActivity(String token) throws FactoryException {
        ComponentName component = new ComponentName(source.appId,ACTIVITY);
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(component,0);
            if (!info.enabled || info.applicationInfo == null || !info.applicationInfo.enabled || !source.appId.equals(info.packageName)) throw unavailable();
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) { throw unavailable(); }
        Intent intent = new Intent(token == null ? Intent.ACTION_MAIN : actionAction(source.appId)).setPackage(source.appId).setComponent(component)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (token == null) intent.addCategory(Intent.CATEGORY_LAUNCHER);
        else intent.setData(uri(source.appId,"action",token));
        return intent;
    }
    public static Intent alarmIntent(Context context, String token) {
        if (!NotificationActionEvents.validToken(token)) throw new IllegalArgumentException();
        String app = context.getPackageName();
        return new Intent(alarmAction(app)).setPackage(app).setComponent(new ComponentName(app,ALARM_RECEIVER)).setData(uri(app,"alarm",token));
    }
    public static String alarmToken(Context context, Intent intent) { return lookup(context,intent,ALARM_RECEIVER,alarmAction(context.getPackageName()),"alarm"); }
    public static String actionToken(Context context, Intent intent) { return lookup(context,intent,ACTIVITY,actionAction(context.getPackageName()),"action"); }
    private static String lookup(Context context, Intent intent, String className, String action, String kind) {
        if (intent == null || !action.equals(intent.getAction()) || !context.getPackageName().equals(intent.getPackage())
                || !new ComponentName(context.getPackageName(),className).equals(intent.getComponent())) return null;
        Uri data = intent.getData();
        if (data == null || data.toString().length() > 1024 || !SCHEME.equals(data.getScheme()) || !context.getPackageName().equals(data.getAuthority())
                || data.getQuery() != null || data.getFragment() != null) return null;
        List<String> parts = data.getPathSegments();
        if (parts.size()!=2 || !kind.equals(parts.get(0)) || !NotificationActionEvents.validToken(parts.get(1))) return null;
        // Exact canonical spelling rejects escaped path aliases and ambiguous lookup identities.
        return uri(context.getPackageName(),kind,parts.get(1)).equals(data) ? parts.get(1) : null;
    }
    private static Uri uri(String app,String kind,String token) { return new Uri.Builder().scheme(SCHEME).authority(app).appendPath(kind).appendPath(token).build(); }
    public static String alarmAction(String app) { return app+".factory.notification.ALARM"; }
    public static String actionAction(String app) { return app+".factory.notification.ACTION"; }
    private void own(Row row) { if (row == null || !source.ownerFingerprint.equals(row.source.ownerFingerprint)) throw new SecurityException("Wrong scheduled artifact"); }
    private AlarmManager alarms() { return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE); }
    private void requireReady(NotificationScheduleRequest.Notification notification) throws FactoryException {
        if (!notificationsReady(notification)) throw new FactoryException("NOT_AVAILABLE","Android notification permission/channel is blocked.");
    }
    private static int priority(NotificationRequest.Priority value) {
        return value == NotificationRequest.Priority.LOW ? Notification.PRIORITY_LOW : value == NotificationRequest.Priority.HIGH ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT;
    }
    private static FactoryException exactRequired() { return new FactoryException("EXACT_ALARM_PERMISSION_REQUIRED","Android exact-alarm access is required; no inexact fallback was used."); }
    private static FactoryException unavailable() { return new FactoryException("NOT_AVAILABLE","Android scheduled notifications are unavailable."); }
}
