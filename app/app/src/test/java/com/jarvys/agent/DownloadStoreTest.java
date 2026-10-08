package com.jarvys.agent;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowEnvironment;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DownloadStoreTest {
    private Context context;
    private FakeDownloadsProvider media;
    private static final byte[] BYTES = "An exported artifact\n".getBytes(StandardCharsets.UTF_8);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        media = FakeDownloadsProvider.install();
    }

    @Test public void savesViaPendingMediaStoreAndSharesOnlyAReadOnlyContentUri() throws Exception {
        DownloadStore.Result saved = export(source("report.txt", BYTES));
        assertEquals(DownloadStore.Status.SAVED, saved.status);
        assertEquals(1, media.inserts);
        assertEquals(1, media.publishes);
        assertEquals(1, media.outputOpens);
        ContentValues row = media.rows.values().iterator().next();
        assertEquals(0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING).intValue());
        assertEquals("Download/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals("text/plain", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertArrayEquals(BYTES, Files.readAllBytes(media.file(1).toPath()));
        assertEquals(context.getPackageName() + ".downloads", saved.uri.getAuthority());
        assertEquals(BYTES.length, saved.bytes);
        Intent open = DownloadIntents.open(saved);
        Intent share = DownloadIntents.share(saved);
        assertEquals(Intent.ACTION_VIEW, open.getAction());
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, open.getFlags());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.getFlags());
        assertEquals(saved.uri, share.getClipData().getItemAt(0).getUri());
        assertNull(org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        DownloadContentProvider provider = readProvider();
        try (ParcelFileDescriptor descriptor = provider.openFile(saved.uri, "r");
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] bytes = new byte[BYTES.length];
            assertEquals(bytes.length, input.read(bytes));
            assertArrayEquals(BYTES, bytes);
        }
        for (String mode : Arrays.asList("w", "rw", "rwt", "wa"))
            assertThrows(FileNotFoundException.class, () -> provider.openFile(saved.uri, mode));
        assertThrows(UnsupportedOperationException.class, () -> provider.delete(saved.uri, null, null));
        try (Cursor rowCursor = provider.query(saved.uri, new String[]{OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE, "_data"}, null, null, null)) {
            assertNotNull(rowCursor);
            assertTrue(rowCursor.moveToFirst());
            assertEquals(2, rowCursor.getColumnCount());
            assertEquals(saved.displayName, rowCursor.getString(0));
            assertEquals(BYTES.length, rowCursor.getLong(1));
        }
    }

    @Test public void recreatedStoreAndDoubleTapReturnTheSameDurableReceipt() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        DownloadStore.Source source = new DownloadStore.Source("conversation", "delivered:1", "report.txt", "text/plain",
                BYTES.length, () -> { opened.incrementAndGet(); return new ByteArrayInputStream(BYTES); });
        DownloadStore.Result first = export(source);
        DownloadStore.Result second = export(source);
        assertEquals(DownloadStore.Status.ALREADY_SAVED, second.status);
        assertEquals(first.uri, second.uri);
        assertEquals(first.displayName, second.displayName);
        assertEquals(1, opened.get());
        assertEquals(1, media.inserts);
    }

    @Test public void simultaneousTapsAcrossStoreInstancesCopyExactlyOnce() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        DownloadStore.Source source = new DownloadStore.Source("conversation", "attachment:1", "report.txt", "text/plain",
                BYTES.length, () -> {
                    opened.incrementAndGet();
                    started.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    return new ByteArrayInputStream(BYTES);
                });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<DownloadStore.Result> first = workers.submit(() -> new DownloadStore(context).export(source, null));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<DownloadStore.Result> second = workers.submit(() -> new DownloadStore(context).export(source, null));
            release.countDown();
            assertEquals(DownloadStore.Status.SAVED, first.get(10, TimeUnit.SECONDS).status);
            assertEquals(DownloadStore.Status.ALREADY_SAVED, second.get(10, TimeUnit.SECONDS).status);
            assertEquals(1, opened.get());
            assertEquals(1, media.inserts);
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void sameNamesAndDifferentScopesNeverOverwriteEachOther() throws Exception {
        media.collideNext = true;
        DownloadStore.Result first = export(source("../report\\unsafe\u202e.txt", BYTES));
        DownloadStore.Result second = export(new DownloadStore.Source("other-conversation", "delivered:1", "../report\\unsafe\u202e.txt",
                "text/plain", BYTES.length, () -> new ByteArrayInputStream(BYTES)));
        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertNotEquals(first.uri, second.uri);
        assertNotEquals(first.displayName, second.displayName);
        assertFalse(first.displayName.contains("/"));
        assertFalse(first.displayName.contains("\\"));
        assertFalse(first.displayName.contains("\u202e"));
        assertTrue(first.displayName.endsWith(".txt"));
        assertEquals(2, media.rows.size());
    }

    @Test public void longAsciiAndUnicodeNamesRemainReadableAfterRestart() throws Exception {
        for (String name : Arrays.asList(repeat("x", 120), repeat("\ud83d\udcc4", 120))) {
            DownloadStore.Source source = new DownloadStore.Source("conversation", "name:" + name, name, "text/plain", BYTES.length,
                    () -> new ByteArrayInputStream(BYTES));
            DownloadStore.Result first = export(source);
            assertEquals(DownloadStore.Status.SAVED, first.status);
            assertTrue(first.displayName.length() <= 120);
            assertTrue(first.displayName.getBytes(StandardCharsets.UTF_8).length <= 180);
            assertEquals(DownloadStore.Status.ALREADY_SAVED, export(source).status);
        }
    }

    @Test public void recoversCopyingRowWithoutLeakingAPartialOrDuplicatingSavedFile() throws Exception {
        DownloadStore.Source source = source("report.txt", BYTES);
        export(source);
        setReceiptStage(source, "copying", false);
        media.rows.values().iterator().next().put(MediaStore.MediaColumns.IS_PENDING, 1);
        DownloadStore.Result restarted = export(source);
        assertEquals(DownloadStore.Status.SAVED, restarted.status);
        assertEquals(2, media.inserts);
        assertEquals(1, media.deletes);
        assertEquals(1, media.rows.size());
        assertFalse(media.file(1).exists());
    }

    @Test public void recoversCrashBetweenMediaInsertAndRecordingItsUri() throws Exception {
        DownloadStore.Source source = source("report.txt", BYTES);
        export(source);
        setReceiptStage(source, "prepared", true);
        media.rows.values().iterator().next().put(MediaStore.MediaColumns.IS_PENDING, 1);
        assertEquals(DownloadStore.Status.SAVED, export(source).status);
        assertEquals(2, media.inserts);
        assertEquals(1, media.deletes);
        assertEquals(1, media.rows.size());
    }

    @Test public void recoversReadyAndAlreadyPublishedWindowsWithoutRecopying() throws Exception {
        DownloadStore.Source source = source("report.txt", BYTES);
        DownloadStore.Result saved = export(source);
        setReceiptStage(source, "ready", false);
        media.rows.values().iterator().next().put(MediaStore.MediaColumns.IS_PENDING, 1);
        DownloadStore.Result recovered = export(source);
        assertEquals(DownloadStore.Status.ALREADY_SAVED, recovered.status);
        assertEquals(saved.uri, recovered.uri);
        assertEquals(1, media.inserts);
        setReceiptStage(source, "ready", false);
        assertEquals(DownloadStore.Status.ALREADY_SAVED, export(source).status);
        assertEquals(1, media.outputOpens);
    }

    @Test public void cancellationBeforeStartAndDuringCopyRemovesPartialRows() throws Exception {
        CancellationToken before = CancellationToken.cancellable();
        before.cancel();
        assertEquals(DownloadStore.Status.CANCELLED, export(source("report.txt", BYTES), before).status);
        assertEquals(0, media.inserts);
        CancellationToken during = CancellationToken.cancellable();
        DownloadStore.Source source = new DownloadStore.Source("c", "generated:1", "picture.png", "image/png", BYTES.length,
                () -> new ByteArrayInputStream(BYTES) {
                    @Override public synchronized int read(byte[] b, int off, int len) {
                        int count = super.read(b, off, len);
                        during.cancel();
                        return count;
                    }
                });
        assertEquals(DownloadStore.Status.CANCELLED, export(source, during).status);
        assertEquals(0, media.rows.size());
        assertEquals(1, media.deletes);
    }

    @Test public void lengthMismatchReadErrorFullDiskAndPublishFailureCleanUp() throws Exception {
        for (int actual : new int[]{BYTES.length - 1, BYTES.length + 1}) {
            DownloadStore.Source bad = new DownloadStore.Source("c", "bad:" + actual, "a.txt", "text/plain", BYTES.length,
                    () -> new ByteArrayInputStream(new byte[actual]));
            assertEquals(DownloadStore.Status.ERROR, export(bad).status);
            assertTrue(media.rows.isEmpty());
        }
        DownloadStore.Source throwing = new DownloadStore.Source("c", "io-error", "a.txt", "text/plain", 1,
                () -> new InputStream() { @Override public int read() throws IOException { throw new IOException("source path secret"); } });
        DownloadStore.Result error = export(throwing);
        assertEquals(DownloadStore.Status.ERROR, error.status);
        assertFalse(error.error.contains("secret"));
        assertTrue(media.rows.isEmpty());
        media.failOpen = true;
        assertEquals(DownloadStore.Status.ERROR, export(source("full.txt", BYTES)).status);
        assertTrue(media.rows.isEmpty());
        media.failOpen = false;
        media.failPublish = true;
        assertEquals(DownloadStore.Status.ERROR, export(source("publish.txt", BYTES)).status);
        assertTrue(media.rows.isEmpty());
    }

    @Test public void failedCleanupKeepsReceiptUntilASafeRetry() throws Exception {
        media.failOpen = true;
        media.failDelete = true;
        DownloadStore.Source source = source("full.txt", BYTES);
        assertEquals("download_cleanup_pending", export(source).error);
        assertEquals(1, media.rows.size());
        assertEquals(DownloadStore.Status.ERROR, export(source).status);
        assertEquals(1, media.inserts);
        media.failOpen = false;
        media.failDelete = false;
        assertEquals(DownloadStore.Status.SAVED, export(source).status);
        assertEquals(2, media.inserts);
        assertEquals(1, media.rows.size());
    }

    @Test public void savedFileEditsAreNotDeletedAndUncertainProviderDoesNotDuplicate() throws Exception {
        DownloadStore.Source source = source("report.txt", BYTES);
        export(source);
        Files.write(media.file(1).toPath(), new byte[]{42});
        DownloadStore.Result result = export(source);
        assertEquals("saved_download_changed", result.error);
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(media.file(1).toPath()));
        assertEquals(0, media.deletes);
        media.failQuery = true;
        assertEquals(DownloadStore.Status.ERROR, export(source).status);
        assertEquals(1, media.inserts);
    }

    @Test public void readyReceiptForAnAlreadyPublishedEditedFileNeverDeletesIt() throws Exception {
        DownloadStore.Source source = source("report.txt", BYTES);
        export(source);
        setReceiptStage(source, "ready", false);
        Files.write(media.file(1).toPath(), new byte[]{17, 18});
        assertEquals(DownloadStore.Status.ERROR, export(source).status);
        assertArrayEquals(new byte[]{17, 18}, Files.readAllBytes(media.file(1).toPath()));
        assertEquals(0, media.deletes);
        assertEquals(1, media.inserts);
    }

    @Test public void deletedSavedDownloadCanBeExplicitlyExportedAgain() throws Exception {
        DownloadStore.Source source = source("report.txt", BYTES);
        DownloadStore.Result first = export(source);
        media.delete(media.firstUri(), null, null);
        DownloadStore.Result replacement = export(source);
        assertEquals(DownloadStore.Status.SAVED, replacement.status);
        assertNotEquals(first.displayName, replacement.displayName);
        assertEquals(2, media.inserts);
    }

    @Test public void mainThreadAndInvalidSourcesHaveNoSideEffects() {
        assertThrows(IllegalStateException.class, () -> new DownloadStore(context).export(source("report.txt", BYTES), null));
        assertThrows(IllegalArgumentException.class, () -> new DownloadStore.Source("c", "a", "a", "text/plain", -1, () -> null));
        assertThrows(IllegalArgumentException.class, () -> new DownloadStore.Source("c", "a", "a", "text/plain", DownloadStore.MAX_BYTES + 1, () -> null));
        assertEquals(0, media.inserts);
        assertFalse(DownloadStore.journalRoot(context).exists());
    }

    @Test public void streamsLargeSourcesInBoundedChunks() throws Exception {
        int size = 4 * 1024 * 1024;
        AtomicInteger maximumRequested = new AtomicInteger();
        DownloadStore.Source source = new DownloadStore.Source("c", "large", "large.zip", "application/zip", size,
                () -> new InputStream() {
                    int left = size;
                    @Override public int read() { if (left == 0) return -1; left--; return 1; }
                    @Override public int read(byte[] bytes, int offset, int length) {
                        maximumRequested.accumulateAndGet(length, Math::max);
                        if (left == 0) return -1;
                        int count = Math.min(left, length);
                        Arrays.fill(bytes, offset, offset + count, (byte) 1);
                        left -= count;
                        return count;
                    }
                });
        assertEquals(DownloadStore.Status.SAVED, export(source).status);
        assertTrue(maximumRequested.get() <= 32 * 1024);
        assertEquals(size, media.file(1).length());
    }

    @Test @Config(sdk = 28) public void legacyDeniedPermissionReportsNeedWithoutCreatingFilesOrAsking() throws Exception {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        AtomicInteger opened = new AtomicInteger();
        DownloadStore.Source source = new DownloadStore.Source("c", "a", "report.txt", "text/plain", BYTES.length,
                () -> { opened.incrementAndGet(); return new ByteArrayInputStream(BYTES); });
        assertEquals(DownloadStore.Status.PERMISSION_REQUIRED, export(source).status);
        assertEquals(0, opened.get());
        assertEquals(0, media.inserts);
        assertFalse(DownloadStore.journalRoot(context).exists());
    }

    @Test @Config(sdk = 28) public void legacyGrantedWritesPublicDownloadsAndUsesReadOnlyProvider() throws Exception {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        DownloadStore.Source source = source("report.txt", BYTES);
        DownloadStore.Result result = export(source);
        assertEquals(DownloadStore.Status.SAVED, result.status);
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), result.displayName);
        assertArrayEquals(BYTES, Files.readAllBytes(file.toPath()));
        assertEquals(0, media.inserts);
        assertEquals(DownloadStore.Status.ALREADY_SAVED, export(source).status);
        DownloadContentProvider provider = readProvider();
        try (ParcelFileDescriptor input = provider.openFile(result.uri, "r")) { assertNotNull(input); }
        assertThrows(FileNotFoundException.class, () -> provider.openFile(result.uri, "w"));
        Uri other = Uri.parse("content://" + context.getPackageName() + ".downloads/../../etc/passwd");
        assertThrows(FileNotFoundException.class, () -> provider.openFile(other, "r"));
    }

    @Test @Config(sdk = 28) public void legacyCancellationAndIoErrorLeaveNoPartialDownloads() throws Exception {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        CancellationToken token = CancellationToken.cancellable();
        DownloadStore.Source source = new DownloadStore.Source("c", "a", "report.txt", "text/plain", 3,
                () -> new InputStream() { @Override public int read() { token.cancel(); return 42; } });
        assertEquals(DownloadStore.Status.CANCELLED, export(source, token).status);
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        assertEquals(0, directory.list().length);
        DownloadStore.Source throwing = new DownloadStore.Source("c", "io", "report.txt", "text/plain", 3,
                () -> { throw new IOException("unavailable"); });
        assertEquals(DownloadStore.Status.ERROR, export(throwing).status);
        assertEquals(0, directory.list().length);
    }

    private DownloadContentProvider readProvider() {
        DownloadContentProvider provider = new DownloadContentProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = context.getPackageName() + ".downloads";
        info.exported = false;
        info.grantUriPermissions = true;
        provider.attachInfo(context, info);
        return provider;
    }
    private DownloadStore.Source source(String name, byte[] bytes) {
        return new DownloadStore.Source("conversation", "delivered:1", name, "text/plain", bytes.length,
                () -> new ByteArrayInputStream(bytes));
    }
    private DownloadStore.Result export(DownloadStore.Source source) throws Exception { return export(source, null); }
    private DownloadStore.Result export(DownloadStore.Source source, CancellationToken token) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { return worker.submit(() -> new DownloadStore(context).export(source, token)).get(15, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); }
    }
    private void setReceiptStage(DownloadStore.Source source, String stage, boolean eraseUri) throws Exception {
        File file = new File(DownloadStore.journalRoot(context), DownloadStore.key(source.scopeKey, source.artifactKey) + ".json");
        JSONObject receipt = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        receipt.put("stage", stage);
        if (eraseUri) receipt.put("mediaUri", "");
        Files.write(file.toPath(), receipt.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }
}
