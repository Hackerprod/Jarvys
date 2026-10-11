package com.jarvys.factory.runtime;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.pm.ServiceInfo;
import java.io.File;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
public final class HttpJobAndroidTest {
    Context context;Source source;HttpJobRequest request;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();source=new Source(context.getPackageName(),HttpJobEngineTest.hash('a'),HttpJobEngineTest.hash('b'),HttpJobEngineTest.hash('c'),1,new Limits(32,8388608));
        request=HttpJobRequest.parse(HttpJobRequestTest.input());
    }
    @Test public void privateSqlitePersistsPayloadReservationAndResult()throws Exception{
        String token=HttpJobRequest.token();
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            Row row=ledger.insert(source,request,token,1);row=ledger.change(row,State.RUNNING,"dispatch_reserved","old-process","attempt","",false,2);assertNotNull(row);
        }
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            Row row=ledger.get(source.owner,request.jobId);assertEquals(State.RUNNING,row.state);assertEquals(request.fingerprint,row.request.fingerprint);assertEquals(token,row.token);
            assertNull(ledger.change(new Row(row.source,row.request,row.token,row.androidId,row.state,row.revision-1,row.reason,row.process,row.attempt,"",false,row.createdAt,row.updatedAt),State.COMPLETED,"stale","","","{}",false,3));
            row=ledger.change(row,State.COMPLETED,"done","","","{\"status\":200}",false,4);assertEquals("{\"status\":200}",row.result);
        }
        assertTrue(new File(context.getNoBackupFilesDir(),"factory-http-jobs-v1.db").isFile());
    }
    @Test public void sameIdDifferentPayloadCannotOverwriteAndAdmissionTokenIsStable()throws Exception{
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            Row first=ledger.insert(source,request,HttpJobRequest.token(),1);Row again=ledger.insert(source,request,HttpJobRequest.token(),2);assertEquals(first.token,again.token);
            JSONObject changed=HttpJobRequestTest.input();changed.getJSONObject("request").put("body","other");
            try{ledger.insert(source,HttpJobRequest.parse(changed),HttpJobRequest.token(),3);fail();}catch(FactoryException e){assertEquals("CONFLICT",e.code);}
        }
    }
    @Test public void currentBudgetDowngradeBlocksEffectsWithoutDeletingData()throws Exception{
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            Row row=ledger.insert(source,request,HttpJobRequest.token(),1);
            Source lower=new Source(source.appId,source.certificateSha256,HttpJobEngineTest.hash('d'),source.recordSha256,2,new Limits(32,65536));
            try{ledger.checkBudget(lower,row);fail();}catch(FactoryException e){assertEquals("QUOTA_EXCEEDED",e.code);}
            assertEquals(row.token,ledger.get(source.owner,request.jobId).token);
        }
    }
    @Test public void interruptedAttemptIsUnknownAndLateCompletionCannotOverwrite()throws Exception{
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            Row row=ledger.insert(source,request,HttpJobRequest.token(),1);row=ledger.change(row,State.RUNNING,"dispatch_reserved","process","attempt","",false,2);
            ledger.interrupted(row.token,"other-attempt",3);assertEquals(State.RUNNING,ledger.get(source.owner,request.jobId).state);
            ledger.interrupted(row.token,"attempt",4);assertEquals(State.UNKNOWN,ledger.get(source.owner,request.jobId).state);
            assertNull(ledger.change(row,State.COMPLETED,"late","","","{}",false,5));
        }
    }
    @Test public void exactSchedulerComponentAndSupportedConstraints()throws Exception{
        ServiceInfo service=new ServiceInfo();service.packageName=context.getPackageName();service.name=NativeHttpJobScheduler.SERVICE;service.permission="android.permission.BIND_JOB_SERVICE";service.exported=false;service.enabled=true;service.directBootAware=false;
        Shadows.shadowOf(context.getPackageManager()).addOrUpdateService(service);
        Shadows.shadowOf((Application)context).grantPermissions("android.permission.INTERNET","android.permission.ACCESS_NETWORK_STATE","android.permission.RECEIVE_BOOT_COMPLETED");
        JSONObject payload=HttpJobRequestTest.input().put("constraints",new JSONObject().put("network","unmetered").put("requiresCharging",true).put("minimumLatencyMs",1000).put("overrideDeadlineMs",3000));
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            Row row=ledger.insert(source,HttpJobRequest.parse(payload),HttpJobRequest.token(),1);NativeHttpJobScheduler scheduler=new NativeHttpJobScheduler(context);assertTrue(scheduler.schedule(row));
            JobScheduler platform=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);JobInfo info=platform.getPendingJob(row.androidId);
            assertEquals(NativeHttpJobScheduler.SERVICE,info.getService().getClassName());assertTrue(info.isPersisted());assertTrue(info.isRequireCharging());assertEquals(1000,info.getMinLatencyMillis());assertEquals(3000,info.getMaxExecutionDelayMillis());
            assertEquals(2,info.getExtras().size());assertEquals(row.token,info.getExtras().getString("token"));assertFalse(info.isPeriodic());scheduler.cancel(row);assertNull(platform.getPendingJob(row.androidId));
        }
    }
    @Test public void exportedServiceIsRejectedBeforeSchedulerAdmission()throws Exception{
        ServiceInfo service=new ServiceInfo();service.packageName=context.getPackageName();service.name=NativeHttpJobScheduler.SERVICE;service.permission="android.permission.BIND_JOB_SERVICE";service.exported=true;service.enabled=true;
        Shadows.shadowOf(context.getPackageManager()).addOrUpdateService(service);
        try(HttpJobLedger ledger=new HttpJobLedger(context)){Row row=ledger.insert(source,request,HttpJobRequest.token(),1);try{new NativeHttpJobScheduler(context).schedule(row);fail();}catch(FactoryException e){assertEquals("SCHEDULER_UNAVAILABLE",e.code);}}
    }
    @Test public void bridgeValidationIsClosedAndNeedsNoAndroidContext()throws Exception {
        NativeHttpJobs.validate("jobs.enqueueHttp",HttpJobRequestTest.input());
        for(String op:new String[]{"jobs.status","jobs.cancel","jobs.readResult","jobs.forget"})NativeHttpJobs.validate(op,new JSONObject().put("jobId",request.jobId));
        NativeHttpJobs.validate("jobs.resume",new JSONObject().put("jobId",request.jobId).put("execution","now").put("acknowledgeUnknown",true));
        for(JSONObject args:new JSONObject[]{new JSONObject().put("jobId",request.jobId),new JSONObject().put("jobId",request.jobId).put("execution","later"),new JSONObject().put("jobId",request.jobId).put("execution","now").put("acknowledgeUnknown","true")}) {
            try{NativeHttpJobs.validate("jobs.resume",args);fail();}catch(FactoryException expected){assertEquals("INVALID_ARGUMENT",expected.code);}
        }
        try{NativeHttpJobs.validate("jobs.status",new JSONObject().put("jobId",request.jobId).put("runJavaScript","payload"));fail();}catch(FactoryException expected){assertEquals("INVALID_ARGUMENT",expected.code);}
    }

    @Test @Config(sdk=27) public void ownDataStaysAvailableWithoutHostOrEffectAuthority()throws Exception {
        android.content.pm.Signature signature=new android.content.pm.Signature("01020304");
        android.content.pm.PackageInfo installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0);installed.signatures=new android.content.pm.Signature[]{signature};
        Shadows.shadowOf(context.getPackageManager()).installPackage(installed);
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());StringBuilder cert=new StringBuilder();for(byte b:digest)cert.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        Source own=new Source(context.getPackageName(),cert.toString(),HttpJobEngineTest.hash('b'),HttpJobEngineTest.hash('c'),1,new Limits(32,8388608));
        assertEquals(own.owner,NativeHttpJobs.owner(context,context.getPackageName()));
        FactoryConfig config=FactoryConfig.parse(new JSONObject().put("schemaVersion",1).put("appId",context.getPackageName()).put("name","Jobs test").put("entryPoint","www/index.html").put("capabilities",new org.json.JSONArray()).toString(),context.getPackageName());
        Authority forbidden=(row,current,mode,session)->{throw new AssertionError("Own-data must not request HTTP authority");};
        JSONObject args=new JSONObject().put("jobId",request.jobId);
        try(HttpJobLedger ledger=new HttpJobLedger(context)){
            Row row=ledger.insert(own,request,HttpJobRequest.token(),1);ledger.change(row,State.COMPLETED,"done","","","{\"status\":200}",false,2);
        }
        assertEquals("completed",NativeHttpJobs.execute(context,config,"jobs.status",args,forbidden,new Session(FactoryHttpJobServices.CLOCK,10000)).getString("status"));
        assertTrue(NativeHttpJobs.execute(context,config,"jobs.readResult",new JSONObject(args.toString()).put("acknowledge",true),forbidden,new Session(FactoryHttpJobServices.CLOCK,10000)).getBoolean("acknowledged"));
        assertTrue(NativeHttpJobs.execute(context,config,"jobs.forget",args,forbidden,new Session(FactoryHttpJobServices.CLOCK,10000)).getBoolean("forgotten"));
        try(HttpJobLedger ledger=new HttpJobLedger(context)){ledger.insert(own,request,HttpJobRequest.token(),3);}
        assertEquals("cancelled",NativeHttpJobs.execute(context,config,"jobs.cancel",args,forbidden,new Session(FactoryHttpJobServices.CLOCK,10000)).getString("status"));
    }

}
