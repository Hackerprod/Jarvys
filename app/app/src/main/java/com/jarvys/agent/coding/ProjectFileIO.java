package com.jarvys.agent.coding;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

  /** A failed exclusive creation may have left bytes; callers must retain its recovery evidence. */
  static final class IncompleteCreationException extends IOException {
    IncompleteCreationException(Throwable cause) {
      super("New project file creation was interrupted; inspect the destination and recovery record before retrying", cause);
    }
  }

  interface CopyObserver {
    void afterChunk(long copied) throws IOException;
  }

  /**
   * Android forbids hard links in untrusted apps. Create a new inode exclusively instead.
   * Existing paths (including dangling symlinks) are never opened or overwritten. Unlike an
   * atomic rename, readers may observe a partial new file; the mutation journal records this
   * intent before entry and recovery never replays it.
   */
  static void copyNew(File source, File destination) throws IOException {
    copyNew(source, destination, null);
  }

  static void copyNew(File source, File destination, CopyObserver observer) throws IOException {
    Attributes original = attributes(source);
    String expectedSha;
    try (InputStream input = openNoFollow(source)) { expectedSha = ProjectScope.sha256(input); }
    copyNew(source, destination, expectedSha, original.size(), observer);
  }

  static void copyNew(File source, File destination, String expectedSha, long expectedSize,
      CopyObserver observer) throws IOException {
    Attributes original = attributes(source);
    if (original.size() != expectedSize) throw new IOException("Creation source length changed");
    if (!original.isRegularFile() || original.isSymbolicLink()) {
      throw new IOException("Creation source is not an ordinary file");
    }
    ProjectScope.Anchor parent = new ProjectScope.Anchor(destination.getParentFile());
    boolean created = false;
    try (InputStream input = openNoFollow(source)) {
      parent.validate();
      if (Build.VERSION.SDK_INT >= 26) {
        Nio.copyNew(input, source, destination, original, parent, expectedSha, expectedSize, observer);
      } else {
        copyNewAndroid(input, source, destination, original, parent, expectedSha, expectedSize, observer);
      }
      created = true;
    } catch (IOException error) {
      if (created && !(error instanceof IncompleteCreationException)) {
        throw new IncompleteCreationException(error);
      }
      throw error;
    }
  }

  private static void copyNewAndroid(InputStream input, File source, File destination,
      Attributes original, ProjectScope.Anchor parent, String expectedSha, long expectedSize, CopyObserver observer) throws IOException {
    FileDescriptor descriptor;
    try {
      int mode = Os.lstat(source.getPath()).st_mode & 0777;
      descriptor = Os.open(destination.getPath(),
          OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, mode);
    } catch (ErrnoException error) {
      throw new IOException(error.getMessage(), error);
    }
    try (FileOutputStream output = new FileOutputStream(descriptor)) {
      Attributes created;
      try { created = fromStat(Os.fstat(descriptor)); }
      catch (ErrnoException error) { throw new IOException("Could not verify new project descriptor", error); }
      copy(input, output, expectedSha, expectedSize, observer);
      output.getFD().sync();
      validateCreated(source, destination, original, created, parent, expectedSha);
    } catch (IOException | RuntimeException error) {
      throw new IncompleteCreationException(error);
    }
  }

  private static void copy(InputStream input, java.io.OutputStream output, String expectedSha,
      long expectedSize, CopyObserver observer) throws IOException {
    MessageDigest digest;
    try { digest = MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    byte[] buffer = new byte[8192];
    long copied = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (count > expectedSize - copied) throw new IOException("Creation source grew during copy");
      output.write(buffer, 0, count);
      digest.update(buffer, 0, count);
      copied += count;
      if (observer != null) observer.afterChunk(copied);
    }
    StringBuilder sha = new StringBuilder();
    for (byte value : digest.digest()) sha.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    if (copied != expectedSize || !expectedSha.equals(sha.toString())) {
      throw new IOException("Creation source content changed");
    }
  }

  private static void validateCreated(File source, File destination, Attributes original,
      Attributes created, ProjectScope.Anchor parent, String expectedSha) throws IOException {
    parent.validate();
    Attributes after = attributes(destination);
    Attributes sourceAfter = attributes(source);
    if (!ProjectScope.sameIdentity(original, sourceAfter) || original.size() != sourceAfter.size()
        || !ProjectScope.sameIdentity(created, after) || after.isSymbolicLink()
        || after.size() != original.size()) {
      throw new IOException("Project file changed during exclusive creation");
    }
    try (InputStream installed = openNoFollow(destination)) {
      if (!expectedSha.equals(ProjectScope.sha256(installed))) {
        throw new IOException("New project content changed before verification");
      }
    }
    if (!ProjectScope.sameIdentity(created, attributes(destination))) {
      throw new IOException("New project file was replaced before verification");
    }
    parent.validate();
  }

  /**
   * Only for app-private recovery metadata, never project/code paths. These callers serialize
   * creation in this process; project tools and the code mount cannot access this directory.
   * Rename preserves a fully written/checksummed record across process death without hard links.
   */
  static synchronized void moveNewPrivateRecord(File source, File destination) throws IOException {
    if (!source.getParentFile().getCanonicalFile().equals(destination.getParentFile().getCanonicalFile())) {
      throw new IOException("Recovery record promotion must stay in its private directory");
    }
    if (exists(destination)) throw new IOException("Private project recovery record already exists");
    if (Build.VERSION.SDK_INT >= 26) {
      Nio.moveNew(source, destination);
    } else if (!source.renameTo(destination)) {
      throw new IOException("Could not promote private project recovery record");
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

  @androidx.annotation.RequiresApi(26)
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

    static void copyNew(InputStream input, File source, File destination, Attributes original,
        ProjectScope.Anchor parent, String expectedSha, long expectedSize, CopyObserver observer) throws IOException {
      // CREATE_NEW is atomic with respect to other creators and refuses existing symlinks.
      Set<OpenOption> options = new HashSet<>(Arrays.asList(StandardOpenOption.WRITE,
          StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS));
      FileChannel channel = FileChannel.open(destination.toPath(), options,
          PosixFilePermissions.asFileAttribute(Files.getPosixFilePermissions(source.toPath(), LinkOption.NOFOLLOW_LINKS)));
      try (FileChannel output = channel) {
        Attributes created = attributes(destination);
        copy(input, Channels.newOutputStream(output), expectedSha, expectedSize, observer);
        output.force(true);
        validateCreated(source, destination, original, created, parent, expectedSha);
      } catch (IOException | RuntimeException error) {
        throw new IncompleteCreationException(error);
      }
    }

    static void moveNew(File source, File destination) throws IOException {
      Files.move(source.toPath(), destination.toPath());
    }
  }
}
