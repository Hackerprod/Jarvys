package com.jarvys.factory.runtime;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/** Must be exported=false in the authenticated manifest. No intent payload is executable content. */
public final class FactoryNotificationAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String token = NativeScheduledNotifications.alarmToken(context,intent);
        if (token != null) FactoryNotificationReceiverWork.run(context,goAsync(),token,false);
    }
}
