package com.jarvys.agent;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Narrow peer-host proof for device automation. It does not gate Factory work or installation. */
final class PeerUiAutomationClient {
    static final String DESCRIPTOR = "com.jarvys.agent.PeerUiAutomationGuard.v1";
    private static final String MAIN = "com.jarvys.agent";
    private static final String RECOVERY = "com.jarvys.agent.recoverytest";
    private static final IBinder CALLER_LIFETIME = new Binder();
    private static final int CHECK = IBinder.FIRST_CALL_TRANSACTION;
    private static final int BEGIN = CHECK + 1;
    private static final int END = CHECK + 2;

    interface Proof {
        boolean isValid();
        AutoCloseable beginAction();
    }

    private PeerUiAutomationClient() { }

    static Proof capture(Context context) {
        if (context == null) throw unavailable();
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        String peer = MAIN.equals(app.getPackageName()) ? RECOVERY
                : RECOVERY.equals(app.getPackageName()) ? MAIN : null;
        if (peer == null) throw unavailable();
        Stamp before = installed(app, peer);
        if (before == null) {
            // Newly installed peers invalidate an observation captured when no peer existed.
            return new Proof() {
                @Override public boolean isValid() {
                    try { return installed(app, peer) == null; }
                    catch (RuntimeException failure) { return false; }
                }
                @Override public AutoCloseable beginAction() {
                    if (!isValid()) throw unavailable();
                    return () -> { };
                }
            };
        }
        String authority = peer + ".factory.operations";
        ProviderInfo provider = app.getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null || !peer.equals(provider.packageName) || provider.applicationInfo == null
                || !"com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider".equals(provider.name)
                || provider.applicationInfo.uid != before.uid || !provider.applicationInfo.enabled
                || !provider.exported || !provider.enabled) throw unavailable();
        Bundle request = new Bundle();
        request.putInt("protocolVersion", 1);
        final Bundle reply;
        try { reply = app.getContentResolver().call(Uri.parse("content://" + authority), "guardStatus", null, request); }
        catch (RuntimeException failure) { throw unavailable(); }
        if (!before.equals(installed(app, peer))) throw unavailable();
        return decode(reply, () -> before.equals(installed(app, peer)));
    }

    interface CurrentIdentity { boolean matches(); }

    @SuppressWarnings("deprecation")
    static Proof decode(Bundle reply, CurrentIdentity identity) {
        if (reply == null || reply.hasFileDescriptors() || !reply.keySet().equals(new HashSet<>(Arrays.asList(
                "protocolVersion", "instance", "epoch", "protected", "guard")))
                || !(reply.get("protocolVersion") instanceof Integer) || reply.getInt("protocolVersion") != 1
                || !(reply.get("epoch") instanceof Long) || !(reply.get("protected") instanceof Boolean)
                || !(reply.get("instance") instanceof String) || !reply.getString("instance").matches("[a-f0-9]{64}")
                || reply.getBoolean("protected") || reply.getBinder("guard") == null) throw unavailable();
        IBinder binder = reply.getBinder("guard");
        String instance = reply.getString("instance");
        long epoch = reply.getLong("epoch");
        AtomicBoolean valid = new AtomicBoolean(true);
        return new Proof() {
            private Parcel request() {
                Parcel value = Parcel.obtain();
                value.writeInterfaceToken(DESCRIPTOR);
                value.writeString(instance);
                return value;
            }
            @Override public boolean isValid() {
                if (!valid.get()) return false;
                Parcel data = request(); Parcel response = Parcel.obtain();
                try {
                    if (!identity.matches() || !binder.isBinderAlive()) return false;
                    data.writeLong(epoch);
                    if (!binder.transact(CHECK, data, response, 0)) return false;
                    requireBoundedReply(response);
                    response.readException();
                    boolean current = response.readInt() == 1 && response.dataAvail() == 0;
                    return current && identity.matches();
                } catch (Exception failure) { valid.set(false); return false; }
                finally { data.recycle(); response.recycle(); }
            }
            @Override public AutoCloseable beginAction() {
                if (!isValid()) throw unavailable();
                Parcel data = request(); Parcel response = Parcel.obtain();
                long id;
                try {
                    data.writeLong(epoch); data.writeStrongBinder(CALLER_LIFETIME);
                    if (!binder.transact(BEGIN, data, response, 0)) throw unavailable();
                    requireBoundedReply(response);
                    response.readException();
                    id = response.readLong();
                    if (id <= 0 || response.dataAvail() != 0) throw unavailable();
                } catch (Exception failure) { valid.set(false); throw unavailable(); }
                finally { data.recycle(); response.recycle(); }
                AtomicBoolean closed = new AtomicBoolean();
                AutoCloseable lease = () -> {
                    if (!closed.compareAndSet(false, true)) return;
                    Parcel end = request(); Parcel ended = Parcel.obtain();
                    try {
                        end.writeLong(id);
                        if (!binder.transact(END, end, ended, 0)) throw unavailable();
                        requireBoundedReply(ended);
                        ended.readException();
                        if (ended.readInt() != 1 || ended.dataAvail() != 0) throw unavailable();
                    } catch (Exception failure) { valid.set(false); }
                    finally { end.recycle(); ended.recycle(); }
                };
                if (!isValid()) {
                    try { lease.close(); } catch (Exception ignored) { }
                    throw unavailable();
                }
                return lease;
            }
        };
    }

    @SuppressWarnings("deprecation")
    private static Stamp installed(Context context, String name) {
        PackageManager pm = context.getPackageManager();
        final PackageInfo info;
        try { info = pm.getPackageInfo(name, Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES); }
        catch (PackageManager.NameNotFoundException absent) { return null; }
        ApplicationInfo app = info.applicationInfo;
        if (!name.equals(info.packageName) || app == null || !name.equals(app.packageName)
                || !app.enabled || info.sharedUserId != null) throw unavailable();
        String[] packages = pm.getPackagesForUid(app.uid);
        if (packages == null || packages.length != 1 || !name.equals(packages[0])) throw unavailable();
        Signature[] signers = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signers == null || signers.length != 1) throw unavailable();
        try {
            return new Stamp(app.uid, Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode,
                    info.lastUpdateTime, app.sourceDir, MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Stamp {
        final int uid;
        final long version, updated;
        final String source;
        final byte[] certificate;
        Stamp(int uid, long version, long updated, String source, byte[] certificate) {
            this.uid = uid; this.version = version; this.updated = updated; this.source = source; this.certificate = certificate;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Stamp)) return false;
            Stamp value = (Stamp) other;
            return uid == value.uid && version == value.version && updated == value.updated
                    && Objects.equals(source, value.source) && Arrays.equals(certificate, value.certificate);
        }
        @Override public int hashCode() { return Objects.hash(uid, version, updated, source, Arrays.hashCode(certificate)); }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("User-only memory review in the other Jarvys app cannot be verified; device automation is paused until both installed hosts support the protection protocol");
    }

    private static void requireBoundedReply(Parcel reply) {
        if (reply.dataSize() > 512 || reply.hasFileDescriptors()) throw unavailable();
    }
}
