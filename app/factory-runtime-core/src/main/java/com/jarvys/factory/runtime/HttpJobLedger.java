package com.jarvys.factory.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.UserManager;
import java.io.File;
import static com.jarvys.factory.runtime.HttpJobEngine.*;

/** Credential-protected, no-backup durable payload/state/result inbox; no backup export path. */
public final class HttpJobLedger implements Store,AutoCloseable {
    private static final String[] COLUMNS={"app","cert","apk","receipt","version","limit_records","limit_bytes","payload","token","android_id","state","revision","reason","process","attempt","result","acknowledged","created","updated","fingerprint"};
    private final Helper helper;private final String ownPackage;
    public HttpJobLedger(Context context){
        if(context==null||context.isDeviceProtectedStorage())throw new IllegalArgumentException("Credential-protected context required");
        UserManager users=(UserManager)context.getSystemService(Context.USER_SERVICE);
        if(users==null||!users.isUserUnlocked())throw new IllegalStateException("Job inbox requires unlocked private storage");
        Context app=context.getApplicationContext();if(app==null)app=context;ownPackage=app.getPackageName();
        helper=new Helper(app,new File(app.getNoBackupFilesDir(),"factory-http-jobs-v1.db").getAbsolutePath());
    }
    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context,String path){super(context,path,null,1);}
        @Override public void onConfigure(SQLiteDatabase db){
            db.execSQL("PRAGMA synchronous=FULL");
            try(Cursor c=db.rawQuery("PRAGMA journal_mode=DELETE",null)){if(!c.moveToFirst()||!"delete".equalsIgnoreCase(c.getString(0)))throw new IllegalStateException("Rollback journal required");}
            cap(db,67108864,false);
        }
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL");
            db.execSQL("CREATE TABLE jobs (owner TEXT NOT NULL, id TEXT NOT NULL, app TEXT NOT NULL, cert TEXT NOT NULL, apk TEXT NOT NULL, receipt TEXT NOT NULL, version INTEGER NOT NULL, limit_records INTEGER NOT NULL, limit_bytes INTEGER NOT NULL, payload TEXT NOT NULL, fingerprint TEXT NOT NULL, token TEXT NOT NULL UNIQUE, android_id INTEGER NOT NULL UNIQUE, state TEXT NOT NULL, revision INTEGER NOT NULL, reason TEXT NOT NULL, process TEXT NOT NULL, attempt TEXT NOT NULL, result TEXT NOT NULL, acknowledged INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, accounted_bytes INTEGER NOT NULL, PRIMARY KEY(owner,id))");
            db.execSQL("CREATE TABLE counter (singleton INTEGER PRIMARY KEY CHECK(singleton=1), next_id INTEGER NOT NULL)");
            db.execSQL("INSERT INTO counter VALUES (1,1242562560)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Explicit job ledger migration required");}
        @Override public void onDowngrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Job ledger downgrade denied");}
    }
    @Override public synchronized Row insert(Source source,HttpJobRequest request,String admissionToken,long now)throws FactoryException{
        if(!ownPackage.equals(source.appId))throw new SecurityException("Wrong private owner");
        SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            Row existing=get(db,source.owner,request.jobId);
            if(existing!=null){if(!existing.request.fingerprint.equals(request.fingerprint))throw new FactoryException("CONFLICT","Job ID already binds different content.");db.setTransactionSuccessful();return existing;}
            long count=scalar(db,"SELECT COUNT(*) FROM jobs"),cost=HttpJobRequest.bytes(request.payloadJson())+NetworkRequest.MAX_RESULT_BYTES+4096L;
            if(count>=source.limits.records||scalar(db,"SELECT COALESCE(SUM(accounted_bytes),0) FROM jobs")+cost+65536>source.limits.storageBytes)throw capacity();
            cap(db,source.limits.storageBytes,true);
            long androidId=scalar(db,"SELECT next_id FROM counter WHERE singleton=1");
            if(androidId>1258291199L)throw capacity();
            db.execSQL("UPDATE counter SET next_id=next_id+1 WHERE singleton=1");
            ContentValues v=new ContentValues();v.put("owner",source.owner);v.put("id",request.jobId);v.put("app",source.appId);v.put("cert",source.certificateSha256);v.put("apk",source.apkSha256);v.put("receipt",source.recordSha256);v.put("version",source.versionCode);v.put("limit_records",source.limits.records);v.put("limit_bytes",source.limits.storageBytes);
            v.put("payload",request.payloadJson());v.put("fingerprint",request.fingerprint);v.put("token",HttpJobRequest.id(admissionToken));v.put("android_id",androidId);v.put("state",State.ACCEPTED.name());v.put("revision",0L);v.put("reason","durably_admitted");v.put("process","");v.put("attempt","");v.put("result","");v.put("acknowledged",0);v.put("created",now);v.put("updated",now);v.put("accounted_bytes",cost);
            db.insertOrThrow("jobs",null,v);Row row=get(db,source.owner,request.jobId);db.setTransactionSuccessful();return row;
        }catch(SQLiteException failure){throw storage(failure);}finally{db.endTransaction();}
    }
    @Override public synchronized void checkBudget(Source source,Row row)throws FactoryException{
        if(!ownPackage.equals(source.appId)||!source.owner.equals(row.source.owner))throw new SecurityException("Current source owner differs");
        SQLiteDatabase db=helper.getWritableDatabase();
        if(scalar(db,"SELECT COUNT(*) FROM jobs")>source.limits.records||scalar(db,"SELECT COALESCE(SUM(accounted_bytes),0) FROM jobs")+65536>source.limits.storageBytes)throw capacity();
        try{cap(db,source.limits.storageBytes,true);}catch(SQLiteException failure){throw storage(failure);}
    }
    @Override public synchronized Row get(String owner,String id)throws FactoryException{
        try{return get(helper.getReadableDatabase(),owner,id);}catch(SQLiteException failure){throw storage(failure);}
    }
    private static Row get(SQLiteDatabase db,String owner,String id)throws FactoryException{
        try(Cursor c=db.query("jobs",COLUMNS,"owner=? AND id=?",new String[]{owner,id},null,null,null,"1")){return c.moveToFirst()?read(c):null;}
    }
    @Override public synchronized Row byToken(String token)throws FactoryException{
        HttpJobRequest.id(token);
        try(Cursor c=helper.getReadableDatabase().query("jobs",COLUMNS,"token=?",new String[]{token},null,null,null,"1")){return c.moveToFirst()?read(c):null;}
        catch(SQLiteException failure){throw storage(failure);}
    }
    @Override public synchronized Row change(Row expected,State state,String reason,String process,String attempt,String result,boolean ack,long now)throws FactoryException{
        if(!ownPackage.equals(expected.source.appId)||result==null||HttpJobRequest.bytes(result)>NetworkRequest.MAX_RESULT_BYTES)throw new IllegalArgumentException();
        SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
        try{
            ContentValues v=new ContentValues();v.put("state",state.name());v.put("reason",reason);v.put("process",process);v.put("attempt",attempt);v.put("result",result);v.put("acknowledged",ack?1:0);v.put("updated",now);v.put("revision",expected.revision+1);
            int count=db.update("jobs",v,"owner=? AND id=? AND token=? AND revision=? AND state=?",new String[]{expected.source.owner,expected.request.jobId,expected.token,Long.toString(expected.revision),expected.state.name()});
            Row next=count==1?get(db,expected.source.owner,expected.request.jobId):null;db.setTransactionSuccessful();return next;
        }catch(SQLiteException failure){throw storage(failure);}finally{db.endTransaction();}
    }
    /** Independent cleanup after a JobService stop/deadline, even if authorization/transport is stuck. */
    public synchronized void interrupted(String token,String attempt,long now)throws FactoryException{
        Row row=byToken(token);
        if(row==null||!attempt.equals(row.attempt))return;
        if(row.state==State.RUNNING||(row.state==State.PREPARING&&"authorizing_replay".equals(row.reason)))change(row,State.UNKNOWN,"jobservice_stopped_after_reservation","","","",false,now);
        else if(row.state==State.PREPARING)change(row,State.QUEUED,"jobservice_stopped_before_effect","","","",false,now);
    }
    @Override public synchronized boolean forget(Row row)throws FactoryException{
        if(!terminal(row.state))throw new FactoryException("UNRESOLVED_JOB","Active jobs cannot be forgotten.");
        try{return helper.getWritableDatabase().delete("jobs","owner=? AND id=? AND token=? AND revision=?",new String[]{row.source.owner,row.request.jobId,row.token,Long.toString(row.revision)})==1;}
        catch(SQLiteException failure){throw storage(failure);}
    }
    private static Row read(Cursor c)throws FactoryException{
        try{
            Source source=new Source(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),new Limits(c.getInt(5),c.getLong(6)));
            String raw=c.getString(7);if(HttpJobRequest.bytes(raw)>BridgeProtocol.MAX_MESSAGE_BYTES)throw new IllegalArgumentException();
            HttpJobRequest request=HttpJobRequest.parse(raw);if(!request.fingerprint.equals(c.getString(19)))throw new IllegalArgumentException();
            String result=c.getString(15);if(HttpJobRequest.bytes(result)>NetworkRequest.MAX_RESULT_BYTES)throw new IllegalArgumentException();
            return new Row(source,request,c.getString(8),c.getInt(9),State.valueOf(c.getString(10)),c.getLong(11),c.getString(12),c.getString(13),c.getString(14),result,c.getInt(16)!=0,c.getLong(17),c.getLong(18));
        }catch(Exception invalid){throw new FactoryException("STORAGE_ERROR","Private job record is invalid.");}
    }
    private static long scalar(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){if(!c.moveToFirst())throw new IllegalStateException();return c.getLong(0);}}
    private static void cap(SQLiteDatabase db,long bytes,boolean strict){
        long pageSize=scalar(db,"PRAGMA page_size"),requested=bytes/pageSize;
        try(Cursor c=db.rawQuery("PRAGMA max_page_count="+requested,null)){if(!c.moveToFirst()||(strict&&c.getLong(0)>requested))throw new android.database.sqlite.SQLiteFullException();}
    }
    private static FactoryException capacity(){return new FactoryException("QUOTA_EXCEEDED","Current signed job inbox capacity is exhausted; acknowledge and forget terminal records explicitly.");}
    private static FactoryException storage(SQLiteException error){return error instanceof android.database.sqlite.SQLiteFullException?capacity():new FactoryException("STORAGE_ERROR","Private job state could not be confirmed.");}
    public synchronized void close(){helper.close();}
}
