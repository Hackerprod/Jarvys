package com.jarvys.factory.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/**
 * Test-only filesystem model. POSIX hosts use real Unix attributes; Windows uses modeled mode,
 * owner and inode values because NTFS exposes no Unix view. Actual Android open flags and FD grants
 * still need device QA.
 */
@Implements(Os.class)
public class FileShareSnapshotOsShadow {
    private static final Map<FileDescriptor, Handle> HANDLES = new IdentityHashMap<>();
    private static final Set<Path> PENDING_DELETE = new HashSet<>();
    private static final java.util.List<Integer> SHARE_OPEN_FLAGS = new java.util.ArrayList<>();
    private static final boolean UNIX = Paths.get(".").getFileSystem().supportedFileAttributeViews().contains("unix");
    private static final Map<Path, PortableStat> PORTABLE_STATS = new HashMap<>();
    private static final AtomicLong NEXT_INODE = new AtomicLong(1);
    private static final int PORTABLE_UID = 1000;
    private static final long PORTABLE_DEVICE = 1;
    private static final class PortableStat {
        final long inode = NEXT_INODE.getAndIncrement();
        int mode;
        long links = 1;
        PortableStat(int mode) { this.mode = mode; }
    }
    public static java.util.List<Integer> shareOpenFlags() { return new java.util.ArrayList<>(SHARE_OPEN_FLAGS); }
    private static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };
    private static final class Handle {
        final Path path;
        final RandomAccessFile file;
        Handle(Path path, RandomAccessFile file) { this.path = path; this.file = file; }
    }
    @Implementation protected static int getuid() {
        if (!UNIX) return PORTABLE_UID;
        try { return ((Number) Files.getAttribute(Paths.get("."), "unix:uid")).intValue(); }
        catch (IOException e) { throw new AssertionError(e); }
    }
    @Implementation protected static void mkdir(String name, int mode) throws ErrnoException {
        try {
            Path path = key(name); Files.createDirectory(path);
            if (UNIX) Files.setAttribute(path, "unix:mode", mode, NOFOLLOW);
            else PORTABLE_STATS.put(path, new PortableStat(0040000 | mode));
        }
        catch (IOException e) { throw errno("mkdir", e); }
    }
    @Implementation protected static StructStat lstat(String name) throws ErrnoException {
        try { return attributes(key(name)); }
        catch (IOException e) { throw errno("lstat", e); }
    }
    @Implementation protected static StructStat fstat(FileDescriptor descriptor) throws ErrnoException {
        try { return attributes(handle(descriptor).path); }
        catch (IOException e) { throw errno("fstat", e); }
    }
    private static StructStat attributes(Path path) throws IOException {
        if (!UNIX) return portableAttributes(path);
        Map<String, Object> a = Files.readAttributes(path, "unix:*", NOFOLLOW);
        return new StructStat(number(a,"dev"), number(a,"ino"), (int) number(a,"mode"),
            number(a,"nlink"), (int) number(a,"uid"), (int) number(a,"gid"), number(a,"rdev"),
            number(a,"size"), time(a,"lastAccessTime"), time(a,"lastModifiedTime"), time(a,"ctime"), 4096, 0);
    }
    private static StructStat portableAttributes(Path path) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        int type = basic.isDirectory() ? 0040000 : basic.isSymbolicLink() ? 0120000 : 0100000;
        PortableStat stat = PORTABLE_STATS.get(path);
        if (stat == null || (stat.mode & 0170000) != type) {
            stat = new PortableStat(type | (type == 0120000 ? 0777 : type == 0040000 ? 0700 : 0600));
            PORTABLE_STATS.put(path, stat);
        }
        return new StructStat(PORTABLE_DEVICE, stat.inode, stat.mode, stat.links, PORTABLE_UID, PORTABLE_UID, 0,
                basic.size(), 0, 0, 0, 4096, 0);
    }
    private static Path key(String name) { return Paths.get(name).toAbsolutePath().normalize(); }
    private static long number(Map<String, Object> a, String name) { return ((Number) a.get(name)).longValue(); }
    private static long time(Map<String, Object> a, String name) { return ((FileTime) a.get(name)).toMillis() / 1000; }
    @Implementation protected static FileDescriptor open(String name, int flags, int mode) throws ErrnoException {
        try {
            Path path = key(name);
            if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(path))
                throw new ErrnoException("open", OsConstants.ELOOP);
            if ((flags & OsConstants.O_CREAT) != 0) {
                if ((flags & OsConstants.O_EXCL) != 0 || !Files.exists(path, NOFOLLOW)) {
                    Files.createFile(path);
                    if (UNIX) Files.setAttribute(path, "unix:mode", mode, NOFOLLOW);
                    else PORTABLE_STATS.put(path, new PortableStat(0100000 | mode));
                }
            }
            boolean writable = (flags & (OsConstants.O_WRONLY | OsConstants.O_RDWR)) != 0;
            RandomAccessFile file = new RandomAccessFile(path.toFile(), writable ? "rw" : "r");
            FileDescriptor descriptor = file.getFD();
            HANDLES.put(descriptor, new Handle(path, file));
            if (path.getParent() != null && path.getParent().getFileName().toString().equals("factory-file-shares")) SHARE_OPEN_FLAGS.add(flags);
            return descriptor;
        } catch (IOException e) { throw errno("open", e); }
    }
    @Implementation protected static void fchmod(FileDescriptor descriptor, int mode) throws ErrnoException {
        try {
            Path path = handle(descriptor).path;
            if (UNIX) Files.setAttribute(path, "unix:mode", mode, NOFOLLOW);
            else { portableAttributes(path); PORTABLE_STATS.get(path).mode = (PORTABLE_STATS.get(path).mode & 0170000) | mode; }
        }
        catch (IOException e) { throw errno("fchmod", e); }
    }
    @Implementation protected static void chmod(String name, int mode) throws ErrnoException {
        try {
            Path path = key(name);
            if (UNIX) Files.setAttribute(path, "unix:mode", mode, NOFOLLOW);
            else { portableAttributes(path); PORTABLE_STATS.get(path).mode = (PORTABLE_STATS.get(path).mode & 0170000) | mode; }
        }
        catch (IOException e) { throw errno("chmod", e); }
    }
    @Implementation protected static void fsync(FileDescriptor descriptor) throws ErrnoException {
        try { descriptor.sync(); } catch (IOException e) { throw errno("fsync", e); }
    }
    @Implementation protected static void link(String oldName, String newName) throws ErrnoException {
        try {
            Path source = key(oldName), target = key(newName); Files.createLink(target, source);
            if (!UNIX) {
                portableAttributes(source);
                PortableStat stat = PORTABLE_STATS.get(source); stat.links++; PORTABLE_STATS.put(target, stat);
            }
        }
        catch (IOException e) { throw errno("link", e); }
    }
    @Implementation protected static void symlink(String oldName, String newName) throws ErrnoException {
        try {
            Path target = key(newName); Files.createSymbolicLink(target, Paths.get(oldName));
            if (!UNIX) PORTABLE_STATS.put(target, new PortableStat(0120000 | 0777));
        }
        catch (IOException e) { throw errno("symlink", e); }
    }
    @Implementation protected static void remove(String name) throws ErrnoException {
        try {
            Path path = key(name);
            java.util.Iterator<Map.Entry<FileDescriptor, Handle>> handles = HANDLES.entrySet().iterator();
            while (handles.hasNext()) {
                Handle open = handles.next().getValue();
                if (!open.file.getFD().valid()) { handles.remove(); continue; }
                if (path.equals(open.path)) { PENDING_DELETE.add(path); return; }
            }
            Files.delete(path); forget(path);
        } catch (IOException e) { throw errno("remove", e); }
    }
    @Implementation protected static void close(FileDescriptor descriptor) throws ErrnoException {
        Handle handle = HANDLES.remove(descriptor);
        if (handle == null) throw new ErrnoException("close", OsConstants.EBADF);
        try {
            handle.file.close();
            if (PENDING_DELETE.contains(handle.path)) {
                for (Handle open : HANDLES.values()) if (handle.path.equals(open.path)) return;
                Files.deleteIfExists(handle.path); PENDING_DELETE.remove(handle.path); forget(handle.path);
            }
        } catch (IOException e) { throw errno("close", e); }
    }
    private static void forget(Path path) {
        if (!UNIX) { PortableStat stat = PORTABLE_STATS.remove(path); if (stat != null && stat.links > 1) stat.links--; }
    }
    private static Handle handle(FileDescriptor descriptor) throws ErrnoException {
        Handle handle = HANDLES.get(descriptor);
        if (handle == null || !descriptor.valid()) throw new ErrnoException("descriptor", OsConstants.EBADF);
        return handle;
    }
    private static ErrnoException errno(String operation, IOException error) {
        int number = error instanceof NoSuchFileException ? OsConstants.ENOENT :
            error instanceof FileAlreadyExistsException ? OsConstants.EEXIST : OsConstants.EIO;
        return new ErrnoException(operation, number, error);
    }
    @Resetter public static void reset() {
        for (Handle handle : HANDLES.values()) try { handle.file.close(); } catch (IOException ignored) { }
        HANDLES.clear();
        PENDING_DELETE.clear();
        SHARE_OPEN_FLAGS.clear();
        PORTABLE_STATS.clear();
    }
}
