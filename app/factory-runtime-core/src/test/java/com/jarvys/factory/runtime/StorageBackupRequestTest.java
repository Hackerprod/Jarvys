package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.DatabaseRequestTest.repeat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class StorageBackupRequestTest {
    private JSONObject export() throws Exception { return new JSONObject().put("version",1).put("keys",new JSONArray().put("theme")).put("filename","settings backup.json"); }
    private JSONObject importing() throws Exception { return new JSONObject().put("version",1).put("keys",new JSONArray().put("theme")).put("handle",repeat("a",64)).put("mode","insertOnly"); }
    private void denied(String method,JSONObject args) { assertEquals("INVALID_ARGUMENT",assertThrows(FactoryException.class,()->StorageBackupRequest.validate(method,args)).code); }
    @Test public void onlyClosedCompleteMethodsAndVersionOne() throws Exception {
        StorageBackupRequest.validate("storage.backupExport",export()); StorageBackupRequest.validate("storage.backupImport",importing());
        StorageBackupRequest.validate("storage.backupImport",importing().put("mode","replaceSelectedKeys"));
        denied("database.backupExport",export()); denied("storage.backupExport",null); denied("storage.backupImport",export());
        denied("storage.backupExport",export().put("path","x")); denied("storage.backupImport",importing().put("tables",new JSONArray()));
        for(String field:new String[]{"version","keys","filename"}) { JSONObject args=export();args.remove(field);denied("storage.backupExport",args); }
        for(String field:new String[]{"version","keys","handle","mode"}) { JSONObject args=importing();args.remove(field);denied("storage.backupImport",args); }
        for(Object version:new Object[]{0,2,1000,1.0,"1",true,JSONObject.NULL}) denied("storage.backupExport",export().put("version",version));
        StorageBackupRequest.validate("storage.backupExport",export().put("version",1L));
    }
    @Test public void selectedKeysAreExactSortedUniqueStorageGrammar() throws Exception {
        for(JSONArray keys:new JSONArray[]{new JSONArray(),new JSONArray().put("z").put("a"),new JSONArray().put("a").put("a"),new JSONArray().put(1),new JSONArray().put(JSONObject.NULL)})
            denied("storage.backupExport",export().put("keys",keys));
        for(String key:new String[]{"","has space","日本語","x/y","x\\y",repeat("x",97)}) denied("storage.backupExport",export().put("keys",new JSONArray().put(key)));
        StorageBackupRequest.validate("storage.backupExport",export().put("keys",new JSONArray().put("..:_-AZ09").put("id").put("sqlite_master")));
        JSONArray keys=new JSONArray();for(int i=0;i<32;i++)keys.put(String.format("key%02d",i));
        StorageBackupRequest.validate("storage.backupExport",export().put("keys",keys)); keys.put("key32");denied("storage.backupExport",export().put("keys",keys));
        denied("storage.backupExport",export().put("keys","theme"));
    }
    @Test public void rejectsUnsafeNamesHandlesModesAndCoercion() throws Exception {
        for(String name:new String[]{"",".hidden","../x","a/notes.json","a\\notes.json","a..json","a.","é.json",repeat("a",121)}) denied("storage.backupExport",export().put("filename",name));
        StorageBackupRequest.validate("storage.backupExport",export().put("filename",repeat("a",120)));
        for(String handle:new String[]{repeat("A",64),repeat("a",63),repeat("a",65),repeat("g",64),"https://example.test/file"}) denied("storage.backupImport",importing().put("handle",handle));
        for(String mode:new String[]{"replace","upsert","merge","replaceSelectedTables","INSERTONLY",""}) denied("storage.backupImport",importing().put("mode",mode));
        denied("storage.backupExport",export().put("filename",true)); denied("storage.backupImport",importing().put("handle",123));
        denied("storage.backupExport",export().put("filename",repeat("a",4097)));
    }
}
