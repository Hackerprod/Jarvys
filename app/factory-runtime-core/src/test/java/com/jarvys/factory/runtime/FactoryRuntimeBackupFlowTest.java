package com.jarvys.factory.runtime;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.FrameLayout;
import androidx.webkit.JavaScriptReplyProxy;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

/**
 * Installed-path runtime integration with real SQLite and entirely synthetic data, identities,
 * grants and provider descriptors. Native approval is injected after inspecting the launch plan.
 * This does not exercise the host review UI, a real picker/Binder caller, or device durability.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 32}, manifest = Config.NONE, shadows = FactoryRuntimeDocumentsTest.Features.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class FactoryRuntimeBackupFlowTest {
    private static final Uri OUTPUT = Uri.parse("content://factory.backup.synthetic/document/output");
    private static final byte[] SOURCE_CERTIFICATE = {5, 8, 13, 21};
    private static final String SOURCE_DIGEST = DatabaseBackupFormat.sha256(SOURCE_CERTIFICATE);
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    public static class TestActivity extends Activity {
        Context storageOwner;
        volatile int grantedModes;
        final AtomicInteger grantChecks = new AtomicInteger();
        final AtomicInteger revokes = new AtomicInteger();
        final List<Intent> launches = new ArrayList<>();
        final List<Integer> requestCodes = new ArrayList<>();
        @Override public Context getApplicationContext() {
            return storageOwner == null ? super.getApplicationContext() : storageOwner;
        }
        @Override public boolean hasWindowFocus() { return true; }
        @Override public void startActivityForResult(Intent intent, int requestCode) {
            assertTrue("Backup uses its per-operation callback range", requestCode >= 0x1000 && requestCode <= 0x7fff);
            launches.add(intent); requestCodes.add(requestCode);
        }
        @Override public int checkUriPermission(Uri uri, int pid, int uid, int mode) {
            grantChecks.incrementAndGet();
            return OUTPUT.equals(uri) && mode != 0 && (grantedModes & mode) == mode
                    ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
        @Override public void revokeUriPermission(Uri uri, int mode) {
            assertEquals(OUTPUT, uri); revokes.incrementAndGet(); grantedModes &= ~mode;
        }
    }

    public static class Provider extends ContentProvider {
        File fixture;
        volatile java.io.FileDescriptor fileDescriptor;
        volatile String mode;
        volatile CountDownLatch openRelease, closeRelease;
        final CountDownLatch openEntered = new CountDownLatch(1), openReturned = new CountDownLatch(1), closeEntered = new CountDownLatch(1);
        final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
            assertEquals(OUTPUT, uri); this.mode = mode; opens.incrementAndGet(); openEntered.countDown();
            if (openRelease != null) awaitUninterruptibly(openRelease);
            if (signal != null) signal.throwIfCanceled();
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_WRITE_ONLY);
            fileDescriptor = descriptor.getFileDescriptor();
            ParcelFileDescriptor wrapped = new ParcelFileDescriptor(descriptor) {
                @Override public void close() throws IOException {
                    closeEntered.countDown();
                    if (closeRelease != null) awaitUninterruptibly(closeRelease);
                    try { super.close(); } finally { closes.incrementAndGet(); }
                }
            };
            openReturned.countDown();
            return wrapped;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return openFile(uri, mode, null);
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            throw new AssertionError("Backup must not query provider metadata");
        }
        @Override public String getType(Uri uri) { return "application/json"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new AssertionError(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new AssertionError(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new AssertionError(); }
    }

    private static class Host implements FactoryRuntime.Host {
        final MemoryBackend storage = new MemoryBackend();
        final String configuration;
        Host(String configuration) { this.configuration = configuration; }
        public InputStream open(String path) { return new ByteArrayInputStream(new byte[0]); }
        public String configuration() { return configuration; }
        public boolean isPreview() { return false; }
        public BoundedStore.Backend storage() { return storage; }
    }
    private static class Response extends JavaScriptReplyProxy {
        JSONObject value;
        int replies;
        public void postMessage(String text) {
            try { value = new JSONObject(text); replies++; } catch (Exception e) { throw new AssertionError(e); }
        }
        public void postMessage(byte[] bytes) { throw new AssertionError("Unexpected binary bridge response"); }
        JSONObject result() throws Exception {
            assertNotNull("Expected a native reply", value); assertTrue(value.toString(), value.getBoolean("ok"));
            return value.getJSONObject("result");
        }
        String error() throws Exception {
            assertNotNull("Expected a native failure", value); assertFalse(value.toString(), value.getBoolean("ok"));
            return value.getJSONObject("error").getString("code");
        }
    }
    private interface Condition { boolean satisfied() throws Exception; }
    private final List<CountDownLatch> releases = new ArrayList<>();
    private FactoryRuntime runtime;
    private TestActivity activity;
    private Provider provider;
    private Context owner;
    private int sequence;
    private final AtomicBoolean nativePolicyAllowed=new AtomicBoolean(true);
    private final AtomicInteger authorizationChecks=new AtomicInteger(), authorizationCloses=new AtomicInteger();

    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(TestActivity.class).setup().get();
        File directory = temporary.newFolder("private-database");
        owner = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getNoBackupFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
        activity.storageOwner = owner;
        DocumentBrokerIdentityTest.install(activity, activity.getPackageName(), new Signature(SOURCE_CERTIFICATE));
        DocumentBrokerIdentityTest.install(activity, DocumentBrokerIdentity.PRIMARY, new Signature(DocumentBrokerIdentityTest.CERTIFICATE));
        int hostFlags=PackageManager.GET_SIGNATURES;
        if(android.os.Build.VERSION.SDK_INT>=28) hostFlags|=PackageManager.GET_SIGNING_CERTIFICATES;
        android.content.pm.PackageInfo hostInfo=activity.getPackageManager().getPackageInfo(DocumentBrokerIdentity.PRIMARY,hostFlags);
        // PackageManager returns a copy. Reinstall the explicit synthetic UID so later
        // mutual Binder authentication reads the same identity, without bypassing it.
        hostInfo.applicationInfo.uid=android.os.Process.myUid();
        Shadows.shadowOf(activity.getPackageManager()).installPackage(hostInfo);
        Shadows.shadowOf(activity.getPackageManager()).setPackagesForUid(android.os.Process.myUid(),DocumentBrokerIdentity.PRIMARY);
        assertEquals(android.os.Process.myUid(),activity.getPackageManager().getApplicationInfo(DocumentBrokerIdentity.PRIMARY,0).uid);
        String configuration = new JSONObject(FactoryDispatcherTest.configuration("[\"database\",\"documents\"]"))
                .put("appId", activity.getPackageName())
                .put("dataBackup", new JSONObject().put("tables", new JSONArray().put("notes")))
                .put("documentBroker", new JSONObject().put("packageName", DocumentBrokerIdentity.PRIMARY)
                        .put("certificateSha256", DocumentBrokerIdentityTest.digest())).toString();
        Host host = new Host(configuration);
        runtime = new FactoryRuntime(activity, new FrameLayout(activity), host);
        field("config").set(runtime, FactoryConfig.parse(configuration, activity.getPackageName()));
        field("store").set(runtime, new BoundedStore(host.storage));
        provider = new Provider(); provider.fixture = temporary.newFile("synthetic-export.json");
        ProviderInfo info = new ProviderInfo(); info.authority = OUTPUT.getAuthority(); info.exported = true;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        runtime.onResume();
        JSONArray steps = new JSONArray();
        for (String table : Arrays.asList("notes", "other"))
            steps.put(new JSONObject().put("kind", "createTable").put("table", table).put("columns", columns()));
        assertTrue(call("database.migrate", new JSONObject().put("fromVersion", 0).put("toVersion", 1)
                .put("steps", steps)).getBoolean("committed"));
    }

    @After public void cleanup() throws Exception {
        for (CountDownLatch release : releases) release.countDown();
        if (runtime != null) {
            runtime.close();
            assertTrue("Runtime I/O must terminate", executor().awaitTermination(10, TimeUnit.SECONDS));
            await("All backup and provider cleanup must finish", () -> work() == null && resources() == 0);
            assertFalse(admitted());
        }
        if (activity != null) activity.finish();
    }
    private static Field field(String name) throws Exception {
        Field value = FactoryRuntime.class.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static Object member(Object object, String name) throws Exception {
        Field value = object.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(object);
    }
    private ThreadPoolExecutor executor() throws Exception { return (ThreadPoolExecutor) field("io").get(runtime); }
    private DocumentHandles handles() throws Exception { return (DocumentHandles) field("documents").get(runtime); }
    private Object work() throws Exception { return field("backup").get(runtime); }
    private static int resources() throws Exception { return ((AtomicInteger) field("documentResources").get(null)).get(); }
    private static boolean admitted() throws Exception { return ((AtomicBoolean) field("BACKUP_ADMISSION").get(null)).get(); }
    private void drain() throws Exception {
        executor().submit(() -> {}).get(10, TimeUnit.SECONDS); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void await(String message, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.satisfied() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); Thread.sleep(10);
        }
        assertTrue(message, condition.satisfied());
    }
    private void awaitFinished() throws Exception {
        await("Backup must release its admission after cleanup", () -> work() == null && resources() == 0 && !admitted());
    }
    private CountDownLatch releaseLatch() { CountDownLatch release = new CountDownLatch(1); releases.add(release); return release; }
    private CountDownLatch blockIo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = releaseLatch();
        executor().execute(() -> { entered.countDown(); awaitUninterruptibly(release); });
        assertTrue(entered.await(5, TimeUnit.SECONDS)); return release;
    }
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) { try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private Response request(String method, JSONObject args) throws Exception {
        Response response = new Response();
        Method receive = FactoryRuntime.class.getDeclaredMethod("receive", String.class, JavaScriptReplyProxy.class);
        receive.setAccessible(true);
        receive.invoke(runtime, new JSONObject().put("v", 1).put("id", "backupFlow" + ++sequence)
                .put("method", method).put("args", args).toString(), response);
        return response;
    }
    private JSONObject call(String method, JSONObject args) throws Exception {
        Response response = request(method, args); drain(); return response.result();
    }
    private static JSONObject columns() throws Exception {
        return new JSONObject().put("title", new JSONObject().put("type", "text").put("nullable", false));
    }
    private static JSONObject operation(String table, String kind, String id, String title) throws Exception {
        return new JSONObject().put("table", table).put("kind", kind).put("id", id)
                .put("values", new JSONObject().put("title", title));
    }
    private void change(String table, String kind, String id, String title) throws Exception {
        assertTrue(call("database.transact", new JSONObject().put("version", 1)
                .put("operations", new JSONArray().put(operation(table, kind, id, title)))).getBoolean("committed"));
    }
    private JSONArray rows(String table) throws Exception {
        return call("database.select", new JSONObject().put("version", 1).put("table", table).put("limit", 100)).getJSONArray("rows");
    }
    private DatabaseBackupFormat.Archive incoming(String... ids) throws Exception {
        JSONArray rows = new JSONArray();
        for (String id : ids) rows.put(new JSONObject().put("id", id).put("title", "synthetic imported " + id));
        return DatabaseBackupFormat.create(activity.getPackageName(), SOURCE_DIGEST, 1,
                new JSONObject().put("notes", columns()), new JSONObject().put("notes", rows));
    }
    private Response export() throws Exception {
        return request("database.backupExport", new JSONObject().put("version", 1)
                .put("tables", new JSONArray().put("notes")).put("filename", "synthetic-notes.json"));
    }
    private Response importArchive(DatabaseBackupFormat.Archive archive, String mode) throws Exception {
        String handle = handles().grantRead(new ByteArrayInputStream(archive.bytes()));
        Response response = request("database.backupImport", new JSONObject().put("version", 1)
                .put("tables", new JSONArray().put("notes")).put("handle", handle).put("mode", mode));
        drain();
        FactoryException consumed = assertThrows(FactoryException.class, () -> handles().read(handle, 0, 1));
        assertEquals("INVALID_HANDLE", consumed.code); return response;
    }
    private DatabaseBackupPlan review(Response response) throws Exception {
        drain();
        await("Review must launch or return a failure", () -> !activity.launches.isEmpty() || response.value != null);
        assertNull("Preparation failed: " + response.value, response.value);
        Intent launched = activity.launches.get(activity.launches.size() - 1);
        assertEquals(DocumentBrokerIdentity.PRIMARY, launched.getComponent().getPackageName());
        assertEquals("com.jarvys.agent.apkfactory.FactoryDataBackupActivity", launched.getComponent().getClassName());
        assertEquals(2, launched.getIntExtra("protocolVersion", 0));
        assertNotNull(launched.getExtras().getBinder("transfer")); assertNotNull(launched.getExtras().getBinder("control"));
        assertNull(launched.getData()); assertNull(launched.getClipData()); assertEquals(0, launched.getFlags());
        return DatabaseBackupPlan.parse(launched.getStringExtra("plan"));
    }
    private int requestCode() { return activity.requestCodes.get(activity.requestCodes.size() - 1); }
    private Intent approval(DatabaseBackupPlan plan) {
        BackupCommitAuthorization endpoint=new BackupCommitAuthorization(plan.nonce,plan.sha256(),plan.deadline,uid->true,
                ()->{authorizationChecks.incrementAndGet();if(!nativePolicyAllowed.get())throw new SecurityException("Synthetic policy revoked");},authorizationCloses::incrementAndGet);
        android.os.Bundle extras=new android.os.Bundle();extras.putBinder("authorization",endpoint);
        return new Intent().putExtra("nonce", plan.nonce).putExtra("planSha256", plan.sha256()).putExtra("approved", true).putExtra("protocolVersion",2).putExtras(extras);
    }
    private Intent exportApproval(DatabaseBackupPlan plan) {
        activity.grantedModes = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        Intent result = approval(plan).setData(OUTPUT).setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        result.setClipData(ClipData.newRawUri("synthetic output", OUTPUT));
        return result;
    }
    private void assertNoRetry(int launches, int opens, Response response) throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS); drain();
        assertEquals(launches, activity.launches.size()); assertEquals(opens, provider.opens.get());
        if (response.value != null) assertEquals(1, response.replies);
    }

    @Test public void revokedHostPolicyAfterUiApprovalPreventsProviderOpen() throws Exception {
        Response response=export(); DatabaseBackupPlan plan=review(response);
        Intent receipt=exportApproval(plan); nativePolicyAllowed.set(false);
        runtime.onActivityResult(requestCode(),Activity.RESULT_OK,receipt); drain(); awaitFinished();
        assertNotNull(response.error()); assertEquals(0,provider.opens.get()); assertTrue(authorizationChecks.get()>0);
    }

    @Test public void exportPreservesOnlyReviewReplyAcrossPauseAndWritesCanonicalSelectedArchive() throws Exception {
        change("notes", "insert", "z", "synthetic last"); change("notes", "insert", "A", "synthetic first");
        change("other", "insert", "private", "synthetic unselected");
        PrivateDatabase database = (PrivateDatabase) field("database").get(runtime);
        DatabaseBackupFormat.Archive expected = database.backupExport(activity.getPackageName(), SOURCE_DIGEST, 1,
                Collections.singletonList("notes"), database.ticket(), () -> true);
        Response response = export(); DatabaseBackupPlan plan = review(response);
        plan.validateArchive(expected);
        assertEquals("export", plan.operation); assertEquals("export", plan.mode);
        assertEquals("synthetic-notes.json", plan.filename); assertEquals(SOURCE_DIGEST, plan.certificateSha256);
        assertNotEquals(DocumentBrokerIdentityTest.digest(), plan.certificateSha256);
        assertEquals("", plan.preimageSha256); assertEquals(0, plan.targetRows().length());
        assertEquals(Collections.singletonList("notes"), plan.tables);
        CountDownLatch release = blockIo();
        String ordinaryHandle = handles().grantRead(new ByteArrayInputStream(new byte[] {7}));
        Response ordinaryDatabase = request("database.info", new JSONObject());
        Response ordinaryDocument = request("documents.read", new JSONObject().put("handle", ordinaryHandle).put("offset", 0).put("length", 1));
        runtime.onPause();
        assertEquals("CANCELLED", ordinaryDatabase.error()); assertEquals("CANCELLED", ordinaryDocument.error());
        assertNull(response.value); assertNull(field("database").get(runtime));
        assertEquals(1, ((Map<?, ?>) field("pending").get(runtime)).size());
        assertThrows(FactoryException.class, () -> handles().read(ordinaryHandle, 0, 1));
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, exportApproval(plan));
        assertEquals(0, provider.opens.get()); assertNull(response.value);
        release.countDown(); drain(); runtime.onResume(); drain(); awaitFinished();
        JSONObject result = response.result();
        assertEquals(expected.bytes().length, result.getInt("bytesWritten")); assertEquals(expected.sha256, result.getString("sha256"));
        assertFalse(result.getBoolean("providerCommitConfirmed")); assertEquals("close_requested", result.getString("closeStatus"));
        assertFalse(result.has("handle")); assertFalse(result.has("uri")); assertFalse(result.toString().contains("content://"));
        assertEquals(1, provider.opens.get()); assertEquals("w", provider.mode); assertTrue(activity.grantChecks.get() > 0);
        assertTrue(activity.revokes.get() > 0); assertEquals(0, activity.grantedModes);
        assertArrayEquals(expected.bytes(), Files.readAllBytes(provider.fixture.toPath()));
        DatabaseBackupFormat.Archive written = DatabaseBackupFormat.parse(Files.readAllBytes(provider.fixture.toPath()));
        assertEquals("A", written.data().getJSONArray("notes").getJSONObject(0).getString("id")); assertFalse(written.data().has("other"));
        assertEquals(1, rows("other").length()); assertNoRetry(1, 1, response);
    }

    @Test public void importReopensInstalledDatabaseAndRestoresOnlyReviewedTable() throws Exception {
        change("notes", "insert", "old", "synthetic original"); change("other", "insert", "keep", "synthetic unrelated");
        DatabaseBackupFormat.Archive archive = incoming("newA", "newB");
        PrivateDatabase old = (PrivateDatabase) field("database").get(runtime);
        PrivateDatabase.Baseline baseline = old.backupBaseline(archive, old.ticket(), () -> true);
        Response response = importArchive(archive, "replaceSelectedTables"); DatabaseBackupPlan plan = review(response);
        plan.validateArchive(archive); assertEquals("import", plan.operation); assertEquals("replaceSelectedTables", plan.mode);
        assertEquals(baseline.preimageSha256, plan.preimageSha256); assertEquals(baseline.schemaSha256, plan.schemaSha256);
        assertEquals(1, plan.targetRows().getInt("notes")); assertEquals("", plan.filename);
        runtime.onPause(); drain(); assertNull(field("database").get(runtime));
        // An independent installed connection changes an unselected table while review is open.
        try (PrivateDatabase independent = PrivateDatabase.installed(owner, owner.getPackageName())) {
            independent.execute("database.transact", new JSONObject().put("version", 1).put("operations", new JSONArray()
                    .put(operation("other", "update", "keep", "synthetic unrelated edited during review"))), independent.ticket(), () -> true);
        }
        Intent approved = approval(plan); assertNull(approved.getData()); assertNull(approved.getClipData()); assertEquals(0, approved.getFlags());
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, approved);
        assertNull(response.value); assertNull(field("database").get(runtime)); assertEquals(0, provider.opens.get());
        runtime.onResume(); drain(); awaitFinished();
        JSONObject result = response.result(); assertTrue(result.getBoolean("committed")); assertEquals(2, result.getJSONObject("counts").getInt("notes"));
        assertNotSame(old, field("database").get(runtime)); assertEquals(2, rows("notes").length());
        assertEquals("newA", rows("notes").getJSONObject(0).getString("id"));
        assertEquals("synthetic unrelated edited during review", rows("other").getJSONObject(0).getString("title"));
        runtime.onPause(); drain(); runtime.onResume(); assertEquals(2, rows("notes").length());
        assertNoRetry(1, 0, response);
    }

    @Test public void selectedEditAfterReviewRejectsReplacementWithoutRetry() throws Exception {
        change("notes", "insert", "old", "synthetic original"); change("other", "insert", "keep", "synthetic unrelated");
        Response response = importArchive(incoming("new"), "replaceSelectedTables"); DatabaseBackupPlan plan = review(response);
        runtime.onPause(); drain();
        try (PrivateDatabase independent = PrivateDatabase.installed(owner, owner.getPackageName())) {
            independent.execute("database.transact", new JSONObject().put("version", 1).put("operations", new JSONArray()
                    .put(operation("notes", "update", "old", "synthetic changed after review"))), independent.ticket(), () -> true);
        }
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, approval(plan)); runtime.onResume(); drain(); awaitFinished();
        assertEquals("BACKUP_CONFLICT", response.error());
        assertEquals(1, rows("notes").length()); assertEquals("old", rows("notes").getJSONObject(0).getString("id"));
        assertEquals("synthetic changed after review", rows("notes").getJSONObject(0).getString("title"));
        assertEquals("keep", rows("other").getJSONObject(0).getString("id")); assertNoRetry(1, 0, response);
    }

    @Test public void insertOnlyCollisionHasNoPartialInsertOrReplacement() throws Exception {
        change("notes", "insert", "z", "synthetic original");
        Response response = importArchive(incoming("a", "z"), "insertOnly"); DatabaseBackupPlan plan = review(response);
        runtime.onPause(); drain(); runtime.onActivityResult(requestCode(), Activity.RESULT_OK, approval(plan));
        runtime.onResume(); drain(); awaitFinished();
        assertEquals("BACKUP_CONFLICT", response.error()); assertEquals(1, rows("notes").length());
        assertEquals("z", rows("notes").getJSONObject(0).getString("id"));
        assertEquals("synthetic original", rows("notes").getJSONObject(0).getString("title")); assertNoRetry(1, 0, response);
    }

    @Test public void wrongPlanHashRejectsApprovalWithoutProviderMutationOrRetry() throws Exception {
        change("notes", "insert", "old", "synthetic original");
        byte[] sentinel = {3, 1, 4}; Files.write(provider.fixture.toPath(), sentinel);
        Response response = export(); DatabaseBackupPlan plan = review(response);
        runtime.onPause(); runtime.onActivityResult(requestCode(), Activity.RESULT_OK,
                exportApproval(plan).putExtra("planSha256", DatabaseBackupFormat.sha256(new byte[] {99})));
        assertNull(response.value); runtime.onResume(); drain(); awaitFinished();
        assertEquals("BACKUP_UNAVAILABLE", response.error()); assertEquals(0, provider.opens.get());
        assertArrayEquals(sentinel, Files.readAllBytes(provider.fixture.toPath()));
        assertEquals("synthetic original", rows("notes").getJSONObject(0).getString("title")); assertNoRetry(1, 0, response);
    }

    @Test public void wrongNonceAndWrongRequestCodeCannotConsumeReviewOrMutateDatabase() throws Exception {
        change("notes", "insert", "old", "synthetic original");
        Response response = importArchive(incoming("new"), "replaceSelectedTables"); DatabaseBackupPlan plan = review(response);
        Object pending = work(); int actual = requestCode(), wrong = actual == 0x7fff ? actual - 1 : actual + 1;
        runtime.onPause(); drain();
        runtime.onActivityResult(wrong, Activity.RESULT_OK, approval(plan));
        runtime.onActivityResult(actual, Activity.RESULT_OK, approval(plan).putExtra("nonce", DatabaseBackupFormat.sha256(new byte[] {42})));
        runtime.onResume(); drain();
        assertNull(response.value); assertSame(pending, work()); assertFalse((Boolean) member(pending, "returned"));
        assertEquals("old", rows("notes").getJSONObject(0).getString("id")); assertNoRetry(1, 0, response);
        assertTrue(request("database.cancel", new JSONObject()).result().getBoolean("cancelRequested"));
        assertEquals("CANCELLED", response.error());
        runtime.onActivityResult(actual, Activity.RESULT_CANCELED, null); awaitFinished();
        runtime.onActivityResult(actual, Activity.RESULT_OK, approval(plan)); drain();
        assertEquals("old", rows("notes").getJSONObject(0).getString("id")); assertEquals(1, response.replies);
    }

    @Test public void lateFirstOperationCallbackCannotAuthorizeSecondOperation() throws Exception {
        change("notes", "insert", "old", "synthetic original");
        Response first = export(); DatabaseBackupPlan firstPlan = review(first); int firstCode = requestCode();
        runtime.onActivityResult(firstCode, Activity.RESULT_CANCELED, null); drain(); awaitFinished();
        assertEquals("CANCELLED", first.error());
        Response second = importArchive(incoming("new"), "replaceSelectedTables");
        DatabaseBackupPlan secondPlan = review(second); int secondCode = requestCode();
        assertNotEquals(firstCode, secondCode); assertNotEquals(firstPlan.nonce, secondPlan.nonce);
        Object pending = work(); runtime.onPause(); drain();
        // An old export's real-looking write grant is revoked without applying either plan.
        runtime.onActivityResult(firstCode, Activity.RESULT_OK, exportApproval(firstPlan));
        runtime.onActivityResult(secondCode, Activity.RESULT_OK, approval(firstPlan));
        runtime.onResume(); drain();
        await("Stale output grant must be revoked", () -> resources() == 0 && activity.grantedModes == 0);
        assertNull(second.value); assertSame(pending, work()); assertFalse((Boolean) member(pending, "returned"));
        assertEquals("old", rows("notes").getJSONObject(0).getString("id")); assertNoRetry(2, 0, second);
        runtime.onActivityResult(secondCode, Activity.RESULT_OK, approval(secondPlan)); drain(); awaitFinished();
        assertTrue(second.result().getBoolean("committed")); assertEquals("new", rows("notes").getJSONObject(0).getString("id"));
        runtime.onActivityResult(firstCode, Activity.RESULT_OK, approval(firstPlan));
        runtime.onActivityResult(secondCode, Activity.RESULT_OK, approval(secondPlan)); drain();
        assertEquals("new", rows("notes").getJSONObject(0).getString("id"));
        assertEquals(1, first.replies); assertEquals(1, second.replies); assertNoRetry(2, 0, second);
    }

    @Test public void deniedNativeWriteGrantNeverOpensProvider() throws Exception {
        Response response = export(); DatabaseBackupPlan plan = review(response);
        Intent approved = exportApproval(plan); activity.grantedModes = 0;
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, approved); drain(); awaitFinished();
        assertEquals("PERMISSION_DENIED", response.error()); assertEquals(0, provider.fixture.length()); assertNoRetry(1, 0, response);
    }

    @Test public void importApprovalCarryingDocumentGrantCannotRestoreRows() throws Exception {
        change("notes", "insert", "old", "synthetic original");
        Response response = importArchive(incoming("new"), "replaceSelectedTables"); DatabaseBackupPlan plan = review(response);
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, exportApproval(plan)); drain(); awaitFinished();
        assertEquals("PERMISSION_DENIED", response.error()); assertEquals("old", rows("notes").getJSONObject(0).getString("id"));
        assertEquals(0, activity.grantedModes); assertNoRetry(1, 0, response);
    }

    @Test(timeout = 20000) public void cancelWhileProviderOpenIsBlockedRetainsAdmissionUntilReturn() throws Exception {
        CountDownLatch release = releaseLatch(); provider.openRelease = release;
        Response response = export(); DatabaseBackupPlan plan = review(response);
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, exportApproval(plan));
        assertTrue(provider.openEntered.await(5, TimeUnit.SECONDS)); Object pending = work();
        CancellationSignal signal = (CancellationSignal) member(pending, "signal");
        assertTrue(request("database.cancel", new JSONObject()).result().getBoolean("cancelRequested"));
        assertEquals("CANCELLED", response.error()); assertEquals(1L, release.getCount());
        assertSame(pending, work()); assertTrue(admitted());
        // Robolectric may drop CancellationSignal in the provider transport. This verifies the
        // runtime's native signal, not remote Binder cancellation delivery.
        await("Native provider cancellation must run", signal::isCanceled);
        assertEquals("BUSY", export().error()); assertEquals(1, provider.opens.get());
        release.countDown(); drain(); awaitFinished();
        assertEquals(0, provider.fixture.length()); assertNoRetry(1, 1, response);
    }

    @Test(timeout = 20000) public void blockedNativeCancellationCallbackCannotBlockMainThreadOrReleaseAdmission() throws Exception {
        CountDownLatch openRelease = releaseLatch(), cancelRelease = releaseLatch(), cancelEntered = new CountDownLatch(1);
        provider.openRelease = openRelease;
        Response response = export(); DatabaseBackupPlan plan = review(response);
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, exportApproval(plan));
        assertTrue(provider.openEntered.await(5, TimeUnit.SECONDS));
        CancellationSignal signal = (CancellationSignal) member(work(), "signal");
        // Deliberately block the local signal listener. This is a bounded callback fixture,
        // not a claim that the shadow resolver delivers a real provider cancellation Binder.
        signal.setOnCancelListener(() -> { cancelEntered.countDown(); awaitUninterruptibly(cancelRelease); });
        assertTrue(request("database.cancel", new JSONObject()).result().getBoolean("cancelRequested"));
        assertTrue(cancelEntered.await(5, TimeUnit.SECONDS)); assertEquals(1L, cancelRelease.getCount());
        openRelease.countDown(); assertTrue(provider.openReturned.await(5, TimeUnit.SECONDS));
        CountDownLatch ioDrained = new CountDownLatch(1); executor().execute(ioDrained::countDown);
        // ContentResolver detaches the remote signal on return. CancellationSignal.setRemote
        // waits for this intentionally blocked listener, so only the I/O worker may remain blocked.
        assertFalse("Resolver I/O must still await native cancellation", ioDrained.await(100, TimeUnit.MILLISECONDS));
        assertEquals("CANCELLED", response.error()); assertNotNull(work()); assertTrue(admitted()); assertTrue(resources() > 0);
        assertEquals("BUSY", export().error());
        cancelRelease.countDown(); assertTrue(ioDrained.await(5, TimeUnit.SECONDS)); drain();
        awaitFinished(); assertNoRetry(1, 1, response);
    }

    @Test(timeout = 20000) public void blockedDescriptorCloseKeepsAdmissionAndDoesNotClaimProviderCommit() throws Exception {
        CountDownLatch release = releaseLatch(); provider.closeRelease = release;
        change("notes", "insert", "one", "synthetic export");
        Response response = export(); DatabaseBackupPlan plan = review(response);
        runtime.onActivityResult(requestCode(), Activity.RESULT_OK, exportApproval(plan)); drain();
        assertTrue("The synthetic descriptor's close must really be entered", provider.closeEntered.await(5, TimeUnit.SECONDS));
        assertFalse(response.result().getBoolean("providerCommitConfirmed")); assertEquals("close_requested", response.result().getString("closeStatus"));
        assertEquals(1L, release.getCount()); assertEquals(0, provider.closes.get()); assertNotNull(work()); assertTrue(admitted());
        assertEquals("BUSY", export().error()); assertEquals(1, provider.opens.get());
        release.countDown(); awaitFinished(); assertTrue(provider.closes.get() > 0);
        // Older AutoCloseOutputStream implementations can re-enter idempotent PFD.close via
        // FileDescriptor.closeAll. Verify the actual resource, not the number of close calls.
        assertNotNull(provider.fileDescriptor); assertFalse("Provider descriptor must really close", provider.fileDescriptor.valid());
        assertNoRetry(1, 1, response);
    }
}
