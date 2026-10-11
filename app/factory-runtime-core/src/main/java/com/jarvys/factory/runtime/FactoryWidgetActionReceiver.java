package com.jarvys.factory.runtime;
import android.content.*;
/** Immutable explicit own-app PendingIntent only; data comes from authenticated persisted models. */
public final class FactoryWidgetActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){String token=NativeWidgetPlatform.actionToken(context,intent);if(token!=null)FactoryWidgetReceiverWork.run(context,goAsync(),"tap",null,null,token);}
}
