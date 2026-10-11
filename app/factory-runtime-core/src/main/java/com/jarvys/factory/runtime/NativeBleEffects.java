package com.jarvys.factory.runtime;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** NativeEffects' BLE slice. Owns actual policy/UI/lifecycle plumbing without borrowing a settled Job. */
final class NativeBleEffects implements AutoCloseable {
    static final int PERMISSION_REQUEST = 58;
    private static final Set<NativeBleEffects> QUARANTINE = ConcurrentHashMap.newKeySet();
    private final Activity activity; private final NativePermissionSessions permissions;
    private final AndroidBlePlatform platform; private final BleCentral.Limits limits;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Grant> grants = ConcurrentHashMap.newKeySet();
    private volatile boolean foreground, closed; private boolean tickScheduled;
    private BleCentral central; private NativeEffects.Reply pageReply;
    private Grant permissionGrant; private String[] permissionNames; private boolean permissionReturned;
    NativeBleEffects(Activity activity, NativePermissionSessions permissions, BleCentral.Limits limits,
                     BlePermissions.ScanPrivacy privacy, boolean foreground) {
        this.activity = activity; this.permissions = permissions; this.limits = limits; this.foreground = foreground;
        platform = new AndroidBlePlatform(activity, privacy, limits); retryQuarantine();
    }
    private final class Grant implements BleCentral.Access {
        final BleRequest request; final NativeEffects.Reply reply;
        final AtomicBoolean ended = new AtomicBoolean(), replySettled = new AtomicBoolean(), revoked = new AtomicBoolean();
        volatile NativePermissionSessions.Lease lease; volatile boolean started;
        Grant(BleRequest request, NativeEffects.Reply reply) { this.request = request; this.reply = reply; }
        boolean current() { return !closed && !ended.get() && !revoked.get() && (replySettled.get() ? reply.pageCurrent() : reply.current()); }
        boolean reviewPending() { NativePermissionSessions.Lease value = lease; return value != null && value.reviewLaunched && !value.reviewReturned; }
        @Override public void check() {
            if (!foreground || !current() || lease == null) throw new SecurityException("BLE page authority ended");
            lease.check();
            if (!"bluetooth.requestPermissions".equals(request.method)) platform.checkPermissions("bluetooth.scan".equals(request.method), !"bluetooth.scan".equals(request.method));
            if (!foreground || !current()) throw new SecurityException("BLE page authority ended");
        }
        /** Resource retirement is independent of bridge settlement. Never recursively cancel from close. */
        @Override public void close() {
            if (!ended.compareAndSet(false,true)) return;
            grants.remove(this); request.clear(); NativePermissionSessions.Lease value = lease; if (value != null) value.close();
        }
        void revoke(String code, String message) {
            revoked.set(true);
            post(() -> {
                if (started && central != null) central.cancelAll();
                failReply(code,message); close(); armTick();
            });
        }
        void failReply(String code, String message) {
            if (replySettled.compareAndSet(false,true) && reply.current()) reply.fail(code,message);
        }
        void deliver(Map<String,Object> value, boolean owned) throws Exception {
            check(); if (!reply.current()) throw new SecurityException("BLE request ended before delivery");
            // Mark retained before posting: Reply.deliver consumes current() synchronously.
            if (!replySettled.compareAndSet(false,true)) throw new SecurityException("BLE reply already settled");
            boolean accepted = false;
            try { accepted = reply.deliver(new JSONObject(value)); }
            finally {
                if (!accepted && owned && central != null) central.cancelAll();
                if (!owned || !accepted) close();
            }
        }
    }
    void perform(String method, JSONObject args, NativeEffects.Reply reply) throws FactoryException {
        BleRequest request = BleRequest.parse(method,args);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!main.post(() -> performParsed(request,reply))) { request.clear(); throw BleCentral.error("BUSY","BLE main-thread dispatcher is unavailable."); }
        } else performParsed(request,reply);
    }
    private void performParsed(BleRequest request, NativeEffects.Reply reply) {
        try {
            if (closed || !foreground || !reply.current() || !reply.pageCurrent()) throw BleCentral.error("CANCELLED","BLE requires the active foreground page.");
            ensureCentral(reply);
            if (!request.needsPolicy()) { immediate(request,reply); request.clear(); armTick(); return; }
            Grant grant = new Grant(request,reply); grants.add(grant);
            try {
                grant.lease = permissions.authorize(request.method,request.authorizationDetails(),new NativePermissionSessions.Callback() {
                    public boolean current() { return grant.current(); }
                    public boolean claimUi() { return foreground && grant.current() && reply.claimUi(); }
                    public void releaseUi() { reply.releaseUi(); }
                    public void ready(NativePermissionSessions.Lease lease) {
                        grant.lease = lease;
                        lease.onCancelled(() -> grant.revoke("PERMISSION_DENIED","BLE permission ended; earlier peripheral effects are not undone."));
                        if (!grant.current()) { grant.close(); return; }
                        execute(grant);
                    }
                    public void failed(String code,String message) { grant.revoke(code,message); }
                });
                if (grant.ended.get()) grant.lease.close();
            } catch (Exception failure) { fail(grant,failure); }
        } catch (Exception failure) {
            request.clear(); if (reply.current()) reply.fail(code(failure),message(failure));
        }
    }
    private void ensureCentral(NativeEffects.Reply reply) {
        if (central != null) return;
        pageReply = reply;
        central = new BleCentral(platform,SystemClock::elapsedRealtime,limits,new BleCentral.Access() {
            public void check() { if (closed || !foreground || !pageReply.pageCurrent()) throw new SecurityException("BLE foreground page ended"); }
            public void close() { /* The page is owned by NativeEffects, not a single BLE resource. */ }
        });
    }
    private void execute(Grant grant) {
        try {
            grant.check(); BleRequest r = grant.request;
            if ("bluetooth.requestPermissions".equals(r.method)) { requestPermissions(grant); return; }
            Map<String,Object> value;
            switch (r.method) {
                case "bluetooth.scan": value = central.scan(r.serviceUuids,r.durationMs,grant); break;
                case "bluetooth.connect": value = central.connect(r.deviceId,grant); break;
                case "bluetooth.services": value = central.services(r.connectionId,r.offset,r.limit,grant); break;
                case "bluetooth.read": value = central.read(r.connectionId,r.attributeId,grant); break;
                case "bluetooth.write": {
                    byte[] bytes = r.bytes();
                    try { value = central.write(r.connectionId,r.attributeId,bytes,r.writeType,grant); }
                    finally { if (bytes != null) Arrays.fill(bytes,(byte)0); r.clear(); }
                    break;
                }
                case "bluetooth.requestMtu": value = central.requestMtu(r.connectionId,r.mtu,grant); break;
                case "bluetooth.subscribe": value = central.subscribe(r.connectionId,r.attributeId,r.mode,grant); break;
                default: throw BleRequest.invalid();
            }
            boolean owned = value.containsKey("operationId") || value.containsKey("scanId") || value.containsKey("connectionId") && !value.containsKey("entries");
            grant.started = owned; grant.deliver(value,owned); armTick();
        } catch (Exception failure) { fail(grant,failure); }
    }
    private void immediate(BleRequest r, NativeEffects.Reply reply) throws Exception {
        if ("bluetooth.poll".equals(r.method)) {
            BleCentral.Poll batch = central.poll(r.handle); batch.check(); checkReply(reply);
            if (reply.deliver(new JSONObject(batch.result))) batch.commit();
            return;
        }
        Map<String,Object> result;
        switch (r.method) {
            case "bluetooth.status": result = platform.status(); break;
            case "bluetooth.stopScan": result = central.stopScan(r.handle); break;
            case "bluetooth.unsubscribe": result = central.unsubscribe(r.subscriptionId); break;
            case "bluetooth.disconnect": result = central.disconnect(r.connectionId); break;
            case "bluetooth.cancel": result = central.cancel(r.operationId); break;
            default: throw BleRequest.invalid();
        }
        checkReply(reply); reply.deliver(new JSONObject(result));
    }
    private void checkReply(NativeEffects.Reply reply) {
        if (closed || !foreground || !reply.current() || !reply.pageCurrent()) throw new SecurityException("BLE reply ownership ended");
    }
    private void requestPermissions(Grant grant) throws Exception {
        BleRequest r = grant.request; List<String> names = platform.missingDangerousPermissions(r.scan,r.connect);
        if (names.isEmpty()) {
            Map<String,Object> value = platform.status(); value.put("permissionRequestStatus","already_granted"); value.put("requestedPermissions",Collections.emptyList());
            grant.deliver(value,false); return;
        }
        if (permissionGrant != null || !grant.reply.claimUi()) throw BleCentral.error("BUSY","Another native UI owns the page.");
        permissionGrant = grant; permissionNames = names.toArray(new String[0]); permissionReturned = false;
        grant.lease.externalHandoff(true);
        try { grant.check(); activity.requestPermissions(permissionNames.clone(),PERMISSION_REQUEST); }
        catch (Exception failure) { releasePermission(); throw failure; }
    }
    boolean onRequestPermissionsResult(int requestCode,String[] names,int[] grants) {
        if (requestCode != PERMISSION_REQUEST) return false;
        Grant grant = permissionGrant;
        if (grant == null || permissionReturned) return true;
        permissionReturned = true;
        if (permissionNames == null || !Arrays.equals(names,permissionNames) || grants == null || grants.length != permissionNames.length)
            grant.revoke("PERMISSION_DENIED","Unexpected Android Bluetooth permission result.");
        finishPermission(); return true;
    }
    private void finishPermission() {
        Grant grant = permissionGrant; if (grant == null || !permissionReturned || !foreground) return;
        List<String> requested = Arrays.asList(permissionNames.clone()); releasePermission();
        try {
            if (!grant.current()) { grant.close(); return; }
            Map<String,Object> value = platform.status(); value.put("permissionRequestStatus","returned"); value.put("requestedPermissions",requested);
            grant.deliver(value,false); // Rechecks policy; status comes from the OS, never callback booleans.
        } catch (Exception failure) { fail(grant,failure); }
    }
    private void releasePermission() {
        Grant grant = permissionGrant; permissionGrant = null; permissionNames = null; permissionReturned = false;
        if (grant != null) { if (grant.lease != null) grant.lease.externalHandoff(false); grant.reply.releaseUi(); }
    }
    boolean hasPendingPermission() { return permissionGrant != null && !permissionReturned; }
    void onPause() {
        foreground = false; main.removeCallbacks(tick); tickScheduled = false;
        List<Grant> previous = new ArrayList<>(grants);
        if (central != null) central.cancelAll();
        for (Grant grant : previous) if (grant != permissionGrant && !grant.reviewPending()) {
            grant.failReply("CANCELLED","BLE foreground ownership ended; earlier peripheral effects are not undone."); grant.close();
        }
    }
    void onResume() { foreground = true; retryQuarantine(); finishPermission(); if (central != null) central.tick(); armTick(); }
    @Override public void close() {
        closed = true; main.removeCallbacks(tick); tickScheduled = false;
        if (central != null) central.close();
        for (Grant grant : new ArrayList<>(grants)) { grant.failReply("CANCELLED","BLE page ended; earlier peripheral effects are not undone."); grant.close(); }
        if (central != null && central.hasUnconfirmedCleanup()) QUARANTINE.add(this); else QUARANTINE.remove(this);
        // An already-visible OS permission UI retains its exact UI owner until its real result returns.
        if (permissionGrant != null && permissionReturned) releasePermission();
    }
    private static void retryQuarantine() {
        for (NativeBleEffects old : new ArrayList<>(QUARANTINE)) {
            if (old.central != null) old.central.close();
            if (old.central == null || !old.central.hasUnconfirmedCleanup()) QUARANTINE.remove(old);
        }
    }
    private final Runnable tick = () -> { tickScheduled = false; if (central != null) central.tick(); armTick(); };
    private void armTick() {
        if (closed || !foreground || central == null || !central.hasResources() || tickScheduled) return;
        tickScheduled = true;
        if (!main.postDelayed(tick,100)) { tickScheduled = false; central.cancelAll(); }
    }
    private void post(Runnable task) { if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else if (!main.post(task)) { if (central != null) central.cancelAll(); close(); } }
    private void fail(Grant grant,Exception failure) {
        if (grant.started && central != null) central.cancelAll();
        grant.failReply(code(failure),message(failure)); grant.close(); armTick();
    }
    private static String code(Exception e) { return e instanceof FactoryException ? ((FactoryException)e).code : e instanceof SecurityException ? "PERMISSION_DENIED" : "NATIVE_ERROR"; }
    private static String message(Exception e) { return e instanceof FactoryException ? e.getMessage() : "BLE operation ended; earlier peripheral effects are not undone."; }
}
