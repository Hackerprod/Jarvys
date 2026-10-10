package com.jarvys.factory.runtime;

import android.os.Handler;
import android.os.Looper;
import android.hardware.biometrics.BiometricPrompt;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Synthetic callbacks and monotonic time only. Never opens or completes a device prompt. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,30,35},manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class BiometricAuthenticationTest {
    static class Driver implements BiometricAuthentication.Driver {
        BiometricAuthentication.Availability availability=BiometricAuthentication.Availability.READY;
        BiometricAuthentication.Events events;int starts,cancels,statusCalls;boolean throwStart,throwCancel,throwStatus,syncSuccess,syncCancel;
        public BiometricAuthentication.Availability status(){statusCalls++;if(throwStatus)throw new IllegalStateException();return availability;}
        public void start(BiometricAuthentication.Request request,BiometricAuthentication.Events events){this.events=events;starts++;if(syncSuccess)events.success(2);if(throwStart)throw new IllegalStateException();}
        public void cancel(){cancels++;if(syncCancel)events.error(BiometricPrompt.BIOMETRIC_ERROR_CANCELED);if(throwCancel)throw new IllegalStateException();}
    }
    static class Result implements BiometricAuthentication.Sink {
        JSONObject value;String code,message;int calls;
        public void ok(JSONObject value){this.value=value;calls++;}
        public void fail(String code,String message){this.code=code;this.message=message;calls++;}
    }
    Driver driver;BiometricAuthentication controller;BiometricAuthentication.ProcessGate gate;Handler handler;long now;boolean active;
    @Before public void setup(){driver=new Driver();gate=new BiometricAuthentication.ProcessGate();handler=new Handler(Looper.getMainLooper());now=1000;active=true;controller=new BiometricAuthentication(driver,()->now,handler,gate);}
    @After public void cleanup(){if(controller.isActive()){driver.syncCancel=true;controller.cancel();}Shadows.shadowOf(Looper.getMainLooper()).idleFor(3000,TimeUnit.MILLISECONDS);}
    BiometricAuthentication.Request request()throws Exception{return BiometricAuthentication.Request.parse(new JSONObject().put("reason","Synthetic local check"));}
    Result start()throws Exception{Result result=new Result();controller.authenticate(request(),()->active,result);return result;}
    void advance(long ms){while(ms>0){long step=Math.min(ms,100);now+=step;Shadows.shadowOf(Looper.getMainLooper()).idleFor(step,TimeUnit.MILLISECONDS);ms-=step;}}
    @Test public void validationRejectsMissingTypesUnknownAndSpoofingText()throws Exception{
        for(String raw:new String[]{"{}","{\"reason\":null}","{\"reason\":1}","{\"reason\":true}","{\"reason\":\"ok\",\"fallback\":true}"})assertThrows(Exception.class,()->BiometricAuthentication.Request.parse(new JSONObject(raw)));
        for(String bad:new String[]{"","   ","\u00a0\u2003","\n","x\u202eabc","x\u200b","x\u2066","x\u2028","x\u2029","x\ud800","x\udfff",new String(new char[161]).replace('\0','x')})
            assertThrows(FactoryException.class,()->BiometricAuthentication.Request.parse(new JSONObject().put("reason",bad)));
        assertEquals("Verificación 🔐",BiometricAuthentication.Request.parse(new JSONObject().put("reason","Verificación 🔐")).reason);
        assertEquals(160,BiometricAuthentication.Request.parse(new JSONObject().put("reason",new String(new char[160]).replace('\0','x'))).reason.length());
    }
    @Test public void availabilityIsCoarseAndNeverAnAuthenticationReceipt()throws Exception{
        for(BiometricAuthentication.Availability status:BiometricAuthentication.Availability.values()){
            driver.availability=status;JSONObject value=controller.availability();assertEquals(status==BiometricAuthentication.Availability.READY,value.getBoolean("available"));
            assertEquals(6,value.length());assertFalse(value.has("authenticated"));assertFalse(value.getBoolean("credentialFallback"));assertEquals(30,value.getInt("minimumApi"));
        }
        driver.throwStatus=true;assertEquals("unavailable",controller.availability().getString("status"));assertEquals(0,driver.starts);
    }
    @Test public void unavailableOrRevokedNeverStarts()throws Exception{
        active=false;assertEquals("CANCELLED",assertThrows(FactoryException.class,()->start()).code);assertEquals(0,driver.starts);
        active=true;for(BiometricAuthentication.Availability status:new BiometricAuthentication.Availability[]{BiometricAuthentication.Availability.UNSUPPORTED,BiometricAuthentication.Availability.UNAVAILABLE}){
            driver.availability=status;assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->start()).code);
        }assertEquals(0,driver.starts);
    }
    @Test public void mismatchAndHelpAreNonterminalThenOnlyOneNarrowSuccess()throws Exception{
        Result result=start();driver.events.failed();driver.events.help();assertEquals(0,result.calls);assertTrue(controller.isActive());
        driver.events.success(2);assertEquals(1,result.calls);assertTrue(result.value.getBoolean("authenticated"));assertFalse(result.value.getBoolean("authorizesOtherActions"));assertFalse(result.value.getBoolean("simulated"));assertEquals(5,result.value.length());assertFalse(result.value.toString().contains("Synthetic"));
        driver.events.success(2);driver.events.error(1);assertEquals(1,result.calls);assertFalse(controller.isUiBusy());assertEquals(0,driver.cancels);
    }
    @Test public void credentialUnknownAndNullResultTypesCannotAuthenticate()throws Exception{
        for(int type:new int[]{1,0,99}){Result result=start();driver.events.success(type);assertEquals("AUTHENTICATION_FAILED",result.code);assertNull(result.value);assertFalse(controller.isUiBusy());}
    }
    @Test public void terminalErrorsStayGenericAndDistinguishUserSystemAndTimeout()throws Exception{
        Result user=start();driver.events.error(BiometricAuthentication.NEGATIVE_BUTTON);assertEquals("CANCELLED",user.code);assertTrue(user.message.contains("user"));
        Result system=start();driver.events.error(BiometricPrompt.BIOMETRIC_ERROR_CANCELED);assertEquals("CANCELLED",system.code);assertTrue(system.message.contains("system"));
        Result timeout=start();driver.events.error(BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT);assertEquals("TIMEOUT",timeout.code);
        advance(60000);Result unknown=start();driver.events.error(987654);assertEquals("UNAVAILABLE",unknown.code);assertFalse(unknown.message.contains("987654"));
    }
    @Test public void cancelRevokesBeforeSignalAndWaitsForTerminalAcknowledgment()throws Exception{
        Result first=start();BiometricAuthentication.Events old=driver.events;JSONObject receipt=controller.cancellation();assertTrue(receipt.getBoolean("cancelRequested"));assertFalse(receipt.getBoolean("cancellationAcknowledged"));assertEquals("CANCELLED",first.code);assertEquals(1,first.calls);assertFalse(controller.isActive());assertTrue(controller.isUiBusy());
        assertEquals("BUSY",assertThrows(FactoryException.class,()->start()).code);old.success(2);assertNull(first.value);assertFalse(controller.isUiBusy());
        Result next=start();old.error(1);old.success(2);assertTrue(controller.isUiBusy());assertEquals(0,next.calls);driver.events.success(2);assertNotNull(next.value);
    }
    @Test public void synchronousCancellationCallbackCannotResolveSuccessOrReleaseNewOwner()throws Exception{
        Result result=start();driver.syncCancel=true;controller.cancel();assertEquals("CANCELLED",result.code);assertEquals(1,result.calls);assertFalse(controller.isUiBusy());
        Result next=start();driver.events.success(2);assertNotNull(next.value);
    }
    @Test public void synchronousStartSuccessThenExceptionSettlesOnce()throws Exception{
        driver.syncSuccess=true;driver.throwStart=true;Result result=start();assertNotNull(result.value);assertEquals(1,result.calls);assertEquals(0,driver.cancels);assertFalse(controller.isUiBusy());
    }
    @Test public void partialStartExceptionRequestsCancelAndFailsClosedWithoutAck()throws Exception{
        driver.throwStart=true;Result result=start();assertEquals("UNAVAILABLE",result.code);assertEquals(1,driver.cancels);assertTrue(controller.isUiBusy());advance(2000);assertTrue(gate.poisoned());assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->start()).code);
        driver.events.success(2);assertTrue(controller.isUiBusy());assertNull(result.value);assertEquals("unavailable",controller.availability().getString("status"));
    }
    @Test public void cleanupExceptionPoisonsSharedGateAndCannotIssueSuccess()throws Exception{
        Result result=start();driver.throwCancel=true;assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->controller.cancellation()).code);assertEquals("UNAVAILABLE",result.code);driver.events.success(2);assertNull(result.value);assertTrue(controller.isUiBusy());
        BiometricAuthentication other=new BiometricAuthentication(new Driver(),()->now,handler,gate);assertTrue(other.isUiBusy());assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->other.authenticate(request(),()->true,new Result())).code);
    }
    @Test public void synchronousCancelAcknowledgmentThenThrowStillPoisons()throws Exception{
        Result result=start();driver.syncCancel=true;driver.throwCancel=true;assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->controller.cancellation()).code);assertEquals("UNAVAILABLE",result.code);assertTrue(gate.poisoned());assertTrue(controller.isUiBusy());assertNull(result.value);
    }
    @Test public void nativeDeadlinePrecedesSdkBoundAndLateSuccessIsDiscarded()throws Exception{
        Result result=start();advance(60000);assertEquals("TIMEOUT",result.code);assertEquals(1,driver.cancels);assertTrue(controller.isUiBusy());driver.events.success(2);assertNull(result.value);assertFalse(controller.isUiBusy());
        Result exact=start();now+=60000;driver.events.success(2);assertEquals("TIMEOUT",exact.code);assertNull(exact.value);assertFalse(controller.isUiBusy());
    }
    @Test public void authorityRevocationCancelsWithoutAutomaticReplay()throws Exception{
        Result result=start();active=false;advance(100);assertEquals("CANCELLED",result.code);assertEquals(1,driver.cancels);driver.events.error(5);active=true;advance(1000);assertEquals(1,driver.starts);assertNull(result.value);
    }
    @Test public void quotasAreRollingAndSharedAcrossControllerRecreation()throws Exception{
        for(int i=0;i<3;i++){Result result=start();driver.events.success(2);assertNotNull(result.value);}
        Driver replacement=new Driver();BiometricAuthentication next=new BiometricAuthentication(replacement,()->now,handler,gate);
        assertEquals("RATE_LIMITED",assertThrows(FactoryException.class,()->next.authenticate(request(),()->true,new Result())).code);assertEquals(0,replacement.starts);
        advance(59999);assertEquals("RATE_LIMITED",assertThrows(FactoryException.class,()->start()).code);advance(1);Result result=start();driver.events.success(2);assertNotNull(result.value);
    }
    @Test public void noOpCancelFloodIsBoundedButActiveCancellationAlwaysWorks()throws Exception{
        for(int i=0;i<8;i++)assertFalse(controller.cancellation().getBoolean("cancelRequested"));
        assertEquals("RATE_LIMITED",assertThrows(FactoryException.class,()->controller.cancellation()).code);
        Result result=start();driver.syncCancel=true;assertTrue(controller.cancellation().getBoolean("cancelRequested"));assertEquals("CANCELLED",result.code);
        advance(10000);assertFalse(controller.cancellation().getBoolean("cancelRequested"));
    }
    @Test public void processDrainSurvivesActivityReplacementAndLateAckCannotClearPoison()throws Exception{
        start();BiometricAuthentication.Events old=driver.events;controller.cancel();BiometricAuthentication replacement=new BiometricAuthentication(new Driver(),()->now,handler,gate);assertTrue(replacement.isUiBusy());advance(2000);assertTrue(gate.poisoned());old.error(5);assertTrue(replacement.isUiBusy());assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->replacement.authenticate(request(),()->true,new Result())).code);
    }
}
