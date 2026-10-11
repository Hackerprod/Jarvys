package com.jarvys.factory.runtime;
import android.content.Context;
import org.json.JSONObject;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;
/** Android foreground facade. All show/cancel paths share one ID gate and invalidate old action generations. */
public final class NativeNotificationActions {
    private final Source source;private final NotificationScheduleLedger ledger;private final NativeNotifications immediate;
    private final NotificationImmediateEngine engine;
    public NativeNotificationActions(Context context,Source source,NotificationScheduleLedger ledger){
        if(context==null||source==null||ledger==null)throw new IllegalArgumentException();
        this.source=source;this.ledger=ledger;ledger.bindCurrentSource(source);immediate=new NativeNotifications(context,source.appId);
        engine=new NotificationImmediateEngine(ledger,new NativeScheduledNotifications(context,source),FactoryNotificationServices.CLOCK,source,FactoryNotificationServices.PROCESS);
    }
    public JSONObject show(NotificationScheduleRequest.Notification notification,NativeOperationAuthorization.Access live)throws FactoryException{
        if(notification==null||live==null)throw new IllegalArgumentException();
        if(!notification.actions.isEmpty()){
            Row result=engine.show(notification,live::check);
            if(result==null)throw new FactoryException("NOT_AVAILABLE","Notification generation changed during dispatch.");
            return result.details();
        }
        synchronized(NotificationEffectGate.forId(source.appId,notification.show.id)){
            live.check();ledger.invalidateVisibleActions(source.ownerFingerprint,notification.show.id);
            return immediate.show(notification.show,live);
        }
    }
    public JSONObject cancelVisible(int id,NativeOperationAuthorization.Access live)throws FactoryException{
        if(live==null)throw new IllegalArgumentException();
        synchronized(NotificationEffectGate.forId(source.appId,id)){
            live.check();ledger.invalidateVisibleActions(source.ownerFingerprint,id);
            return immediate.cancel(id,live);
        }
    }
    public Row generationStatus(String generationId)throws FactoryException{return engine.status(generationId);}
    public boolean forgetGeneration(String generationId,boolean acknowledgeDeliveryUnknown)throws FactoryException{return engine.forget(generationId,acknowledgeDeliveryUnknown);}
}
