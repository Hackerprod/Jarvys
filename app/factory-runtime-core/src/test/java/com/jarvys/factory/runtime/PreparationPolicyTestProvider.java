package com.jarvys.factory.runtime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import com.jarvys.factory.contract.ResourceBudgets;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/** Synthetic authenticated provider for runtime snapshot preflight; it grants no effect authority. */
public final class PreparationPolicyTestProvider extends ContentProvider {
    public volatile NativeInstalledSource.PolicyMode mode=NativeInstalledSource.PolicyMode.ALLOW_ALWAYS;
    public volatile ResourceBudgets resources=ResourceBudgets.legacy();
    public volatile int calls;
    private String appId;
    public static PreparationPolicyTestProvider install(Context context)throws Exception {
        String host=DocumentBrokerIdentity.PRIMARY;
        PackageInfo packageInfo=context.getPackageManager().getPackageInfo(host,PackageManager.GET_PROVIDERS|PackageManager.GET_SIGNATURES|(android.os.Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:0));
        packageInfo.applicationInfo.packageName=host;packageInfo.applicationInfo.enabled=true;packageInfo.applicationInfo.uid=17777;
        ProviderInfo info=new ProviderInfo();info.packageName=host;info.authority=host+".factory.operations";
        info.name="com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider";info.enabled=true;info.exported=true;info.applicationInfo=packageInfo.applicationInfo;
        packageInfo.providers=new ProviderInfo[]{info};Shadows.shadowOf(context.getPackageManager()).installPackage(packageInfo);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(17777,host);
        PreparationPolicyTestProvider provider=new PreparationPolicyTestProvider();provider.appId=context.getPackageName();provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal(info.authority,provider);return provider;
    }
    @Override public Bundle call(String method,String arg,Bundle request) {
        assertEquals("describe_installed",method);assertNull(arg);assertNotNull(request);assertEquals(5,request.keySet().size());
        assertEquals(Integer.valueOf(1),request.get("protocolVersion"));assertTrue(request.get("nonce") instanceof String);
        assertTrue(request.get("deadlineElapsedMs") instanceof Long);
        assertTrue(request.getLong("deadlineElapsedMs")>SystemClock.elapsedRealtime());
        String operation=request.getString("operation");assertTrue("share.file".equals(operation)||"audio.play".equals(operation));
        assertEquals("share.file".equals(operation)?"share":"audio",request.getString("capability"));
        calls++;
        Bundle result=new Bundle(request);
        result.putString("appId",appId);result.putString("certificateSha256",hex('a'));result.putString("apkSha256",hex('b'));
        result.putString("recordSha256",hex('c'));result.putLong("versionCode",1L);
        result.putString("resourceBudgets",ResourceBudgetsJson.toJson(resources).toString());result.putString("policyMode",mode.name());
        return result;
    }
    private static String hex(char value){char[]bytes=new char[64];java.util.Arrays.fill(bytes,value);return new String(bytes);}
    @Override public boolean onCreate(){return true;}
    @Override public Cursor query(Uri uri,String[]projection,String selection,String[]args,String order){throw new AssertionError();}
    @Override public String getType(Uri uri){throw new AssertionError();}
    @Override public Uri insert(Uri uri,ContentValues values){throw new AssertionError();}
    @Override public int delete(Uri uri,String selection,String[]args){throw new AssertionError();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[]args){throw new AssertionError();}
}
