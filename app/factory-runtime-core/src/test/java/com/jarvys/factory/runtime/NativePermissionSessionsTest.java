package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/** Synthetic signed host, real lease protocol, and controlled Android handoffs. No external effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativePermissionSessionsTest {
    private Fixture fixture;
    @Before public void setup() throws Exception { fixture = new Fixture(); }
    @After public void cleanup() throws Exception { fixture.close(); }

    @Test public void allowDeliversWithoutLaunchingAnActivityOrClaimingUi() throws Exception {
        Capture reply = new Capture();
        fixture.screen.focused = false; // ALLOW does not need the ASK review's focus/UI latch.
        NativePermissionSessions.Lease lease = fixture.authorize(reply);
        fixture.pump();
        assertSame(lease, reply.lease); assertEquals(1, reply.ready);
        lease.check();
        assertEquals(0, reply.claims); assertEquals(0, reply.releases);
        assertTrue(fixture.screen.reviews.isEmpty()); assertFalse(fixture.sessions.hasPendingReview());
    }

    @Test public void denyNeverDeliversAuthorityOrLaunchesReview() throws Exception {
        fixture.provider.deny = true;
        Capture reply = new Capture(); fixture.authorize(reply); fixture.pump();
        assertEquals("PERMISSION_DENIED", reply.error); assertEquals(1, reply.failures);
        assertEquals(0, reply.ready); assertNull(reply.lease); assertEquals(0, reply.claims);
        assertTrue(fixture.screen.reviews.isEmpty()); assertTrue(fixture.provider.issued.isEmpty());
    }

    @Test public void cancelledAskRetainsTombstoneUntilReturnThenAllowsNextReview() throws Exception {
        fixture.provider.nextState = NativeOperationAuthorization.PENDING;
        Capture first = new Capture(); fixture.authorize(first); fixture.pump();
        fixture.sessions.cancelAll(); fixture.sessions.cancelAll(); idle();
        assertEquals("CANCELLED", first.error); assertEquals(1, first.failures);
        assertTrue(fixture.sessions.hasPendingReview()); assertEquals(0, first.releases);
        Capture blocked = new Capture(); fixture.authorize(blocked); fixture.pump();
        assertEquals("BUSY", blocked.error); assertEquals(1, fixture.screen.reviews.size());
        assertTrue(fixture.sessions.onActivityResult(NativePermissionSessions.REVIEW_REQUEST, Activity.RESULT_CANCELED, null));
        fixture.sessions.onActivityResult(NativePermissionSessions.REVIEW_REQUEST, Activity.RESULT_CANCELED, null);
        idle();
        assertFalse(fixture.sessions.hasPendingReview()); assertEquals(1, first.releases);
        Capture next = new Capture(); fixture.authorize(next); fixture.pump();
        assertEquals(2, fixture.screen.reviews.size()); assertEquals(1, next.claims); assertNull(next.error);
    }

    @Test public void revokedAskCannotSucceedWhenItsOldActivityReturns() throws Exception {
        fixture.provider.nextState = NativeOperationAuthorization.PENDING;
        Capture reply = new Capture(); NativePermissionSessions.Lease lease = fixture.authorize(reply); fixture.pump();
        fixture.provider.last().state.set(NativeOperationAuthorization.TERMINAL);
        assertThrows(SecurityException.class, lease::check); idle();
        assertEquals("CANCELLED", reply.error); assertEquals(1, reply.failures);
        assertEquals(0, reply.releases); assertTrue(fixture.sessions.hasPendingReview());
        fixture.sessions.onActivityResult(NativePermissionSessions.REVIEW_REQUEST, Activity.RESULT_OK, returned(lease));
        fixture.pump();
        assertEquals(0, reply.ready); assertEquals(1, reply.failures); assertEquals(1, reply.releases);
        Capture next = new Capture(); fixture.authorize(next); fixture.pump();
        assertEquals(2, fixture.screen.reviews.size()); assertEquals(1, next.claims);
    }

    @Test public void approvalReturnedBeforeResumeWaitsForForegroundAndFocusAndSettlesOnce() throws Exception {
        fixture.provider.nextState = NativeOperationAuthorization.PENDING;
        Capture reply = new Capture(); NativePermissionSessions.Lease lease = fixture.authorize(reply); fixture.pump();
        fixture.screen.focused = false; fixture.sessions.onPause();
        fixture.provider.last().state.set(NativeOperationAuthorization.APPROVED);
        fixture.sessions.onActivityResult(NativePermissionSessions.REVIEW_REQUEST, Activity.RESULT_OK, returned(lease));
        fixture.sessions.onActivityResult(NativePermissionSessions.REVIEW_REQUEST, Activity.RESULT_CANCELED, null);
        fixture.pump(); assertEquals(0, reply.ready); assertEquals(0, reply.releases); assertNull(reply.error);
        fixture.sessions.onResume(); fixture.pump(); assertEquals(0, reply.ready);
        fixture.screen.focused = true; fixture.sessions.onWindowFocusChanged(true); fixture.pump();
        assertEquals(1, reply.ready); assertEquals(1, reply.releases); assertSame(lease, reply.lease);
        fixture.sessions.onResume(); fixture.sessions.onWindowFocusChanged(true); fixture.pump();
        assertEquals(1, reply.ready); assertEquals(1, reply.releases); assertNull(reply.error);
    }

    @Test public void successfulActivityResultWithAnotherNonceCannotAuthorizeTheRequest() throws Exception {
        fixture.provider.nextState = NativeOperationAuthorization.PENDING;
        Capture reply = new Capture(); NativePermissionSessions.Lease lease = fixture.authorize(reply); fixture.pump();
        fixture.provider.last().state.set(NativeOperationAuthorization.APPROVED);
        String wrongNonce = (lease.nonce.charAt(0) == 'f' ? "e" : "f") + lease.nonce.substring(1);
        fixture.sessions.onActivityResult(NativePermissionSessions.REVIEW_REQUEST, Activity.RESULT_OK,
                new Intent().putExtra("nonce", wrongNonce));
        fixture.pump();
        assertEquals("PERMISSION_DENIED", reply.error); assertEquals(0, reply.ready); assertEquals(1, reply.releases);
    }

    static Intent returned(NativePermissionSessions.Lease lease) { return new Intent().putExtra("nonce", lease.nonce); }
    static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    static final class Capture implements NativePermissionSessions.Callback {
        NativePermissionSessions.Lease lease;
        String error;
        int claims, releases, ready, failures;
        boolean held;
        public boolean current() { return true; }
        public boolean claimUi() { claims++; assertFalse(held); held = true; return true; }
        public void releaseUi() { assertTrue("Only the owner can release review UI", held); held = false; releases++; }
        public void ready(NativePermissionSessions.Lease value) { lease = value; ready++; }
        public void failed(String code, String message) { error = code; failures++; }
    }

    public static class Screen extends Activity {
        boolean focused = true;
        final List<Intent> reviews = new ArrayList<>();
        @Override public boolean hasWindowFocus() { return focused; }
        @Override public void startActivityForResult(Intent intent, int request) {
            assertEquals(NativePermissionSessions.REVIEW_REQUEST, request); reviews.add(intent);
        }
    }

    /** Queues work deterministically but executes Binder connection/polling off the Android main thread. */
    static final class Worker implements Executor, AutoCloseable {
        final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final ExecutorService thread = Executors.newSingleThreadExecutor();
        public void execute(Runnable task) { tasks.add(task); }
        void runNext() throws Exception { thread.submit(tasks.remove()).get(5, TimeUnit.SECONDS); }
        public void close() throws Exception {
            thread.shutdownNow(); assertTrue(thread.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    static final class Fixture implements AutoCloseable {
        final Screen screen;
        final Worker io = new Worker();
        final PolicyProvider provider = new PolicyProvider();
        final FactoryConfig config;
        final NativePermissionSessions sessions;
        Fixture() throws Exception {
            screen = Robolectric.buildActivity(Screen.class).setup().get();
            String host = DocumentBrokerIdentity.PRIMARY;
            DocumentBrokerIdentityTest.install(screen, host, new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
            PackageInfo info = screen.getPackageManager().getPackageInfo(host,
                    PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES);
            info.applicationInfo.uid = android.os.Process.myUid(); info.applicationInfo.enabled = true;
            ProviderInfo entry = new ProviderInfo(); entry.authority = host + ".factory.operations";
            entry.packageName = host; entry.name = "com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider";
            entry.enabled = true; entry.exported = true; entry.applicationInfo = info.applicationInfo;
            info.providers = new ProviderInfo[]{entry};
            Shadows.shadowOf(screen.getPackageManager()).installPackage(info);
            Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(android.os.Process.myUid(), host);
            provider.attachInfo(screen, entry); ShadowContentResolver.registerProviderInternal(entry.authority, provider);
            config = FactoryConfig.parse(new JSONObject(FactoryDispatcherTest.configuration("[\"network\",\"notifications\",\"speech\"]"))
                    .put("appId", screen.getPackageName()).put("documentBroker", new JSONObject().put("packageName", host)
                            .put("certificateSha256", DocumentBrokerIdentityTest.digest())).toString(), screen.getPackageName());
            sessions = new NativePermissionSessions(screen, config, io, true);
        }
        NativePermissionSessions.Lease authorize(Capture reply) throws Exception {
            return sessions.authorize("notifications.requestPermission", new JSONObject(), reply);
        }
        void pump() throws Exception {
            idle();
            while (!io.tasks.isEmpty()) { io.runNext(); idle(); }
        }
        public void close() throws Exception {
            sessions.close(); idle();
            for (Issued value : provider.issued) value.host.close();
            io.close(); screen.finish();
        }
    }

    static final class Issued {
        final AtomicInteger state;
        final NativeOperationAuthorization host;
        Issued(String nonce, String binding, int initial) {
            state = new AtomicInteger(initial);
            host = new NativeOperationAuthorization(nonce, binding, SystemClock.elapsedRealtime() + 300_000,
                    uid -> uid == android.os.Process.myUid(), new NativeOperationAuthorization.Authority() {
                        public int status() { return state.get(); }
                        public void checkBeforeEffect() { if (state.get() != NativeOperationAuthorization.APPROVED) throw new SecurityException(); }
                    }, () -> { });
        }
    }

    public static final class PolicyProvider extends ContentProvider {
        int nextState = NativeOperationAuthorization.APPROVED;
        boolean deny;
        final List<Issued> issued = new ArrayList<>();
        Issued last() { return issued.get(issued.size() - 1); }
        @Override public Bundle call(String method, String arg, Bundle input) {
            assertEquals("acquire", method); assertNull(arg);
            if (deny) return null;
            try {
                NativeOperationRequest request = NativeOperationRequest.parse(input.getString("operation"), input.getString("details"));
                String nonce = input.getString("nonce"), binding = request.binding(nonce);
                Issued value = new Issued(nonce, binding, nextState); issued.add(value);
                Bundle out = new Bundle(); out.putInt("protocolVersion", 1); out.putString("nonce", nonce);
                out.putString("binding", binding); out.putBinder("lease", value.host); out.putInt("status", nextState);
                if (nextState == NativeOperationAuthorization.PENDING) out.putString("reviewToken", binding);
                return out;
            } catch (Exception invalid) { throw new AssertionError(invalid); }
        }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
