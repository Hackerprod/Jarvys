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
    private String configText;
    private ReplyProvider provider;
    private PackageInfo host;
    private AtomicBoolean current;
    private final String hostName="com.jarvys.agent";
    public static final class ReplyProvider extends ContentProvider {
        String appId;
        int calls;
        Bundle request;
        Consumer<Bundle> mutate=result->{};
        @Override public Bundle call(String method,String arg,Bundle input) {
            assertEquals("describe_installed",method);assertNull(arg);calls++;request=new Bundle(input);
            assertEquals(4,input.keySet().size());
            assertTrue(input.getLong("deadlineElapsedMs")<=SystemClock.elapsedRealtime()+2000L);
            Bundle result=new Bundle(input);
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
        configText=new JSONObject().put("schemaVersion",1).put("appId",context.getPackageName()).put("name","Test").put("entryPoint","www/index.html")
            .put("capabilities",new org.json.JSONArray().put("documents").put("notifications"))
            .put("documentBroker",new JSONObject().put("packageName",hostName).put("certificateSha256",digest.toString())).toString();
        config=FactoryConfig.parse(configText,context.getPackageName());
        provider=new ReplyProvider();provider.appId=context.getPackageName();provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal(info.authority,provider);
    }
    private NativeInstalledSource describe()throws Exception{return NativeInstalledSource.describe(context,config,"notifications",SystemClock.elapsedRealtime()+10000L,current::get);}
    private void rejected(Consumer<Bundle> change){provider.mutate=change;assertThrows(FactoryException.class,()->describe());}
    @Test public void exactSourceUsesAuthenticatedReceiptAndBudgets()throws Exception{
        NativeInstalledSource value=describe();assertEquals(context.getPackageName(),value.appId);assertEquals(42L,value.versionCode);
        assertEquals(repeat('a'),value.certificateSha256);assertEquals(repeat('b'),value.apkSha256);assertEquals(repeat('c'),value.recordSha256);
        assertEquals(ResourceBudgets.legacy(),value.resources);assertEquals(1,provider.calls);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("protocolVersion","nonce","capability","deadlineElapsedMs")),provider.request.keySet());
    }
    @Test public void localSourceUsesInstalledPackageAndPackagedBudgetsWithoutHostCall()throws Exception{
        PackageInfo own=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
        own.signatures=new Signature[]{new Signature(new byte[]{9,7,5,3})};own.versionCode=42;
        shadowOf(context.getPackageManager()).installPackage(own);
        NativeInstalledSource value=NativeInstalledSource.describeLocal(context,config,"notifications",configText);
        assertEquals(context.getPackageName(),value.appId);assertEquals(42L,value.versionCode);
        assertTrue(value.certificateSha256.matches("[a-f0-9]{64}"));assertTrue(value.apkSha256.matches("[a-f0-9]{64}"));
        assertTrue(value.recordSha256.matches("[a-f0-9]{64}"));assertEquals(config.resources,value.resources);
        assertEquals(0,provider.calls);
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
}
