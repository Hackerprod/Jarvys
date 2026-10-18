package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.DatabaseRequestTest.invalid;
import static com.jarvys.factory.runtime.DatabaseRequestTest.repeat;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DatabaseBackupRequestTest {
    private JSONObject export() throws Exception {
        return new JSONObject().put("version",1).put("tables",new JSONArray().put("notes")).put("filename","notes backup.json");
    }
    private JSONObject importing() throws Exception {
        return new JSONObject().put("version",1).put("tables",new JSONArray().put("notes"))
                .put("handle",repeat("a",64)).put("mode","insertOnly");
    }
    @Test public void acceptsOnlyTheTwoCompleteClosedMethods() throws Exception {
        DatabaseBackupRequest.validate("database.backupExport",export());
        DatabaseBackupRequest.validate("database.backupImport",importing());
        DatabaseBackupRequest.validate("database.backupImport",importing().put("mode","replaceSelectedTables"));
        invalid(()->DatabaseBackupRequest.validate("database.export",export()));
        invalid(()->DatabaseBackupRequest.validate("database.backupImport",export()));
        invalid(()->DatabaseBackupRequest.validate("database.backupExport",null));
        invalid(()->DatabaseBackupRequest.validate("database.backupExport",export().put("path","x")));
        for(String name:new String[]{"version","tables","filename"}) {
            JSONObject args=export(); args.remove(name); invalid(()->DatabaseBackupRequest.validate("database.backupExport",args));
        }
        for(String name:new String[]{"version","tables","handle","mode"}) {
            JSONObject args=importing(); args.remove(name); invalid(()->DatabaseBackupRequest.validate("database.backupImport",args));
        }
    }
    @Test public void boundsVersionsFilenamesHandlesAndModesWithoutCoercion() throws Exception {
        for(Object version:new Object[]{0,1001,1.0,"1",true,JSONObject.NULL})
            invalid(()->DatabaseBackupRequest.validate("database.backupExport",export().put("version",version)));
        for(String filename:new String[]{"", ".hidden", "../x", "a/notes.json", "a\\notes.json", "a..json", "a.", "é.json", repeat("a",121)})
            invalid(()->DatabaseBackupRequest.validate("database.backupExport",export().put("filename",filename)));
        DatabaseBackupRequest.validate("database.backupExport",export().put("filename",repeat("a",120)).put("version",1000));
        for(String handle:new String[]{repeat("A",64),repeat("a",63),repeat("a",65),repeat("g",64),"https://example.test/file"})
            invalid(()->DatabaseBackupRequest.validate("database.backupImport",importing().put("handle",handle)));
        for(String mode:new String[]{"replace","upsert","merge","INSERTONLY",""})
            invalid(()->DatabaseBackupRequest.validate("database.backupImport",importing().put("mode",mode)));
        invalid(()->DatabaseBackupRequest.validate("database.backupExport",export().put("filename",repeat("a",4096))));
    }
    @Test public void tablesMustBeSortedUniqueBoundedSafeIdentifiers() throws Exception {
        for(JSONArray tables:new JSONArray[]{new JSONArray(),new JSONArray().put("z").put("a"),new JSONArray().put("notes").put("notes"),
                new JSONArray().put("id"),new JSONArray().put("factory_schema"),new JSONArray().put("sqlite_master"),new JSONArray().put(1)})
            invalid(()->DatabaseBackupRequest.validate("database.backupExport",export().put("tables",tables)));
        JSONArray tables=new JSONArray(); for(int i=0;i<8;i++) tables.put("t"+i);
        DatabaseBackupRequest.validate("database.backupExport",export().put("tables",tables));
        tables.put("t8"); invalid(()->DatabaseBackupRequest.validate("database.backupExport",export().put("tables",tables)));
    }
    @Test public void invalidFieldsNameTheirExpectationWithoutEchoingSubmittedValues() throws Exception {
        for (JSONObject args : new JSONObject[]{
                export().put("filename", "../../private-name"),
                export().put("version", 1.0),
                export().put("tables", new JSONArray().put("../../private-table")),
                importing().put("handle", "private-handle"),
                importing().put("mode", "private-mode")}) {
            String method = args.has("filename") ? "database.backupExport" : "database.backupImport";
            try { DatabaseBackupRequest.validate(method, args); fail("Expected INVALID_ARGUMENT"); }
            catch (FactoryException failure) {
                assertEquals("INVALID_ARGUMENT", failure.code);
                assertTrue(failure.getMessage().contains(method + "."));
                assertFalse(failure.getMessage().contains("private"));
            }
        }
    }
}
