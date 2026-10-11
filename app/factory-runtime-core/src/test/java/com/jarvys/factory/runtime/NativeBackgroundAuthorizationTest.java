package com.jarvys.factory.runtime;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Synthetic provider/Binder regression tests, not installed-artifact or physical-device evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=27, manifest=Config.NONE, application=Application.class)
public final class NativeBackgroundAuthorizationTest {
    private Context context;
    private FactoryConfig config;
    private NativeOperationRequest operation;
    private NativeBackgroundAuthorization.SourceIdentity source;
    private ReplyProvider provider;
    private final AtomicBoolean current=new AtomicBoolean(true);
    private long receiverDeadline;
    public static final class ReplyProvider extends ContentProvider {
        int calls;
        String decision="ASK";
        Bundle request;
        Consumer<Bundle> mutate=reply->{};
        NativeOperationAuthorization lease;
        final AtomicInteger checks=new AtomicInteger(),closed=new AtomicInteger();
        @Override public Bundle call(String method,String arg,Bundle input) {
            assertEquals("acquire_background",method);assertNull(arg);calls++;request=new Bundle(input);
            assertEquals(6,input.keySet().size());
            try {
                NativeOperationRequest operation=NativeOperationRequest.parse(input.getString("operation"),input.getString("details"));
                NativeBackgroundAuthorization.SourceIdentity source=NativeBackgroundAuthorization.SourceIdentity.parse(input.getString("expectedSource"));
                String nonce=input.getString("nonce"),binding=operation.binding(nonce,source.bindingEvidence());
                Bundle result=new Bundle();result.putInt("protocolVersion",1);result.putString("nonce",nonce);result.putString("binding",binding);
                if (decision.equals("ALLOW")) {
                    lease=new NativeOperationAuthorization(nonce,binding,input.getLong("deadlineElapsedMs"),uid->true,
                            new NativeOperationAuthorization.Authority() {
                                public int status(){return NativeOperationAuthorization.APPROVED;}
                                public void checkBeforeEffect(){checks.incrementAndGet();}
                            },closed::incrementAndGet);
                    result.putBinder("lease",lease);result.putInt("status",NativeOperationAuthorization.APPROVED);
                } else {
                    result.putString("decision",decision);result.putInt("status",decision.equals("ASK")?NativeOperationAuthorization.PENDING:NativeOperationAuthorization.TERMINAL);
                }
                mutate.accept(result);return result;
            } catch(FactoryException invalid){throw new AssertionError(invalid);}
        }
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri u,String[]p,String s,String[]a,String o){throw new AssertionError();}
        @Override public String getType(Uri u){throw new AssertionError();}
        @Override public Uri insert(Uri u,ContentValues v){throw new AssertionError();}
        @Override public int delete(Uri u,String s,String[]a){throw new AssertionError();}
        @Override public int update(Uri u,ContentValues v,String s,String[]a){throw new AssertionError();}
    }
    private static String hash(char value){return new String(new char[64]).replace('\0',value);}
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();current.set(true);receiverDeadline=SystemClock.elapsedRealtime()+8000L;
        String hostName="com.jarvys.agent";int uid=Binder.getCallingUid();Signature signature=new Signature(new byte[]{1,3,5,7});
        StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))digest.append(String.format("%02x",b&255));
        PackageInfo host=new PackageInfo();host.packageName=hostName;host.signatures=new Signature[]{signature};host.versionCode=1;
        host.applicationInfo=new ApplicationInfo();host.applicationInfo.packageName=hostName;host.applicationInfo.uid=uid;host.applicationInfo.enabled=true;
        ProviderInfo info=new ProviderInfo();info.packageName=hostName;info.authority=hostName+".factory.operations";
        info.name="com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider";info.enabled=true;info.exported=true;info.applicationInfo=host.applicationInfo;
        host.providers=new ProviderInfo[]{info};shadowOf(context.getPackageManager()).installPackage(host);shadowOf(context.getPackageManager()).setPackagesForUid(uid,hostName);
        config=FactoryConfig.parse(new JSONObject().put("schemaVersion",1).put("appId",context.getPackageName()).put("name","Test").put("entryPoint","www/index.html")
                .put("capabilities",new org.json.JSONArray().put("notifications"))
                .put("documentBroker",new JSONObject().put("packageName",hostName).put("certificateSha256",digest.toString())).toString(),context.getPackageName());
        operation=NativeOperationRequest.parse("notifications.cancel","{\"id\":1}");
        source=new NativeBackgroundAuthorization.SourceIdentity(context.getPackageName(),hash('a'),hash('b'),hash('c'),42L);
        provider=new ReplyProvider();provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(info.authority,provider);
    }
    private <T>T worker(Callable<T> work)throws Exception {
        java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
        try{return executor.submit(work).get(5,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
    }
    private NativeBackgroundAuthorization.Result acquire()throws Exception {
        return worker(()->NativeBackgroundAuthorization.acquire(context,config,operation,source,receiverDeadline,current::get));
    }
    private void rejected(Consumer<Bundle> edit)throws Exception {
        provider.mutate=edit;
        worker(()->{assertThrows(FactoryException.class,()->NativeBackgroundAuthorization.acquire(context,config,operation,source,receiverDeadline,current::get));return null;});
    }
    @Test public void everySourceIdentityFieldChangesTheBackgroundBinding()throws Exception {
        String nonce=hash('f'),binding=operation.binding(nonce,source.bindingEvidence());
        String[] keys={"appId","certificateSha256","apkSha256","recordSha256","versionCode"};
        Object[] values={"example.other",hash('d'),hash('d'),hash('d'),43L};
        for(int i=0;i<keys.length;i++) {
            JSONObject changed=new JSONObject(source.canonicalJson()).put(keys[i],values[i]);
            assertNotEquals(keys[i],binding,operation.binding(nonce,NativeBackgroundAuthorization.SourceIdentity.parse(changed.toString()).bindingEvidence()));
        }
        assertNotEquals(binding,operation.binding(nonce));
        JSONObject reordered=new JSONObject().put("versionCode",42).put("recordSha256",hash('c')).put("apkSha256",hash('b')).put("certificateSha256",hash('a')).put("appId",context.getPackageName());
        assertEquals(source.bindingEvidence(),NativeBackgroundAuthorization.SourceIdentity.parse(reordered.toString()).bindingEvidence());
    }
    @Test public void expectedSourceGrammarIsClosedBoundedAndTyped()throws Exception {
        String[] invalid={"{}",source.canonicalJson().replace("42","42.5"),source.canonicalJson().replace("42","\"42\""),
                new JSONObject(source.canonicalJson()).put("extra",true).toString(),source.canonicalJson().replace("42","0"),
                source.canonicalJson().replace(hash('a'),hash('A')),source.canonicalJson().replace("\"appId\":","\"appId\":\"example.duplicate\",\"appId\":"),
                " ".repeat(1025)+source.canonicalJson()};
        for(String raw:invalid)assertThrows(FactoryException.class,()->NativeBackgroundAuthorization.SourceIdentity.parse(raw));
    }
    @Test public void askAndDenyAreSourceBoundDecisionsWithoutAnEffectLease()throws Exception {
        for(String decision:new String[]{"ASK","DENY"}) {
            provider.decision=decision;
            try(NativeBackgroundAuthorization.Result result=acquire()) {
                assertEquals(NativeBackgroundAuthorization.Decision.valueOf(decision),result.decision);
                assertThrows(SecurityException.class,result::check);assertNull(provider.lease);
            }
        }
        assertEquals(receiverDeadline,provider.request.getLong("deadlineElapsedMs"));
        assertEquals(source.bindingEvidence(),NativeBackgroundAuthorization.SourceIdentity.parse(provider.request.getString("expectedSource")).bindingEvidence());
        assertEquals(0,provider.checks.get());
    }
    @Test public void repliesCannotDropOrSubstituteSourceBinding()throws Exception {
        rejected(reply->reply.putString("binding",operation.binding(provider.request.getString("nonce"))));
        rejected(reply->reply.putString("binding",hash('d')));
        rejected(reply->reply.putString("reviewToken",hash('d')));
        rejected(reply->reply.putLong("protocolVersion",1L));
        rejected(reply->reply.putInt("status",NativeOperationAuthorization.APPROVED));
    }
    @Test public void lateAllowIsConnectedOnlyToCloseItsGrant()throws Exception {
        provider.decision="ALLOW";
        rejected(reply->ShadowSystemClock.advanceBy(2001,TimeUnit.MILLISECONDS));
        assertNotNull(provider.lease);assertTrue(provider.lease.isConnected());assertTrue(provider.lease.isClosed());
        assertEquals(1,provider.closed.get());assertEquals(0,provider.checks.get());
    }
    @Test public void cancellationDuringCallClosesTheLateAllowWithoutCheckingAnEffect()throws Exception {
        provider.decision="ALLOW";rejected(reply->current.set(false));
        assertTrue(provider.lease.isConnected());assertTrue(provider.lease.isClosed());
        assertEquals(1,provider.closed.get());assertEquals(0,provider.checks.get());
    }
    @Test public void allowRequiresLiveChecksAndClosesTheSameGrant()throws Exception {
        provider.decision="ALLOW";NativeBackgroundAuthorization.Result result=acquire();
        assertEquals(NativeBackgroundAuthorization.Decision.ALLOW,result.decision);int prior=provider.checks.get();result.check();
        assertEquals(prior+1,provider.checks.get());current.set(false);assertThrows(SecurityException.class,result::check);
        worker(()->{result.close();result.close();return null;});assertTrue(provider.lease.isClosed());assertEquals(1,provider.closed.get());
    }
    @Test public void expiredOrCancelledReceiversNeverQueryHost()throws Exception {
        current.set(false);rejected(reply->{});assertEquals(0,provider.calls);
        current.set(true);receiverDeadline=SystemClock.elapsedRealtime();rejected(reply->{});assertEquals(0,provider.calls);
    }
}
