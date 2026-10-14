package com.jarvys.factory.runtime;

import android.Manifest;
import androidx.annotation.RequiresPermission;
import android.app.job.JobParameters;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.SystemClock;
import android.security.NetworkSecurityPolicy;
import java.net.HttpURLConnection;
import java.util.Map;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

/** Cold-process resolution uses the authenticated installed source provider, never Activity state. */
public final class FactoryHttpJobServices {
    public static final String PROCESS=HttpJobRequest.token();
    public static final Clock CLOCK=new Clock(){public long epochMs(){return System.currentTimeMillis();}public long elapsedMs(){return SystemClock.elapsedRealtime();}};
    public static final class Environment {
        public final Source source;public final HttpJobEngine engine;
        Environment(Source source,HttpJobEngine engine){this.source=source;this.engine=engine;}
    }
    interface Resolver { Environment open(Context context,HttpJobLedger ledger,Session session,JobParameters params)throws FactoryException; }
    static volatile Resolver testResolver;
    // Installed jobs components are emitted with ACCESS_NETWORK_STATE by the selective manifest plan.
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    static Environment open(Context context,HttpJobLedger ledger,Session session,JobParameters params)throws FactoryException{
        session.check();Resolver override=testResolver;
        if(override!=null)return override.open(context,ledger,session,params);
        FactoryConfig config=configuration(context);Source source=source(context,config,session);
        Authority authority=(row,current,mode,active)->{
            if(mode!=Mode.BACKGROUND_NO_UI||!current.fingerprint.equals(source.fingerprint)||!current.owner.equals(row.source.owner))return new Decision(SessionStatus.UNAVAILABLE,null);
            active.check();
            // On API 24-27 use the current Network explicitly; newer releases expose the assigned Network.
            network(context,params,row.request.constraints);
            NativeOperationRequest disclosure=NativeOperationRequest.parse("jobs.enqueueHttp",row.request.authorizationDetails("scheduled"));
            NativeBackgroundAuthorization.Result result=NativeBackgroundAuthorization.acquireFiniteHttpJob(context,config,disclosure,
                new NativeBackgroundAuthorization.SourceIdentity(source.appId,source.certificateSha256,source.apkSha256,source.recordSha256,source.versionCode),active.deadlineElapsedMs,active::active);
            if(result.decision!=NativeBackgroundAuthorization.Decision.ACTIVE){result.close();return new Decision(SessionStatus.UNAVAILABLE,null);}
            try{active.check();result.check();return new Decision(SessionStatus.ACTIVE,new Lease(){public void check(){active.check();result.check();active.check();}public void close(){result.close();}});}
            catch(RuntimeException failure){result.close();throw failure;}
        };
        Transport transport=(request,access,cancellation)->{
            Row row=ledger.byToken(params.getExtras().getString("token"));
            if(row==null)throw new FactoryException("NOT_FOUND","Job no longer exists.");
            NativeNetwork network=new NativeNetwork(url->{
                try{return (HttpURLConnection)network(context,params,row.request.constraints).openConnection(url);}
                catch(FactoryException unavailable){throw new java.io.IOException("Assigned network unavailable");}
            },host->NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host),System::nanoTime);
            return network.execute(request,access,cancellation);
        };
        return new Environment(source,new HttpJobEngine(ledger,authority,new NativeHttpJobScheduler(context),transport,CLOCK,source,PROCESS));
    }
    public static FactoryConfig configuration(Context context)throws FactoryException{
        try{return FactoryConfig.parse(FactoryRuntime.readConfiguration(context.getAssets().open("factory-app.json")),context.getPackageName());}
        catch(java.io.IOException|RuntimeException failure){throw new FactoryException("SOURCE_UNAVAILABLE","Installed job configuration is unavailable.");}
    }
    public static Source source(Context context,FactoryConfig config,Session session)throws FactoryException{
        session.check();
        if(!config.capabilities.contains("jobs")||!config.capabilities.contains("network"))throw new FactoryException("CAPABILITY_REQUIRED","Finite HTTP jobs require jobs and network capabilities.");
        NativeInstalledSource proof=NativeInstalledSource.describe(context,config,"jobs",session.deadlineElapsedMs,session::active);
        try{
            Object section=proof.resources.toMap().get("jobs");if(!(section instanceof Map))throw new IllegalArgumentException();Map<?,?> limits=(Map<?,?>)section;
            Object records=limits.get("records"),bytes=limits.get("storageBytes");
            if(!(records instanceof Number)||!(bytes instanceof Number)||limits.size()!=2)throw new IllegalArgumentException();
            session.check();return new Source(proof.appId,proof.certificateSha256,proof.apkSha256,proof.recordSha256,proof.versionCode,new Limits(((Number)records).intValue(),((Number)bytes).longValue()));
        }catch(RuntimeException unavailable){throw new FactoryException("SOURCE_UNAVAILABLE","Current signed job budgets are unavailable.");}
    }
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private static Network network(Context context,JobParameters params,HttpJobRequest.Constraints constraints)throws FactoryException{
        ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(connectivity==null)throw new FactoryException("NETWORK_UNAVAILABLE","Android network service unavailable.");
        Network network=Build.VERSION.SDK_INT>=28?params.getNetwork():connectivity.getActiveNetwork();
        if(network==null&&params.isOverrideDeadlineExpired())network=connectivity.getActiveNetwork();
        NetworkCapabilities capabilities=network==null?null:connectivity.getNetworkCapabilities(network);
        if(capabilities==null||!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)||
            (!params.isOverrideDeadlineExpired()&&constraints.network.equals("unmetered")&&!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)))
            throw new FactoryException("NETWORK_UNAVAILABLE","No network matching the current job constraints is available.");
        return network;
    }
    private FactoryHttpJobServices(){}
}
