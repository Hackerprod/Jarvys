package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import static org.junit.Assert.*;

/** Synthetic framework notifications only. Never posts to a physical device or user account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 26, 33, 35}, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeNotificationsTest {
    private static final NativeOperationAuthorization.Access ALLOW = () -> { };
    public static class Screen extends Activity { }
    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    public static class ForeignLauncherPackageManager extends ShadowApplicationPackageManager {
        @Implementation @Override protected Intent getLaunchIntentForPackage(String packageName) {
            // The standard shadow pins the returned component to packageName, even when a fake
            // ResolveInfo names another package. Inject at the exact platform return boundary.
            return new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("other.application", "other.application.MainActivity"));
        }
    }
    private Screen screen;
    private NativeNotifications notifications;
    private NotificationManager manager;
    private ShadowNotificationManager platform;
    private String appId, tag;

    @Before public void setup() throws Exception {
        screen = Robolectric.buildActivity(Screen.class).setup().get();
        appId = screen.getPackageName(); tag = "factory:" + appId;
        declared(true);
        screen.getApplicationInfo().targetSdkVersion = 35;
        screen.getApplication().getApplicationInfo().targetSdkVersion = 35;
        Shadows.shadowOf(screen.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        manager = (NotificationManager) screen.getSystemService(Context.NOTIFICATION_SERVICE);
        platform = Shadows.shadowOf(manager); platform.setNotificationsEnabled(true);
        launcher(appId);
        notifications = new NativeNotifications(screen, appId);
    }

    @After public void cleanup() { screen.finish(); }

    @Test public void enforcesInstalledAppIdentityAndNeverRequiresAnExtraHumanPrompt() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NativeNotifications(screen, "other.application"));
        assertThrows(IllegalArgumentException.class, () -> new NativeNotifications(null, appId));
        assertEquals("ready", notifications.status(null).getString("status"));
        assertEquals(Build.VERSION.SDK_INT >= 33 ? "granted" : "not_required", notifications.status(null).getString("permission"));
        notifications.createChannel(channel("default"), ALLOW);
        assertEquals("accepted", notifications.show(show(7, "First"), ALLOW).getString("status"));
        assertNull(Shadows.shadowOf(screen).getLastRequestedPermission());
    }

    @Test public void stableAppScopedIdsUpdateAndCancelOnlyTheExactNotification() throws Exception {
        notifications.createChannel(channel("default"), ALLOW);
        JSONObject first = notifications.show(show(7, "First"), ALLOW);
        assertEquals(7, first.getInt("id")); assertFalse(first.has("delivered")); assertFalse(first.has("seen"));
        notifications.show(show(7, "Updated"), ALLOW);
        assertEquals(1, platform.size()); assertEquals("Updated", platform.getNotification(tag, 7).extras.getString(Notification.EXTRA_TITLE));
        notifications.show(show(8, "Second"), ALLOW);
        manager.notify("unrelated", 7, new Notification());
        assertEquals(3, platform.size());
        assertEquals("cancel_requested", notifications.cancel(7, ALLOW).getString("status"));
        assertNull(platform.getNotification(tag, 7)); assertNotNull(platform.getNotification(tag, 8));
        assertNotNull(platform.getNotification("unrelated", 7));
        assertEquals("cancel_requested", new NativeNotifications(screen, appId).cancel(8, ALLOW).getString("status"));
        assertNull(platform.getNotification(tag, 8));
        notifications.cancel(7, ALLOW); assertEquals(1, platform.size());
    }

    @Test public void tapsAreImmutableExplicitOwnPackageLaunchesWithNoCallerPayload() throws Exception {
        notifications.createChannel(channel("default"), ALLOW);
        notifications.show(show(17, "Tap"), ALLOW);
        Notification sent = platform.getNotification(tag, 17);
        ShadowPendingIntent tap = Shadows.shadowOf(sent.contentIntent);
        assertTrue(tap.isActivityIntent()); assertEquals(17, tap.getRequestCode());
        assertTrue((tap.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        assertTrue((tap.getFlags() & PendingIntent.FLAG_UPDATE_CURRENT) != 0);
        Intent intent = tap.getSavedIntent();
        assertEquals(appId, intent.getPackage()); assertEquals(appId, intent.getComponent().getPackageName());
        assertEquals(Intent.ACTION_MAIN, intent.getAction()); assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertNull(intent.getData()); assertNull(intent.getExtras()); assertNull(intent.getSelector()); assertNull(intent.getClipData());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, intent.getFlags());
        assertNull(sent.fullScreenIntent); assertEquals(Notification.VISIBILITY_PRIVATE, sent.visibility);
        assertTrue((sent.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertTrue((sent.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertNull(Shadows.shadowOf(screen).getNextStartedActivity());
    }

    @Test public void deniedOrRevokedAuthorizationStopsEachNativeEffect() throws Exception {
        NativeOperationAuthorization.Access denied = () -> { throw new SecurityException("revoked"); };
        assertThrows(SecurityException.class, () -> notifications.createChannel(channel("default"), denied));
        notifications.createChannel(channel("default"), ALLOW);
        assertThrows(SecurityException.class, () -> notifications.show(show(7, "Denied"), denied));
        assertThrows(SecurityException.class, () -> notifications.show(show(7, "Absent lease"), null));
        assertEquals(0, platform.size());
        AtomicInteger checks = new AtomicInteger();
        assertThrows(SecurityException.class, () -> notifications.show(show(7, "Revoked before notify"), () -> {
            if (checks.incrementAndGet() == 3) throw new SecurityException("revoked");
        }));
        assertEquals(3, checks.get()); assertEquals(0, platform.size());
        notifications.show(show(7, "Kept"), ALLOW);
        assertThrows(SecurityException.class, () -> notifications.cancel(7, denied));
        assertNotNull(platform.getNotification(tag, 7));
    }

    @Test public void appBlockingIsReportedAndCancellationStillWorks() throws Exception {
        notifications.createChannel(channel("default"), ALLOW); notifications.show(show(7, "Kept"), ALLOW);
        platform.setNotificationsEnabled(false);
        assertEquals("app_blocked", notifications.status("probe").getString("status"));
        assertThrows(FactoryException.class, () -> notifications.show(show(8, "Blocked"), ALLOW));
        assertNull(platform.getNotification(tag, 8));
        notifications.cancel(7, ALLOW); assertNull(platform.getNotification(tag, 7));
    }

    @Test public void missingPlatformServiceIsExplicitlyUnavailableWithoutEffects() throws Exception {
        Context absent = new ContextWrapper(screen) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return Context.NOTIFICATION_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
        };
        NativeNotifications unavailable = new NativeNotifications(absent, appId);
        assertEquals("unavailable", unavailable.status(null).getString("status"));
        assertFalse(unavailable.status(null).getBoolean("available"));
        assertThrows(FactoryException.class, () -> unavailable.createChannel(channel("default"), ALLOW));
        assertThrows(FactoryException.class, () -> unavailable.show(show(7, "Unavailable"), ALLOW));
        assertThrows(FactoryException.class, () -> unavailable.cancel(7, ALLOW));
        assertEquals(0, platform.size());
    }

    @Test public void realAndroidGrantIsRequiredAndRecheckedBeforeNotify() throws Exception {
        if (Build.VERSION.SDK_INT < 33) return;
        notifications.createChannel(channel("default"), ALLOW);
        Shadows.shadowOf(screen.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals("permission_required", notifications.status("probe").getString("status"));
        assertFalse(notifications.status("probe").getBoolean("ready"));
        assertThrows(FactoryException.class, () -> notifications.show(show(7, "Blocked"), ALLOW));
        Shadows.shadowOf(screen.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(FactoryException.class, () -> notifications.show(show(7, "Revoked during construction"), () -> {
            if (checks.incrementAndGet() == 2)
                Shadows.shadowOf(screen.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        }));
        assertEquals(0, platform.size());
    }

    @Test public void missingPermissionDeclarationIsDistinctFromDeniedGrant() throws Exception {
        if (Build.VERSION.SDK_INT < 33) return;
        declared(false);
        Shadows.shadowOf(screen.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals("not_declared", notifications.status(null).getString("permission"));
        assertEquals("permission_not_declared", notifications.status(null).getString("status"));
        assertEquals("PERMISSION_NOT_DECLARED", assertThrows(FactoryException.class,
                () -> notifications.requestPermission(screen, 41, ALLOW)).code);
        assertNull(Shadows.shadowOf(screen).getLastRequestedPermission());
    }

    @Test public void permissionRequestUsesExactSystemPermissionAndNeverFabricatesGrant() throws Exception {
        if (Build.VERSION.SDK_INT < 33) {
            assertEquals("not_required", notifications.requestPermission(screen, 41, ALLOW).getString("permission"));
            assertNull(Shadows.shadowOf(screen).getLastRequestedPermission()); return;
        }
        Shadows.shadowOf(screen.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        JSONObject result = notifications.requestPermission(screen, 41, ALLOW);
        assertEquals("permission_requested", result.getString("status")); assertFalse(result.getBoolean("permissionGranted"));
        assertEquals(41, Shadows.shadowOf(screen).getLastRequestedPermission().requestCode);
        assertArrayEquals(new String[]{Manifest.permission.POST_NOTIFICATIONS}, Shadows.shadowOf(screen).getLastRequestedPermission().requestedPermissions);
        assertEquals("not_granted", notifications.status(null).getString("permission"));
        assertEquals(0, platform.size());
    }

    @Test public void permissionRequestRequiresFreshLeaseAndLiveActivityButAlreadyGrantedSkipsPrompt() throws Exception {
        assertEquals("ready", notifications.requestPermission(screen, 41, ALLOW).getString("status"));
        assertNull(Shadows.shadowOf(screen).getLastRequestedPermission());
        if (Build.VERSION.SDK_INT < 33) return;
        Shadows.shadowOf(screen.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(SecurityException.class, () -> notifications.requestPermission(screen, 41, () -> {
            if (checks.incrementAndGet() == 2) throw new SecurityException("revoked");
        }));
        assertNull(Shadows.shadowOf(screen).getLastRequestedPermission());
        assertThrows(FactoryException.class, () -> notifications.requestPermission(screen, -1, ALLOW));
        screen.finish();
        assertThrows(FactoryException.class, () -> notifications.requestPermission(screen, 41, ALLOW));
        assertNull(Shadows.shadowOf(screen).getLastRequestedPermission());
    }

    @Test public void modernChannelsAreRequiredAndExistingUserSettingsArePreserved() throws Exception {
        if (Build.VERSION.SDK_INT < 26) {
            assertEquals("not_required", notifications.createChannel(channel("high"), ALLOW).getString("status"));
            assertEquals("not_required", notifications.status("probe").getString("channelStatus"));
            return;
        }
        assertEquals("channel_missing", notifications.status("probe").getString("status"));
        assertThrows(FactoryException.class, () -> notifications.show(show(7, "No channel"), ALLOW));
        JSONObject created = notifications.createChannel(channel("high"), ALLOW);
        assertTrue(created.getBoolean("channelCreated"));
        NotificationChannel original = manager.getNotificationChannel("factory.probe");
        assertEquals(NotificationManager.IMPORTANCE_HIGH, original.getImportance());
        assertFalse(notifications.createChannel(channel("low"), ALLOW).getBoolean("channelCreated"));
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("factory.probe").getImportance());
        notifications.show(show(7, "Channel"), ALLOW);
        assertEquals("factory.probe", platform.getNotification(tag, 7).getChannelId());
    }

    @Test public void blockedChannelCannotBeRaisedOrBypassedByShowPriority() throws Exception {
        if (Build.VERSION.SDK_INT < 26) return;
        manager.createNotificationChannel(new NotificationChannel("factory.probe", "User blocked", NotificationManager.IMPORTANCE_NONE));
        JSONObject result = notifications.createChannel(channel("high"), ALLOW);
        assertFalse(result.getBoolean("channelCreated")); assertEquals("channel_blocked", result.getString("status"));
        assertThrows(FactoryException.class, () -> notifications.show(show(7, "High priority"), ALLOW));
        assertEquals(0, platform.size());
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel("factory.probe").getImportance());
    }

    @Test public void creatingChannelRechecksLeaseBeforeFrameworkMutation() throws Exception {
        if (Build.VERSION.SDK_INT < 26) return;
        AtomicInteger checks = new AtomicInteger();
        assertThrows(SecurityException.class, () -> notifications.createChannel(channel("default"), () -> {
            if (checks.incrementAndGet() == 2) throw new SecurityException("revoked");
        }));
        assertNull(manager.getNotificationChannel("factory.probe"));
    }

    @Test @Config(shadows = ForeignLauncherPackageManager.class)
    public void foreignLauncherComponentNeverGetsADelegatedPendingIntent() throws Exception {
        notifications.createChannel(channel("default"), ALLOW);
        assertEquals("other.application", screen.getPackageManager().getLaunchIntentForPackage(appId).getComponent().getPackageName());
        assertThrows(FactoryException.class, () -> notifications.show(show(7, "Bad launcher"), ALLOW));
        assertEquals(0, platform.size());
    }

    private void declared(boolean declared) throws Exception {
        PackageInfo info = screen.getPackageManager().getPackageInfo(appId, PackageManager.GET_PERMISSIONS);
        info.requestedPermissions = declared ? new String[]{Manifest.permission.POST_NOTIFICATIONS} : new String[0];
        info.applicationInfo.targetSdkVersion = 35;
        Shadows.shadowOf(screen.getPackageManager()).installPackage(info);
    }

    private void launcher(String packageName) {
        ResolveInfo resolved = new ResolveInfo(); resolved.activityInfo = new ActivityInfo();
        resolved.activityInfo.packageName = packageName; resolved.activityInfo.name = Screen.class.getName();
        resolved.activityInfo.applicationInfo = screen.getApplicationInfo(); resolved.activityInfo.exported = true;
        Intent lookup = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(appId);
        Shadows.shadowOf(screen.getPackageManager()).removeResolveInfosForIntent(lookup, appId);
        Shadows.shadowOf(screen.getPackageManager()).removeResolveInfosForIntent(lookup, "other.application");
        Shadows.shadowOf(screen.getPackageManager()).addResolveInfoForIntent(lookup, resolved);
    }

    private NotificationRequest.Channel channel(String importance) throws Exception {
        return NotificationRequest.parseChannel(new JSONObject().put("id", "probe").put("name", "Network probes")
                .put("description", "Reachability changes").put("importance", importance).toString());
    }

    private NotificationRequest.Show show(int id, String title) throws Exception {
        return NotificationRequest.parseShow(new JSONObject().put("id", id).put("channelId", "probe").put("title", title)
                .put("body", "Synthetic probe result").put("priority", "high").toString());
    }
}
