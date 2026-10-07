package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class ProjectAdoptionService {
  private static final Set<String> LEGACY_PRIVATE_ZONES;
  private final CopyObserver observer;

  public enum EntryStatus {
    READY,
    ALREADY_PRESENT,
    COLLISION
  }

  public enum Status {
    COMPLETE,
    PARTIAL,
    CANCELLED,
    CONFLICT,
    FAILED
  }

  static {
    Set<String> zones = new HashSet<>();
    Collections.addAll(
        zones,
        "attachments",
        "memory",
        "skills",
        ".jarvys",
        "jarvys",
        "coding-projects",
        "workspaces");
    LEGACY_PRIVATE_ZONES = Collections.unmodifiableSet(zones);
  }

  public static final class Entry {
    public final String destinationSha;
    public final String path;
    public final String sha;
    public final long size;
    public final EntryStatus status;

    Entry(String path, String sha, String destinationSha, long size) {
      EntryStatus entryStatus;
      this.path = path;
      this.sha = sha;
      this.destinationSha = destinationSha;
      this.size = size;
      if (ProjectScope.MISSING.equals(destinationSha)) {
        entryStatus = EntryStatus.READY;
      } else {
        entryStatus =
            sha.equals(destinationSha) ? EntryStatus.ALREADY_PRESENT : EntryStatus.COLLISION;
      }
      this.status = entryStatus;
    }
  }

  public static final class Review {
    public final List<Entry> entries;
    public final String scopeId;
    private final LegacySource source;

    Review(String scopeId, LegacySource source, List<Entry> entries) {
      this.scopeId = scopeId;
      this.source = source;
      this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }
  }

  public static final class Result {
    public final List<String> alreadyPresent;
    public final List<String> cleanupWarnings;
    public final List<String> copied;
    public final String message;
    public final long scopeVersion;
    public final Status status;

    Result(
        Status status,
        List<String> copied,
        List<String> alreadyPresent,
        String message,
        long version,
        List<String> cleanupWarnings) {
      this.status = status;
      this.copied = Collections.unmodifiableList(new ArrayList<>(copied));
      this.alreadyPresent = Collections.unmodifiableList(new ArrayList<>(alreadyPresent));
      this.cleanupWarnings = Collections.unmodifiableList(new ArrayList<>(cleanupWarnings));
      this.message =
          message + (cleanupWarnings.isEmpty() ? "" : "\n" + String.join("\n", cleanupWarnings));
      this.scopeVersion = version;
    }
  }

  public interface CopyObserver {
    void beforeCopy(int index, String path) throws IOException;

    default void afterCreateDirectory(String path) throws IOException {}

    default void afterPromotion(String path) throws IOException {}

    default void beforeCleanup(String path, boolean directory) throws IOException {}
  }

  public ProjectAdoptionService() {
    this(null);
  }

  public ProjectAdoptionService(CopyObserver observer) {
    this.observer = observer;
  }

  public Review review(
      ProjectScope scope, List<String> explicitRelativeAllowlist, CancellationToken token)
      throws IOException {
    return review(scope, scope.legacyRoot(), explicitRelativeAllowlist, token);
  }

  Review review(
      ProjectScope scope,
      File legacyRoot,
      List<String> explicitRelativeAllowlist,
      CancellationToken token)
      throws IOException {
    CancellationToken cancellation = token == null ? CancellationToken.uncancellable() : token;
    if (explicitRelativeAllowlist == null || explicitRelativeAllowlist.isEmpty()) {
      throw new IOException(
          "Select explicit legacy files to review; recursive adoption is not supported");
    }
    scope.validate();
    LegacySource source = new LegacySource(legacyRoot);
    if (isInside(source.root, scope.rootDirectory())
        || isInside(scope.rootDirectory(), source.root)) {
      throw new IOException("Legacy and project storage must have separate roots");
    }
    Set<String> selected = new HashSet<>();
    List<Entry> entries = new ArrayList<>();
    for (String requested : explicitRelativeAllowlist) {
      cancellation.throwIfCancelled();
      String path = scope.normalizePath(requested);
      String first = path.contains("/") ? path.substring(0, path.indexOf(47)) : path;
      if (LEGACY_PRIVATE_ZONES.contains(first.toLowerCase(Locale.ROOT))) {
        throw new IOException("Private legacy zones cannot be adopted");
      }
      if (".".equals(path)) {
        throw new IOException("Choose individual legacy files, not a directory");
      }
      if (selected.add(path)) {
        File from = source.resolve(path);
        ProjectFileIO.Attributes attrs = ProjectScope.attributes(from);
        if (!attrs.isRegularFile()) {
          throw new IOException("Only ordinary legacy files may be adopted");
        }
        String sha = source.hash(path, cancellation);
        String destinationSha = scope.revision(path, cancellation);
        entries.add(new Entry(path, sha, destinationSha, attrs.size()));
      }
    }
    return new Review(scope.id(), source, entries);
  }

  public Result adopt(
      ProjectScope scope,
      String owner,
      long expectedScopeVersion,
      Review review,
      CancellationToken token) {
    List<String> copied = new ArrayList<>();
    List<String> already = new ArrayList<>();
    List<String> cleanupWarnings = new ArrayList<>();
    CancellationToken cancellation = token == null ? CancellationToken.uncancellable() : token;
    try (ProjectScope.WriterLease lease = scope.acquireWriter(owner, expectedScopeVersion)) {
      if (review == null || !scope.id().equals(review.scopeId)) {
        throw new IOException("Adoption review belongs to a different project");
      }
      for (Entry entry : review.entries) {
        cancellation.throwIfCancelled();
        if (!entry.sha.equals(review.source.hash(entry.path, cancellation))) {
          throw new ProjectScope.ConflictException(
              "Legacy source changed since review: " + entry.path);
        }
        String actual = scope.revision(entry.path, cancellation);
        if (!ProjectScope.MISSING.equals(actual) && !entry.sha.equals(actual)) {
          throw new ProjectScope.ConflictException("Adoption destination collision: " + entry.path);
        }
        if (entry.status == EntryStatus.COLLISION) {
          throw new ProjectScope.ConflictException(
              "Review contains a destination collision: " + entry.path);
        }
        if (entry.sha.equals(actual)) already.add(entry.path);
      }
      for (int i = 0; i < review.entries.size(); i++) {
        Entry entry = review.entries.get(i);
        cancellation.throwIfCancelled();
        lease.validate();
        if (already.contains(entry.path)) continue;
        if (observer != null) observer.beforeCopy(i, entry.path);
        synchronized (scope.fileLock(entry.path)) {
          copy(scope, lease, review.source, entry, copied, cleanupWarnings, cancellation);
        }
      }
      return new Result(
          cleanupWarnings.isEmpty() ? Status.COMPLETE : Status.PARTIAL,
          copied,
          already,
          "Selected files copied; all legacy originals preserved",
          scope.version(),
          cleanupWarnings);
    } catch (CancellationException stopped) {
      return new Result(
          copied.isEmpty() && cleanupWarnings.isEmpty() ? Status.CANCELLED : Status.PARTIAL,
          copied,
          already,
          "Adoption cancelled; only listed copies completed and originals remain intact",
          scope.version(),
          cleanupWarnings);
    } catch (ProjectScope.ConflictException conflict) {
      return new Result(
          copied.isEmpty() && cleanupWarnings.isEmpty() ? Status.CONFLICT : Status.PARTIAL,
          copied,
          already,
          conflict.getMessage(),
          scope.version(),
          cleanupWarnings);
    } catch (IOException | RuntimeException error) {
      return new Result(
          copied.isEmpty() && cleanupWarnings.isEmpty() ? Status.FAILED : Status.PARTIAL,
          copied,
          already,
          error.getMessage() == null ? "Adoption failed" : error.getMessage(),
          scope.version(),
          cleanupWarnings);
    }
  }

  private void copy(
      ProjectScope scope,
      ProjectScope.WriterLease lease,
      LegacySource source,
      Entry entry,
      List<String> copied,
      List<String> cleanupWarnings,
      CancellationToken token)
      throws IOException {
    File target = scope.resolve(entry.path);
    if (!ProjectScope.MISSING.equals(scope.revision(entry.path, token))) {
      throw new ProjectScope.ConflictException("Adoption destination changed: " + entry.path);
    }
    List<ProjectFileCleanup.Entry> createdDirectories = new ArrayList<>();
    ProjectFileCleanup.Entry temporary = null;
    boolean promoted = false;
    try {
      int slash = entry.path.lastIndexOf('/');
      String prefix = "";
      if (slash >= 0) {
        for (String part : entry.path.substring(0, slash).split("/")) {
          prefix = prefix.isEmpty() ? part : prefix + "/" + part;
          File directory = scope.resolve(prefix);
          if (!ProjectFileIO.exists(directory)) {
            ProjectFileIO.mkdir(directory);
            ProjectFileCleanup.Entry created =
                new ProjectFileCleanup.Entry(directory, prefix, true);
            createdDirectories.add(created);
            created.captureIdentity();
            if (observer != null) observer.afterCreateDirectory(prefix);
          }
          scope.resolve(prefix);
        }
      }
      scope.resolve(entry.path);
      File staged = File.createTempFile(".jarvys-tmp-", ".part", target.getParentFile());
      String stagingPath = (slash < 0 ? "" : entry.path.substring(0, slash + 1)) + staged.getName();
      temporary = new ProjectFileCleanup.Entry(staged, stagingPath, false);
      temporary.captureIdentity();
      MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
      try (InputStream input = source.open(entry.path);
          FileOutputStream output = new FileOutputStream(staged)) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
          token.throwIfCancelled();
          output.write(buffer, 0, n);
          digest.update(buffer, 0, n);
        }
        output.getFD().sync();
      }
      StringBuilder actual = new StringBuilder();
      for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
      if (!entry.sha.equals(actual.toString())) {
        throw new ProjectScope.ConflictException(
            "Legacy source changed while copying: " + entry.path);
      }
      ProjectFileIO.preservePermissions(source.resolve(entry.path), staged);
      token.throwIfCancelled();
      scope.resolve(entry.path);
      ProjectFileIO.linkNoReplace(staged, target);
      promoted = true;
      copied.add(entry.path);
      lease.markChanged();
      if (observer != null) observer.afterPromotion(entry.path);
    } finally {
      ProjectFileCleanup.Observer cleanupObserver =
          observer == null ? null : observer::beforeCleanup;
      if (temporary != null)
        ProjectFileCleanup.remove(scope, lease, temporary, cleanupWarnings, cleanupObserver);
      if (!promoted) {
        Collections.reverse(createdDirectories);
        for (ProjectFileCleanup.Entry directory : createdDirectories) {
          ProjectFileCleanup.remove(scope, lease, directory, cleanupWarnings, cleanupObserver);
        }
      }
    }
  }

  private static boolean isInside(File candidate, File root) throws IOException {
    String path = candidate.getCanonicalPath();
    String parent = root.getCanonicalPath();
    return path.equals(parent) || path.startsWith(parent + File.separator);
  }

  private static final class LegacySource {
    final List<ProjectScope.Anchor> anchors = new ArrayList<>();
    final File root;

    LegacySource(File root) throws IOException {
      if (root == null) {
        throw new IOException("Legacy source root is required");
      }
      this.root = root.getAbsoluteFile();
      List<File> chain = new ArrayList<>();
      for (File current = this.root; current != null; current = current.getParentFile()) {
        chain.add(current);
      }
      Collections.reverse(chain);
      for (File current2 : chain) {
        this.anchors.add(new ProjectScope.Anchor(current2));
      }
    }

    File resolve(String relative) throws IOException {
      for (ProjectScope.Anchor anchor : this.anchors) {
        anchor.validate();
      }
      File current = this.root;
      String[] pieces = relative.split("/");
      for (int i = 0; i < pieces.length; i++) {
        current = new File(current, pieces[i]);
        ProjectFileIO.Attributes attrs = ProjectScope.attributes(current);
        if (attrs.isSymbolicLink() || (!attrs.isDirectory() && !attrs.isRegularFile())) {
          throw new IOException("Legacy symlinks and special files cannot be adopted");
        }
        if (i < pieces.length - 1 && !attrs.isDirectory()) {
          throw new IOException("Legacy source parent is not a directory");
        }
      }
      for (ProjectScope.Anchor anchor2 : this.anchors) {
        anchor2.validate();
      }
      return current;
    }

    InputStream open(String relative) throws IOException {
      File path = resolve(relative);
      ProjectFileIO.Attributes before = ProjectScope.attributes(path);
      if (!before.isRegularFile()) {
        throw new IOException("Only ordinary legacy files can be adopted");
      }
      InputStream channel = ProjectFileIO.openNoFollow(path);
      try {
        ProjectFileIO.Attributes after = ProjectScope.attributes(resolve(relative));
        if (!ProjectScope.sameIdentity(before, after)) {
          throw new IOException("Legacy source changed while opening");
        }
        return channel;
      } catch (IOException | RuntimeException failure) {
        channel.close();
        throw failure;
      }
    }

    String hash(String relative, CancellationToken token) throws IOException {
      try (InputStream input = open(relative)) {
        MessageDigest digest;
        try {
          digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
          throw new IllegalStateException(impossible);
        }
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
          token.throwIfCancelled();
          digest.update(buffer, 0, n);
        }
        resolve(relative);
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
      }
    }
  }
}
