package com.jarvys.agent;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Storage/security tests use actual PNG pixel streams; they do not claim WebView rendering. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class HtmlThumbnailStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File files;
    private LocalRunStore ledger;
    private DeliveredArtifactStore delivered;
    private HtmlThumbnailStore cache;
    private final String session = "thumbnail-owner";
    private ChatAttachment artifact;
    private HtmlThumbnailStore.Key key;

    @Before public void setUp() throws Exception {
        files = temporary.getRoot(); ledger = new LocalRunStore(files);
        delivered = new DeliveredArtifactStore(new File(files, "jarvys"));
        cache = new HtmlThumbnailStore(files);
        artifact = own(session, "page.html", "<html><body>Saved original</body></html>");
        key = cache.prepare(session, token(artifact), 32, 24, "js-off-v1:density160:light");
    }
    @After public void tearDown() { ArtifactOsShadow.reset(); AgentRunUiState.resetSession(session); }

    private ChatAttachment own(String chat, String name, String html) throws Exception {
        ledger.appendConversationMessage(chat, "user", "Preview my delivered HTML");
        ChatAttachment file = delivered.snapshotBytes(chat, html.getBytes(StandardCharsets.UTF_8), name,
                "text/html", CancellationToken.uncancellable());
        ledger.appendDeliveredFile(chat, file); return file;
    }
    private static String token(ChatAttachment attachment) { return HtmlPreviewDescriptor.TOKEN_PREFIX + attachment.id; }
    private File cacheDirectory() { return new File(files, "jarvys/delivered/" + session + "/.html-thumbnails"); }
    private File pngFile(HtmlThumbnailStore.Key target) {
        return new File(files, "jarvys/delivered/" + target.session + "/.html-thumbnails/" + target.cacheId + ".png");
    }
    private File manifest() { return new File(files, "jarvys/delivered/" + session + "/" + artifact.id + ".json"); }
    private static byte[] png(int width, int height, int seed) throws Exception {
        int stride = width * 4 + 1;
        byte[] pixels = new byte[stride * height];
        new Random(seed).nextBytes(pixels);
        for (int y = 0; y < height; y++) pixels[y * stride] = 0; // Real unfiltered RGBA scanlines.
        return rawPng(width, height, pixels);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void persistReopenReturnsRealPngWithoutChangingOriginalOrManifest() throws Exception {
        byte[] original = Files.readAllBytes(delivered.resolve(session, artifact).toPath());
        byte[] metadata = Files.readAllBytes(manifest().toPath());
        byte[] image = png(32, 24, 7);
        assertTrue(cache.write(key, image));
        HtmlThumbnailStore reopened = new HtmlThumbnailStore(files);
        HtmlThumbnailStore.Key restored = reopened.prepare(session, key.token, 32, 24, key.policyVersion);
        assertEquals(key.cacheId, restored.cacheId);
        byte[] read = reopened.read(restored);
        assertArrayEquals(image, read);
        Bitmap decoded = BitmapFactory.decodeByteArray(read, 0, read.length);
        assertNotNull(decoded);
        assertEquals(32, decoded.getWidth()); assertEquals(24, decoded.getHeight()); decoded.recycle();
        assertArrayEquals(original, Files.readAllBytes(delivered.resolve(session, artifact).toPath()));
        assertArrayEquals(metadata, Files.readAllBytes(manifest().toPath()));
        assertEquals(1, cacheDirectory().list().length);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeBitmapPngEncoderProducesAcceptedOpaqueAndAlphaPixelStreams() throws Exception {
        for (boolean alpha : new boolean[]{false, true}) {
            Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
            bitmap.setHasAlpha(alpha); bitmap.eraseColor(alpha ? 0x80553399 : 0xff553399);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)); bitmap.recycle();
            byte[] encoded = bytes.toByteArray();
            HtmlThumbnailStore.validatePng(encoded, 32, 24);
            HtmlThumbnailStore.Key encoderKey = cache.prepare(session, key.token, 32, 24, "native-alpha-" + alpha);
            assertTrue(cache.write(encoderKey, encoded));
            Bitmap decoded = BitmapFactory.decodeByteArray(cache.read(encoderKey), 0, encoded.length);
            assertNotNull(decoded); assertEquals(32, decoded.getWidth()); assertEquals(24, decoded.getHeight());
            assertEquals(alpha ? 128 : 255, android.graphics.Color.alpha(decoded.getPixel(0, 0)));
            decoded.recycle();
        }
    }

    @Test public void absentReadDoesNotCreateAnyCacheDirectory() throws Exception {
        assertNull(cache.read(key)); assertFalse(cacheDirectory().exists());
    }

    @Test public void keySeparatesContentSessionArtifactViewportAndRenderingPolicy() throws Exception {
        assertNotEquals(key.cacheId, cache.prepare(session, key.token, 33, 24, key.policyVersion).cacheId);
        assertNotEquals(key.cacheId, cache.prepare(session, key.token, 32, 25, key.policyVersion).cacheId);
        assertNotEquals(key.cacheId, cache.prepare(session, key.token, 32, 24, "js-off-v2:density320:dark").cacheId);
        ChatAttachment otherName = own(session, "other.html", "<html><body>Saved original</body></html>");
        assertNotEquals(key.cacheId, cache.prepare(session, token(otherName), 32, 24, key.policyVersion).cacheId);
        ChatAttachment changed = own(session, "page.html", "<html><body>New immutable content</body></html>");
        HtmlThumbnailStore.Key changedKey = cache.prepare(session, token(changed), 32, 24, key.policyVersion);
        assertNotEquals(key.fingerprint, changedKey.fingerprint); assertNotEquals(key.cacheId, changedKey.cacheId);
        ChatAttachment anotherChat = own("another-chat", "page.html", "<html><body>Saved original</body></html>");
        HtmlThumbnailStore.Key anotherKey = cache.prepare("another-chat", token(anotherChat), 32, 24, key.policyVersion);
        assertEquals(key.fingerprint, anotherKey.fingerprint); assertNotEquals(key.cacheId, anotherKey.cacheId);
    }

    @Test public void manifestWithoutTranscriptOwnershipCannotPrepareOrReadCopiedCache() throws Exception {
        ChatAttachment orphan = delivered.snapshotBytes(session, "<html>orphan</html>".getBytes(StandardCharsets.UTF_8),
                "orphan.html", "text/html", CancellationToken.uncancellable());
        assertThrows(IOException.class, () -> cache.prepare(session, token(orphan), 32, 24, "policy"));
        assertThrows(IOException.class, () -> cache.prepare("foreign-chat", key.token, 32, 24, "policy"));
        cache.write(key, png(32, 24, 1));
        File transcript = new File(files, "jarvys/conversations/" + session + ".jsonl");
        assertTrue("Fixture must remove the actual transcript", transcript.isFile());
        assertTrue(transcript.delete());
        assertThrows(IOException.class, () -> cache.read(key));
        assertThrows(IOException.class, () -> cache.write(key, png(32, 24, 2)));
        assertFalse(cache.isCurrent(key));
    }

    @Test public void changedImmutableBytesRejectEvenAnExistingPng() throws Exception {
        cache.write(key, png(32, 24, 1));
        File original = delivered.resolve(session, artifact);
        byte[] modified = Files.readAllBytes(original.toPath()); modified[12] ^= 1; Files.write(original.toPath(), modified);
        assertFalse(cache.isCurrent(key));
        assertThrows(IOException.class, () -> cache.read(key));
        assertThrows(IOException.class, () -> cache.write(key, png(32, 24, 2)));
    }

    @Test public void bundleFingerprintIncludesAssetsAndImmutableAssetCorruptionRejectsCache() throws Exception {
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), WorkspaceStore.projectIdForSession(session),
                null, null, null, session, false);
        workspace.write("bundle.html", "<html><link href='style.css' rel='stylesheet'><body>bundle</body></html>");
        workspace.write("style.css", "body {color: red}");
        ChatAttachment first = delivered.snapshot(session, workspace, "bundle.html", null, CancellationToken.uncancellable());
        ledger.appendDeliveredFile(session, first);
        HtmlThumbnailStore.Key firstKey = cache.prepare(session, token(first), 32, 24, "policy");
        cache.write(firstKey, png(32, 24, 1));
        workspace.write("style.css", "body {color: blue}");
        ChatAttachment second = delivered.snapshot(session, workspace, "bundle.html", null, CancellationToken.uncancellable());
        ledger.appendDeliveredFile(session, second);
        assertNotEquals(firstKey.fingerprint, cache.prepare(session, token(second), 32, 24, "policy").fingerprint);
        assertArrayEquals(png(32, 24, 1), cache.read(firstKey));
        File capturedCss = new File(files, "jarvys/delivered/" + session + "/" + first.id + ".preview/style.css");
        Files.write(capturedCss.toPath(), "body {color: bad}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> cache.read(firstKey));
    }

    @Test public void changedOwnershipMetadataFailsClosed() throws Exception {
        cache.write(key, png(32, 24, 1));
        JSONObject row = new JSONObject(new String(Files.readAllBytes(manifest().toPath()), StandardCharsets.UTF_8));
        row.put("conversation", "different-owner"); Files.write(manifest().toPath(), row.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> cache.read(key)); assertFalse(cache.isCurrent(key));
    }

    @Test public void malformedRequestsDoNotCreateDirectories() throws Exception {
        for (String bad : new String[]{"../bad", "..", "a/b", "", "."})
            assertThrows(IllegalArgumentException.class, () -> cache.prepare(bad, key.token, 32, 24, "v1"));
        assertThrows(IOException.class, () -> cache.prepare(session, "html-snapshot:../../escape", 32, 24, "v1"));
        assertThrows(IOException.class, () -> cache.prepare(session, key.token, 2048, 2048, "v1"));
        assertThrows(IOException.class, () -> cache.prepare(session, key.token, 0, 24, "v1"));
        assertThrows(IOException.class, () -> cache.prepare(session, key.token, 32, 24, "bad\0policy"));
        assertFalse(cacheDirectory().exists());
    }

    @Test public void oversizedEncodedAndDecodedImagesAreRejectedBeforeWriting() throws Exception {
        assertThrows(IOException.class, () -> cache.write(key, new byte[HtmlThumbnailStore.MAX_ENCODED_BYTES + 1]));
        assertThrows(IOException.class, () -> cache.write(key, png(33, 24, 1)));
        assertThrows(IOException.class, () -> cache.write(key, new byte[]{(byte)137, 80, 78, 71}));
        assertFalse(cacheDirectory().exists());
    }

    @Test public void pngValidationChecksCrcEndMarkerAndActualPixelStreamNotJustHeader() throws Exception {
        byte[] image = png(32, 24, 1);
        byte[] crc = image.clone(); crc[crc.length / 2] ^= 1;
        assertThrows(IOException.class, () -> cache.write(key, crc));
        assertThrows(IOException.class, () -> cache.write(key, Arrays.copyOf(image, image.length - 12)));
        byte[] trailing = Arrays.copyOf(image, image.length + 1);
        assertThrows(IOException.class, () -> cache.write(key, trailing));
        assertThrows(IOException.class, () -> cache.write(key, rawPng(32, 24, new byte[1])));
        assertThrows(IOException.class, () -> cache.write(key, rawPng(32, 24, new byte[(32 * 4 + 1) * 24 + 1])));
        byte[] invalidFilter = new byte[(32 * 4 + 1) * 24]; invalidFilter[0] = 5;
        assertThrows(IOException.class, () -> cache.write(key, rawPng(32, 24, invalidFilter)));
        assertFalse(cacheDirectory().exists());
    }

    @Test public void corruptAndOversizedCacheEntriesAreDiscardedAsMisses() throws Exception {
        cache.write(key, png(32, 24, 1)); Files.write(pngFile(key).toPath(), new byte[40]);
        assertNull(cache.read(key)); assertFalse(pngFile(key).exists());
        cache.write(key, png(32, 24, 1));
        try (RandomAccessFile giant = new RandomAccessFile(pngFile(key), "rw")) { giant.setLength(HtmlThumbnailStore.MAX_ENCODED_BYTES + 1L); }
        assertNull(cache.read(key)); assertFalse(pngFile(key).exists());
    }

    @Test public void sameKeyDoesNotOverwriteAdmittedDerivativeAndReturnedArraysAreIndependent() throws Exception {
        byte[] first = png(32, 24, 1); byte[] expected = first.clone();
        cache.write(key, first); first[0] = 0;
        cache.write(key, png(32, 24, 2));
        byte[] read = cache.read(key); assertArrayEquals(expected, read); read[0] = 0;
        assertArrayEquals(expected, cache.read(key));
    }

    @Test public void globalDiskBudgetEvictsOnlyDerivedPngsAcrossSessions() throws Exception {
        byte[] png = png(32, 24, 1);
        HtmlThumbnailStore bounded = new HtmlThumbnailStore(ledger, delivered, png.length * 2L - 1, 128);
        bounded.write(key, png);
        byte[] metadata = Files.readAllBytes(manifest().toPath());
        ChatAttachment second = own("second-owner", "second.html", "<html>second</html>");
        HtmlThumbnailStore.Key other = bounded.prepare("second-owner", token(second), 32, 24, "policy");
        bounded.write(other, png);
        assertNull(bounded.read(key)); assertArrayEquals(png, bounded.read(other));
        assertNotNull(delivered.resolve(session, artifact));
        assertArrayEquals(metadata, Files.readAllBytes(manifest().toPath()));
        assertTrue(pngFile(other).length() <= png.length * 2L - 1);
    }

    @Test public void entryBudgetAndInterruptedStagingAreBounded() throws Exception {
        HtmlThumbnailStore bounded = new HtmlThumbnailStore(ledger, delivered, HtmlThumbnailStore.MAX_DISK_BYTES, 1);
        byte[] png = png(32, 24, 1); bounded.write(key, png);
        File staging = new File(cacheDirectory(), ".thumbnail-01234567-0123-0123-0123-0123456789ab.tmp");
        Files.write(staging.toPath(), png);
        HtmlThumbnailStore.Key other = bounded.prepare(session, key.token, 32, 24, "different-policy");
        bounded.write(other, png);
        assertFalse(staging.exists()); assertNull(bounded.read(key)); assertArrayEquals(png, bounded.read(other));
        assertEquals(1, cacheDirectory().list().length);
    }

    @Test public void symlinkCacheDirectoryCannotWriteOutsidePrivateStorage() throws Exception {
        File outside = temporary.newFolder("outside-cache");
        Files.createSymbolicLink(cacheDirectory().toPath(), outside.toPath());
        assertThrows(IOException.class, () -> cache.write(key, png(32, 24, 1)));
        assertThrows(IOException.class, () -> cache.read(key)); assertEquals(0, outside.list().length);
    }

    @Test public void symlinkPngCannotReadModifyOrDeleteOutsideFile() throws Exception {
        assertTrue(cacheDirectory().mkdir()); File outside = new File(temporary.getRoot(), "outside.png");
        byte[] image = png(32, 24, 1); Files.write(outside.toPath(), image);
        Files.createSymbolicLink(pngFile(key).toPath(), outside.toPath());
        assertThrows(IOException.class, () -> cache.read(key));
        assertThrows(IOException.class, () -> cache.write(key, png(32, 24, 2)));
        assertArrayEquals(image, Files.readAllBytes(outside.toPath()));
    }

    @Test public void symlinkSwapAtReadOpenCannotReturnExternalPng() throws Exception {
        cache.write(key, png(32, 24, 1)); File outside = new File(temporary.getRoot(), "outside.png");
        byte[] external = png(32, 24, 2); Files.write(outside.toPath(), external);
        ArtifactOsShadow.beforeOpen = () -> {
            try { Files.delete(pngFile(key).toPath()); Files.createSymbolicLink(pngFile(key).toPath(), outside.toPath()); }
            catch (IOException failure) { throw new AssertionError(failure); }
        };
        assertThrows(IOException.class, () -> cache.read(key));
        assertArrayEquals(external, Files.readAllBytes(outside.toPath()));
    }

    @Test public void inodeReplacementAfterReadOpenIsRejectedBeforeReturningPixels() throws Exception {
        cache.write(key, png(32, 24, 1));
        File moved = new File(cacheDirectory(), "moved.png");
        byte[] replacement = png(32, 24, 2);
        ArtifactOsShadow.afterOpen = () -> {
            try { Files.move(pngFile(key).toPath(), moved.toPath()); Files.write(pngFile(key).toPath(), replacement); }
            catch (IOException failure) { throw new AssertionError(failure); }
        };
        assertNull(cache.read(key));
        assertTrue(moved.isFile());
    }

    @Test public void cancelledBeforeWriteLeavesNoCacheAndCancellationDuringOpenLeavesNoPartialPng() throws Exception {
        byte[] image = png(32, 24, 1);
        CancellationToken cancelled = CancellationToken.cancellable(); cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> cache.write(key, image, cancelled));
        assertFalse(cacheDirectory().exists());
        CancellationToken token = CancellationToken.cancellable();
        ArtifactOsShadow.afterOpen = token::cancel;
        assertThrows(java.util.concurrent.CancellationException.class, () -> cache.write(key, image, token));
        assertFalse(pngFile(key).exists());
        assertEquals(0, cacheDirectory().list().length);
        assertTrue(cache.isCurrent(key));
    }

    @Test public void deletionRemovesCacheAndRevokesHeldKeysWithoutResurrection() throws Exception {
        cache.write(key, png(32, 24, 1)); assertTrue(ledger.deleteConversation(session));
        assertFalse(cacheDirectory().exists()); assertFalse(cache.isCurrent(key));
        assertThrows(IOException.class, () -> cache.read(key));
        assertThrows(IOException.class, () -> cache.write(key, png(32, 24, 2)));
        assertFalse(new File(files, "jarvys/delivered/" + session).exists());
        assertFalse(new File(files, "jarvys/conversations/" + session + ".jsonl").exists());
    }

    @Test public void deliveredDeletionWithoutTombstoneStillCannotRecreateSession() throws Exception {
        assertTrue(delivered.deleteSession(session));
        assertThrows(IOException.class, () -> cache.write(key, png(32, 24, 1)));
        assertFalse(new File(files, "jarvys/delivered/" + session).exists());
    }

    @Test public void directDeletionRevokesGenerationEvenAfterIdenticalSessionArtifactIsRecreated() throws Exception {
        byte[] original = Files.readAllBytes(delivered.resolve(session, artifact).toPath());
        assertTrue(delivered.deleteSession(session));
        // Direct store use has no metadata tombstone. Recreating the same bytes still must not
        // make an old renderer callback eligible to commit into the new delivered tree.
        ChatAttachment restored = delivered.snapshotBytes(session, original, artifact.name, artifact.mimeType,
                CancellationToken.uncancellable());
        assertEquals(artifact.id, restored.id);
        assertThrows(IOException.class, () -> cache.write(key, png(32, 24, 1)));
        assertFalse(cache.isCurrent(key)); assertFalse(cacheDirectory().exists());
        HtmlThumbnailStore.Key fresh = new HtmlThumbnailStore(files).prepare(session, key.token, 32, 24, key.policyVersion);
        assertEquals(key.cacheId, fresh.cacheId);
        assertTrue(cache.write(fresh, png(32, 24, 2)));
    }

    @Test public void persistedDeletionTombstoneRejectsRestoredFilesAndOldCapabilities() throws Exception {
        File originalFile = delivered.resolve(session, artifact);
        byte[] source = Files.readAllBytes(originalFile.toPath()), metadata = Files.readAllBytes(manifest().toPath());
        File transcript = new File(files, "jarvys/conversations/" + session + ".jsonl");
        byte[] rows = Files.readAllBytes(transcript.toPath());
        assertTrue(ledger.deleteConversation(session));
        assertTrue(originalFile.getParentFile().mkdir());
        Files.write(originalFile.toPath(), source); Files.write(manifest().toPath(), metadata); Files.write(transcript.toPath(), rows);
        HtmlThumbnailStore reopened = new HtmlThumbnailStore(files);
        assertFalse(reopened.isCurrent(key));
        assertThrows(IOException.class, () -> reopened.write(key, png(32, 24, 1)));
        assertFalse(cacheDirectory().exists());
    }

    @Test(timeout = 10000) public void concurrentDeletionDuringAtomicWriteCannotDeadlockOrResurrectCache() throws Exception {
        byte[] image = png(32, 24, 1);
        CountDownLatch opened = new CountDownLatch(1), resume = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ArtifactOsShadow.beforeOpen = () -> {
            opened.countDown();
            try { if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer was not resumed"); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        };
        try {
            Future<Boolean> writer = executor.submit(() -> { try { return cache.write(key, image); } catch (IOException deleted) { return false; } });
            assertTrue(opened.await(3, TimeUnit.SECONDS));
            Future<Boolean> deletion = executor.submit(() -> new LocalRunStore(files).deleteConversation(session));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            ConversationMetadataStore metadata = new ConversationMetadataStore(files);
            while (!metadata.read(session).deleted && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(metadata.read(session).deleted);
            resume.countDown();
            assertFalse(writer.get(3, TimeUnit.SECONDS)); assertTrue(deletion.get(3, TimeUnit.SECONDS));
            assertFalse(cacheDirectory().exists()); assertFalse(cache.isCurrent(key));
        } finally { resume.countDown(); executor.shutdownNow(); }
    }

    @Test @Config(sdk = 24, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
    public void api24UsesNoFollowDescriptorsAndSurvivesReopen() throws Exception {
        byte[] image = png(32, 24, 1); cache.write(key, image);
        assertArrayEquals(image, new HtmlThumbnailStore(files).read(key));
    }

    private static byte[] rawPng(int width, int height, byte[] pixels) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10});
        ByteArrayOutputStream header = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(header);
        output.writeInt(width); output.writeInt(height); output.write(new byte[]{8, 6, 0, 0, 0});
        chunk(bytes, "IHDR", header.toByteArray());
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { deflater.write(pixels); }
        chunk(bytes, "IDAT", compressed.toByteArray()); chunk(bytes, "IEND", new byte[0]); return bytes.toByteArray();
    }
    private static void chunk(ByteArrayOutputStream bytes, String type, byte[] data) throws Exception {
        DataOutputStream output = new DataOutputStream(bytes); byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(data.length); output.write(name); output.write(data);
        CRC32 crc = new CRC32(); crc.update(name); crc.update(data); output.writeInt((int)crc.getValue());
    }
}
