package com.jarvys.factory.contract;

import com.jarvys.agent.apkfactory.TemplateApk;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic compiled XML tests. These do not claim AAPT or launcher/device validation. */
public class WidgetCompiledResourceAuditTest {
    private static final int CHILDREN=0x7f030001, CONTENT=0x7f030002, EMPTY=0x7f040001;
    private static ManifestPlan.Attribute a(String name,int id,int type,Object value) { return ManifestPlan.attribute(name,id,type,value); }
    private static ManifestPlan.Attribute value(String name,int type,Object value) throws Exception {
        Method id=TemplateApk.class.getDeclaredMethod("widgetAttributeId",String.class);id.setAccessible(true);
        return a(name,(Integer)id.invoke(null,name),type,value);
    }
    private static void integer(List<ManifestPlan.Attribute> attrs,String name,int value) throws Exception { attrs.add(value(name,16,value)); }
    private static void dimension(List<ManifestPlan.Attribute> attrs,String name,int value,int unit) throws Exception { attrs.add(value(name,5,(value<<8)|unit)); }
    private static byte[] xml(String name,List<ManifestPlan.Attribute> attrs) throws Exception {
        Constructor<ManifestNodes.Element> constructor=ManifestNodes.Element.class.getDeclaredConstructor(String.class,String.class,List.class,ManifestPlan.Attribute[].class);
        constructor.setAccessible(true);
        ManifestNodes.Element root=constructor.newInstance(name,name,Collections.emptyList(),attrs.toArray(new ManifestPlan.Attribute[0]));
        return ManifestXml.encodeTree(root);
    }
    private static void verify(byte[] bytes,String kind) throws Exception {
        Method audit=TemplateApk.class.getDeclaredMethod("auditWidgetXml",byte[].class,String.class,int.class,int.class,int.class);audit.setAccessible(true);
        try { audit.invoke(null,bytes,kind,CHILDREN,CONTENT,EMPTY); }
        catch(InvocationTargetException error) { throw (Exception)error.getCause(); }
    }
    private List<ManifestPlan.Attribute> info() throws Exception {
        List<ManifestPlan.Attribute> attrs=new ArrayList<>();dimension(attrs,"minWidth",180,1);dimension(attrs,"minHeight",80,1);dimension(attrs,"minResizeWidth",110,1);dimension(attrs,"minResizeHeight",40,1);
        attrs.add(value("initialLayout",1,EMPTY));attrs.add(value("previewLayout",1,EMPTY));integer(attrs,"resizeMode",3);integer(attrs,"widgetCategory",1);integer(attrs,"updatePeriodMillis",0);return attrs;
    }
    private List<ManifestPlan.Attribute> layout(String kind) throws Exception {
        List<ManifestPlan.Attribute> attrs=new ArrayList<>();boolean container=java.util.Arrays.asList("root","row","column").contains(kind);
        if(!kind.equals("empty"))attrs.add(value("id",1,container?CHILDREN:CONTENT));
        if(kind.equals("image")){dimension(attrs,"layout_width",128,1);dimension(attrs,"layout_height",96,1);dimension(attrs,"padding",4,1);integer(attrs,"scaleType",3);}
        else if(kind.equals("progress")){attrs.add(value("style",2,16842872));dimension(attrs,"layout_width",120,1);dimension(attrs,"layout_height",24,1);integer(attrs,"max",100);integer(attrs,"progress",0);attrs.add(value("indeterminate",18,0));}
        else {
            integer(attrs,"layout_width",container||kind.equals("empty")?-1:-2);integer(attrs,"layout_height",kind.equals("root")?-1:-2);
            if(container){integer(attrs,"orientation",kind.equals("row")?0:1);if(kind.equals("root")){dimension(attrs,"padding",12,1);attrs.add(value("background",28,0xfff4f6fa));}}
            else {
                attrs.add(value("textColor",28,0xff162030));
                if(kind.equals("button")){dimension(attrs,"minHeight",48,1);attrs.add(value("textAllCaps",18,0));integer(attrs,"maxLines",2);integer(attrs,"ellipsize",3);}
                else {dimension(attrs,"textSize",16,2);dimension(attrs,"padding",kind.equals("empty")?12:4,1);if(kind.equals("empty"))attrs.add(value("text",3,"Open the app to configure this widget"));else{integer(attrs,"maxLines",6);integer(attrs,"ellipsize",3);}}
            }
        }
        return attrs;
    }
    @Test public void allEightLayoutsAndMetadataPassBoundedBinaryAudit() throws Exception {
        verify(xml("appwidget-provider",info()),"info");
        for(String kind:new String[]{"root","row","column","text","image","progress","button","empty"}) {
            String type=java.util.Arrays.asList("root","row","column").contains(kind)?"LinearLayout":kind.equals("image")?"ImageView":kind.equals("progress")?"ProgressBar":kind.equals("button")?"Button":"TextView";
            verify(xml(type,layout(kind)),kind);
        }
    }
    @Test public void widgetMetadataCannotRedirectResourcesOrEnablePeriodicUpdates() throws Exception {
        for(String field:new String[]{"initialLayout","previewLayout","resizeMode","widgetCategory","updatePeriodMillis"}) {
            List<ManifestPlan.Attribute> attrs=info();attrs.removeIf(a->a.name.equals(field));attrs.add(value(field,field.endsWith("Layout")?1:16,field.endsWith("Layout")?0x01040001:42));
            byte[] bytes=xml("appwidget-provider",attrs);assertThrows(IOException.class,()->verify(bytes,"info"));
        }
    }
    @Test public void layoutsRejectWrongTypeIdsUnitsAndExtraBehavior() throws Exception {
        assertThrows(IOException.class,()->verify(xml("WebView",layout("root")),"root"));
        List<ManifestPlan.Attribute> wrong=layout("text");wrong.removeIf(a->a.name.equals("id"));wrong.add(value("id",1,CHILDREN));assertThrows(IOException.class,()->verify(xml("TextView",wrong),"text"));
        List<ManifestPlan.Attribute> unit=info();unit.removeIf(a->a.name.equals("minWidth"));dimension(unit,"minWidth",180,2);assertThrows(IOException.class,()->verify(xml("appwidget-provider",unit),"info"));
        List<ManifestPlan.Attribute> extra=info();extra.add(a("configure",0x0101025d,3,"org.evil.Config"));assertThrows(IOException.class,()->verify(xml("appwidget-provider",extra),"info"));
        List<ManifestPlan.Attribute> alias=info();alias.removeIf(a->a.name.equals("initialLayout"));alias.add(a("initialLayout",0x01010252,1,EMPTY));assertThrows(IOException.class,()->verify(xml("appwidget-provider",alias),"info"));
    }
}
