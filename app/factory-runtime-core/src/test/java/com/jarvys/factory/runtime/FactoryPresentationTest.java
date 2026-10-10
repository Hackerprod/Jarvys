package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
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

/** Synthetic lifecycle/configuration fixtures, not Android window-manager or Chromium acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,28,35},manifest=Config.NONE,shadows=FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryPresentationTest {
    public static class Screen extends Activity implements FactoryPresentation.Owner {
        FactoryPresentation.State applied=FactoryPresentation.DEFAULT;
        int recreations; boolean focus=true, failCommit, rejectOrientation;
        @Override public FactoryPresentation.State appliedPresentation(){return applied;}
        @Override public boolean hasWindowFocus(){return focus;}
        @Override public void recreate(){recreations++;}
        @Override public void setRequestedOrientation(int requested){
            if(rejectOrientation) throw new IllegalStateException("Synthetic unsupported window policy");
            super.setRequestedOrientation(requested);
        }
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences real=super.getSharedPreferences(name,mode);
            if(!failCommit || !FactoryPresentation.PREFERENCES.equals(name)) return real;
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(proxy,method,args)->{
                if(method.getName().equals("edit")) {
                    SharedPreferences.Editor editor=real.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,m,a)->{
                        Object result=m.invoke(editor,a);
                        if(m.getName().equals("commit")) return false;
                        return result==editor?p:result;
                    });
                }
                return method.invoke(real,args);
            });
        }
    }
    static class Host implements FactoryRuntime.Host {
        final MemoryBackend memory=new MemoryBackend(); boolean active=true,preview;
        String appId;
        public InputStream open(String path){return new ByteArrayInputStream(new byte[0]);}
        public String configuration(){return "{\"schemaVersion\":1,\"appId\":\""+appId+"\",\"name\":\"Synthetic\",\"entryPoint\":\"www/index.html\",\"capabilities\":[\"presentation\"]}";}
        public boolean isPreview(){return preview;}
        public boolean isActive(){return active;}
        public BoundedStore.Backend storage(){return memory;}
    }
    static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        public void postMessage(String text){try{value=new JSONObject(text);}catch(Exception e){throw new AssertionError(e);}}
        public void postMessage(byte[] bytes){throw new AssertionError();}
        JSONObject result()throws Exception{assertNotNull(value);assertTrue(value.toString(),value.getBoolean("ok"));return value.getJSONObject("result");}
        String error()throws Exception{assertNotNull(value);return value.getJSONObject("error").getString("code");}
    }
    Screen screen; FactoryRuntime runtime; Host host; int sequence;
    static Field field(String name)throws Exception{Field f=FactoryRuntime.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @Before public void setup()throws Exception{
        screen=Robolectric.buildActivity(Screen.class).setup().get();
        FactoryPresentation.preferences(screen).edit().clear().commit();
        host=new Host();host.appId=screen.getPackageName();
        runtime=new FactoryRuntime(screen,new FrameLayout(screen),host);
        field("config").set(runtime,FactoryConfig.parse(host.configuration(),screen.getPackageName()));
        field("store").set(runtime,new BoundedStore(host.memory));runtime.onResume();
    }
    @After public void stop()throws Exception{
        runtime.close();assertTrue(((ThreadPoolExecutor)field("io").get(runtime)).awaitTermination(10,TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();screen.failCommit=false;
        FactoryPresentation.preferences(screen).edit().clear().commit();screen.finish();
    }
    Response request(String method,String args)throws Exception{
        Response response=new Response();Method receive=FactoryRuntime.class.getDeclaredMethod("receive",String.class,JavaScriptReplyProxy.class);receive.setAccessible(true);
        receive.invoke(runtime,new JSONObject().put("v",1).put("id","presentation"+(++sequence)).put("method",method).put("args",new JSONObject(args)).toString(),response);return response;
    }
    String pair(String theme,String orientation){return "{\"theme\":\""+theme+"\",\"orientation\":\""+orientation+"\"}";}
    void cancelPage()throws Exception{Method invalidate=FactoryRuntime.class.getDeclaredMethod("invalidate");invalidate.setAccessible(true);invalidate.invoke(runtime);}
    @Test public void allPairsAreTypedAndPersistTogether()throws Exception{
        for(String theme:new String[]{"system","light","dark"})for(String orientation:new String[]{"system","portrait","landscape"}){
            FactoryPresentation.State state=FactoryPresentation.parse(new JSONObject(pair(theme,orientation)));
            FactoryPresentation.save(screen,state);assertTrue(state.same(FactoryPresentation.read(screen)));
        }
    }
    @Test public void bootstrapRequiresExactInstalledIdentityAndCapabilityBeforeApplyingPreferences()throws Exception{
        FactoryPresentation.save(screen,FactoryPresentation.parse(new JSONObject(pair("dark","portrait"))));
        FactoryPresentation.Bootstrap accepted=FactoryPresentation.bootstrap(screen,host.configuration());
        assertTrue(accepted.enabled);assertEquals("dark",accepted.state.theme);assertEquals("portrait",accepted.state.orientation);
        assertEquals(Configuration.UI_MODE_NIGHT_YES,accepted.context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK);
        for(String invalid:new String[]{host.configuration().replace("[\"presentation\"]","[]"),host.configuration().replace(host.appId,"com.foreign.app"),"{}"}){
            FactoryPresentation.Bootstrap denied=FactoryPresentation.bootstrap(screen,invalid);assertFalse(denied.enabled);assertSame(screen,denied.context);assertSame(FactoryPresentation.DEFAULT,denied.state);
        }
        FactoryPresentation.save(screen,FactoryPresentation.DEFAULT);
        assertEquals("dark",accepted.state.theme); // Bootstrap remains immutable after later persistence.
    }
    @Test public void bootstrapConfigurationReadIsBoundedAndClosesItsInput()throws Exception{
        final boolean[] closed={false};InputStream oversized=new ByteArrayInputStream(new byte[8193]){@Override public void close()throws IOException{closed[0]=true;super.close();}};
        assertThrows(IOException.class,()->FactoryRuntime.readConfiguration(oversized));assertTrue(closed[0]);
        assertEquals(8192,FactoryRuntime.readConfiguration(new ByteArrayInputStream(new byte[8192])).length());
    }
    @Test public void defaultsAndIdempotentRequestDoNotRecreate()throws Exception{
        JSONObject before=request("presentation.get","{}").result();assertEquals("system",before.getString("theme"));assertEquals("system",before.getString("orientation"));assertFalse(before.getBoolean("orientationGuaranteed"));
        JSONObject result=request("presentation.set",pair("system","system")).result();assertFalse(result.getBoolean("recreationRequested"));assertFalse(result.has("preferencesCommitted"));
        assertFalse(request("presentation.reset","{}").result().getBoolean("recreationRequested"));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,screen.recreations);
    }
    @Test public void writeAcknowledgesOnlyPreferencesAndQueuesOneRecreation()throws Exception{
        JSONObject result=request("presentation.set",pair("dark","landscape")).result();assertTrue(result.getBoolean("preferencesCommitted"));assertTrue(result.getBoolean("recreationRequested"));assertFalse(result.getBoolean("orientationGuaranteed"));assertEquals(0,screen.recreations);
        assertEquals("BUSY",request("presentation.reset","{}").error());
        assertEquals("dark",request("presentation.get","{}").result().getString("theme"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,screen.recreations);
        assertEquals("BUSY",request("presentation.reset","{}").error()); // Old page remains retiring until pause.
        assertTrue(FactoryPresentation.read(screen).same(FactoryPresentation.parse(new JSONObject(pair("dark","landscape")))));
    }
    @Test public void pauseCancelsQueuedRecreationButKeepsSavedPairAndAllowsExplicitRetry()throws Exception{
        request("presentation.set",pair("dark","portrait")).result();runtime.onPause();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,screen.recreations);
        assertEquals("dark",FactoryPresentation.read(screen).theme);runtime.onResume();
        JSONObject retry=request("presentation.set",pair("dark","portrait")).result();assertTrue(retry.getBoolean("recreationRequested"));assertFalse(retry.has("preferencesCommitted"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,screen.recreations);
    }
    @Test public void navigationCancelsQueuedCallbackAndDoesNotPretendSettingsApplied()throws Exception{
        request("presentation.set",pair("light","landscape")).result();cancelPage();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,screen.recreations);
        assertTrue(request("presentation.set",pair("light","landscape")).result().getBoolean("recreationRequested"));
    }
    @Test public void closeRevocationAndFocusLossBlockRecreation()throws Exception{
        request("presentation.set",pair("dark","landscape")).result();screen.focus=false;Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,screen.recreations);
        screen.focus=true;request("presentation.set",pair("dark","landscape")).result();host.active=false;Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,screen.recreations);
        host.active=true;request("presentation.set",pair("dark","landscape")).result();runtime.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,screen.recreations);
    }
    @Test public void previewCannotReadOrWriteInstalledPreferencesOrWindow()throws Exception{
        host.preview=true;int before=screen.getRequestedOrientation();
        for(String method:new String[]{"get","set","reset"})assertEquals("UNAVAILABLE",request("presentation."+method,method.equals("set")?pair("dark","portrait"):"{}").error());
        assertTrue(FactoryPresentation.preferences(screen).getAll().isEmpty());assertEquals(before,screen.getRequestedOrientation());assertEquals(0,screen.recreations);
    }
    @Test public void missingCapabilityDeniesEveryMethod()throws Exception{
        field("config").set(runtime,FactoryConfig.parse(host.configuration().replace("[\"presentation\"]","[]"),screen.getPackageName()));
        for(String method:new String[]{"get","set","reset"})assertEquals("CAPABILITY_DENIED",request("presentation."+method,method.equals("set")?pair("dark","portrait"):"{}").error());
        assertTrue(FactoryPresentation.preferences(screen).getAll().isEmpty());
    }
    @Test public void nativeUiOwnerAndBackgroundDenyMutations()throws Exception{
        field("uiOwner").set(runtime,newReply());assertEquals("BUSY",request("presentation.set",pair("dark","portrait")).error());field("uiOwner").set(runtime,null);
        runtime.onPause();assertEquals("UNAVAILABLE",request("presentation.set",pair("dark","portrait")).error());assertTrue(FactoryPresentation.preferences(screen).getAll().isEmpty());
    }
    Object newReply()throws Exception{
        Class<?> type=Class.forName(FactoryRuntime.class.getName()+"$Reply");Constructor<?> c=type.getDeclaredConstructors()[0];c.setAccessible(true);return c.newInstance(runtime,"other","clipboard.write",new Response(),0);
    }
    @Test public void failedCommitIsExplicitAndDoesNotRecreateEvenIfMemoryChanged()throws Exception{
        screen.failCommit=true;assertEquals("PRESENTATION_STORAGE_ERROR",request("presentation.set",pair("dark","portrait")).error());Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,screen.recreations);
        assertEquals("dark",request("presentation.get","{}").result().getString("theme"));assertNull(field("uiOwner").get(runtime));assertFalse((boolean)field("presentationScheduled").get(runtime));
    }
    @Test public void malformedOrWrongTypedPreferencesFallBackTogether()throws Exception{
        FactoryPresentation.preferences(screen).edit().putString("theme","unbounded").putString("orientation","portrait").commit();assertTrue(FactoryPresentation.DEFAULT.same(FactoryPresentation.read(screen)));
        FactoryPresentation.preferences(screen).edit().putInt("theme",4).commit();assertTrue(FactoryPresentation.DEFAULT.same(FactoryPresentation.read(screen)));
    }
    @Test public void localNightOverridePreservesAccessibilityAndOtherConfiguration()throws Exception{
        Configuration before=new Configuration(screen.getResources().getConfiguration());before.fontScale=1.7f;before.densityDpi=320;before.uiMode=Configuration.UI_MODE_TYPE_CAR|Configuration.UI_MODE_NIGHT_NO;before.setLocale(java.util.Locale.forLanguageTag("es-MX"));
        Context base=screen.createConfigurationContext(before);
        for(String theme:new String[]{"light","dark"}){
            Context wrapped=FactoryPresentation.themedContext(base,FactoryPresentation.parse(new JSONObject(pair(theme,"system"))));Configuration after=wrapped.getResources().getConfiguration();
            assertEquals(1.7f,after.fontScale,0);assertEquals(320,after.densityDpi);assertEquals(before.getLocales(),after.getLocales());assertEquals(Configuration.UI_MODE_TYPE_CAR,after.uiMode&Configuration.UI_MODE_TYPE_MASK);
            assertEquals(theme.equals("dark")?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO,after.uiMode&Configuration.UI_MODE_NIGHT_MASK);
            assertEquals(Configuration.UI_MODE_NIGHT_NO,base.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK);
        }
        assertSame(base,FactoryPresentation.themedContext(base,FactoryPresentation.DEFAULT));
    }
    @Test public void boundedOrientationRequestsDoNotClaimObservedRotation()throws Exception{
        for(String orientation:new String[]{"portrait","landscape","system"}){
            FactoryPresentation.State requested=FactoryPresentation.parse(new JSONObject(pair("system",orientation)));FactoryPresentation.applyOrientation(screen,requested);
            assertEquals(orientation.equals("portrait")?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:orientation.equals("landscape")?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,screen.getRequestedOrientation());
            assertFalse(FactoryPresentation.result(screen,requested,false).getBoolean("orientationGuaranteed"));
        }
    }
    @Test public void rejectedOrientationKeepsActivityAndBridgeUsableWithoutChangingPreferences()throws Exception{
        FactoryPresentation.State requested=FactoryPresentation.parse(new JSONObject(pair("dark","landscape")));
        FactoryPresentation.save(screen,requested);screen.rejectOrientation=true;
        assertFalse(FactoryPresentation.applyOrientation(screen,requested));assertFalse(screen.isFinishing());assertFalse(screen.isDestroyed());
        assertTrue(requested.same(FactoryPresentation.read(screen)));
        assertEquals("dark",request("presentation.get","{}").result().getString("theme"));
        assertFalse(request("presentation.get","{}").result().getBoolean("orientationGuaranteed"));
    }
    @Test public void successfulRecreatedSnapshotMakesIgnoredOrientationIdempotent()throws Exception{
        FactoryPresentation.State state=FactoryPresentation.parse(new JSONObject(pair("dark","landscape")));FactoryPresentation.save(screen,state);screen.applied=state;
        assertFalse(request("presentation.set",pair("dark","landscape")).result().getBoolean("recreationRequested"));
        JSONObject reset=request("presentation.reset","{}").result();assertTrue(reset.getBoolean("preferencesCommitted"));assertEquals("system",reset.getString("orientation"));
    }
}
