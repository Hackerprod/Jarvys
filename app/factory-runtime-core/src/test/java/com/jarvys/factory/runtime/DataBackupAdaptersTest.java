package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public class DataBackupAdaptersTest {
    private static final String HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private DatabaseBackupFormat.Archive database() throws Exception {
        return DatabaseBackupFormat.create("example.app",HEX,1,
                new JSONObject().put("notes",new JSONObject().put("text",new JSONObject().put("type","text").put("nullable",true))),
                new JSONObject().put("notes",new JSONArray()));
    }
    private StorageBackupFormat.Archive storage() throws Exception {
        return StorageBackupFormat.create("example.app",HEX,1,new JSONObject().put("notes","literal"));
    }
    @Test public void sqliteAdapterPreservesEveryOriginalByteAndHash() throws Exception {
        DatabaseBackupFormat.Archive archive=database(); DataBackupArchive adapter=DataBackupArchive.parse(archive.bytes());
        assertFalse(adapter.isStorage()); assertArrayEquals(archive.bytes(),adapter.bytes()); assertEquals(archive.sha256,adapter.sha256);
        DatabaseBackupPlan plan=DatabaseBackupPlan.create(HEX,300000,"export",archive,"export","backup.json",
                DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(archive.schema())),"",new JSONObject());
        DataBackupPlan parsed=DataBackupPlan.parse(plan.json());
        assertFalse(parsed.isStorage()); assertEquals(plan.json(),parsed.json()); assertEquals(plan.sha256(),parsed.sha256()); parsed.validateArchive(adapter);
    }
    @Test public void storageAdapterCannotAuthorizeSqliteOrBeAuthorizedBySqlite() throws Exception {
        StorageBackupFormat.Archive archive=storage(); DataBackupArchive adapter=DataBackupArchive.parse(archive.bytes());
        assertTrue(adapter.isStorage()); assertArrayEquals(archive.bytes(),adapter.bytes());
        assertThrows(UnsupportedOperationException.class,()->adapter.selectedNames().add("other"));
        StorageBackupPlan plan=StorageBackupPlan.create(HEX,300000,"export",archive,"export","backup.json","",new JSONObject());
        DataBackupPlan parsed=DataBackupPlan.parse(plan.json()); assertThrows(UnsupportedOperationException.class,()->parsed.keys.add("other")); assertEquals("storage",parsed.capability()); parsed.validateArchive(adapter);
        assertThrows(FactoryException.class,()->parsed.validateArchive(DataBackupArchive.of(database())));
        DatabaseBackupPlan legacy=DatabaseBackupPlan.create(HEX,300000,"export",database(),"export","backup.json",
                DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(database().schema())),"",new JSONObject());
        assertThrows(FactoryException.class,()->DataBackupPlan.of(legacy).validateArchive(adapter));
    }
    @Test public void closedDiscriminatorsRejectMixedAndUnknownFieldsWithoutFallback() throws Exception {
        StorageBackupFormat.Archive archive=storage();
        StorageBackupPlan plan=StorageBackupPlan.create(HEX,300000,"export",archive,"export","backup.json","",new JSONObject());
        for(String key:new String[]{"databaseVersion","tables","unknown"}) {
            String mixed=new String(DatabaseBackupFormat.canonical(new JSONObject(plan.json()).put(key,1)),java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(FactoryException.class,()->DataBackupPlan.parse(mixed));
        }
        JSONObject mixed=new JSONObject(new String(archive.bytes(),java.nio.charset.StandardCharsets.UTF_8)).put("tables",new JSONObject());
        assertThrows(FactoryException.class,()->DataBackupArchive.parse(DatabaseBackupFormat.canonical(mixed)));
        mixed.remove("tables"); mixed.put("format","unknown");
        assertThrows(FactoryException.class,()->DataBackupArchive.parse(DatabaseBackupFormat.canonical(mixed)));
    }
}
