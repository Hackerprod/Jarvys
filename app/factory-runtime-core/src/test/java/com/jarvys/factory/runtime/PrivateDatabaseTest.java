package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/** All files and records in this suite are isolated, synthetic test data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class PrivateDatabaseTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final List<PrivateDatabase> stores = new ArrayList<>();
    interface Checked { void run() throws Exception; }
    static FactoryException error(String code, Checked work) throws Exception {
        try { work.run(); fail("Expected " + code); return null; }
        catch (FactoryException e) { assertEquals(code, e.code); return e; }
    }
    @After public void closeStores() { for (PrivateDatabase store : stores) store.close(); }
    private PrivateDatabase preview() { PrivateDatabase db = PrivateDatabase.preview(); stores.add(db); return db; }
    private PrivateDatabase installed(Context context) throws Exception {
        PrivateDatabase db = PrivateDatabase.installed(context, context.getPackageName()); stores.add(db); return db;
    }
    private Context context(File directory) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
    }
    private static JSONObject def(String type, boolean nullable) throws Exception {
        return new JSONObject().put("type", type).put("nullable", nullable);
    }
    private static JSONObject create(String table, JSONObject columns) throws Exception {
        return new JSONObject().put("kind", "createTable").put("table", table).put("columns", columns);
    }
    private static JSONObject titleColumns() throws Exception { return new JSONObject().put("title", def("text", false)); }
    private static JSONObject call(PrivateDatabase db, String method, JSONObject args) throws Exception {
        return (JSONObject) db.execute("database." + method, args, db.ticket(), () -> true);
    }
    private static JSONObject migrate(PrivateDatabase db, int from, JSONObject... steps) throws Exception {
        return call(db, "migrate", new JSONObject().put("fromVersion", from).put("toVersion", from + 1).put("steps", new JSONArray(steps)));
    }
    private static JSONObject op(String kind, String id, JSONObject values) throws Exception {
        JSONObject result = new JSONObject().put("kind", kind).put("table", "notes").put("id", id);
        if (values != null) result.put("values", values);
        return result;
    }
    private static JSONObject value(String title) throws Exception { return new JSONObject().put("title", title); }
    private static JSONObject txArgs(int version, JSONObject... operations) throws Exception {
        return new JSONObject().put("version", version).put("operations", new JSONArray(operations));
    }
    private static JSONObject tx(PrivateDatabase db, int version, JSONObject... operations) throws Exception {
        return call(db, "transact", txArgs(version, operations));
    }
    private static JSONObject query(int version) throws Exception { return new JSONObject().put("version", version).put("table", "notes"); }
    private static JSONArray rows(PrivateDatabase db, int version) throws Exception { return call(db, "select", query(version)).getJSONArray("rows"); }
    private PrivateDatabase ready() throws Exception {
        PrivateDatabase db = preview(); migrate(db, 0, create("notes", titleColumns())); return db;
    }
    @Test public void infoAccuratelyDescribesPreviewAndLimits() throws Exception {
        JSONObject info = call(preview(), "info", new JSONObject());
        assertEquals(0, info.getInt("version")); assertEquals(0, info.getJSONObject("schema").length());
        assertEquals("preview", info.getString("mode")); assertFalse(info.getBoolean("persistent"));
        assertFalse(info.getBoolean("encrypted")); assertFalse(info.getBoolean("backupIncluded"));
        JSONObject limits = info.getJSONObject("limits");
        assertEquals(DatabaseRequest.MAX_DATABASE_BYTES, limits.getLong("mainDatabaseBytes"));
        assertFalse(limits.getBoolean("totalDiskHardLimit"));
        assertEquals(100, limits.getInt("selectRows")); assertEquals(64, limits.getInt("operations"));
    }
    @Test public void typedCrudRoundTripsWithoutCoercionOrSqlInjection() throws Exception {
        PrivateDatabase db = preview();
        JSONObject columns = titleColumns().put("count", def("integer", false)).put("score", def("real", false))
                .put("active", def("boolean", false)).put("optional", def("text", true));
        migrate(db, 0, create("notes", columns));
        String payload = "日本語 🌱 '; DROP TABLE t_notes; --";
        JSONObject values = value(payload).put("count", DatabaseRequest.MAX_SAFE_INTEGER).put("score", 1.25).put("active", true);
        assertTrue(tx(db, 1, op("insert", "one", values)).getBoolean("committed"));
        JSONObject row = rows(db, 1).getJSONObject(0);
        assertEquals(payload, row.getString("title")); assertEquals(DatabaseRequest.MAX_SAFE_INTEGER, row.getLong("count"));
        assertEquals(1.25, row.getDouble("score"), 0); assertEquals(Boolean.TRUE, row.get("active")); assertTrue(row.isNull("optional"));
        tx(db, 1, op("update", "one", new JSONObject().put("active", false).put("count", -DatabaseRequest.MAX_SAFE_INTEGER).put("optional", "present")));
        row = rows(db, 1).getJSONObject(0);
        assertEquals(Boolean.FALSE, row.get("active")); assertEquals(-DatabaseRequest.MAX_SAFE_INTEGER, row.getLong("count"));
        assertEquals("present", row.getString("optional")); assertEquals(payload, row.getString("title"));
        tx(db, 1, op("update", "one", new JSONObject().put("optional", JSONObject.NULL)));
        assertTrue(rows(db, 1).getJSONObject(0).isNull("optional"));
        assertEquals(1, tx(db, 1, op("delete", "one", null)).getJSONArray("changes").getInt(0));
        assertEquals(0, tx(db, 1, op("delete", "one", null)).getJSONArray("changes").getInt(0));
        assertEquals(0, rows(db, 1).length());
    }
    @Test public void equalityAndBinaryKeysetPagination() throws Exception {
        PrivateDatabase db = preview();
        migrate(db, 0, create("notes", titleColumns().put("active", def("boolean", true)).put("count", def("integer", true))));
        tx(db, 1, op("insert", "z", value("same").put("active", true).put("count", 2)),
                op("insert", "A", value("same").put("active", true).put("count", 2)),
                op("insert", "a", value("different").put("active", false)), op("insert", "b", value("same").put("active", true).put("count", 2)));
        JSONObject first = call(db, "select", query(1).put("where", new JSONObject().put("title", "same").put("active", true).put("count", 2)).put("limit", 2));
        assertEquals("A", first.getJSONArray("rows").getJSONObject(0).getString("id"));
        assertEquals("b", first.getJSONArray("rows").getJSONObject(1).getString("id"));
        assertTrue(first.getBoolean("hasMore")); assertEquals("b", first.getString("nextAfterId")); assertFalse(first.getBoolean("snapshotAcrossPages"));
        JSONObject next = call(db, "select", query(1).put("afterId", first.getString("nextAfterId")).put("limit", 2));
        assertEquals(1, next.getJSONArray("rows").length()); assertEquals("z", next.getJSONArray("rows").getJSONObject(0).getString("id"));
        assertFalse(next.getBoolean("hasMore")); assertTrue(next.isNull("nextAfterId"));
        JSONObject nulls = call(db, "select", query(1).put("where", new JSONObject().put("count", JSONObject.NULL)));
        assertEquals("a", nulls.getJSONArray("rows").getJSONObject(0).getString("id"));
        assertEquals(1, nulls.getJSONArray("rows").length());
        assertEquals(0, call(db, "select", query(1).put("where", value("' OR 1=1 --"))).getJSONArray("rows").length());
        error("INVALID_ARGUMENT", () -> call(db, "select", query(1).put("where", new JSONObject().put("active", 1))));
        error("INVALID_ARGUMENT", () -> call(db, "select", query(1).put("where", new JSONObject().put("missing", "x"))));
    }
    @Test public void selectCanLookupExactImplicitIdWithBoundParameter() throws Exception {
        PrivateDatabase db=ready(); tx(db,1,op("insert","one",value("first")),op("insert","two",value("second")));
        JSONObject result=call(db,"select",query(1).put("where",new JSONObject().put("id","two")));
        assertEquals(1,result.getJSONArray("rows").length()); assertEquals("second",result.getJSONArray("rows").getJSONObject(0).getString("title"));
        error("INVALID_ARGUMENT",()->call(db,"select",query(1).put("where",new JSONObject().put("id",JSONObject.NULL))));
    }
    @Test public void duplicateFailureRollsBackWholeBatchAndSanitizesNativeError() throws Exception {
        PrivateDatabase db = ready(); tx(db, 1, op("insert", "existing", value("original")));
        FactoryException failure = error("DATABASE_ERROR", () -> tx(db, 1, op("update", "existing", value("secret-synthetic-value")),
                op("insert", "new", value("new")), op("insert", "existing", value("duplicate"))));
        assertFalse(failure.getMessage().contains("secret-synthetic-value")); assertFalse(failure.getMessage().contains("t_notes"));
        assertEquals(1, rows(db, 1).length()); assertEquals("original", rows(db, 1).getJSONObject(0).getString("title"));
    }
    @Test public void missingUpdateAndInvalidTypedValueRollBackEarlierWrites() throws Exception {
        PrivateDatabase db = ready();
        error("NOT_FOUND", () -> tx(db, 1, op("insert", "one", value("valid")), op("update", "absent", value("missing"))));
        assertEquals(0, rows(db, 1).length());
        error("INVALID_ARGUMENT", () -> tx(db, 1, op("insert", "one", value("valid")), op("insert", "two", new JSONObject().put("title", true))));
        error("INVALID_ARGUMENT", () -> tx(db, 1, op("insert", "one", new JSONObject().put("title", JSONObject.NULL))));
        error("INVALID_ARGUMENT", () -> tx(db, 1, op("insert", "one", new JSONObject().put("unknown", "x"))));
        assertEquals(0, rows(db, 1).length());
    }
    @Test public void addNullableColumnPreservesRowsAndPublishesVersionAndMetadata() throws Exception {
        PrivateDatabase db = ready(); tx(db, 1, op("insert", "one", value("kept")));
        JSONObject add = new JSONObject().put("kind", "addColumn").put("table", "notes").put("column", "extra").put("definition", def("boolean", true));
        assertEquals(2, migrate(db, 1, add).getInt("version"));
        assertTrue(rows(db, 2).getJSONObject(0).isNull("extra"));
        JSONObject info = call(db, "info", new JSONObject());
        assertEquals(2, info.getInt("version")); assertEquals("boolean", info.getJSONObject("schema").getJSONObject("notes").getJSONObject("extra").getString("type"));
        tx(db, 2, op("update", "one", new JSONObject().put("extra", true)));
        assertTrue(rows(db, 2).getJSONObject(0).getBoolean("extra"));
        error("VERSION_CONFLICT", () -> rows(db, 1));
        error("VERSION_CONFLICT", () -> tx(db, 1, op("delete", "one", null)));
        error("VERSION_CONFLICT", () -> migrate(db, 1, create("other", titleColumns())));
    }
    @Test public void failedMigrationRollsBackDdlMetadataAndVersion() throws Exception {
        PrivateDatabase db = ready();
        error("INVALID_ARGUMENT", () -> migrate(db, 1, create("second", titleColumns()), create("notes", titleColumns())));
        JSONObject info = call(db, "info", new JSONObject());
        assertEquals(1, info.getInt("version")); assertFalse(info.getJSONObject("schema").has("second"));
        // Retrying the table creation proves SQLite DDL rolled back, not just JSON metadata.
        migrate(db, 1, create("second", titleColumns()));
        JSONObject add = new JSONObject().put("kind", "addColumn").put("table", "notes").put("column", "extra").put("definition", def("text", true));
        error("INVALID_ARGUMENT", () -> migrate(db, 2, add, add));
        assertEquals(2, call(db, "info", new JSONObject()).getInt("version"));
        assertFalse(call(db, "info", new JSONObject()).getJSONObject("schema").getJSONObject("notes").has("extra"));
        migrate(db, 2, add);
    }
    @Test public void tableQuotaRollbackLeavesNoExtraNativeTable() throws Exception {
        PrivateDatabase db = preview();
        JSONObject[] steps = new JSONObject[8];
        for (int i = 0; i < steps.length; i++) steps[i] = create("table" + i, titleColumns());
        migrate(db, 0, steps);
        error("INVALID_ARGUMENT", () -> migrate(db, 1, create("ninth", titleColumns())));
        assertEquals(1, call(db, "info", new JSONObject()).getInt("version"));
        assertEquals(8, call(db, "info", new JSONObject()).getJSONObject("schema").length());
    }
    @Test public void oversizedResultFailsWithoutReturningPartialRowsAndSmallerPageWorks() throws Exception {
        PrivateDatabase db = ready(); String text = DatabaseRequestTest.repeat("x", 4096);
        for (int batch = 0; batch < 2; batch++) {
            JSONObject[] operations = new JSONObject[35];
            for (int i = 0; i < operations.length; i++) operations[i] = op("insert", "id" + (batch * 35 + i), value(text));
            tx(db, 1, operations);
        }
        error("QUOTA_EXCEEDED", () -> call(db, "select", query(1).put("limit", 100)));
        JSONObject result = call(db, "select", query(1).put("limit", 10));
        assertEquals(10, result.getJSONArray("rows").length()); assertTrue(result.getBoolean("hasMore"));
        assertTrue(DatabaseRequest.bytes(result) <= DatabaseRequest.MAX_RESULT_BYTES);
    }
    @Test public void mergedRowQuotaRollsBackUpdate() throws Exception {
        PrivateDatabase db = preview(); JSONObject columns = new JSONObject();
        for (int i = 0; i < 4; i++) columns.put("part" + i, def("text", true));
        migrate(db, 0, create("notes", columns));
        JSONObject values = new JSONObject(); String large = DatabaseRequestTest.repeat("x", 4096);
        for (int i = 0; i < 3; i++) values.put("part" + i, large);
        tx(db, 1, op("insert", "one", values));
        error("QUOTA_EXCEEDED", () -> tx(db, 1, op("update", "one", new JSONObject().put("part3", large))));
        assertTrue(rows(db, 1).getJSONObject(0).isNull("part3"));
    }
    @Test public void staleTicketAndFalseAuthorityCannotRun() throws Exception {
        PrivateDatabase db = ready(); long stale = db.ticket(); db.cancel();
        error("CANCELLED", () -> db.execute("database.info", new JSONObject(), stale, () -> true));
        error("CANCELLED", () -> db.execute("database.info", new JSONObject(), db.ticket(), () -> false));
        assertEquals(1, call(db, "info", new JSONObject()).getInt("version"));
        JSONObject cancellation = call(db, "cancel", new JSONObject());
        assertTrue(cancellation.getBoolean("cancelRequested")); assertFalse(cancellation.getBoolean("rollbackConfirmed"));
    }
    @Test public void authorityRevokedBetweenOperationsRollsBackBatch() throws Exception {
        PrivateDatabase db = ready(); AtomicInteger checks = new AtomicInteger();
        error("CANCELLED", () -> db.execute("database.transact", txArgs(1, op("insert", "one", value("first")), op("insert", "two", value("second"))),
                db.ticket(), () -> checks.incrementAndGet() < 5));
        assertEquals(5, checks.get()); assertEquals(0, rows(db, 1).length());
    }
    @Test public void epochCancelledDuringBatchRollsBackAndCanResumeWithNewTicket() throws Exception {
        PrivateDatabase db = ready(); AtomicInteger checks = new AtomicInteger();
        error("CANCELLED", () -> db.execute("database.transact", txArgs(1, op("insert", "one", value("first")), op("insert", "two", value("second")), op("insert", "three", value("third"))),
                db.ticket(), () -> { if (checks.incrementAndGet() == 5) db.cancel(); return true; }));
        assertEquals(0, rows(db, 1).length());
        tx(db, 1, op("insert", "retry", value("new authority"))); assertEquals(1, rows(db, 1).length());
    }
    @Test public void migrationCancellationRollsBackDdlAndVersion() throws Exception {
        PrivateDatabase db = preview(); AtomicInteger checks = new AtomicInteger();
        JSONObject args = new JSONObject().put("fromVersion", 0).put("toVersion", 1)
                .put("steps", new JSONArray().put(create("notes", titleColumns())).put(create("second", titleColumns())));
        error("CANCELLED", () -> db.execute("database.migrate", args, db.ticket(), () -> checks.incrementAndGet() < 5));
        assertEquals(0, call(db, "info", new JSONObject()).getInt("version"));
        assertEquals(0, call(db, "info", new JSONObject()).getJSONObject("schema").length());
        migrate(db, 0, create("notes", titleColumns()));
    }
    @Test public void closePreviewDiscardsDataAndOtherPreviewInstancesAreIsolated() throws Exception {
        PrivateDatabase db = ready(); tx(db, 1, op("insert", "one", value("ephemeral")));
        assertEquals(0, call(preview(), "info", new JSONObject()).getInt("version"));
        JSONObject result = call(db, "close", new JSONObject()); assertTrue(result.getBoolean("closed")); assertFalse(result.getBoolean("dataRetained"));
        assertEquals(0, call(db, "info", new JSONObject()).getInt("version"));
        migrate(db, 0, create("notes", titleColumns())); assertEquals(0, rows(db, 1).length());
        db.close(); db.close(); assertEquals(0, call(db, "info", new JSONObject()).getInt("version"));
    }
    @Test public void installedUsesFixedNoBackupLocationAndPersistsAcrossInstances() throws Exception {
        File directory = temporary.newFolder("no-backup"); Context context = context(directory); PrivateDatabase db = installed(context);
        migrate(db, 0, create("notes", titleColumns())); tx(db, 1, op("insert", "one", value("persisted")));
        JSONObject info = call(db, "info", new JSONObject());
        assertEquals("installed", info.getString("mode")); assertTrue(info.getBoolean("persistent")); assertFalse(info.getBoolean("backupIncluded"));
        assertTrue(new File(directory, "factory-private-v1.sqlite").isFile());
        assertTrue(call(db, "close", new JSONObject()).getBoolean("dataRetained"));
        assertEquals("persisted", rows(db, 1).getJSONObject(0).getString("title")); db.close();
        PrivateDatabase reopened = installed(context); assertEquals("persisted", rows(reopened, 1).getJSONObject(0).getString("title"));
        error("INVALID_CONFIG", () -> PrivateDatabase.installed(context, "wrong.package"));
        error("INVALID_CONFIG", () -> PrivateDatabase.installed(null, "any.package"));
    }
    @Test public void globalRowQuotaRollsBackWholeBatch() throws Exception {
        File directory = temporary.newFolder("row-quota"); Context context = context(directory); PrivateDatabase db = installed(context);
        migrate(db, 0, create("notes", titleColumns())); db.close();
        // Seed synthetic native rows efficiently; the tested write still goes through the typed API.
        try (SQLiteDatabase nativeDb = SQLiteDatabase.openDatabase(new File(directory, "factory-private-v1.sqlite").getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            nativeDb.beginTransaction();
            try {
                for (int i = 0; i < DatabaseRequest.MAX_ROWS; i++) nativeDb.execSQL("INSERT INTO t_notes(id,c_title) VALUES (?,?)", new Object[]{"row" + i, "synthetic"});
                nativeDb.setTransactionSuccessful();
            } finally { nativeDb.endTransaction(); }
        }
        error("QUOTA_EXCEEDED", () -> tx(db, 1, op("update", "row0", value("must roll back")), op("insert", "overflow", value("too many"))));
        JSONObject result = call(db, "select", query(1).put("where", value("must roll back")));
        assertEquals(0, result.getJSONArray("rows").length());
        tx(db, 1, op("delete", "row0", null), op("insert", "replacement", value("within quota")));
    }
    @Test public void corruptNativeFileFailsClosedWithoutDeletingIt() throws Exception {
        File directory = temporary.newFolder("corrupt"); File file = new File(directory, "factory-private-v1.sqlite");
        byte[] original = DatabaseRequestTest.repeat("synthetic-not-a-sqlite-database", 256).getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), original); PrivateDatabase db = installed(context(directory));
        FactoryException failure = error("DATABASE_ERROR", () -> call(db, "info", new JSONObject()));
        assertFalse(failure.getMessage().contains(file.getPath())); assertTrue(file.isFile());
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }
    @Test public void nullableMigrationCannotPushExistingRowBeyondSerializedQuota() throws Exception {
        PrivateDatabase db = preview(); JSONObject columns = new JSONObject(); JSONObject values = new JSONObject();
        for (int i = 0; i < 4; i++) {
            columns.put("part" + i, def("text", false));
            values.put("part" + i, DatabaseRequestTest.repeat("x", i == 3 ? 4000 : 4096));
        }
        JSONObject expected = new JSONObject(values.toString()).put("id", "one");
        int remaining = DatabaseRequest.MAX_ROW_BYTES - DatabaseRequest.bytes(expected);
        values.put("part3", values.getString("part3") + DatabaseRequestTest.repeat("x", remaining));
        migrate(db, 0, create("notes", columns));
        tx(db, 1, op("insert", "one", values));
        assertEquals(DatabaseRequest.MAX_ROW_BYTES, DatabaseRequest.bytes(rows(db, 1).getJSONObject(0)));
        JSONObject add = new JSONObject().put("kind", "addColumn").put("table", "notes").put("column", "extra").put("definition", def("text", true));
        error("QUOTA_EXCEEDED", () -> migrate(db, 1, add));
        assertEquals(1, call(db, "info", new JSONObject()).getInt("version"));
        assertFalse(call(db, "info", new JSONObject()).getJSONObject("schema").getJSONObject("notes").has("extra"));
        tx(db, 1, op("update", "one", new JSONObject().put("part3", "short")));
        migrate(db, 1, add); assertTrue(rows(db, 2).getJSONObject(0).isNull("extra"));
    }
    @Test public void missingMetadataRowIsNotRecreatedAndExistingDataSurvives() throws Exception {
        File directory = temporary.newFolder("missing-metadata"); Context context = context(directory); PrivateDatabase db = installed(context);
        migrate(db, 0, create("notes", titleColumns())); tx(db, 1, op("insert", "one", value("keep"))); db.close();
        File file = new File(directory, "factory-private-v1.sqlite");
        try (SQLiteDatabase nativeDb = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            nativeDb.execSQL("DELETE FROM factory_schema");
        }
        error("DATABASE_ERROR", () -> call(db, "info", new JSONObject())); db.close();
        try (SQLiteDatabase nativeDb = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             android.database.Cursor metadata = nativeDb.rawQuery("SELECT count(*) FROM factory_schema", null);
             android.database.Cursor data = nativeDb.rawQuery("SELECT c_title FROM t_notes", null)) {
            assertTrue(metadata.moveToFirst()); assertEquals(0, metadata.getInt(0));
            assertTrue(data.moveToFirst()); assertEquals("keep", data.getString(0)); assertEquals(1, nativeDb.getVersion());
        }
    }
    @Test public void incompatibleNativeColumnAndUnexpectedTriggerFailClosed() throws Exception {
        for (String mutation : new String[]{"ALTER TABLE t_notes ADD COLUMN unexpected TEXT", "CREATE TRIGGER surprise AFTER INSERT ON t_notes BEGIN DELETE FROM t_notes; END"}) {
            File directory = temporary.newFolder(); PrivateDatabase db = installed(context(directory));
            migrate(db, 0, create("notes", titleColumns())); db.close();
            File file = new File(directory, "factory-private-v1.sqlite");
            try (SQLiteDatabase nativeDb = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) { nativeDb.execSQL(mutation); }
            error("DATABASE_ERROR", () -> call(db, "info", new JSONObject())); assertTrue(file.isFile()); db.close();
        }
    }
    @Test public void persistedWrongStorageClassCannotBeSilentlyCoerced() throws Exception {
        File directory = temporary.newFolder("invalid-storage-type"); PrivateDatabase db = installed(context(directory));
        migrate(db, 0, create("notes", titleColumns().put("count", def("integer", false))));
        tx(db, 1, op("insert", "one", value("keep").put("count", 3))); db.close();
        try (SQLiteDatabase nativeDb = SQLiteDatabase.openDatabase(new File(directory, "factory-private-v1.sqlite").getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            nativeDb.execSQL("UPDATE t_notes SET c_count='not-an-integer' WHERE id='one'");
        }
        error("DATABASE_ERROR", () -> rows(db, 1));
    }

    @Test public void actualNativePageLimitIsAtMostSixteenMiBAndWalIsDisabled() throws Exception {
        File directory = temporary.newFolder("native-page-limit"); PrivateDatabase db = installed(context(directory));
        call(db, "info", new JSONObject());
        java.lang.reflect.Field connection = PrivateDatabase.class.getDeclaredField("database");
        connection.setAccessible(true); SQLiteDatabase nativeDb = (SQLiteDatabase) connection.get(db);
        assertNotNull(nativeDb); assertFalse(nativeDb.isWriteAheadLoggingEnabled());
        long pageSize;
        try (android.database.Cursor cursor = nativeDb.rawQuery("PRAGMA page_size", null)) {
            assertTrue(cursor.moveToFirst()); pageSize = cursor.getLong(0); assertTrue(pageSize > 0);
        }
        try (android.database.Cursor cursor = nativeDb.rawQuery("PRAGMA max_page_count", null)) {
            assertTrue(cursor.moveToFirst()); long pages = cursor.getLong(0); assertTrue(pages > 0);
            assertTrue("Native maximum must bound the main database file", pages * pageSize <= DatabaseRequest.MAX_DATABASE_BYTES);
            assertEquals(nativeDb.getMaximumSize(), pages * pageSize);
        }
        try (android.database.Cursor cursor = nativeDb.rawQuery("PRAGMA journal_mode", null)) {
            assertTrue(cursor.moveToFirst()); assertFalse("wal".equalsIgnoreCase(cursor.getString(0)));
        }
    }
    @Test public void oversizedExistingNativeFileFailsQuotaWithoutShrinkingOrDeletingRows() throws Exception {
        File directory = temporary.newFolder("oversized-native"); PrivateDatabase db = installed(context(directory));
        migrate(db, 0, create("notes", titleColumns())); tx(db, 1, op("insert", "one", value("preserve-existing-synthetic-row"))); db.close();
        File file = new File(directory, "factory-private-v1.sqlite");
        int blobBytes = (int) DatabaseRequest.MAX_DATABASE_BYTES + 1024 * 1024;
        try (SQLiteDatabase nativeDb = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            assertTrue(nativeDb.setMaximumSize(32L * 1024 * 1024) >= 32L * 1024 * 1024);
            nativeDb.execSQL("INSERT INTO t_notes(id,c_title) VALUES ('large_synthetic',zeroblob(?))", new Object[]{blobBytes});
        }
        long originalLength = file.length(); assertTrue(originalLength > DatabaseRequest.MAX_DATABASE_BYTES);
        FactoryException failure = error("QUOTA_EXCEEDED", () -> call(db, "info", new JSONObject()));
        assertFalse(failure.getMessage().contains(directory.getAbsolutePath()));
        db.close(); assertTrue(file.isFile()); assertEquals(originalLength, file.length());
        try (SQLiteDatabase nativeDb = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             android.database.Cursor kept = nativeDb.rawQuery("SELECT c_title FROM t_notes WHERE id='one'", null);
             android.database.Cursor large = nativeDb.rawQuery("SELECT length(c_title) FROM t_notes WHERE id='large_synthetic'", null);
             android.database.Cursor count = nativeDb.rawQuery("SELECT count(*) FROM t_notes", null)) {
            assertTrue(kept.moveToFirst()); assertEquals("preserve-existing-synthetic-row", kept.getString(0));
            assertTrue(large.moveToFirst()); assertEquals(blobBytes, large.getLong(0));
            assertTrue(count.moveToFirst()); assertEquals(2, count.getInt(0)); assertEquals(1, nativeDb.getVersion());
        }
    }
    @Test public void infoCloseAndCancelResponsesDoNotExposePrivatePaths() throws Exception {
        File directory = temporary.newFolder("private-path-sentinel"); PrivateDatabase db = installed(context(directory));
        for (String method : new String[]{"info", "cancel", "close"}) {
            JSONObject result = call(db, method, new JSONObject()); String serialized = result.toString();
            assertFalse(serialized.contains(directory.getAbsolutePath()));
            assertFalse(serialized.contains("private-path-sentinel"));
            assertFalse(serialized.contains("factory-private-v1.sqlite"));
            assertFalse(result.has("path")); assertFalse(result.has("file")); assertFalse(result.has("connection"));
        }
    }

}
