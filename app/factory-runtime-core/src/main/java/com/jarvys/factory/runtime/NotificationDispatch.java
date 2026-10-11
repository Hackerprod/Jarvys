package com.jarvys.factory.runtime;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;
import static com.jarvys.factory.runtime.NotificationScheduleMachine.*;
/** Shared once-attempted native commit for immediate and scheduled rows. Caller owns the visible-ID gate. */
final class NotificationDispatch {
    static Row post(Store store,Platform platform,Clock clock,String process,Row row,Checkpoint live,Session session)throws FactoryException {
        AtomicBoolean entered=new AtomicBoolean();
        try {
            platform.post(row,store.actions(row),()->{
                live.check();if(session!=null)session.check();
                try {
                    Row current=store.get(row.source.ownerFingerprint,row.recordId);
                    if(current==null||current.revision!=row.revision||!current.token.equals(row.token))throw new SecurityException("Stale notification reservation");
                }catch(FactoryException unavailable){throw new SecurityException("Ledger unavailable",unavailable);}
                if(row.kind==Kind.SCHEDULED&&clock.epochMs()>row.request.expiresAtEpochMs)throw new SecurityException("Notification expired");
                if(session!=null)session.check();entered.set(true);
            });
            if(!entered.get())throw new IllegalStateException("Platform omitted final checkpoint");
            return change(store,row,State.ACCEPTED,"notify_returned_no_display_receipt",process,clock.epochMs());
        }catch(FactoryException|RuntimeException failure){
            if(entered.get())return change(store,row,State.DELIVERY_UNKNOWN,"post_result_uncertain_no_auto_replay",process,clock.epochMs());
            State next=row.kind==Kind.IMMEDIATE?State.CANCELLED:clock.epochMs()>row.request.expiresAtEpochMs?State.EXPIRED:
                    !platform.notificationsReady(row.notification)?State.QUEUED_OS_BLOCKED:State.QUEUED_POLICY_UNAVAILABLE;
            return change(store,row,next,"no_post_attempted_preflight_failed",process,clock.epochMs());
        }
    }
    private static Row change(Store store,Row row,State state,String reason,String process,long now)throws FactoryException {
        Row next=store.change(row,state,reason,process,row.attempt,now);
        return next==null?store.get(row.source.ownerFingerprint,row.recordId):next;
    }
    private NotificationDispatch(){}
}
