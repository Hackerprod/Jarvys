package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;

/** Synthetic runtime/host IPC and lifecycle. Host clipboard effects are tested separately. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 32}, manifest = Config.NONE, shadows = FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeClipboardTest {
    private static final int REQUEST = 49, HOST_UID = 23001;
    private static final String TEXT = "synthetic private clipboard text \u00e9";

    public static class Screen extends Activity {
        Intent launched;
        boolean reject;
        @Override public boolean hasWindowFocus() { return true; }
        @Override public void startActivityForResult(Intent intent, int request) {
            assertEquals(REQUEST, request);
            if (reject) throw new android.content.ActivityNotFoundException();
            launched = intent;
        }
    }
    private static class Host implements FactoryRuntime.Host {
        final MemoryBackend data = new MemoryBackend();
        boolean preview, active = true;
        public InputStream open(String path) { return new ByteArrayInputStream(new byte[0]); }
        public String configuration() { return FactoryDispatcherTest.configuration("[\"clipboard\"]"); }
        public boolean isPreview() { return preview; }
        public boolean isActive() { return active; }
        public BoundedStore.Backend storage() { return data; }
    }
    private static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        int count;
        public void postMessage(String text) {
            try { value = new JSONObject(text); count++; } catch (Exception e) { throw new AssertionError(e); }
        }
        public void postMessage(byte[] bytes) { throw new AssertionError(); }
        String error() throws Exception {
            assertNotNull(value); assertFalse(value.toString(), value.getBoolean("ok"));
            return value.getJSONObject("error").getString("code");
        }
    }
    private FactoryRuntime runtime;
    private Screen screen;
    private Host host;
    private int next;
    private static Field field(String name) throws Exception {
        Field value = FactoryRuntime.class.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private Object work() throws Exception { return field("clipboardWrite").get(runtime); }
    private ThreadPoolExecutor worker() throws Exception { return (ThreadPoolExecutor) field("io").get(runtime); }
    @Before public void setup() throws Exception {
        screen = Robolectric.buildActivity(Screen.class).setup().get();
        installHost(new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        host = new Host(); runtime = new FactoryRuntime(screen, new FrameLayout(screen), host);
        setCapabilities("[\"clipboard\"]");
        field("store").set(runtime, new BoundedStore(host.data)); runtime.onResume();
    }
    private void installHost(Signature certificate) throws Exception {
        DocumentBrokerIdentityTest.install(screen, DocumentBrokerIdentity.PRIMARY, certificate);
        int flags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo info = screen.getPackageManager().getPackageInfo(DocumentBrokerIdentity.PRIMARY, flags);
        // Robolectric returns a copy: install the changed package instead of mutating a getter result.
        info.applicationInfo.uid = HOST_UID;
        Shadows.shadowOf(screen.getPackageManager()).installPackage(info);
        Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(HOST_UID, DocumentBrokerIdentity.PRIMARY);
        assertEquals(HOST_UID, screen.getPackageManager().getApplicationInfo(DocumentBrokerIdentity.PRIMARY, 0).uid);
    }
    private void setCapabilities(String capabilities) throws Exception {
        field("config").set(runtime, FactoryConfig.parsePreview(new JSONObject(FactoryDispatcherTest.configuration(capabilities))
                .put("documentBroker", new JSONObject().put("packageName", DocumentBrokerIdentity.PRIMARY)
                        .put("certificateSha256", DocumentBrokerIdentityTest.digest())).toString()));
    }
    @After public void stop() throws Exception {
        runtime.close(); assertTrue(worker().awaitTermination(5, TimeUnit.SECONDS)); idle(); screen.finish(); ShadowBinder.reset();
    }
    private Response request(String method, JSONObject args) throws Exception {
        Response reply = new Response();
        Method receive = FactoryRuntime.class.getDeclaredMethod("receive", String.class, JavaScriptReplyProxy.class);
        receive.setAccessible(true);
        receive.invoke(runtime, new JSONObject().put("v", 1).put("id", "clipboard" + ++next)
                .put("method", method).put("args", args).toString(), reply);
        return reply;
    }
    private Response copy() throws Exception { return request("clipboard.write", new JSONObject().put("text", TEXT)); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Intent result() { return new Intent().putExtra("nonce", screen.launched.getStringExtra("nonce")).putExtra("copied", true); }
    private ClipboardWriteControl.Registration register(CountDownLatch cancelled) throws Exception {
        return register(cancelled, HOST_UID);
    }
    private ClipboardWriteControl.Registration register(CountDownLatch cancelled, int uid) throws Exception {
        ShadowBinder.setCallingUid(uid);
        IBinder control = screen.launched.getExtras().getBinder("control");
        String nonce = screen.launched.getStringExtra("nonce");
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            return thread.submit(() -> {
                assertEquals(uid, android.os.Binder.getCallingUid());
                return ClipboardWriteControl.register(control, nonce, sourceUid -> true, cancelled::countDown);
            }).get(5, TimeUnit.SECONDS);
        } finally { thread.shutdownNow(); }
    }
    @Test public void clipboardOnlyLaunchesPinnedHostWithExactBoundedRequestAndReturnsNull() throws Exception {
        Response reply = copy(); assertNotNull(screen.launched); assertNull(reply.value);
        assertEquals(DocumentBrokerIdentity.PRIMARY, screen.launched.getComponent().getPackageName());
        assertEquals("com.jarvys.agent.apkfactory.FactoryClipboardActivity", screen.launched.getComponent().getClassName());
        assertEquals(TEXT, screen.launched.getStringExtra("text"));
        assertEquals(new HashSet<>(Arrays.asList("protocolVersion", "nonce", "text", "control")), screen.launched.getExtras().keySet());
        assertEquals(1, screen.launched.getIntExtra("protocolVersion", 0));
        assertTrue(screen.launched.getStringExtra("nonce").matches("[a-f0-9]{64}"));
        assertNotNull(screen.launched.getExtras().getBinder("control"));
        assertNull(screen.launched.getAction()); assertNull(screen.launched.getData()); assertNull(screen.launched.getClipData());
        assertNull(screen.launched.getSelector()); assertNull(screen.launched.getPackage()); assertNull(screen.launched.getType());
        assertNull(screen.launched.getCategories()); assertEquals(0, screen.launched.getFlags());
        assertEquals(Collections.singleton("clipboard"), ((FactoryConfig) field("config").get(runtime)).capabilities);
        FileShareTransfer.reserve().close();
        runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result());
        assertTrue(reply.value.toString(), reply.value.getBoolean("ok")); assertTrue(reply.value.isNull("result"));
        assertEquals(1, reply.count); assertNull(work()); assertNull(field("uiOwner").get(runtime));
    }
    @Test public void expectedHostPausePreservesControlAndDefersResultUntilResume() throws Exception {
        Response reply = copy(); CountDownLatch cancelled = new CountDownLatch(1);
        ClipboardWriteControl.Registration registration = register(cancelled);
        try {
            runtime.onPause(); assertFalse(registration.isRevoked());
            runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result());
            assertNull(reply.value); assertNotNull(work()); assertFalse(registration.isRevoked());
            runtime.onResume(); assertTrue(reply.value.getBoolean("ok")); assertTrue(reply.value.isNull("result"));
            assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertEquals(1, reply.count); assertNull(work());
        } finally { registration.close(); }
    }
    @Test public void documentCancellationDoesNotRevokeAnUnrelatedClipboardRequest() throws Exception {
        setCapabilities("[\"clipboard\",\"documents\"]"); Response reply = copy(); CountDownLatch cancelled = new CountDownLatch(1);
        ClipboardWriteControl.Registration registration = register(cancelled);
        try {
            assertTrue(request("documents.cancel", new JSONObject()).value.getBoolean("ok"));
            assertFalse(registration.isRevoked()); assertNull(reply.value); assertEquals("BUSY", copy().error());
            runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result()); assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        } finally { registration.close(); }
    }
    @Test public void resetRevokesNativeControlAndKeepsTombstoneUntilOriginalCallback() throws Exception {
        Response old = copy(); Intent oldResult = result(); CountDownLatch cancelled = new CountDownLatch(1);
        ClipboardWriteControl.Registration registration = register(cancelled);
        try {
            runtime.reset(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertTrue(registration.isRevoked());
            assertNull(old.value); assertNotNull(work()); assertEquals("BUSY", copy().error());
            runtime.onActivityResult(REQUEST, Activity.RESULT_OK, oldResult); assertNull(work()); assertNull(old.value);
            copy(); assertNotNull(work()); assertNotEquals(oldResult.getStringExtra("nonce"), result().getStringExtra("nonce"));
        } finally { registration.close(); }
    }
    @Test public void closeRevokesRegisteredControlWithoutSendingStalePageReply() throws Exception {
        Response reply = copy(); CountDownLatch cancelled = new CountDownLatch(1);
        ClipboardWriteControl.Registration registration = register(cancelled);
        try {
            runtime.close(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertTrue(registration.isRevoked());
            assertNull(reply.value); assertNull(copy().value);
        } finally { registration.close(); }
    }
    @Test public void expiryRevokesHostButRetainsLaunchedTombstoneUntilCallback() throws Exception {
        Response reply = copy(); CountDownLatch cancelled = new CountDownLatch(1);
        ClipboardWriteControl.Registration registration = register(cancelled);
        try {
            runtime.onPause(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(300000, TimeUnit.MILLISECONDS);
            assertEquals("TIMEOUT", reply.error()); assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertTrue(registration.isRevoked()); assertNotNull(work()); runtime.onResume(); assertEquals("BUSY", copy().error());
            runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result()); assertNull(work()); assertEquals(1, reply.count);
        } finally { registration.close(); }
    }
    @Test public void deadlineIsCheckedAtResultEvenBeforeTimerRuns() throws Exception {
        Response reply = copy(); org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300000));
        runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result()); assertEquals("CLIPBOARD_UNAVAILABLE", reply.error());
        assertNull(work()); idle(); assertEquals(1, reply.count);
    }
    @Test public void sourceBackgroundAfterResultRevokesRatherThanReusesApproval() throws Exception {
        Response reply = copy(); runtime.onPause(); runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result());
        assertNull(reply.value); runtime.onPause(); assertEquals("CANCELLED", reply.error()); assertNull(work());
        runtime.onResume(); assertEquals(1, reply.count);
    }
    @Test public void previewHonestlySimulatesWithoutHostOrClipboardAuthority() throws Exception {
        host.preview = true; Response reply = copy(); JSONObject simulated = reply.value.getJSONObject("result");
        assertTrue(simulated.getBoolean("simulated")); assertFalse(simulated.getBoolean("performed"));
        assertEquals("preview", simulated.getString("mode")); assertEquals("clipboard.write", simulated.getString("operation"));
        assertNull(screen.launched); assertNull(work()); assertNull(field("uiOwner").get(runtime));
    }
    @Test public void wrongCertificateAndAmbiguousInstalledHostCannotLaunch() throws Exception {
        installHost(new Signature(new byte[] {9, 8, 7})); assertEquals("UNAVAILABLE", copy().error()); assertNull(screen.launched);
        installHost(new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        DocumentBrokerIdentityTest.install(screen, DocumentBrokerIdentity.RECOVERY, new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        assertEquals("UNAVAILABLE", copy().error()); assertNull(screen.launched); assertNull(work());
    }
    @Test public void nativeRegistrationRejectsWrongAndSharedUid() throws Exception {
        copy(); assertThrows(ExecutionException.class, () -> register(new CountDownLatch(1), HOST_UID + 1));
        Shadows.shadowOf(screen.getPackageManager()).setPackagesForUid(HOST_UID, DocumentBrokerIdentity.PRIMARY, "com.other.app");
        assertThrows(ExecutionException.class, () -> register(new CountDownLatch(1)));
    }
    @Test public void changedPinnedHostCannotRegisterOrReturnSuccess() throws Exception {
        Response reply = copy(); Intent receipt = result(); installHost(new Signature(new byte[] {9, 8, 7}));
        assertThrows(ExecutionException.class, () -> register(new CountDownLatch(1)));
        runtime.onActivityResult(REQUEST, Activity.RESULT_OK, receipt); assertEquals("CLIPBOARD_UNAVAILABLE", reply.error());
        assertNull(work()); assertEquals(1, reply.count);
    }
    @Test public void launchFailureReleasesOwnerForFreshRequest() throws Exception {
        screen.reject = true; assertEquals("UNAVAILABLE", copy().error()); assertNull(work()); assertNull(field("uiOwner").get(runtime));
        screen.reject = false; copy(); assertNotNull(screen.launched); assertNotNull(work());
    }
    @Test public void cancellationCannotClaimCopyAndDuplicateCallbackCannotReplyAgain() throws Exception {
        Response reply = copy(); runtime.onActivityResult(REQUEST, Activity.RESULT_CANCELED, null);
        assertEquals("CLIPBOARD_UNAVAILABLE", reply.error()); assertNull(work());
        runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result()); assertEquals(1, reply.count);
    }
    @Test public void forgedOrExpandedReceiptNeverClaimsCopy() throws Exception {
        for (int variant = 0; variant < 14; variant++) {
            Response reply = copy(); Intent output = result();
            switch (variant) {
                case 0: output.putExtra("nonce", "wrong"); break;
                case 1: output.putExtra("copied", false); break;
                case 2: output.putExtra("copied", "true"); break;
                case 3: output.putExtra("unexpected", true); break;
                case 4: output.setData(android.net.Uri.parse("content://synthetic/forged")); break;
                case 5: output.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); break;
                case 6: output.setClipData(android.content.ClipData.newPlainText("synthetic", "untrusted")); break;
                case 7: output.setSelector(new Intent()); break;
                case 8: output.setAction(Intent.ACTION_SEND); break;
                case 9: output.setPackage(DocumentBrokerIdentity.PRIMARY); break;
                case 10: output.setComponent(screen.launched.getComponent()); break;
                case 11: output.setType("text/plain"); break;
                case 12: output.addCategory(Intent.CATEGORY_DEFAULT); break;
                case 13: output.removeExtra("copied"); break;
            }
            runtime.onActivityResult(REQUEST, Activity.RESULT_OK, output);
            assertEquals("CLIPBOARD_UNAVAILABLE", reply.error()); assertNull(work());
            runtime.onActivityResult(REQUEST, Activity.RESULT_OK, result()); assertEquals(1, reply.count);
        }
    }
}
