package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Synthetic framework interception only. No enrollment, settings or real authentication. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35},manifest=Config.NONE,shadows={BiometricPlatformTest.Manager.class,BiometricPlatformTest.Prompt.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class BiometricPlatformTest {
    @Implements(value=BiometricManager.class,minSdk=30)
    public static class Manager {
        static int result,mask,calls;
        @Implementation protected int canAuthenticate(int authenticators){mask=authenticators;calls++;return result;}
    }
    @Implements(value=BiometricPrompt.class,minSdk=30)
    public static class Prompt {
        @RealObject BiometricPrompt real;
        static BiometricPrompt shown;static CancellationSignal signal;static BiometricPrompt.AuthenticationCallback callback;static int starts;
        @Implementation protected void authenticate(CancellationSignal cancellation,Executor executor,BiometricPrompt.AuthenticationCallback events){shown=real;signal=cancellation;callback=events;starts++;}
        @Implementation protected void authenticate(BiometricPrompt.CryptoObject crypto,CancellationSignal cancellation,Executor executor,BiometricPrompt.AuthenticationCallback events){throw new AssertionError("Crypto overload forbidden");}
    }
    public static class Screen extends Activity {boolean noManager;@Override public Object getSystemService(String name){return noManager&&Context.BIOMETRIC_SERVICE.equals(name)?null:super.getSystemService(name);}}
    Screen activity;BiometricAuthentication.Driver driver;int type,errors,mismatches,helps;
    BiometricAuthentication.Events events=new BiometricAuthentication.Events(){public void success(int value){type=value;}public void error(int code){errors++;}public void failed(){mismatches++;}public void help(){helps++;}};
    @Before public void setup(){Manager.result=BiometricManager.BIOMETRIC_SUCCESS;Manager.mask=Manager.calls=0;Prompt.shown=null;Prompt.signal=null;Prompt.callback=null;Prompt.starts=0;activity=Robolectric.buildActivity(Screen.class).setup().get();driver=BiometricAuthentication.android(activity,new Handler(Looper.getMainLooper()));Shadows.shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.USE_BIOMETRIC);}
    @After public void close(){driver.cancel();activity.finish();}
    @Test public void checksExactStrongMaskAndCoarsensAllNonSuccessStatuses(){
        assertEquals(BiometricAuthentication.Availability.READY,driver.status());assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG,Manager.mask);
        for(int result:new int[]{BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,999}){Manager.result=result;assertEquals(BiometricAuthentication.Availability.UNAVAILABLE,driver.status());}
        activity.noManager=true;assertEquals(BiometricAuthentication.Availability.UNAVAILABLE,driver.status());assertEquals(0,Prompt.starts);
    }
    @Test public void missingPermissionNeverQueriesManagerOrPrompts()throws Exception{
        Shadows.shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.USE_BIOMETRIC);assertEquals(BiometricAuthentication.Availability.UNAVAILABLE,driver.status());assertEquals(0,Manager.calls);
        assertThrows(IllegalStateException.class,()->driver.start(BiometricAuthentication.Request.parse(new JSONObject().put("reason","Synthetic")),events));assertEquals(0,Prompt.starts);
    }
    @Test public void configuresStrongHumanPromptAndNoCryptoOrFallback()throws Exception{
        driver.start(BiometricAuthentication.Request.parse(new JSONObject().put("reason","Synthetic")),events);assertEquals(1,Prompt.starts);assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG,Prompt.shown.getAllowedAuthenticators());assertTrue(Prompt.shown.isConfirmationRequired());assertEquals("Cancel",Prompt.shown.getNegativeButtonText());assertEquals("Biometric authentication",Prompt.shown.getTitle());assertTrue(Prompt.shown.getDescription().toString().contains("does not authorize"));
        Prompt.callback.onAuthenticationFailed();Prompt.callback.onAuthenticationHelp(999,"Never expose this platform string");assertEquals(1,mismatches);assertEquals(1,helps);assertEquals(0,errors);
        Prompt.callback.onAuthenticationSucceeded(null);assertEquals(0,type);CancellationSignal completed=Prompt.signal;driver.cancel();assertFalse(completed.isCanceled());
    }
    @Test public void permissionAndAvailabilityAreRecheckedBeforeStart()throws Exception{
        assertEquals(BiometricAuthentication.Availability.READY,driver.status());Manager.result=BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;assertThrows(IllegalStateException.class,()->driver.start(BiometricAuthentication.Request.parse(new JSONObject().put("reason","Synthetic")),events));assertEquals(0,Prompt.starts);
    }
    @Test public void cancelSignalsExactActivePromptAndTerminalClearsIt()throws Exception{
        driver.start(BiometricAuthentication.Request.parse(new JSONObject().put("reason","Synthetic")),events);CancellationSignal signal=Prompt.signal;driver.cancel();assertTrue(signal.isCanceled());driver.cancel();
        driver.start(BiometricAuthentication.Request.parse(new JSONObject().put("reason","Synthetic")),events);signal=Prompt.signal;Prompt.callback.onAuthenticationError(1,"Private platform text");assertEquals(1,errors);driver.cancel();assertFalse(signal.isCanceled());
    }
    @Test @Config(sdk={24,28,29},shadows={}) public void olderApisAreExplicitlyUnsupportedWithoutFrameworkAccess()throws Exception{
        // Setup created the guarded fallback, never Manager/Prompt.
        assertEquals(BiometricAuthentication.Availability.UNSUPPORTED,driver.status());assertEquals(0,Manager.calls);assertEquals(0,Prompt.starts);assertThrows(IllegalStateException.class,()->driver.start(BiometricAuthentication.Request.parse(new JSONObject().put("reason","Synthetic")),events));
    }
}
