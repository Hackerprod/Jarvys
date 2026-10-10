package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import org.json.JSONObject;

/** Local OS authentication only. No key, token, biometric material or reusable authorization. */
public final class BiometricAuthentication {
    static final int DEADLINE_MS=60000, DRAIN_MS=2000, BIOMETRIC_TYPE=2, NEGATIVE_BUTTON=-1000;
    enum Availability { READY, UNAVAILABLE, UNSUPPORTED }
    interface Clock { long millis(); }
    interface Authority { boolean active(); }
    interface Sink { void ok(JSONObject value); void fail(String code,String message); }
    interface Events { void success(int type); void error(int code); void failed(); void help(); }
    interface Driver { Availability status(); void start(Request request,Events events); void cancel(); }
    public static final class Request {
        public final String reason;
        private Request(String value) { reason=value; }
        public static Request parse(JSONObject args)throws Exception {
            FactoryConfig.exactKeys(args,"reason");
            String value=FactoryConfig.string(args,"reason");
            if(value.isEmpty()||value.length()>160||value.trim().isEmpty()||value.getBytes(StandardCharsets.UTF_8).length>512)
                throw new FactoryException("INVALID_ARGUMENT","Authentication reason must be bounded plain text.");
            boolean visible=false;
            for(int i=0;i<value.length();) {
                int cp=value.codePointAt(i); int type=Character.getType(cp);
                if(Character.isISOControl(cp)||type==Character.FORMAT||type==Character.SURROGATE||type==Character.LINE_SEPARATOR||type==Character.PARAGRAPH_SEPARATOR)
                    throw new FactoryException("INVALID_ARGUMENT","Authentication reason must be bounded plain text.");
                if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))visible=true;
                i+=Character.charCount(cp);
            }
            if(!visible)throw new FactoryException("INVALID_ARGUMENT","Authentication reason must contain visible text.");
            return new Request(value);
        }
    }
    /** Only opaque ownership and bounded monotonic counters survive Activity recreation. */
    static final class ProcessGate {
        private Object owner;
        private boolean poisoned;
        private final ArrayDeque<Long> admissions=new ArrayDeque<>(), controls=new ArrayDeque<>();
        synchronized boolean busy() { return poisoned||owner!=null; }
        synchronized boolean poisoned() { return poisoned; }
        synchronized void admit(Object token,long now)throws FactoryException {
            if(poisoned) throw unavailable();
            if(owner!=null) throw new FactoryException("BUSY","A biometric operation is active or awaiting cancellation.");
            quota(admissions,now,60000,3);
            owner=token;
        }
        synchronized void noOp(long now)throws FactoryException { if(poisoned) throw unavailable(); quota(controls,now,10000,8); }
        private void quota(ArrayDeque<Long> values,long now,long window,int maximum)throws FactoryException {
            if(now<0||(!values.isEmpty()&&now<values.peekLast())) throw new FactoryException("UNAVAILABLE","Monotonic authentication timing is unavailable.");
            while(!values.isEmpty()&&now-values.peekFirst()>=window) values.removeFirst();
            if(values.size()>=maximum) throw new FactoryException("RATE_LIMITED","Biometric request limit reached; wait before trying again.");
            values.addLast(now);
        }
        synchronized void release(Object token) { if(owner==token) owner=null; }
        synchronized void poison(Object token) { if(owner==token) { owner=null;poisoned=true; } }
    }
    private static final ProcessGate PROCESS=new ProcessGate();
    static boolean processUiBusy() { return PROCESS.busy(); }
    private static FactoryException unavailable() { return new FactoryException("UNAVAILABLE","Biometric cleanup is unconfirmed; restart the application."); }
    private final Driver driver; private final Clock clock; private final Handler handler; private final ProcessGate gate;
    private Work current,draining;
    BiometricAuthentication(Driver driver,Clock clock,Handler handler,ProcessGate gate) {
        this.driver=driver;this.clock=clock;this.handler=handler;this.gate=gate;
    }
    static BiometricAuthentication installed(Activity activity,Handler handler) {
        return new BiometricAuthentication(android(activity,handler),SystemClock::elapsedRealtime,handler,PROCESS);
    }
    static Driver android(Activity activity,Handler handler) {
        if(Build.VERSION.SDK_INT>=30) return new Api30Driver(activity,handler);
        return new Driver() {
            public Availability status(){return Availability.UNSUPPORTED;}
            public void start(Request request,Events events){throw new IllegalStateException("Unsupported Android API");}
            public void cancel(){}
        };
    }
    boolean isActive(){return current!=null;}
    boolean isUiBusy(){return gate.busy();}
    JSONObject availability()throws Exception {
        Availability status=gate.poisoned()?Availability.UNAVAILABLE:status();
        return new JSONObject().put("available",status==Availability.READY)
                .put("status",status==Availability.READY?"ready":status==Availability.UNSUPPORTED?"unsupported":"unavailable")
                .put("minimumApi",30).put("biometricClass","strong").put("credentialFallback",false).put("mode","installed");
    }
    private Availability status() {
        try { Availability value=driver.status();return value==null?Availability.UNAVAILABLE:value; }
        catch(RuntimeException failed){return Availability.UNAVAILABLE;}
    }
    void authenticate(Request request,Authority authority,Sink sink)throws Exception {
        if(gate.poisoned()) throw unavailable();
        if(!authority.active()) throw new FactoryException("CANCELLED","Authentication requires the current foreground page.");
        if(status()!=Availability.READY) throw new FactoryException("UNAVAILABLE","Strong biometric authentication is unavailable.");
        Work work=new Work(request,authority,sink,clock.millis());
        gate.admit(work.token,work.started);current=work;
        handler.postDelayed(work.tick,100);
        try {
            if(!authority.active()) { terminal(work,null,"CANCELLED","Authentication authority ended.");return; }
            // The platform adapter checks permission and availability again immediately before start.
            driver.start(request,new Events() {
                public void success(int type){finishSuccess(work,type);}
                public void error(int code){finishError(work,code);}
                public void failed(){check(work);}
                public void help(){check(work);}
            });
        } catch(RuntimeException failed){cancel(work,"UNAVAILABLE","Authentication launch failed; cancellation was requested.");}
    }
    void rejectedControl()throws FactoryException {gate.noOp(clock.millis());}
    JSONObject cancellation()throws Exception {
        if(gate.poisoned()) throw unavailable();
        boolean requested=cancel();
        if(!requested) gate.noOp(clock.millis());
        if(gate.poisoned()) throw unavailable();
        return new JSONObject().put("cancelRequested",requested).put("cancellationAcknowledged",false);
    }
    boolean cancel() {
        Work work=current;if(work==null)return false;
        cancel(work,"CANCELLED","Authentication was cancelled; no authentication receipt was issued.");return true;
    }
    private final class Work {
        final Object token=new Object();final Request request;final Authority authority;final Sink sink;final long started;
        final Runnable tick,drain;boolean signalling,acknowledged;
        Work(Request request,Authority authority,Sink sink,long started) {
            this.request=request;this.authority=authority;this.sink=sink;this.started=started;
            tick=()->check(this);
            drain=()->{if(draining==this){draining=null;gate.poison(token);}};
        }
    }
    private void check(Work work) {
        if(current!=work)return;
        if(!work.authority.active()) {cancel(work,"CANCELLED","Authentication authority ended.");return;}
        long elapsed=clock.millis()-work.started;
        if(elapsed<0||elapsed>=DEADLINE_MS){cancel(work,"TIMEOUT","Authentication timed out; cancellation was requested.");return;}
        handler.removeCallbacks(work.tick);handler.postDelayed(work.tick,100);
    }
    private boolean acknowledgeDrain(Work work) {
        if(draining!=work)return false;
        if(work.signalling){work.acknowledged=true;return true;}
        draining=null;handler.removeCallbacks(work.drain);gate.release(work.token);return true;
    }
    private void finishSuccess(Work work,int type) {
        if(acknowledgeDrain(work)||current!=work)return;
        if(type!=BIOMETRIC_TYPE){terminal(work,null,"AUTHENTICATION_FAILED","The system did not report biometric authentication.");return;}
        long elapsed=clock.millis()-work.started;
        if(elapsed<0||elapsed>=DEADLINE_MS){terminal(work,null,"TIMEOUT","Authentication result arrived after its deadline.");return;}
        if(!work.authority.active()){terminal(work,null,"CANCELLED","Authentication authority ended.");return;}
        try {terminal(work,new JSONObject().put("authenticated",true).put("biometricClass","strong")
                .put("authorizesOtherActions",false).put("mode","installed").put("simulated",false),null,null);}
        catch(Exception failed){terminal(work,null,"NATIVE_ERROR","Authentication receipt could not be represented.");}
    }
    private void finishError(Work work,int code) {
        if(acknowledgeDrain(work)||current!=work)return;
        String mapped="UNAVAILABLE",message="The system could not complete biometric authentication.";
        if(code==BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED||code==NEGATIVE_BUTTON){mapped="CANCELLED";message="The user cancelled biometric authentication.";}
        else if(code==BiometricPrompt.BIOMETRIC_ERROR_CANCELED){mapped="CANCELLED";message="The system interrupted biometric authentication.";}
        else if(code==BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT){mapped="TIMEOUT";message="The system timed out biometric authentication.";}
        terminal(work,null,mapped,message);
    }
    private void terminal(Work work,JSONObject result,String code,String message) {
        if(current!=work)return;
        current=null;handler.removeCallbacks(work.tick);gate.release(work.token);
        if(result!=null&&!work.authority.active()){result=null;code="CANCELLED";message="Authentication authority ended.";}
        if(result!=null)work.sink.ok(result);else work.sink.fail(code,message);
    }
    private void cancel(Work work,String code,String message) {
        if(current!=work)return;
        current=null;handler.removeCallbacks(work.tick);draining=work;
        handler.postDelayed(work.drain,DRAIN_MS);
        // Invalidate authority before signal: drivers may deliver callbacks synchronously.
        work.signalling=true;
        try {driver.cancel();work.signalling=false;if(work.acknowledged)acknowledgeDrain(work);}
        catch(RuntimeException failed){work.signalling=false;draining=null;handler.removeCallbacks(work.drain);gate.poison(work.token);code="UNAVAILABLE";message="Biometric cleanup is unconfirmed; restart the application.";}
        work.sink.fail(code,message);
    }
    @RequiresApi(30)
    private static final class Api30Driver implements Driver {
        final Activity activity;final Handler handler;CancellationSignal signal;
        Api30Driver(Activity activity,Handler handler){this.activity=activity;this.handler=handler;}
        // This library also ships in zero-permission templates/preview. The permission is
        // required only for the selected installed profile; the guard returns before any API call.
        @RequiresPermission(value=Manifest.permission.USE_BIOMETRIC,conditional=true)
        public Availability status() {
            if(activity.checkSelfPermission(Manifest.permission.USE_BIOMETRIC)!=PackageManager.PERMISSION_GRANTED)return Availability.UNAVAILABLE;
            BiometricManager manager=(BiometricManager)activity.getSystemService(Context.BIOMETRIC_SERVICE);
            return manager!=null&&manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS?Availability.READY:Availability.UNAVAILABLE;
        }
        @RequiresPermission(value=Manifest.permission.USE_BIOMETRIC,conditional=true)
        public void start(Request request,Events events) {
            if(activity.checkSelfPermission(Manifest.permission.USE_BIOMETRIC)!=PackageManager.PERMISSION_GRANTED||status()!=Availability.READY)
                throw new IllegalStateException("Biometrics unavailable");
            final CancellationSignal owned=new CancellationSignal();signal=owned;
            BiometricPrompt prompt=new BiometricPrompt.Builder(activity)
                    .setTitle("Biometric authentication")
                    .setSubtitle("Local authentication only")
                    .setDescription(request.reason+"\n\nThis does not authorize any other action.")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setConfirmationRequired(true)
                    .setNegativeButton("Cancel",handler::post,(dialog,which)->{clear(owned);events.error(NEGATIVE_BUTTON);})
                    .build();
            prompt.authenticate(owned,handler::post,new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){clear(owned);events.success(result==null?0:result.getAuthenticationType());}
                @Override public void onAuthenticationError(int code,CharSequence ignored){clear(owned);events.error(code);}
                @Override public void onAuthenticationFailed(){events.failed();}
                @Override public void onAuthenticationHelp(int code,CharSequence ignored){events.help();}
            });
        }
        private void clear(CancellationSignal owned){if(signal==owned)signal=null;}
        public void cancel(){CancellationSignal old=signal;signal=null;if(old!=null)old.cancel();}
    }
}
