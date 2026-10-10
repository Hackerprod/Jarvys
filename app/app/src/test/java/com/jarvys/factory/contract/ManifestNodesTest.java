package com.jarvys.factory.contract;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Host-only construction fixtures. These nodes cannot be selected by factory.json or the bridge. */
public class ManifestNodesTest {
    private static <T> List<T> list(T... values) { return Arrays.asList(values); }
    private static ManifestPlan plan(List<String> caps) {
        return new ManifestPlan("org.example.constructed", "name 🎉 日本語", 7, "versionName", 0x7f010000, 0x7f020000, caps);
    }
    private static ManifestNodes.Element filter(String action, String mime) {
        return ManifestNodes.intentFilter(list(ManifestNodes.action(action), ManifestNodes.category("android.intent.category.DEFAULT"), ManifestNodes.data(mime, null)));
    }
    static ManifestNodes.Element fixture() {
        ManifestPlan base = plan(Collections.emptyList());
        List<ManifestNodes.Element> appChildren = new ArrayList<>(base.root.children.get(1).children);
        appChildren.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.ACTIVITY, "org.example.PrivateActivity", list(
            ManifestNodes.intentFilter(list(ManifestNodes.action("android.intent.action.SEND"), ManifestNodes.action("android.intent.action.SEND_MULTIPLE"),
                ManifestNodes.category("android.intent.category.DEFAULT"), ManifestNodes.category("android.intent.category.BROWSABLE"), ManifestNodes.data("text/plain",null))), filter("android.intent.action.VIEW", "image/png"),
            ManifestNodes.metadataString("org.example.label", "name"), ManifestNodes.metadataBoolean("org.example.flag", false))));
        appChildren.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE, "org.example.PrivateService", list(
            ManifestNodes.metadataInt("org.example.count", 17), ManifestNodes.metadataResource("org.example.resource", 0x7f020000))));
        appChildren.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.RECEIVER, "org.example.PrivateReceiver", list(
            filter("android.intent.action.SEND", "application/json"))));
        appChildren.add(ManifestNodes.privateProvider("org.example.PrivateProvider", base.appId, "files", true, list(
            ManifestNodes.metadataResource("android.support.FILE_PROVIDER_PATHS", 0x7f020000))));
        ManifestNodes.Element app = ManifestNodes.base("application", appChildren, base.root.children.get(1).attributes.toArray(new ManifestPlan.Attribute[0]));
        return ManifestNodes.base("manifest", list(base.root.children.get(0),
            ManifestNodes.permission("android.permission.INTERNET", ManifestNodes.PermissionVariant.STANDARD, null),
            ManifestNodes.permission("android.permission.CAMERA", ManifestNodes.PermissionVariant.SDK_23, 35),
            ManifestNodes.permission("android.permission.RECORD_AUDIO", ManifestNodes.PermissionVariant.STANDARD, 36),
            ManifestNodes.feature("android.hardware.camera", false), ManifestNodes.feature("android.hardware.sensor.accelerometer", true),
            ManifestNodes.queries(list(ManifestNodes.queryPackage("org.example.handler"), ManifestNodes.queryPackage("org.example.second"),
                ManifestNodes.queryIntent("android.intent.action.SEND", "text/plain", null), ManifestNodes.queryIntent("android.intent.action.VIEW", null, "https"))),
            app), base.root.attributes.toArray(new ManifestPlan.Attribute[0]));
    }
    @Test public void currentBaseAndHistoricalPlanEncodeWithExactIndependentAudit() throws Exception {
        ManifestPlan current = plan(Collections.emptyList());
        ManifestAudit.read(ManifestXml.encode(current)).verify(current);
        ManifestPlan legacy = new ManifestPlan(current.appId,current.label,7,current.versionName,current.iconResourceId,current.backupResourceId,current.capabilities,ManifestPlan.Profile.V1_BEFORE_SAFE_AREA);
        ManifestAudit.read(ManifestXml.encode(legacy)).verify(legacy);
        assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encode(current)).verify(legacy));
        assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encode(legacy)).verify(current));
        assertEquals(7, current.nodes.size()); assertEquals(-1,current.nodes.get(0).parentIndex);
        assertEquals(4,current.nodes.get(6).parentIndex);
    }
    @Test public void all4096SelectionsEncodeOnlyTheApprovedDocumentQueries() throws Exception {
        byte[] first = ManifestXml.encode(plan(Collections.emptyList()));
        for (int mask=0; mask<(1 << CapabilityCatalog.NAMES.size()); mask++) {
            List<String> caps = new ArrayList<>();
            for (int bit=0; bit<CapabilityCatalog.NAMES.size(); bit++) if ((mask & (1<<bit)) != 0) caps.add(CapabilityCatalog.NAMES.get(bit));
            ManifestPlan p = plan(caps);
            boolean documents = caps.contains("documents") || caps.contains("browser") || caps.contains("maps") || caps.contains("phone");
            if (!documents) assertArrayEquals(first, ManifestXml.encode(p));
            ManifestAudit.read(ManifestXml.encode(p)).verify(p);
            assertEquals(documents ? 1 : 0, CapabilityCatalog.manifestContributions(caps).size());
            assertEquals(documents ? 10 : 7, p.nodes.size());
            assertTrue(p.permissions.isEmpty()); assertTrue(p.features.isEmpty());
            assertEquals(documents ? 2 : 0, p.queries.size());
            if (documents) {
                assertEquals(new ArrayList<>(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES), p.queries);
                assertEquals(7, p.nodes.get(8).parentIndex); assertEquals(7, p.nodes.get(9).parentIndex);
                assertThrows(IOException.class, () -> ManifestAudit.read(ManifestXml.encode(p)).verify(plan(Collections.emptyList())));
            }
        }
        for (CapabilityCatalog.Capability c : CapabilityCatalog.CAPABILITIES.values()) assertThrows(UnsupportedOperationException.class, () -> c.manifestNodes.add(ManifestNodes.feature("android.hardware.camera", false)));
        assertThrows(IllegalArgumentException.class, () -> plan(list("camera")));
    }
    @Test public void repeatedTypedNodesHaveIndependentParentsAndCanonicalTypes() throws Exception {
        ManifestNodes.Element root = fixture(); byte[] bytes = ManifestXml.encodeTree(root);
        ManifestAudit.verifyTree(bytes, root); ManifestAudit.Document d = ManifestAudit.read(bytes);
        int permissions=0, features=0, privateComponents=0, filters=0, metadata=0;
        for (ManifestAudit.Node n : d.nodes) {
            if (n.path.startsWith("manifest/uses-permission")) permissions++;
            if (n.path.equals("manifest/uses-feature")) features++;
            if (n.path.endsWith("intent-filter")) filters++;
            if (n.path.endsWith("meta-data")) metadata++;
            if (n.attributes.containsKey(ManifestPlan.ANDROID+"|exported") && n.attributes.get(ManifestPlan.ANDROID+"|exported").value.equals(0)) privateComponents++;
            if (n.parentIndex >= 0) assertTrue(n.path.startsWith(d.nodes.get(n.parentIndex).path + "/"));
        }
        assertEquals(3,permissions); assertEquals(2,features); assertEquals(4,privateComponents); assertEquals(4,filters); assertEquals(5,metadata);
        assertThrows(IOException.class, () -> d.attribute("manifest/application/activity",ManifestPlan.ANDROID,"name"));
        assertThrows(IOException.class, () -> d.verify(plan(Collections.emptyList())));
        assertArrayEquals(bytes,ManifestXml.encodeTree(fixture()));
        String dir = System.getProperty("jarvys.factory.evidenceDir");
        if (dir != null) {
            File out = new File(dir,"typed-manifest"); assertTrue(out.mkdirs() || out.isDirectory());
            Files.write(new File(out,"AndroidManifest.xml").toPath(),bytes);
            Files.write(new File(out,"base-manifest.xml").toPath(),ManifestXml.encode(plan(Collections.emptyList())));
        }
    }
    @Test public void sortingAndDefensiveCopiesDoNotDependOnInputOrder() throws Exception {
        List<ManifestNodes.Element> values = new ArrayList<>(list(ManifestNodes.queryPackage("org.example.z"),ManifestNodes.queryPackage("org.example.a")));
        ManifestNodes.Element a = ManifestNodes.queries(values); Collections.reverse(values); ManifestNodes.Element b = ManifestNodes.queries(values); values.clear();
        assertArrayEquals(ManifestXml.encodeTree(a),ManifestXml.encodeTree(b)); assertEquals(2,a.children.size());
        assertThrows(UnsupportedOperationException.class,()->a.children.clear());
        assertThrows(UnsupportedOperationException.class,()->a.children.get(0).attributes.clear());
    }
    @Test public void duplicateAndConflictingDeclarationsAreRejected() {
        ManifestNodes.Element p = ManifestNodes.permission("android.permission.CAMERA",ManifestNodes.PermissionVariant.STANDARD,null);
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.ordered(list(p,p)));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.ordered(list(p,ManifestNodes.permission("android.permission.CAMERA",ManifestNodes.PermissionVariant.SDK_23,35))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.queries(list(ManifestNodes.queryPackage("org.example.a"),ManifestNodes.queryPackage("org.example.a"))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,"org.example.Service",list(ManifestNodes.metadataInt("org.example.key",1),ManifestNodes.metadataString("org.example.key","x"))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.intentFilter(list(ManifestNodes.action("android.intent.action.SEND"),ManifestNodes.action("android.intent.action.SEND"))));
    }
    @Test public void applicationRejectsSharedAuthoritiesAndBaseComponentNameCollisions() {
        ManifestNodes.Element first=ManifestNodes.privateProvider("org.example.First","org.example.app","files",true,Collections.emptyList());
        ManifestNodes.Element second=ManifestNodes.privateProvider("org.example.Second","org.example.app","files",false,Collections.emptyList());
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.base("application",list(first,second)));
        ManifestNodes.Element launcher=plan(Collections.emptyList()).root.children.get(1).children.get(0);
        ManifestNodes.Element collision=ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,ManifestPlan.ACTIVITY,Collections.emptyList());
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.base("application",list(launcher,collision)));
    }
    @Test public void hostileNamesUnicodeBoundsAndChildKindsFailClosed() {
        for (String value : list(null,"", "android.permission.CAMERA;INTERNET", "android.permission.CAMERA/../X", "evil.permission.X", "android.permission.X\n"))
            assertThrows(IllegalArgumentException.class,()->ManifestNodes.permission(value,ManifestNodes.PermissionVariant.STANDARD,null));
        for (String value : list(".Private", "org.example.A/B", "org.example.$Proxy", "org.example.X\uD800"))
            assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateComponent(ManifestNodes.ComponentKind.ACTIVITY,value,Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.metadataString("org.example.label","bad\uDC00"));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.feature("android.permission.CAMERA",true));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.permission("android.permission.CAMERA",null,null));
        for (int api : new int[]{-1,0,23,37,Integer.MAX_VALUE}) assertThrows(IllegalArgumentException.class,()->ManifestNodes.permission("android.permission.CAMERA",ManifestNodes.PermissionVariant.STANDARD,api));
        for (int resource : new int[]{0,0x01010000,0x7f000000,0x80010000}) assertThrows(IllegalArgumentException.class,()->ManifestNodes.metadataResource("org.example.res",resource));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,"org.example.Service",list(ManifestNodes.permission("android.permission.INTERNET",ManifestNodes.PermissionVariant.STANDARD,null))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.queries(list(ManifestNodes.feature("android.hardware.camera",true))));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.intentFilter(list(ManifestNodes.category("android.intent.category.DEFAULT"))));
    }
    @Test public void authoritiesAreDerivedAndIntentDataCannotInjectUrisOrPackages() throws Exception {
        ManifestNodes.Element first = ManifestNodes.privateProvider("org.example.Provider","org.example.one","files",false,Collections.emptyList());
        ManifestNodes.Element second = ManifestNodes.privateProvider("org.example.Provider","org.example.two","files",false,Collections.emptyList());
        assertEquals("org.example.one.files",ManifestAudit.read(ManifestXml.encodeTree(first)).attribute("provider",ManifestPlan.ANDROID,"authorities").value);
        assertEquals("org.example.two.files",ManifestAudit.read(ManifestXml.encodeTree(second)).attribute("provider",ManifestPlan.ANDROID,"authorities").value);
        for(String suffix:list("shared.authority","../files","x;y","",null)) assertThrows(IllegalArgumentException.class,()->ManifestNodes.privateProvider("org.example.Provider","org.example.one",suffix,true,Collections.emptyList()));
        for(String scheme:list("intent","file","javascript","https://host","HTTPS")) assertThrows(IllegalArgumentException.class,()->ManifestNodes.data(null,scheme));
        for(String mime:list("text/plain; charset=utf-8","text/../plain","*/plain","text/plain\n")) assertThrows(IllegalArgumentException.class,()->ManifestNodes.data(mime,null));
    }
    @Test public void attributeTypeResourceRawNamespaceAndValueMutationCannotMatchConstruction() throws Exception {
        byte[] good = ManifestXml.encodeTree(fixture()); int tested=0;
        for(int p=8;p<good.length;p+=i32(good,p+4)) {
            if(u16(good,p)==0x102) for(int a=p+36;a<p+i32(good,p+4);a+=20) {
                final int position=a;
                for(int field:new int[]{0,4,8,16}) { byte[] bad=good.clone(); put(bad,position+field,0x7fffffff); assertThrows(IOException.class,()->ManifestAudit.verifyTree(bad,fixture())); }
                byte[] bad=good.clone();bad[a+15]=(byte)127;assertThrows(IOException.class,()->ManifestAudit.verifyTree(bad,fixture())); tested++;
            }
            if(u16(good,p)==0x180) for(int a=p+8;a<p+i32(good,p+4);a+=4) { byte[] bad=good.clone();put(bad,a,i32(good,a)^1);assertThrows(IOException.class,()->ManifestAudit.verifyTree(bad,fixture())); }
        }
        assertTrue(tested>50);
    }
    @Test public void movingACompleteChildBetweenRepeatedPathsChangesItsParentAndFailsAudit() throws Exception {
        byte[] good=ManifestXml.encodeTree(fixture()); ManifestAudit.Document original=ManifestAudit.read(good);
        int source=-1,target=-1;
        for(int i=0;i<original.nodes.size();i++) {
            ManifestAudit.Node n=original.nodes.get(i);
            ManifestAudit.Attribute name=n.attributes.get(ManifestPlan.ANDROID+"|name");
            if(name!=null && name.value.equals("android.intent.category.LAUNCHER")) source=i;
            if(n.path.endsWith("intent-filter") && n.parentIndex>=0) {
                ManifestAudit.Attribute component=original.nodes.get(n.parentIndex).attributes.get(ManifestPlan.ANDROID+"|name");
                if(target<0 && component!=null && component.value.equals("org.example.PrivateActivity")) target=i;
            }
        }
        assertTrue(source>=0 && target>source);
        int start=-1,end=-1,destination=-1,index=0;List<Integer> stack=new ArrayList<>();
        for(int p=8;p<good.length;p+=i32(good,p+4)) {
            if(u16(good,p)==0x102) {if(index==source)start=p;stack.add(index++);}
            if(u16(good,p)==0x103) {
                int closing=stack.remove(stack.size()-1);
                if(closing==source)end=p+i32(good,p+4);
                if(closing==target)destination=p;
            }
        }
        assertTrue(start>=0 && end>start && destination>end);
        java.io.ByteArrayOutputStream moved=new java.io.ByteArrayOutputStream();
        moved.write(good,0,start);moved.write(good,end,destination-end);moved.write(good,start,end-start);moved.write(good,destination,good.length-destination);
        byte[] changed=moved.toByteArray();assertEquals(good.length,changed.length);
        ManifestAudit.Document actual=ManifestAudit.read(changed);boolean found=false;
        for(ManifestAudit.Node n:actual.nodes) {
            ManifestAudit.Attribute name=n.attributes.get(ManifestPlan.ANDROID+"|name");
            if(name!=null && name.value.equals("android.intent.category.LAUNCHER")) {
                assertEquals(original.nodes.get(source).path,n.path);
                assertNotEquals(original.nodes.get(source).parentIndex,n.parentIndex);found=true;
            }
        }
        assertTrue(found);assertThrows(IOException.class,()->ManifestAudit.verifyTree(changed,fixture()));
    }
    @Test public void truncationSpecialIndexesAndOversizedChunksAreRejected() throws Exception {
        byte[] good=ManifestXml.encodeTree(fixture());
        for(int p=8;p<good.length;p+=i32(good,p+4)) {
            byte[] shortBytes=Arrays.copyOf(good,p);put(shortBytes,4,shortBytes.length);assertThrows(IOException.class,()->ManifestAudit.read(shortBytes));
            byte[] oversized=good.clone();put(oversized,p+4,0x7ffffffc);assertThrows(IOException.class,()->ManifestAudit.read(oversized));
            if(u16(good,p)==0x102) { byte[] bad=good.clone();bad[p+30]=1;assertThrows(IOException.class,()->ManifestAudit.read(bad)); }
        }
    }
    @Test public void treeLimitsAreEnforcedBeforeWriting() throws Exception {
        List<ManifestNodes.Element> many=new ArrayList<>();for(int i=0;i<33;i++)many.add(ManifestNodes.queryPackage("org.example.a"+i));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.queries(many));
        assertThrows(IllegalArgumentException.class,()->ManifestNodes.base("application",list(plan(Collections.emptyList()).root)));
        List<ManifestNodes.Element> components=new ArrayList<>();
        for(int i=0;i<30;i++) {
            List<ManifestNodes.Element> metadata=new ArrayList<>();
            for(int j=0;j<5;j++)metadata.add(ManifestNodes.metadataInt("org.example.k"+j,j));
            components.add(ManifestNodes.privateComponent(ManifestNodes.ComponentKind.SERVICE,"org.example.Service"+i,metadata));
        }
        ManifestNodes.Element large=ManifestNodes.base("application",components);
        assertThrows(IOException.class,()->ManifestXml.encodeTree(large));
        assertThrows(IOException.class,()->ManifestXml.encodeTree(null));
    }
    private static int u16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static int i32(byte[] b,int p) { return u16(b,p)|(u16(b,p+2)<<16); }
    private static void put(byte[] b,int p,int value) { for(int i=0;i<4;i++) b[p+i]=(byte)(value>>>(8*i)); }
}
