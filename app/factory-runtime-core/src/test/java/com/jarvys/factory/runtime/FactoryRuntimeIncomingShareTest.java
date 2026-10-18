package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
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
import static org.junit.Assert.*;

/** Synthetic runtime ownership/response tests. No Chromium, real provider or cross-app ASK claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, shadows = FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeIncomingShareTest {
    private static final String PAGE = BridgeProtocol.ORIGIN + "/www/index.html";
    public static class TestActivity extends Activity {
        @Override public boolean hasWindowFocus() { return true; }
    }
    private static final class Host implements FactoryRuntime.Host {
        final MemoryBackend storage = new MemoryBackend();
        boolean open = true;
        public InputStream open(String path) { return new ByteArrayInputStream(new byte[0]); }
        public String configuration() { return "unused"; }
        public boolean isPreview() { return false; }
        public boolean isSessionOpen() { return open; }
        public BoundedStore.Backend storage() { return storage; }
    }
    private static final class Response extends JavaScriptReplyProxy {
        JSONObject value;
        boolean lose;
        @Override public void postMessage(String text) {
            if (lose) throw new IllegalStateException("Synthetic uncertain post");
            try { value = new JSONObject(text); } catch (Exception invalid) { throw new AssertionError(invalid); }
        }
        @Override public void postMessage(byte[] data) { throw new AssertionError(); }
    }
    private FactoryRuntime runtime;
    private TestActivity activity;
    private Host host;
    private FactoryConfig config;
    private int sequence;
    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(TestActivity.class).setup().get(); host = new Host();
        config = FactoryConfig.parsePreview(new JSONObject(FactoryDispatcherTest.configuration("[\"documents\",\"incomingShare\"]"))
                .put("incomingShare", new JSONObject().put("mimeTypes", new JSONArray().put("text/plain")).put("multiple", true)).toString());
        runtime = new FactoryRuntime(activity, new FrameLayout(activity), host);
        field("config").set(runtime, config); field("store").set(runtime, new BoundedStore(host.storage));
        call("initializeIncomingShares", new Class<?>[0]); runtime.onResume();
    }
    @After public void cleanup() throws Exception {
        runtime.close(); assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static Field field(String name) throws Exception {
        Field value = FactoryRuntime.class.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = FactoryRuntime.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(runtime, args);
    }
    private void page(String url) throws Exception { call("incomingPageStarted", new Class<?>[]{String.class}, url); }
    private IncomingShareInbox inbox() throws Exception { return (IncomingShareInbox) field("incomingShares").get(runtime); }
    private IncomingShareInbox.Owner owner() throws Exception { return (IncomingShareInbox.Owner) field("incomingPage").get(runtime); }
    private DocumentHandles documents() throws Exception { return (DocumentHandles) field("documents").get(runtime); }
    private ThreadPoolExecutor executor() throws Exception { return (ThreadPoolExecutor) field("io").get(runtime); }
    private IncomingSharePayload payload(String text, boolean attachment) throws Exception {
        return IncomingSharePayload.parse((IncomingSharePayload.Policy) field("incomingSharePolicy").get(runtime),
                IncomingSharePayload.SEND, "text/plain", text, "Untrusted subject",
                attachment ? Collections.singletonList("content://synthetic.incoming/item") : Collections.emptyList());
    }
    private void stageLaunch(String text) throws Exception {
        field("initialIncomingShare").set(runtime, payload(text, false));
        field("awaitingInitialIncomingPage").setBoolean(runtime, true);
    }
    private Response request(String name, JSONObject args) throws Exception {
        Response response = new Response();
        call("receive", new Class<?>[]{String.class, JavaScriptReplyProxy.class}, new JSONObject().put("v", 1)
                .put("id", "incoming" + ++sequence).put("method", name).put("args", args).toString(), response);
        return response;
    }
    @Test public void launchSnapshotTransfersOnlyAfterFirstTrustedPageInvalidation() throws Exception {
        stageLaunch("private initial text"); assertNull(owner());
        page(PAGE); IncomingShareInbox.Owner first = owner();
        assertTrue(first.current()); assertEquals(1, inbox().list(first).length());
        assertNull(field("initialIncomingShare").get(runtime)); assertFalse(field("awaitingInitialIncomingPage").getBoolean(runtime));
        page(PAGE); assertFalse(first.current()); assertEquals(0, inbox().list(owner()).length());
    }
    @Test public void untrustedFirstNavigationCannotTransferLaunchToALaterPage() throws Exception {
        stageLaunch("private initial text"); page("https://untrusted.invalid/");
        assertNull(owner()); assertNull(field("initialIncomingShare").get(runtime));
        page(PAGE); assertEquals(0, inbox().list(owner()).length());
    }
    @Test public void resetBeforeFirstNavigationDropsStagedLaunch() throws Exception {
        stageLaunch("private initial text"); call("invalidate", new Class<?>[0]); page(PAGE);
        assertEquals(0, inbox().list(owner()).length());
    }
    @Test public void newShareIsCopiedWhileOrdinaryLauncherIntentIsIgnored() throws Exception {
        page(PAGE); Intent fresh = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "copied text");
        runtime.onNewIntent(fresh); fresh.putExtra(Intent.EXTRA_TEXT, "mutated afterwards");
        runtime.onNewIntent(new Intent(Intent.ACTION_MAIN).setData(android.net.Uri.parse("jarvys-action://notification/synthetic")));
        JSONArray listed = inbox().list(owner()); assertEquals(1, listed.length());
        IncomingShareInbox.Claim claim = inbox().claim(owner(), listed.getJSONObject(0).getString("id"), null);
        assertEquals("copied text", claim.authorizationDetails().getString("textPreview")); claim.close();
    }
    @Test public void pausePreservesMetadataOwnerButRejectsForegroundMethods() throws Exception {
        page(PAGE); IncomingShareInbox.Owner first = owner(); inbox().capture(first, payload("private source text", false));
        runtime.onPause(); assertTrue(first.current()); assertEquals(1, inbox().list(first).length());
        assertEquals("UNAVAILABLE", request("incomingShare.list", new JSONObject()).value.getJSONObject("error").getString("code"));
        runtime.onResume(); JSONObject reply = request("incomingShare.list", new JSONObject()).value;
        assertTrue(reply.getBoolean("ok")); assertFalse(reply.toString().contains("private source text"));
        assertNull(field("nativeOperations").get(runtime));
    }
    @Test public void listAndDiscardUseNoPolicySession() throws Exception {
        page(PAGE); String id = inbox().capture(owner(), payload("private source text", false));
        Response listed = request("incomingShare.list", new JSONObject());
        assertEquals(id, listed.value.getJSONArray("result").getJSONObject(0).getString("id"));
        Response discarded = request("incomingShare.discard", new JSONObject().put("id", id));
        assertTrue(discarded.value.getJSONObject("result").getBoolean("discarded"));
        assertNull(field("nativeOperations").get(runtime)); assertEquals(0, inbox().list(owner()).length());
    }
    @Test public void sessionClosureEndsStableOwnerWithoutNeedingForeground() throws Exception {
        page(PAGE); IncomingShareInbox.Owner first = owner(); host.open = false; assertFalse(first.current());
    }
    private final class Take {
        final IncomingShareInbox.Claim claim;
        final NativeOperationSessions.Callback callback;
        final Object work;
        final Response response = new Response();
        final NativeOperationSessions.Lease lease;
        final CountDownLatch closed = new CountDownLatch(1);
        final String id;
        Take(boolean attachment) throws Exception {
            id = inbox().capture(owner(), payload("one-time text", attachment)); claim = inbox().claim(owner(), id, null);
            Class<?> replyClass = Class.forName(FactoryRuntime.class.getName() + "$Reply");
            Constructor<?> replyConstructor = replyClass.getDeclaredConstructors()[0]; replyConstructor.setAccessible(true);
            String requestId = "take" + ++sequence;
            Object reply = replyConstructor.newInstance(runtime, requestId, "incomingShare.take", response, field("generation").getInt(runtime));
            @SuppressWarnings("unchecked") Map<String, Object> pending = (Map<String, Object>) field("pending").get(runtime); pending.put(requestId, reply);
            Class<?> workClass = Class.forName(FactoryRuntime.class.getName() + "$IncomingTake");
            Constructor<?> constructor = workClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            work = constructor.newInstance(runtime, owner(), id, claim, documents(), reply); callback = (NativeOperationSessions.Callback) work;
            NativeOperationSessions sessions = new NativeOperationSessions(activity, config, executor(), true);
            field("nativeOperations").set(runtime, sessions);
            lease = sessions.new Lease(NativeOperationRequest.parse("incomingShare.take", claim.authorizationDetails()), callback, true);
            Field leases = NativeOperationSessions.class.getDeclaredField("leases"); leases.setAccessible(true);
            @SuppressWarnings("unchecked") Set<NativeOperationSessions.Lease> active = (Set<NativeOperationSessions.Lease>) leases.get(sessions); active.add(lease);
            Method attach = workClass.getDeclaredMethod("attach", NativeOperationSessions.Lease.class); attach.setAccessible(true); attach.invoke(work, lease);
        }
        void prepare() throws Exception {
            DocumentHandles handles = documents();
            executor().submit(() -> {
                try { claim.prepare(handles, lease::check, uri -> new ByteArrayInputStream(new byte[]{1, 2, 3}) {
                    @Override public void close() { closed.countDown(); }
                }); } catch (Exception failure) { throw new RuntimeException(failure); }
            }).get(5, TimeUnit.SECONDS);
        }
        void deliver() throws Exception { Method method = work.getClass().getDeclaredMethod("deliver"); method.setAccessible(true); method.invoke(work); }
    }
    @Test public void settledReplyRetainsStreamLeaseUntilTheDocumentCloses() throws Exception {
        page(PAGE); Take take = new Take(true); take.prepare(); take.deliver();
        assertTrue(take.response.value.getBoolean("ok")); assertTrue(take.callback.current()); take.lease.check();
        String handle = take.response.value.getJSONObject("result").getJSONArray("attachments").getJSONObject(0).getString("handle");
        assertEquals("AQID", documents().read(handle, 0, 3).base64); documents().close(handle);
        assertTrue(take.closed.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!take.lease.ended.get() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5);
        }
        assertTrue(take.lease.ended.get());
    }
    @Test public void textOnlyDeliverySettlesItsLeaseWithoutWaitingForTheInboxTick() throws Exception {
        page(PAGE); Take take = new Take(false); take.prepare(); take.deliver();
        assertEquals("one-time text", take.response.value.getJSONObject("result").getString("text"));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue(take.lease.ended.get());
    }
    @Test public void reservationFailurePreservesHandleLimitInsteadOfLosingItToFinishedCallback() throws Exception {
        page(PAGE);
        java.util.List<DocumentHandles.Reservation> full = new java.util.ArrayList<>();
        for (int i = 0; i < config.resources.documents.openHandles; i++) full.add(documents().reserveRead());
        Take take = new Take(true); take.callback.ready(take.lease);
        executor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("HANDLE_LIMIT", take.response.value.getJSONObject("error").getString("code"));
        assertFalse(take.claim.committed()); assertEquals(1, inbox().list(owner()).length());
        for (DocumentHandles.Reservation reservation : full) reservation.cancel();
    }
    @Test public void lostPostConsumesOnceAndClosesUndisclosedHandles() throws Exception {
        page(PAGE); Take take = new Take(true); take.prepare(); take.response.lose = true; take.deliver();
        assertTrue(take.claim.committed()); assertEquals(0, inbox().list(owner()).length()); assertFalse(take.callback.current());
        assertTrue(take.closed.await(5, TimeUnit.SECONDS)); assertTrue(take.lease.ended.get());
    }
    @Test public void discardSettlesPendingTakeAndKeepsNoLease() throws Exception {
        page(PAGE); Take take = new Take(false);
        request("incomingShare.discard", new JSONObject().put("id", take.id));
        assertEquals("CANCELLED", take.response.value.getJSONObject("error").getString("code"));
        assertTrue(take.lease.ended.get()); assertFalse(take.claim.committed());
    }
    @Test public void leavingForegroundCancelsAnUnsettledTakeWithoutConsumingText() throws Exception {
        page(PAGE); Take take = new Take(false);
        runtime.onPause(); assertFalse(take.callback.current()); assertTrue(take.lease.ended.get());
        assertFalse(take.claim.committed());
    }
    @Test public void replacementPageCancelsPreparedWorkInsteadOfDeliveringToNewPage() throws Exception {
        page(PAGE); Take take = new Take(true); take.prepare(); page(PAGE); take.deliver();
        assertNull(take.response.value); assertFalse(take.claim.committed()); assertFalse(take.callback.current());
        assertTrue(take.closed.await(5, TimeUnit.SECONDS)); assertEquals(0, inbox().list(owner()).length());
    }
}
