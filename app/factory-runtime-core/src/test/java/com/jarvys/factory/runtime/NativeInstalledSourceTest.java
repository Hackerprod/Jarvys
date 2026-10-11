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
import android.os.Bundle;
import android.os.SystemClock;
import com.jarvys.factory.contract.ResourceBudgets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=27, manifest=Config.NONE, application=Application.class)
public class NativeInstalledSourceTest {
    private Context context;
    private FactoryConfig config;
    private ReplyProvider provider;
    private PackageInfo host;
    private AtomicBoolean current;
    private final String hostName="com.jarvys.agent";
    public static final class ReplyProvider extends ContentProvider {
        String appId;
        int calls;
        Bundle request;
        String policyMode="ALLOW_ALWAYS";
        boolean oldHost;
        Consumer<Bundle> mutate=result->{};
        @Override public Bundle call(String method,String arg,Bundle input) {
            assertEquals("describe_installed",method);assertNull(arg);calls++;request=new Bundle(input);
            assertEquals(input.containsKey("operation")?5:4,input.keySet().size());
            if(oldHost && input.containsKey("operation")) throw new IllegalArgumentException("Unsupported descriptor");
            assertTrue(input.getLong("deadlineElapsedMs")<=SystemClock.elapsedRealtime()+2000L);
            Bundle result=new Bundle(input);
            if(input.containsKey("operation")) result.putString("policyMode",policyMode);
            result.putString("appId",appId);result.putString("certificateSha256",repeat('a'));result.putString("apkSha256",repeat('b'));
            result.putString("recordSha256",repeat('c'));result.putLong("versionCode",42L);
            result.putString("resourceBudgets",ResourceBudgetsJson.toJson(ResourceBudgets.legacy()).toString());
            mutate.accept(result);return result;
        }
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri u,String[]p,String s,String[]a,String o){throw new AssertionError();}
        @Override public String getType(Uri u){throw new AssertionError();}
        @Override public Uri insert(Uri u,ContentValues v){throw new AssertionError();}
        @Override public int delete(Uri u,String s,String[]a){throw new AssertionError();}
        @Override public int update(Uri u,ContentValues v,String s,String[]a){throw new AssertionError();}
    }
    private static String repeat(char c){char[]v=new char[64];java.util.Arrays.fill(v,c);return new String(v);}
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();current=new AtomicBoolean(true);
        Signature signature=new Signature(new byte[]{1,3,5,7});
        StringBuilder digest=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))digest.append(String.format("%02x",b&255));
        host=new PackageInfo();host.packageName=hostName;host.signatures=new Signature[]{signature};host.versionCode=1;
        host.applicationInfo=new ApplicationInfo();host.applicationInfo.packageName=hostName;host.applicationInfo.uid=23999;host.applicationInfo.enabled=true;
        ProviderInfo info=new ProviderInfo();info.packageName=hostName;info.authority=hostName+".factory.operations";
        info.name="com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider";info.enabled=true;info.exported=true;info.applicationInfo=host.applicationInfo;
        host.providers=new ProviderInfo[]{info};shadowOf(context.getPackageManager()).installPackage(host);shadowOf(context.getPackageManager()).setPackagesForUid(23999,hostName);
        config=FactoryConfig.parse(new JSONObject().put("schemaVersion",1).put("appId",context.getPackageName()).put("name","Test").put("entryPoint","www/index.html")
            .put("capabilities",new org.json.JSONArray().put("notifications"))
            .put("documentBroker",new JSONObject().put("packageName",hostName).put("certificateSha256",digest.toString())).toString(),context.getPackageName());
        provider=new ReplyProvider();provider.appId=context.getPackageName();provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal(info.authority,provider);
    }
    private NativeInstalledSource describe()throws Exception{return NativeInstalledSource.describe(context,config,"notifications",SystemClock.elapsedRealtime()+10000L,current::get);}
    private NativeInstalledSource describeOperation(String operation)throws Exception{return NativeInstalledSource.describeOperation(context,config,operation,SystemClock.elapsedRealtime()+10000L,current::get);}
    private void rejectedOperation(Consumer<Bundle> change){
        provider.mutate=change;
        FactoryException error=assertThrows(FactoryException.class,()->describeOperation("notifications.cancel"));
        assertEquals("SOURCE_UNAVAILABLE",error.code);
    }
    private void rejected(Consumer<Bundle> change){provider.mutate=change;assertThrows(FactoryException.class,()->describe());}
    @Test public void exactSourceUsesAuthenticatedReceiptAndBudgets()throws Exception{
        NativeInstalledSource value=describe();assertEquals(context.getPackageName(),value.appId);assertEquals(42L,value.versionCode);
        assertEquals(repeat('a'),value.certificateSha256);assertEquals(repeat('b'),value.apkSha256);assertEquals(repeat('c'),value.recordSha256);
        assertEquals(ResourceBudgets.legacy(),value.resources);assertEquals(1,provider.calls);assertNull(value.policyMode);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("protocolVersion","nonce","capability","deadlineElapsedMs")),provider.request.keySet());
    }
    @Test public void rejectsExtraMissingWrongTypeAndReplayFields(){
        rejected(b->b.putBoolean("unexpected",true));rejected(b->b.remove("recordSha256"));rejected(b->b.putLong("protocolVersion",1L));
        rejected(b->b.putString("nonce",repeat('d')));rejected(b->b.putString("capability","network"));
        rejected(b->b.putInt("deadlineElapsedMs",1));rejected(b->b.putLong("deadlineElapsedMs",b.getLong("deadlineElapsedMs")+1));
        rejected(b->b.putString("appId","example.other"));rejected(b->b.putInt("versionCode",42));rejected(b->b.putLong("versionCode",0));
        rejected(b->b.putString("certificateSha256",repeat('A')));rejected(b->b.putString("apkSha256","bad"));rejected(b->b.putInt("recordSha256",1));
        rejected(b->b.putString("resourceBudgets","{\"version\":1,\"version\":1}"));rejected(b->b.putString("resourceBudgets","{\"unknown\":1}"));
        rejected(b->b.putInt("resourceBudgets",1));
    }
    @Test public void expiredAndCancelledCallersNeverQueryHost(){
        current.set(false);assertThrows(FactoryException.class,()->describe());assertEquals(0,provider.calls);
        current.set(true);assertThrows(FactoryException.class,()->NativeInstalledSource.describe(context,config,"notifications",SystemClock.elapsedRealtime(),current::get));
        assertEquals(0,provider.calls);
    }
    @Test public void rejectsLateReplyAndReceiverRevocation(){
        rejected(b->current.set(false));current.set(true);
        rejected(b->ShadowSystemClock.advanceBy(2001,TimeUnit.MILLISECONDS));
    }
    @Test public void rejectsHostReplacementSharedUidAndProviderMutation(){
        rejected(b->shadowOf(context.getPackageManager()).setPackagesForUid(23999,hostName,"example.shared"));
        shadowOf(context.getPackageManager()).setPackagesForUid(23999,hostName);
        rejected(b->{host.providers[0].exported=false;shadowOf(context.getPackageManager()).installPackage(host);});
        host.providers[0].exported=true;shadowOf(context.getPackageManager()).installPackage(host);
        rejected(b->{host.signatures=new Signature[]{new Signature(new byte[]{2,4,6,8})};shadowOf(context.getPackageManager()).installPackage(host);});
    }
    @Test public void undeclaredCapabilityNeverQueriesHost(){
        assertThrows(FactoryException.class,()->NativeInstalledSource.describe(context,config,"network",SystemClock.elapsedRealtime()+10000L,current::get));
        assertEquals(0,provider.calls);
    }
    @Test public void operationQueryReturnsAllPointInTimeModesWithoutAcquiringAnEffect()throws Exception{
        for(NativeInstalledSource.PolicyMode mode:NativeInstalledSource.PolicyMode.values()) {
            provider.policyMode=mode.name();
            NativeInstalledSource value=describeOperation("notifications.cancel");
            assertEquals(mode,value.policyMode);assertEquals(context.getPackageName(),value.appId);
            assertEquals(ResourceBudgets.legacy(),value.resources);
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("protocolVersion","nonce","capability","deadlineElapsedMs","operation")),provider.request.keySet());
            assertEquals("notifications.cancel",provider.request.getString("operation"));assertEquals("notifications",provider.request.getString("capability"));
        }
        assertEquals(3,provider.calls); // The fixture rejects any acquire call or document-provider access.
        assertNull(shadowOf((Application)context).getNextStartedActivity());
    }
    @Test public void rejectsMalformedMissingMismatchedAndUnsolicitedPolicyReplies(){
        rejectedOperation(b->b.remove("operation"));rejectedOperation(b->b.putString("operation","notifications.show"));
        rejectedOperation(b->b.putInt("operation",1));rejectedOperation(b->b.remove("policyMode"));
        rejectedOperation(b->b.putInt("policyMode",1));rejectedOperation(b->b.putString("policyMode",null));
        rejectedOperation(b->b.putString("policyMode","allow_always"));rejectedOperation(b->b.putString("policyMode","UNKNOWN"));
        rejectedOperation(b->b.putString("policyMode"," ALLOW_ALWAYS"));rejectedOperation(b->b.putString("reviewToken",repeat('d')));
        rejectedOperation(b->b.putBinder("lease",new android.os.Binder()));
        rejectedOperation(b->b.putString("nonce",repeat('e')));rejectedOperation(b->b.putString("capability","audio"));
        rejectedOperation(b->b.putString("appId","example.other"));rejectedOperation(b->b.putInt("versionCode",42));
        rejectedOperation(b->b.putString("apkSha256","bad"));rejectedOperation(b->b.putString("resourceBudgets","{}"));
        rejected(b->{b.putString("operation","notifications.cancel");b.putString("policyMode","ALLOW_ALWAYS");});
    }
    @Test public void unknownAndUndeclaredOperationsNeverQueryHost(){
        assertThrows(IllegalArgumentException.class,()->describeOperation("unknown.operation"));
        assertThrows(IllegalArgumentException.class,()->describeOperation("runtime.info"));
        FactoryException error=assertThrows(FactoryException.class,()->describeOperation("audio.play"));
        assertEquals("SOURCE_UNAVAILABLE",error.code);assertEquals(0,provider.calls);
    }
    @Test public void policyQueryRequiresCurrentCallerDeadlineAndAuthenticatedHost(){
        current.set(false);assertThrows(FactoryException.class,()->describeOperation("notifications.cancel"));assertEquals(0,provider.calls);
        current.set(true);
        assertThrows(FactoryException.class,()->NativeInstalledSource.describeOperation(context,config,"notifications.cancel",SystemClock.elapsedRealtime(),current::get));
        assertEquals(0,provider.calls);
        rejectedOperation(b->current.set(false));current.set(true);
        rejectedOperation(b->shadowOf(context.getPackageManager()).setPackagesForUid(23999,hostName,"example.shared"));
        shadowOf(context.getPackageManager()).setPackagesForUid(23999,hostName);
        rejectedOperation(b->{host.signatures=new Signature[]{new Signature(new byte[]{2,4,6,8})};shadowOf(context.getPackageManager()).installPackage(host);});
    }
    @Test public void queryIsNotACachedGrantAndRejectsLateReply()throws Exception{
        NativeInstalledSource before=describeOperation("notifications.cancel");
        String firstNonce=provider.request.getString("nonce");provider.policyMode="DENY";
        NativeInstalledSource after=describeOperation("notifications.cancel");
        assertEquals(NativeInstalledSource.PolicyMode.ALLOW_ALWAYS,before.policyMode);
        assertEquals(NativeInstalledSource.PolicyMode.DENY,after.policyMode);
        assertNotEquals(firstNonce,provider.request.getString("nonce"));assertEquals(2,provider.calls);
        rejectedOperation(b->ShadowSystemClock.advanceBy(2001,TimeUnit.MILLISECONDS));
    }
    @Test public void oldHostRejectsOptionalQueryExplicitlyWhileLegacyDescriptionStillWorks()throws Exception{
        provider.oldHost=true;
        FactoryException error=assertThrows(FactoryException.class,()->describeOperation("notifications.cancel"));
        assertEquals("SOURCE_UNAVAILABLE",error.code);assertEquals(1,provider.calls);
        assertNull(describe().policyMode);assertEquals(2,provider.calls);assertFalse(provider.request.containsKey("operation"));
    }
    @Test public void oldShapeReplyCannotBeUpgradedToImplicitAllow(){
        rejectedOperation(b->{b.remove("operation");b.remove("policyMode");});
        assertEquals(1,provider.calls);
    }

}
