package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/** Synthetic host/provider tests. No user documents, real picker, Chromium, or device isolation claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 32}, manifest = Config.NONE, shadows = FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FactoryRuntimeDocumentsTest {
    private static final Uri URI = Uri.parse("content://factory.synthetic/document/fixture");
    @Implements(value = WebViewFeature.class, isInAndroidSdk = false)
    public static class Features {
        @Implementation public static boolean isFeatureSupported(String feature) {
            return WebViewFeature.WEB_MESSAGE_LISTENER.equals(feature);
        }
    }
    public static class TestActivity extends Activity {
        volatile boolean granted = true;
        volatile CountDownLatch revokeRelease;
        final CountDownLatch revokeEntered = new CountDownLatch(1);
        final AtomicInteger revokes = new AtomicInteger();
        Intent launched;
        @Override public boolean hasWindowFocus() { return true; }
        @Override public void startActivityForResult(Intent intent, int request) { launched = intent; assertEquals(42, request); }
        @Override public int checkUriPermission(Uri uri, int pid, int uid, int flags) {
            return granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
        @Override public void revokeUriPermission(Uri uri, int mode) {
            revokes.incrementAndGet(); revokeEntered.countDown();
            if (revokeRelease != null) awaitUninterruptibly(revokeRelease);
            granted = false;
        }
    }
    public static class Provider extends ContentProvider {
        final AtomicInteger opens = new AtomicInteger();
        volatile boolean fail;
        volatile CancellationSignal signal;
        volatile CountDownLatch openRelease;
        final CountDownLatch openEntered = new CountDownLatch(1);
        volatile String mode;
        File fixture;
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
            this.signal = signal; this.mode = mode; opens.incrementAndGet(); openEntered.countDown();
            if (openRelease != null) awaitUninterruptibly(openRelease);
            if (signal != null) signal.throwIfCanceled();
            if (fail) throw new FileNotFoundException("synthetic open failure");
            return ParcelFileDescriptor.open(fixture, "w".equals(mode) ? ParcelFileDescriptor.MODE_WRITE_ONLY : ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { return openFile(uri, mode, null); }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new AssertionError("Metadata query not allowed"); }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new AssertionError(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new AssertionError(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new AssertionError(); }
    }
    private static class Host implements FactoryRuntime.Host {
        final MemoryBackend backend = new MemoryBackend();
        boolean preview;
        public InputStream open(String path) { return new ByteArrayInputStream(new byte[0]); }
        public String configuration() { return FactoryDispatcherTest.configuration("[\"documents\"]"); }
        public boolean isPreview() { return preview; }
        public BoundedStore.Backend storage() { return backend; }
    }
    private static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        @Override public void postMessage(String text) { try { value = new JSONObject(text); } catch (Exception e) { throw new AssertionError(e); } }
        @Override public void postMessage(byte[] data) { throw new AssertionError("Unexpected binary bridge response"); }
        String error() throws Exception { assertNotNull("Expected native response", value); return value.getJSONObject("error").getString("code"); }
    }
    private final List<FactoryRuntime> runtimes = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private FactoryRuntime runtime;
    private TestActivity activity;
    private Provider provider;
    private Host host;
    private int requestId;
    @Before public void setup() throws Exception {
        provider = new Provider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "factory.synthetic"; info.exported = true;
        provider.attachInfo(org.robolectric.RuntimeEnvironment.getApplication(), info);
        provider.fixture = File.createTempFile("synthetic-document", ".bin");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(provider.fixture)) { out.write(new byte[] {1, 2, 3}); }
        ShadowContentResolver.registerProviderInternal("factory.synthetic", provider);
        create(false);
    }
    private void create(boolean preview) throws Exception {
        activity = Robolectric.buildActivity(TestActivity.class).setup().get();
        DocumentBrokerIdentityTest.install(activity, DocumentBrokerIdentity.PRIMARY, new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        host = new Host(); host.preview = preview;
        runtime = new FactoryRuntime(activity, new FrameLayout(activity), host); runtimes.add(runtime);
        field("config").set(runtime, DocumentBrokerIdentityTest.configuration(DocumentBrokerIdentity.PRIMARY, DocumentBrokerIdentityTest.digest()));
        field("store").set(runtime, new BoundedStore(host.backend));
        runtime.onResume();
    }
    private static Field field(String name) throws Exception {
        Field field = FactoryRuntime.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private DocumentHandles handles() throws Exception { return (DocumentHandles) field("documents").get(runtime); }
    private ThreadPoolExecutor executor() throws Exception { return (ThreadPoolExecutor) field("io").get(runtime); }
    private static int resources() throws Exception { return ((AtomicInteger) field("documentResources").get(null)).get(); }
    private Response request(String method, JSONObject args) throws Exception {
        Response response = new Response();
        Method receive = FactoryRuntime.class.getDeclaredMethod("receive", String.class, JavaScriptReplyProxy.class); receive.setAccessible(true);
        receive.invoke(runtime, new JSONObject().put("v", 1).put("id", "document" + ++requestId).put("method", method).put("args", args).toString(), response);
        return response;
    }
    private Response open() throws Exception { return request("documents.open", new JSONObject().put("mimeType", "application/octet-stream")); }
    private Intent result() {
        assertNotNull("Broker must have launched", activity.launched);
        return new Intent().setData(URI).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("nonce", activity.launched.getStringExtra("nonce"));
    }
    private void idleIo() throws Exception {
        CountDownLatch done = new CountDownLatch(1); executor().execute(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS)); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private CountDownLatch blockIo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); releases.add(release);
        executor().execute(() -> { entered.countDown(); awaitUninterruptibly(release); });
        assertTrue(entered.await(5, TimeUnit.SECONDS)); return release;
    }
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private void awaitCleanup() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (resources() != 0 && System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertEquals("All descriptor and grant admissions must be released", 0, resources());
    }
    @After public void cleanup() throws Exception {
        for (CountDownLatch release : releases) release.countDown();
        for (FactoryRuntime instance : runtimes) instance.close();
        for (FactoryRuntime instance : runtimes) assertTrue(((ThreadPoolExecutor) field("io").get(instance)).awaitTermination(5, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); awaitCleanup();
        assertTrue(provider.fixture.delete());
    }
    @Test public void previewReturnsUnavailableWithoutPickerProviderOrFakeHandle() throws Exception {
        host.preview = true;
        Response response = open(); assertEquals("UNAVAILABLE", response.error());
        assertNull(activity.launched); assertEquals(0, provider.opens.get());
        assertFalse(response.value.toString().contains("handle"));
    }
    @Test public void matchingNonceOpensExactlyOneReadDescriptorAndReturnsOpaqueHandle() throws Exception {
        Response response = open(); runtime.onActivityResult(42, Activity.RESULT_OK, result()); idleIo();
        assertEquals(1, provider.opens.get()); assertEquals("r", provider.mode);
        JSONObject value = response.value.getJSONObject("result");
        String handle = value.getString("handle"); assertTrue(handle.matches("[a-f0-9]{64}"));
        assertEquals("read", value.getString("mode")); assertFalse(value.toString().contains("content://"));
        assertEquals("AQID", handles().read(handle, 0, 3).base64);
    }
    @Test public void resultBeforeResumeDoesNotTouchProvider() throws Exception {
        Response response = open(); Intent result = result(); runtime.onPause();
        runtime.onActivityResult(42, Activity.RESULT_OK, result);
        assertEquals(0, provider.opens.get()); assertNull(response.value);
        runtime.onResume(); idleIo(); assertEquals(1, provider.opens.get()); assertNotNull(response.value);
    }
    @Test public void wrongNonceNeverOpensProvider() throws Exception {
        Response response = open(); runtime.onActivityResult(42, Activity.RESULT_OK, result().putExtra("nonce", "wrong"));
        assertEquals("PERMISSION_DENIED", response.error()); assertEquals(0, provider.opens.get());
    }
    @Test public void cancelledPickerNeverOpensProvider() throws Exception {
        Response response = open(); runtime.onActivityResult(42, Activity.RESULT_CANCELED, null);
        assertEquals("CANCELLED", response.error()); assertEquals(0, provider.opens.get());
        assertNull(field("documentSelection").get(runtime));
    }
    @Test public void cancelledResultCarryingGrantStillRevokesIt() throws Exception {
        Response response = open(); runtime.onActivityResult(42, Activity.RESULT_CANCELED, result());
        assertEquals("CANCELLED", response.error()); assertEquals(0, provider.opens.get());
        assertTrue("Canceled returned grant must be revoked", activity.revokeEntered.await(5, TimeUnit.SECONDS));
        awaitCleanup(); assertFalse(activity.granted);
    }
    @Test public void staleResultAfterPageResetCannotGrantAuthority() throws Exception {
        open(); Intent old = result(); runtime.reset();
        runtime.onActivityResult(42, Activity.RESULT_OK, old);
        assertEquals(0, provider.opens.get()); assertNull(field("documentSelection").get(runtime));
        assertTrue(((Map<?, ?>) field("pending").get(runtime)).isEmpty());
    }
    @Test public void resultRequiresExactModeContentUriAndSingleMatchingClip() throws Exception {
        for (int variant = 0; variant < 10; variant++) {
            activity.granted = true; Response response = open(); Intent result = result();
            switch (variant) {
                case 0: result.setData(Uri.parse("file:///synthetic/fixture")); break;
                case 1: result.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION); break;
                case 2: result.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); break;
                case 3: result.setFlags(0); break;
                case 4: result.setClipData(ClipData.newRawUri("fixture", Uri.parse("content://factory.synthetic/other"))); break;
                case 5: ClipData clip = ClipData.newRawUri("fixture", URI); clip.addItem(new ClipData.Item(URI)); result.setClipData(clip); break;
                case 6: result.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); break;
                case 7: result.putExtra("uri", "content://unexpected"); break;
                case 8: result.setSelector(new Intent(Intent.ACTION_VIEW)); break;
                default: result.setData(Uri.parse("content://factory.synthetic/" + new String(new char[8193]).replace('\0', 'a')));
            }
            runtime.onActivityResult(42, Activity.RESULT_OK, result);
            assertEquals("variant " + variant, "PERMISSION_DENIED", response.error()); awaitCleanup();
        }
        assertEquals(0, provider.opens.get());
    }
    @Test public void deniedGrantNeverOpensProvider() throws Exception {
        Response response = open(); Intent result = result(); activity.granted = false;
        runtime.onActivityResult(42, Activity.RESULT_OK, result);
        assertEquals("PERMISSION_DENIED", response.error()); assertEquals(0, provider.opens.get());
    }
    @Test public void providerFailureReleasesReservationAndGrant() throws Exception {
        provider.fail = true; Response response = open(); runtime.onActivityResult(42, Activity.RESULT_OK, result()); idleIo();
        assertEquals("IO_ERROR", response.error()); assertEquals(1, provider.opens.get()); awaitCleanup();
        assertNull(field("documentSelection").get(runtime));
        for (int i = 0; i < DocumentHandles.MAX_HANDLES; i++) handles().reserveRead().cancel();
    }
    @Test public void createUsesOnlyWriteGrantAndWriteDescriptor() throws Exception {
        Response response = request("documents.create", new JSONObject().put("mimeType", "application/octet-stream").put("filename", "fixture.bin"));
        Intent result = result().setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        runtime.onActivityResult(42, Activity.RESULT_OK, result); idleIo();
        assertEquals("w", provider.mode); assertEquals(1, provider.opens.get());
        JSONObject value = response.value.getJSONObject("result"); assertEquals("write", value.getString("mode"));
        assertEquals(3, handles().write(value.getString("handle"), 0, "BAUG").bytesWritten);
        try { handles().read(value.getString("handle"), 3, 1); fail("Write authority permitted a read"); }
        catch (FactoryException expected) { assertEquals("WRONG_MODE", expected.code); }
    }
    @Test(timeout = 15000) public void pauseCancelsBlockedProviderWithoutWaitingAndReleasesAdmission() throws Exception {
        CountDownLatch release = new CountDownLatch(1); releases.add(release); provider.openRelease = release;
        Response response = open(); runtime.onActivityResult(42, Activity.RESULT_OK, result());
        assertTrue(provider.openEntered.await(5, TimeUnit.SECONDS));
        // Robolectric's ContentResolver transport can invoke the two-argument provider method,
        // dropping CancellationSignal. Assert the native signal, not real Binder delivery.
        Object selection = field("documentSelection").get(runtime);
        Field signalField = selection.getClass().getDeclaredField("signal"); signalField.setAccessible(true);
        CancellationSignal nativeSignal = (CancellationSignal) signalField.get(selection);
        assertFalse(nativeSignal.isCanceled());
        runtime.onPause(); assertEquals(1L, release.getCount());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!nativeSignal.isCanceled() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Native cancellation signal must be canceled", nativeSignal.isCanceled());
        release.countDown(); idleIo(); awaitCleanup();
        assertEquals("CANCELLED", response.error()); assertNull(field("documentSelection").get(runtime));
    }
    @Test public void pauseResetAndDestroyRevokeAlreadyIssuedHandles() throws Exception {
        for (int action = 0; action < 3; action++) {
            if (action > 0) create(false);
            DocumentHandles old = handles(); String handle = old.grantRead(new ByteArrayInputStream(new byte[] {1}));
            if (action == 0) runtime.onPause(); else if (action == 1) runtime.reset(); else runtime.close();
            try { old.read(handle, 0, 1); fail("Old handle survived lifecycle event " + action); }
            catch (FactoryException expected) { assertEquals("INVALID_HANDLE", expected.code); }
        }
    }
    @Test public void queuedOpenBehindBlockedIoDrainsCleanupAfterPause() throws Exception { queuedOpenCancellation(0); }
    @Test public void queuedOpenBehindBlockedIoDrainsCleanupAfterReset() throws Exception { queuedOpenCancellation(1); }
    @Test public void queuedOpenBehindBlockedIoDrainsCleanupAfterDestroy() throws Exception { queuedOpenCancellation(2); }
    private void queuedOpenCancellation(int action) throws Exception {
        CountDownLatch release = blockIo(); open(); runtime.onActivityResult(42, Activity.RESULT_OK, result());
        assertTrue(resources() > 0); assertEquals(0, provider.opens.get());
        if (action == 0) runtime.onPause(); else if (action == 1) runtime.reset(); else runtime.close();
        release.countDown();
        if (action == 2) assertTrue(executor().awaitTermination(5, TimeUnit.SECONDS)); else idleIo();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); awaitCleanup();
        assertEquals(0, provider.opens.get());
    }
    @Test public void saturatedOpenQueueReleasesReservationAndGrant() throws Exception {
        CountDownLatch release = blockIo();
        for (int i = 0; i < 16; i++) executor().execute(() -> { });
        Response response = open(); runtime.onActivityResult(42, Activity.RESULT_OK, result());
        assertEquals("BUSY", response.error()); assertEquals(0, provider.opens.get());
        awaitCleanup(); assertNull(field("documentSelection").get(runtime));
        for (int i = 0; i < DocumentHandles.MAX_HANDLES; i++) handles().reserveRead().cancel();
        release.countDown(); idleIo();
    }
    @Test public void delayedGrantCleanupBlocksSameUriSelectionAcrossRuntimeInstances() throws Exception {
        open(); Intent old = result();
        CountDownLatch release = new CountDownLatch(1); releases.add(release); activity.revokeRelease = release;
        TestActivity first = activity;
        runtime.onActivityResult(42, Activity.RESULT_OK, old.putExtra("nonce", "wrong"));
        assertTrue(first.revokeEntered.await(5, TimeUnit.SECONDS)); assertTrue(resources() > 0);
        create(false); Response response = open();
        assertEquals("BUSY", response.error()); assertNull(activity.launched); assertEquals(0, provider.opens.get());
        release.countDown(); awaitCleanup();
        Response next = open(); assertNull(next.value); assertNotNull(activity.launched);
        runtime.onActivityResult(42, Activity.RESULT_CANCELED, null);
    }
}
