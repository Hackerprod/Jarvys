package com.jarvys.factory.runtime;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.UUID;

/** Private provisional disk ownership. No path or descriptor is delivered to the share receiver. */
public final class FileShareSnapshot implements StreamingFileShareTransfer.Snapshot {
    // O_CLOEXEC's stable Android/Linux ABI bit is usable on API 21 (public field needs API 27).
    private static final int CLOEXEC = 0x80000, OWNER_RW = 0600, OWNER_READ = 0400, OWNER_DIR = 0700;
    public static final String FILE_SHARE_NAMESPACE = "factory-share-snapshots";
    public static final String AUDIO_NAMESPACE = "factory-audio-snapshots";
    private static final java.util.Set<String> ACTIVE_ROOTS = new java.util.HashSet<>();
    private String ownedRoot;
    private final Context context;
    private final String namespace;
    private File pending;
    private FileOutputStream writer;
    private FileInputStream reader;
    long size;
    String sha256;

    public FileShareSnapshot(Context context) { this(context, FILE_SHARE_NAMESPACE); }
    public FileShareSnapshot(Context context, String namespace) {
        if (context == null || !(FILE_SHARE_NAMESPACE.equals(namespace) || AUDIO_NAMESPACE.equals(namespace))) throw new IllegalArgumentException();
        this.context = context.getApplicationContext(); this.namespace = namespace;
    }

    @Override public void materialize(long maximum, StreamingFileShareTransfer.SnapshotWriter source, Runnable active) throws Exception {
        if (maximum < 1 || maximum > StreamingFileShareTransfer.MAX_BYTES || source == null || active == null ||
                pending != null || writer != null || reader != null || sha256 != null) throw new IllegalArgumentException();
        active.run();
        File cache = context.getCacheDir().getCanonicalFile();
        File root = new File(cache, namespace);
        synchronized (ACTIVE_ROOTS) {
            if (!ACTIVE_ROOTS.add(root.getPath())) throw new IOException("Snapshot namespace already active");
            ownedRoot = root.getPath();
        }
        try { Os.mkdir(root.getPath(), OWNER_DIR); }
        catch (ErrnoException exists) { if (exists.errno != OsConstants.EEXIST) throw exists; }
        StructStat directory = Os.lstat(root.getPath());
        requireDirectory(root, directory);
        // A killed process may leave one provisional file, never read authority. No recursive delete.
        cleanProvisionalRoot(root);
        active.run();
        pending = new File(root, "snapshot-" + UUID.randomUUID() + ".tmp");
        FileDescriptor writable = Os.open(pending.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT |
                OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | CLOEXEC, OWNER_RW);
        writer = new FileOutputStream(writable);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final long[] written = {0};
        OutputStream checked = new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[] {(byte)value}, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                if (bytes == null || offset < 0 || count < 0 || offset > bytes.length - count)
                    throw new IndexOutOfBoundsException();
                active.run();
                if (count > StreamingFileShareTransfer.CHUNK_BYTES || count > maximum - written[0])
                    throw new IOException("File-share source exceeds declared budget");
                writer.write(bytes, offset, count);
                digest.update(bytes, offset, count);
                written[0] += count;
                active.run();
            }
            // The materializer retains exclusive ownership; a source cannot close the disk output.
            @Override public void close() throws IOException { throw new IOException("Snapshot output is privately owned"); }
        };
        source.write(checked, active);
        active.run();
        if (written[0] < 1) throw new IOException("Empty file-share source");
        writer.flush(); Os.fsync(writable); Os.fchmod(writable, OWNER_READ);
        StructStat complete = Os.fstat(writable);
        requireFile(complete, written[0]);
        StructStat currentRoot = Os.lstat(root.getPath());
        requireDirectory(root, currentRoot);
        if (currentRoot.st_dev != directory.st_dev || currentRoot.st_ino != directory.st_ino)
            throw new IOException("File-share cache replaced");
        FileDescriptor readable = Os.open(pending.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | CLOEXEC, 0);
        reader = new FileInputStream(readable);
        StructStat pinned = Os.fstat(readable);
        requireFile(pinned, written[0]);
        if (pinned.st_dev != complete.st_dev || pinned.st_ino != complete.st_ino)
            throw new IOException("File-share snapshot replaced");
        writer.close(); writer = null;
        // Immutable snapshot has only a read-only FD and no path. Source handle mutation is irrelevant.
        Os.remove(pending.getPath()); pending = null;
        active.run();
        size = written[0]; sha256 = StreamingFileShareTransfer.hex(digest.digest());
    }

    @Override public int read(byte[] bytes, int offset, int count) throws IOException {
        if (reader == null) throw new IOException("Snapshot closed");
        return reader.read(bytes, offset, count);
    }

    @Override public long size() { return size; }
    @Override public String sha256() { return sha256; }

    /** Retain every still-owned object on failure so admission cannot be released prematurely. */
    @Override public void close() throws Exception {
        Exception failure = null;
        if (writer != null) {
            try { writer.close(); writer = null; }
            catch (Exception e) { failure = e; }
        }
        if (reader != null) {
            try { reader.close(); reader = null; }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (pending != null) {
            try { unlink(pending); pending = null; }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        // Initial orphan cleanup may have failed before a new pending file was created. Those
        // bytes also retain namespace/transfer admission until their cleanup is confirmed.
        if (ownedRoot != null) {
            try { cleanProvisionalRoot(new File(ownedRoot)); }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
        synchronized (ACTIVE_ROOTS) {
            if (ownedRoot != null) { ACTIVE_ROOTS.remove(ownedRoot); ownedRoot = null; }
        }
    }

    private static void cleanProvisionalRoot(File root) throws Exception {
        StructStat stat;
        try { stat = Os.lstat(root.getPath()); }
        catch (ErrnoException missing) { if (missing.errno == OsConstants.ENOENT) return; throw missing; }
        requireDirectory(root, stat);
        File[] stale = root.listFiles();
        if (stale == null || stale.length > 2) throw new IOException("Uninspectable file-share cache");
        for (File file : stale) {
            if (!file.getName().matches("snapshot-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.tmp"))
                throw new IOException("Unexpected file-share cache entry");
            unlink(file);
        }
        if (root.list() == null || root.list().length != 0) throw new IOException("Snapshot cleanup incomplete");
    }
    private static void requireDirectory(File root, StructStat stat) throws IOException {
        if (!root.getCanonicalFile().equals(root) || !OsConstants.S_ISDIR(stat.st_mode) ||
                stat.st_uid != Os.getuid() || (stat.st_mode & 0777) != OWNER_DIR)
            throw new IOException("Unsafe file-share cache");
    }
    private static void requireFile(StructStat stat, long size) throws IOException {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Os.getuid() ||
                stat.st_nlink != 1 || (stat.st_mode & 0777) != OWNER_READ || stat.st_size != size)
            throw new IOException("Unsafe file-share snapshot");
    }
    private static void unlink(File file) throws Exception {
        requireDirectory(file.getParentFile(), Os.lstat(file.getParent()));
        StructStat stat;
        try { stat = Os.lstat(file.getPath()); }
        catch (ErrnoException missing) { if (missing.errno == OsConstants.ENOENT) return; throw missing; }
        if (!OsConstants.S_ISREG(stat.st_mode) && !OsConstants.S_ISLNK(stat.st_mode))
            throw new IOException("Unexpected snapshot file kind");
        Os.remove(file.getPath());
        try { Os.lstat(file.getPath()); throw new IOException("Snapshot cleanup incomplete"); }
        catch (ErrnoException missing) { if (missing.errno != OsConstants.ENOENT) throw missing; }
    }
}
