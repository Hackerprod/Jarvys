package com.jarvys.agent.coding;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

final class ProjectFileIO {
  private ProjectFileIO() {}

  static final class Attributes {
    final boolean directory;
    final Object key;
    final boolean regular;
    final long size;
    final boolean symlink;

    Attributes(Object key, boolean directory, boolean regular, boolean symlink, long size) {
      this.key = key;
      this.directory = directory;
      this.regular = regular;
      this.symlink = symlink;
      this.size = size;
    }

    Object fileKey() {
      return this.key;
    }

    boolean isDirectory() {
      return this.directory;
    }

    boolean isRegularFile() {
      return this.regular;
    }

    boolean isSymbolicLink() {
      return this.symlink;
    }

    long size() {
      return this.size;
    }
  }

  static Attributes attributes(File path) throws IOException {
    if (Build.VERSION.SDK_INT >= 26) {
      return Nio.attributes(path);
    }
    try {
      return fromStat(Os.lstat(path.getPath()));
    } catch (ErrnoException error) {
      throw new IOException(error.getMessage(), error);
    }
  }

  static boolean exists(File path) throws IOException {
    if (Build.VERSION.SDK_INT >= 26) {
      return Nio.exists(path);
    }
    try {
      Os.lstat(path.getPath());
      return true;
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT) {
        return false;
      }
      throw new IOException(error.getMessage(), error);
    }
  }

  static boolean isDirectory(File path) throws IOException {
    return exists(path) && attributes(path).isDirectory();
  }

  static void mkdir(File path) throws IOException {
    if (!path.mkdir()) {
      throw new IOException("Could not create project directory");
    }
  }

  static InputStream openNoFollow(File path) throws IOException {
    if (Build.VERSION.SDK_INT >= 26) return Nio.open(path);
    FileDescriptor descriptor = null;
    try {
      StructStat before = Os.lstat(path.getPath());
      if (!OsConstants.S_ISREG(before.st_mode)) {
        throw new IOException("Not an ordinary project file");
      }
      descriptor = Os.open(path.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
      StructStat opened = Os.fstat(descriptor);
      if (before.st_dev != opened.st_dev
          || before.st_ino != opened.st_ino
          || !OsConstants.S_ISREG(opened.st_mode)) {
        throw new IOException("Project file changed while opening");
      }
      FileInputStream stream = new FileInputStream(descriptor);
      descriptor = null; // Ownership passes to the stream only after all checks succeed.
      return stream;
    } catch (ErrnoException error) {
      throw new IOException(error.getMessage(), error);
    } finally {
      if (descriptor != null) {
        try {
          Os.close(descriptor);
        } catch (ErrnoException ignored) {
        }
      }
    }
  }

  static void preservePermissions(File source, File staged) throws IOException {
    if (Build.VERSION.SDK_INT >= 26) {
      Nio.preservePermissions(source, staged);
      return;
    }
    try {
      Os.chmod(staged.getPath(), Os.lstat(source.getPath()).st_mode & 511);
    } catch (ErrnoException error) {
      throw new IOException(error.getMessage(), error);
    }
  }

  static void atomicReplace(File source, File destination) throws IOException {
    if (Build.VERSION.SDK_INT >= 26) {
      Nio.atomicReplace(source, destination);
      return;
    }
    try {
      Os.rename(source.getPath(), destination.getPath());
    } catch (ErrnoException error) {
      throw new IOException(error.getMessage(), error);
    }
  }

  static void linkNoReplace(File source, File destination) throws IOException {
    if (Build.VERSION.SDK_INT >= 26) {
      Nio.link(source, destination);
      return;
    }
    try {
      Os.link(source.getPath(), destination.getPath());
    } catch (ErrnoException error) {
      throw new IOException(error.getMessage(), error);
    }
  }

  static void delete(File file) throws IOException {
    if (!file.delete()) {
      throw new IOException("Could not remove project file");
    }
  }

  static void deleteIfExists(File file) throws IOException {
    if (exists(file)) {
      delete(file);
    }
  }

  private static Attributes fromStat(StructStat stat) {
    return new Attributes(
        stat.st_dev + ":" + stat.st_ino,
        OsConstants.S_ISDIR(stat.st_mode),
        OsConstants.S_ISREG(stat.st_mode),
        OsConstants.S_ISLNK(stat.st_mode),
        stat.st_size);
  }

  private static final class Nio {
    private Nio() {}

    static Attributes attributes(File file) throws IOException {
      BasicFileAttributes a =
          Files.readAttributes(
              file.toPath(),
              (Class<BasicFileAttributes>) BasicFileAttributes.class,
              LinkOption.NOFOLLOW_LINKS);
      return new Attributes(
          a.fileKey(), a.isDirectory(), a.isRegularFile(), a.isSymbolicLink(), a.size());
    }

    static boolean exists(File file) {
      return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static InputStream open(File file) throws IOException {
      return Channels.newInputStream(
          Files.newByteChannel(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    static void preservePermissions(File source, File staged) throws IOException {
      Files.setPosixFilePermissions(
          staged.toPath(),
          Files.getPosixFilePermissions(source.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    static void atomicReplace(File source, File destination) throws IOException {
      Files.move(
          source.toPath(),
          destination.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    }

    static void link(File source, File destination) throws IOException {
      Files.createLink(destination.toPath(), source.toPath());
    }
  }
}
