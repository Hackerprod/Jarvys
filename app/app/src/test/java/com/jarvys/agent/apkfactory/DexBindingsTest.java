package com.jarvys.agent.apkfactory;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.Adler32;
import org.junit.Test;

/** Synthetic metadata fixtures exercise the bounded audit, not Android bytecode verification. */
public class DexBindingsTest {
    private static final String ACTIVITY="Lcom/jarvys/factory/runtime/FactoryActivity;";
    private static final String FACTORY="Landroidx/core/app/CoreComponentFactory;";
    private static final String ACTIVITY_NAME="com.jarvys.factory.runtime.FactoryActivity";

    @Test public void returnsImmutableDefinitionEvidence() throws Exception {
        Map<String,String> result=DexBindings.verify(files(fixture(true,true)));
        assertEquals(2,result.size()); assertEquals("classes.dex",result.get(ACTIVITY_NAME));
        try { result.put("other","classes.dex"); fail("Mutable evidence"); }
        catch(UnsupportedOperationException expected) { }
    }
    @Test public void supportsRequiredClassesInDifferentDexFiles() throws Exception {
        Map<String,byte[]> input=files(fixture(true,false)); input.put("classes2.dex",fixture(false,true));
        Map<String,String> result=DexBindings.verify(input);
        assertEquals("classes.dex",result.get(ACTIVITY_NAME));
        assertEquals("classes2.dex",result.get("androidx.core.app.CoreComponentFactory"));
    }
    @Test public void descriptorReferenceIsNotClassDefinition() throws Exception { reject(files(fixture(false,true)),"missing"); }
    @Test public void rejectsDuplicateDefinitionsAcrossDex() throws Exception {
        byte[] b=fixture(true,true); Map<String,byte[]> input=files(b); input.put("classes2.dex",b.clone()); reject(input,"Duplicate");
    }
    @Test public void rejectsDuplicateUnrelatedDefinitionsAcrossDex() throws Exception {
        Map<String,byte[]> input=files(fixture(true,true));
        input.put("classes2.dex",secondaryFixture()); input.put("classes3.dex",secondaryFixture());
        reject(input,"Duplicate");
    }
    @Test public void permitsIndependentSecondaryDefinition() throws Exception {
        Map<String,byte[]> input=files(fixture(true,true)); input.put("classes2.dex",secondaryFixture());
        assertEquals(2,DexBindings.verify(input).size());
    }
    @Test public void rejectsDexGapsAndNoncanonicalNames() throws Exception {
        Map<String,byte[]> input=files(fixture(true,true)); input.put("classes3.dex",fixture(false,true)); reject(input,"canonical");
        input.clear(); input.put("classes1.dex",fixture(true,true)); reject(input,"canonical");
    }
    @Test public void rejectsMissingAndEmptyDex() throws Exception {
        reject(Collections.<String,byte[]>emptyMap(),"count"); reject(files(new byte[0]),"size");
    }
    @Test public void rejectsAbstractInterfaceAndNonpublicComponents() throws Exception {
        for(int flags:new int[]{0,0x401,0x201,0x2001}) {
            byte[] b=fixture(true,true); put(b,get(b,100)+4,flags); resign(b); reject(files(b),"concrete");
        }
    }
    @Test public void rejectsWrongSuperclass() throws Exception {
        byte[] b=fixture(true,true); int cls=get(b,100); put(b,cls+8,get(b,cls)); resign(b); reject(files(b),"superclass");
    }
    @Test public void rejectsAbsentConstructorDefinition() throws Exception {
        byte[] b=fixture(true,true); put(b,get(b,100)+24,0); resign(b); reject(files(b),"constructor");
    }
    @Test public void rejectsPrivateConstructor() throws Exception {
        byte[] b=fixture(true,true); int cd=get(b,get(b,100)+24); b[cd+5]=(byte)0x80; resign(b); reject(files(b),"constructor");
    }
    @Test public void rejectsConstructorWithoutCode() throws Exception {
        byte[] b=fixture(true,true); int cd=get(b,get(b,100)+24); b[cd+8]=0; resign(b); reject(files(b),"constructor");
    }
    @Test public void rejectsBadSignatureAndChecksum() throws Exception {
        byte[] b=fixture(true,true); b[b.length-1]^=1; reject(files(b),"signature");
        b=fixture(true,true); b[8]^=1; reject(files(b),"checksum");
    }
    @Test public void rejectsHeaderAndTableBounds() throws Exception {
        for(int field:new int[]{32,36,40,104,108}) {
            byte[] b=fixture(true,true); put(b,field,0); resign(b); reject(files(b),"DEX");
        }
        byte[] b=fixture(true,true); put(b,56,Integer.MAX_VALUE); resign(b); reject(files(b),"count");
        b=fixture(true,true); put(b,60,get(b,108)); resign(b); reject(files(b),"span");
    }
    @Test public void rejectsInvalidTypeAndMethodIndices() throws Exception {
        byte[] b=fixture(true,true); put(b,get(b,68),get(b,56)); resign(b); reject(files(b),"index");
        b=fixture(true,true); int m=get(b,92); b[m+2]=(byte)0xff; b[m+3]=(byte)0xff; resign(b); reject(files(b),"index");
    }
    @Test public void rejectsMalformedStringAndOversizedUleb() throws Exception {
        byte[] b=fixture(true,true); int s=get(b,get(b,60)); b[s+1]=0; resign(b); reject(files(b),"string");
        b=fixture(true,true); s=get(b,get(b,60)); Arrays.fill(b,s,s+5,(byte)0xff); resign(b); reject(files(b),"ULEB");
        b=fixture(true,true); s=get(b,get(b,60)); b[s+1]=(byte)0xc1; b[s+2]=(byte)0x81; resign(b); reject(files(b),"overlong");
    }
    @Test public void rejectsMapTableMismatch() throws Exception {
        byte[] b=fixture(true,true); int map=get(b,52); put(b,map+4+12+4,1); resign(b); reject(files(b),"mismatch");
    }
    @Test public void rejectsDuplicateDefinitionsWithinDex() throws Exception {
        byte[] b=fixture(true,true); int cls=get(b,100); put(b,cls+32,get(b,cls)); resign(b); reject(files(b),"Duplicate");
    }
    @Test public void rejectsTruncationWithoutUncheckedExceptions() throws Exception {
        byte[] b=fixture(true,true);
        for(int size:new int[]{0,7,111,112,b.length-1}) reject(files(Arrays.copyOf(b,size)),"DEX");
    }

    /** Independent secondary class for TemplateApk multi-DEX preservation tests. */
    static byte[] secondaryFixture() throws Exception {
        byte[] b=fixture(true,false);
        byte[] from="FactoryActivity".getBytes(StandardCharsets.UTF_8);
        byte[] to="FactoryAuxClass".getBytes(StandardCharsets.UTF_8);
        for(int p=0;p<=b.length-from.length;p++) {
            boolean match=true;
            for(int i=0;i<from.length;i++) if(b[p+i]!=from[i]) { match=false; break; }
            if(match) { System.arraycopy(to,0,b,p,to.length); break; }
        }
        resign(b); return b;
    }

    private static Map<String,byte[]> files(byte[] b) { Map<String,byte[]> m=new LinkedHashMap<>(); m.put("classes.dex",b); return m; }
    private static void reject(Map<String,byte[]> input,String message) throws Exception {
        try { DexBindings.verify(input); fail("Expected rejection: "+message); }
        catch(IOException expected) { assertTrue(expected.getMessage(),expected.getMessage().contains(message)); }
    }
    private static byte[] fixture(boolean activity,boolean factory) throws Exception {
        List<String> types=new ArrayList<>(new TreeSet<>(Arrays.asList(ACTIVITY,FACTORY,"Landroid/app/Activity;","Landroid/app/AppComponentFactory;","V")));
        TreeSet<String> pool=new TreeSet<>(types); pool.add("<init>");
        List<String> strings=new ArrayList<>(pool), defined=new ArrayList<>();
        for(String t:types) if((t.equals(ACTIVITY) && activity) || (t.equals(FACTORY) && factory)) defined.add(t);
        int stringOff=112,typeOff=stringOff+strings.size()*4,protoOff=typeOff+types.size()*4;
        int methodOff=protoOff+12,classOff=methodOff+defined.size()*8,data=classOff+defined.size()*32;
        ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write(new byte[data]);
        int[] stringOffsets=new int[strings.size()];
        for(int i=0;i<strings.size();i++) { stringOffsets[i]=out.size(); uleb(out,strings.get(i).length()); out.write(strings.get(i).getBytes(StandardCharsets.UTF_8)); out.write(0); }
        align(out); int codeStart=out.size(); int[] codes=new int[defined.size()];
        for(int i=0;i<codes.length;i++) { align(out); codes[i]=out.size(); byte[] code=new byte[18]; code[0]=1; code[2]=1; put(code,12,1); code[16]=0x0e; out.write(code); }
        int classDataStart=out.size(); int[] classData=new int[defined.size()];
        for(int i=0;i<classData.length;i++) {
            classData[i]=out.size(); out.write(new byte[]{0,0,1,0}); uleb(out,i); uleb(out,0x10001); uleb(out,codes[i]);
        }
        align(out); int mapOff=out.size();
        int[][] map={{0,1,0},{1,strings.size(),stringOff},{2,types.size(),typeOff},{3,1,protoOff},
                {5,defined.size(),methodOff},{6,defined.size(),classOff},{0x2002,strings.size(),data},
                {0x2001,defined.size(),codeStart},{0x2000,defined.size(),classDataStart},{0x1000,1,mapOff}};
        byte[] maps=new byte[4+map.length*12]; put(maps,0,map.length);
        for(int i=0;i<map.length;i++) { int p=4+i*12; maps[p]=(byte)map[i][0]; maps[p+1]=(byte)(map[i][0]>>8); put(maps,p+4,map[i][1]); put(maps,p+8,map[i][2]); } out.write(maps);
        byte[] b=out.toByteArray(); System.arraycopy(new byte[]{'d','e','x','\n','0','3','5',0},0,b,0,8);
        put(b,32,b.length); put(b,36,112); put(b,40,0x12345678); put(b,52,mapOff);
        put(b,56,strings.size()); put(b,60,stringOff); put(b,64,types.size()); put(b,68,typeOff);
        put(b,72,1); put(b,76,protoOff); put(b,88,defined.size()); put(b,92,methodOff);
        put(b,96,defined.size()); put(b,100,classOff); put(b,104,b.length-data); put(b,108,data);
        for(int i=0;i<strings.size();i++) put(b,stringOff+i*4,stringOffsets[i]);
        for(int i=0;i<types.size();i++) put(b,typeOff+i*4,strings.indexOf(types.get(i)));
        put(b,protoOff,strings.indexOf("V")); put(b,protoOff+4,types.indexOf("V"));
        for(int i=0;i<defined.size();i++) {
            String desc=defined.get(i); int m=methodOff+i*8,c=classOff+i*32;
            b[m]=(byte)types.indexOf(desc); put(b,m+4,strings.indexOf("<init>"));
            put(b,c,types.indexOf(desc)); put(b,c+4,1);
            put(b,c+8,types.indexOf(desc.equals(ACTIVITY)?"Landroid/app/Activity;":"Landroid/app/AppComponentFactory;"));
            put(b,c+16,-1); put(b,c+24,classData[i]);
        }
        resign(b); return b;
    }
    private static void align(ByteArrayOutputStream out) { while((out.size()&3)!=0) out.write(0); }
    private static void uleb(ByteArrayOutputStream out,int value) { do { int next=value&127; value>>>=7; out.write(next|(value==0?0:128)); } while(value!=0); }
    private static int get(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24); }
    private static void put(byte[] b,int p,int value) { for(int i=0;i<4;i++) b[p+i]=(byte)(value>>>(i*8)); }
    private static void resign(byte[] b) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-1"); sha.update(b,32,b.length-32); System.arraycopy(sha.digest(),0,b,12,20);
        Adler32 adler=new Adler32(); adler.update(b,12,b.length-12); put(b,8,(int)adler.getValue());
    }
}
