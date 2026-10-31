package com.jarvys.factory.contract;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
/** Synthetic AXML only; the final integration suite also validates the AAPT-compiled template. */
public class AdaptiveIconAuditTest {
    private static final int BG=0x7f010002, FG=0x7f010001;
    private static ManifestNodes.Element node(String name,List<ManifestNodes.Element> children,ManifestPlan.Attribute... attrs) throws Exception {
        Constructor<ManifestNodes.Element> c=ManifestNodes.Element.class.getDeclaredConstructor(String.class,String.class,List.class,ManifestPlan.Attribute[].class);
        c.setAccessible(true); return c.newInstance(name,name,children,attrs);
    }
    private static ManifestNodes.Element layer(String name,int resource,int attrId,int type) throws Exception {
        return node(name,Collections.emptyList(),ManifestPlan.attribute("drawable",attrId,type,resource));
    }
    public static byte[] fixture(int bg,int fg) throws Exception {
        return ManifestXml.encodeTree(node("adaptive-icon",Arrays.asList(layer("background",bg,0x01010199,1),layer("foreground",fg,0x01010199,1))));
    }
    @Test public void exactTypedCompiledLayersPass() throws Exception { AdaptiveIconAudit.verify(fixture(BG,FG),BG,FG); }
    @Test public void symbolsAreBoundToActualOwnPackageIds() throws Exception {
        assertThrows(IOException.class,()->AdaptiveIconAudit.verify(fixture(FG,BG),BG,FG));
        assertThrows(IOException.class,()->AdaptiveIconAudit.verify(fixture(0x01010001,FG),BG,FG));
        assertThrows(IOException.class,()->AdaptiveIconAudit.verify(fixture(BG,FG),BG,BG));
    }
    @Test public void extraNodesAndAttributeAliasesAreRejected() throws Exception {
        byte[] extra=ManifestXml.encodeTree(node("adaptive-icon",Arrays.asList(layer("background",BG,0x01010199,1),layer("foreground",FG,0x01010199,1),layer("monochrome",FG,0x01010199,1))));
        assertThrows(IOException.class,()->AdaptiveIconAudit.verify(extra,BG,FG));
        for(int[] type:new int[][]{{0x01010002,1},{0x01010199,16}}) {
            byte[] xml=ManifestXml.encodeTree(node("adaptive-icon",Arrays.asList(layer("background",BG,type[0],type[1]),layer("foreground",FG,0x01010199,1))));
            assertThrows(IOException.class,()->AdaptiveIconAudit.verify(xml,BG,FG));
        }
    }
    @Test public void textXmlAndIncompleteBinaryFailClosed() throws Exception {
        assertThrows(IOException.class,()->AdaptiveIconAudit.verify("<adaptive-icon/>".getBytes("UTF-8"),BG,FG));
        byte[] xml=fixture(BG,FG); assertThrows(IOException.class,()->AdaptiveIconAudit.verify(Arrays.copyOf(xml,xml.length-4),BG,FG));
    }
}
