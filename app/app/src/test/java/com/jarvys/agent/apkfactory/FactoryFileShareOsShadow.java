package com.jarvys.agent.apkfactory;

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
import java.nio.file.attribute.FileTime;
import java.util.IdentityHashMap;
import java.util.Map;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/**
 * Test-only Unix-filesystem model. Default Robolectric lstat follows links and reports zero
 * inode/nlink metadata, so it cannot exercise fail-closed native-file identity checks.
 * This shadow uses real host Unix attributes; actual Android open flags/FD grants need device QA.
 */
@Implements(Os.class)
public class FactoryFileShareOsShadow {
    private static final Map<FileDescriptor, Handle> HANDLES = new IdentityHashMap<>();
    private static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };
    private static final class Handle {
        final Path path;
        final RandomAccessFile file;
        Handle(Path path, RandomAccessFile file) { this.path = path; this.file = file; }
    }
    @Implementation protected static int getuid() {
        try { return ((Number) Files.getAttribute(Paths.get("."), "unix:uid")).intValue(); }
        catch (IOException e) { throw new AssertionError(e); }
    }
    @Implementation protected static void mkdir(String name, int mode) throws ErrnoException {
        try { Path path = Paths.get(name); Files.createDirectory(path); Files.setAttribute(path, "unix:mode", mode, NOFOLLOW); }
        catch (IOException e) { throw errno("mkdir", e); }
    }
    @Implementation protected static StructStat lstat(String name) throws ErrnoException {
        try { return attributes(Paths.get(name)); }
        catch (IOException e) { throw errno("lstat", e); }
    }
    @Implementation protected static StructStat fstat(FileDescriptor descriptor) throws ErrnoException {
        try { return attributes(handle(descriptor).path); }
        catch (IOException e) { throw errno("fstat", e); }
    }
    private static StructStat attributes(Path path) throws IOException {
        Map<String, Object> a = Files.readAttributes(path, "unix:*", NOFOLLOW);
        return new StructStat(number(a,"dev"), number(a,"ino"), (int) number(a,"mode"),
            number(a,"nlink"), (int) number(a,"uid"), (int) number(a,"gid"), number(a,"rdev"),
            number(a,"size"), time(a,"lastAccessTime"), time(a,"lastModifiedTime"), time(a,"ctime"), 4096, 0);
    }
    private static long number(Map<String, Object> a, String name) { return ((Number) a.get(name)).longValue(); }
    private static long time(Map<String, Object> a, String name) { return ((FileTime) a.get(name)).toMillis() / 1000; }
    @Implementation protected static FileDescriptor open(String name, int flags, int mode) throws ErrnoException {
        try {
            Path path = Paths.get(name);
            if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(path))
                throw new ErrnoException("open", OsConstants.ELOOP);
            if ((flags & OsConstants.O_CREAT) != 0) {
                if ((flags & OsConstants.O_EXCL) != 0 || !Files.exists(path, NOFOLLOW)) {
                    Files.createFile(path);
                    Files.setAttribute(path, "unix:mode", mode, NOFOLLOW);
                }
            }
            boolean writable = (flags & (OsConstants.O_WRONLY | OsConstants.O_RDWR)) != 0;
            RandomAccessFile file = new RandomAccessFile(path.toFile(), writable ? "rw" : "r");
            FileDescriptor descriptor = file.getFD();
            HANDLES.put(descriptor, new Handle(path, file));
            return descriptor;
        } catch (IOException e) { throw errno("open", e); }
    }
    @Implementation protected static void fchmod(FileDescriptor descriptor, int mode) throws ErrnoException {
        try { Files.setAttribute(handle(descriptor).path, "unix:mode", mode, NOFOLLOW); }
        catch (IOException e) { throw errno("fchmod", e); }
    }
    @Implementation protected static void chmod(String name, int mode) throws ErrnoException {
        try { Files.setAttribute(Paths.get(name), "unix:mode", mode, NOFOLLOW); }
        catch (IOException e) { throw errno("chmod", e); }
    }
    @Implementation protected static void fsync(FileDescriptor descriptor) throws ErrnoException {
        try { descriptor.sync(); } catch (IOException e) { throw errno("fsync", e); }
    }
    @Implementation protected static void link(String oldName, String newName) throws ErrnoException {
        try { Files.createLink(Paths.get(newName), Paths.get(oldName)); }
        catch (IOException e) { throw errno("link", e); }
    }
    @Implementation protected static void symlink(String oldName, String newName) throws ErrnoException {
        try { Files.createSymbolicLink(Paths.get(newName), Paths.get(oldName)); }
        catch (IOException e) { throw errno("symlink", e); }
    }
    @Implementation protected static void remove(String name) throws ErrnoException {
        try { Files.delete(Paths.get(name)); } catch (IOException e) { throw errno("remove", e); }
    }
    @Implementation protected static void close(FileDescriptor descriptor) throws ErrnoException {
        Handle handle = HANDLES.remove(descriptor);
        if (handle == null) throw new ErrnoException("close", OsConstants.EBADF);
        try { handle.file.close(); } catch (IOException e) { throw errno("close", e); }
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
    }
}
