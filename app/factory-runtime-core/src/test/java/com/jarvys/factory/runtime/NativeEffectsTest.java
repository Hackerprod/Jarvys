package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static com.jarvys.factory.runtime.NativePermissionSessionsTest.idle;
import static org.junit.Assert.*;

/** Adapter integration with synthetic Android permissions, fake HTTP, and silent fake speech engines. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeEffectsTest {
    private NativePermissionSessionsTest.Fixture fixture;
    private NativeEffects effects;
    private ThreadPoolExecutor networkIo;
    private Connection connection;
    private final AtomicInteger opens = new AtomicInteger();
    private final List<Engine> engines = new ArrayList<>();
    private NotificationManager notifications;
    private int sequence;

    @Before public void setup() throws Exception {
        fixture = new NativePermissionSessionsTest.Fixture();
        PackageInfo own = fixture.screen.getPackageManager().getPackageInfo(fixture.config.appId, PackageManager.GET_PERMISSIONS);
        own.requestedPermissions = new String[]{Manifest.permission.POST_NOTIFICATIONS};
        own.applicationInfo.targetSdkVersion = 35;
        Shadows.shadowOf(fixture.screen.getPackageManager()).installPackage(own);
        Shadows.shadowOf(fixture.screen.getPackageManager()).setPackagesForUid(android.os.Process.myUid(), DocumentBrokerIdentity.PRIMARY);
        fixture.screen.getApplicationInfo().targetSdkVersion = 35;
        fixture.screen.getApplication().getApplicationInfo().targetSdkVersion = 35;
        Shadows.shadowOf(fixture.screen.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        notifications = (NotificationManager) fixture.screen.getSystemService(Context.NOTIFICATION_SERVICE);
        Shadows.shadowOf(notifications).setNotificationsEnabled(true);
        connection = new Connection();
        NativeNetwork network = new NativeNetwork(url -> { opens.incrementAndGet(); return connection; }, host -> true, System::nanoTime);
        NativeSpeech speech = new NativeSpeech(fixture.screen, fixture.config.appId, (preferred, initialized) -> {
            Engine engine = new Engine(initialized); engines.add(engine); return engine;
        });
        networkIo = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                task -> { Thread thread = new Thread(task, "synthetic-native-network"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
        effects = new NativeEffects(fixture.screen, fixture.config, fixture.sessions, fixture.io, true, networkIo, network, speech);
    }

    @After public void cleanup() throws Exception {
        effects.close(); connection.release.countDown();
        assertTrue("Synthetic network worker must terminate", networkIo.awaitTermination(5, TimeUnit.SECONDS));
        idle(); fixture.close();
    }

    @Test public void denyLeavesNetworkNotificationAndSpeechAdaptersUntouched() throws Exception {
        fixture.provider.deny = true;
        Result network = perform("network.request", new JSONObject().put("url", "https://synthetic.invalid/"));
        Result channel = perform("notifications.channel", new JSONObject().put("id", "denied").put("name", "Denied"));
        Result speech = perform("speech.speak", new JSONObject().put("text", "Never spoken"));
        fixture.pump();
        for (Result result : new Result[]{network, channel, speech}) {
            assertEquals("PERMISSION_DENIED", result.error); assertEquals(1, result.calls); assertEquals(0, result.successes);
        }
        assertEquals(0, opens.get()); assertTrue(engines.isEmpty());
        assertNull(notifications.getNotificationChannel("factory.denied"));
        assertEquals(0, Shadows.shadowOf(notifications).size()); assertTrue(fixture.screen.reviews.isEmpty());
    }

    @Test public void cancellationBypassesFullNetworkQueueAndLateTransportResultCannotSucceed() throws Exception {
        connection.block = true;
        JSONObject args = new JSONObject().put("url", "https://synthetic.invalid/").put("timeoutMs", 60000);
        Result running = perform("network.request", args); fixture.pump();
        assertTrue("Fake request must reach its controlled response boundary", connection.entered.await(5, TimeUnit.SECONDS));
        Result queued = perform("network.request", args); fixture.pump();
        assertEquals(1, networkIo.getQueue().size());
        Result rejected = perform("network.request", args); fixture.pump();
        assertEquals("BUSY", rejected.error); assertEquals(1, rejected.calls); assertEquals(1, opens.get());
        Result cancellation = perform("network.cancel", new JSONObject()); idle();
        assertEquals(2, cancellation.value.getInt("cancelled")); assertFalse(cancellation.value.getBoolean("rollbackConfirmed"));
        assertEquals("CANCELLED", running.error); assertEquals("CANCELLED", queued.error);
        assertEquals(3, fixture.provider.issued.size()); // Cancellation itself needs no queued authorization.
        connection.release.countDown(); networkIo.shutdown();
        assertTrue(networkIo.awaitTermination(5, TimeUnit.SECONDS)); idle();
        assertEquals(1, running.calls); assertEquals(1, queued.calls); assertEquals(1, rejected.calls);
        assertEquals(0, running.successes); assertEquals(0, queued.successes); assertEquals(1, opens.get());
        assertTrue(connection.disconnects.get() > 0);
    }

    @Test public void queuedNetworkSuccessRemainsCancellableUntilMainThreadDelivery() throws Exception {
        Result pending = queueSuccessfulNetworkResult();
        Result cancellation = perform("network.cancel", new JSONObject());
        assertEquals(1, cancellation.value.getInt("cancelled"));
        idle();
        assertEquals("CANCELLED", pending.error); assertEquals(1, pending.calls);
        assertEquals(0, pending.successes); assertNull(pending.value);
    }

    @Test public void queuedNetworkSuccessRechecksLivePolicyBeforeMainThreadDelivery() throws Exception {
        Result pending = queueSuccessfulNetworkResult();
        // No revocation hint is sent: delivery itself must perform a fresh host CHECK.
        fixture.provider.last().state.set(NativeOperationAuthorization.TERMINAL);
        idle();
        assertNotNull(pending.error); assertEquals(1, pending.calls);
        assertEquals(0, pending.successes); assertNull(pending.value);
    }

    @Test public void queuedNetworkSuccessCannotBeDeliveredAfterPauseOrRevivedOnResume() throws Exception {
        Result pending = queueSuccessfulNetworkResult();
        effects.onPause(); fixture.sessions.onPause(); idle();
        assertNotNull(pending.error); assertEquals(1, pending.calls);
        assertEquals(0, pending.successes); assertNull(pending.value);
        fixture.sessions.onResume(); effects.onResume(); fixture.pump();
        assertEquals(1, pending.calls); assertEquals(0, pending.successes);
    }

    private Result queueSuccessfulNetworkResult() throws Exception {
        connection.block = true;
        Result pending = perform("network.request", new JSONObject().put("url", "https://synthetic.invalid/"));
        fixture.pump();
        assertTrue(connection.entered.await(5, TimeUnit.SECONDS));
        connection.release.countDown();
        // A worker barrier proves the response has returned and completion is queued. Do not idle main yet.
        networkIo.submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertEquals(1, connection.inputOpens.get()); assertEquals(0, pending.calls);
        assertFalse("Authority must stay live while successful delivery is queued", fixture.provider.last().host.isClosed());
        return pending;
    }

    @Test public void notificationResultWaitsForResumeAndReadsOsGrantInsteadOfCallbackClaim() throws Exception {
        Result denied = perform("notifications.requestPermission", new JSONObject()); fixture.pump();
        assertTrue(effects.hasPendingPermission()); assertEquals(1, denied.claims);
        assertEquals(NativeEffects.NOTIFICATION_PERMISSION_REQUEST, Shadows.shadowOf(fixture.screen).getLastRequestedPermission().requestCode);
        assertArrayEquals(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                Shadows.shadowOf(fixture.screen).getLastRequestedPermission().requestedPermissions);
        effects.onPause(); fixture.sessions.onPause();
        permissionResult(PackageManager.PERMISSION_GRANTED); // Callback lies: OS permission remains denied.
        permissionResult(PackageManager.PERMISSION_DENIED); // Duplicate must not replace the accepted result.
        fixture.pump(); assertEquals(0, denied.calls); assertEquals(0, denied.releases);
        fixture.sessions.onResume(); effects.onResume(); fixture.pump();
        assertEquals("not_granted", denied.value.getString("permission"));
        assertEquals("permission_required", denied.value.getString("status")); assertEquals(1, denied.calls); assertEquals(1, denied.releases);
        Result granted = perform("notifications.requestPermission", new JSONObject()); fixture.pump();
        Shadows.shadowOf(fixture.screen.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        permissionResult(PackageManager.PERMISSION_DENIED); // OS now grants it, regardless of the callback integer.
        fixture.pump();
        assertEquals("granted", granted.value.getString("permission")); assertEquals("ready", granted.value.getString("status"));
        assertEquals(1, granted.calls); assertEquals(1, granted.releases); assertFalse(effects.hasPendingPermission());
        assertEquals(0, Shadows.shadowOf(notifications).size());
    }

    @Test public void busySpeechJobCannotCloseTheActiveEngineOrSettleItsReply() throws Exception {
        Result first = perform("speech.speak", new JSONObject().put("text", "First")); fixture.pump();
        Engine engine = engines.get(0); engine.ready(); idle();
        Result busy = perform("speech.voices", new JSONObject()); fixture.pump();
        assertEquals("BUSY", busy.error); assertEquals(1, busy.calls);
        assertEquals(0, engine.stops); assertEquals(0, engine.shutdowns); assertEquals(0, first.calls); assertEquals(1, engines.size());
        engine.progress.done(engine.id); idle();
        assertEquals("completed", first.value.getString("status")); assertEquals(1, first.calls);
        assertEquals(1, engine.shutdowns); assertEquals(0, engine.stops);
    }

    @Test public void staleSpeechCancellationCannotCloseANewerJobUsingTheSameAdapter() throws Exception {
        Result old = perform("speech.speak", new JSONObject().put("text", "Old")); fixture.pump();
        Engine first = engines.get(0); first.ready(); idle();
        first.progress.error(first.id, TextToSpeech.ERROR_NETWORK); // Queue old failure without processing it yet.
        Result next = perform("speech.speak", new JSONObject().put("text", "New"));
        fixture.io.runNext(); // Queue the next authorized delivery behind the old failure.
        idle(); // Old failure enqueues close; next delivery takes ownership before that close runs.
        assertEquals("SPEECH_ERROR", old.error); assertEquals(1, old.calls);
        assertEquals(2, engines.size()); assertEquals(0, next.calls);
        Engine second = engines.get(1); assertEquals(0, second.stops); assertEquals(0, second.shutdowns);
        second.ready(); idle(); second.progress.done(second.id); idle();
        assertEquals("completed", next.value.getString("status")); assertEquals(1, next.calls);
        first.progress.done(first.id); idle(); assertEquals(1, old.calls); assertEquals(1, next.calls);
    }

    private Result perform(String method, JSONObject args) throws Exception {
        Result result = new Result();
        BridgeProtocol.Request request = BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                new JSONObject().put("v", 1).put("id", "effect_" + ++sequence).put("method", method).put("args", args).toString(), fixture.config);
        effects.perform(request, result); return result;
    }
    private void permissionResult(int grant) {
        assertTrue(effects.onRequestPermissionsResult(NativeEffects.NOTIFICATION_PERMISSION_REQUEST,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, new int[]{grant}));
    }
    private static final class Result implements NativeEffects.Reply {
        JSONObject value;
        String error;
        int calls, successes, claims, releases;
        boolean held;
        public boolean current() { return true; }
        public boolean claimUi() { assertFalse(held); held = true; claims++; return true; }
        public void releaseUi() { if (held) { held = false; releases++; } }
        public void ok(Object value) { this.value = (JSONObject) value; calls++; successes++; }
        public void fail(String code, String message) { error = code; calls++; }
    }
    private static final class Connection extends HttpURLConnection {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger disconnects = new AtomicInteger(), inputOpens = new AtomicInteger();
        volatile boolean block;
        Connection() throws Exception { super(new URL("https://synthetic.invalid/")); }
        @Override public int getResponseCode() throws IOException {
            entered.countDown();
            if (block) try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test did not release the synthetic response");
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException(stopped); }
            return 200;
        }
        @Override public InputStream getInputStream() { inputOpens.incrementAndGet(); return new ByteArrayInputStream(new byte[0]); }
        @Override public void connect() { }
        @Override public void disconnect() { disconnects.incrementAndGet(); }
        @Override public boolean usingProxy() { return false; }
    }
    private static final class Engine implements NativeSpeech.Engine {
        final NativeSpeech.Initialization initialized;
        NativeSpeech.Progress progress;
        String id;
        int stops, shutdowns;
        Engine(NativeSpeech.Initialization initialized) { this.initialized = initialized; }
        void ready() { initialized.complete(TextToSpeech.SUCCESS); }
        public int listen(NativeSpeech.Progress value) { progress = value; return TextToSpeech.SUCCESS; }
        public Set<Voice> voices() { return Collections.emptySet(); }
        public Voice currentVoice() { return null; }
        public String defaultEngine() { return "synthetic.silent.engine"; }
        public int language(Locale value) { return TextToSpeech.LANG_AVAILABLE; }
        public int voice(Voice value) { return TextToSpeech.SUCCESS; }
        public int rate(float value) { return TextToSpeech.SUCCESS; }
        public int pitch(float value) { return TextToSpeech.SUCCESS; }
        public int maxInputLength() { return 4000; }
        public int speak(String text, float volume, String value) { id = value; return TextToSpeech.SUCCESS; }
        public int stop() { stops++; return TextToSpeech.SUCCESS; }
        public void shutdown() { shutdowns++; }
    }
}
