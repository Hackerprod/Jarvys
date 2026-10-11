package com.jarvys.agent.apkfactory;
import com.jarvys.factory.contract.AdaptiveIconAuditTest;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Pure synthetic ARSC/AXML checks, with shortened ZIP paths and exact config matching. */
public class AdaptiveIconResourcesTest {
    private static final String[] PATHS={"res/a.png","res/b.png","res/c.png","res/d.png","res/e.xml"};
    private static final int ICON=0x7f020000, BG=0x7f010002, FG=0x7f010001;
    private static Object invoke(String method,Class<?>[] types,Object... args) throws Exception {
        Method m=TemplateApk.class.getDeclaredMethod(method,types);m.setAccessible(true);
        try { return m.invoke(null,args); } catch(InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
    private static Map<String,TemplateApk.ResourceBinding> audit(byte[] resources,Map<String,byte[]> files) throws Exception {
        invoke("verifyResourceTable",new Class[]{byte[].class},resources);
        Map<String,TemplateApk.ResourceBinding> bindings=new TreeMap<>();
        invoke("auditAdaptiveIconResources",new Class[]{Map.class,byte[].class,int.class,Map.class,boolean.class},files,resources,ICON,bindings,true);
        return bindings;
    }
    private static Map<String,byte[]> files() throws Exception {
        Map<String,byte[]> result=new TreeMap<>();
        result.put(PATHS[0],png(48));result.put(PATHS[1],png(192));result.put(PATHS[2],png(192));result.put(PATHS[3],png(48));
        result.put(PATHS[4],AdaptiveIconAuditTest.fixture(BG,FG));return result;
    }
    @Test public void compiledSymbolsRebindEvenWhenAaptShortensEveryPath() throws Exception {
        Map<String,TemplateApk.ResourceBinding> result=audit(table(65534,26,false,PATHS),files());
        assertEquals(4,result.size()); assertEquals(ICON,result.get("icon").id);assertEquals(PATHS[3],result.get("icon").path);
        assertEquals(PATHS[4],result.get("iconApi26").path);assertEquals(FG,result.get("iconForeground").id);assertEquals(BG,result.get("iconBackground").id);
    }
    @Test public void sdkDensityAndUnexpectedVariantsFailClosed() throws Exception {
        for(int[] configuration:new int[][]{{0,26},{65535,26},{65534,25},{65534,27}})
            assertThrows(IOException.class,()->audit(table(configuration[0],configuration[1],false,PATHS),files()));
        assertThrows(IOException.class,()->audit(table(65534,26,true,PATHS),files()));
    }
    @Test public void missingRedirectedOrAliasedResourcesFailClosed() throws Exception {
        for(String path:PATHS) { Map<String,byte[]> value=files();value.remove(path);assertThrows(IOException.class,()->audit(table(65534,26,false,PATHS),value)); }
        String[] aliases=PATHS.clone();aliases[3]=PATHS[0];assertThrows(IOException.class,()->audit(table(65534,26,false,aliases),files()));
        Map<String,byte[]> redirect=files();redirect.put(PATHS[4],AdaptiveIconAuditTest.fixture(FG,BG));assertThrows(IOException.class,()->audit(table(65534,26,false,PATHS),redirect));
    }
    @Test public void layerDimensionsAndLegacyFallbackAreAudited() throws Exception {
        for(String layer:new String[]{PATHS[1],PATHS[2]}) { Map<String,byte[]> value=files();value.put(layer,png(48));assertThrows(IOException.class,()->audit(table(65534,26,false,PATHS),value)); }
        Map<String,byte[]> value=files();value.put(PATHS[3],png(49));assertThrows(IOException.class,()->audit(table(65534,26,false,PATHS),value));
    }
    @Test public void opaqueSolidBackgroundIsVerifiedFromPixels() throws Exception {
        for(byte[] background:new byte[][]{png(192,0,false),png(192,128,false),png(192,255,true)}) {
            Map<String,byte[]> value=files();value.put(PATHS[2],background);assertThrows(IOException.class,()->audit(table(65534,26,false,PATHS),value));
        }
        Map<String,byte[]> foreground=files();foreground.put(PATHS[1],png(192,0,false));audit(table(65534,26,false,PATHS),foreground);
    }
    @Test public void solidColorAuditSupportsAllFivePngScanlineFilters() throws Exception {
        for(int channels:new int[]{3,4})for(int filter=0;filter<=4;filter++) {
            int width=3,height=3,row=width*channels;byte[] encoded=new byte[height*(row+1)],pixels=new byte[row];
            for(int x=0;x<width;x++){pixels[x*channels]=16;pixels[x*channels+1]=32;pixels[x*channels+2]=48;if(channels==4)pixels[x*channels+3]=(byte)255;}
            for(int y=0;y<height;y++) {
                encoded[y*(row+1)]=(byte)filter;
                for(int x=0;x<row;x++) {
                    int a=x>=channels?pixels[x-channels]&255:0,b=y>0?pixels[x]&255:0,c=y>0&&x>=channels?pixels[x-channels]&255:0,p=0;
                    if(filter==1)p=a;else if(filter==2)p=b;else if(filter==3)p=(a+b)/2;
                    else if(filter==4){int v=a+b-c,pa=Math.abs(v-a),pb=Math.abs(v-b),pc=Math.abs(v-c);p=pa<=pb&&pa<=pc?a:pb<=pc?b:c;}
                    encoded[y*(row+1)+1+x]=(byte)((pixels[x]&255)-p);
                }
            }
            invoke("verifySolidOpaque",new Class[]{byte[].class,int.class,int.class,int.class},encoded,width,height,channels);
        }
    }
    @Test public void legacyResolverStillRequiresOneUnqualifiedNodpiPng() throws Exception {
        assertEquals(PATHS[0],invoke("resolveIconPath",new Class[]{byte[].class,int.class},table(65534,26,false,PATHS),0x7f010000));
        assertThrows(IOException.class,()->invoke("resolveIconPath",new Class[]{byte[].class,int.class},table(65534,26,false,PATHS),ICON));
    }
    private static byte[] table(int apiDensity,int api,boolean extra,String[] paths) throws IOException {
        byte[] values=pool(paths),types=pool("drawable","mipmap"),keys=pool("factory_icon","factory_icon_adaptive_foreground","factory_icon_adaptive_background","factory_adaptive_icon");
        ByteArrayOutputStream body=new ByteArrayOutputStream();body.write(types);body.write(keys);
        body.write(spec(1,3));body.write(type(1,65535,0,new int[]{0,1,2},new int[]{0,1,2}));
        body.write(spec(2,1));body.write(type(2,65535,0,new int[]{3},new int[]{3}));body.write(type(2,apiDensity,api,new int[]{3},new int[]{4}));
        if(extra)body.write(type(2,65534,28,new int[]{3},new int[]{4}));
        byte[] p=new byte[288];set16(p,0,0x200);set16(p,2,288);set32(p,4,288+body.size());set32(p,8,0x7f);
        byte[] name="org.example.icon".getBytes(StandardCharsets.UTF_16LE);System.arraycopy(name,0,p,12,name.length);
        set32(p,268,288);set32(p,276,288+types.length);
        ByteArrayOutputStream out=new ByteArrayOutputStream();header(out,2,12,12+values.length+p.length+body.size());i32(out,1);out.write(values);out.write(p);out.write(body.toByteArray());return out.toByteArray();
    }
    private static byte[] spec(int id,int count) { byte[] b=new byte[16+4*count];set16(b,0,0x202);set16(b,2,16);set32(b,4,b.length);b[8]=(byte)id;set32(b,12,count);return b; }
    private static byte[] type(int id,int density,int api,int[] keys,int[] paths) {
        int start=48+4*keys.length;byte[] b=new byte[start+16*keys.length];set16(b,0,0x201);set16(b,2,48);set32(b,4,b.length);b[8]=(byte)id;
        set32(b,12,keys.length);set32(b,16,start);set32(b,20,28);set16(b,34,density);set16(b,44,api);
        for(int i=0;i<keys.length;i++){set32(b,48+4*i,i*16);int e=start+i*16;set16(b,e,8);set32(b,e+4,keys[i]);set16(b,e+8,8);b[e+11]=3;set32(b,e+12,paths[i]);}return b;
    }
    private static byte[] pool(String... values) throws IOException {
        ByteArrayOutputStream data=new ByteArrayOutputStream(),offsets=new ByteArrayOutputStream();
        for(String text:values){i32(offsets,data.size());byte[] bytes=text.getBytes(StandardCharsets.UTF_8);data.write(text.length());data.write(bytes.length);data.write(bytes);data.write(0);}while(data.size()%4!=0)data.write(0);
        ByteArrayOutputStream out=new ByteArrayOutputStream();header(out,1,28,28+offsets.size()+data.size());i32(out,values.length);i32(out,0);i32(out,256);i32(out,28+offsets.size());i32(out,0);out.write(offsets.toByteArray());out.write(data.toByteArray());return out.toByteArray();
    }
    public static byte[] png(int size) throws IOException { return png(size,255,false); }
    private static byte[] png(int size,int alpha,boolean varied) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});ByteArrayOutputStream h=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(h);d.writeInt(size);d.writeInt(size);d.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",h.toByteArray());
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)){byte[] pixels=new byte[size*(size*4+1)];for(int y=0;y<size;y++)for(int x=0;x<size;x++)pixels[y*(size*4+1)+1+x*4+3]=(byte)alpha;if(varied)pixels[1]=1;z.write(pixels);}chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream out,String name,byte[] data)throws IOException{byte[] type=name.getBytes(StandardCharsets.US_ASCII);DataOutputStream d=new DataOutputStream(out);d.writeInt(data.length);d.write(type);d.write(data);CRC32 crc=new CRC32();crc.update(type);crc.update(data);d.writeInt((int)crc.getValue());}
    private static void header(ByteArrayOutputStream out,int kind,int length,int size){i16(out,kind);i16(out,length);i32(out,size);}
    private static void i16(ByteArrayOutputStream out,int value){out.write(value);out.write(value>>>8);}private static void i32(ByteArrayOutputStream out,int value){i16(out,value);i16(out,value>>>16);}
    private static void set16(byte[] bytes,int at,int value){bytes[at]=(byte)value;bytes[at+1]=(byte)(value>>>8);}private static void set32(byte[] bytes,int at,int value){set16(bytes,at,value);set16(bytes,at+2,value>>>16);}
}
