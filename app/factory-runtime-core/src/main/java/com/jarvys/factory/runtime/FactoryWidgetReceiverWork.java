package com.jarvys.factory.runtime;
import android.content.*;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Bounded cold receiver worker. Independent expiry fences late Binder replies and live checks. */
final class FactoryWidgetReceiverWork {
    private static final long BUDGET_MS=8000;
    private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(64));
    private static final ScheduledThreadPoolExecutor WATCH=new ScheduledThreadPoolExecutor(1);
    static {WORK.allowCoreThreadTimeOut(true);WATCH.setRemoveOnCancelPolicy(true);}
    static void run(Context context,BroadcastReceiver.PendingResult pending,String kind,int[] ids,int[] oldIds,String token){
        Context app=context.getApplicationContext();Context owner=app==null?context:app;int[] selected=ids==null?new int[0]:ids.clone(),old=oldIds==null?new int[0]:oldIds.clone();
        if(selected.length>128||old.length>128){pending.finish();return;}
        AtomicBoolean active=new AtomicBoolean(true),finished=new AtomicBoolean();long deadline=SystemClock.elapsedRealtime()+BUDGET_MS;
        Runnable finish=()->{active.set(false);if(finished.compareAndSet(false,true))pending.finish();};
        ScheduledFuture<?> timeout=WATCH.schedule(finish,BUDGET_MS,TimeUnit.MILLISECONDS);
        try{WORK.execute(()->{try{
            java.util.function.BooleanSupplier current=()->active.get()&&SystemClock.elapsedRealtime()<deadline&&!Thread.currentThread().isInterrupted();if(!current.getAsBoolean())return;
            FactoryWidgetServices.Environment e=FactoryWidgetServices.open(owner,deadline,current);
            if(!current.getAsBoolean())return;
            if(kind.equals("tap")){e.ledger.acceptTap(token,System.currentTimeMillis(),e.platform.ownInstances());return;}
            if(kind.equals("deleted")){e.ledger.deleted(selected);return;}
            if(kind.equals("restored"))e.ledger.remap(old,selected,e.platform.ownInstances());
            for(int id:selected){if(!current.getAsBoolean())return;WidgetRequest request=e.engine.restorationRequest(id);if(request==null)continue;
                NativeOperationRequest operation=NativeOperationRequest.parse(WidgetRequest.OPERATION,request.authorizationDetails());
                try(NativeBackgroundAuthorization.Result lease=NativeBackgroundAuthorization.acquire(owner,e.config,operation,e.identity(),deadline,current)){
                    if(lease.decision!=NativeBackgroundAuthorization.Decision.ACTIVE)continue;
                    boolean restored=e.engine.restore(request,()->{if(!current.getAsBoolean())throw new SecurityException("Widget receiver expired");lease.check();if(!current.getAsBoolean())throw new SecurityException("Widget receiver expired");});
                    if(restored&&kind.equals("restored"))e.platform.restoreCompleted(id,lease);
                }
            }
        }catch(FactoryException|RuntimeException blocked){android.util.Log.w("FactoryWidgets","Widget receiver work remains unconfirmed; no automatic replay was scheduled.");}
        finally{timeout.cancel(false);finish.run();}});}catch(RejectedExecutionException full){timeout.cancel(false);finish.run();}
    }
    private FactoryWidgetReceiverWork(){}
}
