package com.jarvys.agent.coding;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Check the inode actually opened, not just the pathname before/after a potentially hostile rename. */
public final class VerifiedArtifactInput {
    private VerifiedArtifactInput() { }
    /** Used for an already scoped public download, whose path may be mutable outside the app. */
    public static ParcelFileDescriptor openReadOnly(File verifiedPath) throws IOException {
        File canonical = verifiedPath.getCanonicalFile();
        if (!verifiedPath.getAbsoluteFile().equals(canonical)) throw new IOException("Download path changed");
        try (InputStream input = open(canonical, identity(canonical))) {
            return ParcelFileDescriptor.dup(((FileInputStream) input).getFD());
        }
    }
    static StructStat identity(File source) throws IOException {
        try { return Os.lstat(source.getPath()); }
        catch (ErrnoException invalid) { throw new IOException("Could not identify artifact source", invalid); }
    }
    static InputStream open(File source, StructStat expected) throws IOException {
        FileDescriptor descriptor = null;
        try {
            if (!OsConstants.S_ISREG(expected.st_mode)) throw new IOException("Not an ordinary artifact file");
            descriptor = Os.open(source.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            StructStat actual = Os.fstat(descriptor);
            if (!OsConstants.S_ISREG(actual.st_mode) || expected.st_dev != actual.st_dev || expected.st_ino != actual.st_ino
                    || expected.st_size != actual.st_size) throw new IOException("Artifact source changed while opening");
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(descriptor)) {
                String openedPath = Os.readlink("/proc/self/fd/" + duplicate.getFd());
                if (!source.getAbsolutePath().equals(openedPath))
                    throw new IOException("Opened artifact is outside its expected location");
            }
            FileInputStream input = new FileInputStream(descriptor);
            descriptor = null;
            return input;
        } catch (ErrnoException invalid) { throw new IOException("Could not securely open artifact", invalid); }
        finally {
            if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { }
        }
    }
}
