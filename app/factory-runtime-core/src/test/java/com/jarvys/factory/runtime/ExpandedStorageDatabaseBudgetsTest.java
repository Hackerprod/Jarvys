package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class ExpandedStorageDatabaseBudgetsTest {
    private final List<PrivateDatabase> opened=new ArrayList<>();
    @After public void close() { for(PrivateDatabase db:opened) db.close(); }
    private static String repeat(String value,int count) { return DatabaseRequestTest.repeat(value,count); }
    private static ResourceBudgets budget(String section,Object... fields) {
        Map<String,Object> root=new LinkedHashMap<>(),values=new LinkedHashMap<>(); root.put("version",1);
        for(int i=0;i<fields.length;i+=2) values.put((String)fields[i],fields[i+1]); root.put(section,values);
        return ResourceBudgets.parse(root);
    }
    interface Checked { void run() throws Exception; }
    private static void error(String code,Checked action) throws Exception {
        assertEquals(code,assertThrows(FactoryException.class,action::run).code);
    }
    @Test public void liveGrammarMaximaAndFrozenArchiveDefaultsStaySeparate() {
        ResourceBudgets limits=ResourceBudgets.maxima();
        assertEquals(StorageRequest.MAX_VALUE_BYTES,limits.storage.valueBytes);
        assertEquals(16L*1024*1024,limits.storage.totalBytes); assertEquals(65536,limits.storage.entries);
        assertEquals(DatabaseRequest.HARD_MAX_CELL_BYTES,limits.database.cellBytes);
        assertEquals(DatabaseRequest.HARD_MAX_ROW_BYTES,limits.database.rowBytes);
        assertEquals(DatabaseRequest.HARD_MAX_SELECT_ROWS,limits.database.selectPageRows);
        assertEquals(DatabaseRequest.HARD_MAX_OPERATIONS,limits.database.batchOperations);
        assertEquals(65536,BridgeProtocol.MAX_VALUE_BYTES); assertEquals(4096,DatabaseRequest.MAX_CELL_BYTES);
        assertEquals(16384,DatabaseRequest.MAX_ROW_BYTES); assertEquals(64,DatabaseRequest.MAX_OPERATIONS);
        assertEquals(100,ResourceBudgets.legacy().database.selectPageRows);
    }
    @Test public void storageExpandsWithoutWholeMapReplacementAndLoweringNeverEvicts() throws Exception {
        MemoryBackend backend=new MemoryBackend(); BoundedStore expanded=new BoundedStore(backend,ResourceBudgets.maxima());
        String value=repeat("x",300000); for(int i=0;i<5;i++) expanded.set("key"+i,value);
        assertEquals(value,expanded.get("key0")); assertEquals(5,expanded.list().size());
        BoundedStore lowered=new BoundedStore(backend,budget("storage","entries",1,"totalBytes",4,"valueBytes",1));
        assertEquals(value,lowered.get("key0")); error("QUOTA_EXCEEDED",()->lowered.set("new","x"));
        error("QUOTA_EXCEEDED",()->lowered.set("key0",value+"x"));
        lowered.set("key0","small"); lowered.remove("key1"); assertEquals("small",expanded.get("key0"));
        assertEquals(value,expanded.get("key2"));
        BoundedStore.Backend selectedOnly=new BoundedStore.Backend() {
            public Map<String,String> read() { return Collections.emptyMap(); }
            public String get(String key) { return "direct"; }
            public boolean replace(Map<String,String> data) { throw new AssertionError("Whole-map replacement"); }
            public boolean putSelected(Map<String,String> data) { assertEquals(1,data.size()); return true; }
            public boolean removeSelected(String key) { return true; }
        };
        BoundedStore selected=new BoundedStore(selectedOnly,ResourceBudgets.maxima());
        selected.set("a","value"); selected.remove("a"); assertEquals("direct",selected.get("a"));
    }
    @Test public void expandedEntryBoundRejectsGrowthButAllowsReplacementAndRemoval() throws Exception {
        Map<String,String> values=new HashMap<>(); for(int i=0;i<65536;i++) values.put("k"+i,"");
        MemoryBackend backend=new MemoryBackend(); backend.replace(values);
        BoundedStore store=new BoundedStore(backend,ResourceBudgets.maxima());
        error("QUOTA_EXCEEDED",()->store.set("extra","")); store.set("k0","x"); store.remove("k1"); store.set("extra","");
        assertEquals(65536,backend.read().size()); assertEquals("x",store.get("k0"));
    }
    @Test public void storagePagesAreByteBoundedCompleteAndLegacyListNeverTruncates() throws Exception {
        Map<String,String> values=new HashMap<>(); for(int i=0;i<6000;i++) values.put(String.format("k%05d",i)+repeat("x",90),"");
        MemoryBackend backend=new MemoryBackend(); backend.replace(values); BoundedStore store=new BoundedStore(backend,ResourceBudgets.maxima());
        error("TOO_LARGE",store::list); List<String> seen=new ArrayList<>(); String after=null; int pages=0;
        do {
            JSONObject page=store.listPage(after,StorageRequest.MAX_PAGE_KEYS); pages++;
            assertFalse(page.getBoolean("snapshotAcrossPages")); assertTrue(DatabaseRequest.bytes(page)<ResourceBudgets.BRIDGE_MESSAGE_BYTES-128);
            JSONArray keys=page.getJSONArray("keys"); assertTrue(keys.length()>0);
            for(int i=0;i<keys.length();i++) seen.add(keys.getString(i));
            if(!page.getBoolean("hasMore")) { assertTrue(page.isNull("nextAfterKey")); break; }
            after=page.getString("nextAfterKey"); assertEquals(keys.getString(keys.length()-1),after);
        } while(pages<4);
        assertEquals(2,pages); assertEquals(new ArrayList<>(new TreeSet<>(values.keySet())),seen);
        JSONObject empty=store.listPage(seen.get(seen.size()-1),1); assertEquals(0,empty.getJSONArray("keys").length()); assertFalse(empty.getBoolean("hasMore"));
        error("INVALID_ARGUMENT",()->store.listPage(null,0)); error("INVALID_ARGUMENT",()->store.listPage("bad key",1));
    }
    @Test public void storageEscapesAndOuterEnvelopeAreBoundedBeforeMutation() throws Exception {
        BoundedStore store=new BoundedStore(new MemoryBackend(),ResourceBudgets.maxima());
        String escaped=repeat("\u0001",90000); error("INVALID_ARGUMENT",()->store.set("large",escaped)); assertNull(store.get("large"));
        String accepted=repeat("\u0001",80000); store.set("large",accepted); assertEquals(accepted,store.get("large"));
        JSONObject args=new JSONObject().put("key",repeat("k",96)).put("value",repeat("x",StorageRequest.MAX_VALUE_BYTES-256));
        StorageRequest.validate("storage.set",args);
        JSONObject envelope=new JSONObject().put("v",1).put("id",repeat("i",64)).put("method","storage.set").put("args",args);
        assertTrue(DatabaseRequest.bytes(envelope)<=ResourceBudgets.BRIDGE_MESSAGE_BYTES);
        error("INVALID_ARGUMENT",()->StorageRequest.validate("storage.set",args.put("value",repeat("x",StorageRequest.MAX_VALUE_BYTES))));
        error("INVALID_ARGUMENT",()->StorageBackupFormat.create("test.storage",repeat("a",64),1,new JSONObject().put("large",repeat("x",65537))));
    }
    @Test public void storageValidationNamesTheFieldWithoutEchoingItsValue() throws Exception {
        try {
            StorageRequest.validate("storage.set", new JSONObject().put("key", "synthetic private key").put("value", "value"));
            fail("Expected INVALID_ARGUMENT");
        } catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertTrue(failure.getMessage().contains("storage.set.key"));
            assertFalse(failure.getMessage().contains("synthetic private key"));
        }
        try {
            StorageRequest.validate("storage.listPage", new JSONObject().put("limit", "synthetic private limit"));
            fail("Expected INVALID_ARGUMENT");
        } catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertTrue(failure.getMessage().contains("storage.listPage.limit"));
            assertFalse(failure.getMessage().contains("synthetic private limit"));
        }
    }
    @Test public void preferencesEditsDoNotClearUnknownNativeEntries() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); SharedPreferences raw=context.getSharedPreferences("expanded-storage-test",Context.MODE_PRIVATE);
        raw.edit().clear().putString("a","old").putString("other","retained").putInt("native",7).commit();
        BoundedStore store=new BoundedStore(new PreferencesBackend(raw),ResourceBudgets.maxima());
        assertEquals("old",store.get("a")); error("STORAGE_ERROR",()->store.set("a","new"));
        store.remove("a"); assertEquals(7,raw.getInt("native",0)); assertEquals("retained",raw.getString("other",null));
        raw.edit().remove("native").commit(); store.set("a",repeat("x",80000)); assertEquals("retained",raw.getString("other",null));
    }
    private PrivateDatabase ready(ResourceBudgets budgets) throws Exception {
        PrivateDatabase db=PrivateDatabase.preview(budgets); opened.add(db);
        call(db,"migrate",new JSONObject().put("fromVersion",0).put("toVersion",1).put("steps",new JSONArray().put(
                new JSONObject().put("kind","createTable").put("table","notes").put("columns",columns())))); return db;
    }
    private static JSONObject columns() throws Exception { return new JSONObject().put("title",new JSONObject().put("type","text").put("nullable",false)); }
    private static JSONObject call(PrivateDatabase db,String method,JSONObject args) throws Exception {
        return (JSONObject)db.execute("database."+method,args,db.ticket(),()->true);
    }
    private static JSONObject insert(String id,String value) throws Exception {
        return new JSONObject().put("kind","insert").put("table","notes").put("id",id).put("values",new JSONObject().put("title",value));
    }
    private static JSONObject tx(JSONArray operations) throws Exception { return new JSONObject().put("version",1).put("operations",operations); }
    private static JSONObject query(int limit) throws Exception { return new JSONObject().put("version",1).put("table","notes").put("limit",limit); }
    @Test public void expandedCellBatchAndPageAdmissionRemainIndependentOfLegacy() throws Exception {
        PrivateDatabase expanded=ready(ResourceBudgets.maxima()),legacy=ready(ResourceBudgets.legacy());
        JSONObject large=tx(new JSONArray().put(insert("large",repeat("x",20000)))); DatabaseRequest.validate("database.transact",large);
        error("QUOTA_EXCEEDED",()->call(legacy,"transact",large)); call(expanded,"transact",large);
        JSONArray operations=new JSONArray(); for(int i=0;i<150;i++) operations.put(insert(String.format("k%03d",i),"x"));
        JSONObject batch=tx(operations); error("QUOTA_EXCEEDED",()->call(legacy,"transact",batch)); call(expanded,"transact",batch);
        JSONObject page=call(expanded,"select",query(1000)); assertEquals(151,page.getJSONArray("rows").length()); assertFalse(page.getBoolean("hasMore"));
        assertEquals(0,call(legacy,"select",query(1000)).getJSONArray("rows").length());
    }
    @Test public void exactMaximumSerializedRowFitsAndBytePaginationAlwaysAdvances() throws Exception {
        PrivateDatabase db=ready(ResourceBudgets.maxima());
        int overhead=DatabaseRequest.bytes(new JSONObject().put("id","a").put("title",""));
        String value=repeat("x",DatabaseRequest.HARD_MAX_ROW_BYTES-overhead);
        call(db,"transact",tx(new JSONArray().put(insert("a",value)))); call(db,"transact",tx(new JSONArray().put(insert("b",value))));
        JSONObject first=call(db,"select",query(DatabaseRequest.HARD_MAX_SELECT_ROWS));
        assertEquals(1,first.getJSONArray("rows").length()); assertTrue(first.getBoolean("hasMore")); assertEquals("a",first.getString("nextAfterId"));
        assertTrue(DatabaseRequest.bytes(first)<=ResourceBudgets.DATABASE_RESPONSE_BYTES);
        JSONObject second=call(db,"select",query(DatabaseRequest.HARD_MAX_SELECT_ROWS).put("afterId","a"));
        assertEquals("b",second.getJSONArray("rows").getJSONObject(0).getString("id")); assertFalse(second.getBoolean("hasMore"));
        error("QUOTA_EXCEEDED",()->call(db,"transact",tx(new JSONArray().put(insert("c",value+"x")))));
        error("INVALID_ARGUMENT",()->call(db,"transact",tx(new JSONArray().put(insert("escaped",repeat("\u0001",60000))))));
    }
    @Test public void existingMaximumSchemaCanBeReadAfterMigration() throws Exception {
        PrivateDatabase db=PrivateDatabase.preview(ResourceBudgets.maxima()); opened.add(db);
        JSONArray steps=new JSONArray();
        for(int table=0;table<DatabaseRequest.HARD_MAX_TABLES;table++) {
            JSONObject columns=new JSONObject();
            for(int column=0;column<DatabaseRequest.HARD_MAX_COLUMNS;column++)
                columns.put("c"+column,new JSONObject().put("type","text").put("nullable",true));
            steps.put(new JSONObject().put("kind","createTable").put("table","table"+table).put("columns",columns));
        }
        call(db,"migrate",new JSONObject().put("fromVersion",0).put("toVersion",1).put("steps",steps));
        JSONObject schema=call(db,"info",new JSONObject()).getJSONObject("schema");
        assertEquals(16,schema.length()); assertEquals(32,schema.getJSONObject("table15").length());
    }
    @Test public void transactionCancellationStillRollsBackExpandedBatch() throws Exception {
        PrivateDatabase db=ready(ResourceBudgets.maxima()); JSONArray operations=new JSONArray();
        for(int i=0;i<150;i++) operations.put(insert("k"+i,"value")); AtomicInteger checks=new AtomicInteger();
        error("CANCELLED",()->db.execute("database.transact",tx(operations),db.ticket(),()->checks.incrementAndGet()<20));
        assertEquals(0,call(db,"select",query(1000)).getJSONArray("rows").length());
    }
    @Test public void backupCurrentPreimageStreamsExpandedRowsButArchiveFormatStaysV1() throws Exception {
        PrivateDatabase db=ready(ResourceBudgets.maxima()); String value=repeat("x",20000);
        call(db,"transact",tx(new JSONArray().put(insert("large",value))));
        JSONObject current=new JSONObject().put("id","large").put("title",value);
        DatabaseBackupFormat.normalizeCurrentRow(current,columns()); error("INVALID_ARGUMENT",()->DatabaseBackupFormat.normalizeRow(current,columns()));
        DatabaseBackupFormat.Archive archive=DatabaseBackupFormat.create("test.backup",repeat("a",64),1,new JSONObject().put("notes",columns()),
                new JSONObject().put("notes",new JSONArray().put(new JSONObject().put("id","replacement").put("title","small"))));
        Field field=PrivateDatabase.class.getDeclaredField("database"); field.setAccessible(true); SQLiteDatabase nativeDb=(SQLiteDatabase)field.get(db);
        nativeDb.beginTransaction(); try { for(int i=0;i<10001;i++) nativeDb.execSQL("INSERT INTO t_notes(id,c_title) VALUES (?,?)",new Object[]{"k"+i,"x"}); nativeDb.setTransactionSuccessful(); } finally { nativeDb.endTransaction(); }
        PrivateDatabase.Baseline baseline=db.backupBaseline(archive,db.ticket(),()->true); assertEquals(10002,baseline.counts().getInt("notes"));
        assertTrue(db.backupImport(archive,"replaceSelectedTables",baseline,db.ticket(),()->true).getBoolean("committed"));
        assertEquals("replacement",call(db,"select",query(1000)).getJSONArray("rows").getJSONObject(0).getString("id"));
    }
}
