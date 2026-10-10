package com.jarvys.factory.runtime;

import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Synthetic event/clock fixtures only: no physical sensor or device acceptance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,35},manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SensorCaptureTest {
    static class Driver implements SensorCapture.Driver {
        boolean available=true, registered=true, throwRegister, throwUnregister;
        int registrations, unregisters, intervalUs; String kind;
        SensorCapture.Events events;
        public boolean available(String kind){return available;}
        public boolean register(String kind,int intervalUs,SensorCapture.Events events){
            registrations++;this.kind=kind;this.intervalUs=intervalUs;this.events=events;
            if(throwRegister)throw new IllegalStateException("Synthetic registration failure");return registered;
        }
        public void unregister(){unregisters++;if(throwUnregister)throw new IllegalStateException("Synthetic cleanup failure");}
    }
    static class Result implements SensorCapture.Sink {
        JSONObject value; String error; int replies;
        public void ok(JSONObject result){value=result;replies++;}
        public void fail(String code,String message){error=code;replies++;}
        JSONArray samples()throws Exception{assertNotNull(value);return value.getJSONArray("samples");}
    }
    Driver driver; SensorCapture capture; long now; boolean active;
    @Before public void setup(){driver=new Driver();now=1000000000L;active=true;capture=new SensorCapture(driver,()->now,new Handler(Looper.getMainLooper()));}
    @After public void cleanup(){capture.cancel();}
    static JSONObject args(String kind,int duration,int interval,int samples)throws Exception{return new JSONObject().put("kind",kind).put("durationMs",duration).put("intervalMs",interval).put("maxSamples",samples);}
    Result start(String kind,int duration,int interval,int samples)throws Exception{
        Result result=new Result();capture.capture(SensorCapture.Request.parse(args(kind,duration,interval,samples)),()->active,result);return result;
    }
    void emit(float...values){driver.events.sample(now,values);}
    void advance(int ms){now+=ms*1000000L;Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS);}
    Set<String> keys(JSONObject value){Set<String> keys=new HashSet<>();value.keys().forEachRemaining(keys::add);return keys;}
    @Test public void strictRequestBoundsTypesMissingNullAndUnknownFields()throws Exception{
        SensorCapture.Request.parse(args("light",100,100,1));SensorCapture.Request.parse(args("accelerometer",5000,1000,50));
        for(String key:new String[]{"durationMs","intervalMs","maxSamples"}){
            for(Object bad:new Object[]{JSONObject.NULL,"100",true,100.0,0,-1,Long.MAX_VALUE}){
                JSONObject a=args("light",100,100,1).put(key,bad);assertThrows(Exception.class,()->SensorCapture.Request.parse(a));
            }
            JSONObject missing=args("light",100,100,1);missing.remove(key);assertThrows(Exception.class,()->SensorCapture.Request.parse(missing));
        }
        for(String kind:new String[]{"gyroscope","magnetometer","step-counter","LIGHT",""})assertThrows(Exception.class,()->SensorCapture.Request.parse(args(kind,100,100,1)));
        for(String extra:new String[]{"frequencyHz","sensorId","background","persist","vendor"})assertThrows(Exception.class,()->SensorCapture.Request.parse(args("light",100,100,1).put(extra,true)));
        assertThrows(Exception.class,()->SensorCapture.Request.parse(args("light",5001,100,1)));
        assertThrows(Exception.class,()->SensorCapture.Request.parse(args("light",100,1001,1)));
        assertThrows(Exception.class,()->SensorCapture.Request.parse(args("light",100,100,51)));
        assertThrows(Exception.class,()->SensorCapture.Request.parse(args("light",100,100,1).put("kind",JSONObject.NULL)));
    }
    @Test public void inventoryExposesOnlyBoundedKindUnitsFrameAndAvailability()throws Exception{
        JSONObject result=capture.list();assertEquals(new HashSet<>(Arrays.asList("sensors","mode")),keys(result));
        JSONArray sensors=result.getJSONArray("sensors");assertEquals(2,sensors.length());
        for(int i=0;i<2;i++)assertEquals(new HashSet<>(Arrays.asList("kind","unit","frame","available")),keys(sensors.getJSONObject(i)));
        assertEquals("lux",sensors.getJSONObject(0).getString("unit"));assertEquals("scalar",sensors.getJSONObject(0).getString("frame"));
        assertEquals("m/s2",sensors.getJSONObject(1).getString("unit"));assertEquals("device-natural",sensors.getJSONObject(1).getString("frame"));
        driver.available=false;assertFalse(capture.list().getJSONArray("sensors").getJSONObject(0).getBoolean("available"));
    }
    @Test public void unavailableAuthorityAndBusyFailBeforeRegistering()throws Exception{
        active=false;assertEquals("CANCELLED",assertThrows(FactoryException.class,()->start("light",500,100,1)).code);assertEquals(0,driver.registrations);
        active=true;driver.available=false;assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->start("light",500,100,1)).code);
        driver.available=true;start("light",500,100,1);assertEquals("BUSY",assertThrows(FactoryException.class,()->start("accelerometer",500,100,1)).code);assertEquals(1,driver.registrations);
    }
    @Test public void zeroEventsAndSparseOnChangeLightCompleteAtDeadline()throws Exception{
        Result empty=start("light",500,100,50);advance(500);assertEquals(0,empty.samples().length());assertEquals("duration",empty.value.getString("reason"));assertEquals(1,driver.unregisters);
        Result sparse=start("light",500,100,50);emit(7);advance(500);assertEquals(1,sparse.samples().length());assertEquals(7,sparse.samples().getJSONObject(0).getJSONArray("values").getDouble(0),0);
    }
    @Test public void copiesValuesUsesNaturalFrameAndStopsAtSampleCap()throws Exception{
        Result r=start("accelerometer",5000,100,2);float[] mutable={1,2,3,999};driver.events.sample(now,mutable);mutable[0]=999;
        advance(100);emit(4,5,6);assertEquals(2,r.samples().length());assertEquals(3,r.samples().getJSONObject(0).getJSONArray("values").length());assertEquals(1,r.samples().getJSONObject(0).getJSONArray("values").getDouble(0),0);
        assertEquals("m/s2",r.value.getString("unit"));assertEquals("device-natural",r.value.getString("frame"));assertTrue(r.value.getBoolean("includesGravity"));assertFalse(r.value.getBoolean("simulated"));assertEquals("sample_limit",r.value.getString("reason"));
        emit(7,8,9);assertEquals(1,r.replies);assertEquals(1,driver.unregisters);assertEquals(100000,driver.intervalUs);
    }
    @Test public void staleFutureOutOfOrderNonfiniteAndShortEventsAreDiscarded()throws Exception{
        Result r=start("accelerometer",1000,100,50);driver.events.sample(now-1,new float[]{1,2,3});driver.events.sample(now+1,new float[]{1,2,3});emit(1,2);emit(Float.NaN,2,3);
        advance(100);emit(Float.POSITIVE_INFINITY,2,3);advance(100);emit(1,Float.NEGATIVE_INFINITY,3);advance(100);emit(1,2,3);
        advance(100);driver.events.sample(now-200000000L,new float[]{4,5,6});advance(100);emit(7,8,9);advance(500);
        assertEquals(2,r.samples().length());assertEquals(300,r.samples().getJSONObject(0).getLong("elapsedMs"));assertEquals(500,r.samples().getJSONObject(1).getLong("elapsedMs"));
    }
    @Test public void delayedCallbackCannotAddAReadingAfterDeadline()throws Exception{
        Result r=start("light",100,100,50);now+=200000000L;driver.events.sample(now-150000000L,new float[]{123});assertEquals(0,r.samples().length());assertEquals("duration",r.value.getString("reason"));advance(100);assertEquals(1,r.replies);
    }
    @Test public void fiftyExtremeFiniteSamplesRemainBelowSixteenKiB()throws Exception{
        Result r=start("accelerometer",5000,100,50);
        for(int i=0;i<50;i++){if(i>0)advance(100);emit(Float.MAX_VALUE,-Float.MAX_VALUE,Float.MIN_VALUE);}
        assertEquals(50,r.samples().length());assertTrue(r.value.toString().getBytes(StandardCharsets.UTF_8).length<=16384);assertEquals(1,driver.unregisters);
    }
    @Test public void repeatedOneSampleAndCrossKindCaptureCannotBypassTenHz()throws Exception{
        Result first=start("light",500,100,1);emit(1);assertEquals(1,first.replies);
        Result second=start("accelerometer",500,100,1);emit(1,2,3);assertEquals(0,second.replies);advance(99);emit(1,2,3);assertEquals(0,second.replies);advance(1);emit(1,2,3);assertEquals(1,second.replies);
        Result third=start("light",500,100,1);emit(1);assertEquals(0,third.replies);capture.cancel();
        Result fourth=start("light",500,100,1);emit(2);assertEquals(0,fourth.replies);advance(100);emit(2);assertEquals(1,fourth.replies);
    }
    @Test public void receiptTimeAlsoThrottlesBatchedHardwareTimestamps()throws Exception{
        Result r=start("light",1000,100,50);now+=300000000L;driver.events.sample(now-200000000L,new float[]{1});driver.events.sample(now-100000000L,new float[]{2});driver.events.sample(now,new float[]{3});advance(700);assertEquals(1,r.samples().length());
    }
    @Test public void cancellationIsIdempotentAndOldListenerCannotTouchNewCapture()throws Exception{
        Result old=start("light",500,100,50);SensorCapture.Events stale=driver.events;emit(1);assertTrue(capture.cancel());assertFalse(capture.cancel());assertEquals("CANCELLED",old.error);assertNull(old.value);assertEquals(1,old.replies);
        advance(100);Result next=start("light",500,100,1);stale.sample(now,new float[]{99});assertEquals(0,next.replies);emit(2);assertEquals(1,next.samples().length());assertEquals(2,driver.unregisters);
    }
    @Test public void heartbeatRevocationDiscardsBufferedDataAndNeverRestarts()throws Exception{
        Result r=start("light",5000,100,50);emit(1);active=false;advance(100);assertEquals("CANCELLED",r.error);assertNull(r.value);active=true;advance(1000);assertEquals(1,driver.registrations);assertEquals(1,driver.unregisters);
    }
    @Test public void falseAndThrowingRegistrationAlwaysAttemptCleanup()throws Exception{
        driver.registered=false;Result first=start("light",500,100,1);assertEquals("UNAVAILABLE",first.error);assertEquals(1,driver.unregisters);
        driver.throwRegister=true;Result second=start("light",500,100,1);assertEquals("UNAVAILABLE",second.error);assertEquals(2,driver.unregisters);
    }
    @Test public void failedUnregisterDiscardsSuccessAndLatchesClosed()throws Exception{
        driver.throwUnregister=true;Result r=start("light",500,100,1);emit(1);assertNull(r.value);assertEquals("UNAVAILABLE",r.error);assertEquals(1,r.replies);
        driver.throwUnregister=false;assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->start("light",500,100,1)).code);assertEquals("UNAVAILABLE",assertThrows(FactoryException.class,()->capture.list()).code);assertFalse(capture.cancel());assertEquals(1,driver.registrations);
    }
    @Test public void androidAdapterRegistersOnlySelectedDefaultSensorAndUnregisters()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();
        android.hardware.SensorManager manager=(android.hardware.SensorManager)context.getSystemService(android.content.Context.SENSOR_SERVICE);
        org.robolectric.shadows.ShadowSensorManager shadow=Shadows.shadowOf(manager);
        SensorCapture.Driver adapter=SensorCapture.android(context,new Handler(Looper.getMainLooper()));
        assertFalse(adapter.available("light"));assertFalse(adapter.available("accelerometer"));
        android.hardware.Sensor light=org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_LIGHT);
        android.hardware.Sensor acceleration=org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_ACCELEROMETER);
        shadow.addSensor(light);shadow.addSensor(acceleration);assertTrue(adapter.available("light"));assertTrue(adapter.available("accelerometer"));
        final int[] calls={0};final long[] timestamp={0};final float[][] values={null};
        assertTrue(adapter.register("light",100000,(time,eventValues)->{calls[0]++;timestamp[0]=time;values[0]=eventValues.clone();}));
        assertEquals(1,shadow.getListeners().size());android.hardware.SensorEventListener listener=shadow.getListeners().get(0);assertTrue(shadow.hasListener(listener,light));assertFalse(shadow.hasListener(listener,acceleration));
        android.hardware.SensorEvent event=org.robolectric.shadows.ShadowSensorManager.createSensorEvent(3);event.sensor=acceleration;event.timestamp=123456789L;event.values[0]=99;listener.onSensorChanged(event);assertEquals(0,calls[0]);
        event.sensor=light;event.values[0]=42;listener.onSensorChanged(event);assertEquals(1,calls[0]);assertEquals(123456789L,timestamp[0]);assertEquals(42,values[0][0],0);
        adapter.unregister();assertFalse(shadow.hasListener(listener));assertTrue(shadow.getListeners().isEmpty());adapter.unregister();
        shadow.setForceListenersToFail(true);assertFalse(adapter.register("light",100000,(time,eventValues)->{}));adapter.unregister();assertTrue(shadow.getListeners().isEmpty());
    }

}
