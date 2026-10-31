package com.jarvys.factory.runtime;

import android.app.Activity;
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

/** Real native SQLite with synthetic data; no device/Chromium/persistence acceptance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,32},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeDatabaseTest {
    static class Host implements FactoryRuntime.Host {
        final MemoryBackend memory=new MemoryBackend(); volatile boolean active=true; boolean preview=true;
        String appId="com.example.database";
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){try{return new JSONObject(FactoryDispatcherTest.configuration("[\"database\"]")).put("appId",appId).toString();}catch(Exception e){throw new AssertionError(e);}}
        public boolean isPreview(){return preview;}
        public boolean isActive(){return active;}
        public BoundedStore.Backend storage(){return memory;}
    }
    static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        public void postMessage(String text){try{value=new JSONObject(text);}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] value){throw new AssertionError();}
        JSONObject result()throws Exception{assertNotNull(value);assertTrue(value.toString(),value.getBoolean("ok"));return value.getJSONObject("result");}
        String error()throws Exception{assertNotNull(value);return value.getJSONObject("error").getString("code");}
    }
    FactoryRuntime runtime; Activity screen; Host host; int next; CountDownLatch release=new CountDownLatch(1);
    static Field field(String name)throws Exception{Field f=FactoryRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    ThreadPoolExecutor worker()throws Exception{return (ThreadPoolExecutor)field("io").get(runtime);}
    @Before public void setup()throws Exception{
        screen=Robolectric.buildActivity(Activity.class).setup().get();host=new Host();runtime=new FactoryRuntime(screen,new FrameLayout(screen),host);
        field("config").set(runtime,FactoryConfig.parsePreview(host.configuration()));field("store").set(runtime,new BoundedStore(host.memory));runtime.onResume();
    }
    @After public void stop()throws Exception{release.countDown();runtime.close();assertTrue(worker().awaitTermination(10,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();screen.finish();}
    Response request(String method,String args)throws Exception{
        Response response=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);
        receive.invoke(runtime,new JSONObject().put("v",1).put("id","database"+(++next)).put("method",method).put("args",new JSONObject(args)).toString(),response);return response;
    }
    void drain()throws Exception{worker().submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void block()throws Exception{CountDownLatch started=new CountDownLatch(1);worker().execute(()->{started.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(5,TimeUnit.SECONDS));}
    String migration(){return "{\"fromVersion\":0,\"toVersion\":1,\"steps\":[{\"kind\":\"createTable\",\"table\":\"notes\",\"columns\":{\"text\":{\"type\":\"text\",\"nullable\":false}}}]}";}
    @Test public void previewCrudRunsWithoutBrokerAndReportsEphemeralMode()throws Exception{
        Response migrate=request("database.migrate",migration());drain();assertEquals(1,migrate.result().getInt("version"));
        Response write=request("database.transact","{\"version\":1,\"operations\":[{\"kind\":\"insert\",\"table\":\"notes\",\"id\":\"one\",\"values\":{\"text\":\"synthetic\"}}]}");drain();assertTrue(write.result().getBoolean("committed"));
        Response select=request("database.select","{\"version\":1,\"table\":\"notes\"}");drain();assertEquals("synthetic",select.result().getJSONArray("rows").getJSONObject(0).getString("text"));
        Response info=request("database.info","{}");drain();assertEquals("preview",info.result().getString("mode"));assertFalse(info.result().getBoolean("persistent"));
        assertNull(field("uiOwner").get(runtime));assertNull(((FactoryConfig)field("config").get(runtime)).documentBroker);
    }
    @Test public void previewStartSetsForegroundLeaseBeforeWebviewInitialization()throws Exception{
        field("documentForeground").set(runtime,false);runtime.start();assertTrue(field("documentForeground").getBoolean(runtime));
    }
    @Test public void cancelInvalidatesQueuedWriteBeforeItCanMigrate()throws Exception{
        block();Response old=request("database.migrate",migration());Response cancel=request("database.cancel","{}");assertTrue(cancel.result().getBoolean("cancelRequested"));
        release.countDown();drain();assertEquals("CANCELLED",old.error());Response info=request("database.info","{}");drain();assertEquals(0,info.result().getInt("version"));
    }
    @Test public void closeInvalidatesOlderQueueAndReopensEmptyPreview()throws Exception{
        request("database.migrate",migration());drain();block();Response old=request("database.info","{}");Response close=request("database.close","{}");release.countDown();drain();
        assertEquals("CANCELLED",old.error());assertTrue(close.result().getBoolean("closed"));assertFalse(close.result().getBoolean("dataRetained"));
        Response info=request("database.info","{}");drain();assertEquals(0,info.result().getInt("version"));
    }
    @Test public void pauseDropsQueueWithoutWaitingAndNewSessionHasNoPreviewData()throws Exception{
        block();Response old=request("database.migrate",migration());runtime.onPause();assertEquals("CANCELLED",old.error());assertNull(field("database").get(runtime));
        release.countDown();drain();runtime.onResume();Response info=request("database.info","{}");drain();assertEquals(0,info.result().getInt("version"));
    }
    @Test public void revokedSessionCannotRunQueuedMigrationOrLeakReply()throws Exception{
        block();Response old=request("database.migrate",migration());host.active=false;release.countDown();drain();assertNull(old.value);
        PrivateDatabase database=(PrivateDatabase)field("database").get(runtime);
        assertEquals(0,((JSONObject)database.execute("database.info",new JSONObject(),database.ticket(),()->true)).getInt("version"));
    }
    @Test public void resetDropsQueuedMigrationAndRetiresMemoryStore()throws Exception{
        block();Response old=request("database.migrate",migration());Method invalidate=FactoryRuntime.class.getDeclaredMethod("invalidate");invalidate.setAccessible(true);invalidate.invoke(runtime);
        release.countDown();drain();assertNull(old.value);assertNull(field("database").get(runtime));Response info=request("database.info","{}");drain();assertEquals(0,info.result().getInt("version"));
    }
    @Test public void undeclaredAndBackgroundRequestsCannotCreateDatabase()throws Exception{
        field("config").set(runtime,FactoryConfig.parsePreview(FactoryDispatcherTest.configuration("[]")));assertEquals("CAPABILITY_DENIED",request("database.info","{}").error());assertNull(field("database").get(runtime));
        field("config").set(runtime,FactoryConfig.parsePreview(host.configuration()));runtime.onPause();assertEquals("CANCELLED",request("database.info","{}").error());assertNull(field("database").get(runtime));
    }
    @Test public void installedIdentityMismatchIsRejectedWithoutDatabase()throws Exception{
        host.preview=false;assertEquals("INVALID_CONFIG",request("database.info","{}").error());assertNull(field("database").get(runtime));
    }
    @Test public void installedDatabaseNeedsNoHostAndRetainsSchemaAfterPause()throws Exception{
        host.preview=false;host.appId=screen.getPackageName();field("config").set(runtime,FactoryConfig.parse(host.configuration(),screen.getPackageName()));
        Response migration=request("database.migrate",migration());drain();assertTrue(migration.result().getBoolean("committed"));runtime.onPause();drain();runtime.onResume();
        Response info=request("database.info","{}");drain();assertEquals(1,info.result().getInt("version"));assertTrue(info.result().getBoolean("persistent"));
    }
}
