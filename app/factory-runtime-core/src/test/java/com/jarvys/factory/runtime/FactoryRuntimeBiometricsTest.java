package com.jarvys.factory.runtime;

import android.app.Activity;
import android.os.*;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Injected bridge/lifecycle fixtures only: no physical biometric, system prompt or Chromium evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,29,30,35},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeBiometricsTest {
    public static class Screen extends Activity {
        boolean focus=true;
        @Override public boolean hasWindowFocus(){return focus;}
    }
    static class Host implements FactoryRuntime.Host {
        boolean active=true,preview,session=true;
        String appId;
        final MemoryBackend memory=new MemoryBackend();
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return "{\"schemaVersion\":1,\"appId\":\""+appId+"\",\"name\":\"Untrusted app label\",\"entryPoint\":\"www/index.html\",\"capabilities\":[\"biometrics\",\"share\"]}";}
        public boolean isPreview(){return preview;}
        public boolean isActive(){return active;}
        public boolean isSessionOpen(){return session;}
        public BoundedStore.Backend storage(){return memory;}
    }
    static class Driver implements BiometricAuthentication.Driver {
        BiometricAuthentication.Availability state=BiometricAuthentication.Availability.READY;
        BiometricAuthentication.Events events;
        BiometricAuthentication.Request arguments;
        int starts,cancels;
        boolean throwCancel;
        public BiometricAuthentication.Availability status(){return state;}
        public void start(BiometricAuthentication.Request request,BiometricAuthentication.Events callback){starts++;arguments=request;events=callback;}
        public void cancel(){cancels++;if(throwCancel)throw new IllegalStateException("Synthetic cancellation failure");}
    }
    static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        int count;
        public void postMessage(String text){try{value=new JSONObject(text);count++;}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] bytes){throw new AssertionError();}
        JSONObject result()throws Exception{assertNotNull(value);assertTrue(value.toString(),value.getBoolean("ok"));return value.getJSONObject("result");}
        String error()throws Exception{assertNotNull(value);assertFalse(value.toString(),value.getBoolean("ok"));return value.getJSONObject("error").getString("code");}
    }
    Screen screen;
    Host host;
    FactoryRuntime runtime;
    Driver driver;
    BiometricAuthentication.ProcessGate gate;
    long now;
    int sequence;
    static Field field(String name)throws Exception{Field f=FactoryRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @Before public void setup()throws Exception{
        screen=Robolectric.buildActivity(Screen.class).setup().get();
        now=1000;gate=new BiometricAuthentication.ProcessGate();
        installRuntime();
    }
    void installRuntime()throws Exception{
        host=new Host();host.appId=screen.getPackageName();
        runtime=new FactoryRuntime(screen,new FrameLayout(screen),host);
        field("config").set(runtime,FactoryConfig.parse(host.configuration(),screen.getPackageName()));
        field("store").set(runtime,new BoundedStore(host.memory));
        driver=new Driver();
        field("biometrics").set(runtime,new BiometricAuthentication(driver,()->now,new Handler(Looper.getMainLooper()),gate));
        runtime.onResume();
    }
    void closeRuntime()throws Exception{
        runtime.close();assertTrue(((ThreadPoolExecutor)field("io").get(runtime)).awaitTermination(10,TimeUnit.SECONDS));
    }
    @After public void cleanup()throws Exception{
        closeRuntime();Shadows.shadowOf(Looper.getMainLooper()).idle();screen.finish();
    }
    String arguments(){return "{\"reason\":\"Confirm this local action\"}";}
    String envelope(String id,String method,String args)throws Exception{return new JSONObject().put("v",1).put("id",id).put("method",method).put("args",new JSONObject(args)).toString();}
    Response raw(String value)throws Exception{
        Response response=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);receive.invoke(runtime,value,response);return response;
    }
    Response request(String method,String args)throws Exception{return request("bio"+(++sequence),method,args);}
    Response request(String id,String method,String args)throws Exception{return raw(envelope(id,method,args));}
    void advance(int ms){
        for(int remaining=ms;remaining>0;){
            int step=Math.min(100,remaining);now+=step;
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(step,TimeUnit.MILLISECONDS);remaining-=step;
        }
    }
    void invalidate()throws Exception{Method method=FactoryRuntime.class.getDeclaredMethod("invalidate");method.setAccessible(true);method.invoke(runtime);}
    void saturated()throws Exception{field("rateWindow").setLong(runtime,SystemClock.elapsedRealtime());field("rateCount").setInt(runtime,80);}
    void ordinaryRateReset()throws Exception{field("rateCount").setInt(runtime,0);}
    Response share()throws Exception{return request("share.text","{\"text\":\"Synthetic fixture\"}");}

    @Test public void previewCannotQueryAuthenticateOrCancelAndNeverStartsDriver()throws Exception{
        host.preview=true;
        for(String method:new String[]{"availability","authenticate","cancel"})
            assertEquals("UNAVAILABLE",request("biometrics."+method,method.equals("authenticate")?arguments():"{}").error());
        assertEquals(0,driver.starts);assertEquals(0,driver.cancels);
    }
    @Test public void capabilityOriginAndMainFrameAreRequiredForAllThreeMethods()throws Exception{
        FactoryConfig enabled=(FactoryConfig)field("config").get(runtime);
        field("config").set(runtime,FactoryConfig.parse(host.configuration().replace("[\"biometrics\",\"share\"]","[]"),screen.getPackageName()));
        for(String method:new String[]{"availability","authenticate","cancel"}){
            String name="biometrics."+method,args=method.equals("authenticate")?arguments():"{}";
            assertEquals("CAPABILITY_DENIED",request(name,args).error());
            String raw=envelope("source",name,args);
            assertEquals("UNTRUSTED_SOURCE",assertThrows(FactoryException.class,()->BridgeProtocol.validate("https://evil.invalid",true,raw,enabled)).code);
            assertEquals("UNTRUSTED_SOURCE",assertThrows(FactoryException.class,()->BridgeProtocol.validate(BridgeProtocol.ORIGIN,false,raw,enabled)).code);
        }
        assertEquals(0,driver.starts);
    }
    @Test public void availabilityIsLocalCoarseAndNeverAnAuthenticationReceipt()throws Exception{
        JSONObject result=request("biometrics.availability","{}").result();
        assertTrue(result.getBoolean("available"));assertEquals("ready",result.getString("status"));
        assertEquals("strong",result.getString("biometricClass"));assertFalse(result.getBoolean("credentialFallback"));
        assertFalse(result.has("authenticated"));assertFalse(result.has("token"));assertFalse(result.has("reason"));
        assertEquals(0,driver.starts);
        driver.state=BiometricAuthentication.Availability.UNAVAILABLE;
        assertFalse(request("biometrics.availability","{}").result().getBoolean("available"));
        assertEquals("UNAVAILABLE",request("biometrics.authenticate",arguments()).error());
        assertNull(field("uiOwner").get(runtime));assertEquals(0,driver.starts);
    }
    @Test @Config(sdk={24,29}) public void actualOlderAndroidAdapterIsExplicitlyUnsupported()throws Exception{
        field("biometrics").set(runtime,BiometricAuthentication.installed(screen,new Handler(Looper.getMainLooper())));
        JSONObject result=request("biometrics.availability","{}").result();
        assertFalse(result.getBoolean("available"));assertEquals("unsupported",result.getString("status"));
        assertEquals("UNAVAILABLE",request("biometrics.authenticate",arguments()).error());
        assertFalse(request("biometrics.cancel","{}").result().getBoolean("cancelRequested"));
    }
    @Test public void admissionNeedsFocusButSystemPromptFocusLossDoesNotRevokeSuccess()throws Exception{
        screen.focus=false;assertEquals("BUSY",request("biometrics.authenticate",arguments()).error());assertEquals(0,driver.starts);
        screen.focus=true;Response auth=request("biometrics.authenticate",arguments());
        assertNotNull(field("uiOwner").get(runtime));assertEquals("Confirm this local action",driver.arguments.reason);
        screen.focus=false;advance(100);assertEquals(0,driver.cancels);assertNull(auth.value);
        driver.events.success(2);JSONObject result=auth.result();
        assertTrue(result.getBoolean("authenticated"));assertEquals("strong",result.getString("biometricClass"));
        assertFalse(result.getBoolean("authorizesOtherActions"));assertFalse(result.getBoolean("simulated"));
        assertEquals("installed",result.getString("mode"));assertEquals(5,result.length());
        assertNull(field("uiOwner").get(runtime));driver.events.success(2);assertEquals(1,auth.count);
    }
    @Test public void anotherUiCannotOverlapAuthenticationOrItsCancellationDrain()throws Exception{
        Response auth=request("biometrics.authenticate",arguments());Object owner=field("uiOwner").get(runtime);
        assertEquals("BUSY",share().error());assertEquals("BUSY",request("biometrics.authenticate",arguments()).error());
        assertSame(owner,field("uiOwner").get(runtime));assertEquals(1,driver.starts);
        JSONObject cancel=request("biometrics.cancel","{}").result();
        assertTrue(cancel.getBoolean("cancelRequested"));assertFalse(cancel.getBoolean("cancellationAcknowledged"));
        assertEquals("CANCELLED",auth.error());assertNull(field("uiOwner").get(runtime));
        assertEquals("BUSY",share().error());assertEquals("BUSY",request("biometrics.authenticate",arguments()).error());
        driver.events.error(5);assertTrue(share().result().getBoolean("chooserOpened"));assertEquals(1,driver.starts);
    }
    @Test public void boundedInvalidOrDuplicateCancelCannotRevokeEvenWhenGlobalRateIsSaturated()throws Exception{
        Response auth=request("inflight","biometrics.authenticate",arguments());saturated();
        assertEquals("BUSY",request("inflight","biometrics.cancel","{}").error());
        assertEquals("INVALID_ARGUMENT",request("badargs","biometrics.cancel","{\"fallback\":true}").error());
        assertEquals("INVALID_REQUEST",request("","biometrics.cancel","{}").error());
        assertEquals("INVALID_ARGUMENT",raw("{\"v\":1,\"id\":null,\"method\":\"biometrics.cancel\",\"args\":{}}").error());
        assertEquals(0,driver.cancels);assertNull(auth.value);assertEquals(80,field("rateCount").getInt(runtime));
        driver.events.success(2);assertTrue(auth.result().getBoolean("authenticated"));
    }
    @SuppressWarnings("unchecked") @Test public void validCancelBypassesPendingAndRateSaturationWithoutRequiringWindowFocus()throws Exception{
        Response auth=request("inflight","biometrics.authenticate",arguments());
        Map<String,Object> pending=(Map<String,Object>)field("pending").get(runtime);
        Class<?> reply=Class.forName(FactoryRuntime.class.getName()+"$Reply");Constructor<?> constructor=reply.getDeclaredConstructors()[0];constructor.setAccessible(true);
        for(int i=0;i<15;i++)pending.put("blocked"+i,constructor.newInstance(runtime,"blocked"+i,"runtime.info",new Response(),field("generation").getInt(runtime)));
        assertEquals(16,pending.size());assertEquals("BUSY",request("biometrics.availability","{}").error());
        saturated();screen.focus=false;
        JSONObject result=request("biometrics.cancel","{}").result();assertTrue(result.getBoolean("cancelRequested"));assertFalse(result.getBoolean("cancellationAcknowledged"));
        assertEquals("CANCELLED",auth.error());assertEquals(1,driver.cancels);assertEquals(80,field("rateCount").getInt(runtime));
    }
    @Test public void escapedMethodAndExactOneKiBControlBudgetStillAllowCancellation()throws Exception{
        Response auth=request("biometrics.authenticate",arguments());saturated();
        String escaped="{\"v\":1,\"id\":\"escaped\",\"method\":\"biometrics."+"\\"+"u0063ancel\",\"args\":{}}";
        String padded=escaped+new String(new char[1024-escaped.getBytes(java.nio.charset.StandardCharsets.UTF_8).length]).replace('\0',' ');
        assertEquals(1024,padded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(raw(padded).result().getBoolean("cancelRequested"));assertEquals("CANCELLED",auth.error());assertEquals(1,driver.cancels);
    }
    @Test public void noOpControlsArePerIdRateLimitedAndActiveCancellationAlwaysRemainsAvailable()throws Exception{
        for(int i=0;i<8;i++)assertFalse(request("biometrics.cancel","{}").result().getBoolean("cancelRequested"));
        Response limited=request("limited","biometrics.cancel","{}");assertEquals("RATE_LIMITED",limited.error());assertEquals("limited",limited.value.getString("id"));
        assertEquals(0,field("rateCount").getInt(runtime));
        Response auth=request("biometrics.authenticate",arguments());saturated();
        assertTrue(request("biometrics.cancel","{}").result().getBoolean("cancelRequested"));assertEquals("CANCELLED",auth.error());assertEquals(1,driver.cancels);
    }
    @Test public void globalFailureRevokesAuthAndLateCallbackCannotMintReceipt()throws Exception{
        Response auth=request("biometrics.authenticate",arguments());BiometricAuthentication.Events stale=driver.events;saturated();
        Response global=request("biometrics.availability","{}");assertEquals("RATE_LIMITED",global.error());assertTrue(global.value.isNull("id"));
        assertEquals("CANCELLED",auth.error());assertEquals(1,driver.cancels);assertNull(field("uiOwner").get(runtime));
        stale.success(2);advance(1000);assertEquals(1,auth.count);assertEquals(1,driver.starts);
    }
    @Test public void oversizedAndUnparseableTrafficRetainGlobalFailAllSemantics()throws Exception{
        for(String input:new String[]{"not-json",envelope("oversized","biometrics.cancel","{}")+new String(new char[1024]).replace('\0',' ')}){
            ordinaryRateReset();Response auth=request("biometrics.authenticate",arguments());BiometricAuthentication.Events stale=driver.events;saturated();
            Response global=raw(input);assertEquals("RATE_LIMITED",global.error());assertTrue(global.value.isNull("id"));
            assertEquals("CANCELLED",auth.error());stale.error(5);assertEquals(1,auth.count);
        }
        assertEquals(2,driver.starts);assertEquals(2,driver.cancels);
    }
    @Test public void pauseCancelsAndResumeNeverRestartsAuthentication()throws Exception{
        Response auth=request("biometrics.authenticate",arguments());runtime.onPause();
        assertEquals("CANCELLED",auth.error());assertEquals(1,driver.cancels);assertNull(field("uiOwner").get(runtime));
        assertEquals("CANCELLED",request("biometrics.authenticate",arguments()).error());
        assertEquals("CANCELLED",request("biometrics.availability","{}").error());
        assertEquals("CANCELLED",request("biometrics.cancel","{}").error());
        driver.events.success(2);runtime.onResume();advance(1000);assertEquals(1,driver.starts);assertEquals(1,auth.count);
        Response next=request("biometrics.authenticate",arguments());driver.events.success(2);assertTrue(next.result().getBoolean("authenticated"));
    }
    @Test public void navigationResetAndCloseDiscardOldRepliesAndCallbacks()throws Exception{
        Response first=request("biometrics.authenticate",arguments());BiometricAuthentication.Events old=driver.events;
        invalidate();assertEquals(1,driver.cancels);old.success(2);assertNull(first.value);
        Response second=request("biometrics.authenticate",arguments());old=driver.events;
        runtime.reset();assertEquals(2,driver.cancels);old.error(5);assertNull(second.value);
        Response third=request("biometrics.authenticate",arguments());old=driver.events;
        runtime.close();assertEquals(3,driver.cancels);old.success(2);advance(1000);assertNull(third.value);assertEquals(3,driver.starts);
    }
    @Test public void heartbeatRevokesHostOrSessionAuthorityEvenWithoutAnActivityPause()throws Exception{
        Response first=request("biometrics.authenticate",arguments());BiometricAuthentication.Events old=driver.events;
        host.active=false;advance(100);assertEquals(1,driver.cancels);assertNull(first.value);assertNull(field("uiOwner").get(runtime));
        host.active=true;old.error(5);advance(100);assertEquals(1,driver.starts);
        Response second=request("biometrics.authenticate",arguments());old=driver.events;
        host.session=false;advance(100);assertEquals(2,driver.cancels);assertNull(second.value);assertNull(field("uiOwner").get(runtime));
        host.session=true;old.success(2);advance(100);assertEquals(2,driver.starts);
    }
    @Test public void deadlineCancelsWithoutAuthorizingAndDrainsBeforeNewUi()throws Exception{
        Response auth=request("biometrics.authenticate",arguments());advance(60000);
        assertEquals("TIMEOUT",auth.error());assertEquals(1,driver.cancels);assertEquals("BUSY",share().error());
        driver.events.success(2);assertEquals(1,auth.count);assertTrue(share().result().getBoolean("chooserOpened"));
    }
    @Test public void cancellationWithoutTerminalCallbackPoisonsUiAcrossRecreation()throws Exception{
        Response auth=request("biometrics.authenticate",arguments());BiometricAuthentication.Events old=driver.events;
        assertTrue(request("biometrics.cancel","{}").result().getBoolean("cancelRequested"));assertEquals("CANCELLED",auth.error());
        advance(2000);assertEquals("BUSY",share().error());assertFalse(request("biometrics.availability","{}").result().getBoolean("available"));
        closeRuntime();installRuntime();old.error(5);
        assertEquals("BUSY",share().error());assertEquals("BUSY",request("biometrics.authenticate",arguments()).error());
        assertEquals("UNAVAILABLE",request("biometrics.cancel","{}").error());assertEquals(0,driver.starts);
    }
    @Test public void drainGateSurvivesRecreationAndOnlyTerminalOldCallbackReleasesIt()throws Exception{
        Response oldReply=request("biometrics.authenticate",arguments());Driver oldDriver=driver;
        BiometricAuthentication.Events old=driver.events;closeRuntime();installRuntime();
        assertNull(oldReply.value);assertEquals(1,oldDriver.cancels);
        assertEquals("BUSY",request("biometrics.authenticate",arguments()).error());assertEquals("BUSY",share().error());
        assertFalse(request("biometrics.cancel","{}").result().getBoolean("cancelRequested"));assertEquals(0,driver.cancels);
        old.success(2);Response next=request("biometrics.authenticate",arguments());driver.events.success(2);
        assertTrue(next.result().getBoolean("authenticated"));assertNull(oldReply.value);assertEquals(1,driver.starts);
    }
    @Test public void rollingAuthenticationQuotaSurvivesResetAndRuntimeReplacement()throws Exception{
        for(int i=0;i<3;i++){
            Response auth=request("biometrics.authenticate",arguments());driver.events.success(2);assertTrue(auth.result().getBoolean("authenticated"));runtime.reset();
        }
        closeRuntime();installRuntime();
        assertEquals("RATE_LIMITED",request("biometrics.authenticate",arguments()).error());assertEquals(0,driver.starts);assertNull(field("uiOwner").get(runtime));
        advance(60000);Response next=request("biometrics.authenticate",arguments());driver.events.success(2);assertTrue(next.result().getBoolean("authenticated"));
    }
    @Test public void credentialTypeCannotAuthorizeAndLateOldCallbacksCannotClearNewOwner()throws Exception{
        Response first=request("biometrics.authenticate",arguments());BiometricAuthentication.Events old=driver.events;
        old.success(1);assertEquals("AUTHENTICATION_FAILED",first.error());assertNull(field("uiOwner").get(runtime));
        Response next=request("biometrics.authenticate",arguments());Object owner=field("uiOwner").get(runtime);
        old.error(5);old.success(2);assertSame(owner,field("uiOwner").get(runtime));assertNull(next.value);assertEquals(1,first.count);
        driver.events.success(2);assertTrue(next.result().getBoolean("authenticated"));assertNull(field("uiOwner").get(runtime));
    }
    @Test public void cancellationExceptionFailsClosedAndNeverClaimsClosure()throws Exception{
        Response auth=request("biometrics.authenticate",arguments());driver.throwCancel=true;
        assertEquals("UNAVAILABLE",request("biometrics.cancel","{}").error());assertEquals("UNAVAILABLE",auth.error());
        assertNull(field("uiOwner").get(runtime));assertEquals("BUSY",share().error());
        driver.events.success(2);assertEquals(1,auth.count);assertEquals("UNAVAILABLE",request("biometrics.cancel","{}").error());
    }
    @Test public void rejectedControlFloodSharesQuotaWithoutCancellingOrEmittingGlobalRateFailure()throws Exception{
        Response auth=request("inflight","biometrics.authenticate",arguments());saturated();
        for(int i=0;i<8;i++) {
            Response rejected=request("invalid"+i,"biometrics.cancel","{\"credentialFallback\":true}");
            assertEquals("INVALID_ARGUMENT",rejected.error());assertEquals("invalid"+i,rejected.value.getString("id"));
        }
        Response limited=request("limited","biometrics.cancel","{\"credentialFallback\":true}");
        assertEquals("RATE_LIMITED",limited.error());assertEquals("limited",limited.value.getString("id"));
        Response duplicate=request("inflight","biometrics.cancel","{}");assertEquals("RATE_LIMITED",duplicate.error());assertEquals("inflight",duplicate.value.getString("id"));
        Response noId=request("","biometrics.cancel","{}");assertEquals("INVALID_REQUEST",noId.error());assertTrue(noId.value.isNull("id"));
        assertEquals(0,driver.cancels);assertNull(auth.value);assertEquals(80,field("rateCount").getInt(runtime));
        assertTrue(request("biometrics.cancel","{}").result().getBoolean("cancelRequested"));assertEquals("CANCELLED",auth.error());assertEquals(1,driver.cancels);
    }
    @Test public void previewAndAuthorityRejectedControlsAlsoConsumeBoundedQuota()throws Exception{
        host.preview=true;
        for(int i=0;i<4;i++)assertEquals("UNAVAILABLE",request("biometrics.cancel","{}").error());
        host.preview=false;runtime.onPause();
        for(int i=0;i<4;i++)assertEquals("CANCELLED",request("biometrics.cancel","{}").error());
        Response limited=request("limited","biometrics.cancel","{}");assertEquals("RATE_LIMITED",limited.error());assertEquals("limited",limited.value.getString("id"));
        assertEquals(0,driver.starts);assertEquals(0,driver.cancels);assertEquals(0,field("rateCount").getInt(runtime));
    }
}
