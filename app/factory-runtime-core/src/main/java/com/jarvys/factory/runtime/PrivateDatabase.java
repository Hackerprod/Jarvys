package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteFullException;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** One private typed SQLite store. No wire input can select a file, connection, SQL or extension. */
public final class PrivateDatabase implements AutoCloseable {
    private final Context context;
    private final ResourceBudgets.Database budget;
    private File file;
    private final AtomicLong epoch=new AtomicLong();
    private volatile CancellationSignal active;
    private SQLiteDatabase database;
    private final BackupTransactionEnd backupTransactionEnd;
    private PrivateDatabase(Context context,ResourceBudgets budgets) { this(context,budgets,SQLiteDatabase::endTransaction); }
    private PrivateDatabase(Context context,ResourceBudgets budgets,BackupTransactionEnd end) {
        if(budgets==null || end==null) throw new IllegalArgumentException();
        this.context=context; this.budget=budgets.database; this.backupTransactionEnd=end;
    }
    /** Failure-only test seam; production instances always call SQLiteDatabase.endTransaction directly. */
    interface BackupTransactionEnd { void end(SQLiteDatabase database); }
    static PrivateDatabase previewForBackupTest(BackupTransactionEnd end) { return new PrivateDatabase(null,ResourceBudgets.legacy(),end); }
    public static PrivateDatabase preview() { return preview(ResourceBudgets.legacy()); }
    public static PrivateDatabase preview(ResourceBudgets budgets) { return new PrivateDatabase(null,budgets); }
    public static PrivateDatabase installed(Context context,String appId) throws FactoryException {
        return installed(context,appId,ResourceBudgets.legacy());
    }
    public static PrivateDatabase installed(Context context,String appId,ResourceBudgets budgets) throws FactoryException {
        if(context==null || !context.getPackageName().equals(appId))
            throw new FactoryException("INVALID_CONFIG","Database identity does not match installed application.");
        Context owner=context.getApplicationContext();
        if(owner==null || !owner.getPackageName().equals(appId)) throw new FactoryException("INVALID_CONFIG","Database application context is unavailable.");
        return new PrivateDatabase(owner,budgets);
    }
    public long ticket() { return epoch.get(); }
    /** Synchronous authority invalidation; never closes an in-use SQLite connection. */
    public void cancel() { epoch.incrementAndGet(); CancellationSignal signal=active; if(signal!=null) signal.cancel(); }
    /** Invoke on the serialized database worker, after cancel. Preview close discards its RAM data. */
    @Override public synchronized void close() { if(database!=null) { database.close(); database=null; } }
    private void check(long ticket,BooleanSupplier current) throws FactoryException {
        if(ticket!=epoch.get() || current==null || !current.getAsBoolean() || ticket!=epoch.get() || Thread.currentThread().isInterrupted())
            throw new FactoryException("CANCELLED","Database operation cancelled. A prior commit is not undone.");
    }
    public synchronized Object execute(String method,JSONObject args,long ticket,BooleanSupplier current) throws Exception {
        DatabaseRequest.validate(method,args); check(ticket,current);
        if("database.cancel".equals(method)) { cancel(); return cancellation(); }
        if("database.close".equals(method)) { cancel(); close(); return new JSONObject().put("closed",true).put("dataRetained",context!=null); }
        CancellationSignal signal=new CancellationSignal(); active=signal;
        try {
            check(ticket,current); open(); check(ticket,current);
            database.beginTransaction();
            Object result;
            try {
                JSONObject schema=readSchema(signal); int version=database.getVersion();
                if("database.info".equals(method)) result=info(schema,version);
                else if("database.migrate".equals(method)) {
                    if(args.getInt("fromVersion")!=version) throw versionConflict();
                    result=migrate(schema,args,ticket,current,signal);
                } else {
                    if(args.getInt("version")!=version) throw versionConflict();
                    result="database.transact".equals(method)?transact(schema,args,ticket,current,signal):select(schema,args,signal,ticket,current);
                }
                if(DatabaseRequest.bytes(result)>DatabaseRequest.MAX_RESULT_BYTES) throw quota();
                check(ticket,current); // Last authority/size check before SQLite's irreversible commit boundary.
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
            return result;
        } catch(OperationCanceledException cancelled) {
            throw new FactoryException("CANCELLED","Database operation cancelled. A prior commit is not undone.");
        } catch(SQLiteFullException full) { throw quota(); }
        catch(SQLiteException failure) {
            // Never expose SQLite messages containing private values, filenames or generated SQL.
            throw new FactoryException("DATABASE_ERROR","Private database operation failed; inspect state before retrying.");
        } finally { active=null; }
    }
    /** Immutable review preimage. It remains valid across closing and reopening this app's database. */
    public static final class Baseline {
        public final String preimageSha256, schemaSha256;
        private final String archiveSha256;
        private final JSONObject selectedCounts;
        private Baseline(String archiveSha256, String preimageSha256, String schemaSha256, JSONObject counts) {
            this.archiveSha256=archiveSha256; this.preimageSha256=preimageSha256; this.schemaSha256=schemaSha256;
            this.selectedCounts=(JSONObject)BackupJson.copy(counts);
        }
        public JSONObject counts() { return (JSONObject)BackupJson.copy(selectedCounts); }
    }
    private interface BackupWork<T> { T run(JSONObject schema,CancellationSignal signal) throws Exception; }
    /** All archive rows are read under one SQLite snapshot, with no keyset/page query boundary. */
    public synchronized DatabaseBackupFormat.Archive backupExport(String appId,String certificate,int version,List<String> tables,
                                                                  long ticket,BooleanSupplier current) throws Exception {
        if(tables==null) throw DatabaseRequest.invalid("database.backupExport.tables must be a non-empty selected table list");
        final List<String> selected=DatabaseBackupRequest.tables(new JSONArray(tables));
        if(version<1 || version>1000) throw DatabaseRequest.invalid("database.backupExport.version must be an integer from 1 through 1000");
        if(context!=null && !context.getPackageName().equals(appId)) throw backupIdentity();
        return backupTransaction(ticket,current,(schema,signal)-> {
            if(database.getVersion()!=version) throw versionConflict();
            JSONObject selectedSchema=backupSchema(schema,selected), data=new JSONObject();
            long encodedRows=0;
            for(String name:selected) {
                JSONObject columns=selectedSchema.getJSONObject(name); JSONArray rows=new JSONArray();
                try(Cursor cursor=database.rawQuery(selectSql(name,columns)+" ORDER BY id COLLATE BINARY ASC",null,signal)) {
                    while(cursor.moveToNext()) {
                        check(ticket,current); JSONObject value=backupRow(cursor,columns);
                        encodedRows+=DatabaseBackupFormat.canonical(value).length+1L;
                        if(encodedRows>DatabaseBackupFormat.MAX_BYTES) throw BackupJson.tooLarge();
                        rows.put(value);
                    }
                }
                data.put(name,rows);
            }
            return DatabaseBackupFormat.create(appId,certificate,version,selectedSchema,data);
        });
    }
    public synchronized Baseline backupBaseline(DatabaseBackupFormat.Archive archive,long ticket,BooleanSupplier current) throws Exception {
        backupIdentity(archive);
        return backupTransaction(ticket,current,(schema,signal)-> {
            backupCompatible(archive,schema);
            return backupSnapshot(archive,schema,signal,ticket,current);
        });
    }
    public synchronized JSONObject backupImport(DatabaseBackupFormat.Archive archive,String mode,Baseline baseline,
                                                  long ticket,BooleanSupplier current) throws Exception {
        backupIdentity(archive); DatabaseBackupRequest.mode(mode);
        if(baseline==null || !archive.sha256.equals(baseline.archiveSha256)) throw backupConflict();
        return backupTransaction(ticket,current,(schema,signal)-> {
            backupCompatible(archive,schema);
            Baseline actual=backupSnapshot(archive,schema,signal,ticket,current);
            if(!actual.preimageSha256.equals(baseline.preimageSha256) || !actual.schemaSha256.equals(baseline.schemaSha256) ||
                    !Arrays.equals(DatabaseBackupFormat.canonical(actual.selectedCounts),DatabaseBackupFormat.canonical(baseline.selectedCounts)))
                throw backupConflict();
            JSONObject data=archive.data(), incomingSchema=archive.schema(), imported=new JSONObject();
            Usage before=usage(schema,signal); long selectedBytesBefore=storedBytes(schema,archive.tables,signal);
            // Validate every incoming row against its preimage before replacing any selected table.
            // Only one persisted row is materialized at a time, even when the target is much larger.
            for(String name:archive.tables) {
                JSONObject definitions=incomingSchema.getJSONObject(name); JSONArray rows=data.getJSONArray(name);
                for(int i=0;i<rows.length();i++) {
                    check(ticket,current); JSONObject next=DatabaseBackupFormat.normalizeRow(rows.getJSONObject(i),definitions);
                    admitRow(readRow(name,definitions,next.getString("id"),signal),next);
                }
            }
            if("insertOnly".equals(mode)) {
                // Fail on every collision, including identical rows. Never silently update or skip a record.
                for(String name:archive.tables) {
                    JSONArray rows=data.getJSONArray(name);
                    for(int i=0;i<rows.length();i++) {
                        check(ticket,current);
                        try(Cursor cursor=database.rawQuery("SELECT id FROM "+table(name)+" WHERE id=?",
                                new String[]{rows.getJSONObject(i).getString("id")},signal)) {
                            if(cursor.moveToFirst()) throw backupConflict();
                        }
                    }
                }
            } else {
                for(String name:archive.tables) { check(ticket,current); database.delete(table(name),null,null); }
            }
            for(String name:archive.tables) {
                JSONObject columns=incomingSchema.getJSONObject(name); JSONArray rows=data.getJSONArray(name);
                for(int i=0;i<rows.length();i++) {
                    check(ticket,current); JSONObject value=DatabaseBackupFormat.normalizeRow(rows.getJSONObject(i),columns);
                    ContentValues content=new ContentValues(); content.put("id",value.getString("id"));
                    for(String column:keys(columns)) put(content,column(column),value.get(column));
                    database.insertOrThrow(table(name),null,content);
                }
                imported.put(name,rows.length());
            }
            enforceWriteUsage(schema,signal,before,subtract(storedBytes(schema,archive.tables,signal),selectedBytesBefore));
            return new JSONObject().put("version",archive.version).put("mode",mode).put("tables",new JSONArray(archive.tables))
                    .put("counts",imported).put("committed",true);
        });
    }
    private <T> T backupTransaction(long ticket,BooleanSupplier current,BackupWork<T> work) throws Exception {
        check(ticket,current); CancellationSignal signal=new CancellationSignal(); active=signal;
        try {
            check(ticket,current); open(); check(ticket,current); database.beginTransaction();
            T result; Exception primaryFailure=null;
            try {
                result=work.run(readSchema(signal),signal);
                check(ticket,current); // Last authority check immediately before the irreversible commit boundary.
                database.setTransactionSuccessful();
            } catch(Exception failure) {
                primaryFailure=failure; throw failure;
            } finally {
                try { backupTransactionEnd.end(database); }
                catch(RuntimeException endFailure) {
                    // SQLITE_FULL can roll back natively before Android ends its transaction. Preserve
                    // that primary failure without treating a failed end/commit acknowledgement as success.
                    if(primaryFailure==null) throw endFailure;
                    if(primaryFailure!=endFailure) primaryFailure.addSuppressed(endFailure);
                }
            }
            // A result is never returned until endTransaction has completed successfully.
            return result;
        } catch(FactoryException failure) {
            if("QUOTA_EXCEEDED".equals(failure.code))
                throw new FactoryException("QUOTA_EXCEEDED","Private database quota exceeded; inspect state before retrying.");
            throw failure;
        } catch(OperationCanceledException cancelled) {
            throw new FactoryException("CANCELLED","Database backup operation cancelled. A prior commit is not undone.");
        } catch(SQLiteFullException full) {
            throw new FactoryException("QUOTA_EXCEEDED","Private database quota exceeded; inspect state before retrying.");
        } catch(SQLiteException failure) {
            throw new FactoryException("DATABASE_ERROR","Database backup operation failed; commit state may be unknown. Inspect state before retrying.");
        } finally { active=null; }
    }
    private void backupIdentity(DatabaseBackupFormat.Archive archive) throws FactoryException {
        if(archive==null) throw DatabaseRequest.invalid("database.backupImport.archive must be a validated frozen v1 database archive");
        if(context!=null && !context.getPackageName().equals(archive.appId)) throw backupIdentity();
    }
    private static FactoryException backupIdentity() {
        return new FactoryException("SCHEMA_MISMATCH","Database backup package is incompatible with this application.");
    }
    private static JSONObject backupSchema(JSONObject schema,List<String> selected) throws Exception {
        JSONObject result=new JSONObject();
        for(String name:selected) result.put(name,BackupJson.copy(columns(schema,name)));
        return result;
    }
    private void backupCompatible(DatabaseBackupFormat.Archive archive,JSONObject schema) throws Exception {
        if(database.getVersion()!=archive.version) throw versionConflict();
        if(!Arrays.equals(DatabaseBackupFormat.canonical(backupSchema(schema,archive.tables)),DatabaseBackupFormat.canonical(archive.schema())))
            throw new FactoryException("SCHEMA_MISMATCH","Selected database schemas do not match the backup.");
    }
    /** Streams rows into SHA-256; a valid target preimage may be larger than the archive's 1 MiB bound. */
    private Baseline backupSnapshot(DatabaseBackupFormat.Archive archive,JSONObject schema,CancellationSignal signal,
                                    long ticket,BooleanSupplier current) throws Exception {
        JSONObject selectedSchema=backupSchema(schema,archive.tables), counts=new JSONObject();
        String schemaHash=DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(selectedSchema));
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); long total=0;
        digest.update(("{\"databaseVersion\":"+archive.version+",\"tables\":{").getBytes(StandardCharsets.US_ASCII));
        boolean firstTable=true;
        for(String name:archive.tables) {
            check(ticket,current); if(!firstTable) digest.update((byte)','); firstTable=false;
            digest.update(DatabaseBackupFormat.canonical(name)); digest.update(":{\"columns\":".getBytes(StandardCharsets.US_ASCII));
            JSONObject columns=selectedSchema.getJSONObject(name); digest.update(DatabaseBackupFormat.canonical(columns));
            digest.update(",\"rows\":[".getBytes(StandardCharsets.US_ASCII)); int count=0;
            try(Cursor cursor=database.rawQuery(selectSql(name,columns)+" ORDER BY id COLLATE BINARY ASC",null,signal)) {
                while(cursor.moveToNext()) {
                    check(ticket,current); if(++total>DatabaseRequest.HARD_MAX_ROWS) throw quota();
                    JSONObject value=DatabaseBackupFormat.normalizeCurrentRow(row(cursor,columns),columns); if(count++>0) digest.update((byte)',');
                    digest.update(DatabaseBackupFormat.canonical(value));
                }
            }
            digest.update("]}".getBytes(StandardCharsets.US_ASCII)); counts.put(name,count);
        }
        digest.update("}}".getBytes(StandardCharsets.US_ASCII));
        return new Baseline(archive.sha256,DatabaseBackupFormat.hex(digest.digest()),schemaHash,counts);
    }
    private static JSONObject backupRow(Cursor cursor,JSONObject columns) throws Exception {
        JSONObject value=row(cursor,columns);
        if(DatabaseRequest.bytes(value)>DatabaseRequest.MAX_ROW_BYTES) throw quota();
        return DatabaseBackupFormat.normalizeRow(value,columns);
    }
    private static FactoryException backupConflict() {
        return new FactoryException("BACKUP_CONFLICT","Selected database rows changed or an imported ID already exists; review a fresh import plan.");
    }
    public static JSONObject cancellation() throws Exception {
        return new JSONObject().put("cancelRequested",true).put("rollbackConfirmed",false);
    }
    private void open() throws Exception {
        if(database!=null) return;
        if(context!=null) file=new File(context.getNoBackupFilesDir(),"factory-private-v1.sqlite");
        SQLiteDatabase opened=context==null?SQLiteDatabase.create(null):SQLiteDatabase.openDatabase(file.getPath(),null,
                SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                corrupt -> { throw new android.database.sqlite.SQLiteDatabaseCorruptException("Private database requires recovery; it was not deleted."); });
        try {
            opened.disableWriteAheadLogging();
            // SQLite must not truncate or reject an existing store when a new artifact lowers its
            // capacity. Pin growth to the larger of current pages and the configured page budget;
            // transactional admission below permits reads, deletes and non-growing replacements.
            long pageSize=pragma(opened,"page_size"), existing=mainBytes(opened);
            if(existing>DatabaseRequest.HARD_MAX_DATABASE_BYTES) throw quota();
            long configured=(budget.mainFileBytes/pageSize)*pageSize;
            long maximum=Math.max(existing,configured);
            if(maximum<pageSize || opened.setMaximumSize(maximum)>maximum) throw quota();
            opened.beginTransaction();
            try {
                // Initialize only an empty version-zero database, never repair missing metadata.
                boolean empty;
                try(Cursor cursor=opened.rawQuery("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name!='android_metadata'",null)) { empty=!cursor.moveToFirst(); }
                if(empty) {
                    if(opened.getVersion()!=0) throw corrupt();
                    opened.execSQL("CREATE TABLE factory_schema (id INTEGER PRIMARY KEY CHECK(id=1), schema_json TEXT NOT NULL)");
                    opened.execSQL("INSERT INTO factory_schema (id,schema_json) VALUES (1,?)",new Object[]{"{}"});
                }
                opened.setTransactionSuccessful();
            } finally { opened.endTransaction(); }
            database=opened;
        } catch(Exception failure) { opened.close(); throw failure; }
    }
    private JSONObject readSchema(CancellationSignal signal) throws Exception {
        try(Cursor cursor=database.rawQuery("SELECT schema_json FROM factory_schema WHERE id=1",null,signal)) {
            if(!cursor.moveToFirst()) throw corrupt();
            JSONObject schema=StrictJson.object(cursor.getString(0),ResourceBudgets.DATABASE_SCHEMA_BYTES,8,
                    1+DatabaseRequest.HARD_MAX_TABLES*(1+3*DatabaseRequest.HARD_MAX_COLUMNS));
            if(schema.length()>DatabaseRequest.HARD_MAX_TABLES) throw corrupt();
            for(String table:keys(schema)) { DatabaseRequest.identifier(table); DatabaseRequest.columns(schema.getJSONObject(table)); }
            int version=database.getVersion();
            if(version<0 || version>1000 || ((version==0)!=(schema.length()==0))) throw corrupt();
            verifySchema(schema,signal);
            return schema;
        }
    }
    private void verifySchema(JSONObject schema,CancellationSignal signal) throws Exception {
        java.util.Set<String> expected=new java.util.HashSet<>(); expected.add("factory_schema");
        for(String name:keys(schema)) expected.add(table(name));
        try(Cursor cursor=database.rawQuery("SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name!='android_metadata'",null,signal)) {
            while(cursor.moveToNext()) if(!"table".equals(cursor.getString(0)) || !expected.remove(cursor.getString(1))) throw corrupt();
        }
        if(!expected.isEmpty()) throw corrupt();
        try(Cursor cursor=database.rawQuery("SELECT count(*) FROM factory_schema",null,signal)) { if(!cursor.moveToFirst() || cursor.getLong(0)!=1) throw corrupt(); }
        for(String name:keys(schema)) {
            JSONObject columns=schema.getJSONObject(name); java.util.Set<String> names=new java.util.HashSet<>(); names.add("id");
            for(String column:keys(columns)) names.add(column(column));
            try(Cursor cursor=database.rawQuery("PRAGMA table_info("+table(name)+")",null,signal)) {
                while(cursor.moveToNext()) {
                    String column=cursor.getString(1); if(!names.remove(column) || !cursor.isNull(4)) throw corrupt();
                    if("id".equals(column)) { if(!"TEXT".equals(cursor.getString(2)) || cursor.getInt(3)!=1 || cursor.getInt(5)!=1) throw corrupt(); }
                    else {
                        JSONObject definition=columns.getJSONObject(column.substring(2)); String type=definition.getString("type");
                        String sqlType="text".equals(type)?"TEXT":"real".equals(type)?"REAL":"INTEGER";
                        if(!sqlType.equals(cursor.getString(2)) || cursor.getInt(3)!=(definition.getBoolean("nullable")?0:1) || cursor.getInt(5)!=0) throw corrupt();
                    }
                }
            }
            if(!names.isEmpty()) throw corrupt();
        }
    }
    private JSONObject info(JSONObject schema,int version) throws Exception {
        return new JSONObject().put("version",version).put("schema",schema).put("mode",context==null?"preview":"installed")
                .put("persistent",context!=null).put("encrypted",false).put("backupIncluded",false)
                .put("limits",new JSONObject().put("mainDatabaseBytes",budget.mainFileBytes)
                        .put("totalDiskHardLimit",false).put("tables",budget.tables).put("columnsPerTable",budget.columnsPerTable)
                        .put("rows",budget.rows).put("cellBytes",budget.cellBytes).put("rowBytes",budget.rowBytes)
                        .put("operations",budget.batchOperations).put("migrationSteps",DatabaseRequest.MAX_STEPS)
                        .put("requestBytes",DatabaseRequest.MAX_ARGUMENT_BYTES).put("responseBytes",DatabaseRequest.MAX_RESULT_BYTES)
                        .put("selectRows",budget.selectPageRows));
    }
    private JSONObject migrate(JSONObject schema,JSONObject args,long ticket,BooleanSupplier current,CancellationSignal signal) throws Exception {
        JSONArray steps=args.getJSONArray("steps"); Usage before=usage(schema,signal);
        for(int i=0;i<steps.length();i++) {
            check(ticket,current); JSONObject step=steps.getJSONObject(i); String table=step.getString("table");
            if("createTable".equals(step.getString("kind"))) {
                if(schema.has(table)) throw DatabaseRequest.invalid("database.migrate.steps["+i+"].table must name a table that does not already exist");
                if(schema.length()>=budget.tables) throw quota();
                JSONObject columns=step.getJSONObject("columns");
                if(columns.length()>budget.columnsPerTable) throw quota();
                StringBuilder sql=new StringBuilder("CREATE TABLE ").append(table(table)).append(" (id TEXT PRIMARY KEY NOT NULL COLLATE BINARY");
                for(String column:keys(columns)) sql.append(',').append(column(column)).append(' ').append(sqlType(columns.getJSONObject(column)));
                database.execSQL(sql.append(')').toString()); schema.put(table,new JSONObject(columns.toString()));
            } else {
                JSONObject columns=columns(schema,table); String column=step.getString("column");
                if(columns.has(column)) throw DatabaseRequest.invalid("database.migrate.steps["+i+"].column must name a column that does not already exist");
                if(columns.length()>=budget.columnsPerTable) throw quota();
                JSONObject definition=step.getJSONObject("definition");
                database.execSQL("ALTER TABLE "+table(table)+" ADD COLUMN "+column(column)+" "+sqlType(definition));
                columns.put(column,new JSONObject(definition.toString()));
            }
        }
        check(ticket,current);
        // A migration always grows the schema. Do not use free pages as an over-budget escape.
        if(before.mainBytes>budget.mainFileBytes) throw quota();
        enforceWriteUsage(schema,signal,before,0);
        // New nullable columns also enlarge serialized existing rows. Reject atomically before commit.
        for(String table:keys(schema)) {
            JSONObject columns=columns(schema,table);
            try(Cursor cursor=database.rawQuery(selectSql(table,columns),null,signal)) {
                while(cursor.moveToNext()) { check(ticket,current); if(DatabaseRequest.bytes(row(cursor,columns))>budget.rowBytes) throw quota(); }
            }
        }
        if(DatabaseRequest.bytes(schema)>ResourceBudgets.DATABASE_SCHEMA_BYTES) throw quota();
        database.execSQL("UPDATE factory_schema SET schema_json=? WHERE id=1",new Object[]{schema.toString()});
        database.setVersion(args.getInt("toVersion"));
        enforceWriteUsage(schema,signal,before,0);
        return new JSONObject().put("version",args.getInt("toVersion")).put("stepsApplied",steps.length()).put("committed",true);
    }
    private JSONObject transact(JSONObject schema,JSONObject args,long ticket,BooleanSupplier current,CancellationSignal signal) throws Exception {
        JSONArray operations=args.getJSONArray("operations"), changes=new JSONArray();
        if(operations.length()>budget.batchOperations) throw quota();
        Usage before=usage(schema,signal); long byteDelta=0;
        for(int i=0;i<operations.length();i++) {
            check(ticket,current); JSONObject operation=operations.getJSONObject(i); String table=operation.getString("table"), id=operation.getString("id");
            JSONObject columns=columns(schema,table); String kind=operation.getString("kind"); int changed;
            long previousBytes=storedRowBytes(table,columns,id,signal);
            if("delete".equals(kind)) changed=database.delete(table(table),"id=?",new String[]{id});
            else {
                JSONObject previous="update".equals(kind)?readRow(table,columns,id,signal):null;
                JSONObject values=operation.getJSONObject("values"); ContentValues content=new ContentValues();
                for(String name:keys(values)) {
                    if(!columns.has(name)) throw DatabaseRequest.invalid("database.transact.operations["+i+"].values keys must name columns declared for this table");
                    Object value=values.get(name); DatabaseRequest.typed(value,columns.getJSONObject(name),"database.transact.operations["+i+"].values.<column>"); put(content,column(name),value);
                }
                if("insert".equals(kind)) {
                    for(String name:keys(columns)) if(!values.has(name) && !columns.getJSONObject(name).getBoolean("nullable"))
                        throw DatabaseRequest.invalid("database.transact.operations["+i+"].values must include every non-nullable column for insert");
                    content.put("id",id); database.insertOrThrow(table(table),null,content); changed=1;
                } else {
                    changed=database.update(table(table),content,"id=?",new String[]{id});
                    if(changed!=1) throw new FactoryException("NOT_FOUND","No database row has that ID.");
                }
                JSONObject next=readRow(table,columns,id,signal);
                if(next==null) throw corrupt(); admitRow(previous,next);
            }
            byteDelta=add(byteDelta,subtract(storedRowBytes(table,columns,id,signal),previousBytes));
            changes.put(changed);
        }
        enforceWriteUsage(schema,signal,before,byteDelta); check(ticket,current);
        return new JSONObject().put("version",args.getInt("version")).put("changes",changes).put("committed",true);
    }
    private JSONObject select(JSONObject schema,JSONObject args,CancellationSignal signal,long ticket,BooleanSupplier current) throws Exception {
        String table=args.getString("table"); JSONObject columns=columns(schema,table);
        StringBuilder sql=new StringBuilder(selectSql(table,columns)).append(" WHERE 1=1"); List<String> binds=new ArrayList<>();
        if(args.has("where")) for(String name:keys(args.getJSONObject("where"))) {
            Object value=args.getJSONObject("where").get(name);
            if("id".equals(name)) {
                sql.append(" AND id=?"); binds.add(DatabaseRequest.id((String)value)); continue;
            }
            if(!columns.has(name)) throw DatabaseRequest.invalid("database.select.where keys must name id or declared columns");
            DatabaseRequest.typed(value,columns.getJSONObject(name),"database.select.where.<column>");
            if(value==JSONObject.NULL) sql.append(" AND ").append(column(name)).append(" IS NULL");
            else { sql.append(" AND ").append(column(name)).append("=?"); binds.add(value instanceof Boolean?((Boolean)value?"1":"0"):value.toString()); }
        }
        if(args.has("afterId")) { sql.append(" AND id>?"); binds.add(args.getString("afterId")); }
        int limit=Math.min(args.has("limit")?args.getInt("limit"):50,budget.selectPageRows);
        sql.append(" ORDER BY id COLLATE BINARY ASC LIMIT ?"); binds.add(Integer.toString(limit+1));
        JSONArray rows=new JSONArray(); boolean more=false; String last=null; long encodedBytes=2;
        final long rowPageBytes=DatabaseRequest.HARD_MAX_ROW_BYTES+2L;
        try(Cursor cursor=database.rawQuery(sql.toString(),binds.toArray(new String[0]),signal)) {
            while(cursor.moveToNext()) {
                check(ticket,current);
                if(rows.length()==limit) { more=true; break; }
                JSONObject row=row(cursor,columns);
                int rowBytes=DatabaseRequest.bytes(row);
                if(rowBytes>DatabaseRequest.HARD_MAX_ROW_BYTES) throw quota();
                long next=add(encodedBytes,(long)rowBytes+(rows.length()==0?0:1));
                if(next>rowPageBytes) { if(rows.length()==0) throw quota(); more=true; break; }
                rows.put(row); encodedBytes=next; last=cursor.getString(0);
            }
        }
        return new JSONObject().put("version",args.getInt("version")).put("rows",rows).put("hasMore",more)
                .put("nextAfterId",more?last:JSONObject.NULL).put("snapshotAcrossPages",false);
    }
    private static final class Usage {
        final long rows,mainBytes;
        Usage(long rows,long mainBytes) { this.rows=rows; this.mainBytes=mainBytes; }
    }
    private Usage usage(JSONObject schema,CancellationSignal signal) throws Exception {
        long total=0;
        for(String table:keys(schema)) try(Cursor cursor=database.rawQuery("SELECT count(*) FROM "+table(table),null,signal)) {
            if(!cursor.moveToFirst() || cursor.getLong(0)<0) throw corrupt(); total=add(total,cursor.getLong(0));
        }
        return new Usage(total,mainBytes(database));
    }
    private void enforceWriteUsage(JSONObject schema,CancellationSignal signal,Usage before,long byteDelta) throws Exception {
        Usage after=usage(schema,signal);
        if(after.rows>budget.rows && after.rows>before.rows) throw quota();
        if(after.mainBytes>budget.mainFileBytes &&
                (after.mainBytes>before.mainBytes || after.rows>before.rows || byteDelta>0)) throw quota();
    }
    private void admitRow(JSONObject before,JSONObject after) throws Exception {
        int next=DatabaseRequest.bytes(after);
        if(next>DatabaseRequest.HARD_MAX_ROW_BYTES || (next>budget.rowBytes && (before==null || next>DatabaseRequest.bytes(before)))) throw quota();
        for(String key:keys(after)) {
            if("id".equals(key) || !(after.get(key) instanceof String)) continue;
            int bytes=after.getString(key).getBytes(StandardCharsets.UTF_8).length;
            Object previous=before==null?null:before.opt(key);
            if(bytes>budget.cellBytes && (!(previous instanceof String) || bytes>((String)previous).getBytes(StandardCharsets.UTF_8).length)) throw quota();
        }
    }
    private JSONObject readRow(String name,JSONObject columns,String id,CancellationSignal signal) throws Exception {
        try(Cursor cursor=database.rawQuery(selectSql(name,columns)+" WHERE id=?",new String[]{id},signal)) {
            return cursor.moveToFirst()?row(cursor,columns):null;
        }
    }
    // SQL computes bounded aggregate counters without materializing a table or a result JSONArray.
    // The constant row charge also makes deletion/reinsertion accounting independent of null cells.
    private static String byteExpression(JSONObject columns) throws Exception {
        StringBuilder sql=new StringBuilder("128+length(CAST(id AS BLOB))");
        for(String name:keys(columns)) sql.append("+COALESCE(length(CAST(").append(column(name)).append(" AS BLOB)),0)");
        return sql.toString();
    }
    private long storedRowBytes(String name,JSONObject columns,String id,CancellationSignal signal) throws Exception {
        try(Cursor cursor=database.rawQuery("SELECT "+byteExpression(columns)+" FROM "+table(name)+" WHERE id=?",new String[]{id},signal)) {
            if(!cursor.moveToFirst()) return 0;
            long bytes=cursor.getLong(0); if(bytes<0) throw corrupt(); return bytes;
        }
    }
    private long storedBytes(JSONObject schema,List<String> names,CancellationSignal signal) throws Exception {
        long total=0;
        for(String name:names) try(Cursor cursor=database.rawQuery("SELECT COALESCE(sum("+byteExpression(columns(schema,name))+"),0) FROM "+table(name),null,signal)) {
            if(!cursor.moveToFirst() || cursor.getLong(0)<0) throw corrupt(); total=add(total,cursor.getLong(0));
        }
        return total;
    }
    private static long pragma(SQLiteDatabase connection,String key) throws Exception {
        try(Cursor cursor=connection.rawQuery("PRAGMA "+key,null)) {
            if(!cursor.moveToFirst()) throw corrupt(); long value=cursor.getLong(0);
            if(value<0 || ("page_size".equals(key) && value==0)) throw corrupt(); return value;
        }
    }
    private static long mainBytes(SQLiteDatabase connection) throws Exception {
        try { return Math.multiplyExact(pragma(connection,"page_count"),pragma(connection,"page_size")); }
        catch(ArithmeticException overflow) { throw quota(); }
    }
    private static long add(long a,long b) throws FactoryException {
        try { return Math.addExact(a,b); } catch(ArithmeticException overflow) { throw quota(); }
    }
    private static long subtract(long a,long b) throws FactoryException {
        try { return Math.subtractExact(a,b); } catch(ArithmeticException overflow) { throw quota(); }
    }
    private static JSONObject row(Cursor cursor,JSONObject columns) throws Exception {
        if(cursor.getType(0)!=Cursor.FIELD_TYPE_STRING) throw corrupt();
        JSONObject result=new JSONObject().put("id",DatabaseRequest.id(cursor.getString(0))); int index=1;
        for(String name:keys(columns)) {
            JSONObject definition=columns.getJSONObject(name); Object value;
            int storageType=cursor.getType(index); String type=definition.getString("type");
            if(storageType!=Cursor.FIELD_TYPE_NULL &&
                    ("text".equals(type)?storageType!=Cursor.FIELD_TYPE_STRING:
                    "real".equals(type)?storageType!=Cursor.FIELD_TYPE_INTEGER&&storageType!=Cursor.FIELD_TYPE_FLOAT:storageType!=Cursor.FIELD_TYPE_INTEGER)) throw corrupt();
            if(cursor.isNull(index)) value=JSONObject.NULL;
            else switch(definition.getString("type")) {
                case "integer": value=cursor.getLong(index); break;
                case "real": value=cursor.getDouble(index); break;
                case "boolean": long flag=cursor.getLong(index); if(flag!=0 && flag!=1) throw corrupt(); value=flag==1; break;
                default: value=cursor.getString(index);
            }
            DatabaseRequest.typed(value,definition,"database.select.rows.<column>"); result.put(name,value); index++;
        }
        return result;
    }
    private static String selectSql(String table,JSONObject columns) throws Exception {
        StringBuilder sql=new StringBuilder("SELECT id"); for(String name:keys(columns)) sql.append(',').append(column(name));
        return sql.append(" FROM ").append(table(table)).toString();
    }
    private static JSONObject columns(JSONObject schema,String table) throws Exception {
        if(!schema.has(table)) throw new FactoryException("SCHEMA_MISMATCH","Database table is not defined."); return schema.getJSONObject(table);
    }
    private static String table(String name) throws FactoryException { return "t_"+DatabaseRequest.identifier(name); }
    private static String column(String name) throws FactoryException { return "c_"+DatabaseRequest.identifier(name); }
    private static String sqlType(JSONObject definition) throws Exception {
        String type=definition.getString("type");
        return ("text".equals(type)?"TEXT":"real".equals(type)?"REAL":"INTEGER")+(definition.getBoolean("nullable")?"":" NOT NULL");
    }
    private static List<String> keys(JSONObject object) {
        List<String> result=new ArrayList<>(); for(Iterator<String> it=object.keys();it.hasNext();) result.add(it.next()); Collections.sort(result); return result;
    }
    private static void put(ContentValues values,String name,Object value) {
        if(value==JSONObject.NULL) values.putNull(name);
        else if(value instanceof String) values.put(name,(String)value);
        else if(value instanceof Boolean) values.put(name,(Boolean)value);
        else if(value instanceof Integer || value instanceof Long) values.put(name,((Number)value).longValue());
        else values.put(name,((Number)value).doubleValue());
    }
    private static FactoryException versionConflict() { return new FactoryException("VERSION_CONFLICT","Database version changed; inspect schema before retrying."); }
    private static FactoryException quota() { return new FactoryException("QUOTA_EXCEEDED","Private database quota exceeded; no partial batch is committed."); }
    private static FactoryException corrupt() { return new FactoryException("DATABASE_ERROR","Private database schema is invalid; no automatic reset was performed."); }
}
