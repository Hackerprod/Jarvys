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
@Config(sdk={24,28,32},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimePhotosTest {
    public static class Screen extends Activity {
        Intent launched; boolean reject;
        @Override public boolean hasWindowFocus(){return true;}
        @Override public void startActivityForResult(Intent intent,int request){assertEquals(44,request);if(reject)throw new android.content.ActivityNotFoundException();launched=intent;}
    }
    private static class Host implements FactoryRuntime.Host {
        final MemoryBackend data=new MemoryBackend(); boolean preview;
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return FactoryDispatcherTest.configuration("[\"documents\",\"photos\",\"share\"]");}
        public boolean isPreview(){return preview;}
        public BoundedStore.Backend storage(){return data;}
    }
    private static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        public void postMessage(String text){try{value=new JSONObject(text);}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] bytes){throw new AssertionError();}
        String error()throws Exception{assertNotNull(value);return value.getJSONObject("error").getString("code");}
    }
    private FileShareTransfer endpoint;
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
    @After public void stop()throws Exception{if(endpoint!=null)endpoint.close();runtime.close();assertTrue(worker().awaitTermination(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();screen.finish();}
    private Response request(String method,JSONObject args)throws Exception{
        Response r=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);
        receive.invoke(runtime,new JSONObject().put("v",1).put("id","share"+(++next)).put("method",method).put("args",args).toString(),r);return r;
    }
    private Response photo()throws Exception{return request("photos.pick",new JSONObject());}
    private ThreadPoolExecutor photoWorker()throws Exception{return (ThreadPoolExecutor)field("PHOTO_IO").get(null);}
    private void idle()throws Exception{CountDownLatch done=new CountDownLatch(1);assertTrue(photoWorker().getQueue().offer(done::countDown,5,TimeUnit.SECONDS));assertTrue(done.await(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private Intent result(byte[] bytes)throws Exception{
        if(endpoint!=null)endpoint.close();
        endpoint=FileShareTransfer.reserve().complete(bytes,screen.launched.getStringExtra("nonce"),uid->true);
        android.os.Bundle extras=new android.os.Bundle();extras.putString("nonce",screen.launched.getStringExtra("nonce"));
        extras.putBinder("transfer",endpoint);extras.putInt("size",endpoint.size);extras.putString("sha256",endpoint.sha256);
        extras.putString("mimeType","image/png");extras.putInt("width",2);extras.putInt("height",2);
        return new Intent().putExtras(extras);
    }
    @Test public void exactRequestAndVerifiedOpaqueReadHandle()throws Exception{
        Response response=photo();assertNull(response.value);
        assertEquals("com.jarvys.agent.apkfactory.FactoryPhotoActivity",screen.launched.getComponent().getClassName());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("operation","nonce")),screen.launched.getExtras().keySet());
        assertEquals("pick",screen.launched.getStringExtra("operation"));assertNull(screen.launched.getData());
        runtime.onPause();runtime.onActivityResult(44,Activity.RESULT_OK,result(new byte[]{1,2,3}));
        assertNull(response.value);runtime.onResume();idle();
        JSONObject value=response.value.getJSONObject("result");assertEquals("read",value.getString("mode"));
        assertEquals(3,value.getInt("size"));assertTrue(value.getBoolean("metadataRetained"));
        assertFalse(value.has("transfer"));assertFalse(value.has("uri"));assertFalse(value.has("path"));
        assertEquals("AQID",handles().read(value.getString("handle"),0,3).base64);
        assertTrue(handles().read(value.getString("handle"),3,1).eof);
    }
    @Test public void previewAndMissingHostNeverLaunch()throws Exception{
        host.preview=true;assertEquals("UNAVAILABLE",photo().error());assertNull(screen.launched);
        host.preview=false;DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.RECOVERY,new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        assertEquals("UNAVAILABLE",photo().error());assertNull(screen.launched);
    }
    @Test public void malformedHostFieldsNeverGrant()throws Exception{
        for(int variant=0;variant<12;variant++){
            Response response=photo();Intent data=result(new byte[]{1});
            if(variant==0)data.putExtra("nonce","bad");if(variant==1)data.putExtra("size",0);
            if(variant==2)data.putExtra("size",1L);if(variant==3)data.putExtra("mimeType","image/gif");
            if(variant==4)data.putExtra("width",4097);if(variant==5){data.putExtra("width",4096);data.putExtra("height",4096);}
            if(variant==6)data.putExtra("sha256","A".repeat(64));if(variant==7)data.putExtra("unexpected",true);
            if(variant==8)data.setData(android.net.Uri.parse("content://private/photo"));if(variant==9)data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(variant==10)data.putExtra("height",0);if(variant==11)data.removeExtra("transfer");
            runtime.onActivityResult(44,Activity.RESULT_OK,data);assertEquals("INVALID_PHOTO",response.error());
            endpoint.close();
        }
    }
    @Test public void digestMismatchNeverExposesHandle()throws Exception{
        Response response=photo();Intent data=result(new byte[]{1});data.putExtra("sha256","0".repeat(64));
        runtime.onActivityResult(44,Activity.RESULT_OK,data);idle();assertEquals("INVALID_SHARE",response.error());
        assertNull(field("photoSelection").get(runtime));
    }
    @Test public void cancellationRetainsPickerTombstoneAndLateResultCannotGrant()throws Exception{
        Response first=photo();request("documents.cancel",new JSONObject());assertEquals("CANCELLED",first.error());
        assertEquals("BUSY",photo().error());runtime.onActivityResult(44,Activity.RESULT_OK,result(new byte[]{1}));
        assertNull(field("photoSelection").get(runtime));Response next=photo();assertNull(next.value);
        runtime.onActivityResult(44,Activity.RESULT_CANCELED,null);assertEquals("CANCELLED",next.error());
    }
    @Test public void pauseWhileBlockedCopySettlesAndRetainsAdmission()throws Exception{
        Response response=photo();Intent data=result(new byte[]{1});
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        android.os.Binder blocker=new android.os.Binder(){
            protected boolean onTransact(int code,android.os.Parcel in,android.os.Parcel out,int flags){
                entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}return false;
            }
        };
        data.getExtras();android.os.Bundle extras=data.getExtras();extras.putBinder("transfer",blocker);data.replaceExtras(extras);
        runtime.onActivityResult(44,Activity.RESULT_OK,data);
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));runtime.onPause();assertEquals("CANCELLED",response.error());
            java.util.concurrent.Semaphore admission=(java.util.concurrent.Semaphore)field("PHOTO_ADMISSION").get(null);
            assertEquals(0,admission.availablePermits());assertNotNull(field("photoSelection").get(runtime));
            release.countDown();idle();assertEquals(1,admission.availablePermits());assertNull(field("photoSelection").get(runtime));
        }finally{release.countDown();}
    }
    @Test public void changedHostBeforeResultFailsClosed()throws Exception{
        Response response=photo();Intent data=result(new byte[]{1});
        DocumentBrokerIdentityTest.install(screen,DocumentBrokerIdentity.PRIMARY,new Signature(new byte[]{9,8,7}));
        runtime.onActivityResult(44,Activity.RESULT_OK,data);assertEquals("UNAVAILABLE",response.error());
    }
    @Test public void launchFailureAllowsRetryAndCaptureOperation()throws Exception{
        screen.reject=true;assertEquals("UNAVAILABLE",photo().error());assertNull(field("photoSelection").get(runtime));
        screen.reject=false;Response response=request("photos.capture",new JSONObject());assertEquals("capture",screen.launched.getStringExtra("operation"));
        runtime.onActivityResult(44,Activity.RESULT_CANCELED,null);assertEquals("CANCELLED",response.error());
    }
}
