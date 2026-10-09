package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

public final class ProjectMutationService {
  private final long maxMaterializedBytes;
  private final CommitObserver observer;

  public enum Kind {
    ADD,
    WRITE,
    EDIT,
    MOVE,
    DELETE
  }

  public enum Status {
    APPLIED,
    PARTIAL,
    CONFLICT,
    CANCELLED,
    FAILED
  }

  public static final class Hunk {
    public final String newText;
    public final String oldText;

    public Hunk(String oldText, String newText) {
      this.oldText = oldText;
      this.newText = newText;
    }
  }

  public static final class Operation {
    public final String destination;
    public final String expectedDestinationSha;
    public final String expectedSha;
    public final List<Hunk> hunks;
    public final Kind kind;
    public final String path;
    public final String text;
    private final byte[] binary;

    private Operation(
        Kind kind,
        String path,
        String expectedSha,
        String text,
        List<Hunk> hunks,
        String destination,
        String expectedDestinationSha) {
      this(kind, path, expectedSha, text, hunks, destination, expectedDestinationSha, null);
    }

    private Operation(Kind kind, String path, String expectedSha, String text, List<Hunk> hunks,
        String destination, String expectedDestinationSha, byte[] binary) {
      this.binary = binary == null ? null : binary.clone();
      this.kind = kind;
      this.path = path;
      this.expectedSha = expectedSha;
      this.text = text;
      this.hunks =
          hunks == null
              ? Collections.emptyList()
              : Collections.unmodifiableList(new ArrayList<>(hunks));
      this.destination = destination;
      this.expectedDestinationSha = expectedDestinationSha;
    }

    public static Operation add(String path, String text) {
      return new Operation(Kind.ADD, path, ProjectScope.MISSING, text, null, null, null);
    }

    public static Operation write(String path, String expectedSha, String text) {
      return new Operation(Kind.WRITE, path, expectedSha, text, null, null, null);
    }

    public static Operation edit(String path, String expectedSha, List<Hunk> hunks) {
      return new Operation(Kind.EDIT, path, expectedSha, null, hunks, null, null);
    }

    public static Operation move(
        String path, String expectedSha, String destination, String expectedDestinationSha) {
      return new Operation(
          Kind.MOVE, path, expectedSha, null, null, destination, expectedDestinationSha);
    }

    public static Operation delete(String path, String expectedSha) {
      return new Operation(Kind.DELETE, path, expectedSha, null, null, null, null);
    }
  }

  public static final class Applied {
    public final String afterSha;
    public final String beforeSha;
    public final String destination;
    public final String diff;
    public final Kind kind;
    public final String path;

    Applied(
        Kind kind,
        String path,
        String destination,
        String beforeSha,
        String afterSha,
        String diff) {
      this.kind = kind;
      this.path = path;
      this.destination = destination;
      this.beforeSha = beforeSha;
      this.afterSha = afterSha;
      this.diff = diff;
    }
  }

  public static final class Result {
    public final List<Applied> applied;
    public final List<String> cleanupWarnings;
    public final String journalId;
    public final String message;
    public final long scopeVersion;
    public final Status status;

    Result(
        Status status,
        List<Applied> applied,
        String message,
        long scopeVersion,
        List<String> cleanupWarnings) {
      this(status, applied, message, scopeVersion, cleanupWarnings, null);
    }

    Result(
        Status status,
        List<Applied> applied,
        String message,
        long scopeVersion,
        List<String> cleanupWarnings,
        String journalId) {
      this.status = status;
      this.applied = Collections.unmodifiableList(new ArrayList<>(applied));
      this.cleanupWarnings = Collections.unmodifiableList(new ArrayList<>(cleanupWarnings));
      this.message =
          message + (cleanupWarnings.isEmpty() ? "" : "\n" + String.join("\n", cleanupWarnings));
      this.scopeVersion = scopeVersion;
      this.journalId = journalId;
    }

    public boolean isSuccess() {
      return this.status == Status.APPLIED;
    }
  }

  public interface CommitObserver {
    void beforeCommit(int index, String path) throws IOException;

    default void afterCreateDirectory(String path) throws IOException {}

    default void afterPromotion(String path) throws IOException {}

    /** Last live authority check before the filesystem publication boundary. */
    default void beforePromotion(String path) throws IOException {}

    default void afterCreationChunk(String path, long copied) throws IOException {}

    default void afterEffectBeforeJournal(String path) throws IOException {}

    default void beforeCleanup(String path, boolean directory) throws IOException {}
  }

  public ProjectMutationService() {
    this(262144L, null);
  }

  public ProjectMutationService(long maxMaterializedBytes) {
    this(maxMaterializedBytes, null);
  }

  public ProjectMutationService(long maxMaterializedBytes, CommitObserver observer) {
    if (maxMaterializedBytes <= 0 || maxMaterializedBytes > 2147483639) {
      throw new IllegalArgumentException("A positive materialization budget is required");
    }
    this.maxMaterializedBytes = maxMaterializedBytes;
    this.observer = observer;
  }

  private static final class Plan {
    final byte[] after;
    final byte[] before;
    final String beforeSha;
    final String changeDiff;
    final String destination;
    final byte[] destinationBefore;
    final String destinationSha;
    final Operation op;
    final String path;
    final String stagingPath;

    Plan(
        Operation op,
        String path,
        String destination,
        byte[] before,
        byte[] after,
        byte[] destinationBefore)
        throws IOException {
      String str;
      this.op = op;
      this.path = path;
      this.destination = destination;
      this.before = before;
      this.after = after;
      this.destinationBefore = destinationBefore;
      this.beforeSha = ProjectMutationService.hash(before);
      this.destinationSha = ProjectMutationService.hash(destinationBefore);
      this.changeDiff = op.binary != null
          ? "Binary asset: " + (before == null ? 0 : before.length) + " -> " + after.length + " bytes"
          : ProjectMutationService.diff(
              destination == null ? path : destination,
              destination == null ? before : destinationBefore,
              after);
      String target = destination == null ? path : destination;
      int slash = target.lastIndexOf(47);
      if (op.kind == Kind.DELETE) {
        str = null;
      } else {
        str =
            (slash < 0 ? "" : target.substring(0, slash + 1))
                + ".jarvys-tmp-"
                + UUID.randomUUID()
                + ".part";
      }
      this.stagingPath = str;
    }
  }

  public Result apply(
      ProjectScope scope,
      String owner,
      long expectedScopeVersion,
      List<Operation> operations,
      CancellationToken token) {
    try (ProjectScope.WriterLease lease = scope.acquireWriter(owner, expectedScopeVersion)) {
      return applyHeld(scope, lease, operations, token);
    } catch (IOException failure) {
      return new Result(failure instanceof ProjectScope.ConflictException ? Status.CONFLICT : Status.FAILED,
          Collections.emptyList(), failure.getMessage(), scope.version(), Collections.emptyList());
    }
  }

  /** Runtime-owned binary source; never exposed as a base64/text mutation argument. */
  public interface BinarySource { byte[] produce() throws IOException; }

  /** Validate before consuming provider quota, holding the same scoped writer lease until receipt. */
  public Result produceBinary(ProjectScope scope, String owner, long expectedScopeVersion,
      String path, String expectedSha, BinarySource source, CancellationToken token) throws IOException {
    token.throwIfCancelled();
    try (ProjectScope.WriterLease lease = scope.acquireWriter(owner, expectedScopeVersion)) {
      String relative = ordinaryPath(scope, path);
      requireExpected(expectedSha);
      checkExpected(expectedSha, hash(read(scope, relative, token)), relative);
      byte[] bytes = source.produce();
      token.throwIfCancelled();
      lease.validate();
      if (bytes == null || bytes.length == 0 || bytes.length > maxMaterializedBytes)
        throw new IOException("Binary output exceeds the bounded asset budget");
      Operation operation = new Operation(Kind.WRITE, relative, expectedSha, null, null, null, null, bytes);
      return applyHeld(scope, lease, Collections.singletonList(operation), token);
    }
  }

  private Result applyHeld(ProjectScope scope, ProjectScope.WriterLease lease,
      List<Operation> operations, CancellationToken token) {
    List<Applied> applied = new ArrayList<>();
    List<String> cleanupWarnings = new ArrayList<>();
    CancellationToken cancellation = token == null ? CancellationToken.uncancellable() : token;
    ProjectMutationJournal.Transaction journal = null;
    try {
      lease.validate();
      List<Plan> plans = preflight(scope, operations, cancellation);
      cancellation.throwIfCancelled();
      journal = new ProjectMutationJournal(scope).begin(lease.owner, journalChanges(scope, plans));
      for (int i = 0; i < plans.size(); i++) {
        Plan plan = plans.get(i);
        cancellation.throwIfCancelled();
        lease.validate();
        if (observer != null) observer.beforeCommit(i, plan.path);
        synchronized (scope.fileLock(plan.path)) {
          commit(scope, lease, plan, applied, cleanupWarnings, cancellation, journal);
        }
      }
      return finish(
          journal,
          cleanupWarnings.isEmpty() ? Status.APPLIED : Status.PARTIAL,
          applied,
          "Applied with durable receipts; replacements are atomic, new files are exclusively created; no multi-file transaction is claimed",
          scope.version(),
          cleanupWarnings);
    } catch (CancellationException stopped) {
      return finish(
          journal,
          applied.isEmpty() && cleanupWarnings.isEmpty() ? Status.CANCELLED : Status.PARTIAL,
          applied,
          "Cancelled; only listed operations were applied",
          scope.version(),
          cleanupWarnings);
    } catch (ProjectScope.ConflictException stale) {
      return finish(
          journal,
          applied.isEmpty() && cleanupWarnings.isEmpty() ? Status.CONFLICT : Status.PARTIAL,
          applied,
          stale.getMessage(),
          scope.version(),
          cleanupWarnings);
    } catch (IOException | RuntimeException failure) {
      return finish(
          journal,
          applied.isEmpty() && cleanupWarnings.isEmpty() ? Status.FAILED : Status.PARTIAL,
          applied,
          failure.getMessage() == null ? "Project mutation failed" : failure.getMessage(),
          scope.version(),
          cleanupWarnings);
    }
  }

  private static Result finish(
      ProjectMutationJournal.Transaction journal,
      Status status,
      List<Applied> applied,
      String message,
      long version,
      List<String> warnings) {
    if (journal != null) {
      try {
        journal.finish(status, warnings.size());
      } catch (IOException | RuntimeException failure) {
        warnings.add(
            "Mutation terminal journal could not be persisted; recover by inspecting current files,"
                + " never replaying");
        status = !applied.isEmpty() || status == Status.PARTIAL ? Status.PARTIAL : Status.FAILED;
      }
    }
    return new Result(
        status, applied, message, version, warnings, journal == null ? null : journal.id);
  }

  private static List<ProjectMutationJournal.Change> journalChanges(
      ProjectScope scope, List<Plan> plans) throws IOException {
    List<ProjectMutationJournal.Change> changes = new ArrayList<>();
    Set<String> parents = new HashSet<>();
    for (Plan plan : plans) {
      String target = plan.destination == null ? plan.path : plan.destination;
      if (plan.op.kind != Kind.DELETE) {
        int lastSlash = target.lastIndexOf(47);
        if (lastSlash >= 0) {
          String prefix = "";
          for (String part : target.substring(0, lastSlash).split("/")) {
            prefix = prefix.isEmpty() ? part : prefix + "/" + part;
            if (parents.add(prefix) && !ProjectFileIO.exists(scope.resolve(prefix))) {
              changes.add(ProjectMutationJournal.Change.directory(prefix));
            }
          }
        }
        changes.add(ProjectMutationJournal.Change.staging(plan.stagingPath, hash(plan.after)));
      }
      if (plan.op.kind == Kind.MOVE) {
        changes.add(
            new ProjectMutationJournal.Change(
                plan.destination,
                "MOVE_DESTINATION",
                plan.destinationSha,
                hash(plan.after),
                false));
        changes.add(
            new ProjectMutationJournal.Change(
                plan.path, "MOVE_SOURCE_DELETE", plan.beforeSha, ProjectScope.MISSING, false));
      } else {
        changes.add(
            new ProjectMutationJournal.Change(
                plan.path, plan.op.kind.name(), plan.beforeSha, hash(plan.after), false));
      }
    }
    return changes;
  }

  private List<Plan> preflight(
      ProjectScope scope, List<Operation> operations, CancellationToken token) throws IOException {
    if (operations == null || operations.isEmpty()) {
      throw new IOException("At least one patch operation is required");
    }
    List<Plan> plans = new ArrayList<>();
    Set<String> touched = new HashSet<>();
    for (Operation op : operations) {
      token.throwIfCancelled();
      if (op == null) throw new IOException("Patch operation is missing");
      String path = ordinaryPath(scope, op.path);
      if (!touched.add(path)) {
        throw new IOException("Patch paths overlap; combine edits for " + path);
      }
      requireExpected(op.expectedSha);
      byte[] before = read(scope, path, token);
      checkExpected(op.expectedSha, hash(before), path);
      String destination = null;
      byte[] destinationBefore = null;
      byte[] after = null;
      if (op.kind != Kind.ADD && op.kind != Kind.WRITE && before == null) {
        throw new IOException("Source file does not exist: " + path);
      }
      switch (op.kind) {
        case ADD:
          if (before != null) {
            throw new ProjectScope.ConflictException("Destination already exists: " + path);
          }
          after = encode(op.text, null);
          break;
        case WRITE:
          after = op.binary == null ? encode(op.text, before) : op.binary;
          break;
        case EDIT:
          if (op.hunks.isEmpty()) throw new IOException("An edit needs at least one exact hunk");
          Text decoded = decode(before);
          String modified = decoded.value;
          for (Hunk hunk : op.hunks) {
            token.throwIfCancelled();
            if (hunk == null
                || hunk.oldText == null
                || hunk.oldText.isEmpty()
                || hunk.newText == null) {
              throw new IOException("Exact hunk old text must be nonempty");
            }
            String old = decoded.adapt(hunk.oldText);
            String replacement = decoded.adapt(hunk.newText);
            int match = modified.indexOf(old);
            if (match < 0)
              throw new ProjectScope.ConflictException("Patch hunk does not match: " + path);
            if (modified.indexOf(old, match + 1) >= 0) {
              throw new ProjectScope.ConflictException("Patch hunk is ambiguous: " + path);
            }
            modified =
                modified.substring(0, match)
                    + replacement
                    + modified.substring(match + old.length());
          }
          after = decoded.bytes(modified);
          break;
        case MOVE:
          destination = ordinaryPath(scope, op.destination);
          if (!touched.add(destination))
            throw new IOException("Patch paths overlap: " + destination);
          requireExpected(op.expectedDestinationSha);
          destinationBefore = read(scope, destination, token);
          checkExpected(op.expectedDestinationSha, hash(destinationBefore), destination);
          decode(before);
          if (destinationBefore != null) decode(destinationBefore);
          after = before;
          break;
        case DELETE:
          decode(before);
          break;
      }
      if (after != null && op.binary == null) decode(after);
      if (after != null && after.length > maxMaterializedBytes) {
        throw new IOException(
            "Mutation exceeds the text materialization budget; use a smaller edit or an authorized"
                + " command");
      }
      plans.add(new Plan(op, path, destination, before, after, destinationBefore));
    }
    for (String a : touched) {
      for (String b : touched) {
        if (!a.equals(b) && a.startsWith(b + "/")) {
          throw new IOException("Patch contains overlapping file and directory paths");
        }
      }
    }
    return plans;
  }

  private void commit(
      ProjectScope scope,
      ProjectScope.WriterLease lease,
      Plan plan,
      List<Applied> applied,
      List<String> cleanupWarnings,
      CancellationToken token,
      ProjectMutationJournal.Transaction journal)
      throws IOException {
    token.throwIfCancelled();
    lease.validate();
    checkExpected(plan.beforeSha, scope.revision(plan.path, token), plan.path);
    if (plan.destination != null) {
      checkExpected(plan.destinationSha, scope.revision(plan.destination, token), plan.destination);
    }
    if (plan.op.kind == Kind.DELETE) {
      Applied receipt =
          new Applied(
              Kind.DELETE, plan.path, null, plan.beforeSha, ProjectScope.MISSING, plan.changeDiff);
      ProjectFileIO.delete(scope.resolve(plan.path));
      applied.add(receipt);
      lease.markChanged();
      recordEffect(scope, plan.path, journal);
      if (this.observer != null) {
        this.observer.afterPromotion(plan.path);
        return;
      }
      return;
    }
    if (plan.op.kind == Kind.MOVE) {
      synchronized (scope.fileLock(plan.destination)) {
        Applied copy =
            new Applied(
                plan.destinationBefore == null ? Kind.ADD : Kind.WRITE,
                plan.destination,
                null,
                plan.destinationSha,
                hash(plan.after),
                plan.changeDiff);
        atomicWrite(
            scope,
            lease,
            plan.destination,
            plan.after,
            plan.destinationSha,
            plan.path,
            copy,
            applied,
            cleanupWarnings,
            token,
            plan.stagingPath,
            journal);
        checkExpected(plan.beforeSha, scope.revision(plan.path, token), plan.path);
        Applied moved =
            new Applied(
                Kind.MOVE,
                plan.path,
                plan.destination,
                plan.beforeSha,
                hash(plan.after),
                "rename from "
                    + plan.path
                    + "\nrename to "
                    + plan.destination
                    + "\n"
                    + plan.changeDiff);
        ProjectFileIO.delete(scope.resolve(plan.path));
        applied.set(applied.size() - 1, moved);
        recordEffect(scope, plan.path, journal);
        if (this.observer != null) {
          this.observer.afterPromotion(plan.path);
        }
      }
      return;
    }
    Applied receipt2 =
        new Applied(
            plan.op.kind, plan.path, null, plan.beforeSha, hash(plan.after), plan.changeDiff);
    atomicWrite(
        scope,
        lease,
        plan.path,
        plan.after,
        plan.beforeSha,
        plan.before == null ? null : plan.path,
        receipt2,
        applied,
        cleanupWarnings,
        token,
        plan.stagingPath,
        journal);
  }

  private void recordEffect(
      ProjectScope scope, String relative, ProjectMutationJournal.Transaction journal)
      throws IOException {
    if (this.observer != null) {
      this.observer.afterEffectBeforeJournal(relative);
    }
    int slash = relative.lastIndexOf(47);
    ProjectJournalIO.syncDirectory(scope.resolve(slash < 0 ? "." : relative.substring(0, slash)));
    journal.applied(relative);
  }

  private void atomicWrite(
      ProjectScope scope,
      ProjectScope.WriterLease lease,
      String relative,
      byte[] bytes,
      String expected,
      String permissionsFrom,
      Applied receipt,
      List<Applied> applied,
      List<String> cleanupWarnings,
      CancellationToken token,
      String stagingPath,
      ProjectMutationJournal.Transaction journal)
      throws IOException {
    File target = scope.resolve(relative);
    List<ProjectFileCleanup.Entry> createdDirectories = new ArrayList<>();
    ProjectFileCleanup.Entry temporary = null;
    boolean promoted = false;
    try {
      createParents(scope, relative, createdDirectories, journal);
      scope.resolve(relative);
      File staged =
          new File(target.getParentFile(), stagingPath.substring(stagingPath.lastIndexOf('/') + 1));
      if (!staged.createNewFile()) throw new IOException("Mutation staging target already exists");
      temporary = new ProjectFileCleanup.Entry(staged, stagingPath, false);
      temporary.captureIdentity();
      try (FileOutputStream output = new FileOutputStream(staged)) {
        output.write(bytes);
        output.getFD().sync();
      }
      ProjectJournalIO.syncDirectory(staged.getParentFile());
      journal.applied(stagingPath);
      token.throwIfCancelled();
      scope.resolve(relative);
      checkExpected(expected, scope.revision(relative, token), relative);
      if (permissionsFrom != null)
        ProjectFileIO.preservePermissions(scope.resolve(permissionsFrom), staged);
      scope.resolve(relative);
      lease.validate();
      token.throwIfCancelled();
      if (observer != null) observer.beforePromotion(relative);
      if (ProjectScope.MISSING.equals(expected)) {
        try {
          ProjectFileIO.copyNew(staged, target, hash(bytes), bytes.length, copied -> {
            token.throwIfCancelled();
            lease.validate();
            if (observer != null) observer.afterCreationChunk(relative, copied);
          });
          promoted = true;
        } catch (ProjectFileIO.IncompleteCreationException interruptedCreation) {
          // The destination was exclusively created but completion was not verified. Keep both
          // paths and the durable intent, invalidate stale scope versions, and never replay.
          promoted = true;
          temporary = null;
          cleanupWarnings.add("New file creation is incomplete or unverified: " + relative
              + "; staging and recovery evidence retained. Inspect before retrying.");
          lease.markChanged();
          throw interruptedCreation;
        }
      } else {
        ProjectFileIO.atomicReplace(staged, target);
        promoted = true;
        temporary = null;
      }
      applied.add(receipt);
      lease.markChanged();
      recordEffect(scope, relative, journal);
      if (temporary == null) journal.cleaned(stagingPath);
      if (observer != null) observer.afterPromotion(relative);
    } finally {
      ProjectFileCleanup.Observer cleanupObserver =
          observer == null ? null : observer::beforeCleanup;
      if (temporary != null) {
        ProjectFileCleanup.remove(scope, lease, temporary, cleanupWarnings, cleanupObserver);
        recordCleanup(scope, temporary, cleanupWarnings, journal);
      }
      if (!promoted) {
        Collections.reverse(createdDirectories);
        for (ProjectFileCleanup.Entry directory : createdDirectories) {
          ProjectFileCleanup.remove(scope, lease, directory, cleanupWarnings, cleanupObserver);
          recordCleanup(scope, directory, cleanupWarnings, journal);
        }
      }
    }
  }

  private static void recordCleanup(
      ProjectScope scope,
      ProjectFileCleanup.Entry entry,
      List<String> warnings,
      ProjectMutationJournal.Transaction journal) {
    try {
      scope.validate();
      if (!ProjectFileIO.exists(entry.file)) {
        ProjectJournalIO.syncDirectory(entry.file.getParentFile());
        journal.cleaned(entry.relative);
      }
    } catch (IOException | RuntimeException e) {
      warnings.add(
          "Mutation cleanup journal could not be persisted; cleanup outcome requires inspection");
    }
  }

  private void createParents(
      ProjectScope scope,
      String relative,
      List<ProjectFileCleanup.Entry> created,
      ProjectMutationJournal.Transaction journal)
      throws IOException {
    int lastSlash = relative.lastIndexOf(47);
    if (lastSlash < 0) {
      return;
    }
    String[] parts = relative.substring(0, lastSlash).split("/");
    String prefix = "";
    for (String part : parts) {
      prefix = prefix.isEmpty() ? part : prefix + "/" + part;
      File directory = scope.resolve(prefix);
      if (!ProjectFileIO.exists(directory)) {
        if (!journal.contains(prefix)) {
          throw new IOException("Project parent changed after mutation intent");
        }
        ProjectFileIO.mkdir(directory);
        ProjectFileCleanup.Entry entry = new ProjectFileCleanup.Entry(directory, prefix, true);
        created.add(entry);
        entry.captureIdentity();
        recordEffect(scope, prefix, journal);
        if (this.observer != null) {
          this.observer.afterCreateDirectory(prefix);
        }
      }
      if (!ProjectFileIO.isDirectory(directory)) {
        throw new IOException("Project parent is not a directory");
      }
      scope.resolve(prefix);
    }
  }

  private byte[] read(ProjectScope scope, String path, CancellationToken token) throws IOException {
    File file = scope.resolve(path);
    if (!ProjectFileIO.exists(file)) return null;
    if (ProjectFileIO.attributes(file).size() > maxMaterializedBytes) {
      throw new IOException(
          "Mutation exceeds the text materialization budget; use a smaller edit or an authorized"
              + " command");
    }
    try (InputStream input = scope.openRead(path);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = input.read(buffer)) != -1) {
        token.throwIfCancelled();
        if ((long) output.size() + n > maxMaterializedBytes) {
          throw new IOException("File grew beyond the text materialization budget");
        }
        output.write(buffer, 0, n);
      }
      return output.toByteArray();
    }
  }

  private String ordinaryPath(ProjectScope scope, String path) throws IOException {
    String normalized = scope.normalizePath(path);
    if (".".equals(normalized)) {
      throw new IOException("A project file path is required");
    }
    scope.resolve(normalized);
    return normalized;
  }

  private static void requireExpected(String expected) throws IOException {
    if (expected == null
        || (!ProjectScope.MISSING.equals(expected) && !expected.matches("[0-9a-f]{64}"))) {
      throw new IOException("Expected SHA-256 or 'missing' is required");
    }
  }

  private static void checkExpected(String expected, String actual, String path)
      throws IOException {
    if (!expected.equals(actual)) {
      throw new ProjectScope.ConflictException(
          "Stale file revision: " + path + "; read it again before writing");
    }
  }

  private static String hash(byte[] bytes) {
    return bytes == null ? ProjectScope.MISSING : ProjectScope.sha256(bytes);
  }

  private static final class Text {
    final boolean bom;
    final boolean crlf;
    final String value;

    Text(String value, boolean bom) {
      this.value = value;
      this.bom = bom;
      this.crlf = value.contains("\r\n") && !value.replace("\r\n", "").contains("\n");
    }

    String adapt(String text) {
      return this.crlf ? text.replace("\r\n", "\n").replace("\n", "\r\n") : text;
    }

    byte[] bytes(String text) {
      byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
      if (!this.bom) {
        return utf8;
      }
      byte[] bytes = new byte[utf8.length + 3];
      bytes[0] = -17;
      bytes[1] = -69;
      bytes[2] = -65;
      System.arraycopy(utf8, 0, bytes, 3, utf8.length);
      return bytes;
    }
  }

  private static Text decode(byte[] bytes) throws IOException {
    if (bytes == null) return new Text("", false);
    boolean bom =
        bytes.length >= 3
            && bytes[0] == (byte) 0xef
            && bytes[1] == (byte) 0xbb
            && bytes[2] == (byte) 0xbf;
    for (byte b : bytes) {
      if (b == 0) throw new IOException("Binary files are not supported by text mutation tools");
    }
    try {
      int offset = bom ? 3 : 0;
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
              .toString();
      return new Text(value, bom);
    } catch (CharacterCodingException binary) {
      throw new IOException("File is not valid UTF-8 text", binary);
    }
  }

  private static byte[] encode(String text, byte[] original) throws IOException {
    if (text == null || text.indexOf(0) >= 0) {
      throw new IOException("Valid text content is required");
    }
    Text format = decode(original);
    if (text.startsWith("\ufeff")) {
      if (original == null) {
        format = new Text("", true);
      }
      text = text.substring(1);
    }
    return format.bytes(format.adapt(text));
  }

  private static String diff(String path, byte[] before, byte[] after) throws IOException {
    if (Arrays.equals(before, after)) return "";
    Text oldText = decode(before);
    Text newText = decode(after);
    String oldValue = (oldText.bom ? "\ufeff" : "") + oldText.value;
    String newValue = (newText.bom ? "\ufeff" : "") + newText.value;
    List<String> oldLines = diffLines(oldValue);
    List<String> newLines = diffLines(newValue);
    StringBuilder output =
        new StringBuilder("--- ")
            .append(before == null ? "/dev/null" : "a/" + path)
            .append("\n+++ ")
            .append(after == null ? "/dev/null" : "b/" + path)
            .append('\n');
    output
        .append("@@ -")
        .append(oldLines.isEmpty() ? 0 : 1)
        .append(',')
        .append(oldLines.size())
        .append(" +")
        .append(newLines.isEmpty() ? 0 : 1)
        .append(',')
        .append(newLines.size())
        .append(" @@\n");
    appendDiffLines(output, oldLines, oldValue, '-');
    appendDiffLines(output, newLines, newValue, '+');
    return output.toString();
  }

  private static List<String> diffLines(String text) {
    if (text.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    if (text.endsWith("\n")) {
      lines.remove(lines.size() - 1);
    }
    return lines;
  }

  private static void appendDiffLines(
      StringBuilder output, List<String> lines, String text, char prefix) {
    for (String line : lines) {
      output.append(prefix).append(line).append('\n');
    }
    if (lines.isEmpty() || text.endsWith("\n")) {
      return;
    }
    output.append("\\ No newline at end of file\n");
  }
}
