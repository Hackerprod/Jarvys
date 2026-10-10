package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.Signature;
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

/** Synthetic runtime/broker boundary only; no chooser, recipient or user file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,32},manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeFileSharingTest {
    public static class Screen extends Activity {
        Intent launched; boolean reject;
        @Override public boolean hasWindowFocus(){return true;}
        @Override public void startActivityForResult(Intent intent,int request){assertEquals(43,request);if(reject)throw new android.content.ActivityNotFoundException();launched=intent;}
    }
    private static class Host implements FactoryRuntime.Host {
        final MemoryBackend data=new MemoryBackend(); boolean preview;
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return FactoryDispatcherTest.configuration("[\"documents\",\"share\"]");}
        public boolean isPreview(){return preview;}
        public BoundedStore.Backend storage(){return data;}
    }
    private static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        public void postMessage(String text){try{value=new JSONObject(text);}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] bytes){throw new AssertionError();}
        String error()throws Exception{assertNotNull(value);return value.getJSONObject("error").getString("code");}
    }
    private FactoryRuntime runtime; private Screen screen; private Host host; private int next;
    static Field field(String name)throws Exception{Field f=FactoryRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private DocumentHandles handles()throws Exception{return (DocumentHandles)field("documents").get(runtime);}
    private ThreadPoolExecutor worker()throws Exception{return (ThreadPoolExecutor)field("io").get(runtime);}
    @Before public void setup()throws Exception{
        screen=Robolectric.buildActivity(Screen.class).setup().get();
        DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.PRIMARY,new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        android.content.pm.ApplicationInfo info=screen.getPackageManager().getApplicationInfo(DocumentBrokerIdentity.PRIMARY,0);
        info.uid=17777;Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(17777,DocumentBrokerIdentity.PRIMARY);
        host=new Host();runtime=new FactoryRuntime(screen,new FrameLayout(screen),host);
        field("config").set(runtime,FactoryConfig.parsePreview(new JSONObject(host.configuration()).put("documentBroker",
                new JSONObject().put("packageName",DocumentBrokerIdentity.PRIMARY).put("certificateSha256",DocumentBrokerIdentityTest.digest())).toString()));
        field("store").set(runtime,new BoundedStore(host.data));runtime.onResume();
    }
    @After public void stop()throws Exception{runtime.close();assertTrue(worker().awaitTermination(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();screen.finish();}
    private Response request(String method,JSONObject args)throws Exception{
        Response r=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);
        receive.invoke(runtime,new JSONObject().put("v",1).put("id","share"+(++next)).put("method",method).put("args",args).toString(),r);return r;
    }
    private Response share(byte[] data)throws Exception{return shareHandle(handles().grantRead(new ByteArrayInputStream(data)));}
    private Response shareHandle(String token)throws Exception{return request("share.file",new JSONObject().put("handle",token).put("filename","fixture.bin").put("mimeType","application/octet-stream"));}
    private void idle()throws Exception{CountDownLatch done=new CountDownLatch(1);worker().execute(done::countDown);assertTrue(done.await(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private Intent result(){return new Intent().putExtra("nonce",screen.launched.getStringExtra("nonce")).putExtra("chooserOpened",true).putExtra("deliveryConfirmed",false);}
    @Test public void snapshotPrecedesBrokerLaunchAndNoBytesOrUriEnterIntent()throws Exception{
        String token=handles().grantRead(new ByteArrayInputStream(new byte[262144]));Response reply=shareHandle(token);idle();
        assertNull(reply.value);assertNotNull(screen.launched);assertEquals("com.jarvys.agent.apkfactory.FactoryFileShareActivity",screen.launched.getComponent().getClassName());
        assertEquals(262144,screen.launched.getIntExtra("size",0));assertNotNull(screen.launched.getExtras().getBinder("transfer"));
        assertNull(screen.launched.getData());assertNull(screen.launched.getClipData());assertEquals(0,screen.launched.getFlags());
        try{handles().read(token,0,1);fail();}catch(FactoryException expected){assertEquals("INVALID_HANDLE",expected.code);}
        runtime.onPause();runtime.onActivityResult(43,Activity.RESULT_OK,result());assertNull(reply.value);runtime.onResume();
        assertTrue(reply.value.getJSONObject("result").getBoolean("chooserOpened"));assertFalse(reply.value.getJSONObject("result").getBoolean("deliveryConfirmed"));
    }
    @Test public void duplicateRequestsRemainBusyUntilBrokerCallback()throws Exception{
        share(new byte[]{1});idle();Response duplicate=share(new byte[]{2});assertEquals("BUSY",duplicate.error());
        request("documents.cancel",new JSONObject());Response stillBusy=share(new byte[]{3});assertEquals("BUSY",stillBusy.error());
        runtime.onActivityResult(43,Activity.RESULT_CANCELED,null);assertNull(field("fileShare").get(runtime));
    }
    @Test public void noChooserForPreviewOrInvalidSource()throws Exception{
        host.preview=true;assertEquals("UNAVAILABLE",share(new byte[]{1}).error());assertNull(screen.launched);
        host.preview=false;Response empty=share(new byte[0]);idle();assertEquals("EMPTY_FILE",empty.error());assertNull(screen.launched);
    }
    @Test public void noChooserAfterPauseBeforeQueuedSnapshot()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);worker().execute(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));Response response=share(new byte[]{1});runtime.onPause();release.countDown();idle();
        assertNotNull(response.value);assertNull(screen.launched);assertNull(field("fileShare").get(runtime));
    }
    @Test public void launchFailureReleasesEndpointAndAllowsFreshRequest()throws Exception{
        screen.reject=true;Response failed=share(new byte[]{1});idle();assertEquals("SHARE_UNAVAILABLE",failed.error());assertNull(field("fileShare").get(runtime));
        screen.reject=false;share(new byte[]{2});idle();assertNotNull(screen.launched);runtime.onActivityResult(43,Activity.RESULT_CANCELED,null);
    }
    @Test public void forgedOrOverclaimingHostResultFailsClosed()throws Exception{
        for(int variant=0;variant<5;variant++){
            Response response=share(new byte[]{1});idle();Intent output=result();
            if(variant==0)output.putExtra("nonce","bad");if(variant==1)output.putExtra("deliveryConfirmed",true);
            if(variant==2)output.setData(android.net.Uri.parse("content://private/file"));if(variant==3)output.putExtra("unexpected",true);
            if(variant==4)output.putExtra("chooserOpened","true");
            runtime.onActivityResult(43,Activity.RESULT_OK,output);assertEquals("SHARE_UNAVAILABLE",response.error());
        }
    }
    @Test public void wrongOrAmbiguousHostNeverReceivesSnapshot()throws Exception{
        DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.RECOVERY,new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        assertEquals("UNAVAILABLE",share(new byte[]{1}).error());assertNull(screen.launched);
    }
}
