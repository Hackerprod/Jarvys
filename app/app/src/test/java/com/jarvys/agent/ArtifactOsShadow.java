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
    private static final Map<FileDescriptor,Path> directories = new IdentityHashMap<>();
    private static final Map<FileDescriptor,Closeable> owners = new IdentityHashMap<>();
    private static final Map<FileDescriptor,Path> files = new IdentityHashMap<>();
    private static final Map<Integer,Path> duplicatedDescriptors = new HashMap<>();
    @Resetter public static void reset() { beforeOpen = null; afterOpen = null; owners.clear(); directories.clear(); files.clear(); duplicatedDescriptors.clear(); }
    @Implementation protected static StructStat lstat(String path) throws ErrnoException { return stat(Paths.get(path), LinkOption.NOFOLLOW_LINKS); }
    @Implementation protected static StructStat fstat(FileDescriptor fd) throws ErrnoException {
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
            long creation = attributes.creationTime().to(TimeUnit.NANOSECONDS);
            long inode = (creation * 31L) ^ path.toAbsolutePath().normalize().toString().hashCode();
            int mode = attributes.isSymbolicLink() ? OsConstants.S_IFLNK
                    : attributes.isDirectory() ? OsConstants.S_IFDIR : OsConstants.S_IFREG;
            return new StructStat(path.getFileSystem().provider().getScheme().hashCode(), inode,
                    mode | 0600, 1, 0, 0, 0, attributes.size(), 0,0,0,4096,0);
        } catch (IOException failure) { throw new ErrnoException("stat", OsConstants.ENOENT); }
    }
    @Implementation protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        Runnable hook = beforeOpen; beforeOpen = null; if (hook != null) hook.run();
        try {
            if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(Paths.get(path))) throw new IOException("symlink");
            if (Files.isDirectory(Paths.get(path))) { FileDescriptor fd = new FileDescriptor(); directories.put(fd,Paths.get(path)); return fd; }
            FileDescriptor fd;
            if ((flags & OsConstants.O_WRONLY) != 0 || (flags & OsConstants.O_RDWR) != 0) {
                // Deterministic host adapter for the production exclusive write-open contract.
                if ((flags & OsConstants.O_CREAT) != 0 && (flags & OsConstants.O_EXCL) != 0)
                    Files.createFile(Paths.get(path));
                else if ((flags & OsConstants.O_CREAT) != 0 && !Files.exists(Paths.get(path)))
                    Files.createFile(Paths.get(path));
                RandomAccessFile output = new RandomAccessFile(path, "rw");
                if ((flags & OsConstants.O_TRUNC) != 0) output.setLength(0);
            fd = output.getFD(); owners.put(fd, output); files.put(fd, Paths.get(path));
            } else {
                FileInputStream input = new FileInputStream(path);
            fd = input.getFD(); owners.put(fd, input); files.put(fd, Paths.get(path));
            }
            hook = afterOpen; afterOpen = null; if (hook != null) hook.run();
            return fd;
        } catch (IOException failure) { throw new ErrnoException("open", OsConstants.EIO); }
    }
    @Implementation protected static void close(FileDescriptor fd) throws ErrnoException {
        if (directories.remove(fd) != null) return;
        files.remove(fd);
        try { Closeable owner = owners.remove(fd); if (owner != null) owner.close(); else new FileInputStream(fd).close(); }
        catch (IOException failure) { throw new ErrnoException("close", OsConstants.EIO); }
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
