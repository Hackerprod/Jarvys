package com.jarvys.factory.runtime;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;
import static com.jarvys.factory.runtime.NotificationScheduleMachine.*;
/** Foreground notification actions using the same ledger and dispatch, without alarms or synthetic dates. */
public final class NotificationImmediateEngine {
    private final Store store;private final Platform platform;private final Clock clock;private final Source source;private final String process;
    public NotificationImmediateEngine(Store store,Platform platform,Clock clock,Source source,String process){
        if(store==null||platform==null||clock==null||source==null||!NotificationActionEvents.validToken(process))throw new IllegalArgumentException();
        this.store=store;this.platform=platform;this.clock=clock;this.source=source;this.process=process;
    }
    /** The caller has already acquired notifications.show for this exact full payload; this never acquires another grant. */
    public Row show(NotificationScheduleRequest.Notification notification,Checkpoint live)throws FactoryException{
        if(notification==null||notification.actions.isEmpty()||live==null)throw new IllegalArgumentException();
        if(!source.notificationsCapability)throw new FactoryException("CAPABILITY_REQUIRED","Notifications are not declared by this artifact.");
        live.check();
        if(!platform.notificationsReady(notification))throw new FactoryException("NOT_AVAILABLE","Android notification permission/channel is blocked.");
        Row admitted=store.insertImmediate(source,notification,process,clock.epochMs());
        synchronized(NotificationEffectGate.forId(source.appId,notification.show.id)){
            try{live.check();}
            catch(RuntimeException ended){return store.change(admitted,State.CANCELLED,"no_post_attempted_authority_ended",process,"",clock.epochMs());}
            Row reserved=store.change(admitted,State.DISPATCH_RESERVED,"persisted_before_immediate_post",process,NotificationActionEvents.randomToken(),clock.epochMs());
            if(reserved==null)return store.get(source.ownerFingerprint,admitted.recordId);
            return NotificationDispatch.post(store,platform,clock,process,reserved,live,null);
        }
    }
    public Row status(String generation)throws FactoryException{
        if(!NotificationActionEvents.validToken(generation))throw new FactoryException("INVALID_ARGUMENT","Invalid notification generation ID.");
        Row row=store.byAlarmToken(generation);
        return row!=null&&row.kind==Kind.IMMEDIATE&&source.ownerFingerprint.equals(row.source.ownerFingerprint)?row:null;
    }
    public boolean forget(String generation,boolean acknowledgeUnknown)throws FactoryException{
        Row row=status(generation);if(row==null)return false;
        if(!terminal(row.state)||(row.state==State.DELIVERY_UNKNOWN&&!acknowledgeUnknown))throw new FactoryException("UNRESOLVED_NOTIFICATION","Notification generation is not ready to forget.");
        return store.forget(row,acknowledgeUnknown);
    }
}
