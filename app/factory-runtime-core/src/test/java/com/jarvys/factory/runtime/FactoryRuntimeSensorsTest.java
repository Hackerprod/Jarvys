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

/** Synthetic bridge/lifecycle fixtures; physical OEM sensor and Chromium tests remain separate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,35},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeSensorsTest {
    public static class Screen extends Activity {boolean focus=true;@Override public boolean hasWindowFocus(){return focus;}}
    static class Host implements FactoryRuntime.Host {
        boolean active=true,preview,session=true;String appId;final MemoryBackend memory=new MemoryBackend();
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return "{\"schemaVersion\":1,\"appId\":\""+appId+"\",\"name\":\"Synthetic\",\"entryPoint\":\"www/index.html\",\"capabilities\":[\"sensors\"]}";}
        public boolean isPreview(){return preview;}public boolean isActive(){return active;}public boolean isSessionOpen(){return session;}public BoundedStore.Backend storage(){return memory;}
    }
    static class Response extends JavaScriptReplyProxy {
        JSONObject value;int count;
        public void postMessage(String text){try{value=new JSONObject(text);count++;}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] bytes){throw new AssertionError();}
        JSONObject result()throws Exception{assertNotNull(value);assertTrue(value.toString(),value.getBoolean("ok"));return value.getJSONObject("result");}
        String error()throws Exception{assertNotNull(value);assertFalse(value.toString(),value.getBoolean("ok"));return value.getJSONObject("error").getString("code");}
    }
    Screen screen;Host host;FactoryRuntime runtime;SensorCaptureTest.Driver driver;long now;int sequence;
    static Field field(String name)throws Exception{Field f=FactoryRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @Before public void setup()throws Exception{
        screen=Robolectric.buildActivity(Screen.class).setup().get();host=new Host();host.appId=screen.getPackageName();runtime=new FactoryRuntime(screen,new FrameLayout(screen),host);
        field("config").set(runtime,FactoryConfig.parse(host.configuration(),screen.getPackageName()));field("store").set(runtime,new BoundedStore(host.memory));
        driver=new SensorCaptureTest.Driver();now=1000000000L;field("sensors").set(runtime,new SensorCapture(driver,()->now,new Handler(Looper.getMainLooper())));runtime.onResume();
    }
    @After public void cleanup()throws Exception{runtime.close();assertTrue(((ThreadPoolExecutor)field("io").get(runtime)).awaitTermination(10,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();screen.finish();}
    String arguments(){return "{\"kind\":\"light\",\"durationMs\":5000,\"intervalMs\":100,\"maxSamples\":50}";}
    Response request(String method,String args)throws Exception{return request("sensor"+(++sequence),method,args);}
    Response request(String id,String method,String args)throws Exception{
        Response response=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);
        receive.invoke(runtime,new JSONObject().put("v",1).put("id",id).put("method",method).put("args",new JSONObject(args)).toString(),response);return response;
    }
    void advance(int ms){now+=ms*1000000L;Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS);}
    void invalidate()throws Exception{Method method=FactoryRuntime.class.getDeclaredMethod("invalidate");method.setAccessible(true);method.invoke(runtime);}
    @Test public void previewCannotListCaptureOrCancelInstalledSensors()throws Exception{
        host.preview=true;for(String method:new String[]{"list","capture","cancel"})assertEquals("UNAVAILABLE",request("sensors."+method,method.equals("capture")?arguments():"{}").error());assertEquals(0,driver.registrations);
    }
    @Test public void capabilityOriginAndMainFrameAreRequiredForEveryMethod()throws Exception{
        FactoryConfig enabled=(FactoryConfig)field("config").get(runtime);field("config").set(runtime,FactoryConfig.parse(host.configuration().replace("[\"sensors\"]","[]"),screen.getPackageName()));
        for(String method:new String[]{"list","capture","cancel"}){
            String name="sensors."+method,args=method.equals("capture")?arguments():"{}";assertEquals("CAPABILITY_DENIED",request(name,args).error());
            String raw=new JSONObject().put("v",1).put("id","source").put("method",name).put("args",new JSONObject(args)).toString();
            assertEquals("UNTRUSTED_SOURCE",assertThrows(FactoryException.class,()->BridgeProtocol.validate("https://evil.invalid",true,raw,enabled)).code);
            assertEquals("UNTRUSTED_SOURCE",assertThrows(FactoryException.class,()->BridgeProtocol.validate(BridgeProtocol.ORIGIN,false,raw,enabled)).code);
        }
        assertEquals(0,driver.registrations);
    }
    @Test public void pauseCancelsAndResumeNeverAutomaticallyRegisters()throws Exception{
        Response capture=request("sensors.capture",arguments());driver.events.sample(now,new float[]{1});runtime.onPause();assertEquals("CANCELLED",capture.error());assertEquals(1,driver.unregisters);
        assertEquals("CANCELLED",request("sensors.capture",arguments()).error());runtime.onResume();advance(1000);assertEquals(1,driver.registrations);assertFalse(request("sensors.cancel","{}").result().getBoolean("cancelled"));
    }
    @Test public void navigationResetAndDestroyDiscardOldCallbacks()throws Exception{
        Response capture=request("sensors.capture",arguments());SensorCapture.Events old=driver.events;invalidate();assertEquals(1,driver.unregisters);old.sample(now,new float[]{999});assertNull(capture.value);
        Response next=request("sensors.capture",arguments());runtime.reset();assertEquals(2,driver.unregisters);assertNull(next.value);
        Response last=request("sensors.capture",arguments());runtime.close();assertEquals(3,driver.unregisters);advance(5000);assertNull(last.value);assertEquals(3,driver.registrations);
    }
    @Test public void focusAndHostRevocationAreObservedWithinHeartbeatWithoutResume()throws Exception{
        Response first=request("sensors.capture",arguments());screen.focus=false;advance(100);assertEquals("CANCELLED",first.error());assertEquals(1,driver.unregisters);screen.focus=true;advance(100);assertEquals(1,driver.registrations);
        Response second=request("sensors.capture",arguments());host.active=false;advance(100);assertEquals(2,driver.unregisters);assertNull(second.value);host.active=true;advance(500);assertEquals(2,driver.registrations);
        request("sensors.capture",arguments());host.session=false;advance(100);assertEquals(3,driver.unregisters);host.session=true;advance(500);assertEquals(3,driver.registrations);
    }
    @Test public void foregroundAndFocusAreRequiredForInventoryToo()throws Exception{
        screen.focus=false;assertEquals("CANCELLED",request("sensors.list","{}").error());screen.focus=true;runtime.onPause();assertEquals("CANCELLED",request("sensors.list","{}").error());runtime.onResume();assertEquals(2,request("sensors.list","{}").result().getJSONArray("sensors").length());
    }
    @Test public void cancellationIsIdempotentAndRejectsDuplicateCaptureId()throws Exception{
        Response capture=request("inflight","sensors.capture",arguments());assertEquals("BUSY",request("inflight","sensors.cancel","{}").error());assertEquals(0,driver.unregisters);assertNull(capture.value);
        assertTrue(request("sensors.cancel","{}").result().getBoolean("cancelled"));assertEquals("CANCELLED",capture.error());assertFalse(request("sensors.cancel","{}").result().getBoolean("cancelled"));assertEquals(1,driver.unregisters);
    }
    @SuppressWarnings("unchecked") @Test public void validatedCancelWorksUnderPendingAndRateSaturation()throws Exception{
        Response capture=request("inflight","sensors.capture",arguments());Map<String,Object> pending=(Map<String,Object>)field("pending").get(runtime);
        Class<?> reply=Class.forName(FactoryRuntime.class.getName()+"$Reply");Constructor<?> constructor=reply.getDeclaredConstructors()[0];constructor.setAccessible(true);
        for(int i=0;i<15;i++)pending.put("blocked"+i,constructor.newInstance(runtime,"blocked"+i,"sensors.capture",new Response(),field("generation").getInt(runtime)));
        assertEquals(16,pending.size());assertEquals("BUSY",request("sensors.list","{}").error());
        field("rateWindow").setLong(runtime,SystemClock.elapsedRealtime());field("rateCount").setInt(runtime,80);
        assertTrue(request("sensors.cancel","{}").result().getBoolean("cancelled"));assertEquals("CANCELLED",capture.error());assertEquals(1,driver.unregisters);
    }
    @Test public void globalRateFailureImmediatelyDiscardsAndUnregistersOrphanedCapture()throws Exception{
        Response capture=request("sensors.capture",arguments());driver.events.sample(now,new float[]{42});
        SensorCapture.Events stale=driver.events;
        field("rateWindow").setLong(runtime,SystemClock.elapsedRealtime());field("rateCount").setInt(runtime,80);
        assertEquals("RATE_LIMITED",request("sensors.list","{}").error());
        assertEquals("CANCELLED",capture.error());assertEquals(1,capture.count);assertEquals(1,driver.unregisters);
        stale.sample(now,new float[]{999});advance(5000);assertEquals(1,capture.count);assertEquals(1,driver.registrations);
        assertFalse(request("sensors.cancel","{}").result().getBoolean("cancelled"));
    }
    @Test public void globalRateFailurePreservesCleanupUncertaintyAndClosedLatch()throws Exception{
        Response capture=request("sensors.capture",arguments());driver.throwUnregister=true;
        field("rateWindow").setLong(runtime,SystemClock.elapsedRealtime());field("rateCount").setInt(runtime,80);
        assertEquals("RATE_LIMITED",request("sensors.list","{}").error());
        assertEquals("UNAVAILABLE",capture.error());assertEquals(1,driver.unregisters);
        driver.throwUnregister=false;
        assertEquals("UNAVAILABLE",request("sensors.cancel","{}").error());
        field("rateCount").setInt(runtime,0);
        assertEquals("UNAVAILABLE",request("sensors.capture",arguments()).error());assertEquals(1,driver.registrations);
    }
    @Test public void malformedCancelCannotReleaseCaptureAndNewRequestStaysThrottledAfterReset()throws Exception{
        Response first=request("sensors.capture",arguments().replace("\"maxSamples\":50","\"maxSamples\":1"));driver.events.sample(now,new float[]{1});assertEquals(1,first.result().getJSONArray("samples").length());runtime.reset();
        Response second=request("sensors.capture",arguments().replace("\"maxSamples\":50","\"maxSamples\":1"));driver.events.sample(now,new float[]{2});assertNull(second.value);
        assertEquals("INVALID_ARGUMENT",request("sensors.cancel","{\"background\":true}").error());assertEquals(1,driver.unregisters);advance(100);driver.events.sample(now,new float[]{2});assertEquals(1,second.result().getJSONArray("samples").length());
    }
    @Test public void escapedMethodCancelBypassesSaturationAfterValidation()throws Exception{
        Response capture=request("sensors.capture",arguments());field("rateWindow").setLong(runtime,SystemClock.elapsedRealtime());field("rateCount").setInt(runtime,81);
        String raw="{\"v\":1,\"id\":\"escaped\",\"method\":\"sensors."+"\\"+"u0063ancel\",\"args\":{}}";
        Response response=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);receive.invoke(runtime,raw,response);
        assertTrue(response.result().getBoolean("cancelled"));assertEquals("CANCELLED",capture.error());assertEquals(1,driver.unregisters);
    }
    @Test public void cleanupFailureMakesCancelAndEverySensorMethodUnavailable()throws Exception{
        Response capture=request("sensors.capture",arguments());driver.throwUnregister=true;
        assertEquals("UNAVAILABLE",request("sensors.cancel","{}").error());assertEquals("UNAVAILABLE",capture.error());
        for(String method:new String[]{"list","capture","cancel"})assertEquals("UNAVAILABLE",request("sensors."+method,method.equals("capture")?arguments():"{}").error());assertEquals(1,driver.registrations);
    }

}
