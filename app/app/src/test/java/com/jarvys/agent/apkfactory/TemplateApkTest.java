package com.jarvys.agent.apkfactory;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Structural/packaging tests against the real bundled runtime. No keys or installation are used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TemplateApkTest {
    private byte[] template;
    private byte[] icon;
    private TemplateApk.Spec spec;
    private Map<String, byte[]> assets;

    @Before public void loadBundledRuntime() throws Exception {
        try (InputStream in = RuntimeEnvironment.getApplication().getAssets().open("apk_factory/template.apk")) {
            template = readAll(in);
        }
        icon = png(24, 24);
        spec = new TemplateApk.Spec("org.example.factory.notes", "Factory notes", 1, "1.0");
        assets = new LinkedHashMap<>();
        assets.put("assets/www/index.html", utf8("<!doctype html><h1>Offline notes</h1>"));
        assets.put("assets/factory-app.json", utf8("{\"schemaVersion\":1}"));
        assets.put("assets/factory-provenance.json", utf8("{\"testFixture\":true}"));
    }

    @Test public void realRuntimeBuildsTwoPackagesAndAnUpdateWithoutChangingDex() throws Exception {
        Map<String, byte[]> original = unzip(template);
        TemplateApk.Spec[] specs = {
            spec,
            new TemplateApk.Spec("org.example.factory.second", "Notas 🎉 日本語", 1, "v1.1"),
            new TemplateApk.Spec(spec.appId, spec.label, 2, "v2")
        };
        Integer templateIconId = null;
        for (TemplateApk.Spec requested : specs) {
            byte[] apk = TemplateApk.build(template, requested, icon, assets);
            TemplateApk.ManifestInfo actual = TemplateApk.inspect(apk);
            assertEquals(requested.appId, actual.appId);
            assertEquals(requested.label, actual.label);
            assertEquals(requested.versionCode, actual.versionCode);
            assertEquals(requested.versionName, actual.versionName);
            assertEquals("com.jarvys.factory.runtime.FactoryActivity", actual.activityClass);
            assertTrue(actual.permissions.isEmpty());
            // Compare actual template/output IDs; never assume a numeric R value or shortened path.
            if (templateIconId == null) templateIconId = actual.iconResourceId;
            assertEquals(templateIconId.intValue(), actual.iconResourceId);
            Map<String, byte[]> generated = unzip(apk);
            assertArrayEquals(original.get("classes.dex"), generated.get("classes.dex"));
            assertEquals(requested.appId, resourcePackageName(generated.get("resources.arsc")));
            int replacedImages = 0;
            for (String name : original.keySet()) {
                if (name.startsWith("res/") && name.endsWith(".png") && Arrays.equals(icon, generated.get(name))) replacedImages++;
                if (name.endsWith(".dex")) assertArrayEquals("DEX changed: " + name, original.get(name), generated.get(name));
            }
            assertEquals(1, replacedImages);
            assertArrayEquals(assets.get("assets/factory-provenance.json"), generated.get("assets/factory-provenance.json"));
            assertStoredEntriesAligned(apk);
        }
    }


    @Test public void all64SelectionsHaveTheSameClosedZeroPermissionManifest() throws Exception {
        byte[] apk=build();
        com.jarvys.factory.contract.ManifestPlan base=TemplateApk.inspect(apk).plan;
        com.jarvys.factory.contract.ManifestAudit.Document decoded=com.jarvys.factory.contract.ManifestAudit.read(unzip(apk).get("AndroidManifest.xml"));
        for(int mask=0;mask<64;mask++) {
            List<String> requested=new ArrayList<>();
            for(int bit=0;bit<6;bit++) if((mask&(1<<bit))!=0) requested.add(com.jarvys.factory.contract.CapabilityCatalog.NAMES.get(bit));
            com.jarvys.factory.contract.ManifestPlan plan=new com.jarvys.factory.contract.ManifestPlan(spec.appId,spec.label,spec.versionCode,spec.versionName,base.iconResourceId,base.backupResourceId,requested);
            decoded.verify(plan); assertTrue(plan.permissions.isEmpty()); assertTrue(plan.features.isEmpty());
            assertTrue(plan.queries.isEmpty()); assertTrue(plan.hosts.isEmpty()); assertEquals(1,plan.exportedComponents.size());
            List<String> snapshot=new ArrayList<>(plan.capabilities); requested.clear(); assertEquals(snapshot,plan.capabilities);
            assertThrows(UnsupportedOperationException.class,()->plan.nodes.clear());
            assertThrows(UnsupportedOperationException.class,()->plan.nodes.get(0).attributes.clear());
            assertThrows(UnsupportedOperationException.class,()->plan.capabilities.clear());
        }
    }
    @Test public void callerIdentityVersionAndResourcePlanCannotBeSubstituted() throws Exception {
        byte[] apk=build();
        for(TemplateApk.Spec wrong:new TemplateApk.Spec[]{new TemplateApk.Spec("org.example.other","Factory notes",1,"1.0"),
                new TemplateApk.Spec(spec.appId,"Wrong",1,"1.0"),new TemplateApk.Spec(spec.appId,spec.label,2,"1.0"),
                new TemplateApk.Spec(spec.appId,spec.label,1,"wrong")}) rejects(()->TemplateApk.verify(apk,wrong));
        com.jarvys.factory.contract.ManifestPlan base=TemplateApk.inspect(apk).plan;
        com.jarvys.factory.contract.ManifestPlan wrong=new com.jarvys.factory.contract.ManifestPlan(spec.appId,spec.label,1,"1.0",base.backupResourceId,base.iconResourceId,Collections.emptyList());
        rejects(()->com.jarvys.factory.contract.ManifestAudit.read(unzip(apk).get("AndroidManifest.xml")).verify(wrong));
    }
    @Test public void independentReaderRejectsEveryAttributeTypeResourceIdAndValueMutation() throws Exception {
        byte[] original=unzip(template).get("AndroidManifest.xml"); int count=0;
        for(int p=8;p<original.length;p+=int32(original,p+4)) if(u16(original,p)==0x102) {
            for(int a=p+36;a<p+int32(original,p+4);a+=20) {
                byte[] wrongType=original.clone();wrongType[a+15]=(byte)0x7f;rejectManifest(wrongType);
                byte[] wrongData=original.clone();set32(wrongData,a+16,int32(original,a+16)^0x40000000);rejectManifest(wrongData);
                byte[] invalidRaw=original.clone();set32(invalidRaw,a+8,0x7fffffff);rejectManifest(invalidRaw);count++;
            }
        }
        assertEquals(23,count);
        int resourceCount=0;
        for(int p=8;p<original.length;p+=int32(original,p+4)) if(u16(original,p)==0x180)
            for(int a=p+8;a<p+int32(original,p+4);a+=4) {
                byte[] bad=original.clone();set32(bad,a,int32(bad,a)^1);rejectManifest(bad);resourceCount++;
            }
        assertTrue(resourceCount>=16);
    }
    @Test public void independentReaderRejectsMissingDuplicateAndUnexpectedAttributes() throws Exception {
        byte[] original=unzip(template).get("AndroidManifest.xml");
        int root=-1,application=-1;
        for(int p=8;p<original.length;p+=int32(original,p+4)) if(u16(original,p)==0x102) {
            if(root<0) root=p; else if(u16(original,p+28)>5) application=p;
        }
        assertTrue(root>=0 && application>=0);
        int copied=root+36;
        for(int a=root+36;a<root+int32(original,root+4);a+=20) if(int32(original,a)==-1 && (original[a+15]&255)==3) copied=a;
        byte[] unknown=insert(original,application+int32(original,application+4),Arrays.copyOfRange(original,copied,copied+20));
        set32(unknown,application+4,int32(original,application+4)+20);set16(unknown,application+28,u16(original,application+28)+1);rejectManifest(unknown);
        byte[] duplicate=insert(original,application+36,Arrays.copyOfRange(original,application+36,application+56));
        set32(duplicate,application+4,int32(original,application+4)+20);set16(duplicate,application+28,u16(original,application+28)+1);rejectManifest(duplicate);
        byte[] missing=new byte[original.length-20];System.arraycopy(original,0,missing,0,application+36);
        System.arraycopy(original,application+56,missing,application+36,original.length-application-56);
        set32(missing,4,missing.length);set32(missing,application+4,int32(original,application+4)-20);set16(missing,application+28,u16(original,application+28)-1);rejectManifest(missing);
    }
    @Test public void independentReaderRejectsDuplicateNodesNamespacesAndSpecialIndexes() throws Exception {
        byte[] original=unzip(template).get("AndroidManifest.xml");
        for(int p=8;p<original.length;p+=int32(original,p+4)) {
            int kind=u16(original,p),size=int32(original,p+4);
            if(kind==0x102) {
                byte[] bad=original.clone();set16(bad,p+30,1);rejectManifest(bad);
                byte[] wrongNamespace=original.clone();set32(wrongNamespace,p+16,int32(original,p+20));rejectManifest(wrongNamespace);
                if(p+size<original.length && u16(original,p+size)==0x103) {
                    int complete=size+int32(original,p+size+4);
                    rejectManifest(insert(original,p,Arrays.copyOfRange(original,p,p+complete)));
                }
            }
            if(kind==0x100 || kind==0x101) rejectManifest(insert(original,p,Arrays.copyOfRange(original,p,p+size)));
        }
    }
    @Test public void independentReaderRejectsEveryTruncationWithCheckedErrors() throws Exception {
        byte[] original=unzip(template).get("AndroidManifest.xml");
        for(int length=0;length<original.length;length++) {
            byte[] bad=Arrays.copyOf(original,length);
            rejects(()->com.jarvys.factory.contract.ManifestAudit.read(bad));
        }
        for(int p=8;p<original.length;p+=int32(original,p+4)) {
            byte[] bad=original.clone();set32(bad,p+4,0x7ffffffc);rejectManifest(bad);
        }
    }
    @Test public void secondaryDexInventoryIsCompleteImmutableAndExactlyPreserved() throws Exception {
        Map<String,byte[]> files=unzip(template);
        // Synthetic packaging fixture only; not an installable multidex compatibility claim.
        files.put("classes2.dex",DexBindingsTest.secondaryFixture());
        byte[] apk=TemplateApk.build(zip(files,false),spec,icon,assets);
        TemplateApk.ManifestInfo info=TemplateApk.inspect(apk);
        assertEquals(2,info.dexSha256.size());assertArrayEquals(files.get("classes2.dex"),unzip(apk).get("classes2.dex"));
        assertThrows(UnsupportedOperationException.class,()->info.dexSha256.clear());
        for(String name:new String[]{"classes1.dex","classes02.dex","classes3.dex","assets/classes2.dex","other.dex","CLASSES.DEX"}) {
            Map<String,byte[]> invalid=unzip(template);invalid.put(name,files.get("classes.dex"));
            rejects(()->TemplateApk.build(zip(invalid,false),spec,icon,assets));
        }
        for(int offset:new int[]{0,7,32,36,40}) {
            Map<String,byte[]> invalid=unzip(template);byte[] corrupt=files.get("classes.dex").clone();corrupt[offset]^=1;invalid.put("classes2.dex",corrupt);
            rejects(()->TemplateApk.build(zip(invalid,false),spec,icon,assets));
        }
    }
    @Test public void compiledBindingEvidenceIsDerivedImmutableAndStableAcrossPackages() throws Exception {
        TemplateApk.ManifestInfo original=TemplateApk.inspect(template),generated=TemplateApk.inspect(build());
        assertEquals(2,generated.resourceBindings.size());
        assertEquals("drawable",generated.resourceBindings.get("icon").type);
        assertEquals("factory_icon",generated.resourceBindings.get("icon").name);
        assertEquals(generated.iconResourceId,generated.resourceBindings.get("icon").id);
        assertEquals("xml",generated.resourceBindings.get("backup").type);
        assertEquals("factory_backup_rules",generated.resourceBindings.get("backup").name);
        assertEquals(generated.plan.backupResourceId,generated.resourceBindings.get("backup").id);
        assertEquals(original.resourceBindings.get("backup").path,generated.resourceBindings.get("backup").path);
        assertEquals(original.componentDex,generated.componentDex);
        assertEquals(2,generated.componentDex.size());
        assertEquals("classes.dex",generated.componentDex.get("com.jarvys.factory.runtime.FactoryActivity"));
        assertEquals("classes.dex",generated.componentDex.get("androidx.core.app.CoreComponentFactory"));
        assertThrows(UnsupportedOperationException.class,()->generated.resourceBindings.clear());
        assertThrows(UnsupportedOperationException.class,()->generated.componentDex.clear());
    }
    @Test public void duplicateDexDefinitionsAndMissingCompiledPayloadAreRejected() throws Exception {
        Map<String,byte[]> files=unzip(template);files.put("classes2.dex",files.get("classes.dex").clone());
        rejects(()->TemplateApk.build(zip(files,false),spec,icon,assets));
        TemplateApk.ManifestInfo info=TemplateApk.inspect(template);
        for(TemplateApk.ResourceBinding binding:info.resourceBindings.values()) {
            Map<String,byte[]> missing=unzip(template);missing.remove(binding.path);
            rejects(()->TemplateApk.build(zip(missing,false),spec,icon,assets));
        }
    }
    @Test public void completeResourceTableRejectsMalformedSpecificationsAndUnknownChildren() throws Exception {
        for(String mutation:new String[]{"chunkCount","startAlignment","specCount","specType","specReserved","unknownChild","duplicateSpec","missingSpec","poolPayload","poolAlias","entryAlias","duplicateSymbol"}) {
            Map<String,byte[]> files=unzip(template);
            files.put("resources.arsc",hostileResourceTable(files.get("resources.arsc"),mutation));
            rejects(()->TemplateApk.build(zip(files,false),spec,icon,assets));
        }
    }
    private static byte[] hostileResourceTable(byte[] source,String mutation) {
        byte[] b=source.clone();int pkg=-1;
        for(int p=12;p<b.length;p+=int32(b,p+4))if(u16(b,p)==0x200){pkg=p;break;}
        assertTrue(pkg>=0);
        if(mutation.equals("poolPayload")){set32(b,pkg+268,int32(b,pkg+268)+4);return b;}
        if(mutation.equals("poolAlias")){set32(b,pkg+276,int32(b,pkg+268));return b;}
        for(int q=pkg+u16(b,pkg+2);q<pkg+int32(b,pkg+4);q+=int32(b,q+4)) {
            if(u16(b,q)==0x202) {
                if(mutation.equals("chunkCount")){set16(b,q+10,0xffff);return b;}
                if(mutation.equals("specCount")){set32(b,q+12,int32(b,q+12)+1);return b;}
                if(mutation.equals("specType")){b[q+8]=0;return b;}
                if(mutation.equals("specReserved")){b[q+9]=1;return b;}
                if(mutation.equals("unknownChild")){set16(b,q,0x203);return b;}
                if(mutation.equals("duplicateSpec")){
                    byte[] result=insert(b,q,Arrays.copyOfRange(b,q,q+int32(b,q+4)));
                    set32(result,pkg+4,int32(b,pkg+4)+int32(b,q+4));return result;
                }
                if(mutation.equals("missingSpec")) {
                    int n=int32(b,q+4);byte[] result=new byte[b.length-n];
                    System.arraycopy(b,0,result,0,q);System.arraycopy(b,q+n,result,q,b.length-q-n);
                    set32(result,4,result.length);set32(result,pkg+4,int32(b,pkg+4)-n);return result;
                }
            }
            if(u16(b,q)==0x201 && mutation.equals("startAlignment")){set32(b,q+16,int32(b,q+16)+1);return b;}
            if(u16(b,q)==0x201 && (mutation.equals("entryAlias") || mutation.equals("duplicateSymbol"))) {
                int first=-1,count=int32(b,q+12),start=int32(b,q+16),offsets=q+u16(b,q+2);
                for(int i=0;i<count;i++) {
                    int relative=int32(b,offsets+4*i);if(relative==-1)continue;
                    if(first<0){first=i;continue;}
                    if(mutation.equals("entryAlias")) set32(b,offsets+4*i,int32(b,offsets+4*first));
                    else set32(b,q+start+relative+4,int32(b,q+start+int32(b,offsets+4*first)+4));
                    return b;
                }
            }
        }
        throw new AssertionError(mutation);
    }
    @Test public void missingOrWrongBackupResourceCannotMatchTheClosedPlan() throws Exception {
        byte[] apk=build();int backupId=TemplateApk.inspect(apk).plan.backupResourceId;
        Map<String,byte[]> files=unzip(template);byte[] xml=files.get("AndroidManifest.xml");
        boolean found=false;
        for(int p=8;p<xml.length;p+=int32(xml,p+4)) if(u16(xml,p)==0x102)
            for(int a=p+36;a<p+int32(xml,p+4);a+=20) if((xml[a+15]&255)==1 && int32(xml,a+16)==backupId) {
                set32(xml,a+16,TemplateApk.inspect(apk).iconResourceId);found=true;
            }
        assertTrue(found);rejects(()->TemplateApk.build(zip(files,false),spec,icon,assets));
    }

    @Test public void slashNamedElementCannotImpersonateTheExpectedParentTree() throws Exception {
        byte[] original=unzip(template).get("AndroidManifest.xml");int application=-1,endApplication=-1;
        for(int p=8;p<original.length;p+=int32(original,p+4)) if(u16(original,p)==0x102 && u16(original,p+28)>7) application=p;
        assertTrue(application>=0);int name=int32(original,application+20);
        for(int p=application;p<original.length;p+=int32(original,p+4)) if(u16(original,p)==0x103 && int32(original,p+20)==name) endApplication=p;
        int insertAt=application+int32(original,application+4);assertTrue(endApplication>insertAt);
        ByteArrayOutputStream changed=new ByteArrayOutputStream();changed.write(original,0,insertAt);
        changed.write(original,endApplication,24);changed.write(original,insertAt,endApplication-insertAt);
        changed.write(original,endApplication+24,original.length-endApplication-24);
        rejectManifest(renamePoolString(changed.toByteArray(),"activity","application/activity"));
    }
    @Test public void backupIncludesTraversalMissingDomainsAndQualifiedResourcesAreRejected() throws Exception {
        TemplateApk.ManifestInfo info=TemplateApk.inspect(template);
        Map<String,byte[]> original=unzip(template);String backupPath=null;
        for(Map.Entry<String,byte[]> e:original.entrySet()) if(e.getKey().endsWith(".xml") && !e.getKey().equals("AndroidManifest.xml")) {
            try { com.jarvys.factory.contract.ManifestAudit.verifyBackupRules(e.getValue());backupPath=e.getKey(); } catch(IOException ignored) { }
        }
        assertNotNull(backupPath);byte[] backup=original.get(backupPath);
        for(String[] mutation:new String[][]{{"exclude","include"},{".",".."},{"root","unknown"},{"device-transfer","unexpected"}}) {
            byte[] bad=renamePoolString(backup,mutation[0],mutation[1]);
            rejects(()->com.jarvys.factory.contract.ManifestAudit.verifyBackupRules(bad));
            Map<String,byte[]> files=unzip(template);files.put(backupPath,bad);
            rejects(()->TemplateApk.build(zip(files,false),spec,icon,assets));
        }
        for(int resourceId:new int[]{info.plan.iconResourceId,info.plan.backupResourceId}) {
            Map<String,byte[]> files=unzip(template);mutateResource(files.get("resources.arsc"),resourceId,"locale");
            rejects(()->TemplateApk.build(zip(files,false),spec,icon,assets));
        }
    }
    @Test public void signingCannotChangeAnyResourceOrWebsiteEntry() throws Exception {
        byte[] apk=build();TemplateApk.verifyUnchangedPayload(apk,apk);TemplateApk.verifyUnchangedPayload(apk,alignedZip(unzip(apk)));
        for(String name:new String[]{"resources.arsc","assets/www/index.html","classes.dex"}) {
            Map<String,byte[]> files=unzip(apk);byte[] changed=files.get(name).clone();changed[changed.length-1]^=1;files.put(name,changed);
            byte[] different=alignedZip(files);rejects(()->TemplateApk.verifyUnchangedPayload(apk,different));
        }
        Map<String,byte[]> extra=unzip(apk);extra.put("assets/extra.txt",utf8("extra"));
        rejects(()->TemplateApk.verifyUnchangedPayload(apk,alignedZip(extra)));
    }
    private static byte[] alignedZip(Map<String,byte[]> files) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes)) {
            for(Map.Entry<String,byte[]> e:files.entrySet()) {
                ZipEntry entry=new ZipEntry(e.getKey());entry.setTime(946728000000L);entry.setMethod(ZipEntry.STORED);
                CRC32 crc=new CRC32();crc.update(e.getValue());entry.setSize(e.getValue().length);entry.setCompressedSize(e.getValue().length);entry.setCrc(crc.getValue());
                int padding=(4-(bytes.size()+30+utf8(e.getKey()).length+4)%4)%4;byte[] extra=new byte[4+padding];
                set16(extra,0,0xf17e);set16(extra,2,padding);entry.setExtra(extra);
                out.putNextEntry(entry);out.write(e.getValue());out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] renamePoolString(byte[] xml,String old,String replacement) throws IOException {
        int pool=8,size=int32(xml,pool+4),count=int32(xml,pool+8),flags=int32(xml,pool+16),start=int32(xml,pool+20);boolean utf8=(flags&256)!=0;
        List<String> values=new ArrayList<>();boolean found=false;
        for(int i=0;i<count;i++) {
            int[] at={pool+start+int32(xml,pool+28+4*i)};int units=poolLength(xml,at,utf8),length=utf8?poolLength(xml,at,true):units*2;
            String value=new String(xml,at[0],length,utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);
            if(value.equals(old)){value=replacement;found=true;}values.add(value);
        }
        assertTrue("Pool string absent: "+old,found);
        ByteArrayOutputStream data=new ByteArrayOutputStream();int[] offsets=new int[count];
        for(int i=0;i<count;i++) {
            String value=values.get(i);byte[] encoded=value.getBytes(utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);offsets[i]=data.size();
            emitPoolLength(data,value.length(),utf8);if(utf8)emitPoolLength(data,encoded.length,true);data.write(encoded);data.write(0);if(!utf8)data.write(0);
        }
        while(data.size()%4!=0)data.write(0);byte[] next=new byte[28+count*4+data.size()];
        set16(next,0,1);set16(next,2,28);set32(next,4,next.length);set32(next,8,count);set32(next,16,utf8?256:0);set32(next,20,28+count*4);
        for(int i=0;i<count;i++)set32(next,28+i*4,offsets[i]);System.arraycopy(data.toByteArray(),0,next,28+count*4,data.size());
        byte[] result=new byte[xml.length-size+next.length];System.arraycopy(xml,0,result,0,8);System.arraycopy(next,0,result,8,next.length);
        System.arraycopy(xml,8+size,result,8+next.length,xml.length-8-size);set32(result,4,result.length);return result;
    }

    @Test public void legacyV1LayoutIsAcceptedOnlyThroughExplicitReceiptCompatibility() throws Exception {
        Map<String,byte[]> files=unzip(build());
        byte[] old=removeAttributeById(files.get("AndroidManifest.xml"),0x0101022b);files.put("AndroidManifest.xml",old);
        byte[] legacy=alignedZip(files);
        rejects(()->TemplateApk.verify(legacy,spec));
        TemplateApk.ManifestInfo accepted=TemplateApk.verifyExistingV1(legacy,spec);
        assertEquals(com.jarvys.factory.contract.ManifestPlan.Profile.V1_BEFORE_SAFE_AREA,accepted.plan.profile);
        TemplateApk.verifyAgainstPlan(legacy,accepted.plan);
        rejects(()->TemplateApk.verifyExistingV1(legacy,new TemplateApk.Spec(spec.appId,spec.label,2,spec.versionName)));
        byte[] unsafe=renamePoolString(old,"android.intent.action.MAIN","android.intent.action.VIEW");
        files.put("AndroidManifest.xml",unsafe);
        rejects(()->TemplateApk.verifyExistingV1(alignedZip(files),spec));
        assertEquals(com.jarvys.factory.contract.ManifestPlan.Profile.CURRENT,TemplateApk.verifyExistingV1(build(),spec).plan.profile);
    }
    public static byte[] legacyArtifactFixture(byte[] current) throws IOException {
        Map<String,byte[]> files=unzip(current);
        files.put("AndroidManifest.xml",removeAttributeById(files.get("AndroidManifest.xml"),0x0101022b));
        return alignedZip(files);
    }
    private static byte[] removeAttributeById(byte[] xml,int id) throws IOException {
        int nameIndex=-1;
        for(int p=8;p<xml.length;p+=int32(xml,p+4)) if(u16(xml,p)==0x180)
            for(int a=p+8;a<p+int32(xml,p+4);a+=4) if(int32(xml,a)==id) nameIndex=(a-p-8)/4;
        assertTrue(nameIndex>=0);
        for(int p=8;p<xml.length;p+=int32(xml,p+4)) if(u16(xml,p)==0x102)
            for(int a=p+36;a<p+int32(xml,p+4);a+=20) if(int32(xml,a+4)==nameIndex) {
                byte[] result=new byte[xml.length-20];System.arraycopy(xml,0,result,0,a);
                System.arraycopy(xml,a+20,result,a,xml.length-a-20);set32(result,4,result.length);
                set32(result,p+4,int32(xml,p+4)-20);set16(result,p+28,u16(xml,p+28)-1);return result;
            }
        throw new AssertionError("Attribute absent");
    }
    private void rejectManifest(byte[] candidate) throws Exception {
        com.jarvys.factory.contract.ManifestPlan plan=TemplateApk.inspect(template).plan;
        rejects(()->com.jarvys.factory.contract.ManifestAudit.read(candidate).verify(plan));
        Map<String,byte[]> files=unzip(template);files.put("AndroidManifest.xml",candidate);
        rejects(()->TemplateApk.build(zip(files,false),spec,icon,assets));
    }
    private static byte[] insert(byte[] original,int offset,byte[] added) {
        byte[] result=new byte[original.length+added.length];System.arraycopy(original,0,result,0,offset);
        System.arraycopy(added,0,result,offset,added.length);System.arraycopy(original,offset,result,offset+added.length,original.length-offset);
        set32(result,4,result.length);return result;
    }
    @Test public void deterministicZipDoesNotDependOnMapInsertionOrderOrTimezone() throws Exception {
        byte[] first = build();
        Map<String, byte[]> reverse = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(assets.keySet()); Collections.reverse(names);
        for (String name : names) reverse.put(name, assets.get(name));
        TimeZone old = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            assertArrayEquals(first, TemplateApk.build(template, spec, icon, reverse));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            assertArrayEquals(first, TemplateApk.build(template, spec, icon, assets));
        } finally { TimeZone.setDefault(old); }
    }

    @Test public void streamingDeflatedTemplateWithDataDescriptorsIsAccepted() throws Exception {
        byte[] streaming = zip(unzip(template), false);
        assertEquals(spec.appId, TemplateApk.inspect(TemplateApk.build(streaming, spec, icon, assets)).appId);
    }

    @Test public void bothBinaryXmlStringPoolEncodingsAreAccepted() throws Exception {
        Map<String, byte[]> files = unzip(template);
        files.put("AndroidManifest.xml", oppositePool(files.get("AndroidManifest.xml")));
        TemplateApk.Spec unicode = new TemplateApk.Spec(spec.appId, "Notas 🎉 日本語", 1, "1.0.β");
        byte[] transformed = TemplateApk.build(zip(files, false), unicode, icon, assets);
        assertEquals(unicode.label, TemplateApk.inspect(transformed).label);
        assertEquals(unicode.versionName, TemplateApk.inspect(transformed).versionName);
    }

    @Test public void siteReplacementRemovesStaleTemplateFiles() throws Exception {
        Map<String, byte[]> source = unzip(template);
        source.put("assets/www/stale.js", utf8("throw new Error('stale')"));
        source.put("assets/factory-provenance.json", utf8("old"));
        Map<String, byte[]> generated = unzip(TemplateApk.build(zip(source, false), spec, icon, assets));
        assertFalse(generated.containsKey("assets/www/stale.js"));
        assertArrayEquals(assets.get("assets/factory-provenance.json"), generated.get("assets/factory-provenance.json"));
    }

    @Test public void reservedMalformedOrTooLongIdentitiesAreRejected() throws Exception {
        for (String id : new String[]{"android", "bad..name", "1bad.name", "org.example/x", "com.jarvys.agent", "com.jarvys.factory.template", "org." + repeat("a", 128)}) {
            final TemplateApk.Spec bad = new TemplateApk.Spec(id, "Test", 1, "1");
            rejects(() -> TemplateApk.build(template, bad, icon, assets));
        }
    }

    @Test public void invalidLabelsVersionsAndUnpairedUnicodeAreRejected() throws Exception {
        for (String label : new String[]{"", "bad\nlabel", "\ud800", repeat("x", 81)}) {
            final TemplateApk.Spec bad = new TemplateApk.Spec(spec.appId, label, 1, "1");
            rejects(() -> TemplateApk.build(template, bad, icon, assets));
        }
        for (int code : new int[]{0, -1}) {
            final TemplateApk.Spec bad = new TemplateApk.Spec(spec.appId, "Test", code, "1");
            rejects(() -> TemplateApk.build(template, bad, icon, assets));
        }
        rejects(() -> TemplateApk.build(template, new TemplateApk.Spec(spec.appId, "Test", 1, ""), icon, assets));
    }

    @Test public void onlyExactApprovedAssetRootsCanBeReplaced() throws Exception {
        for (String path : new String[]{"classes.dex", "resources.arsc", "assets/other.json", "assets/www/../escape", "assets/www//file", "/assets/www/file", "assets/www/evil.so"}) {
            Map<String, byte[]> invalid = new HashMap<>(assets); invalid.put(path, new byte[1]);
            rejects(() -> TemplateApk.build(template, spec, icon, invalid));
        }
        for (String required : new String[]{"assets/www/index.html", "assets/factory-app.json"}) {
            Map<String, byte[]> missing = new HashMap<>(assets); missing.remove(required);
            rejects(() -> TemplateApk.build(template, spec, icon, missing));
        }
    }

    @Test public void archiveTraversalNativeLibrariesAndSymlinksAreRejected() throws Exception {
        for (String path : new String[]{"assets/../escape", "lib/arm64-v8a/evil.so", "assets/evil.so"}) {
            Map<String, byte[]> invalid = unzip(template); invalid.put(path, new byte[1]);
            byte[] candidate = zip(invalid, false);
            rejects(() -> TemplateApk.build(candidate, spec, icon, assets));
        }
        byte[] symlink = template.clone(); int central = findCentral(symlink, "classes.dex");
        set16(symlink, central + 4, 0x0314); set32(symlink, central + 38, 0120777L << 16);
        rejects(() -> TemplateApk.build(symlink, spec, icon, assets));
    }

    @Test public void duplicatePathsAndLocalCentralDisagreementAreRejected() throws Exception {
        Map<String, byte[]> files = unzip(template); files.put("classes.dey", files.get("classes.dex"));
        byte[] duplicate = zip(files, false); int central = findCentral(duplicate, "classes.dey");
        int local = int32(duplicate, central + 42);
        duplicate[central + 46 + "classes.de".length()] = 'x';
        duplicate[local + 30 + "classes.de".length()] = 'x';
        rejects(() -> TemplateApk.build(duplicate, spec, icon, assets));
        byte[] mismatch = template.clone(); central = findCentral(mismatch, "classes.dex"); local = int32(mismatch, central + 42);
        mismatch[local + 30] ^= 1;
        rejects(() -> TemplateApk.build(mismatch, spec, icon, assets));
    }

    @Test public void signatureBearingTemplatesAreNeverReused() throws Exception {
        Map<String, byte[]> files = unzip(template); files.put("META-INF/CERT.SF", utf8("signature"));
        byte[] v1 = zip(files, false); rejects(() -> TemplateApk.build(v1, spec, icon, assets));
        int end = eocd(template), directory = int32(template, end + 16);
        byte[] v2 = new byte[template.length + 32];
        System.arraycopy(template, 0, v2, 0, directory);
        set32(v2, directory, 24); set32(v2, directory + 8, 24);
        System.arraycopy(utf8("APK Sig Block 42"), 0, v2, directory + 16, 16);
        System.arraycopy(template, directory, v2, directory + 32, template.length - directory);
        set32(v2, end + 32 + 16, directory + 32);
        rejects(() -> TemplateApk.build(v2, spec, icon, assets));
    }

    @Test public void compressedOrUnalignedGeneratedResourcesFailInspection() throws Exception {
        Map<String, byte[]> generated = unzip(build());
        byte[] deflated = zip(generated, false), unaligned = zip(generated, true);
        rejects(() -> TemplateApk.inspect(deflated));
        rejects(() -> TemplateApk.inspect(unaligned));
    }

    @Test public void oversizeAndTruncatedArchivesFailWithCheckedErrors() throws Exception {
        rejects(() -> TemplateApk.inspect(new byte[TemplateApk.MAX_APK_BYTES + 1]));
        byte[] oversized = template.clone(); set32(oversized, findCentral(oversized, "classes.dex") + 24, 17 * 1024 * 1024);
        rejects(() -> TemplateApk.build(oversized, spec, icon, assets));
        // Bounded matrix includes the beginning, interior and every last-64-byte boundary.
        for (int n : new int[]{0, 1, 8, 21, 100, template.length / 2}) {
            byte[] shortened = Arrays.copyOf(template, n); rejects(() -> TemplateApk.inspect(shortened));
        }
        for (int n = template.length - 64; n < template.length; n++) {
            byte[] shortened = Arrays.copyOf(template, n); rejects(() -> TemplateApk.inspect(shortened));
        }
    }

    @Test public void crcCorruptionAndMissingOrInvalidDexAreRejected() throws Exception {
        byte[] corrupt = template.clone(); int c = findCentral(corrupt, "classes.dex"), local = int32(corrupt, c + 42);
        int data = local + 30 + u16(corrupt, local + 26) + u16(corrupt, local + 28); corrupt[data] ^= 1;
        rejects(() -> TemplateApk.build(corrupt, spec, icon, assets));
        Map<String, byte[]> missing = unzip(template); missing.remove("classes.dex");
        byte[] without = zip(missing, false); rejects(() -> TemplateApk.build(without, spec, icon, assets));
        Map<String, byte[]> invalid = unzip(template); invalid.get("classes.dex")[0] = 0;
        byte[] bad = zip(invalid, false); rejects(() -> TemplateApk.build(bad, spec, icon, assets));
    }

    @Test public void malformedXmlBoundsStringOffsetsAndAttributeIdsAreRejected() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Map<String, byte[]> files = unzip(template); byte[] xml = files.get("AndroidManifest.xml");
            if (kind == 0) set32(xml, 4, xml.length + 4);
            if (kind == 1) set32(xml, 8 + 28, 0xffffffffL);
            if (kind == 2) {
                boolean changed = false;
                for (int p = 8; p < xml.length; p += int32(xml, p + 4)) if (u16(xml, p) == 0x180) {
                    for (int i = p + 8; i < p + int32(xml, p + 4); i += 4) if (int32(xml, i) == 0x0101021b) {
                        set32(xml, i, 0x0101021c); changed = true; break;
                    }
                }
                assertTrue("versionCode resource map not found", changed);
            }
            byte[] bad = zip(files, false); rejects(() -> TemplateApk.build(bad, spec, icon, assets));
        }
    }

    @Test public void malformedResourcePackagesAndIconEntriesAreRejected() throws Exception {
        int iconId = TemplateApk.inspect(build()).iconResourceId;
        for (String kind : new String[]{"count", "size", "name", "pool", "header", "sparse", "compact", "key", "value"}) {
            Map<String, byte[]> files = unzip(template); byte[] arsc = files.get("resources.arsc");
            mutateResource(arsc, iconId, kind);
            byte[] bad = zip(files, false); rejects(() -> TemplateApk.build(bad, spec, icon, assets));
        }
    }

    @Test public void pngChecksRejectCrcDimensionsEncodingAndInflatedSizeMismatch() throws Exception {
        byte[] crc = icon.clone(); crc[crc.length - 1] ^= 1;
        rejects(() -> TemplateApk.build(template, spec, crc, assets));
        rejects(() -> TemplateApk.build(template, spec, new byte[45], assets));
        rejects(() -> TemplateApk.build(template, spec, new byte[1024 * 1024 + 1], assets));
        byte[] dimensions = png(1025, 1); rejects(() -> TemplateApk.build(template, spec, dimensions, assets));
        byte[] inconsistent = icon.clone(); inconsistent[16 + 3] = 23; repairPngCrc(inconsistent, 8);
        rejects(() -> TemplateApk.build(template, spec, inconsistent, assets));
    }

    private byte[] build() throws IOException { return TemplateApk.build(template, spec, icon, assets); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action) throws Exception {
        try { action.run(); fail("Malformed input was accepted"); } catch (IOException expected) { /* expected checked failure */ }
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(); for (int i=0;i<count;i++) out.append(value); return out.toString(); }
    private static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))!=-1) out.write(buf,0,n); return out.toByteArray(); }
    private static Map<String,byte[]> unzip(byte[] zip) throws IOException {
        Map<String,byte[]> files=new LinkedHashMap<>();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip))) { ZipEntry e; while((e=in.getNextEntry())!=null) files.put(e.getName(),readAll(in)); }
        return files;
    }
    private static byte[] zip(Map<String,byte[]> files,boolean stored) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes)) {
            for(Map.Entry<String,byte[]> e:files.entrySet()) {
                ZipEntry entry=new ZipEntry(e.getKey()); entry.setTime(315532800000L);
                if(stored) { CRC32 crc=new CRC32();crc.update(e.getValue());entry.setMethod(ZipEntry.STORED);entry.setSize(e.getValue().length);entry.setCompressedSize(e.getValue().length);entry.setCrc(crc.getValue()); }
                out.putNextEntry(entry);out.write(e.getValue());out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static int u16(byte[] b,int p) {return (b[p]&255)|((b[p+1]&255)<<8);}
    private static int int32(byte[] b,int p) {return u16(b,p)|(u16(b,p+2)<<16);}
    private static void set16(byte[] b,int p,int v) {b[p]=(byte)v;b[p+1]=(byte)(v>>>8);}
    private static void set32(byte[] b,int p,long v) {for(int i=0;i<4;i++)b[p+i]=(byte)(v>>>(8*i));}
    private static int eocd(byte[] apk) {for(int p=apk.length-22;p>=0;p--)if(int32(apk,p)==0x06054b50 && p+22+u16(apk,p+20)==apk.length)return p;throw new AssertionError("No EOCD");}
    private static int findCentral(byte[] apk,String name) {
        int end=eocd(apk);for(int p=int32(apk,end+16);p<end;p+=46+u16(apk,p+28)+u16(apk,p+30)+u16(apk,p+32)) {
            if(name.equals(new String(apk,p+46,u16(apk,p+28),StandardCharsets.UTF_8)))return p;
        }
        throw new AssertionError("Missing ZIP entry: "+name);
    }
    private static void assertStoredEntriesAligned(byte[] apk) {
        int end=eocd(apk);for(int p=int32(apk,end+16);p<end;p+=46+u16(apk,p+28)+u16(apk,p+30)+u16(apk,p+32)) {
            assertEquals(0,u16(apk,p+10));int local=int32(apk,p+42);assertEquals(0,(local+30+u16(apk,local+26)+u16(apk,local+28))%4);
        }
    }
    private static String resourcePackageName(byte[] b) {
        for(int p=12;p<b.length;p+=int32(b,p+4))if(u16(b,p)==0x200){int n=0;while(n<256&&u16(b,p+12+n)!=0)n+=2;return new String(b,p+12,n,StandardCharsets.UTF_16LE);}
        throw new AssertionError("No resource package");
    }
    private static void mutateResource(byte[] b,int iconId,String kind) {
        int wantedType=(iconId>>>16)&255,wantedEntry=iconId&65535;
        if(kind.equals("count")){set32(b,8,2);return;}if(kind.equals("size")){set32(b,4,b.length+4);return;}
        for(int p=12;p<b.length;p+=int32(b,p+4))if(u16(b,p)==0x200){
            int size=int32(b,p+4),header=u16(b,p+2);
            if(kind.equals("name")){b[p+12]='x';return;}if(kind.equals("pool")){set32(b,p+268,size+4);return;}
            for(int q=p+header;q<p+size;q+=int32(b,q+4))if(u16(b,q)==0x201&&(b[q+8]&255)==wantedType){
                if(kind.equals("header")){set16(b,q+2,8);return;}if(kind.equals("sparse")){b[q+9]=1;return;}
                int entryCount=int32(b,q+12),start=int32(b,q+16),offsets=q+u16(b,q+2);
                for(int i=0;i<entryCount;i++){int relative=int32(b,offsets+4*i);if(relative==-1)continue;
                    // Use the entry index from the real manifest reference, never a guessed numeric ID.
                    if(i!=wantedEntry)continue;int e=q+start+relative;
                    if(kind.equals("locale"))b[q+28]=101;else if(kind.equals("compact"))set16(b,e+2,8);else if(kind.equals("key"))set32(b,e+4,0x7fffffff);else if(kind.equals("value"))b[e+11]=1;else throw new AssertionError(kind);
                    return;
                }
            }
        }
        throw new AssertionError("No matching launcher entry: "+kind);
    }
    private static byte[] png(int width,int height) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream h=new ByteArrayOutputStream();DataOutputStream hd=new DataOutputStream(h);hd.writeInt(width);hd.writeInt(height);hd.write(new byte[]{8,6,0,0,0});pngChunk(out,"IHDR",h.toByteArray());
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(DeflaterOutputStream z=new DeflaterOutputStream(compressed)){for(int y=0;y<height;y++){z.write(0);for(int x=0;x<width;x++)z.write(new byte[]{40,100,(byte)180,(byte)255});}}
        pngChunk(out,"IDAT",compressed.toByteArray());pngChunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    private static void pngChunk(ByteArrayOutputStream out,String name,byte[] data) throws IOException {
        DataOutputStream stream=new DataOutputStream(out);byte[] type=utf8(name);stream.writeInt(data.length);stream.write(type);stream.write(data);CRC32 crc=new CRC32();crc.update(type);crc.update(data);stream.writeInt((int)crc.getValue());
    }
    private static void repairPngCrc(byte[] b,int p){int size=((b[p]&255)<<24)|((b[p+1]&255)<<16)|((b[p+2]&255)<<8)|(b[p+3]&255);CRC32 crc=new CRC32();crc.update(b,p+4,size+4);long v=crc.getValue();for(int i=0;i<4;i++)b[p+8+size+i]=(byte)(v>>>(24-8*i));}
    private static byte[] oppositePool(byte[] xml) throws IOException {
        int pool=8,size=int32(xml,pool+4),count=int32(xml,pool+8),flags=int32(xml,pool+16),start=int32(xml,pool+20);boolean oldUtf8=(flags&256)!=0,newUtf8=!oldUtf8;
        List<String> strings=new ArrayList<>();for(int i=0;i<count;i++){
            int[] at={pool+start+int32(xml,pool+28+4*i)};int units=poolLength(xml,at,oldUtf8),length=oldUtf8?poolLength(xml,at,true):units*2;
            strings.add(new String(xml,at[0],length,oldUtf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE));
        }
        ByteArrayOutputStream data=new ByteArrayOutputStream();int[] offsets=new int[count];for(int i=0;i<count;i++){
            String s=strings.get(i);byte[] encoded=s.getBytes(newUtf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE);offsets[i]=data.size();emitPoolLength(data,s.length(),newUtf8);if(newUtf8)emitPoolLength(data,encoded.length,true);data.write(encoded);data.write(0);if(!newUtf8)data.write(0);
        }
        while(data.size()%4!=0)data.write(0);byte[] replacement=new byte[28+4*count+data.size()];set16(replacement,0,1);set16(replacement,2,28);set32(replacement,4,replacement.length);set32(replacement,8,count);set32(replacement,16,newUtf8?256:0);set32(replacement,20,28+4*count);for(int i=0;i<count;i++)set32(replacement,28+4*i,offsets[i]);System.arraycopy(data.toByteArray(),0,replacement,28+4*count,data.size());
        byte[] result=new byte[xml.length-size+replacement.length];System.arraycopy(xml,0,result,0,8);System.arraycopy(replacement,0,result,8,replacement.length);System.arraycopy(xml,8+size,result,8+replacement.length,xml.length-8-size);set32(result,4,result.length);return result;
    }
    private static int poolLength(byte[] b,int[] at,boolean utf8){if(utf8){int v=b[at[0]++]&255;if((v&128)!=0)v=((v&127)<<8)|(b[at[0]++]&255);return v;}int v=u16(b,at[0]);at[0]+=2;if((v&32768)!=0){v=((v&32767)<<16)|u16(b,at[0]);at[0]+=2;}return v;}
    private static void emitPoolLength(ByteArrayOutputStream b,int n,boolean utf8){if(utf8){if(n>=128)b.write((n>>>8)|128);b.write(n);}else{if(n>=32768){b.write(0);b.write(128);}b.write(n);b.write(n>>>8);}}
}
