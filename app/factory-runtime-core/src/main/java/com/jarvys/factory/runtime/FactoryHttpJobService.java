package com.jarvys.factory.runtime;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

/** Bounded one-shot workers. Android callbacks do no Binder, SQLite, network or disconnect work. */
public final class FactoryHttpJobService extends JobService {
    private static final ThreadPoolExecutor WORK=pool(2,16);
    private static final ThreadPoolExecutor CLEANUP=pool(2,32);
    private static final ThreadPoolExecutor PERSIST=pool(1,32);
    private static final ScheduledThreadPoolExecutor WATCH=new ScheduledThreadPoolExecutor(1);
    static{WATCH.setRemoveOnCancelPolicy(true);}
    private final ConcurrentHashMap<Integer,Run> active=new ConcurrentHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private static ThreadPoolExecutor pool(int threads,int queued){ThreadPoolExecutor e=new ThreadPoolExecutor(threads,threads,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(queued));e.allowCoreThreadTimeOut(true);return e;}
    private final class Run {
        final JobParameters params;final String token;final Session session=new Session(FactoryHttpJobServices.CLOCK,Session.MAX_LIFETIME_MS);
        final AtomicBoolean ended=new AtomicBoolean(),osStopped=new AtomicBoolean();volatile ScheduledFuture<?> timeout;
        Run(JobParameters p,String token){params=p;this.token=token;}
        void end(boolean retry,boolean fromOs){
            if(fromOs){osStopped.set(true);active.remove(params.getJobId(),this);}
            if(!ended.compareAndSet(false,true))return;
            boolean beforeEffect=session.stop();
            ScheduledFuture<?> timer=timeout;if(timer!=null)timer.cancel(false);
            cleanup(this);
            if(!fromOs)main.post(()->{try{if(!osStopped.get())jobFinished(params,retry&&beforeEffect);}finally{active.remove(params.getJobId(),this);}});
        }
    }
    @Override public boolean onStartJob(JobParameters params){
        String token;
        try{
            PersistableBundle e=params.getExtras();
            if(!e.keySet().equals(new HashSet<>(Arrays.asList("schemaVersion","token")))||!Integer.valueOf(1).equals(e.get("schemaVersion")))return false;
            token=HttpJobRequest.id(e.getString("token"));
        }catch(Exception malformed){return false;}
        if(active.size()>=18){main.post(()->jobFinished(params,true));return true;}
        Run run=new Run(params,token);if(active.putIfAbsent(params.getJobId(),run)!=null)return false;
        run.timeout=WATCH.schedule(()->run.end(true,false),Session.MAX_LIFETIME_MS,TimeUnit.MILLISECONDS);
        try{WORK.execute(()->execute(run));}
        catch(java.util.concurrent.RejectedExecutionException busy){run.end(true,false);}
        return true;
    }
    private void execute(Run run){
        boolean retry=false;
        try(HttpJobLedger ledger=new HttpJobLedger(this)){
            run.session.check();Row row=ledger.byToken(run.token);
            // Extras are only an opaque index. Reused ID or stale OS delivery cannot select another payload.
            if(row==null||row.androidId!=run.params.getJobId())return;
            FactoryHttpJobServices.Environment environment=FactoryHttpJobServices.open(this,ledger,run.session,run.params);
            RunResult result=environment.engine.fire(run.token,run.session);retry=result.retryBeforeEffect;
        }catch(FactoryException|RuntimeException unavailable){retry=!run.session.reserved();}
        finally{run.end(retry,false);}
    }
    @Override public boolean onStopJob(JobParameters params){
        Run run=active.get(params.getJobId());
        if(run==null)return false;
        boolean retry=run.session.stop();run.end(retry,true);return retry;
    }
    @Override public void onDestroy(){for(Run run:active.values())run.end(true,true);super.onDestroy();}
    private void cleanup(Run run){
        Context app=getApplicationContext();final Context owner=app==null?this:app;
        try{CLEANUP.execute(run.session.cancellation::cancel);}catch(java.util.concurrent.RejectedExecutionException busy){/* Access is already fenced; transport's own deadline remains active. */}
        try{PERSIST.execute(()->{try(HttpJobLedger ledger=new HttpJobLedger(owner)){ledger.interrupted(run.token,run.session.attempt,System.currentTimeMillis());}catch(FactoryException|RuntimeException unavailable){/* Durable reservation remains conservative; never authorize a replay. */}});}
        catch(java.util.concurrent.RejectedExecutionException busy){/* No external effect is introduced by unavailable cleanup. */}
    }
}
