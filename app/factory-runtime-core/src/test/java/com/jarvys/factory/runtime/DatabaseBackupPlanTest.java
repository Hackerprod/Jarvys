package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public class DatabaseBackupPlanTest {
    private static String hex(char c) { char[] a=new char[64]; Arrays.fill(a,c); return new String(a); }
    private DatabaseBackupFormat.Archive archive() throws Exception {
        JSONObject columns=new JSONObject().put("text",new JSONObject().put("type","text").put("nullable",true));
        return DatabaseBackupFormat.create("example.factory.app",hex('a'),1,new JSONObject().put("notes",columns),
            new JSONObject().put("notes",new JSONArray().put(new JSONObject().put("id","one").put("text","Synthetic only"))));
    }
    private DatabaseBackupPlan plan() throws Exception {
        DatabaseBackupFormat.Archive archive=archive();
        return DatabaseBackupPlan.create(hex('b'),300000,"import",archive,"replaceSelectedTables","",
            DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(archive.schema())),hex('c'),new JSONObject().put("notes",2));
    }
    private String json(JSONObject object) throws Exception { return new String(DatabaseBackupFormat.canonical(object),StandardCharsets.UTF_8); }
    private void denied(String value) { try { DatabaseBackupPlan.parse(value); fail("Expected invalid plan"); } catch(FactoryException expected){} }
    @Test public void canonicalExactPlanAndNestedValuesAreImmutable() throws Exception {
        DatabaseBackupFormat.Archive archive=archive(); JSONObject rows=new JSONObject().put("notes",2);
        DatabaseBackupPlan value=DatabaseBackupPlan.create(hex('b'),300000,"import",archive,"insertOnly","",DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(archive.schema())),hex('c'),rows);
        String original=value.json(); rows.put("notes",8); value.targetRows().put("notes",9);
        byte[] bytes=archive.bytes(); Arrays.fill(bytes,(byte)0); archive.schema().put("evil",true); archive.data().put("evil",true);
        assertEquals(original,value.json()); assertEquals(2,value.targetRows().getInt("notes")); value.validateArchive(archive);
        assertEquals(original,DatabaseBackupPlan.parse(original).json());
        assertEquals(DatabaseBackupFormat.sha256(original.getBytes(StandardCharsets.UTF_8)),value.sha256());
        try { value.tables.add("evil"); fail(); } catch(UnsupportedOperationException expected){}
    }
    @Test public void strictEnvelopeRejectsExtraMissingAndNoncanonicalInputs() throws Exception {
        JSONObject good=new JSONObject(plan().json());
        denied(json(new JSONObject(good.toString()).put("extra",true)));
        for(String field:Arrays.asList("nonce","deadline","operation","archiveSha256","size","appId","certificateSha256","databaseVersion","tables","mode","filename","schemaSha256","preimageSha256","targetRows")) {
            JSONObject changed=new JSONObject(good.toString());changed.remove(field);denied(json(changed));
        }
        denied(" "+plan().json()); denied(plan().json()+"\n");
        denied(plan().json().replace("\"deadline\":300000","\"deadline\":3e5"));
        denied(plan().json().replace("\"deadline\":300000","\"deadline\":300000.0"));
        denied(plan().json().replace("\"databaseVersion\":1","\"databaseVersion\":\"1\""));
        denied(plan().json().replace("\"notes\":2","\"notes\":2.0"));
    }
    @Test public void incompatibleHashOriginSchemaSizeAndTableSetFail() throws Exception {
        DatabaseBackupFormat.Archive archive=archive();
        for(String field:Arrays.asList("archiveSha256","certificateSha256","schemaSha256")) {
            JSONObject changed=new JSONObject(plan().json()).put(field,hex('d'));
            DatabaseBackupPlan parsed=DatabaseBackupPlan.parse(json(changed));
            try { parsed.validateArchive(archive); fail(field); } catch(FactoryException expected){}
        }
        for(JSONObject changed:Arrays.asList(new JSONObject(plan().json()).put("size",1),new JSONObject(plan().json()).put("appId","example.other.app"),new JSONObject(plan().json()).put("databaseVersion",2))) {
            try { DatabaseBackupPlan.parse(json(changed)).validateArchive(archive); fail(); } catch(FactoryException expected){}
        }
    }
    @Test public void operationModeAndBoundsCannotBeEdited() throws Exception {
        JSONObject original=new JSONObject(plan().json());
        for(JSONObject changed:Arrays.asList(
            new JSONObject(original.toString()).put("mode","merge"),new JSONObject(original.toString()).put("operation","export"),
            new JSONObject(original.toString()).put("filename","import.json"),new JSONObject(original.toString()).put("preimageSha256",""),
            new JSONObject(original.toString()).put("size",1048577),new JSONObject(original.toString()).put("deadline",0),
            new JSONObject(original.toString()).put("databaseVersion",1001),new JSONObject(original.toString()).put("targetRows",new JSONObject().put("notes",10001)),
            new JSONObject(original.toString()).put("targetRows",new JSONObject().put("notes",-1)),new JSONObject(original.toString()).put("targetRows",new JSONObject().put("other",1)),
            new JSONObject(original.toString()).put("tables",new JSONArray().put("notes").put("notes")),
            new JSONObject(original.toString()).put("tables",new JSONArray().put("factory_private")))) denied(json(changed));
        DatabaseBackupFormat.Archive archive=archive();
        DatabaseBackupPlan exported=DatabaseBackupPlan.create(hex('b'),300000,"export",archive,"export","notes.json",DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(archive.schema())),"",new JSONObject());
        assertEquals("export",DatabaseBackupPlan.parse(exported.json()).mode);
        denied(json(new JSONObject(exported.json()).put("filename","../notes.json")));
        denied(json(new JSONObject(exported.json()).put("targetRows",new JSONObject().put("notes",0))));
    }
}
