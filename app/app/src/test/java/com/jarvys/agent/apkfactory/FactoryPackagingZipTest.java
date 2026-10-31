package com.jarvys.agent.apkfactory;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Synthetic ZIP-envelope tests; these do not build, sign or install an APK. */
public class FactoryPackagingZipTest {
    private Object call(String method,Class<?>[] types,Object...args) throws Exception {
        Method target=TemplateApk.class.getDeclaredMethod(method,types);target.setAccessible(true);
        try{return target.invoke(null,args);}catch(InvocationTargetException error){throw (Exception)error.getCause();}
    }
    private int planned(Map<String,byte[]> files) throws Exception {return (Integer)call("zipOutputBytes",new Class<?>[]{Map.class},files);}
    private byte[] zip(Map<String,byte[]> files) throws Exception {return (byte[])call("writeZip",new Class<?>[]{Map.class},files);}
    @Test public void increasedSingleAndTotalSourceSizesRoundTripUnderUnchangedArchiveEnvelope() throws Exception {
        Map<String,byte[]> files=new TreeMap<>();files.put("assets/www/index.html",new byte[9*1024*1024]);
        byte[] zip=zip(files); assertEquals(planned(files),zip.length);assertEquals(9*1024*1024,TemplateApk.expandedBytes(zip));
        Map<?,?> read=(Map<?,?>)call("readZip",new Class<?>[]{byte[].class,boolean.class},zip,true);
        assertArrayEquals(files.get("assets/www/index.html"),(byte[])read.get("assets/www/index.html"));
    }
    @Test public void fixedTemplateAndMetadataEntriesCountAgainst512BeforeWriting() throws Exception {
        Map<String,byte[]> files=new TreeMap<>(); for(int i=0;i<511;i++) files.put("assets/www/file"+i,new byte[0]);
        files.put("classes.dex",new byte[0]);assertTrue(planned(files)>0);
        files.put("assets/factory-provenance.json",new byte[0]);
        assertThrows(IOException.class,()->planned(files));assertThrows(IOException.class,()->zip(files));
    }
    @Test public void technical16MiBSingleAnd32MiBComposedArchiveStayFinite() throws Exception {
        Map<String,byte[]> files=new TreeMap<>();files.put("assets/www/index.html",new byte[16*1024*1024]);
        assertTrue(planned(files)>16*1024*1024);
        files.put("assets/www/second",files.get("assets/www/index.html"));
        assertThrows(IOException.class,()->planned(files));
        files.clear();files.put("assets/www/index.html",new byte[TemplateApk.MAX_ENTRY_BYTES+1]);
        assertThrows(IOException.class,()->planned(files));
    }
    @Test public void expandedBudgetRejectsCompressedZipBombBeforeDecompression() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)) {
            byte[] zero=new byte[8192];
            for(int i=0;i<3;i++){zip.putNextEntry(new java.util.zip.ZipEntry("assets/www/"+i));for(int j=0;j<2048;j++)zip.write(zero);zip.closeEntry();}
        }
        assertThrows(IOException.class,()->TemplateApk.expandedBytes(bytes.toByteArray()));
        assertThrows(IOException.class,()->call("readZip",new Class<?>[]{byte[].class},bytes.toByteArray()));
    }
}
