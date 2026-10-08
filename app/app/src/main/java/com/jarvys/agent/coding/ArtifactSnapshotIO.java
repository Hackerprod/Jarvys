package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Bounded binary snapshot of an ordinary file. No source path or bytes are exposed to the model. */
public final class ArtifactSnapshotIO {
  private ArtifactSnapshotIO() {}

  public static final class Snapshot {
    public final long bytes;
    public final String sha256;
    Snapshot(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
  }

  public static Snapshot copy(File root, String relative, File target, long limit,
      CancellationToken token) throws IOException {
    if (relative == null || relative.isEmpty() || relative.startsWith("/")
        || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0
        || relative.matches("^[A-Za-z]:.*")) throw new IOException("A scoped relative file path is required");
    File canonicalRoot = root.getCanonicalFile();
    if (!root.getAbsoluteFile().equals(canonicalRoot)) throw new IOException("Snapshot root cannot use symbolic links");
    List<ProjectScope.Anchor> anchors = new ArrayList<>();
    File ancestor = root.getAbsoluteFile();
    while (ancestor != null) { anchors.add(new ProjectScope.Anchor(ancestor)); ancestor = ancestor.getParentFile(); }
    File source = root;
    String[] parts = relative.split("/", -1);
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".jarvys-tmp-"))
        throw new IOException("Invalid scoped file path");
      source = new File(source, part);
      if (i < parts.length - 1) anchors.add(new ProjectScope.Anchor(source));
    }
    validate(anchors);
    ProjectFileIO.Attributes before = ProjectFileIO.attributes(source);
    if (!before.isRegularFile() || before.isSymbolicLink() || before.size() < 0 || before.size() > limit)
      throw new IOException("File is unavailable or exceeds the attachment size limit");
    if (target.getParentFile().getUsableSpace() < before.size() + 1024 * 1024)
      throw new IOException("Not enough private storage to attach this file");
    android.system.StructStat identity = VerifiedArtifactInput.identity(source);
    validate(anchors);
    MessageDigest digest = digest();
    long bytes = 0;
    try (InputStream input = VerifiedArtifactInput.open(source, identity);
         FileOutputStream output = new FileOutputStream(target)) {
      validate(anchors);
      if (!ProjectScope.sameIdentity(before, ProjectFileIO.attributes(source)))
        throw new IOException("File changed while opening");
      byte[] buffer = new byte[32768];
      int count;
      while ((count = input.read(buffer)) != -1) {
        token.throwIfCancelled();
        if (count == 0) continue;
        if (count > limit - bytes || count > before.size() - bytes)
          throw new IOException("File grew while attaching");
        output.write(buffer, 0, count); digest.update(buffer, 0, count); bytes += count;
      }
      output.getFD().sync();
    }
    token.throwIfCancelled();
    validate(anchors);
    ProjectFileIO.Attributes after = ProjectFileIO.attributes(source);
    if (!ProjectScope.sameIdentity(before, after) || before.size() != after.size() || bytes != before.size())
      throw new IOException("File changed while attaching");
    String hash = hex(digest.digest());
    // Concurrent in-place writes cannot silently turn a delivery into a mixed-version file.
    try (InputStream input = VerifiedArtifactInput.open(source, identity)) {
      MessageDigest second = digest(); long checked = 0; byte[] buffer = new byte[32768]; int count;
      while ((count = input.read(buffer)) != -1) {
        token.throwIfCancelled();
        if (count > limit - checked) throw new IOException("File grew while verifying");
        checked += count; second.update(buffer, 0, count);
      }
      if (checked != bytes || !hash.equals(hex(second.digest()))) throw new IOException("File changed while attaching");
    }
    validate(anchors);
    if (!ProjectScope.sameIdentity(before, ProjectFileIO.attributes(source))) throw new IOException("File was replaced while attaching");
    return new Snapshot(bytes, hash);
  }

  public static void syncDirectory(File directory) throws IOException { ProjectJournalIO.syncDirectory(directory); }

  private static void validate(List<ProjectScope.Anchor> anchors) throws IOException {
    for (ProjectScope.Anchor anchor : anchors) anchor.validate();
  }
  private static MessageDigest digest() {
    try { return MessageDigest.getInstance("SHA-256"); }
    catch (Exception impossible) { throw new IllegalStateException(impossible); }
  }
  public static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder();
    for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }
}
