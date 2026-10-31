package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class PrivateDatabaseResourceBudgetsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final List<PrivateDatabase> opened=new ArrayList<>();
    @After public void close() { for(PrivateDatabase db:opened) db.close(); }
    private PrivateDatabase preview(ResourceBudgets budget) { PrivateDatabase db=PrivateDatabase.preview(budget); opened.add(db); return db; }
    private PrivateDatabase installed(Context context,ResourceBudgets budget) throws Exception {
        PrivateDatabase db=PrivateDatabase.installed(context,context.getPackageName(),budget); opened.add(db); return db;
    }
    private Context context(File directory) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
    }
    private static JSONObject call(PrivateDatabase db,String method,JSONObject args) throws Exception {
        return (JSONObject)db.execute("database."+method,args,db.ticket(),()->true);
    }
    private static JSONObject columns(int count) throws Exception {
        JSONObject columns=new JSONObject();
        for(int i=0;i<count;i++) columns.put("c"+i,new JSONObject().put("type","text").put("nullable",true));
        return columns;
    }
    private static JSONObject create(String table,int count) throws Exception {
        return new JSONObject().put("kind","createTable").put("table",table).put("columns",columns(count));
    }
    private static JSONObject migrate(PrivateDatabase db,int version,JSONObject... steps) throws Exception {
        return call(db,"migrate",new JSONObject().put("fromVersion",version).put("toVersion",version+1).put("steps",new JSONArray(steps)));
    }
    private static JSONObject op(String kind,String id,String value) throws Exception {
        JSONObject op=new JSONObject().put("kind",kind).put("table","notes").put("id",id);
        if(value!=null) op.put("values",new JSONObject().put("c0",value)); return op;
    }
    private static JSONObject tx(PrivateDatabase db,int version,JSONObject... operations) throws Exception {
        return call(db,"transact",new JSONObject().put("version",version).put("operations",new JSONArray(operations)));
    }
    private static JSONObject query(int version) throws Exception { return new JSONObject().put("version",version).put("table","notes"); }
    private static void quota(DataResourceBudgetsTest.Checked work) throws Exception {
        try { work.run(); fail("Expected quota"); } catch(FactoryException failure) { assertEquals("QUOTA_EXCEEDED",failure.code); }
    }
    @Test public void expandedTablesAndColumnsUseHardGrammarWithSeparateLegacyAdmission() throws Exception {
        ResourceBudgets large=DataResourceBudgetsTest.budget("database","tables",9,"columnsPerTable",32);
        PrivateDatabase expanded=preview(large), legacy=preview(ResourceBudgets.legacy());
        JSONObject[] steps=new JSONObject[9];
        for(int i=0;i<9;i++) steps[i]=create("table"+i,32);
        JSONObject request=new JSONObject().put("fromVersion",0).put("toVersion",1).put("steps",new JSONArray(steps));
        DatabaseRequest.validate("database.migrate",request);
        quota(()->call(legacy,"migrate",request));
        assertEquals(0,call(legacy,"info",new JSONObject()).getInt("version"));
        assertTrue(call(expanded,"migrate",request).getBoolean("committed"));
        JSONObject info=call(expanded,"info",new JSONObject());
        assertEquals(9,info.getJSONObject("schema").length());
        assertEquals(32,info.getJSONObject("schema").getJSONObject("table0").length());
        quota(()->migrate(expanded,1,create("overflow",1)));
        assertEquals(1,call(expanded,"info",new JSONObject()).getInt("version"));
    }
    @Test public void loweredArtifactPreservesReadsDeletesAndShrinkingWritesAcrossReopen() throws Exception {
        File directory=temporary.newFolder("lowered"); Context context=context(directory);
        PrivateDatabase initial=installed(context,ResourceBudgets.legacy()); migrate(initial,0,create("notes",2));
        String original=DatabaseRequestTest.repeat("x",200);
        tx(initial,1,op("insert","a",original),op("insert","b",original),op("insert","c",original)); initial.close();
        ResourceBudgets small=DataResourceBudgetsTest.budget("database","rows",1L,"tables",1,"columnsPerTable",1,
                "mainFileBytes",4096L,"cellBytes",2,"rowBytes",50,"selectPageRows",1);
        PrivateDatabase lower=installed(context,small);
        JSONObject first=call(lower,"select",query(1).put("limit",100));
        assertEquals(1,first.getJSONArray("rows").length()); assertEquals(original,first.getJSONArray("rows").getJSONObject(0).getString("c0"));
        assertTrue(first.getBoolean("hasMore")); assertEquals("a",first.getString("nextAfterId"));
        JSONObject next=call(lower,"select",query(1).put("afterId","a").put("limit",100));
        assertEquals("b",next.getJSONArray("rows").getJSONObject(0).getString("id"));
        tx(lower,1,op("update","a",DatabaseRequestTest.repeat("x",100))); // Still above row/cell limits, but shrinking.
        quota(()->tx(lower,1,op("update","a",DatabaseRequestTest.repeat("x",101))));
        quota(()->tx(lower,1,op("insert","d","x")));
        tx(lower,1,op("delete","b",null)); tx(lower,1,op("delete","c",null));
        assertFalse(call(lower,"select",query(1)).getBoolean("hasMore"));
        lower.close(); PrivateDatabase reopened=installed(context,small);
        assertEquals(100,call(reopened,"select",query(1)).getJSONArray("rows").getJSONObject(0).getString("c0").length());
        tx(reopened,1,op("delete","a",null)); assertEquals(0,call(reopened,"select",query(1)).getJSONArray("rows").length());
        assertTrue(new File(directory,"factory-private-v1.sqlite").isFile());
    }
    @Test public void expandedRowAndMainFileBudgetsAreAppliedByNativeCommit() throws Exception {
        File directory=temporary.newFolder("expanded"); Context context=context(directory);
        ResourceBudgets large=DataResourceBudgetsTest.budget("database","rows",10001L,"mainFileBytes",32L*1024*1024);
        PrivateDatabase db=installed(context,large); migrate(db,0,create("notes",1)); db.close();
        try(SQLiteDatabase raw=SQLiteDatabase.openDatabase(new File(directory,"factory-private-v1.sqlite").getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            raw.beginTransaction();
            try {
                for(int i=0;i<10000;i++) raw.execSQL("INSERT INTO t_notes(id,c_c0) VALUES (?,?)",new Object[]{"id"+i,"x"});
                raw.setTransactionSuccessful();
            } finally { raw.endTransaction(); }
        }
        assertTrue(tx(db,1,op("insert","last","x")).getBoolean("committed"));
        quota(()->tx(db,1,op("insert","overflow","x")));
        Field connection=PrivateDatabase.class.getDeclaredField("database"); connection.setAccessible(true);
        SQLiteDatabase raw=(SQLiteDatabase)connection.get(db);
        assertEquals(32L*1024*1024,raw.getMaximumSize());
        try(Cursor count=raw.rawQuery("SELECT count(*) FROM t_notes",null)) { assertTrue(count.moveToFirst()); assertEquals(10001,count.getLong(0)); }
        JSONObject limits=call(db,"info",new JSONObject()).getJSONObject("limits");
        assertEquals(10001,limits.getLong("rows")); assertEquals(32L*1024*1024,limits.getLong("mainDatabaseBytes"));
    }
    @Test public void transactionBatchBudgetIsIndependentOfPureWireValidation() throws Exception {
        PrivateDatabase db=preview(DataResourceBudgetsTest.budget("database","batchOperations",1)); migrate(db,0,create("notes",1));
        JSONObject args=new JSONObject().put("version",1).put("operations",new JSONArray().put(op("insert","a","one")).put(op("insert","b","two")));
        DatabaseRequest.validate("database.transact",args);
        quota(()->call(db,"transact",args)); assertEquals(0,call(db,"select",query(1)).getJSONArray("rows").length());
        tx(db,1,op("insert","a","one")); assertEquals(1,call(db,"select",query(1)).getJSONArray("rows").length());
    }
}
