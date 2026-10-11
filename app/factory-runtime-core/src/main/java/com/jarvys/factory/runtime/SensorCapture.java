package com.jarvys.factory.runtime;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import com.jarvys.factory.contract.ResourceBudgets;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Foreground-only, finite, main-thread-owned capture. No identifiers or persistent sensor data. */
public final class SensorCapture {
    // Grammar ceilings are fixed; configured policy is enforced only by the native helper.
    public static final int MAX_RESULT_BYTES=16384, MAX_SAMPLES=50, MAX_DURATION_MS=300000, MIN_INTERVAL_MS=100;
    public static final class Request {
        public final String kind;
        public final int durationMs, intervalMs, maxSamples;
        private Request(String kind,int duration,int interval,int samples) { this.kind=kind;durationMs=duration;intervalMs=interval;maxSamples=samples; }
        public static Request parse(JSONObject args) throws Exception {
            FactoryConfig.exactKeys(args,"kind","durationMs","intervalMs","maxSamples");
            String kind=FactoryConfig.string(args,"kind");
            if(!kind.equals("light")&&!kind.equals("accelerometer")&&!kind.equals("gyroscope")) throw new FactoryException("INVALID_ARGUMENT","Unsupported sensor kind.");
            return new Request(kind,integer(args,"durationMs",100,MAX_DURATION_MS),integer(args,"intervalMs",MIN_INTERVAL_MS,1000),integer(args,"maxSamples",1,MAX_SAMPLES));
        }
        private static int integer(JSONObject args,String key,int min,int max)throws Exception {
            Object value=args.get(key);
            if(!(value instanceof Integer)&&!(value instanceof Long)) throw new FactoryException("INVALID_ARGUMENT",key+" must be an integer.");
            long number=((Number)value).longValue();
            if(number<min||number>max) throw new FactoryException("INVALID_ARGUMENT",key+" is outside the sensor limit.");
            return (int)number;
        }
    }
    interface Clock { long nanos(); }
    interface Authority { boolean active(); }
    interface Sink { void ok(JSONObject result); void fail(String code,String message); }
    interface Events { void sample(long timestamp,float[] values); }
    interface Driver {
        boolean available(String kind);
        boolean register(String kind,int intervalUs,Events events);
        void unregister();
    }
    static Driver android(Context context,Handler handler) {
        final SensorManager manager=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        return new Driver() {
            SensorEventListener listener;
            private Sensor sensor(String kind) { return manager==null?null:manager.getDefaultSensor(kind.equals("light")?Sensor.TYPE_LIGHT:kind.equals("gyroscope")?Sensor.TYPE_GYROSCOPE:Sensor.TYPE_ACCELEROMETER); }
            public boolean available(String kind) { return sensor(kind)!=null; }
            public boolean register(String kind,int intervalUs,Events events) {
                Sensor selected=sensor(kind);
                if(selected==null) return false;
                listener=new SensorEventListener() {
                    public void onSensorChanged(SensorEvent event) {
                        if(event.sensor==selected) events.sample(event.timestamp,event.values);
                    }
                    public void onAccuracyChanged(Sensor sensor,int accuracy) { }
                };
                return manager.registerListener(listener,selected,intervalUs,0,handler);
            }
            public void unregister() {
                if(manager!=null&&listener!=null) manager.unregisterListener(listener);
                listener=null;
            }
        };
    }
    private final Driver driver;
    private final Clock clock;
    private final Handler handler;
    private final ResourceBudgets.Sensors budget;
    private Work current;
    private boolean cleanupFailed;
    // Retained across capture, pause, page reset and sensor kind changes; no one-sample bypass.
    private long lastReceipt=-1,lastEvent=-1;
    SensorCapture(Driver driver,Clock clock,Handler handler) { this(driver,clock,handler,ResourceBudgets.legacy().sensors); }
    SensorCapture(Driver driver,Clock clock,Handler handler,ResourceBudgets.Sensors budget) {
        this.driver=driver;this.clock=clock;this.handler=handler;this.budget=java.util.Objects.requireNonNull(budget);
    }
    static SensorCapture installed(Context context,Handler handler) { return installed(context,handler,ResourceBudgets.legacy().sensors); }
    static SensorCapture installed(Context context,Handler handler,ResourceBudgets.Sensors budget) {
        return new SensorCapture(android(context,handler),SystemClock::elapsedRealtimeNanos,handler,budget);
    }
    JSONObject list()throws Exception {
        if(cleanupFailed) throw new FactoryException("UNAVAILABLE","Sensor cleanup is unconfirmed; restart the application.");
        JSONArray sensors=new JSONArray();
        for(String kind:new String[]{"light","accelerometer","gyroscope"}) sensors.put(metadata(kind).put("available",driver.available(kind)));
        return new JSONObject().put("sensors",sensors).put("mode","installed");
    }
    private static JSONObject metadata(String kind)throws Exception {
        return new JSONObject().put("kind",kind).put("unit",kind.equals("light")?"lux":kind.equals("gyroscope")?"rad/s":"m/s2")
                .put("frame",kind.equals("light")?"scalar":"device-natural");
    }
    void capture(Request request,Authority authority,Sink sink)throws Exception {
        if(cleanupFailed) throw new FactoryException("UNAVAILABLE","Sensor cleanup is unconfirmed; restart the application.");
        if(current!=null) throw new FactoryException("BUSY","A sensor capture is already active.");
        if(!authority.active()) throw new FactoryException("CANCELLED","Sensor capture requires the current foreground page.");
        if(request.durationMs>budget.captureDurationMs || request.maxSamples>budget.captureSamples
                || request.maxSamples>budget.bufferedSamples || request.intervalMs*1000L<budget.minimumIntervalUs)
            throw new FactoryException("INVALID_ARGUMENT","Sensor capture exceeds its declared resource budget.");
        if(!driver.available(request.kind)) throw new FactoryException("UNAVAILABLE","The requested sensor is unavailable.");
        Work work=new Work(request,authority,sink,clock.nanos()); current=work;
        try {
            if(!authority.active()) { finish(work,null,"CANCELLED","Sensor capture authority ended before registration.");return; }
            if(!driver.register(request.kind,request.intervalMs*1000,(timestamp,values)->sample(work,timestamp,values))) {
                finish(work,null,"UNAVAILABLE","Sensor registration is unavailable.");return;
            }
            if(current==work) handler.postDelayed(work.tick,Math.min(100,request.durationMs));
        } catch(RuntimeException failed) { finish(work,null,"UNAVAILABLE","Sensor registration failed."); }
    }
    JSONObject cancellation()throws Exception {
        boolean cancelled=cancel();
        if(cleanupFailed) throw new FactoryException("UNAVAILABLE","Sensor cleanup is unconfirmed; further captures disabled.");
        return new JSONObject().put("cancelled",cancelled);
    }
    boolean cancel() {
        Work work=current;
        if(work==null) return false;
        finish(work,null,"CANCELLED","Sensor capture was cancelled; buffered samples were discarded.");return true;
    }
    private final class Work {
        final Request request; final Authority authority; final Sink sink; final long started;
        JSONArray samples=new JSONArray();
        long previousEvent=-1;
        final Runnable tick;
        Work(Request request,Authority authority,Sink sink,long started) {
            this.request=request;this.authority=authority;this.sink=sink;this.started=started;
            tick=()->checkDeadline(this);
        }
    }
    private void checkDeadline(Work work) {
        if(current!=work) return;
        if(!work.authority.active()) { finish(work,null,"CANCELLED","Sensor capture authority ended.");return; }
        if(clock.nanos()-work.started>=work.request.durationMs*1000000L) { complete(work,"duration");return; }
        handler.postDelayed(work.tick,100);
    }
    private void sample(Work work,long timestamp,float[] values) {
        if(current!=work) return;
        long now=clock.nanos();
        if(!work.authority.active()) { finish(work,null,"CANCELLED","Sensor capture authority ended.");return; }
        if(now-work.started>=work.request.durationMs*1000000L) { complete(work,"duration");return; }
        int size=work.request.kind.equals("light")?1:3;
        if(now<work.started||timestamp<work.started||timestamp>now||timestamp<=work.previousEvent||values==null||values.length<size) return;
        work.previousEvent=timestamp;
        long interval=work.request.intervalMs*1000000L;
        if((lastReceipt>=0&&now-lastReceipt<interval)||(lastEvent>=0&&timestamp-lastEvent<interval)) return;
        try {
            if(work.samples.length()>=Math.min(MAX_SAMPLES,budget.bufferedSamples)) {
                finish(work,null,"TOO_LARGE","Sensor buffer exceeded its sample limit.");return;
            }
            JSONArray copied=new JSONArray();
            for(int i=0;i<size;i++) { if(Float.isNaN(values[i])||Float.isInfinite(values[i])) return; copied.put((double)values[i]); }
            work.samples.put(new JSONObject().put("elapsedMs",(now-work.started)/1000000L).put("values",copied));
            lastReceipt=now;lastEvent=timestamp;
            if(result(work,"sample_limit").toString().getBytes(StandardCharsets.UTF_8).length>MAX_RESULT_BYTES) {
                finish(work,null,"TOO_LARGE","Sensor result exceeded its byte limit.");return;
            }
            if(work.samples.length()>=work.request.maxSamples) complete(work,"sample_limit");
        } catch(Exception invalid) { finish(work,null,"NATIVE_ERROR","Sensor sample could not be represented."); }
    }
    private JSONObject result(Work work,String reason)throws Exception {
        return metadata(work.request.kind).put("includesGravity",work.request.kind.equals("accelerometer"))
                .put("reason",reason).put("samples",work.samples).put("mode","installed").put("simulated",false);
    }
    private void complete(Work work,String reason) {
        try {
            JSONObject result=result(work,reason);
            if(work.samples.length()>Math.min(work.request.maxSamples,Math.min(budget.captureSamples,budget.bufferedSamples))
                    || work.samples.length()>MAX_SAMPLES || result.toString().getBytes(StandardCharsets.UTF_8).length>MAX_RESULT_BYTES) {
                finish(work,null,"TOO_LARGE","Sensor result exceeded its declared budget.");return;
            }
            finish(work,result,null,null);
        }
        catch(Exception failed) { finish(work,null,"NATIVE_ERROR","Sensor result could not be represented."); }
    }
    private void finish(Work work,JSONObject result,String code,String message) {
        if(current!=work) return;
        current=null;handler.removeCallbacks(work.tick);
        // Unregister even after registration returned false or threw: it may have partly succeeded.
        try { driver.unregister(); }
        catch(RuntimeException failed) { cleanupFailed=true;result=null;code="UNAVAILABLE";message="Sensor cleanup is unconfirmed; data discarded and further captures disabled."; }
        work.samples=new JSONArray();
        if(result!=null&&!work.authority.active()) { result=null;code="CANCELLED";message="Sensor capture authority ended."; }
        if(result!=null) work.sink.ok(result); else work.sink.fail(code,message);
    }
}
