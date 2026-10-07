package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AttachmentRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Context context() { return ApplicationProvider.getApplicationContext(); }

    @Test public void sanitizedMetadataRoundTripsWithoutBytesOrPaths() throws Exception {
        assertEquals(".._.._bad_name_", ChatAttachment.sanitizeName("../../bad\0name\u202e"));
        assertEquals("attachment", ChatAttachment.sanitizeName("   "));
        assertEquals("attachment", ChatAttachment.sanitizeName(".."));
        String longName = String.join("", Collections.nCopies(200, "😀"));
        String safe = ChatAttachment.sanitizeName(longName);
        assertTrue(safe.getBytes(StandardCharsets.UTF_8).length <= 218);
        assertFalse(Character.isHighSurrogate(safe.charAt(safe.length() - 1)));
        String id = UUID.randomUUID().toString();
        ChatAttachment item = new ChatAttachment(id, safe, "IMAGE/PNG", 42, ChatAttachment.Kind.IMAGE, id + "-" + safe);
        assertEquals(item, ChatAttachment.fromJson(item.toJson()));
        assertEquals("image/png", item.mimeType);
        assertFalse(item.toJson().has("base64"));
        assertNull(ChatAttachment.fromJson(new JSONObject().put("id", "../../evil")));
        assertTrue(ChatAttachment.fromJsonArray(null).isEmpty());
        assertTrue(ChatAttachment.fromJsonArray(new JSONArray().put(JSONObject.NULL)).isEmpty());
    }

    @Test public void contentProviderCopiesMissingOrHostileNamesAndDuplicateNames() throws Exception {
        File source = temp.newFile("source");
        Files.write(source.toPath(), new byte[]{1, 2, 3});
        FixtureProvider provider = new FixtureProvider(source);
        ProviderInfo info = new ProviderInfo();
        info.authority = "attachment.fixture";
        info.exported = false;
        provider.attachInfo(context(), info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        AttachmentStore store = new AttachmentStore(temp.newFolder("jarvys"), context().getContentResolver());
        Uri uri = Uri.parse("content://attachment.fixture/file");
        for (String name : new String[]{"../../x", "", "hello\0world", null, "repeat", "repeat"}) {
            provider.name = name;
            provider.omitColumn = name == null;
            ChatAttachment attachment = store.copyFromUri("chat", uri);
            assertEquals(ChatAttachment.sanitizeName(name), attachment.name);
            assertEquals(3, attachment.sizeBytes);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(store.resolve("chat", attachment).toPath()));
        }
        assertEquals(6, store.sessionDirectory("chat").listFiles().length);
        assertThrows(IllegalArgumentException.class, () -> store.copyFromUri("chat", Uri.fromFile(source)));
    }

    @Test public void failedReadOrCloseDoesNotPublishPartialFileAndAlwaysClosesInput() throws Exception {
        AttachmentStore store = new AttachmentStore(temp.newFolder("jarvys"));
        final boolean[] closed = {false};
        InputStream broken = new InputStream() {
            int read;
            @Override public int read() throws IOException { if (++read > 8) throw new IOException("injected"); return 1; }
            @Override public void close() { closed[0] = true; }
        };
        assertThrows(IOException.class, () -> store.copyFromStream("chat", "x", null, null, broken));
        assertTrue(closed[0]);
        assertEquals(0, store.sessionDirectory("chat").listFiles().length);
        InputStream closeFailure = new ByteArrayInputStream(new byte[]{1}) {
            @Override public void close() throws IOException { throw new IOException("close failed"); }
        };
        assertThrows(IOException.class, () -> store.copyFromStream("chat", "x", null, null, closeFailure));
        assertEquals(0, store.sessionDirectory("chat").listFiles().length);
    }

    @Test public void largeAndEmptyInputsUseBoundedStreaming() throws Exception {
        AttachmentStore store = new AttachmentStore(temp.newFolder("jarvys"));
        final long length = 2L * 1024 * 1024 + 3;
        InputStream source = new InputStream() {
            long remaining = length;
            @Override public int read() { if (remaining == 0) return -1; remaining--; return 42; }
            @Override public int read(byte[] data, int offset, int count) {
                assertTrue("Copy must use bounded chunks", count <= 8192);
                if (remaining == 0) return -1;
                int read = (int) Math.min(remaining, count);
                java.util.Arrays.fill(data, offset, offset + read, (byte) 42);
                remaining -= read;
                return read;
            }
        };
        ChatAttachment large = store.copyFromStream("chat", "large", null, null, source);
        assertEquals(length, large.sizeBytes);
        assertEquals(length, store.resolve("chat", large).length());
        ChatAttachment empty = store.copyFromStream("chat", "empty", null, null, new ByteArrayInputStream(new byte[0]));
        assertEquals(0, empty.sizeBytes);
        assertTrue(store.deleteAttachment("chat", empty));
        assertThrows(IllegalArgumentException.class, () -> store.resolve("chat", empty));
    }

    @Test public void rootSessionFileSymlinksAndTraversalAreRejectedWithoutFollowingThem() throws Exception {
        File base = temp.newFolder("base");
        File outside = temp.newFolder("outside");
        File sentinel = new File(outside, "sentinel");
        Files.write(sentinel.toPath(), new byte[]{9});
        AttachmentStore store = new AttachmentStore(base);
        ChatAttachment item = store.copyFromStream("chat", "x", "text/plain", null, new ByteArrayInputStream(new byte[]{1}));
        File itemFile = store.resolve("chat", item);
        assertTrue(itemFile.delete());
        Files.createSymbolicLink(itemFile.toPath(), sentinel.toPath());
        assertThrows(IllegalArgumentException.class, () -> store.resolve("chat", item));
        assertFalse(store.deleteSession("chat"));
        assertTrue(sentinel.isFile());
        assertThrows(IllegalArgumentException.class, () -> store.sessionDirectory("../outside"));
        assertThrows(IllegalArgumentException.class, () -> store.resolve("chat", "../sentinel"));
        File aliasBase = temp.newFolder("alias-base");
        Files.createSymbolicLink(new File(aliasBase, "attachments").toPath(), outside.toPath());
        assertThrows(IllegalArgumentException.class, () -> new AttachmentStore(aliasBase));
        store.cleanupOrphans(Collections.emptyMap());
        assertTrue(sentinel.isFile());
    }

    @Test public void orphanCleanupKeepsOnlyPersistedMetadataAndOtherSessionDeletionIsIsolated() throws Exception {
        AttachmentStore store = new AttachmentStore(temp.newFolder("jarvys"));
        ChatAttachment kept = store.copyFromStream("chat", "keep", null, null, new ByteArrayInputStream(new byte[]{1}));
        store.copyFromStream("chat", "orphan", null, null, new ByteArrayInputStream(new byte[]{2}));
        ChatAttachment other = store.copyFromStream("other", "keep", null, null, new ByteArrayInputStream(new byte[]{3}));
        java.util.Map<String, java.util.Set<String>> references = new java.util.HashMap<>();
        references.put("chat", Collections.singleton(kept.relativePath));
        references.put("other", Collections.singleton(other.relativePath));
        store.cleanupOrphans(references);
        assertEquals(1, store.sessionDirectory("chat").listFiles().length);
        assertTrue(store.deleteSession("chat"));
        assertTrue(store.resolve("other", other).isFile());
    }

    @Test public void heapSamplingUsesCeilDimensionsAndRejectsInvalidBounds() {
        assertEquals(1, AttachmentImagePreparer.sampleSize(100, 100, 480_000, 1));
        assertEquals(2, AttachmentImagePreparer.sampleSize(100, 100, 479_999, 1));
        assertTrue(AttachmentImagePreparer.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1) > 1);
        assertThrows(IllegalArgumentException.class, () -> AttachmentImagePreparer.sampleSize(0, 1, 10, 1));
    }

    @Test public void transparentPngUsesWhiteVisionBackgroundButEditPreservesAlpha() throws Exception {
        File png = temp.newFile("alpha.png");
        Bitmap original = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        original.eraseColor(Color.TRANSPARENT);
        try (FileOutputStream output = new FileOutputStream(png)) { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        original.recycle();
        ConversationTurn.Image image = AttachmentImagePreparer.prepare(context(), png, "alpha", CancellationToken.uncancellable());
        byte[] bytes = Base64.decode(image.jpegBase64, Base64.NO_WRAP);
        Bitmap opaque = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertEquals(8, image.width);
        assertTrue(Color.red(opaque.getPixel(4, 4)) > 245);
        opaque.recycle();
        ImageEditInput edit = AttachmentImagePreparer.prepareForEdit(context(), png, 480_000, CancellationToken.uncancellable());
        bytes = Base64.decode(edit.base64, Base64.NO_WRAP);
        Bitmap transparent = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertEquals(0, Color.alpha(transparent.getPixel(4, 4)));
        transparent.recycle();
    }

    @Test public void legacyExifRotationAndCorruptImagesAreHandled() throws Exception {
        for (int orientation : new int[]{ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270}) {
            File jpeg = temp.newFile("rotate" + orientation + ".jpg");
            Bitmap original = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
            original.eraseColor(Color.BLUE);
            try (FileOutputStream output = new FileOutputStream(jpeg)) { assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 100, output)); }
            original.recycle();
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
            exif.saveAttributes();
            Bitmap decoded = AttachmentImagePreparer.decodeLegacy(jpeg, 480_000, 1);
            assertEquals(10, decoded.getWidth());
            assertEquals(20, decoded.getHeight());
            decoded.recycle();
        }
        File corrupt = temp.newFile("corrupt");
        Files.write(corrupt.toPath(), new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class, () -> AttachmentImagePreparer.prepare(context(), corrupt, "corrupt", CancellationToken.uncancellable()));
        CancellationToken token = CancellationToken.cancellable();
        token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> AttachmentImagePreparer.prepare(context(), corrupt, "x", token));
    }

    private static final class FixtureProvider extends ContentProvider {
        private final File file;
        String name;
        boolean omitColumn;
        FixtureProvider(File file) { this.file = file; }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor result = new MatrixCursor(omitColumn ? new String[]{"other"} : new String[]{OpenableColumns.DISPLAY_NAME});
            result.addRow(new Object[]{name});
            return result;
        }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
