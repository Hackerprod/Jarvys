package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import com.jarvys.factory.contract.CapabilityCatalog;
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

/** Synthetic installed-source session checks. No approval UI or external effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeOperationSessionsTest {
    private Fixture fixture;
    @Before public void setup() throws Exception { fixture = new Fixture(); }
    @After public void cleanup() throws Exception { fixture.close(); }

    @Test public void authenticatedOperationIsDeliveredWithoutJarvysUi() throws Exception {
        Capture reply = new Capture(); NativeOperationSessions.Lease lease = fixture.authorize(reply);
        fixture.pump();
        assertSame(lease, reply.lease); assertEquals(1, reply.ready); lease.check();
        assertEquals(0, reply.claims); assertEquals(0, reply.releases); assertTrue(fixture.screen.launched.isEmpty());
    }

    @Test public void unavailableHostNeverDeliversAnOperationSession() throws Exception {
        fixture.provider.unavailable = true;
        Capture reply = new Capture(); fixture.authorize(reply); fixture.pump();
        assertEquals("UNAVAILABLE", reply.error); assertEquals(1, reply.failures);
        assertEquals("This operation requires Jarvys installed and active.", reply.message);
        assertEquals(0, reply.ready); assertNull(reply.lease); assertEquals(0, reply.claims);
    }

    @Test public void appOwnedOperationIsDeliveredWithoutCallingTheHostProvider() throws Exception {
        fixture.provider.unavailable = true;
        Capture reply = new Capture(); NativeOperationSessions.Lease lease = fixture.authorizeLocal(reply); idle();
        assertSame(lease, reply.lease); assertEquals(1, reply.ready); assertTrue(lease.local); lease.check();
        assertTrue(fixture.provider.issued.isEmpty()); assertEquals(0, reply.claims);
    }

    @Test public void localLeaseStillEndsWithTheForegroundPageAndCapabilityGate() throws Exception {
        FactoryException denied = assertThrows(FactoryException.class, () -> fixture.sessions.authorizeLocal(
                "location.requestPermission", new JSONObject().put("precision", "approximate"), new Capture()));
        assertEquals("CAPABILITY_DENIED", denied.code);
        Capture reply = new Capture(); NativeOperationSessions.Lease lease = fixture.authorizeLocal(reply); idle();
        fixture.sessions.onPause(); idle();
        assertEquals("CANCELLED", reply.error); assertEquals(1, reply.failures); assertThrows(SecurityException.class, lease::check);
    }

    @Test public void aHostSessionEndingBeforeEffectInvalidatesTheLease() throws Exception {
        Capture reply = new Capture(); NativeOperationSessions.Lease lease = fixture.authorize(reply); fixture.pump();
        fixture.provider.last().state.set(NativeOperationAuthorization.TERMINAL);
        assertThrows(SecurityException.class, lease::check); idle();
        assertEquals("CANCELLED", reply.error); assertEquals(1, reply.failures); assertEquals(0, reply.claims);
        assertTrue(reply.message.contains("requires Jarvys installed and active"));
    }

    @Test public void AndroidSystemHandoffKeepsTheLiveSessionAcrossPauseAndResume() throws Exception {
        Capture reply = new Capture(); NativeOperationSessions.Lease lease = fixture.authorize(reply); fixture.pump();
        lease.externalHandoff(true); fixture.sessions.onPause(); idle();
        assertEquals(0, reply.failures); fixture.sessions.onResume(); lease.check();
        lease.close(); assertEquals(0, reply.failures);
    }

    @Test public void leavingForegroundCancelsAnOperationWithoutSystemHandoff() throws Exception {
        Capture reply = new Capture(); NativeOperationSessions.Lease lease = fixture.authorize(reply); fixture.pump();
        fixture.sessions.onPause(); idle();
        assertEquals("CANCELLED", reply.error); assertEquals(1, reply.failures);
        assertThrows(SecurityException.class, lease::check);
    }

    static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    static final class Capture implements NativeOperationSessions.Callback {
        NativeOperationSessions.Lease lease; String error, message; int claims, releases, ready, failures;
        public boolean current() { return true; }
        public boolean claimUi() { claims++; return true; }
        public void releaseUi() { releases++; }
        public void ready(NativeOperationSessions.Lease value) { lease = value; ready++; }
        public void failed(String code, String message) { error = code; this.message = message; failures++; }
    }
    public static class Screen extends Activity { final List<android.content.Intent> launched = new ArrayList<>(); }
    static final class Worker implements Executor, AutoCloseable {
        final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        final ExecutorService thread = Executors.newSingleThreadExecutor();
        public void execute(Runnable task) { tasks.add(task); }
        void runNext() throws Exception { thread.submit(tasks.remove()).get(5, TimeUnit.SECONDS); }
        public void close() throws Exception { thread.shutdownNow(); assertTrue(thread.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    static final class Fixture implements AutoCloseable {
        final Screen screen; final Worker io = new Worker(); final SessionProvider provider = new SessionProvider();
        final FactoryConfig config; final NativeOperationSessions sessions;
        Fixture() throws Exception { this("[\"network\",\"notifications\",\"speech\",\"share\"]"); }
        Fixture(String capabilities) throws Exception {
            screen = Robolectric.buildActivity(Screen.class).setup().get(); String host = DocumentBrokerIdentity.PRIMARY;
            DocumentBrokerIdentityTest.install(screen, host, new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
            PackageInfo info = screen.getPackageManager().getPackageInfo(host, PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES);
            info.applicationInfo.uid = android.os.Process.myUid(); info.applicationInfo.enabled = true;
            ProviderInfo entry = new ProviderInfo(); entry.authority = host + ".factory.operations"; entry.packageName = host;
            entry.name = "com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider"; entry.enabled = true;
            entry.exported = true; entry.applicationInfo = info.applicationInfo; info.providers = new ProviderInfo[]{entry};
            Shadows.shadowOf(screen.getPackageManager()).installPackage(info);
            Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(android.os.Process.myUid(), host);
            provider.attachInfo(screen, entry); ShadowContentResolver.registerProviderInternal(entry.authority, provider);
            JSONObject configJson = new JSONObject(FactoryDispatcherTest.configuration(capabilities))
                    .put("appId", screen.getPackageName());
            JSONArray requested = new JSONArray(capabilities);
            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < requested.length(); i++) selected.add(requested.getString(i));
            if (CapabilityCatalog.requiresHostBroker(selected))
                configJson.put("documentBroker", new JSONObject().put("packageName", host)
                        .put("certificateSha256", DocumentBrokerIdentityTest.digest()));
            config = FactoryConfig.parse(configJson.toString(), screen.getPackageName());
            sessions = new NativeOperationSessions(screen, config, io, true);
        }
        NativeOperationSessions.Lease authorize(Capture reply) throws Exception {
            return sessions.authorize("share.text", new JSONObject().put("text", "Synthetic").put("title", ""), reply);
        }
        NativeOperationSessions.Lease authorizeLocal(Capture reply) throws Exception {
            return sessions.authorizeLocal("notifications.requestPermission", new JSONObject(), reply);
        }
        void pump() throws Exception { idle(); while (!io.tasks.isEmpty()) { io.runNext(); idle(); } }
        public void close() throws Exception { sessions.close(); idle(); for (Issued value : provider.issued) value.host.close(); io.close(); screen.finish(); }
    }
    static final class Issued {
        final AtomicInteger state; final NativeOperationAuthorization host;
        Issued(String nonce, String binding) {
            state = new AtomicInteger(NativeOperationAuthorization.ACTIVE);
            host = new NativeOperationAuthorization(nonce, binding, SystemClock.elapsedRealtime() + 300_000,
                    uid -> uid == android.os.Process.myUid(), new NativeOperationAuthorization.Authority() {
                        public int status() { return state.get(); }
                        public void checkBeforeEffect() { if (state.get() != NativeOperationAuthorization.ACTIVE) throw new SecurityException(); }
                    }, () -> { });
        }
    }
    public static final class SessionProvider extends ContentProvider {
        boolean unavailable; final List<Issued> issued = new ArrayList<>(); Issued last() { return issued.get(issued.size() - 1); }
        @Override public Bundle call(String method, String arg, Bundle input) {
            assertEquals("acquire", method); assertNull(arg); if (unavailable) return null;
            try {
                NativeOperationRequest request = NativeOperationRequest.parse(input.getString("operation"), input.getString("details"));
                String nonce = input.getString("nonce"), binding = request.binding(nonce); Issued value = new Issued(nonce, binding); issued.add(value);
                Bundle out = new Bundle(); out.putInt("protocolVersion", 1); out.putString("nonce", nonce); out.putString("binding", binding);
                out.putBinder("lease", value.host); out.putInt("status", NativeOperationAuthorization.ACTIVE); return out;
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
