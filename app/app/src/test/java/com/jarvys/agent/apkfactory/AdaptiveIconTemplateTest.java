package com.jarvys.agent.apkfactory;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Final integrated-template test. Requires the normal bundled runtime build, not per-app compilation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AdaptiveIconTemplateTest {
    private byte[] template,legacy,layer;
    private Map<String,byte[]> assets;
    private TemplateApk.Spec spec(boolean adaptive) { return new TemplateApk.Spec("org.example.adaptive","Adaptive canvas",1,"1",Collections.emptyList(),null,null,null,adaptive); }
    @Before public void load() throws Exception {
        try(InputStream in=RuntimeEnvironment.getApplication().getAssets().open("apk_factory/template.apk")){template=read(in);}
        legacy=AdaptiveIconResourcesTest.png(48);layer=AdaptiveIconResourcesTest.png(192);
        assets=new TreeMap<>();assets.put("assets/www/index.html","<!doctype html><h1>Canvas</h1>".getBytes("UTF-8"));assets.put("assets/factory-app.json","{}".getBytes("UTF-8"));
    }
    private byte[] build() throws Exception { return TemplateApk.build(template,spec(true),legacy,assets,new TemplateApk.AdaptiveLayers(layer,layer)); }
    @Test public void genuineCompiledAdaptiveResourceAndPre26FallbackRebindWithoutChangingDex() throws Exception {
        byte[] result=build();TemplateApk.ManifestInfo info=TemplateApk.verify(result,spec(true));
        assertEquals("mipmap",info.resourceBindings.get("icon").type);assertEquals(info.iconResourceId,info.resourceBindings.get("iconApi26").id);
        Map<String,byte[]> files=unzip(result);assertArrayEquals(legacy,files.get(info.resourceBindings.get("icon").path));
        assertArrayEquals(layer,files.get(info.resourceBindings.get("iconForeground").path));assertArrayEquals(layer,files.get(info.resourceBindings.get("iconBackground").path));
        assertEquals(TemplateApk.inspect(template).dexSha256,info.dexSha256);assertEquals(info.resourceBindings.keySet(),TemplateApk.inspect(result).resourceBindings.keySet());
        TemplateApk.verifyAgainstPlan(result,info.plan);assertArrayEquals(result,build());
    }
    @Test public void callerMustApproveAdaptiveModeAndBothRasterLayers() throws Exception {
        byte[] result=build();assertThrows(IOException.class,()->TemplateApk.verify(result,spec(false)));
        assertThrows(IOException.class,()->TemplateApk.verifyExistingClosedV4(result,spec(true)));
        assertThrows(IOException.class,()->TemplateApk.build(template,spec(true),legacy,assets));
        assertThrows(IOException.class,()->TemplateApk.build(template,spec(false),legacy,assets,new TemplateApk.AdaptiveLayers(layer,layer)));
        assertThrows(IOException.class,()->TemplateApk.build(template,spec(true),legacy,assets,new TemplateApk.AdaptiveLayers(legacy,layer)));
    }
    @Test public void missingCompiledXmlOrDamagedLayersCannotPassVerification() throws Exception {
        byte[] result=build();TemplateApk.ManifestInfo info=TemplateApk.verify(result,spec(true));
        for(String role:Arrays.asList("iconApi26","iconForeground","iconBackground","icon")) {
            Map<String,byte[]> files=unzip(result);files.remove(info.resourceBindings.get(role).path);byte[] changed=zip(files);
            assertThrows(IOException.class,()->TemplateApk.verify(changed,spec(true)));
        }
        Map<String,byte[]> files=unzip(result);files.put(info.resourceBindings.get("iconApi26").path,"<adaptive-icon/>".getBytes("UTF-8"));
        byte[] changed=zip(files);assertThrows(IOException.class,()->TemplateApk.verify(changed,spec(true)));
    }
    @Test public void optingOutStillUsesOriginalDrawableAndChangesExactlyOneRaster() throws Exception {
        byte[] result=TemplateApk.build(template,spec(false),legacy,assets);TemplateApk.ManifestInfo info=TemplateApk.verify(result,spec(false));
        assertEquals("drawable",info.resourceBindings.get("icon").type);assertFalse(info.resourceBindings.containsKey("iconApi26"));
        Map<String,byte[]> original=unzip(template),after=unzip(result);int changed=0;
        for(String path:original.keySet())if(path.endsWith(".png")&&!Arrays.equals(original.get(path),after.get(path)))changed++;
        assertEquals(1,changed);assertThrows(IOException.class,()->TemplateApk.verify(result,spec(true)));
    }
    private static byte[] read(InputStream input) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=input.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}
    private static Map<String,byte[]> unzip(byte[] bytes)throws IOException{Map<String,byte[]> result=new TreeMap<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes))){for(ZipEntry e;(e=z.getNextEntry())!=null;)if(!e.isDirectory())result.put(e.getName(),read(z));}return result;}
    private static byte[] zip(Map<String,byte[]> files)throws Exception{Method m=TemplateApk.class.getDeclaredMethod("writeZip",Map.class);m.setAccessible(true);try{return(byte[])m.invoke(null,files);}catch(InvocationTargetException e){throw(Exception)e.getCause();}}
}
