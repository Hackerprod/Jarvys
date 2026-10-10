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
public class FactoryRuntimeContactsTest {
    public static class Screen extends Activity {
        Intent launched; boolean reject; boolean focused=true;
        @Override public boolean hasWindowFocus(){return focused;}
        @Override public void startActivityForResult(Intent intent,int request){assertEquals(48,request);if(reject)throw new android.content.ActivityNotFoundException();launched=intent;}
    }
    private static class Host implements FactoryRuntime.Host {
        final MemoryBackend data=new MemoryBackend(); boolean preview,active=true;
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return FactoryDispatcherTest.configuration("[\"contacts\"]");}
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
    private Object work()throws Exception{return field("contactPick").get(runtime);}
    private ThreadPoolExecutor worker()throws Exception{return (ThreadPoolExecutor)field("io").get(runtime);}
    @Before public void setup()throws Exception{
        screen=Robolectric.buildActivity(Screen.class).setup().get();installHost();
        host=new Host();runtime=new FactoryRuntime(screen,new FrameLayout(screen),host);setCapabilities("[\"contacts\"]");
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
        receive.invoke(runtime,new JSONObject().put("v",1).put("id","contact"+(++next)).put("method",method).put("args",args).toString(),r);return r;
    }
    private Response open()throws Exception{return request("contacts.pick",new JSONObject().put("kind","phone"));}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private Intent result(){return new Intent().putExtra("nonce",screen.launched.getStringExtra("nonce")).putExtra("kind",screen.launched.getStringExtra("kind")).putExtra("value"," +1 (555) 0100 ext. 42 ");}
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
    @Test public void expectedBrokerPauseDoesNotRevokeAndDocumentsCancelHasNoExternalLaunchAuthority()throws Exception{
        setCapabilities("[\"contacts\",\"documents\"]");Response reply=open();idle();CountDownLatch cancelled=new CountDownLatch(1);
        ExternalLaunchControl.Registration registration=register(cancelled);
        runtime.onPause();assertFalse(registration.isRevoked());runtime.onResume();request("documents.cancel",new JSONObject());
        assertFalse(registration.isRevoked());assertNull(reply.value);assertEquals("BUSY",open().error());
        runtime.onActivityResult(48,Activity.RESULT_OK,result());assertTrue(cancelled.await(5,TimeUnit.SECONDS));registration.close();
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
        assertNotNull(work());assertEquals("BUSY",open().error());runtime.onActivityResult(48,Activity.RESULT_OK,result());assertNull(work());
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
        runtime.onResume();assertEquals("BUSY",open().error());runtime.onActivityResult(48,Activity.RESULT_OK,result());assertNull(work());assertEquals(1,reply.count);
        registration.close();
    }
    @Test public void expiryIsCheckedAtResultEvenWhenTimerHasNotRun()throws Exception{
        Response reply=open();idle();org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300000));
        runtime.onActivityResult(48,Activity.RESULT_OK,result());assertEquals("TIMEOUT",reply.error());assertNull(work());idle();assertEquals(1,reply.count);
    }
    @Test public void earlyRevocationIsObservedByLateHostRegistration()throws Exception{
        open();idle();runtime.reset();CountDownLatch cancelled=new CountDownLatch(1);ExternalLaunchControl.Registration registration=register(cancelled);
        assertTrue(registration.isRevoked());assertEquals(0,cancelled.getCount());registration.close();
    }
    @Test public void launchFailureReleasesOwnerForFreshExplicitRequest()throws Exception{
        screen.reject=true;Response failed=open();idle();assertEquals("CONTACT_UNAVAILABLE",failed.error());assertNull(work());assertNull(field("uiOwner").get(runtime));
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
        assertEquals("CONTACT_UNAVAILABLE",pending.error());assertNull(screen.launched);assertNull(work());
    }
    @Test public void resultFailsIfPinnedHostChangedDuringReview()throws Exception{
        Response pending=open();idle();Intent result=result();DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.PRIMARY,new Signature(new byte[]{9,8,7}));
        runtime.onActivityResult(48,Activity.RESULT_OK,result);assertEquals("CONTACT_UNAVAILABLE",pending.error());
    }
    @Test public void hostControlRegistrationRechecksCurrentCertificateAndUniqueUid()throws Exception{
        open();idle();Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(hostUid(),DocumentBrokerIdentity.PRIMARY,"com.other.app");
        try{register(new CountDownLatch(1));fail();}catch(ExecutionException expected){}
    }

    @Test public void onlyExactPinnedNativeHostReceivesKindAndMinimalControlEnvelope() throws Exception {
        for(String kind:new String[]{"phone","email"}) {
            screen.launched=null;
            Response reply=request("contacts.pick",new JSONObject().put("kind",kind)); assertNull(screen.launched); idle();
            assertNotNull(screen.launched); assertNull(reply.value);
            assertEquals("com.jarvys.agent",screen.launched.getComponent().getPackageName());
            assertEquals("com.jarvys.agent.apkfactory.FactoryContactActivity",screen.launched.getComponent().getClassName());
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("protocolVersion","nonce","kind","control")),screen.launched.getExtras().keySet());
            assertEquals(1,screen.launched.getIntExtra("protocolVersion",0)); assertEquals(kind,screen.launched.getStringExtra("kind"));
            assertTrue(screen.launched.getStringExtra("nonce").matches("[a-f0-9]{64}")); assertNotNull(screen.launched.getExtras().getBinder("control"));
            assertNull(screen.launched.getAction()); assertNull(screen.launched.getData()); assertNull(screen.launched.getType());
            assertNull(screen.launched.getClipData()); assertNull(screen.launched.getSelector()); assertNull(screen.launched.getSourceBounds());
            assertEquals(0,screen.launched.getFlags());
            assertEquals(java.util.Collections.singleton("contacts"),((FactoryConfig)field("config").get(runtime)).capabilities);
            FileShareTransfer.reserve().close();
            String value=kind.equals("phone")?" +1 (555) 0100 ext. 42 ":" élève@例え.invalid ";
            runtime.onPause(); runtime.onActivityResult(48,Activity.RESULT_OK,result().putExtra("value",value)); assertNull(reply.value);
            runtime.onResume(); JSONObject data=reply.value.getJSONObject("result");
            assertEquals(2,data.length()); assertEquals(kind,data.getString("kind")); assertEquals(value,data.getString("value"));
            assertFalse(data.has("nonce")); assertFalse(data.has("name")); assertFalse(data.has("uri")); assertFalse(data.has("actionConfirmed"));
            assertEquals(1,reply.count); assertNull(work());
        }
    }
    @Test public void everyUnexpectedResultEnvelopeFieldAndTypeFailsClosed() throws Exception {
        for(int variant=0;variant<30;variant++) {
            Response response=open(); idle(); Intent output=result();
            switch(variant) {
                case 0:output.putExtra("nonce","bad");break;
                case 1:output.putExtra("nonce",1);break;
                case 2:output.removeExtra("nonce");break;
                case 3:output.putExtra("kind","email");break;
                case 4:output.putExtra("kind","PHONE");break;
                case 5:output.putExtra("kind",1);break;
                case 6:output.removeExtra("kind");break;
                case 7:output.putExtra("value",true);break;
                case 8:output.putExtra("value",(String)null);break;
                case 9:output.removeExtra("value");break;
                case 10:output.putExtra("unexpected",true);break;
                case 11:output.setData(android.net.Uri.parse("content://com.android.contacts/data/1"));break;
                case 12:output.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);break;
                case 13:output.setClipData(android.content.ClipData.newPlainText("x","x"));break;
                case 14:output.setSelector(new Intent());break;
                case 15:output.setAction(Intent.ACTION_PICK);break;
                case 16:output.setPackage("com.example.contacts");break;
                case 17:output.setComponent(new android.content.ComponentName("com.example.contacts","Picker"));break;
                case 18:output.setType("text/plain");break;
                case 19:output.addCategory(Intent.CATEGORY_DEFAULT);break;
                case 20:output.setSourceBounds(new android.graphics.Rect(0,0,1,1));break;
                case 21:if(android.os.Build.VERSION.SDK_INT>=29)output.setIdentifier("forged");else output.putExtra("identifier","forged");break;
                case 22:output.putExtra("protocolVersion",1);break;
                case 23:output.putExtra("displayName","Synthetic Person");break;
                case 24:output.putExtra("contactId","1");break;
                case 25:output.putExtra("lookupKey","forbidden");break;
                case 26:output.putExtra("launchRequested",true);break;
                case 27:output.putExtra("actionConfirmed",true);break;
                case 28:output.putExtra("value",new String[]{"a","b"});break;
                case 29:output.putExtra("nonce",new String[]{screen.launched.getStringExtra("nonce")});break;
            }
            runtime.onActivityResult(48,Activity.RESULT_OK,output); assertEquals("CONTACT_UNAVAILABLE",response.error());
            runtime.onActivityResult(48,Activity.RESULT_OK,result()); assertEquals(1,response.count); assertNull(work());
            assertFalse(response.value.toString().contains("555"));
        }
    }
    @Test public void unsafeContactValuesCannotReachJavascript() throws Exception {
        for(String value:new String[]{"", " \u00a0", "\ud800", "\udc00", "a\n", "a\t", "a\u007f", "a\u0085", "a\u200d", "a\u202e", "a\u2066", "a\u2028", "a\u2029", new String(new char[257]).replace('\0','a'),repeat("😀",257)}) {
            Response response=open(); idle(); runtime.onActivityResult(48,Activity.RESULT_OK,result().putExtra("value",value));
            assertEquals("CONTACT_UNAVAILABLE",response.error()); assertEquals(1,response.count); assertNull(work());
        }
    }
    private String repeat(String value,int count) { StringBuilder b=new StringBuilder(); for(int i=0;i<count;i++)b.append(value); return b.toString(); }
    @Test public void exactMaximumScalarCountAndUnusualActionSyntaxAreReturnedUnchanged() throws Exception {
        for(String value:new String[]{repeat("😀",256),"*123#", "not-an-email", "e\u0301", "  +1 (555) 0100 ext. 42  "}) {
            Response response=open(); idle(); runtime.onActivityResult(48,Activity.RESULT_OK,result().putExtra("value",value));
            assertEquals(value,response.value.getJSONObject("result").getString("value")); assertEquals(2,response.value.getJSONObject("result").length());
        }
    }
    @Test public void resultKindMustMatchTheOriginalRequestedKind() throws Exception {
        Response response=request("contacts.pick",new JSONObject().put("kind","email")); idle();
        runtime.onActivityResult(48,Activity.RESULT_OK,result().putExtra("kind","phone")); assertEquals("CONTACT_UNAVAILABLE",response.error());
    }
    @Test public void cancelledResultCannotCarryAnyDataAndUnknownCodesNeverReleaseData() throws Exception {
        Response cancelled=open(); idle(); runtime.onActivityResult(48,Activity.RESULT_CANCELED,null); assertEquals("CANCELLED",cancelled.error());
        for(int code:new int[]{Activity.RESULT_CANCELED,7}) {
            Response response=open(); idle(); runtime.onActivityResult(48,code,result()); assertEquals("CONTACT_UNAVAILABLE",response.error());
        }
        Response absent=open(); idle(); runtime.onActivityResult(48,Activity.RESULT_OK,null); assertEquals("CONTACT_UNAVAILABLE",absent.error());
    }
    @Test public void changedHostUidOrSharedUidAtResultCannotReleaseSelectedValue() throws Exception {
        Response response=open(); idle();
        Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(hostUid(),DocumentBrokerIdentity.PRIMARY,"com.other.app");
        runtime.onActivityResult(48,Activity.RESULT_OK,result()); assertEquals("CONTACT_UNAVAILABLE",response.error());
    }
    @Test public void malformedKindsAndBackgroundNeverAcquireContactAuthority() throws Exception {
        for(JSONObject args:new JSONObject[]{new JSONObject(),new JSONObject().put("kind","all"),new JSONObject().put("kind",true),new JSONObject().put("kind","phone").put("uri","content://forbidden")}) {
            assertEquals("INVALID_ARGUMENT",request("contacts.pick",args).error()); assertNull(work()); assertNull(screen.launched);
        }
        runtime.onPause(); assertEquals("UNAVAILABLE",open().error()); assertNull(work()); runtime.onResume();
        screen.focused=false; assertEquals("BUSY",open().error()); assertNull(work());
    }
    @Test public void contactAndExistingExternalNativeOperationsShareOnlyUiAdmission() throws Exception {
        setCapabilities("[\"contacts\",\"phone\",\"maps\",\"email\",\"sms\",\"browser\",\"documents\"]");
        open(); idle();
        for(String method:new String[]{"phone.dial","maps.open","email.compose","sms.compose","browser.open","documents.open"}) {
            JSONObject args;
            if(method.equals("phone.dial"))args=new JSONObject().put("number","123");
            else if(method.equals("maps.open"))args=new JSONObject().put("query","Synthetic fixture");
            else if(method.equals("email.compose"))args=new JSONObject().put("to","fixture@example.invalid").put("subject","").put("body","");
            else if(method.equals("sms.compose"))args=new JSONObject().put("number","123").put("body","");
            else if(method.equals("browser.open"))args=new JSONObject().put("url","https://example.com/");
            else args=new JSONObject().put("mimeType","text/plain");
            assertEquals("BUSY",request(method,args).error()); assertNotNull(work());
        }
        runtime.onActivityResult(48,Activity.RESULT_OK,result()); assertNull(work());
    }
    @Test public void returnedContactDataIsDiscardedOnReloadBeforeResume() throws Exception {
        Response old=open(); idle(); Object work=work(); runtime.onPause(); runtime.onActivityResult(48,Activity.RESULT_OK,result());
        Field selected=work.getClass().getDeclaredField("result"); selected.setAccessible(true); assertNotNull(selected.get(work));
        runtime.reset(); assertNull(selected.get(work)); runtime.onResume(); assertNull(old.value); assertNull(work());
    }
    @Test public void repeatedPauseAfterReturnRevokesAndErasesRetainedSelection() throws Exception {
        Response response=open(); idle(); Object work=work(); runtime.onPause(); runtime.onActivityResult(48,Activity.RESULT_OK,result());
        runtime.onPause(); Field selected=work.getClass().getDeclaredField("result"); selected.setAccessible(true); assertNull(selected.get(work));
        runtime.onResume(); assertEquals("CANCELLED",response.error()); assertNull(work()); assertEquals(1,response.count);
    }
    @Test public void returnedValueAfterExpiryRemainsUnreleasedUntilCleanup() throws Exception {
        Response response=open(); idle(); Object work=work();
        runtime.onPause(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(300000,TimeUnit.MILLISECONDS); assertEquals("TIMEOUT",response.error());
        runtime.onActivityResult(48,Activity.RESULT_OK,result());
        Field selected=work.getClass().getDeclaredField("result"); selected.setAccessible(true); assertNull(selected.get(work)); assertNull(work());
        runtime.onResume(); assertEquals(1,response.count);
    }
    @Test public void oldAndOtherProtocolRepliesNeverSettleANewSelection() throws Exception {
        Response first=open(); idle(); Intent old=result(); runtime.onActivityResult(48,Activity.RESULT_OK,old); assertEquals(1,first.count);
        Response second=open(); idle(); assertNotEquals(old.getStringExtra("nonce"),screen.launched.getStringExtra("nonce"));
        runtime.onActivityResult(47,Activity.RESULT_OK,old); assertNull(second.value);
        runtime.onActivityResult(48,Activity.RESULT_OK,old); assertEquals("CONTACT_UNAVAILABLE",second.error());
        Response external=open(); idle(); runtime.onActivityResult(48,Activity.RESULT_OK,new Intent().putExtra("nonce",screen.launched.getStringExtra("nonce")).putExtra("launchRequested",true).putExtra("actionConfirmed",false));
        assertEquals("CONTACT_UNAVAILABLE",external.error());
    }
}
