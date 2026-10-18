package com.jarvys.factory.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.UserManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;
import static com.jarvys.factory.runtime.NotificationScheduleMachine.*;

/** Private, credential-protected, no-backup SQLite ledger. No payload comes from alarm/tap extras. */
public final class NotificationScheduleLedger implements Store, NotificationActionEvents.Store, AutoCloseable {
    private static final String[] ROW = {"app_id","cert","apk","receipt","version_code","exact_capability",
            "limit_schedules","limit_events","limit_tombstones","limit_bytes","request","alarm_token","state","revision",
            "reason","process","attempt","created_at","updated_at","request_fingerprint","artifact","source_fingerprint","kind","notification_capability","scheduling_capability"};
    private final Helper helper;
    private final String ownPackage;
    private Source currentSource;
    /** Called only after current installed-artifact verification; provenance never grants current authority. */
    public synchronized void bindCurrentSource(Source source) {
        if (!ownPackage.equals(source.appId)) throw new SecurityException();
        currentSource=source; cap(helper.getWritableDatabase(),source.limits.ledgerBytes,false);
    }
    public NotificationScheduleLedger(Context context) {
        if (context == null || context.isDeviceProtectedStorage()) throw new IllegalArgumentException("Credential-protected context required");
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (users == null || !users.isUserUnlocked()) throw new IllegalStateException("Notification ledger requires unlocked storage");
        Context app = context.getApplicationContext(); if (app == null) app = context;
        ownPackage = app.getPackageName();
        helper = new Helper(app, new File(app.getNoBackupFilesDir(), "factory-notification-ledger-v1.db").getAbsolutePath());
    }
    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context, String path) { super(context, path, null, 1); }
        @Override public void onConfigure(SQLiteDatabase db) {
            db.setForeignKeyConstraintsEnabled(true);
            db.execSQL("PRAGMA synchronous=FULL");
            try (Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE",null)) { if (!mode.moveToFirst() || !"delete".equalsIgnoreCase(mode.getString(0))) throw new IllegalStateException("Rollback journal required"); }
        }
        @Override public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            // max_page_count is connection-local: restore the strictest persisted source budget on every reopen.
            long bytes = scalar(db,"SELECT COALESCE(MAX(limit_bytes),16777216) FROM schedules",null);
            cap(db,bytes,false);
        }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL");
            db.execSQL("CREATE TABLE schedules (artifact TEXT NOT NULL, source_fingerprint TEXT NOT NULL, schedule_id TEXT NOT NULL, app_id TEXT NOT NULL, cert TEXT NOT NULL, apk TEXT NOT NULL, receipt TEXT NOT NULL, version_code INTEGER NOT NULL, exact_capability INTEGER NOT NULL, notification_capability INTEGER NOT NULL, scheduling_capability INTEGER NOT NULL, limit_schedules INTEGER NOT NULL, limit_events INTEGER NOT NULL, limit_tombstones INTEGER NOT NULL, limit_bytes INTEGER NOT NULL, kind TEXT NOT NULL, request TEXT NOT NULL, request_fingerprint TEXT NOT NULL, alarm_token TEXT NOT NULL UNIQUE, state TEXT NOT NULL, revision INTEGER NOT NULL, reason TEXT NOT NULL, process TEXT NOT NULL, attempt TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, accounted_bytes INTEGER NOT NULL, PRIMARY KEY(artifact,schedule_id))");
            db.execSQL("CREATE INDEX schedule_state ON schedules(artifact,state)");
            db.execSQL("CREATE TABLE actions (token TEXT PRIMARY KEY, event_id TEXT NOT NULL UNIQUE, artifact TEXT NOT NULL, schedule_id TEXT NOT NULL, notification_id INTEGER NOT NULL, ordinal INTEGER NOT NULL, expires_at INTEGER, enabled INTEGER NOT NULL DEFAULT 0, consumed INTEGER NOT NULL DEFAULT 0, received_at INTEGER, UNIQUE(artifact,schedule_id,ordinal), FOREIGN KEY(artifact,schedule_id) REFERENCES schedules(artifact,schedule_id))");
            db.execSQL("CREATE INDEX action_owner ON actions(artifact,notification_id)");
            db.execSQL("CREATE TABLE inbox (event_id TEXT PRIMARY KEY, token TEXT NOT NULL UNIQUE REFERENCES actions(token), artifact TEXT NOT NULL, received_at INTEGER NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX inbox_owner ON inbox(artifact,acknowledged,received_at,event_id)");
            db.execSQL("CREATE TABLE recovery (singleton INTEGER PRIMARY KEY CHECK(singleton=1), cursor TEXT)");
            db.execSQL("INSERT INTO recovery(singleton,cursor) VALUES (1,NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("Explicit notification ledger migration required"); }
        @Override public void onDowngrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("Notification ledger downgrade denied"); }
    }
    @Override public synchronized Row insert(Source source, NotificationScheduleRequest.Schedule request, long now) throws FactoryException {
        if(request==null)throw new IllegalArgumentException();return insertRow(source,request,request.notification,"",now);
    }
    @Override public synchronized Row insertImmediate(Source source, NotificationScheduleRequest.Notification notification, String process, long now) throws FactoryException {
        if(notification==null || notification.actions.isEmpty())throw new IllegalArgumentException();return insertRow(source,null,notification,process,now);
    }
    private Row insertRow(Source source,NotificationScheduleRequest.Schedule request,NotificationScheduleRequest.Notification notification,String process,long now) throws FactoryException {
        if (!ownPackage.equals(source.appId)) throw new SecurityException("Wrong notification owner");
        SQLiteDatabase db = helper.getWritableDatabase();
        // Page-level cap is global to this private ledger, including historical artifacts. Never delete to fit.
        try { cap(db,source.limits.ledgerBytes,true); }
        catch (android.database.sqlite.SQLiteFullException full) { throw capacity(); }
        db.beginTransaction();
        try {
            String token=NotificationActionEvents.randomToken();
            String recordId=request==null?"@"+token:request.scheduleId;
            Row existing = request==null?null:get(db, source.ownerFingerprint,recordId);
            if (existing != null) {
                if (!existing.fingerprint.equals(request.fingerprint())) throw new FactoryException("CONFLICT", "Schedule ID already binds different content.");
                db.setTransactionSuccessful(); return existing;
            }
            long live = scalar(db, "SELECT COUNT(*) FROM schedules WHERE kind='SCHEDULED' AND state NOT IN ('ACCEPTED','DELIVERY_UNKNOWN','CANCELLED','REVOKED','EXPIRED')", null);
            long liability = scalar(db, "SELECT (SELECT COUNT(*) FROM schedules)+(SELECT COUNT(*) FROM actions)", null);
            long bytes = scalar(db, "SELECT COALESCE(SUM(accounted_bytes),0) FROM schedules", null);
            String json = NotificationScheduleRequest.canonical(request==null?notification.details():request.authorizationDetails(), 0);
            long cost = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2048L + notification.actions.size() * 1024L;
            if ((request!=null && live >= source.limits.schedules) || liability + 1L + notification.actions.size() > source.limits.tombstones
                    || bytes + cost + 65536L > mainBudget(db,source.limits.ledgerBytes)) throw capacity();
            ContentValues values = source(source);
            values.put("schedule_id",recordId);values.put("kind",request==null?Kind.IMMEDIATE.name():Kind.SCHEDULED.name());values.put("request",json);values.put("request_fingerprint",request==null?Row.immediateFingerprint(notification):request.fingerprint());
            values.put("alarm_token", token); values.put("state", State.PENDING.name()); values.put("revision", 0);
            values.put("reason", "durably_admitted"); values.put("process",process); values.put("attempt", "");
            values.put("created_at", now); values.put("updated_at", now); values.put("accounted_bytes", cost);
            db.insertOrThrow("schedules", null, values);
            for (int ordinal = 0; ordinal < notification.actions.size(); ordinal++) {
                ContentValues action = new ContentValues();
                action.put("token", NotificationActionEvents.randomToken()); action.put("event_id", NotificationActionEvents.randomToken());
                action.put("artifact",source.ownerFingerprint);action.put("schedule_id",recordId);
                action.put("notification_id", notification.show.id); action.put("ordinal",ordinal);if(request==null)action.putNull("expires_at");else action.put("expires_at",request.expiresAtEpochMs);
                db.insertOrThrow("actions", null, action);
            }
            Row created=get(db,source.ownerFingerprint,recordId); db.setTransactionSuccessful(); return created;
        } catch (android.database.sqlite.SQLiteFullException full) { throw capacity(); }
        catch (SQLiteException failure) { throw storage(failure); }
        finally { db.endTransaction(); }
    }
    @Override public synchronized Row get(String artifact, String id) throws FactoryException {
        try { return get(helper.getReadableDatabase(), artifact, id); } catch (SQLiteException failure) { throw storage(failure); }
    }
    private Row get(SQLiteDatabase db, String artifact, String id) throws FactoryException {
        try (Cursor c = db.query("schedules", ROW, "artifact=? AND schedule_id=?", new String[]{artifact,id}, null,null,null,"1")) {
            return c.moveToFirst() ? read(c) : null;
        }
    }
    @Override public synchronized Row byAlarmToken(String token) throws FactoryException {
        if (!NotificationActionEvents.validToken(token)) return null;
        try (Cursor c = helper.getReadableDatabase().query("schedules", ROW, "alarm_token=?", new String[]{token}, null,null,null,"1")) {
            return c.moveToFirst() ? read(c) : null;
        } catch (SQLiteException failure) { throw storage(failure); }
    }
    @Override public synchronized Row change(Row expected, State next, String reason, String process, String attempt, long now) throws FactoryException {
        SQLiteDatabase db = helper.getWritableDatabase(); cap(db,currentSource == null ? expected.source.limits.ledgerBytes : currentSource.limits.ledgerBytes,false); db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("state", next.name()); values.put("reason", reason);
            values.put("revision", expected.revision + 1); values.put("process", process == null ? "" : process);
            values.put("attempt", attempt == null ? "" : attempt); values.put("updated_at", now);
            int updated = db.update("schedules", values, "artifact=? AND schedule_id=? AND revision=? AND state=? AND alarm_token=?",
                    new String[]{expected.source.ownerFingerprint, expected.recordId, Long.toString(expected.revision), expected.state.name(), expected.token});
            if (updated != 1) { db.setTransactionSuccessful(); return null; }
            if (next == State.DISPATCH_RESERVED) {
                // New notification generation invalidates previous own-ID action lookups conservatively before posting.
                db.execSQL("UPDATE actions SET enabled=0 WHERE artifact=? AND notification_id=?", new Object[]{expected.source.ownerFingerprint,expected.notification.show.id});
                db.execSQL("UPDATE actions SET enabled=1 WHERE artifact=? AND schedule_id=? AND consumed=0", new Object[]{expected.source.ownerFingerprint,expected.recordId});
            } else if (next != State.ACCEPTED && next != State.DELIVERY_UNKNOWN) {
                db.execSQL("UPDATE actions SET enabled=0 WHERE artifact=? AND schedule_id=?", new Object[]{expected.source.ownerFingerprint,expected.recordId});
            }
            Row row = get(db, expected.source.ownerFingerprint, expected.recordId); db.setTransactionSuccessful(); return row;
        } catch (SQLiteException failure) { throw storage(failure); }
        finally { db.endTransaction(); }
    }
    @Override public synchronized boolean forget(Row expected, boolean acknowledgeUnknown) throws FactoryException {
        if (!terminal(expected.state) || (expected.state == State.DELIVERY_UNKNOWN && !acknowledgeUnknown))
            throw new FactoryException("UNRESOLVED_NOTIFICATION", "Unresolved notification cannot be forgotten.");
        SQLiteDatabase db = helper.getWritableDatabase(); cap(db,currentSource == null ? expected.source.limits.ledgerBytes : currentSource.limits.ledgerBytes,false); db.beginTransaction();
        try {
            Row current = get(db,expected.source.ownerFingerprint,expected.recordId);
            if (current == null || current.revision != expected.revision || !current.token.equals(expected.token)) return false;
            String[] owner = {expected.source.ownerFingerprint,expected.recordId};
            if (scalar(db,"SELECT COUNT(*) FROM inbox i JOIN actions a ON i.token=a.token WHERE a.artifact=? AND a.schedule_id=? AND i.acknowledged=0",owner) != 0)
                throw new FactoryException("UNACKNOWLEDGED_ACTIONS", "Acknowledge pending action events before forgetting this record.");
            db.execSQL("DELETE FROM inbox WHERE token IN (SELECT token FROM actions WHERE artifact=? AND schedule_id=?)",owner);
            db.delete("actions","artifact=? AND schedule_id=?",owner);
            int count = db.delete("schedules","artifact=? AND schedule_id=? AND revision=? AND alarm_token=?",
                    new String[]{expected.source.ownerFingerprint,expected.recordId,Long.toString(expected.revision),expected.token});
            if (count != 1) throw new IllegalStateException("Forget CAS failed");
            db.setTransactionSuccessful(); return true;
        } catch (SQLiteException failure) { throw storage(failure); }
        finally {
            db.endTransaction();
            // Reclaim a bounded number of free pages after explicit forgetting; no unresolved row is selected.
            try (Cursor compact = db.rawQuery("PRAGMA incremental_vacuum(64)",null)) { while (compact.moveToNext()) { } }
            catch (SQLiteException unconfirmedCompaction) { /* Logical forget committed; later explicit cleanup can retry compaction. */ }
        }
    }
    @Override public synchronized List<ActionToken> actions(Row reservation) throws FactoryException {
        List<ActionToken> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("actions", new String[]{"token","event_id","ordinal"},
                "artifact=? AND schedule_id=? AND enabled=1 AND consumed=0", new String[]{reservation.source.ownerFingerprint,reservation.recordId}, null,null,"ordinal", "3")) {
            while (c.moveToNext()) result.add(new ActionToken(c.getString(0),c.getString(1),reservation.notification.actions.get(c.getInt(2))));
            return result;
        } catch (SQLiteException failure) { throw storage(failure); }
    }
    @Override public synchronized List<Row> page(String afterToken, int limit) throws FactoryException {
        if (limit < 1 || limit > 64 || (afterToken != null && !NotificationActionEvents.validToken(afterToken))) throw new IllegalArgumentException();
        List<Row> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().query("schedules",ROW,afterToken == null ? null : "alarm_token>?",
                afterToken == null ? null : new String[]{afterToken},null,null,"alarm_token",Integer.toString(limit))) {
            while (c.moveToNext()) result.add(read(c)); return result;
        } catch (SQLiteException failure) { throw storage(failure); }
    }
    @Override public synchronized String recoveryCursor() throws FactoryException {
        try (Cursor c=helper.getReadableDatabase().rawQuery("SELECT cursor FROM recovery WHERE singleton=1",null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("Missing recovery cursor");
            return c.isNull(0)?null:c.getString(0);
        } catch (SQLiteException failure) { throw storage(failure); }
    }
    @Override public synchronized void checkpointRecovery(String token) throws FactoryException {
        if (token!=null && !NotificationActionEvents.validToken(token)) throw new IllegalArgumentException();
        ContentValues values=new ContentValues(); if(token==null)values.putNull("cursor");else values.put("cursor",token);
        try { if(helper.getWritableDatabase().update("recovery",values,"singleton=1",null)!=1)throw new IllegalStateException("Recovery cursor missing"); }
        catch (SQLiteException failure) { throw storage(failure); }
    }
    /** Used when the packaged host resolver cannot be acquired at all; never loads a WebView. */
    public synchronized void queueUnavailable(String token, String process, long now) throws FactoryException {
        Row row = byAlarmToken(token);
        if (row == null || row.kind!=Kind.SCHEDULED || now < row.request.atEpochMs || (row.state != State.SCHEDULED && row.state != State.PENDING && row.state != State.SCHEDULE_UNKNOWN)) return;
        change(row, State.QUEUED_SESSION_UNAVAILABLE, "receiver_work_pending", process, "", now);
    }
    public synchronized void invalidateVisibleActions(String artifact, int notificationId) {
        ContentValues values = new ContentValues(); values.put("enabled", 0);
        helper.getWritableDatabase().update("actions", values, "artifact=? AND notification_id=?", new String[]{artifact,Integer.toString(notificationId)});
    }
    @Override public synchronized NotificationActionEvents.Event acceptTap(String artifact, String token, long now) {
        if (!NotificationActionEvents.validToken(artifact) || !NotificationActionEvents.validToken(token) || now < 1) return null;
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try (Cursor c = db.query("actions",new String[]{"event_id","schedule_id","ordinal","expires_at","enabled","consumed","received_at"},
                "artifact=? AND token=?",new String[]{artifact,token},null,null,null,"1")) {
            if (!c.moveToFirst() || (!c.isNull(3) && now > c.getLong(3))) return null;
            Row row = get(db, artifact, c.getString(1));
            if (row == null || !ownPackage.equals(row.source.appId) || row.state == State.CANCELLED || row.state == State.REVOKED || row.state == State.EXPIRED) return null;
            boolean consumed = c.getInt(5) != 0;
            Limits limits = currentSource == null ? row.source.limits : currentSource.limits;
            cap(db,limits.ledgerBytes,!consumed);
            if (!consumed && (c.getInt(4) == 0 || (row.state != State.DISPATCH_RESERVED && row.state != State.ACCEPTED && row.state != State.DELIVERY_UNKNOWN))) return null;
            long received = consumed ? c.getLong(6) : now;
            NotificationActionEvents.Event event = NotificationActionEvents.fromPersistedAction(c.getString(0),row.scheduleId,
                    row.notification.show.id, row.notification.actions.get(c.getInt(2)), received);
            if (!consumed) {
                long count = scalar(db,"SELECT COUNT(*) FROM inbox WHERE acknowledged=0",null);
                if (count >= limits.inboxEvents) throw new NotificationLedgerCapacityException(); // Token remains retryable after ack frees capacity.
                ContentValues inbox = new ContentValues(); inbox.put("event_id",event.eventId); inbox.put("token",token);
                inbox.put("artifact",artifact); inbox.put("received_at",now); db.insertOrThrow("inbox",null,inbox);
                ContentValues action = new ContentValues(); action.put("consumed",1); action.put("received_at",now);
                if (db.update("actions",action,"token=? AND consumed=0",new String[]{token}) != 1) throw new IllegalStateException("Tap CAS failed");
            }
            db.setTransactionSuccessful(); return event;
        } catch (android.database.sqlite.SQLiteFullException full) { throw new NotificationLedgerCapacityException(); }
        catch (FactoryException failure) { throw new IllegalStateException("Notification ledger corrupt",failure); }
        finally { db.endTransaction(); }
    }
    @Override public synchronized NotificationActionEvents.Event poll(String artifact) {
        if (!NotificationActionEvents.validToken(artifact)) throw new IllegalArgumentException();
        SQLiteDatabase db = helper.getReadableDatabase(); db.beginTransactionNonExclusive();
        try (Cursor c = db.rawQuery("SELECT i.event_id,a.schedule_id,a.ordinal,i.received_at FROM inbox i JOIN actions a ON i.token=a.token WHERE i.artifact=? AND i.acknowledged=0 ORDER BY i.received_at,i.event_id LIMIT 1",new String[]{artifact})) {
            if (!c.moveToFirst()) return null;
            Row row = get(db,artifact,c.getString(1));
            return NotificationActionEvents.fromPersistedAction(c.getString(0),row.scheduleId,row.notification.show.id,
                    row.notification.actions.get(c.getInt(2)),c.getLong(3));
        } catch (FactoryException failure) { throw new IllegalStateException("Notification ledger corrupt",failure); }
        finally { db.endTransaction(); }
    }
    @Override public synchronized boolean acknowledge(String artifact, String eventId) {
        if (!NotificationActionEvents.validToken(artifact) || !NotificationActionEvents.validToken(eventId)) return false;
        ContentValues values = new ContentValues(); values.put("acknowledged",1);
        return helper.getWritableDatabase().update("inbox",values,"artifact=? AND event_id=?",new String[]{artifact,eventId}) == 1;
    }
    @Override public synchronized void close() { helper.close(); }
    private Row read(Cursor c) throws FactoryException {
        Source source = new Source(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getInt(23)!=0,c.getInt(24)!=0,c.getInt(5)!=0,
                new Limits(c.getInt(6),c.getInt(7),c.getInt(8),c.getLong(9)));
        Kind kind=Kind.valueOf(c.getString(22));
        State state=readState(c.getString(12));
        if (!state.name().equals(c.getString(12))) {
            ContentValues migration=new ContentValues(); migration.put("state",state.name()); migration.put("reason","receiver_work_pending");
            helper.getWritableDatabase().update("schedules",migration,"artifact=? AND schedule_id=? AND state=?",
                    new String[]{c.getString(20),kind==Kind.SCHEDULED?NotificationScheduleRequest.parseSchedule(c.getString(10)).scheduleId:"@"+c.getString(11),c.getString(12)});
        }
        Row row=kind==Kind.SCHEDULED?new Row(source,NotificationScheduleRequest.parseSchedule(c.getString(10)),c.getString(11),state,c.getLong(13),c.getString(14),c.getString(15),c.getString(16),c.getLong(17),c.getLong(18)):
                new Row(source,NotificationScheduleRequest.parseNotification(c.getString(10)),c.getString(11),state,c.getLong(13),c.getString(14),c.getString(15),c.getString(16),c.getLong(17),c.getLong(18));
        if(!ownPackage.equals(source.appId)||!source.ownerFingerprint.equals(c.getString(20))||!source.fingerprint.equals(c.getString(21))||!row.fingerprint.equals(c.getString(19)))
            throw new FactoryException("INVALID_ARTIFACT","Notification ledger identity mismatch.");
        return row;
    }
    private static State readState(String stored) throws FactoryException {
        if ("QUEUED_APPROVAL".equals(stored) || "QUEUED_POLICY_UNAVAILABLE".equals(stored)) return State.QUEUED_SESSION_UNAVAILABLE;
        try { return State.valueOf(stored); }
        catch (RuntimeException invalid) { throw new FactoryException("STORAGE_UNAVAILABLE","Notification ledger state is invalid."); }
    }

    private static ContentValues source(Source source) {
        ContentValues values = new ContentValues(); values.put("artifact",source.ownerFingerprint); values.put("source_fingerprint",source.fingerprint); values.put("app_id",source.appId);
        values.put("cert",source.certificateSha256); values.put("apk",source.artifactSha256); values.put("receipt",source.receiptSha256);
        values.put("version_code",source.versionCode); values.put("exact_capability",source.exactCapability?1:0);
        values.put("notification_capability",source.notificationsCapability?1:0);values.put("scheduling_capability",source.schedulingCapability?1:0);
        values.put("limit_schedules",source.limits.schedules); values.put("limit_events",source.limits.inboxEvents);
        values.put("limit_tombstones",source.limits.tombstones); values.put("limit_bytes",source.limits.ledgerBytes); return values;
    }
    private static long scalar(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor c = db.rawQuery(sql,args)) { if (!c.moveToFirst()) throw new IllegalStateException("Missing quota result"); return c.getLong(0); }
    }
    private static long mainBudget(SQLiteDatabase db,long totalBytes) {
        long page=db.getPageSize();return ((totalBytes-16384L)/3L/page)*page;
    }
    private static void cap(SQLiteDatabase db,long totalBytes,boolean newWork) {
        // Reserve two main-file sizes plus 16 KiB for rollback journal overhead/SQLite metadata.
        // Tiny declarations can honestly be insufficient for the fixed schema; they never expand silently.
        long page = db.getPageSize();
        long mainLimit = mainBudget(db,totalBytes);
        long existing = scalar(db,"PRAGMA page_count",null) * page;
        if (mainLimit < page || (newWork && existing > mainLimit))
            throw new android.database.sqlite.SQLiteFullException("Notification ledger storage budget exceeded");
        // Lowering a signed budget never deletes old data; allow cleanup/status changes without further growth.
        long effective = Math.max(existing,mainLimit);
        if (db.setMaximumSize(effective) > effective) throw new android.database.sqlite.SQLiteFullException("Notification page cap failed");
    }
    private static FactoryException capacity() { return new FactoryException("CAPACITY_EXCEEDED", "Notification ledger budget is full; retained IDs are never evicted automatically."); }
    private static FactoryException storage(Exception cause) { return new FactoryException("STORAGE_ERROR", "Notification ledger transaction failed."); }
}
