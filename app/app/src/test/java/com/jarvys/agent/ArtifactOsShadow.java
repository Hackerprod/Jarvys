package com.jarvys.agent;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowParcelFileDescriptor;
import org.robolectric.util.ReflectionHelpers;

/** Host-only kernel adapter: real Unix inode/descriptor identities, not Android device acceptance. */
@Implements(Os.class)
public class ArtifactOsShadow {
    public static Runnable beforeOpen, afterOpen;
    private static final Map<FileDescriptor,FileInputStream> owners = new IdentityHashMap<>();
    @Resetter public static void reset() { beforeOpen = null; afterOpen = null; owners.clear(); }
    @Implementation protected static StructStat lstat(String path) throws ErrnoException { return stat(Paths.get(path), LinkOption.NOFOLLOW_LINKS); }
    @Implementation protected static StructStat fstat(FileDescriptor fd) throws ErrnoException { return stat(Paths.get("/proc/self/fd/" + descriptor(fd))); }
    private static int descriptor(FileDescriptor fd) { return ReflectionHelpers.getField(fd, "fd"); }
    private static StructStat stat(Path path, LinkOption... options) throws ErrnoException {
        try {
            Map<String,Object> values = Files.readAttributes(path, "unix:dev,ino,mode,nlink,uid,gid,rdev,size,lastAccessTime,lastModifiedTime,ctime", options);
            return new StructStat(((Number)values.get("dev")).longValue(), ((Number)values.get("ino")).longValue(),
                    ((Number)values.get("mode")).intValue(), ((Number)values.get("nlink")).longValue(),
                    ((Number)values.get("uid")).intValue(), ((Number)values.get("gid")).intValue(),
                    ((Number)values.get("rdev")).longValue(), ((Number)values.get("size")).longValue(), 0,0,0,4096,0);
        } catch (IOException failure) { throw new ErrnoException("stat", OsConstants.ENOENT); }
    }
    @Implementation protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        Runnable hook = beforeOpen; beforeOpen = null; if (hook != null) hook.run();
        try {
            if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(Paths.get(path))) throw new IOException("symlink");
            FileInputStream input = new FileInputStream(path); FileDescriptor fd = input.getFD(); owners.put(fd,input);
            hook = afterOpen; afterOpen = null; if (hook != null) hook.run();
            return fd;
        } catch (IOException failure) { throw new ErrnoException("open", OsConstants.EIO); }
    }
    @Implementation protected static void close(FileDescriptor fd) throws ErrnoException {
        try { FileInputStream input = owners.remove(fd); if (input != null) input.close(); else new FileInputStream(fd).close(); }
        catch (IOException failure) { throw new ErrnoException("close", OsConstants.EIO); }
    }
    @Implementation protected static String readlink(String path) throws ErrnoException {
        try { return Files.readSymbolicLink(Paths.get(path)).toString(); }
        catch (IOException failure) { throw new ErrnoException("readlink", OsConstants.EIO); }
    }
    @Implements(ParcelFileDescriptor.class)
    public static class Descriptor extends ShadowParcelFileDescriptor {
        @Implementation protected static ParcelFileDescriptor dup(FileDescriptor fd) throws IOException {
            // Reopen this inode through proc, preserving the kernel path/identity without copying bytes.
            return ParcelFileDescriptor.open(new File("/proc/self/fd/" + descriptor(fd)), ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }
}
