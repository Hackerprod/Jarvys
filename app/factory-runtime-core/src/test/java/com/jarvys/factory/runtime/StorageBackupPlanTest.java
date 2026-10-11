package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.DatabaseRequestTest.repeat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class StorageBackupPlanTest {
    private StorageBackupFormat.Archive archive() throws Exception { return StorageBackupFormatTest.archive(new JSONObject().put("theme","dark")); }
    private StorageBackupPlan plan() throws Exception { return StorageBackupPlan.create(repeat("b",64),300000,"import",archive(),"replaceSelectedKeys","",repeat("c",64),new JSONObject().put("theme",true)); }
    private String json(JSONObject value) throws Exception { return new String(StorageBackupFormat.canonical(value),StandardCharsets.UTF_8); }
    private void denied(String value) { assertEquals("INVALID_BACKUP",assertThrows(FactoryException.class,()->StorageBackupPlan.parse(value)).code); }
    @Test public void planAndPresenceAreImmutableCanonicalAndArchiveBound() throws Exception {
        JSONObject present=new JSONObject().put("theme",false);StorageBackupFormat.Archive archive=archive();
        StorageBackupPlan plan=StorageBackupPlan.create(repeat("b",64),300000,"import",archive,"insertOnly","",repeat("c",64),present);
        String original=plan.json();present.put("theme",true);plan.targetPresent().put("theme",true);archive.data().put("theme","changed");
        assertFalse(plan.targetPresent().getBoolean("theme"));assertEquals(original,plan.json());assertEquals(original,StorageBackupPlan.parse(original).json());
        assertEquals(StorageBackupFormat.sha256(original.getBytes(StandardCharsets.UTF_8)),plan.sha256());plan.validateArchive(archive);
        assertThrows(UnsupportedOperationException.class,()->plan.keys.add("x"));assertEquals(1,plan.storageVersion);
        assertThrows(FactoryException.class,()->plan.validateArchive(StorageBackupFormatTest.archive(new JSONObject().put("theme","light"))));
    }
    @Test public void completeClosedPlanRejectsNoncanonicalAndWrongTypes() throws Exception {
        String original=plan().json();
        for(String field:new String[]{"nonce","deadline","operation","archiveSha256","size","appId","certificateSha256","storageVersion","keys","mode","filename","preimageSha256","targetPresent"}) {
            JSONObject changed=new JSONObject(original);changed.remove(field);denied(json(changed));
        }
        denied(json(new JSONObject(original).put("extra",true)));denied(" "+original);denied(original+"\n");
        denied(original.replace("\"deadline\":300000","\"deadline\":3e5"));denied(original.replace("\"storageVersion\":1","\"storageVersion\":1.0"));
        for(Object presence:new Object[]{0,1,"true",JSONObject.NULL,new JSONArray()})denied(json(new JSONObject(original).put("targetPresent",new JSONObject().put("theme",presence))));
        for(Object version:new Object[]{0,2,"1",1.0})denied(json(new JSONObject(original).put("storageVersion",version)));
    }
    @Test public void tamperedIdentityArchiveKeysAndModeAreRejected() throws Exception {
        StorageBackupFormat.Archive archive=archive();String original=plan().json();
        for(String field:new String[]{"archiveSha256","certificateSha256"})assertThrows(FactoryException.class,()->StorageBackupPlan.parse(json(new JSONObject(original).put(field,repeat("d",64)))).validateArchive(archive));
        for(JSONObject changed:new JSONObject[]{new JSONObject(original).put("size",1),new JSONObject(original).put("appId","example.other"),
                new JSONObject(original).put("keys",new JSONArray().put("other")).put("targetPresent",new JSONObject().put("other",true))})
            assertThrows(FactoryException.class,()->StorageBackupPlan.parse(json(changed)).validateArchive(archive));
        for(JSONObject changed:new JSONObject[]{new JSONObject(original).put("mode","replaceSelectedTables"),new JSONObject(original).put("operation","export"),
                new JSONObject(original).put("filename","import.json"),new JSONObject(original).put("preimageSha256",""),new JSONObject(original).put("size",1048577),
                new JSONObject(original).put("deadline",0),new JSONObject(original).put("keys",new JSONArray().put("theme").put("theme")),
                new JSONObject(original).put("targetPresent",new JSONObject().put("other",true))})denied(json(changed));
        StorageBackupPlan exported=StorageBackupPlan.create(repeat("b",64),300000,"export",archive,"export","settings.json","",new JSONObject());
        assertEquals("export",StorageBackupPlan.parse(exported.json()).mode);denied(json(new JSONObject(exported.json()).put("filename","../settings.json")));
        denied(json(new JSONObject(exported.json()).put("targetPresent",new JSONObject().put("theme",false))));
    }
}
