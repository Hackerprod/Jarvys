package com.jarvys.agent;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowParcelFileDescriptor;
import org.robolectric.util.ReflectionHelpers;

/** Host-only kernel adapter used to preserve file identity checks in Robolectric tests. */
@Implements(Os.class)
public class ArtifactOsShadow {
    public static Runnable beforeOpen, afterOpen;
    public static Runnable afterClose;
    public static Path hookTargetPath;
    private static final Map<FileDescriptor,Path> directories = new IdentityHashMap<>();
    private static final Map<FileDescriptor,Closeable> owners = new IdentityHashMap<>();
    private static final Map<FileDescriptor,Path> files = new IdentityHashMap<>();
    private static final Map<FileDescriptor,StructStat> openedStats = new IdentityHashMap<>();
    private static final Map<FileDescriptor,Runnable> closeHooks = new IdentityHashMap<>();
    private static final Map<Integer,Path> duplicatedDescriptors = new HashMap<>();
    @Resetter public static void reset() { beforeOpen = null; afterOpen = null; afterClose = null; hookTargetPath = null; owners.clear(); directories.clear(); files.clear(); openedStats.clear(); closeHooks.clear(); duplicatedDescriptors.clear(); }
    @Implementation protected static StructStat lstat(String path) throws ErrnoException { return stat(Paths.get(path), LinkOption.NOFOLLOW_LINKS); }
    @Implementation protected static StructStat fstat(FileDescriptor fd) throws ErrnoException {
        StructStat opened = openedStats.get(fd);
        if (opened != null) return opened;
        Path path = directories.get(fd);
        if (path == null) path = files.get(fd);
        if (path == null) path = Paths.get("/proc/self/fd/" + descriptor(fd));
        return stat(path);
    }
    private static int descriptor(FileDescriptor fd) { return ReflectionHelpers.getField(fd, "fd"); }
    private static StructStat stat(Path path, LinkOption... options) throws ErrnoException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"))
            return portableStat(path, options);
        try {
            Map<String,Object> values = Files.readAttributes(path, "unix:dev,ino,mode,nlink,uid,gid,rdev,size,lastAccessTime,lastModifiedTime,ctime", options);
            return new StructStat(((Number)values.get("dev")).longValue(), ((Number)values.get("ino")).longValue(),
                    ((Number)values.get("mode")).intValue(), ((Number)values.get("nlink")).longValue(),
                    ((Number)values.get("uid")).intValue(), ((Number)values.get("gid")).intValue(),
                    ((Number)values.get("rdev")).longValue(), ((Number)values.get("size")).longValue(), 0,0,0,4096,0);
        } catch (UnsupportedOperationException unsupported) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, options);
                long creation = attributes.creationTime().to(TimeUnit.NANOSECONDS);
                long inode = (creation * 31L) ^ path.toAbsolutePath().normalize().toString().hashCode();
                int mode = attributes.isSymbolicLink() ? OsConstants.S_IFLNK
                        : attributes.isDirectory() ? OsConstants.S_IFDIR : OsConstants.S_IFREG;
                return new StructStat(path.getFileSystem().provider().getScheme().hashCode(), inode,
                        mode | 0600, 1, 0, 0, 0, attributes.size(), 0,0,0,4096,0);
            } catch (IOException failure) { throw new ErrnoException("stat", OsConstants.ENOENT); }
        } catch (IOException failure) { throw new ErrnoException("stat", OsConstants.ENOENT); }
    }
    private static StructStat portableStat(Path path, LinkOption... options) throws ErrnoException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, options);
            long inode = portableInode(path, attributes);
            int mode = attributes.isSymbolicLink() ? OsConstants.S_IFLNK
                    : attributes.isDirectory() ? OsConstants.S_IFDIR : OsConstants.S_IFREG;
            return new StructStat(path.getFileSystem().provider().getScheme().hashCode(), inode,
                    mode | 0600, 1, 0, 0, 0, attributes.size(), 0,0,0,4096,0);
        } catch (IOException failure) { throw new ErrnoException("stat", OsConstants.ENOENT); }
    }
    private static long portableInode(Path path, BasicFileAttributes attributes) {
        Object fileKey = attributes.fileKey();
        if (fileKey != null) {
            String value = fileKey.toString();
            long hash = 0xcbf29ce484222325L;
            for (int index = 0; index < value.length(); index++) {
                hash ^= value.charAt(index);
                hash *= 0x100000001b3L;
            }
            return hash;
        }
        long creation = attributes.creationTime().to(TimeUnit.NANOSECONDS);
        return (creation * 31L) ^ path.toAbsolutePath().normalize().toString().hashCode();
    }
    private static boolean matchesHookTarget(Path path) {
        if (hookTargetPath == null) return true;
        return path.toAbsolutePath().normalize().equals(hookTargetPath.toAbsolutePath().normalize());
    }
    private static StructStat openedStat(FileDescriptor fd, Path path) throws ErrnoException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            Path descriptorPath = Paths.get("/proc/self/fd/" + descriptor(fd));
            if (Files.exists(descriptorPath)) return stat(descriptorPath);
        }
        return stat(path);
    }
    @Implementation protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        Path openedPath = Paths.get(path).toAbsolutePath().normalize();
        boolean matchingHookTarget = matchesHookTarget(openedPath);
        Runnable hook = matchingHookTarget ? beforeOpen : null;
        if (hook != null) { beforeOpen = null; hook.run(); }
        try {
            if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(openedPath)) throw new IOException("symlink");
            if (Files.isDirectory(openedPath)) {
                FileDescriptor fd = new FileDescriptor();
                directories.put(fd, openedPath);
                openedStats.put(fd, stat(openedPath));
                if (matchingHookTarget && afterClose != null) { closeHooks.put(fd, afterClose); afterClose = null; }
                return fd;
            }
            FileDescriptor fd;
            Closeable owner;
            if ((flags & OsConstants.O_WRONLY) != 0 || (flags & OsConstants.O_RDWR) != 0) {
                // Deterministic host adapter for the production exclusive write-open contract.
                if ((flags & OsConstants.O_CREAT) != 0 && (flags & OsConstants.O_EXCL) != 0)
                    Files.createFile(openedPath);
                else if ((flags & OsConstants.O_CREAT) != 0 && !Files.exists(openedPath))
                    Files.createFile(openedPath);
                RandomAccessFile output = new RandomAccessFile(path, "rw");
                if ((flags & OsConstants.O_TRUNC) != 0) output.setLength(0);
                fd = output.getFD(); owner = output;
            } else {
                FileInputStream input = new FileInputStream(path);
                fd = input.getFD(); owner = input;
            }
            StructStat openedStat;
            try { openedStat = openedStat(fd, openedPath); }
            catch (ErrnoException invalid) { try { owner.close(); } catch (IOException ignored) { } throw invalid; }
            owners.put(fd, owner); files.put(fd, openedPath); openedStats.put(fd, openedStat);
            if (matchingHookTarget) {
                hook = afterOpen; afterOpen = null; if (hook != null) hook.run();
                if (afterClose != null) { closeHooks.put(fd, afterClose); afterClose = null; }
            }
            return fd;
        } catch (IOException failure) { throw new ErrnoException("open", OsConstants.EIO); }
    }
    @Implementation protected static void close(FileDescriptor fd) throws ErrnoException {
        boolean directory = directories.remove(fd) != null;
        files.remove(fd);
        openedStats.remove(fd);
        ErrnoException closeError = null;
        if (!directory) {
            try { Closeable owner = owners.remove(fd); if (owner != null) owner.close(); else new FileInputStream(fd).close(); }
            catch (IOException failure) { closeError = new ErrnoException("close", OsConstants.EIO); }
        }
        Runnable hook = closeHooks.remove(fd);
        if (hook != null) hook.run();
        if (closeError != null) throw closeError;
    }
    @Implementation protected static void fsync(FileDescriptor fd) { /* Host receipt-order test; not a hardware fsync claim. */ }
    @Implementation protected static String readlink(String path) throws ErrnoException {
        String descriptorPrefix = "/proc/self/fd/";
        if (path.startsWith(descriptorPrefix)) {
            try {
                Path opened = duplicatedDescriptors.get(Integer.parseInt(path.substring(descriptorPrefix.length())));
                if (opened != null) return opened.toAbsolutePath().toString();
            } catch (NumberFormatException ignored) { }
        }
        try { return Files.readSymbolicLink(Paths.get(path)).toString(); }
        catch (IOException failure) { throw new ErrnoException("readlink", Files.exists(Paths.get(path), LinkOption.NOFOLLOW_LINKS) ? OsConstants.EINVAL : OsConstants.ENOENT); }
    }
    @Implements(ParcelFileDescriptor.class)
    public static class Descriptor extends ShadowParcelFileDescriptor {
        @Implementation protected static ParcelFileDescriptor dup(FileDescriptor fd) throws IOException {
            Path path = files.get(fd);
            if (path == null) path = directories.get(fd);
            if (path == null) throw new FileNotFoundException("Descriptor has no host path");
            ParcelFileDescriptor duplicate = ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
            duplicatedDescriptors.put(duplicate.getFd(), path);
            return duplicate;
        }
    }
}
