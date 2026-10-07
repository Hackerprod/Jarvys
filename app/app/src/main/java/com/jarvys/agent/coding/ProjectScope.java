package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProjectScope {
  public static final String MISSING = "missing";
  private final List<Anchor> anchors;
  private final Set<Capability> capabilities;
  private final String conversationId;
  private final ProjectScopeIdentity durable;
  private final String id;
  private final File root;
  private final State state;

  public enum Capability {
    READ,
    WRITE
  }

  static final class State {
    final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();
    long version;
    WriterLease writer;

    State() {}
  }

  static final class Anchor {
    final Object key;
    final File path;

    Anchor(File path) throws IOException {
      this.path = path;
      ProjectFileIO.Attributes attributes = ProjectScope.attributes(path);
      if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
        throw new IOException("Project directory is not an ordinary directory");
      }
      this.key = attributes.fileKey();
      if (this.key == null) {
        throw new IOException("Filesystem cannot verify project directory identity");
      }
    }

    void validate() throws IOException {
      ProjectFileIO.Attributes attributes = ProjectScope.attributes(this.path);
      if (!attributes.isDirectory()
          || attributes.isSymbolicLink()
          || !this.key.equals(attributes.fileKey())) {
        throw new IOException(
            "Project root or parent directory was replaced; reopen requires review");
      }
    }
  }

  ProjectScope(
      String id,
      String conversationId,
      File root,
      List<Anchor> anchors,
      State state,
      Set<Capability> capabilities,
      ProjectScopeIdentity durable) {
    this.id = id;
    this.conversationId = conversationId;
    this.root = root;
    this.anchors = Collections.unmodifiableList(new ArrayList<>(anchors));
    this.state = state;
    this.durable = durable;
    EnumSet<Capability> selected = EnumSet.noneOf(Capability.class);
    selected.addAll(capabilities);
    this.capabilities = Collections.unmodifiableSet(selected);
  }

  File legacyRoot() {
    return new File(this.anchors.get(0).path, "jarvys/workspaces/" + this.id.substring(0, 24));
  }

  public String id() {
    return this.id;
  }

  public String conversationId() {
    return this.conversationId;
  }

  public Set<Capability> capabilities() {
    return this.capabilities;
  }

  public ProjectScope restrict(Set<Capability> allowed) {
    EnumSet<Capability> selected = EnumSet.noneOf(Capability.class);
    selected.addAll(this.capabilities);
    selected.retainAll(allowed);
    return new ProjectScope(
        this.id, this.conversationId, this.root, this.anchors, this.state, selected, this.durable);
  }

  public void validate() throws IOException {
    for (Anchor anchor : this.anchors) {
      anchor.validate();
    }
  }

  public String durableIdentity() throws IOException {
    validate();
    this.durable.validate();
    return this.durable.value;
  }

  public ProjectMutationJournal.RecoveryReport mutationRecovery() throws IOException {
    require(Capability.READ);
    return new ProjectMutationJournal(this).recover();
  }

  File mutationJournalDirectory() throws IOException {
    durableIdentity();
    return this.durable.journalDirectory;
  }

  public File rootDirectory() throws IOException {
    require(Capability.READ);
    validate();
    return this.root;
  }

  public File validatedMountRoot() throws IOException {
    return rootDirectory();
  }

  public long version() {
    long j;
    synchronized (this.state) {
      j = this.state.version;
    }
    return j;
  }

  public void require(Capability capability) throws IOException {
    if (!this.capabilities.contains(capability)) {
      throw new IOException("Project capability denied: " + capability.name());
    }
  }

  public String normalizePath(String value) throws IOException {
    if (value == null) {
      throw new IOException("Project path is required");
    }
    if (value.indexOf(0) >= 0
        || value.indexOf(92) >= 0
        || value.startsWith("/")
        || value.matches("^[A-Za-z]:.*")) {
      throw new IOException("Only relative project paths are allowed");
    }
    List<String> pieces = new ArrayList<>();
    for (String piece : value.split("/", -1)) {
      if (!piece.isEmpty() && !".".equals(piece)) {
        if ("..".equals(piece)) {
          throw new IOException("Parent traversal is not allowed");
        }
        if (piece.startsWith(".jarvys-tmp-")) {
          throw new IOException("Internal staging paths are not accessible");
        }
        pieces.add(piece);
      }
    }
    return pieces.isEmpty() ? "." : String.join("/", pieces);
  }

  public File resolve(String value) throws IOException {
    require(Capability.READ);
    String relative = normalizePath(value);
    validate();
    File current = this.root;
    if (!".".equals(relative)) {
      String[] parts = relative.split("/");
      for (int i = 0; i < parts.length; i++) {
        current = new File(current, parts[i]);
        if (ProjectFileIO.exists(current)) {
          ProjectFileIO.Attributes a = attributes(current);
          if (a.isSymbolicLink() || (!a.isDirectory() && !a.isRegularFile())) {
            throw new IOException(
                "Symlinks and special files are not permitted in project operations");
          }
          if (i < parts.length - 1 && !a.isDirectory()) {
            throw new IOException("Project parent is not a directory");
          }
        }
      }
    }
    if (!current.getCanonicalPath().equals(this.root.getPath())
        && !current.getCanonicalPath().startsWith(this.root.getPath() + File.separator)) {
      throw new IOException("Project path escaped its root");
    }
    validate();
    return current;
  }

  public InputStream openRead(String relative) throws IOException {
    File path = resolve(relative);
    ProjectFileIO.Attributes before = attributes(path);
    if (!before.isRegularFile()) {
      throw new IOException("Project path is not an ordinary file");
    }
    InputStream channel = ProjectFileIO.openNoFollow(path);
    try {
      resolve(relative);
      ProjectFileIO.Attributes after = attributes(path);
      if (!sameIdentity(before, after)) {
        throw new IOException("Project file changed while opening");
      }
      return channel;
    } catch (IOException | RuntimeException failure) {
      channel.close();
      throw failure;
    }
  }

  public String revision(String relative) throws IOException {
    return revision(relative, CancellationToken.uncancellable());
  }

  public String revision(String relative, CancellationToken token) throws IOException {
    File path = resolve(relative);
    if (!ProjectFileIO.exists(path)) return MISSING;
    try (InputStream input = openRead(relative)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        token.throwIfCancelled();
        digest.update(buffer, 0, count);
      }
      resolve(relative);
      return hex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public WriterLease acquireWriter(String owner, long expectedVersion) throws IOException {
    require(Capability.WRITE);
    validate();
    if (owner == null || owner.trim().isEmpty()) {
      throw new IOException("Writer owner is required");
    }
    synchronized (state) {
      if (state.writer != null) {
        throw new ConflictException("Project is being written by " + state.writer.owner);
      }
      if (expectedVersion != state.version) {
        throw new ConflictException("Project version changed; read current state before writing");
      }
      WriterLease lease = new WriterLease(this, owner, state.version);
      state.writer = lease;
      return lease;
    }
  }

  Object fileLock(String relative) {
    return state.fileLocks.computeIfAbsent(relative, key -> new Object());
  }

  public static final class WriterLease implements AutoCloseable {
    public final long acquiredVersion;
    private boolean changed;
    private final AtomicBoolean closed;
    public final String owner;
    private final ProjectScope scope;

    private WriterLease(ProjectScope scope, String owner, long acquiredVersion) {
      this.closed = new AtomicBoolean();
      this.scope = scope;
      this.owner = owner;
      this.acquiredVersion = acquiredVersion;
    }

    public void validate() throws IOException {
      this.scope.validate();
      synchronized (this.scope.state) {
        if (this.closed.get() || this.scope.state.writer != this) {
          throw new ConflictException("Project writer lease is no longer valid");
        }
      }
    }

    public void markChanged() throws IOException {
      synchronized (this.scope.state) {
        if (this.closed.get() || this.scope.state.writer != this) {
          throw new ConflictException("Project writer lease is no longer valid");
        }
        if (!this.changed) {
          this.scope.state.version++;
          this.changed = true;
        }
      }
    }

    @Override // java.lang.AutoCloseable
    public void close() {
      if (this.closed.compareAndSet(false, true)) {
        synchronized (this.scope.state) {
          if (this.scope.state.writer == this) {
            this.scope.state.writer = null;
          }
        }
      }
    }
  }

  public static class ConflictException extends IOException {
    public ConflictException(String message) {
      super(message);
    }
  }

  static ProjectFileIO.Attributes attributes(File path) throws IOException {
    return ProjectFileIO.attributes(path);
  }

  static boolean sameIdentity(ProjectFileIO.Attributes a, ProjectFileIO.Attributes b) {
    return a.fileKey() != null
        && a.fileKey().equals(b.fileKey())
        && a.isRegularFile() == b.isRegularFile();
  }

  public static String sha256(InputStream input) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      while (true) {
        int n = input.read(buffer);
        if (n == -1) {
          return hex(digest.digest());
        }
        digest.update(buffer, 0, n);
      }
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String sha256(byte[] bytes) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      out.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
    }
    return out.toString();
  }
}
