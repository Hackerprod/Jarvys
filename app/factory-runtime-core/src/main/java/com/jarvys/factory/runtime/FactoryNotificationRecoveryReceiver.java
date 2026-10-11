package com.jarvys.factory.runtime;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/** Private protected-system-broadcast receiver; credential storage only, never Direct Boot. */
public final class FactoryNotificationRecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean exact = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) || exact)
            FactoryNotificationReceiverWork.run(context,goAsync(),null,exact);
    }
}
