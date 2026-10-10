package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.Signature;
import android.os.IBinder;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Synthetic runtime/host IPC and lifecycle only; never starts an external application or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,32},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeExternalLaunchTest {
    public static class Screen extends Activity {
        Intent launched; boolean reject; boolean focused=true;
        @Override public boolean hasWindowFocus(){return focused;}
        @Override public void startActivityForResult(Intent intent,int request){assertEquals(47,request);if(reject)throw new android.content.ActivityNotFoundException();launched=intent;}
    }
    private static class Host implements FactoryRuntime.Host {
        final MemoryBackend data=new MemoryBackend(); boolean preview,active=true;
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return FactoryDispatcherTest.configuration("[\"maps\"]");}
        public boolean isPreview(){return preview;}
        public boolean isActive(){return active;}
        public BoundedStore.Backend storage(){return data;}
    }
    private static class Response extends JavaScriptReplyProxy {
        JSONObject value; int count;
        public void postMessage(String text){try{value=new JSONObject(text);count++;}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] bytes){throw new AssertionError();}
        String error()throws Exception{assertNotNull(value);return value.getJSONObject("error").getString("code");}
    }
    private FactoryRuntime runtime; private Screen screen; private Host host; private int next;
    private static Field field(String name)throws Exception{Field f=FactoryRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private Object work()throws Exception{return field("externalLaunch").get(runtime);}
    private ThreadPoolExecutor worker()throws Exception{return (ThreadPoolExecutor)field("io").get(runtime);}
    @Before public void setup()throws Exception{
        screen=Robolectric.buildActivity(Screen.class).setup().get();installHost();
        host=new Host();runtime=new FactoryRuntime(screen,new FrameLayout(screen),host);setCapabilities("[\"maps\"]");
        field("store").set(runtime,new BoundedStore(host.data));runtime.onResume();
    }
    private void installHost()throws Exception{
        DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.PRIMARY,new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(hostUid(),DocumentBrokerIdentity.PRIMARY);
    }
    private int hostUid()throws Exception{return screen.getPackageManager().getApplicationInfo(DocumentBrokerIdentity.PRIMARY,0).uid;}
    private void setCapabilities(String caps)throws Exception{
        field("config").set(runtime,FactoryConfig.parsePreview(new JSONObject(FactoryDispatcherTest.configuration(caps)).put("documentBroker",
                new JSONObject().put("packageName",DocumentBrokerIdentity.PRIMARY).put("certificateSha256",DocumentBrokerIdentityTest.digest())).toString()));
    }
    @After public void stop()throws Exception{runtime.close();assertTrue(worker().awaitTermination(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();screen.finish();org.robolectric.shadows.ShadowBinder.reset();}
    private Response request(String method,JSONObject args)throws Exception{
        Response r=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);
        receive.invoke(runtime,new JSONObject().put("v",1).put("id","maps"+(++next)).put("method",method).put("args",args).toString(),r);return r;
    }
    private Response open()throws Exception{return request("maps.open",new JSONObject().put("query","Café %2F & + # 東京"));}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private Intent result(){return new Intent().putExtra("nonce",screen.launched.getStringExtra("nonce")).putExtra("launchRequested",true).putExtra("actionConfirmed",false);}
    private ExternalLaunchControl.Registration register(CountDownLatch cancelled)throws Exception{
        org.robolectric.shadows.ShadowBinder.setCallingUid(hostUid());
        IBinder control=screen.launched.getExtras().getBinder("control");String nonce=screen.launched.getStringExtra("nonce");
        ExecutorService thread=Executors.newSingleThreadExecutor();
        try{return thread.submit(()->{
            assertEquals(hostUid(),android.os.Binder.getCallingUid());
            return ExternalLaunchControl.register(control,nonce,uid->true,cancelled::countDown);
        }).get(5,TimeUnit.SECONDS);}
        finally{thread.shutdownNow();}
    }
    @Test public void mapsOnlyCallsExactPinnedHostAndPreservesOriginalTypedQueryWithoutFileAuthority()throws Exception{
        Response reply=open();assertNull(screen.launched);idle();assertNotNull(screen.launched);assertNull(reply.value);
        assertEquals("com.jarvys.agent",screen.launched.getComponent().getPackageName());
        assertEquals("com.jarvys.agent.apkfactory.FactoryExternalActionActivity",screen.launched.getComponent().getClassName());
        assertEquals("maps.open",screen.launched.getStringExtra("method"));
        assertEquals("Café %2F & + # 東京",new JSONObject(screen.launched.getStringExtra("args")).getString("query"));
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("protocolVersion","nonce","method","args","control")),screen.launched.getExtras().keySet());
        assertEquals(1,screen.launched.getIntExtra("protocolVersion",0));assertNotNull(screen.launched.getExtras().getBinder("control"));
        assertNull(screen.launched.getAction());assertNull(screen.launched.getData());assertNull(screen.launched.getClipData());assertNull(screen.launched.getSelector());assertEquals(0,screen.launched.getFlags());
        assertTrue(((FactoryConfig)field("config").get(runtime)).capabilities.equals(java.util.Collections.singleton("maps")));
        FileShareTransfer.reserve().close();
        runtime.onPause();runtime.onActivityResult(47,Activity.RESULT_OK,result());assertNull(reply.value);runtime.onResume();
        assertTrue(reply.value.getJSONObject("result").getBoolean("launchRequested"));assertFalse(reply.value.getJSONObject("result").getBoolean("actionConfirmed"));
        assertEquals(2,reply.value.getJSONObject("result").length());assertEquals(1,reply.count);assertNull(work());
    }
    @Test public void expectedBrokerPauseDoesNotRevokeAndDocumentsCancelHasNoExternalLaunchAuthority()throws Exception{
        setCapabilities("[\"maps\",\"documents\"]");Response reply=open();idle();CountDownLatch cancelled=new CountDownLatch(1);
        ExternalLaunchControl.Registration registration=register(cancelled);
        runtime.onPause();assertFalse(registration.isRevoked());runtime.onResume();request("documents.cancel",new JSONObject());
        assertFalse(registration.isRevoked());assertNull(reply.value);assertEquals("BUSY",open().error());
        runtime.onActivityResult(47,Activity.RESULT_OK,result());assertTrue(cancelled.await(5,TimeUnit.SECONDS));registration.close();
    }
    @Test public void pauseBeforeQueuedLaunchRevokesAuthorityAndReleasesUnlaunchedOwner()throws Exception{
        Response reply=open();runtime.onPause();idle();assertEquals("CANCELLED",reply.error());assertNull(screen.launched);assertNull(work());
        runtime.onResume();open();idle();assertNotNull(screen.launched);
    }
    @Test public void reloadBeforeQueuedLaunchDoesNotStartHost()throws Exception{
        Response reply=open();runtime.reset();idle();assertNull(screen.launched);assertNull(work());assertNull(reply.value);
    }
    @Test public void reloadRevokesRegisteredControlButRetainsTombstoneUntilCallback()throws Exception{
        Response old=open();idle();CountDownLatch cancelled=new CountDownLatch(1);ExternalLaunchControl.Registration registration=register(cancelled);
        runtime.reset();assertTrue(cancelled.await(5,TimeUnit.SECONDS));assertTrue(registration.isRevoked());assertNull(old.value);
        assertNotNull(work());assertEquals("BUSY",open().error());runtime.onActivityResult(47,Activity.RESULT_OK,result());assertNull(work());
        open();idle();assertNotNull(work());registration.close();
    }
    @Test public void closeAndInactiveSourceCannotGainNewLaunchAuthority()throws Exception{
        open();idle();CountDownLatch cancelled=new CountDownLatch(1);ExternalLaunchControl.Registration registration=register(cancelled);
        runtime.close();assertTrue(cancelled.await(5,TimeUnit.SECONDS));assertTrue(registration.isRevoked());registration.close();
    }
    @Test public void inactiveHostWhileQueuedCannotLaunch()throws Exception{
        Response reply=open();host.active=false;idle();assertNull(screen.launched);assertNull(work());assertNull(reply.value);
    }
    @Test public void nativeDeadlineRevokesBeforeConsentAndKeepsUncertainLaunchedOwnership()throws Exception{
        Response reply=open();idle();CountDownLatch cancelled=new CountDownLatch(1);ExternalLaunchControl.Registration registration=register(cancelled);
        runtime.onPause();Shadows.shadowOf(Looper.getMainLooper()).idleFor(300000,TimeUnit.MILLISECONDS);
        assertEquals("TIMEOUT",reply.error());assertTrue(cancelled.await(5,TimeUnit.SECONDS));assertTrue(registration.isRevoked());assertNotNull(work());
        runtime.onResume();assertEquals("BUSY",open().error());runtime.onActivityResult(47,Activity.RESULT_OK,result());assertNull(work());assertEquals(1,reply.count);
        registration.close();
    }
    @Test public void expiryIsCheckedAtResultEvenWhenTimerHasNotRun()throws Exception{
        Response reply=open();idle();org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300000));
        runtime.onActivityResult(47,Activity.RESULT_OK,result());assertEquals("TIMEOUT",reply.error());assertNull(work());idle();assertEquals(1,reply.count);
    }
    @Test public void earlyRevocationIsObservedByLateHostRegistration()throws Exception{
        open();idle();runtime.reset();CountDownLatch cancelled=new CountDownLatch(1);ExternalLaunchControl.Registration registration=register(cancelled);
        assertTrue(registration.isRevoked());assertEquals(0,cancelled.getCount());registration.close();
    }
    @Test public void launchFailureReleasesOwnerForFreshExplicitRequest()throws Exception{
        screen.reject=true;Response failed=open();idle();assertEquals("EXTERNAL_UNAVAILABLE",failed.error());assertNull(work());assertNull(field("uiOwner").get(runtime));
        screen.reject=false;open();idle();assertNotNull(screen.launched);
    }
    @Test public void previewNeverLaunchesAndMissingOrAmbiguousHostFailsClosed()throws Exception{
        host.preview=true;assertEquals("UNAVAILABLE",open().error());assertNull(screen.launched);host.preview=false;
        DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.RECOVERY,new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        assertEquals("UNAVAILABLE",open().error());assertNull(screen.launched);
    }
    @Test public void sharedUidAndChangedPinnedHostNeverLaunch()throws Exception{
        Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(hostUid(),DocumentBrokerIdentity.PRIMARY,"com.other.app");
        assertEquals("UNAVAILABLE",open().error());assertNull(screen.launched);
        Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(hostUid(),DocumentBrokerIdentity.PRIMARY);
        Response pending=open();DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.PRIMARY,new Signature(new byte[]{9,8,7}));idle();
        assertEquals("EXTERNAL_UNAVAILABLE",pending.error());assertNull(screen.launched);assertNull(work());
    }
    @Test public void resultFailsIfPinnedHostChangedDuringReview()throws Exception{
        Response pending=open();idle();Intent result=result();DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.PRIMARY,new Signature(new byte[]{9,8,7}));
        runtime.onActivityResult(47,Activity.RESULT_OK,result);assertEquals("EXTERNAL_UNAVAILABLE",pending.error());
    }
    @Test public void hostControlRegistrationRechecksCurrentCertificateAndUniqueUid()throws Exception{
        open();idle();Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(hostUid(),DocumentBrokerIdentity.PRIMARY,"com.other.app");
        try{register(new CountDownLatch(1));fail();}catch(ExecutionException expected){}
    }
    @Test public void forgedExpandedAndOverclaimingResultsAreRejectedWithoutSecondReply()throws Exception{
        for(int variant=0;variant<13;variant++){
            Response response=open();idle();Intent output=result();
            switch(variant){
                case 0:output.putExtra("nonce","bad");break;case 1:output.putExtra("actionConfirmed",true);break;
                case 2:output.putExtra("launchRequested","true");break;case 3:output.putExtra("unexpected",true);break;
                case 4:output.setData(android.net.Uri.parse("https://example.com/"));break;case 5:output.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);break;
                case 6:output.setClipData(android.content.ClipData.newPlainText("x","x"));break;case 7:output.setSelector(new Intent());break;
                case 8:output.setAction(Intent.ACTION_VIEW);break;case 9:output.setPackage("com.example.maps");break;
                case 10:output.setComponent(new android.content.ComponentName("com.example.maps","ExternalLaunch"));break;
                case 11:output.setType("text/html");break;case 12:output.addCategory(Intent.CATEGORY_BROWSABLE);break;
            }
            runtime.onActivityResult(47,Activity.RESULT_OK,output);assertEquals("EXTERNAL_UNAVAILABLE",response.error());
            runtime.onActivityResult(47,Activity.RESULT_OK,result());assertEquals(1,response.count);
        }
    }
    @Test public void phoneAloneUsesExactTypedWireAndNoDocumentOrMapAuthority()throws Exception{
        setCapabilities("[\"phone\"]");
        Response reply=request("phone.dial",new JSONObject().put("number","+15550100"));idle();
        assertEquals("phone.dial",screen.launched.getStringExtra("method"));
        assertEquals("+15550100",new JSONObject(screen.launched.getStringExtra("args")).getString("number"));
        assertEquals("tel:+15550100",ExternalLaunchRequest.parse(screen.launched.getStringExtra("method"),screen.launched.getStringExtra("args")).uri);
        assertEquals("CAPABILITY_DENIED",open().error());
        runtime.onActivityResult(47,Activity.RESULT_OK,result());
        assertTrue(reply.value.getJSONObject("result").getBoolean("launchRequested"));
        assertFalse(reply.value.getJSONObject("result").getBoolean("actionConfirmed"));
    }
    @Test public void mapsAndPhoneCannotOverlapSharedNativeUiOwnership()throws Exception{
        setCapabilities("[\"maps\",\"phone\"]");open();idle();
        assertEquals("BUSY",request("phone.dial",new JSONObject().put("number","123")).error());
        runtime.onActivityResult(47,Activity.RESULT_OK,result());
        request("phone.dial",new JSONObject().put("number","123"));idle();
        assertEquals("phone.dial",screen.launched.getStringExtra("method"));
    }
    @Test public void declinedLaunchIsRepresentedWithoutActionOrClosureClaim()throws Exception{
        Response response=open();idle();runtime.onActivityResult(47,Activity.RESULT_OK,result().putExtra("launchRequested",false));
        assertFalse(response.value.getJSONObject("result").getBoolean("launchRequested"));assertFalse(response.value.getJSONObject("result").getBoolean("actionConfirmed"));
    }
    @Test public void coordinatesUseNumericWireAndMapUriNeverOriginatedFromJavascript()throws Exception{
        Response reply=request("maps.open",new JSONObject().put("latitude",-0.0).put("longitude",1e-7));idle();
        assertEquals("maps.open",screen.launched.getStringExtra("method"));
        JSONObject args=new JSONObject(screen.launched.getStringExtra("args"));
        assertTrue(args.get("latitude") instanceof Number);assertTrue(args.get("longitude") instanceof Number);
        assertEquals("geo:0,0.0000001",ExternalLaunchRequest.parse("maps.open",screen.launched.getStringExtra("args")).uri);
        assertNull(screen.launched.getData());assertFalse(screen.launched.hasExtra("uri"));
        runtime.onActivityResult(47,Activity.RESULT_OK,result());assertEquals(1,reply.count);
    }
    @Test public void malformedTypedRequestsAndBackgroundCannotGainNativeOwnership()throws Exception{
        for(JSONObject args:new JSONObject[]{new JSONObject().put("query","q").put("latitude",0),new JSONObject().put("latitude","1").put("longitude",2),new JSONObject().put("query","\u202equery"),new JSONObject().put("query","q").put("uri","geo:1,2")}) {
            assertEquals("INVALID_ARGUMENT",request("maps.open",args).error());assertNull(work());assertNull(screen.launched);
        }
        runtime.onPause();assertEquals("UNAVAILABLE",open().error());assertNull(work());runtime.onResume();
        screen.focused=false;assertEquals("BUSY",open().error());assertNull(work());screen.focused=true;
        setCapabilities("[\"phone\"]");
        for(Object number:new Object[]{123,"*123#","+1 23","tel:123"}) {
            assertEquals("INVALID_ARGUMENT",request("phone.dial",new JSONObject().put("number",number)).error());assertNull(work());assertNull(screen.launched);
        }
    }
    @Test public void browserResultProtocolCannotSettleTypedExternalRequestAsSuccessful()throws Exception{
        Response reply=open();idle();
        Intent browserResult=new Intent().putExtra("nonce",screen.launched.getStringExtra("nonce")).putExtra("launchRequested",true).putExtra("pageLoadConfirmed",false);
        runtime.onActivityResult(47,Activity.RESULT_OK,browserResult);
        assertEquals("EXTERNAL_UNAVAILABLE",reply.error());assertEquals(1,reply.count);
    }

    private JSONObject editorArgs(String capability) throws Exception {
        return capability.equals("email") ? new JSONObject().put("to", "fixture+tag@example.invalid").put("subject", "Subject").put("body", "Line 1\nLine 2")
                : new JSONObject().put("number", "+00123").put("body", "SMS fixture");
    }
    @Test public void editorsUseOnlyExactPinnedNativeReviewAndNeverReturnDeliveryClaims() throws Exception {
        for (String capability : new String[]{"email", "sms"}) {
            setCapabilities("[\"" + capability + "\"]");
            JSONObject args = editorArgs(capability); Response response = request(capability + ".compose", args); idle();
            assertEquals(capability + ".compose", screen.launched.getStringExtra("method"));
            assertEquals(args.toString(), screen.launched.getStringExtra("args"));
            assertEquals("com.jarvys.agent.apkfactory.FactoryExternalActionActivity", screen.launched.getComponent().getClassName());
            assertNull(screen.launched.getData()); assertNull(screen.launched.getAction()); assertNull(screen.launched.getClipData());
            assertEquals(0, screen.launched.getFlags());
            runtime.onActivityResult(47, Activity.RESULT_OK, result());
            JSONObject receipt = response.value.getJSONObject("result");
            assertEquals(2, receipt.length()); assertTrue(receipt.getBoolean("launchRequested")); assertFalse(receipt.getBoolean("actionConfirmed"));
            assertFalse(receipt.has("sent")); assertFalse(receipt.has("delivered")); assertNull(work());
        }
    }
    @Test public void editorQueuedAuthorityCannotSurvivePauseOrSwitchToAnotherCapability() throws Exception {
        for (String capability : new String[]{"email", "sms"}) {
            setCapabilities("[\"" + capability + "\"]"); screen.launched = null;
            Response response = request(capability + ".compose", editorArgs(capability)); runtime.onPause(); idle();
            assertEquals("CANCELLED", response.error()); assertNull(screen.launched); assertNull(work()); runtime.onResume();
            String other = capability.equals("email") ? "sms" : "email";
            assertEquals("CAPABILITY_DENIED", request(other + ".compose", editorArgs(other)).error());
        }
    }
    @Test public void editorCancellationKeepsLaunchedOwnerAndRejectsForgedCompletion() throws Exception {
        setCapabilities("[\"email\"]"); Response response = request("email.compose", editorArgs("email")); idle();
        CountDownLatch cancelled = new CountDownLatch(1); ExternalLaunchControl.Registration registration = register(cancelled);
        runtime.reset(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertNotNull(work());
        assertEquals("BUSY", request("email.compose", editorArgs("email")).error());
        runtime.onActivityResult(47, Activity.RESULT_OK, result().putExtra("sent", true));
        assertNull(response.value); assertNull(work()); registration.close();
    }

}
