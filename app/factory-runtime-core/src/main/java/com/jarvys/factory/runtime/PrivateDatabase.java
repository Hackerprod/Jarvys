package com.jarvys.factory.runtime;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** One private typed SQLite store. No wire input can select a file, connection, SQL or extension. */
public final class PrivateDatabase implements AutoCloseable {
    private final Context context;
    private File file;
    private final AtomicLong epoch=new AtomicLong();
    private volatile CancellationSignal active;
    private SQLiteDatabase database;
    private PrivateDatabase(Context context) { this.context=context; }
    public static PrivateDatabase preview() { return new PrivateDatabase(null); }
    public static PrivateDatabase installed(Context context,String appId) throws FactoryException {
        if(context==null || !context.getPackageName().equals(appId))
            throw new FactoryException("INVALID_CONFIG","Database identity does not match installed application.");
        Context owner=context.getApplicationContext();
        if(owner==null || !owner.getPackageName().equals(appId)) throw new FactoryException("INVALID_CONFIG","Database application context is unavailable.");
        return new PrivateDatabase(owner);
    }
    public long ticket() { return epoch.get(); }
    /** Synchronous authority invalidation; never closes an in-use SQLite connection. */
    public void cancel() { epoch.incrementAndGet(); CancellationSignal signal=active; if(signal!=null) signal.cancel(); }
    /** Invoke on the serialized database worker, after cancel. Preview close discards its RAM data. */
    @Override public synchronized void close() { if(database!=null) { database.close(); database=null; } }
    private void check(long ticket,BooleanSupplier current) throws FactoryException {
        if(ticket!=epoch.get() || !current.getAsBoolean() || Thread.currentThread().isInterrupted())
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
            if(opened.setMaximumSize(DatabaseRequest.MAX_DATABASE_BYTES)>DatabaseRequest.MAX_DATABASE_BYTES) throw quota();
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
            JSONObject schema=StrictJson.object(cursor.getString(0),65536);
            if(schema.length()>DatabaseRequest.MAX_TABLES) throw corrupt();
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
                .put("limits",new JSONObject().put("mainDatabaseBytes",DatabaseRequest.MAX_DATABASE_BYTES)
                        .put("totalDiskHardLimit",false).put("tables",DatabaseRequest.MAX_TABLES).put("columnsPerTable",DatabaseRequest.MAX_COLUMNS)
                        .put("rows",DatabaseRequest.MAX_ROWS).put("cellBytes",DatabaseRequest.MAX_CELL_BYTES).put("rowBytes",DatabaseRequest.MAX_ROW_BYTES)
                        .put("operations",DatabaseRequest.MAX_OPERATIONS).put("migrationSteps",DatabaseRequest.MAX_STEPS)
                        .put("requestBytes",DatabaseRequest.MAX_ARGUMENT_BYTES).put("responseBytes",DatabaseRequest.MAX_RESULT_BYTES)
                        .put("selectRows",DatabaseRequest.MAX_SELECT_ROWS));
    }
    private JSONObject migrate(JSONObject schema,JSONObject args,long ticket,BooleanSupplier current,CancellationSignal signal) throws Exception {
        JSONArray steps=args.getJSONArray("steps");
        for(int i=0;i<steps.length();i++) {
            check(ticket,current); JSONObject step=steps.getJSONObject(i); String table=step.getString("table");
            if("createTable".equals(step.getString("kind"))) {
                if(schema.has(table) || schema.length()>=DatabaseRequest.MAX_TABLES) throw DatabaseRequest.invalid();
                JSONObject columns=step.getJSONObject("columns");
                StringBuilder sql=new StringBuilder("CREATE TABLE ").append(table(table)).append(" (id TEXT PRIMARY KEY NOT NULL COLLATE BINARY");
                for(String column:keys(columns)) sql.append(',').append(column(column)).append(' ').append(sqlType(columns.getJSONObject(column)));
                database.execSQL(sql.append(')').toString()); schema.put(table,new JSONObject(columns.toString()));
            } else {
                JSONObject columns=columns(schema,table); String column=step.getString("column");
                if(columns.has(column) || columns.length()>=DatabaseRequest.MAX_COLUMNS) throw DatabaseRequest.invalid();
                JSONObject definition=step.getJSONObject("definition");
                database.execSQL("ALTER TABLE "+table(table)+" ADD COLUMN "+column(column)+" "+sqlType(definition));
                columns.put(column,new JSONObject(definition.toString()));
            }
        }
        check(ticket,current); enforceRows(schema,signal);
        // New nullable columns also enlarge serialized existing rows. Reject atomically before commit.
        for(String table:keys(schema)) {
            JSONObject columns=columns(schema,table);
            try(Cursor cursor=database.rawQuery(selectSql(table,columns),null,signal)) {
                while(cursor.moveToNext()) { check(ticket,current); if(DatabaseRequest.bytes(row(cursor,columns))>DatabaseRequest.MAX_ROW_BYTES) throw quota(); }
            }
        }
        database.execSQL("UPDATE factory_schema SET schema_json=? WHERE id=1",new Object[]{schema.toString()});
        database.setVersion(args.getInt("toVersion"));
        return new JSONObject().put("version",args.getInt("toVersion")).put("stepsApplied",steps.length()).put("committed",true);
    }
    private JSONObject transact(JSONObject schema,JSONObject args,long ticket,BooleanSupplier current,CancellationSignal signal) throws Exception {
        JSONArray operations=args.getJSONArray("operations"), changes=new JSONArray();
        for(int i=0;i<operations.length();i++) {
            check(ticket,current); JSONObject operation=operations.getJSONObject(i); String table=operation.getString("table"), id=operation.getString("id");
            JSONObject columns=columns(schema,table); String kind=operation.getString("kind"); int changed;
            if("delete".equals(kind)) changed=database.delete(table(table),"id=?",new String[]{id});
            else {
                JSONObject values=operation.getJSONObject("values"); ContentValues content=new ContentValues();
                for(String name:keys(values)) {
                    if(!columns.has(name)) throw DatabaseRequest.invalid();
                    Object value=values.get(name); DatabaseRequest.typed(value,columns.getJSONObject(name)); put(content,column(name),value);
                }
                if("insert".equals(kind)) {
                    for(String name:keys(columns)) if(!values.has(name) && !columns.getJSONObject(name).getBoolean("nullable")) throw DatabaseRequest.invalid();
                    content.put("id",id); database.insertOrThrow(table(table),null,content); changed=1;
                } else {
                    changed=database.update(table(table),content,"id=?",new String[]{id});
                    if(changed!=1) throw new FactoryException("NOT_FOUND","No database row has that ID.");
                }
                try(Cursor cursor=database.rawQuery(selectSql(table,columns)+" WHERE id=?",new String[]{id},signal)) {
                    if(!cursor.moveToFirst() || DatabaseRequest.bytes(row(cursor,columns))>DatabaseRequest.MAX_ROW_BYTES) throw quota();
                }
            }
            changes.put(changed);
        }
        enforceRows(schema,signal); check(ticket,current);
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
            if(!columns.has(name)) throw DatabaseRequest.invalid();
            DatabaseRequest.typed(value,columns.getJSONObject(name));
            if(value==JSONObject.NULL) sql.append(" AND ").append(column(name)).append(" IS NULL");
            else { sql.append(" AND ").append(column(name)).append("=?"); binds.add(value instanceof Boolean?((Boolean)value?"1":"0"):value.toString()); }
        }
        if(args.has("afterId")) { sql.append(" AND id>?"); binds.add(args.getString("afterId")); }
        int limit=args.has("limit")?args.getInt("limit"):50;
        sql.append(" ORDER BY id COLLATE BINARY ASC LIMIT ?"); binds.add(Integer.toString(limit+1));
        JSONArray rows=new JSONArray(); boolean more=false; String last=null;
        try(Cursor cursor=database.rawQuery(sql.toString(),binds.toArray(new String[0]),signal)) {
            while(cursor.moveToNext()) {
                check(ticket,current);
                if(rows.length()==limit) { more=true; break; }
                JSONObject row=row(cursor,columns);
                if(DatabaseRequest.bytes(row)>DatabaseRequest.MAX_ROW_BYTES) throw quota();
                rows.put(row); last=cursor.getString(0);
                if(DatabaseRequest.bytes(rows)>DatabaseRequest.MAX_RESULT_BYTES-1024) throw quota();
            }
        }
        return new JSONObject().put("version",args.getInt("version")).put("rows",rows).put("hasMore",more)
                .put("nextAfterId",more?last:JSONObject.NULL).put("snapshotAcrossPages",false);
    }
    private void enforceRows(JSONObject schema,CancellationSignal signal) throws Exception {
        long total=0;
        for(String table:keys(schema)) try(Cursor cursor=database.rawQuery("SELECT count(*) FROM "+table(table),null,signal)) {
            if(!cursor.moveToFirst()) throw corrupt(); total+=cursor.getLong(0); if(total>DatabaseRequest.MAX_ROWS) throw quota();
        }
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
            DatabaseRequest.typed(value,definition); result.put(name,value); index++;
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
