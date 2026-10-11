package com.jarvys.factory.runtime;

import android.app.Activity;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Synthetic lifecycle/authority tests; does not claim physical Binder, picker or SQLite durability. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,32},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeBackupTest {
    static final String EXPORT="{\"version\":1,\"tables\":[\"notes\"],\"filename\":\"notes.json\"}";
    static class Host implements FactoryRuntime.Host {
        final MemoryBackend memory=new MemoryBackend(); boolean preview=true; boolean active=true;
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return FactoryDispatcherTest.configuration("[\"database\",\"documents\"]");}
        public boolean isPreview(){return preview;}
        public boolean isActive(){return active;}
        public BoundedStore.Backend storage(){return memory;}
    }
    static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        public void postMessage(String text){try{value=new JSONObject(text);}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] bytes){throw new AssertionError();}
        String error()throws Exception{assertNotNull(value);return value.getJSONObject("error").getString("code");}
    }
    FactoryRuntime runtime; Activity activity; Host host; int sequence;
    static Field field(String name)throws Exception {Field f=FactoryRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    static Field member(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    static Method method(String name,Class<?>...args)throws Exception{Method m=FactoryRuntime.class.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    @Before public void setup()throws Exception {
        activity=Robolectric.buildActivity(Activity.class).setup().get();host=new Host();runtime=new FactoryRuntime(activity,new FrameLayout(activity),host);
        String config=new JSONObject(host.configuration()).put("dataBackup",new JSONObject().put("tables",new org.json.JSONArray("[\"notes\"]"))).toString();
        field("config").set(runtime,FactoryConfig.parsePreview(config));field("store").set(runtime,new BoundedStore(host.memory));runtime.onResume();
    }
    @After public void stop()throws Exception {
        Object work=field("backup").get(runtime);
        if(work!=null) {member(work,"returned").setBoolean(work,true);member(work,"worker").setBoolean(work,false);}
        runtime.close();
        ThreadPoolExecutor io=(ThreadPoolExecutor)field("io").get(runtime);assertTrue(io.awaitTermination(10,TimeUnit.SECONDS));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(((java.util.concurrent.atomic.AtomicInteger)field("documentResources").get(null)).get()!=0 && System.nanoTime()<until) {
            Thread.sleep(5); Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        if(work!=null && field("backup").get(runtime)!=null) method("finishBackup",work.getClass()).invoke(runtime,work);
        assertFalse(((AtomicBoolean)field("BACKUP_ADMISSION").get(null)).get());activity.finish();
    }
    Response request(String name,String args)throws Exception{return request("backup"+(++sequence),name,args);}
    Response request(String id,String name,String args)throws Exception {
        Response response=new Response();method("receive",String.class,JavaScriptReplyProxy.class).invoke(runtime,
                new JSONObject().put("v",1).put("id",id).put("method",name).put("args",new JSONObject(args)).toString(),response);return response;
    }
    @SuppressWarnings("unchecked") Object installWork(String id,Response response,boolean launched)throws Exception {
        Class<?> replyClass=Class.forName(FactoryRuntime.class.getName()+"$Reply");Constructor<?> replyCtor=replyClass.getDeclaredConstructors()[0];replyCtor.setAccessible(true);
        Object reply=replyCtor.newInstance(runtime,id,"database.backupExport",response,field("generation").getInt(runtime));
        ((Map<String,Object>)field("pending").get(runtime)).put(id,reply);
        BridgeProtocol.Request request=BridgeProtocol.validate(BridgeProtocol.ORIGIN,true,new JSONObject().put("v",1).put("id",id)
                .put("method","database.backupExport").put("args",new JSONObject(EXPORT)).toString(),(FactoryConfig)field("config").get(runtime));
        Class<?> type=Class.forName(FactoryRuntime.class.getName()+"$BackupWork");Constructor<?> ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object work=ctor.newInstance(runtime,request,reply);member(work,"launched").setBoolean(work,launched);
        field("backup").set(runtime,work);field("uiOwner").set(runtime,reply);((AtomicBoolean)field("BACKUP_ADMISSION").get(null)).set(true);return work;
    }
    void drainCleanup()throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(((java.util.concurrent.atomic.AtomicInteger)field("documentResources").get(null)).get()!=0 && System.nanoTime()<until) Thread.sleep(5);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Object work=field("backup").get(runtime);if(work!=null) method("finishBackup",work.getClass()).invoke(runtime,work);
    }
    @Test public void previewDoesNotAllocateDatabaseOrConsumeHandle()throws Exception {
        DocumentHandles handles=(DocumentHandles)field("documents").get(runtime);String token=handles.grantRead(new ByteArrayInputStream(new byte[]{1,2}));
        assertEquals("UNAVAILABLE",request("database.backupExport",EXPORT).error());
        assertEquals("UNAVAILABLE",request("database.backupImport","{\"version\":1,\"tables\":[\"notes\"],\"handle\":\""+token+"\",\"mode\":\"insertOnly\"}").error());
        assertEquals(1,handles.read(token,0,1).nextOffset);assertNull(field("database").get(runtime));assertNull(field("backup").get(runtime));
    }
    @Test public void defaultEmptyPolicyDeniesBeforeNativeAdmission()throws Exception {
        host.preview=false;field("config").set(runtime,FactoryConfig.parsePreview(host.configuration()));
        assertEquals("BACKUP_DENIED",request("database.backupExport",EXPORT).error());assertNull(field("backup").get(runtime));assertNull(field("database").get(runtime));
    }
    @Test public void preparationPauseCancelsAndReleasesIdleAdmission()throws Exception {
        Response response=new Response();Object work=installWork("preparing",response,false);runtime.onPause();
        assertEquals("CANCELLED",response.error());assertTrue(member(work,"cancelled").getBoolean(work));drainCleanup();assertNull(field("backup").get(runtime));
    }
    @SuppressWarnings("unchecked") @Test public void onlyLaunchedOwnReplySurvivesPauseAndOrdinaryHandlesAreRevoked()throws Exception {
        Response response=new Response();Object work=installWork("review",response,true);
        DocumentHandles handles=(DocumentHandles)field("documents").get(runtime);String token=handles.grantRead(new ByteArrayInputStream(new byte[]{1}));
        PrivateDatabase database=PrivateDatabase.preview();field("database").set(runtime,database);
        runtime.onPause();assertNull(response.value);assertSame(work,field("backup").get(runtime));assertNull(field("database").get(runtime));
        assertThrows(FactoryException.class,()->handles.read(token,0,1));
        Map<String,Object> pending=(Map<String,Object>)field("pending").get(runtime);assertTrue(pending.containsKey("review"));
        assertTrue(((AtomicBoolean)field("BACKUP_ADMISSION").get(null)).get());
    }
    @Test public void pauseAfterReturnCancelsRatherThanReusingApproval()throws Exception {
        Response response=new Response();Object work=installWork("returned",response,true);member(work,"returned").setBoolean(work,true);
        runtime.onPause();assertEquals("CANCELLED",response.error());drainCleanup();assertNull(field("backup").get(runtime));
    }
    @Test public void cancelledLaunchedWorkRetainsTombstoneUntilOriginalCallback()throws Exception {
        Response response=new Response();Object work=installWork("review",response,true);
        Response cancel=request("database.cancel","{}");assertTrue(cancel.value.getBoolean("ok"));assertEquals("CANCELLED",response.error());
        assertSame(work,field("backup").get(runtime));assertTrue(((AtomicBoolean)field("BACKUP_ADMISSION").get(null)).get());
        runtime.onActivityResult(member(work,"requestCode").getInt(work),Activity.RESULT_CANCELED,null);drainCleanup();assertNull(field("backup").get(runtime));
        runtime.onActivityResult(member(work,"requestCode").getInt(work),Activity.RESULT_CANCELED,null);assertNull(field("database").get(runtime));
    }
    @Test public void duplicateCancellationIdCannotRevokeReview()throws Exception {
        Object work=installWork("review",new Response(),true);assertEquals("BUSY",request("review","database.cancel","{}").error());
        assertFalse(member(work,"cancelled").getBoolean(work));
    }
    @SuppressWarnings("unchecked") @Test public void cancellationBypassesPendingAndRateSaturation()throws Exception {
        Response response=new Response();Object work=installWork("review",response,true);
        Map<String,Object> pending=(Map<String,Object>)field("pending").get(runtime);Object reply=pending.get("review");
        for(int i=0;i<15;i++)pending.put("blocked"+i,reply);
        field("rateCount").setInt(runtime,1000);field("rateWindow").setLong(runtime,android.os.SystemClock.elapsedRealtime());
        Response cancel=request("database.cancel","{}");assertTrue(cancel.value.getBoolean("ok"));assertTrue(member(work,"cancelled").getBoolean(work));assertEquals("CANCELLED",response.error());
    }
    @Test public void malformedCancelCannotRevokeBackup()throws Exception {
        Object work=installWork("review",new Response(),true);assertEquals("INVALID_ARGUMENT",request("database.cancel","{\"unexpected\":true}").error());
        assertFalse(member(work,"cancelled").getBoolean(work));
    }
    @Test public void globalRateFailureRevokesBackupBeforeRejectingAllPromises()throws Exception {
        Response response=new Response();Object work=installWork("review",response,true);
        field("rateCount").setInt(runtime,1000);field("rateWindow").setLong(runtime,android.os.SystemClock.elapsedRealtime());
        assertEquals("RATE_LIMITED",request("runtime.info","{}").error());assertTrue(member(work,"cancelled").getBoolean(work));assertEquals("CANCELLED",response.error());
    }
    @Test public void navigationRevokesSnapshotAndCannotAttachOldCallbackToNewGeneration()throws Exception {
        Object work=installWork("review",new Response(),true);method("invalidate").invoke(runtime);
        assertTrue(member(work,"cancelled").getBoolean(work));assertSame(work,field("backup").get(runtime));
        runtime.onActivityResult(member(work,"requestCode").getInt(work),Activity.RESULT_CANCELED,null);drainCleanup();assertNull(field("backup").get(runtime));assertNull(field("database").get(runtime));
    }
    @Test public void expiryDoesNotRenewWhenReviewReturns()throws Exception {
        Response response=new Response();Object work=installWork("review",response,true);long deadline=member(work,"deadline").getLong(work);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300001));
        ((Runnable)member(work,"tick").get(work)).run();assertTrue(member(work,"cancelled").getBoolean(work));
        assertEquals(deadline,member(work,"deadline").getLong(work));runtime.onActivityResult(member(work,"requestCode").getInt(work),Activity.RESULT_CANCELED,null);drainCleanup();assertNull(field("backup").get(runtime));
    }
    @Test public void cancelWhileWorkerIsBlockedRetainsAdmission()throws Exception {
        Object work=installWork("preparing",new Response(),false);member(work,"worker").setBoolean(work,true);
        request("database.cancel","{}");assertSame(work,field("backup").get(runtime));assertTrue(((AtomicBoolean)field("BACKUP_ADMISSION").get(null)).get());
        member(work,"worker").setBoolean(work,false);method("finishBackup",work.getClass()).invoke(runtime,work);drainCleanup();assertNull(field("backup").get(runtime));
    }
    @Test public void destroyedOwnerReleasesGlobalAdmissionWithoutAnImpossibleCallback()throws Exception {
        Object work=installWork("destroyed",new Response(),true);runtime.close();drainCleanup();
        assertNull(field("backup").get(runtime));assertFalse(((AtomicBoolean)field("BACKUP_ADMISSION").get(null)).get());
        // A fresh runtime has a distinct callback identifier and cannot inherit the old authority.
        FactoryRuntime old=runtime;runtime=new FactoryRuntime(activity,new FrameLayout(activity),host);
        field("config").set(runtime,field("config").get(old));field("store").set(runtime,new BoundedStore(host.memory));runtime.onResume();
        Object next=installWork("new",new Response(),true);assertNotEquals(member(work,"requestCode").getInt(work),member(next,"requestCode").getInt(next));
        runtime.onActivityResult(member(work,"requestCode").getInt(work),Activity.RESULT_CANCELED,null);
        assertFalse(member(next,"returned").getBoolean(next));
    }
    @Test public void cancelledReviewDiscardsPrivateSnapshotBeforeCallbackArrives()throws Exception {
        Object work=installWork("private",new Response(),true);
        JSONObject schema=new JSONObject().put("notes",new JSONObject().put("text",new JSONObject().put("type","text").put("nullable",false)));
        DatabaseBackupFormat.Archive archive=DatabaseBackupFormat.create("example.test",new String(new char[64]).replace('\0','a'),1,schema,
                new JSONObject().put("notes",new org.json.JSONArray().put(new JSONObject().put("id","one").put("text","synthetic private value"))));
        member(work,"archive").set(work,DataBackupArchive.of(archive));request("database.cancel","{}");
        assertNull(member(work,"archive").get(work));assertNull(member(work,"baseline").get(work));assertNull(member(work,"plan").get(work));
        assertSame(work,field("backup").get(runtime));
    }
    @Test public void staleSuccessfulNonceCannotEndCurrentReview()throws Exception {
        Object work=installWork("current",new Response(),true);
        runtime.onActivityResult(member(work,"requestCode").getInt(work),Activity.RESULT_OK,new android.content.Intent().putExtra("nonce",new String(new char[64]).replace('\0','f')));
        assertFalse(member(work,"returned").getBoolean(work));assertFalse(member(work,"cancelled").getBoolean(work));assertSame(work,field("backup").get(runtime));
    }
    @Test public void blockingProviderCancellationRunsOffMainAndRetainsAdmission()throws Exception {
        Object work=installWork("blocking",new Response(),false);
        android.os.CancellationSignal signal=(android.os.CancellationSignal)member(work,"signal").get(work);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> ran=new java.util.concurrent.atomic.AtomicReference<>();
        signal.setOnCancelListener(()-> { ran.set(Thread.currentThread());entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();} });
        try {
            Response cancel=request("database.cancel","{}");assertTrue(cancel.value.getBoolean("ok"));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(),ran.get());assertSame(work,field("backup").get(runtime));
            runtime.close();assertTrue(((AtomicBoolean)field("BACKUP_ADMISSION").get(null)).get());
        } finally { release.countDown(); }
        drainCleanup();assertNull(field("backup").get(runtime));
    }
    @Test public void noOpCancellationControlIsBoundedIndependentlyOfNormalRate()throws Exception {
        for(int i=0;i<8;i++)assertTrue(request("database.cancel","{}").value.getBoolean("ok"));
        assertEquals("RATE_LIMITED",request("database.cancel","{}").error());
    }

}
