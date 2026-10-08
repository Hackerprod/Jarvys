package com.jarvys.agent.crew;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.jarvys.agent.MainActivity;
import com.jarvys.agent.R;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Posts at most one background completion notification for each mission. */
public final class CrewMissionNotifier {
    private static final String CHANNEL_ID = "jarvys_crew_results";
    private static final int NOTIFICATION_ID_BASE = 32000;
    private static final Set<String> POSTED = ConcurrentHashMap.newKeySet();

    private CrewMissionNotifier() { }

    public static boolean publishIfBackground(Context context, CrewMissionSnapshot mission) {
        return publishForState(context, mission, MainActivity.isAppForeground(),
                context != null && notificationsAllowed(context));
    }

    public static boolean publishForState(Context context, CrewMissionSnapshot mission,
                                          boolean appForeground, boolean permissionGranted) {
        if (context == null || mission == null || appForeground || !permissionGranted
                || !"SYNTHESIZED".equals(mission.status)) return false;
        String key = mission.conversationId + "\u0000" + mission.missionId;
        if (!POSTED.add(key)) return false;
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) { POSTED.remove(key); return false; }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        context.getString(R.string.crew_result_notification_channel), NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent open = new Intent(context, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, mission.conversationId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent content = PendingIntent.getActivity(context, key.hashCode(), open, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
            Notification notification = builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(context.getString(R.string.crew_result_notification_title))
                    .setContentText(context.getString(R.string.crew_result_notification_text, mission.title.isEmpty() ? context.getString(R.string.crew_task_untitled) : mission.title))
                    .setContentIntent(content).setAutoCancel(true).build();
            manager.notify(NOTIFICATION_ID_BASE + (key.hashCode() & 0x3fffffff), notification);
            return true;
        } catch (RuntimeException failure) {
            POSTED.remove(key);
            return false;
        }
    }

    private static boolean notificationsAllowed(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
