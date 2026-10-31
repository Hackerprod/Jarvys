package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import static com.jarvys.factory.runtime.PrivateDatabaseTest.error;
import static com.jarvys.factory.runtime.DatabaseRequestTest.repeat;

/** Synthetic data only. Import review never holds a transaction across user/provider work. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class PrivateDatabaseBackupTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final List<PrivateDatabase> stores=new ArrayList<>();
    private static final String APP="test.backup", CERT=repeat("a",64);
    private static final List<String> NOTES=Collections.singletonList("notes");
    @After public void closeStores() { for(PrivateDatabase store:stores) store.close(); }
    private PrivateDatabase track(PrivateDatabase store) { stores.add(store); return store; }
    private PrivateDatabase preview() { return track(PrivateDatabase.preview()); }
    private Context context(File directory) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
    }
    private PrivateDatabase installed(Context context) throws Exception { return track(PrivateDatabase.installed(context,context.getPackageName())); }
    private static JSONObject columns() throws Exception { return new JSONObject().put("title",new JSONObject().put("type","text").put("nullable",false)); }
    private static JSONObject call(PrivateDatabase db,String method,JSONObject args) throws Exception {
        return (JSONObject)db.execute("database."+method,args,db.ticket(),()->true);
    }
    private static void schema(PrivateDatabase db,String... tables) throws Exception {
        JSONArray steps=new JSONArray(); for(String table:tables) steps.put(new JSONObject().put("kind","createTable").put("table",table).put("columns",columns()));
        call(db,"migrate",new JSONObject().put("fromVersion",0).put("toVersion",1).put("steps",steps));
    }
    private PrivateDatabase ready(String... tables) throws Exception { PrivateDatabase db=preview(); schema(db,tables); return db; }
    private static void change(PrivateDatabase db,String table,String kind,String id,String title) throws Exception {
        JSONObject op=new JSONObject().put("kind",kind).put("table",table).put("id",id);
        if(title!=null) op.put("values",new JSONObject().put("title",title));
        call(db,"transact",new JSONObject().put("version",1).put("operations",new JSONArray().put(op)));
    }
    private static JSONArray rows(PrivateDatabase db,String table) throws Exception {
        return call(db,"select",new JSONObject().put("version",1).put("table",table).put("limit",100)).getJSONArray("rows");
    }
    private static DatabaseBackupFormat.Archive export(PrivateDatabase db,String appId,List<String> tables) throws Exception {
        return db.backupExport(appId,CERT,1,tables,db.ticket(),()->true);
    }
    private static DatabaseBackupFormat.Archive incoming(String appId,String... ids) throws Exception {
        JSONArray rows=new JSONArray(); for(String id:ids) rows.put(new JSONObject().put("id",id).put("title","imported "+id));
        return DatabaseBackupFormat.create(appId,CERT,1,new JSONObject().put("notes",columns()),new JSONObject().put("notes",rows));
    }
    private static PrivateDatabase.Baseline baseline(PrivateDatabase db,DatabaseBackupFormat.Archive archive) throws Exception {
        return db.backupBaseline(archive,db.ticket(),()->true);
    }
    private static JSONObject restore(PrivateDatabase db,DatabaseBackupFormat.Archive archive,String mode,PrivateDatabase.Baseline baseline) throws Exception {
        return db.backupImport(archive,mode,baseline,db.ticket(),()->true);
    }
    private static SQLiteDatabase nativeConnection(PrivateDatabase db) throws Exception {
        java.lang.reflect.Field field=PrivateDatabase.class.getDeclaredField("database"); field.setAccessible(true); return (SQLiteDatabase)field.get(db);
    }
    private static long count(SQLiteDatabase db,String table) {
        try(Cursor cursor=db.rawQuery("SELECT count(*) FROM t_"+table,null)) { assertTrue(cursor.moveToFirst()); return cursor.getLong(0); }
    }
    private static void seed(File directory,String table,int count,String title) {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(directory,"factory-private-v1.sqlite").getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            db.beginTransaction(); try {
                for(int i=0;i<count;i++) db.execSQL("INSERT INTO t_"+table+"(id,c_title) VALUES (?,?)",new Object[]{"row"+i,title});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    @Test public void typedArchiveRoundTripsEmptyAndNonemptyTablesAndLeavesOthersAlone() throws Exception {
        PrivateDatabase source=ready("empty","notes","other"); change(source,"notes","insert","z","last"); change(source,"notes","insert","A","first");
        change(source,"other","insert","secret","unselected");
        DatabaseBackupFormat.Archive archive=export(source,APP,Arrays.asList("empty","notes"));
        assertEquals(0,archive.data().getJSONArray("empty").length()); assertFalse(archive.data().has("other"));
        assertEquals("A",archive.data().getJSONArray("notes").getJSONObject(0).getString("id"));
        PrivateDatabase target=ready("empty","notes","other"); change(target,"other","insert","keep","keep unselected");
        PrivateDatabase.Baseline baseline=baseline(target,archive);
        JSONObject result=restore(target,DatabaseBackupFormat.parse(archive.bytes()),"insertOnly",baseline);
        assertTrue(result.getBoolean("committed")); assertEquals(2,result.getJSONObject("counts").getInt("notes"));
        assertEquals(0,result.getJSONObject("counts").getInt("empty")); assertEquals(2,rows(target,"notes").length());
        assertEquals("keep unselected",rows(target,"other").getJSONObject(0).getString("title"));
        assertArrayEquals(archive.bytes(),export(target,APP,Arrays.asList("empty","notes")).bytes());
    }
    @Test public void selectedEditsInvalidateReviewButUnselectedEditsDoNot() throws Exception {
        PrivateDatabase target=ready("notes","other"); change(target,"notes","insert","old","original");
        DatabaseBackupFormat.Archive archive=incoming(APP,"new"); PrivateDatabase.Baseline before=baseline(target,archive);
        before.counts().put("notes",999); assertEquals(1,before.counts().getInt("notes"));
        change(target,"notes","update","old","changed after review");
        error("BACKUP_CONFLICT",()->restore(target,archive,"replaceSelectedTables",before));
        assertEquals("changed after review",rows(target,"notes").getJSONObject(0).getString("title"));
        PrivateDatabase.Baseline fresh=baseline(target,archive);
        assertNotEquals(before.preimageSha256,fresh.preimageSha256); assertEquals(before.schemaSha256,fresh.schemaSha256);
        change(target,"other","insert","unrelated","unrelated edit after review");
        assertEquals(fresh.preimageSha256,baseline(target,archive).preimageSha256);
        assertTrue(restore(target,archive,"replaceSelectedTables",fresh).getBoolean("committed"));
        assertEquals("new",rows(target,"notes").getJSONObject(0).getString("id")); assertEquals(1,rows(target,"other").length());
    }
    @Test public void insertOnlyCollisionAbortsEverythingEvenWhenValuesAreIdentical() throws Exception {
        PrivateDatabase target=ready("notes"); change(target,"notes","insert","z","imported z");
        DatabaseBackupFormat.Archive archive=incoming(APP,"a","z");
        error("BACKUP_CONFLICT",()->restore(target,archive,"insertOnly",baseline(target,archive)));
        assertEquals(1,rows(target,"notes").length()); assertEquals("z",rows(target,"notes").getJSONObject(0).getString("id"));
        assertTrue(restore(target,archive,"replaceSelectedTables",baseline(target,archive)).getBoolean("committed"));
        assertEquals(2,rows(target,"notes").length());
    }
    @Test public void baselineIsBoundToExactArchiveAndRejectsVersionAndSchemaChanges() throws Exception {
        PrivateDatabase target=ready("notes"); DatabaseBackupFormat.Archive first=incoming(APP,"first"), other=incoming(APP,"other");
        PrivateDatabase.Baseline baseline=baseline(target,first);
        error("BACKUP_CONFLICT",()->restore(target,other,"insertOnly",baseline));
        error("BACKUP_CONFLICT",()->restore(target,first,"insertOnly",null));
        JSONObject wrong=new JSONObject().put("notes",new JSONObject().put("different",new JSONObject().put("type","text").put("nullable",true)));
        DatabaseBackupFormat.Archive mismatched=DatabaseBackupFormat.create(APP,CERT,1,wrong,new JSONObject().put("notes",new JSONArray()));
        error("SCHEMA_MISMATCH",()->baseline(target,mismatched));
        error("SCHEMA_MISMATCH",()->target.backupExport(APP,CERT,1,Collections.singletonList("absent"),target.ticket(),()->true));
        call(target,"migrate",new JSONObject().put("fromVersion",1).put("toVersion",2).put("steps",new JSONArray().put(new JSONObject().put("kind","addColumn")
                .put("table","notes").put("column","extra").put("definition",new JSONObject().put("type","text").put("nullable",true)))));
        error("VERSION_CONFLICT",()->restore(target,first,"insertOnly",baseline));
        error("VERSION_CONFLICT",()->target.backupExport(APP,CERT,1,NOTES,target.ticket(),()->true));
    }
    @Test public void baselineSurvivesCloseAndFreshInstalledConnectionAndImportPersists() throws Exception {
        Context context=context(temporary.newFolder()); PrivateDatabase first=installed(context); schema(first,"notes");
        change(first,"notes","insert","old","original"); DatabaseBackupFormat.Archive archive=incoming(context.getPackageName(),"new");
        PrivateDatabase.Baseline baseline=baseline(first,archive); first.cancel(); first.close();
        PrivateDatabase fresh=installed(context); assertTrue(restore(fresh,archive,"replaceSelectedTables",baseline).getBoolean("committed")); fresh.close();
        PrivateDatabase reopened=installed(context); assertEquals("new",rows(reopened,"notes").getJSONObject(0).getString("id"));
        error("SCHEMA_MISMATCH",()->baseline(reopened,incoming("different.app","x")));
    }
    @Test public void largeTargetPreimageStreamsDespiteExceedingArchiveLimitAndCanBeReplacedWithEmpty() throws Exception {
        File directory=temporary.newFolder(); Context context=context(directory); PrivateDatabase target=installed(context); schema(target,"notes"); target.close();
        seed(directory,"notes",300,repeat("x",4096));
        DatabaseBackupFormat.Archive empty=incoming(context.getPackageName());
        PrivateDatabase.Baseline baseline=baseline(target,empty); assertEquals(300,baseline.counts().getInt("notes"));
        error("TOO_LARGE",()->export(target,context.getPackageName(),NOTES));
        assertTrue(restore(target,empty,"replaceSelectedTables",baseline).getBoolean("committed")); assertEquals(0,rows(target,"notes").length());
    }
    @Test public void globalRowsQuotaIncludesUnselectedTablesAndRollsBackWrites() throws Exception {
        File directory=temporary.newFolder(); Context context=context(directory); PrivateDatabase target=installed(context); schema(target,"notes","other"); target.close();
        seed(directory,"other",10000,"unselected"); DatabaseBackupFormat.Archive archive=incoming(context.getPackageName(),"new");
        PrivateDatabase.Baseline baseline=baseline(target,archive);
        error("QUOTA_EXCEEDED",()->restore(target,archive,"insertOnly",baseline));
        assertEquals(0,rows(target,"notes").length()); assertEquals(10000,count(nativeConnection(target),"other"));
    }
    @Test public void diskFullDuringReplacementRollsBackDeletedAndInsertedRows() throws Exception {
        PrivateDatabase target=ready("notes"); change(target,"notes","insert","old","keep me");
        JSONArray data=new JSONArray(); for(int i=0;i<50;i++) data.put(new JSONObject().put("id","new"+i).put("title",repeat("x",4096)));
        DatabaseBackupFormat.Archive archive=DatabaseBackupFormat.create(APP,CERT,1,new JSONObject().put("notes",columns()),new JSONObject().put("notes",data));
        PrivateDatabase.Baseline baseline=baseline(target,archive); SQLiteDatabase nativeDb=nativeConnection(target); long pages;
        try(Cursor cursor=nativeDb.rawQuery("PRAGMA page_count",null)) { assertTrue(cursor.moveToFirst()); pages=cursor.getLong(0); }
        nativeDb.setMaximumSize(pages*nativeDb.getPageSize());
        error("QUOTA_EXCEEDED",()->restore(target,archive,"replaceSelectedTables",baseline));
        assertEquals(1,rows(target,"notes").length()); assertEquals("keep me",rows(target,"notes").getJSONObject(0).getString("title"));
    }
    @Test public void cancellationAtLastAuthorityCheckRollsBackReplacementAndNeverClaimsPriorCommitUndo() throws Exception {
        PrivateDatabase target=ready("notes"); change(target,"notes","insert","old","keep me");
        DatabaseBackupFormat.Archive archive=incoming(APP,"new1","new2"); PrivateDatabase.Baseline baseline=baseline(target,archive);
        SQLiteDatabase nativeDb=nativeConnection(target); AtomicBoolean cancelled=new AtomicBoolean();
        FactoryException failure=error("CANCELLED",()->target.backupImport(archive,"replaceSelectedTables",baseline,target.ticket(),()-> {
            try(Cursor cursor=nativeDb.rawQuery("SELECT count(*) FROM t_notes WHERE id IN ('new1','new2')",null)) {
                if(cursor.moveToFirst() && cursor.getInt(0)==2) { cancelled.set(true); target.cancel(); }
            }
            return true;
        }));
        assertTrue(cancelled.get()); assertTrue(failure.getMessage().contains("prior commit"));
        assertEquals(1,rows(target,"notes").length()); assertEquals("old",rows(target,"notes").getJSONObject(0).getString("id"));
        long stale=target.ticket(); target.cancel();
        error("CANCELLED",()->target.backupExport(APP,CERT,1,NOTES,stale,()->true));
        error("CANCELLED",()->target.backupBaseline(archive,target.ticket(),()->false));
    }
    @Test public void endTransactionFailureCannotProduceFalseSuccessOrPromiseRollback() throws Exception {
        AtomicBoolean failEnd=new AtomicBoolean();
        PrivateDatabase target=track(PrivateDatabase.previewForBackupTest(db->{
            db.endTransaction(); if(failEnd.get()) throw new SQLiteException("synthetic private value or path");
        }));
        schema(target,"notes"); DatabaseBackupFormat.Archive archive=incoming(APP,"new"); PrivateDatabase.Baseline baseline=baseline(target,archive);
        failEnd.set(true); FactoryException failure=error("DATABASE_ERROR",()->restore(target,archive,"insertOnly",baseline));
        assertTrue(failure.getMessage().contains("unknown")); assertFalse(failure.getMessage().contains("synthetic"));
        assertFalse(failure.getMessage().contains("rolled back")); assertEquals(1,rows(target,"notes").length());
    }
    @Test public void exportUsesOneSnapshotAcrossMoreThanOneSelectPage() throws Exception {
        File directory=temporary.newFolder(); Context context=context(directory); PrivateDatabase source=installed(context); schema(source,"notes"); source.close();
        seed(directory,"notes",200,"original"); call(source,"info",new JSONObject());
        AtomicInteger checks=new AtomicInteger(); AtomicReference<Throwable> writerError=new AtomicReference<>();
        CountDownLatch started=new CountDownLatch(1); AtomicReference<Thread> writer=new AtomicReference<>();
        SQLiteDatabase snapshotConnection=nativeConnection(source);
        // Connection setup performs journal-mode PRAGMAs; complete it before the read snapshot starts.
        // The competing INSERT itself still starts while export owns the transaction.
        try(SQLiteDatabase concurrent=SQLiteDatabase.openDatabase(new File(directory,"factory-private-v1.sqlite").getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            concurrent.disableWriteAheadLogging();
            DatabaseBackupFormat.Archive archive=source.backupExport(context.getPackageName(),CERT,1,NOTES,source.ticket(),()-> {
                if(checks.incrementAndGet()==4) {
                    assertTrue(snapshotConnection.inTransaction());
                    Thread thread=new Thread(()-> {
                        started.countDown();
                        try { concurrent.execSQL("INSERT INTO t_notes(id,c_title) VALUES ('later','after snapshot')"); }
                        catch(Throwable failure) { writerError.set(failure); }
                    }); writer.set(thread); thread.start();
                    try { assertTrue(started.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                }
                return true;
            });
            assertNotNull(writer.get()); writer.get().join(10000); assertFalse(writer.get().isAlive());
            if(writerError.get()!=null) throw new AssertionError(writerError.get());
            assertEquals(200,archive.data().getJSONArray("notes").length()); assertEquals(201,count(snapshotConnection,"notes"));
            assertEquals(201,export(source,context.getPackageName(),NOTES).data().getJSONArray("notes").length());
        }
    }
}
