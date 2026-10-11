package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;

/** Immediate own-package notifications. No scheduler, service, reboot receiver or policy store. */
public final class NativeNotifications {
    private static final String CHANNEL_PREFIX = "factory.";
    private final Context context;
    private final String appId, tag;

    /** Installed apps only: a host preview must use its simulated notification adapter. */
    public NativeNotifications(Context context, String appId) {
        if (context == null || appId == null || !appId.equals(context.getPackageName()))
            throw new IllegalArgumentException("Notification identity must match the installed package.");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.appId = appId;
        this.tag = "factory:" + appId;
    }

    /** A readiness snapshot, never a claim that a notification will be displayed or read. */
    public JSONObject status(String channelId) throws FactoryException {
        if (channelId != null) NotificationRequest.channelId(channelId);
        NotificationManager manager = manager();
        boolean available = manager != null;
        boolean required = Build.VERSION.SDK_INT >= 33;
        String permission = !required ? "not_required" : !permissionDeclared() ? "not_declared" :
                permissionGranted() ? "granted" : "not_granted";
        boolean enabled = false;
        String channelState = channelId == null ? "not_checked" : Build.VERSION.SDK_INT < 26 ? "not_required" : "missing";
        int importance = -1;
        if (available) {
            try {
                enabled = manager.areNotificationsEnabled();
                if (channelId != null && Build.VERSION.SDK_INT >= 26) {
                    NotificationChannel channel = manager.getNotificationChannel(platformChannelId(channelId));
                    if (channel != null) {
                        importance = channel.getImportance();
                        channelState = channelBlocked(manager, channel) ? "blocked" : "ready";
                    }
                }
            } catch (RuntimeException unavailable) { available = false; }
        }
        String state = !available ? "unavailable" : "not_declared".equals(permission) ? "permission_not_declared" :
                "not_granted".equals(permission) ? "permission_required" : !enabled ? "app_blocked" :
                "missing".equals(channelState) ? "channel_missing" : "blocked".equals(channelState) ? "channel_blocked" : "ready";
        try {
            JSONObject result = new JSONObject().put("status", state).put("available", available)
                    .put("permission", permission).put("runtimePermissionRequired", required)
                    .put("notificationsEnabled", enabled).put("channelsSupported", Build.VERSION.SDK_INT >= 26)
                    .put("channelStatus", channelState).put("ready", "ready".equals(state));
            if (channelId != null) result.put("channelId", channelId);
            if (importance >= 0) result.put("channelImportance", importance);
            return result;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Existing channels are retained unchanged, including user-selected importance and blocking. */
    public JSONObject createChannel(NotificationRequest.Channel request, NativeOperationAuthorization.Access authorization)
            throws FactoryException {
        require(authorization);
        if (request == null) throw invalid();
        NotificationManager manager = requireManager();
        if (Build.VERSION.SDK_INT < 26) return receipt("not_required", "channelId", request.id);
        NotificationChannel existing;
        try { existing = manager.getNotificationChannel(platformChannelId(request.id)); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        if (existing == null) {
            NotificationChannel channel = new NotificationChannel(platformChannelId(request.id), request.name, importance(request.importance));
            channel.setDescription(request.description);
            authorization.check();
            try { manager.createNotificationChannel(channel); }
            catch (RuntimeException unavailable) { throw unavailable(); }
        }
        JSONObject result = status(request.id);
        try { result.put("channelCreated", existing == null && result.optBoolean("available") && !"missing".equals(result.optString("channelStatus"))); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    /** The same app-scoped tag/id replaces an earlier notification; no delivery receipt is implied. */
    @SuppressWarnings("deprecation")
    public JSONObject show(NotificationRequest.Show request, NativeOperationAuthorization.Access authorization)
            throws FactoryException {
        require(authorization);
        if (request == null) throw invalid();
        requireReady(request.channelId);
        Intent launch = ownLaunchIntent();
        authorization.check();
        PendingIntent tap;
        try {
            tap = PendingIntent.getActivity(context, request.id, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (RuntimeException unavailable) { throw unavailable(); }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ?
                new Notification.Builder(context, platformChannelId(request.channelId)) : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(request.title).setContentText(request.body)
                .setStyle(new Notification.BigTextStyle().bigText(request.body))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPriority(priority(request.priority))
                .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(tap);
        if (Build.VERSION.SDK_INT < 26 && request.priority != NotificationRequest.Priority.LOW)
            builder.setDefaults(Notification.DEFAULT_SOUND);
        Notification notification;
        try { notification = builder.build(); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        // Re-read both permission and channel settings after construction and before the effect.
        requireReady(request.channelId);
        NotificationManager manager = requireManager();
        authorization.check();
        try { manager.notify(tag, request.id, notification); }
        catch (SecurityException denied) { throw new FactoryException("PERMISSION_DENIED", "Android did not permit this notification."); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        return receipt("accepted", "id", request.id);
    }

    /** Cancels only this helper's exact app-scoped ID; permission denial does not block cleanup. */
    public JSONObject cancel(int id, NativeOperationAuthorization.Access authorization) throws FactoryException {
        require(authorization);
        if (id < 1) throw invalid();
        NotificationManager manager = requireManager();
        authorization.check();
        try { manager.cancel(tag, id); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        return receipt("cancel_requested", "id", id);
    }

    /**
     * Requests the real Android 13+ permission when absent. This does not grant permission and
     * Android may suppress the dialog. The runtime owns onRequestPermissionsResult, verifies its
     * exact request/permission, rechecks status and the live authorization, then settles its callback.
     * Authorization must support the shared short live CHECK on main immediately before the
     * permission request; acquiring a lease or waiting for host approval stays off the UI thread.
     */
    public JSONObject requestPermission(Activity activity, int requestCode, NativeOperationAuthorization.Access authorization)
            throws FactoryException {
        require(authorization);
        if (Build.VERSION.SDK_INT < 33 || permissionGranted()) return status(null);
        if (!permissionDeclared()) throw new FactoryException("PERMISSION_NOT_DECLARED", "Declare POST_NOTIFICATIONS in this application's manifest.");
        if (activity == null || !appId.equals(activity.getPackageName()) || activity.isFinishing() || activity.isDestroyed()
                || requestCode < 0 || requestCode > 65535 || Looper.myLooper() != Looper.getMainLooper())
            throw new FactoryException("NOT_AVAILABLE", "Notification permission requires the current application activity.");
        if (context.getApplicationInfo().targetSdkVersion < 33)
            throw new FactoryException("NOT_SUPPORTED", "Explicit notification permission requests require target SDK 33 or newer.");
        authorization.check();
        try { activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        return receipt("permission_requested", "permissionGranted", false);
    }

    private NotificationManager manager() { return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE); }
    private NotificationManager requireManager() throws FactoryException {
        NotificationManager manager = manager();
        if (manager == null) throw unavailable();
        return manager;
    }
    private boolean permissionGranted() {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean permissionDeclared() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(appId, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null)
                for (String permission : info.requestedPermissions) if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { /* Fail closed. */ }
        return false;
    }
    private void requireReady(String channelId) throws FactoryException {
        String state = status(channelId).optString("status");
        if (!"ready".equals(state)) throw new FactoryException(
                state.startsWith("permission_") ? "PERMISSION_DENIED" : "NOT_AVAILABLE",
                "Notification unavailable: " + state + ".");
    }
    private Intent ownLaunchIntent() throws FactoryException {
        Intent discovered = context.getPackageManager().getLaunchIntentForPackage(appId);
        ComponentName component = discovered == null ? null : discovered.getComponent();
        if (component == null || !appId.equals(component.getPackageName()))
            throw new FactoryException("NOT_AVAILABLE", "This application has no safe launcher activity.");
        // Rebuild from the verified component. Do not copy data, extras, selectors or grant flags.
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(appId)
                .setComponent(component).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
    private static boolean channelBlocked(NotificationManager manager, NotificationChannel channel) {
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return true;
        if (Build.VERSION.SDK_INT >= 28 && channel.getGroup() != null) {
            NotificationChannelGroup group = manager.getNotificationChannelGroup(channel.getGroup());
            return group != null && group.isBlocked();
        }
        return false;
    }
    static String platformChannelId(String id) { return CHANNEL_PREFIX + id; }
    private static int importance(NotificationRequest.Priority value) {
        return value == NotificationRequest.Priority.LOW ? NotificationManager.IMPORTANCE_LOW :
                value == NotificationRequest.Priority.HIGH ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT;
    }
    private static int priority(NotificationRequest.Priority value) {
        return value == NotificationRequest.Priority.LOW ? Notification.PRIORITY_LOW :
                value == NotificationRequest.Priority.HIGH ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT;
    }
    private static void require(NativeOperationAuthorization.Access authorization) {
        if (authorization == null) throw new SecurityException("Live notification authorization required.");
        authorization.check();
    }
    private static JSONObject receipt(String status, String key, Object value) {
        try { return new JSONObject().put("status", status).put(key, value); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid notification request."); }
    private static FactoryException unavailable() { return new FactoryException("NOT_AVAILABLE", "Android notifications are unavailable."); }
}
