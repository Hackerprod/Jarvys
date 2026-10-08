package com.jarvys.agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.CRC32;

/** Private, per-bot thumbnails. Never resolves URLs, attachment references, or arbitrary paths. */
public final class BotIconStore {
    public static final int ICON_EDGE = 512;
    // Defensive encoded-input budget; thumbnails themselves decode to at most 512 x 512 pixels.
    static final int MAX_ENCODED_BYTES = 32 * 1024 * 1024;
    // Defensive decompression/decoder work budget, comfortably above supported provider sizes.
    static final int MAX_SOURCE_EDGE = 8192;
    static final long MAX_SOURCE_PIXELS = 32L * 1024 * 1024;
    private static final String ICON_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png";
    private final File root;

    public BotIconStore(Context context) { this(context.getApplicationContext().getFilesDir()); }

    BotIconStore(File filesDirectory) {
        try {
            // Android may give filesDir a platform-owned symlinked ancestor. Only our own subtree
            // must be symlink-free; canonicalize that trusted ancestor once.
            root = new File(filesDirectory.getCanonicalFile(), "bot_icons");
            verify(root);
        } catch (IOException error) { throw storageFailure(); }
    }

    public File resolve(String botId, String iconRef) {
        File target = target(botId, iconRef);
        if (!target.isFile()) throw new IllegalArgumentException("Bot icon is unavailable");
        return target;
    }

    String save(String botId, byte[] generatedPng, CancellationToken token) {
        token.throwIfCancelled();
        byte[] thumbnail = thumbnail(generatedPng, token);
        File directory = directory(botId);
        File temporary = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw storageFailure();
            verify(root);
            verify(directory);
            String iconRef = UUID.randomUUID().toString() + ".png";
            File target = target(botId, iconRef);
            temporary = File.createTempFile(".icon-", ".tmp", directory);
            verify(temporary);
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(thumbnail);
                output.flush();
                output.getFD().sync();
            }
            token.throwIfCancelled();
            verify(root);
            verify(directory);
            verify(target);
            if (target.exists() || !temporary.renameTo(target)) throw storageFailure();
            return iconRef;
        } catch (IOException error) { throw storageFailure(); }
        finally { if (temporary != null && temporary.exists()) temporary.delete(); }
    }

    boolean delete(String botId, String iconRef) {
        File target = target(botId, iconRef);
        return !target.exists() || target.delete();
    }

    private File target(String botId, String iconRef) {
        if (iconRef == null || !iconRef.matches(ICON_PATTERN)) {
            throw new IllegalArgumentException("Invalid private bot icon reference");
        }
        File target = new File(directory(botId), iconRef);
        try { verify(target); } catch (IOException error) { throw storageFailure(); }
        return target;
    }

    private File directory(String botId) {
        if (botId == null || !botId.matches("[a-z][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid bot identifier");
        }
        File directory = new File(root, botId);
        try { verify(root); verify(directory); }
        catch (IOException error) { throw storageFailure(); }
        return directory;
    }

    private static byte[] thumbnail(byte[] bytes, CancellationToken token) {
        if (!completePng(bytes)) throw new IllegalArgumentException("Generated bot icon is not a complete PNG");
        Bitmap decoded = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || !"image/png".equals(options.outMimeType)) {
                throw new IllegalArgumentException("Generated bot icon cannot be decoded");
            }
            options.inSampleSize = 1;
            while ((long) options.outWidth / options.inSampleSize > ICON_EDGE
                    || (long) options.outHeight / options.inSampleSize > ICON_EDGE) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            token.throwIfCancelled();
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) throw new IllegalArgumentException("Generated bot icon cannot be decoded");
            int longest = Math.max(decoded.getWidth(), decoded.getHeight());
            scaled = longest > ICON_EDGE ? Bitmap.createScaledBitmap(decoded,
                    Math.max(1, decoded.getWidth() * ICON_EDGE / longest),
                    Math.max(1, decoded.getHeight() * ICON_EDGE / longest), true) : decoded;
            token.throwIfCancelled();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            // Re-encoding removes metadata and makes all saved icons small static PNG bitmaps.
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) throw storageFailure();
            return output.toByteArray();
        } catch (OutOfMemoryError error) {
            throw new IllegalArgumentException("Not enough memory to prepare the bot icon");
        } finally {
            if (scaled != null && scaled != decoded) scaled.recycle();
            if (decoded != null) decoded.recycle();
        }
    }

    /** Validate framing and CRC before decoding, rejecting truncated data and trailing payloads. */
    private static boolean completePng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (bytes == null || bytes.length < 8 || bytes.length > MAX_ENCODED_BYTES) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        int offset = 8;
        boolean header = false;
        boolean pixels = false;
        while (offset <= bytes.length - 12) {
            long length = uint32(bytes, offset);
            if (length > bytes.length - offset - 12) return false;
            int count = (int) length;
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 4, count + 4);
            if (crc.getValue() != uint32(bytes, offset + 8 + count)) return false;
            if (!header) {
                if (!"IHDR".equals(type) || count != 13) return false;
                // Reject hostile/decompression-bomb dimensions before the platform decoder runs.
                long width = uint32(bytes, offset + 8), height = uint32(bytes, offset + 12);
                if (width < 1 || width > MAX_SOURCE_EDGE || height < 1 || height > MAX_SOURCE_EDGE
                        || width * height > MAX_SOURCE_PIXELS) return false;
                header = true;
            } else if ("IHDR".equals(type)) return false;
            if ("IDAT".equals(type)) pixels |= count > 0;
            offset += count + 12;
            if ("IEND".equals(type)) return count == 0 && pixels && offset == bytes.length;
        }
        return false;
    }

    private static long uint32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private static void verify(File file) throws IOException {
        // File.exists/isFile/canonical paths follow links and cannot reliably detect a dangling
        // link. Inspect the directory entry itself before any existence check, including deletion.
        boolean symbolicLink;
        if (Build.VERSION.SDK_INT >= 26) {
            symbolicLink = Api26.isSymbolicLink(file);
        } else {
            try { symbolicLink = OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode); }
            catch (ErrnoException error) {
                if (error.errno != OsConstants.ENOENT) throw new IOException(error);
                symbolicLink = false;
            }
        }
        if (symbolicLink || !file.getCanonicalFile().equals(file.getAbsoluteFile())) {
            throw new IllegalArgumentException("Bot icon symlinks are not allowed");
        }
    }

    @androidx.annotation.RequiresApi(26)
    private static final class Api26 {
        static boolean isSymbolicLink(File file) throws IOException {
            try {
                return java.nio.file.Files.readAttributes(file.toPath(),
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS).isSymbolicLink();
            } catch (java.nio.file.NoSuchFileException absent) { return false; }
        }
    }

    private static IllegalStateException storageFailure() {
        return new IllegalStateException("Could not save the private bot icon");
    }
}
