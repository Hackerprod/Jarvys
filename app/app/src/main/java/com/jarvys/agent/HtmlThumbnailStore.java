package com.jarvys.agent;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.jarvys.agent.coding.ArtifactSnapshotIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Disposable PNG derivatives of transcript-owned, immutable HTML deliveries. This class never
 * executes HTML, reads a workspace, creates a conversation, or changes a delivered artifact.
 * All methods doing IO belong on a worker thread. Keep decoded bitmaps in the renderer's bounded
 * memory cache; it must revalidate ownership with isCurrent before serving a memory hit.
 */
public final class HtmlThumbnailStore {
    public static final int MAX_ENCODED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_DECODED_BYTES = 4 * 1024 * 1024;
    public static final int MAX_MEMORY_BYTES = 8 * 1024 * 1024;
    public static final long MAX_DISK_BYTES = 16L * 1024 * 1024;
    public static final int MAX_DISK_ENTRIES = 128;
    private static final String DIRECTORY = ".html-thumbnails";
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final LocalRunStore ledger;
    private final DeliveredArtifactStore delivered;
    private final long diskLimit;
    private final int entryLimit;

    /** Immutable capability containing no filesystem path; only prepare can create one. */
    public static final class Key {
        public final String session, token, artifactId, cacheId, fingerprint, policyVersion;
        public final int width, height;
        private Key(String session, HtmlPreviewDescriptor preview, int width, int height, String policy) {
            this.session = session; token = preview.token; artifactId = preview.artifactId;
            fingerprint = preview.contentFingerprint; this.width = width; this.height = height;
            policyVersion = policy;
            cacheId = digest("html-thumbnail-v1\0" + session + "\0" + token + "\0" + fingerprint
                    + "\0" + width + "x" + height + "\0" + policy);
        }
    }

    public HtmlThumbnailStore(Context context) { this(context.getApplicationContext().getFilesDir()); }
    HtmlThumbnailStore(File filesDirectory) {
        this(new LocalRunStore(filesDirectory), new DeliveredArtifactStore(new File(filesDirectory, "jarvys")),
                MAX_DISK_BYTES, MAX_DISK_ENTRIES);
    }
    HtmlThumbnailStore(LocalRunStore ledger, DeliveredArtifactStore delivered, long diskLimit, int entryLimit) {
        if (ledger == null || delivered == null || diskLimit < 1 || diskLimit > MAX_DISK_BYTES
                || entryLimit < 1 || entryLimit > MAX_DISK_ENTRIES) throw new IllegalArgumentException("Invalid thumbnail limits");
        this.ledger = ledger; this.delivered = delivered; this.diskLimit = diskLimit; this.entryLimit = entryLimit;
    }

    public Key prepare(String session, String token, int width, int height, String policyVersion) throws IOException {
        ConversationMetadataStore.validateSessionId(session);
        if (!HtmlPreviewDescriptor.isSnapshotToken(token) || width < 1 || height < 1
                || width > 2048 || height > 2048 || (long) width * height * 4 > MAX_DECODED_BYTES
                || policyVersion == null || policyVersion.length() < 1 || policyVersion.length() > 256
                || policyVersion.indexOf('\0') >= 0) throw new IOException("Invalid thumbnail request");
        ChatAttachment owner = owned(session, token.substring(HtmlPreviewDescriptor.TOKEN_PREFIX.length()));
        final HtmlPreviewDescriptor preview;
        try { preview = delivered.previewForAttachment(session, owner); }
        catch (IllegalArgumentException invalid) { throw new IOException("HTML preview is unavailable", invalid); }
        if (preview == null) throw new IOException("The delivered artifact has no HTML preview");
        Key key = new Key(session, preview, width, height, policyVersion);
        delivered.withThumbnailStorage(session, owner, key.fingerprint, storage -> null);
        owned(key); // A concurrent deletion cannot publish a usable newly prepared capability.
        return key;
    }

    /** Includes transcript, deletion-tombstone, manifest, and immutable asset integrity checks. */
    public boolean isCurrent(Key key) {
        try {
            ChatAttachment owner = owned(key);
            delivered.withThumbnailStorage(key.session, owner, key.fingerprint, storage -> null);
            owned(key);
            return true;
        } catch (IOException | RuntimeException unavailable) { return false; }
    }

    /** A corrupt, oversized, or absent derivative is a cache miss. Ownership failures are errors. */
    public byte[] read(Key key) throws IOException {
        ChatAttachment owner = owned(key);
        byte[] result = delivered.withThumbnailStorage(key.session, owner, key.fingerprint, storage -> {
            File directory = cacheDirectory(storage, false);
            if (!directory.exists()) return null;
            File target = new File(directory, key.cacheId + ".png");
            storage.verifyPath(target);
            if (!target.exists()) return null;
            byte[] bytes;
            try {
                bytes = readPng(storage, target);
                validatePng(bytes, key.width, key.height);
            } catch (IOException corrupt) {
                // Refuse symlinks; never unlink or follow a substituted unsafe location.
                storage.verifyPath(target);
                if (target.isFile()) target.delete();
                return null;
            }
            storage.requireLiveSession();
            target.setLastModified(System.currentTimeMillis());
            return bytes;
        });
        owned(key);
        return result;
    }

    public boolean write(Key key, byte[] png) throws IOException {
        return write(key, png, CancellationToken.uncancellable());
    }

    /**
     * Fully validate before creating any file, then fsync + atomically rename under the same lock
     * used by session deletion. Cancellation or deleted ownership never recreates a session tree.
     */
    public boolean write(Key key, byte[] png, CancellationToken token) throws IOException {
        if (token == null) throw new IllegalArgumentException("A cancellation token is required");
        token.throwIfCancelled();
        if (png == null || png.length > MAX_ENCODED_BYTES || png.length > diskLimit)
            throw new IOException("Thumbnail exceeds the encoded limit");
        byte[] immutable = png.clone();
        if (key == null) throw new IOException("Thumbnail identity is missing");
        validatePng(immutable, key.width, key.height);
        ChatAttachment owner = owned(key);
        return delivered.withThumbnailStorage(key.session, owner, key.fingerprint, storage -> {
            token.throwIfCancelled();
            storage.requireLiveSession();
            File directory = cacheDirectory(storage, true);
            File target = new File(directory, key.cacheId + ".png");
            storage.verifyPath(target);
            if (target.exists()) {
                try {
                    byte[] existing = readPng(storage, target);
                    validatePng(existing, key.width, key.height);
                    target.setLastModified(System.currentTimeMillis());
                    return true; // Rendering nondeterminism cannot replace an admitted derivative.
                } catch (IOException invalid) {
                    storage.verifyPath(target);
                    if (!target.isFile() || !target.delete()) throw new IOException("Could not remove invalid thumbnail");
                }
            }
            makeRoom(storage, immutable.length);
            token.throwIfCancelled();
            storage.requireLiveSession();
            File staged = new File(directory, ".thumbnail-" + java.util.UUID.randomUUID() + ".tmp");
            try {
                writeStaged(storage, staged, immutable, token);
                token.throwIfCancelled();
                storage.requireLiveSession(); storage.verifyPath(staged); storage.verifyPath(target);
                if (target.exists() || !staged.renameTo(target)) throw new IOException("Could not commit thumbnail");
                storage.verifyPath(target);
                ArtifactSnapshotIO.syncDirectory(directory);
                return true;
            } finally {
                storage.verifyPath(staged);
                if (staged.exists() && !staged.delete()) throw new IOException("Could not discard thumbnail staging");
            }
        });
    }

    private ChatAttachment owned(Key key) throws IOException {
        if (key == null) throw new IOException("Thumbnail identity is missing");
        return owned(key.session, key.artifactId);
    }
    private ChatAttachment owned(String session, String artifactId) throws IOException {
        try {
            ChatAttachment owner = ledger.findChatFile(session, "delivered", artifactId);
            if (owner == null) throw new IOException("The transcript does not own this artifact");
            return owner;
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            throw new IOException("Conversation ownership is unavailable", unavailable);
        }
    }

    private static File cacheDirectory(DeliveredArtifactStore.ThumbnailStorage storage, boolean create) throws IOException {
        storage.requireLiveSession();
        File directory = new File(storage.sessionDirectory, DIRECTORY);
        storage.verifyPath(directory);
        if (create && !directory.exists() && !directory.mkdir()) throw new IOException("Could not create thumbnail cache");
        storage.verifyPath(directory);
        if (directory.exists() && !directory.isDirectory()) throw new IOException("Invalid thumbnail cache directory");
        return directory;
    }

    /** Only derived PNGs are evicted; immutable files and unknown entries are never deleted. */
    private void makeRoom(DeliveredArtifactStore.ThumbnailStorage storage, long additional) throws IOException {
        List<File> thumbnails = new ArrayList<>();
        long total = 0;
        storage.verifyPath(storage.deliveredRoot);
        File[] sessions = storage.deliveredRoot.listFiles();
        if (sessions == null) throw new IOException("Delivered storage is unavailable");
        for (File session : sessions) {
            storage.verifyPath(session);
            if (!session.isDirectory()) continue;
            File directory = new File(session, DIRECTORY); storage.verifyPath(directory);
            if (!directory.exists()) continue;
            if (!directory.isDirectory()) throw new IOException("Invalid thumbnail cache directory");
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Thumbnail cache is unavailable");
            for (File file : files) {
                storage.verifyPath(file);
                String name = file.getName();
                if (name.matches("\\.thumbnail-[0-9a-f-]{36}\\.tmp")) {
                    if (!file.isFile() || !file.delete()) throw new IOException("Could not discard interrupted thumbnail");
                } else if (name.matches("[0-9a-f]{64}\\.png")) {
                    if (!file.isFile()) throw new IOException("Invalid thumbnail cache member");
                    thumbnails.add(file); total += file.length();
                    if (total < 0) throw new IOException("Thumbnail cache size overflow");
                } else throw new IOException("Unknown thumbnail cache member");
            }
        }
        thumbnails.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getPath));
        int count = thumbnails.size();
        for (File oldest : thumbnails) {
            if (total + additional <= diskLimit && count < entryLimit) break;
            storage.verifyPath(oldest);
            long size = oldest.length();
            if (!oldest.delete()) throw new IOException("Could not evict thumbnail");
            total -= size; count--;
        }
        if (total + additional > diskLimit || count >= entryLimit) throw new IOException("Thumbnail cache is full");
    }

    private static byte[] readPng(DeliveredArtifactStore.ThumbnailStorage storage, File file) throws IOException {
        storage.verifyPath(file);
        FileDescriptor descriptor = null;
        try {
            StructStat before = Os.lstat(file.getPath());
            if (!OsConstants.S_ISREG(before.st_mode) || before.st_size < 1 || before.st_size > MAX_ENCODED_BYTES)
                throw new IOException("Invalid thumbnail file size");
            descriptor = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            StructStat actual = Os.fstat(descriptor);
            if (before.st_dev != actual.st_dev || before.st_ino != actual.st_ino || before.st_size != actual.st_size)
                throw new IOException("Thumbnail changed while opening");
            verifyDescriptor(storage, file, descriptor);
            try (FileInputStream input = new FileInputStream(descriptor)) {
                descriptor = null;
                byte[] bytes = new byte[(int) before.st_size];
                int offset = 0, count;
                while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) != -1) offset += count;
                if (offset != bytes.length || input.read() != -1) throw new IOException("Thumbnail changed while reading");
                storage.verifyPath(file);
                return bytes;
            }
        } catch (ErrnoException invalid) { throw new IOException("Could not securely read thumbnail", invalid); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { } }
    }

    private static void writeStaged(DeliveredArtifactStore.ThumbnailStorage storage, File staged, byte[] png,
            CancellationToken token) throws IOException {
        storage.verifyPath(staged);
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(staged.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                    | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            verifyDescriptor(storage, staged, descriptor);
            try (FileOutputStream output = new FileOutputStream(descriptor)) {
                descriptor = null;
                for (int offset = 0; offset < png.length; offset += 8192) {
                    token.throwIfCancelled();
                    output.write(png, offset, Math.min(8192, png.length - offset));
                }
                output.getFD().sync();
                verifyDescriptor(storage, staged, output.getFD());
            }
        } catch (ErrnoException invalid) { throw new IOException("Could not securely write thumbnail", invalid); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { } }
    }

    private static void verifyDescriptor(DeliveredArtifactStore.ThumbnailStorage storage, File file,
            FileDescriptor descriptor) throws IOException, ErrnoException {
        storage.verifyPath(file);
        StructStat opened = Os.fstat(descriptor), named = Os.lstat(file.getPath());
        if (!OsConstants.S_ISREG(opened.st_mode) || !OsConstants.S_ISREG(named.st_mode)
                || opened.st_dev != named.st_dev || opened.st_ino != named.st_ino)
            throw new IOException("Thumbnail path changed while opening");
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(descriptor)) {
            if (!file.getCanonicalPath().equals(Os.readlink("/proc/self/fd/" + duplicate.getFd())))
                throw new IOException("Opened thumbnail is outside private storage");
        }
    }

    /** Strict bounded PNG container + decompression validation; never treats PNG headers as pixels. */
    static void validatePng(byte[] png, int expectedWidth, int expectedHeight) throws IOException {
        if (png == null || png.length < 57 || png.length > MAX_ENCODED_BYTES
                || !Arrays.equals(PNG, Arrays.copyOf(png, 8))) throw new IOException("Invalid PNG thumbnail");
        int offset = 8, channels = 0;
        boolean header = false, data = false, ended = false, dataEnded = false;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        while (offset <= png.length - 12) {
            long lengthLong = unsignedInt(png, offset);
            if (lengthLong > png.length - offset - 12) throw new IOException("PNG chunk exceeds thumbnail bounds");
            int length = (int) lengthLong;
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            if (!type.matches("[A-Za-z]{4}")) throw new IOException("Invalid PNG chunk type");
            CRC32 crc = new CRC32(); crc.update(png, offset + 4, length + 4);
            if (crc.getValue() != unsignedInt(png, offset + 8 + length)) throw new IOException("PNG checksum mismatch");
            int start = offset + 8;
            if (!header) {
                if (!type.equals("IHDR") || length != 13) throw new IOException("PNG header missing");
                long width = unsignedInt(png, start), height = unsignedInt(png, start + 4);
                if (width != expectedWidth || height != expectedHeight || width < 1 || height < 1
                        || width * height * 4 > MAX_DECODED_BYTES || width > 2048 || height > 2048
                        || png[start + 8] != 8 || (png[start + 9] != 6 && png[start + 9] != 2)
                        || png[start + 10] != 0 || png[start + 11] != 0 || png[start + 12] != 0)
                    throw new IOException("PNG dimensions or format exceed thumbnail policy");
                channels = png[start + 9] == 6 ? 4 : 3; header = true;
            } else if (type.equals("IDAT")) {
                if (dataEnded) throw new IOException("Noncontiguous PNG image data");
                compressed.write(png, start, length); data = true;
            } else if (type.equals("IEND")) {
                if (length != 0 || !data || offset + 12 != png.length) throw new IOException("Invalid PNG end");
                ended = true; break;
            } else {
                if (type.equals("IHDR") || Character.isUpperCase(type.charAt(0)))
                    throw new IOException("Unsupported PNG critical chunk");
                if (data) dataEnded = true;
            }
            offset += length + 12;
        }
        if (!ended) throw new IOException("Incomplete PNG thumbnail");
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed.toByteArray());
            long expected = ((long) expectedWidth * channels + 1) * expectedHeight;
            int stride = expectedWidth * channels + 1;
            byte[] buffer = new byte[8192]; long decoded = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) throw new IOException("Incomplete PNG pixel stream");
                if (decoded + count > expected) throw new IOException("PNG pixel stream exceeds decoded bound");
                for (int i = 0; i < count; i++) if ((decoded + i) % stride == 0 && (buffer[i] & 255) > 4)
                    throw new IOException("Invalid PNG row filter");
                decoded += count;
            }
            if (decoded != expected || inflater.getRemaining() != 0) throw new IOException("PNG pixel stream length differs");
        } catch (DataFormatException invalid) { throw new IOException("Invalid compressed PNG pixels", invalid); }
        finally { inflater.end(); }
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }
    private static String digest(String value) {
        try { return ArtifactSnapshotIO.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
