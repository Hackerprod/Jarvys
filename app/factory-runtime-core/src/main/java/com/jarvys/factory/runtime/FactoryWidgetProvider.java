package com.jarvys.factory.runtime;
import android.appwidget.*;
import android.content.*;
import android.os.Bundle;
/** Selectively declared exported=false provider; system lifecycle never grants operation authority. */
public final class FactoryWidgetProvider extends AppWidgetProvider {
    @Override public void onReceive(Context context,Intent intent){
        // AppWidgetProvider's default RESTORED dispatch invokes onRestored AND onUpdate in one
        // broadcast. Take a single goAsync lifetime instead of trying to detach it twice.
        if(intent!=null&&AppWidgetManager.ACTION_APPWIDGET_RESTORED.equals(intent.getAction())){
            int[] oldIds=intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS);
            int[] newIds=intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if(oldIds!=null&&newIds!=null)FactoryWidgetReceiverWork.run(context,goAsync(),"restored",newIds,oldIds,null);
            return;
        }
        super.onReceive(context,intent);
    }
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){FactoryWidgetReceiverWork.run(c,goAsync(),"update",ids,null,null);}
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options){FactoryWidgetReceiverWork.run(c,goAsync(),"update",new int[]{id},null,null);}
    @Override public void onDeleted(Context c,int[] ids){FactoryWidgetReceiverWork.run(c,goAsync(),"deleted",ids,null,null);}
    @Override public void onRestored(Context c,int[] oldIds,int[] newIds){FactoryWidgetReceiverWork.run(c,goAsync(),"restored",newIds,oldIds,null);}
}
