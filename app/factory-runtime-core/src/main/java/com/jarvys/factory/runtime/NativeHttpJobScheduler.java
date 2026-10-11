package com.jarvys.factory.runtime;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.PersistableBundle;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

/** Exact finite component only. OS constraints/quotas remain authoritative; no exact-time promise. */
public final class NativeHttpJobScheduler implements Platform {
    public static final String SERVICE="com.jarvys.factory.runtime.FactoryHttpJobService";
    private final Context context;private final JobScheduler scheduler;private final ComponentName component;
    public NativeHttpJobScheduler(Context context)throws FactoryException{
        this.context=context;component=new ComponentName(context.getPackageName(),SERVICE);
        scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)throw new FactoryException("UNAVAILABLE","Android JobScheduler is unavailable.");
    }
    @Override public boolean schedule(Row row)throws FactoryException{
        try{
            ServiceInfo info=context.getPackageManager().getServiceInfo(component,0);
            if(!info.enabled||info.exported||info.directBootAware||!"android.permission.BIND_JOB_SERVICE".equals(info.permission))throw new SecurityException();
            for(String p:new String[]{"android.permission.INTERNET","android.permission.ACCESS_NETWORK_STATE","android.permission.RECEIVE_BOOT_COMPLETED"})
                if(context.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();
            PersistableBundle extra=new PersistableBundle();extra.putInt("schemaVersion",1);extra.putString("token",row.token);
            HttpJobRequest.Constraints c=row.request.constraints;
            JobInfo.Builder builder=new JobInfo.Builder(row.androidId,component).setExtras(extra).setPersisted(true)
                .setRequiredNetworkType(c.network.equals("unmetered")?JobInfo.NETWORK_TYPE_UNMETERED:JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(c.requiresCharging).setMinimumLatency(c.minimumLatencyMs)
                .setBackoffCriteria(30000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            if(c.overrideDeadlineMs>=0)builder.setOverrideDeadline(c.overrideDeadlineMs);
            return scheduler.schedule(builder.build())==JobScheduler.RESULT_SUCCESS;
        }catch(Exception unavailable){throw new FactoryException("SCHEDULER_UNAVAILABLE","Job component, permissions, quota or platform scheduling is unavailable.");}
    }
    @Override public void cancel(Row row)throws FactoryException{
        try{scheduler.cancel(row.androidId);}catch(RuntimeException unavailable){throw new FactoryException("SCHEDULER_UNAVAILABLE","Android job cancellation was not confirmed.");}
    }
}
