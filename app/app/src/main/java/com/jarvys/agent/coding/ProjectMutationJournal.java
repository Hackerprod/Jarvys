package com.jarvys.agent.coding;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class ProjectMutationJournal {
  private static final String DIRECTORY = "directory";
  private final ProjectScope scope;

  public enum RecoveryStatus {
    NOT_APPLIED,
    APPLIED,
    PARTIAL,
    UNCERTAIN
  }

  public static final class PathObservation {
    public final String action;
    public final String afterSha;
    public final boolean auxiliary;
    public final String beforeSha;
    public final boolean changedSinceReceipt;
    public final String currentSha;
    public final String path;
    public final RecoveryStatus status;

    PathObservation(
        String path,
        String action,
        String beforeSha,
        String afterSha,
        String currentSha,
        RecoveryStatus status,
        boolean auxiliary,
        boolean changedSinceReceipt) {
      this.path = path;
      this.action = action;
      this.beforeSha = beforeSha;
      this.afterSha = afterSha;
      this.currentSha = currentSha;
      this.status = status;
      this.auxiliary = auxiliary;
      this.changedSinceReceipt = changedSinceReceipt;
    }
  }

  public static final class RecoveryEntry {
    public final long createdAt;
    public final String id;
    public final String ownerDigest;
    public final List<PathObservation> paths;
    public final RecoveryStatus status;
    public final String terminalStatus;

    RecoveryEntry(
        String id,
        String ownerDigest,
        String terminalStatus,
        long createdAt,
        RecoveryStatus status,
        List<PathObservation> paths) {
      this.id = id;
      this.ownerDigest = ownerDigest;
      this.terminalStatus = terminalStatus;
      this.createdAt = createdAt;
      this.status = status;
      this.paths = Collections.unmodifiableList(paths);
    }
  }

  public static final class RecoveryReport {
    public final List<RecoveryEntry> entries;
    public final int issueCount;

    RecoveryReport(List<RecoveryEntry> entries, int issueCount) {
      this.entries = Collections.unmodifiableList(entries);
      this.issueCount = issueCount;
    }

    public String summary() {
      int notApplied = 0;
      int applied = 0;
      int partial = 0;
      int uncertain = 0;
      int changed = 0;
      for (RecoveryEntry entry : this.entries) {
        switch (entry.status) {
          case NOT_APPLIED:
            notApplied++;
            break;
          case APPLIED:
            applied++;
            break;
          case PARTIAL:
            partial++;
            break;
          case UNCERTAIN:
            uncertain++;
            break;
        }
        for (PathObservation path : entry.paths) {
          if (path.changedSinceReceipt) {
            changed++;
          }
        }
      }
      return "Project mutation recovery (read-only): "
          + applied
          + " applied, "
          + notApplied
          + " not applied, "
          + partial
          + " partial, "
          + uncertain
          + " uncertain; "
          + this.issueCount
          + " unreadable/unsupported records; "
          + changed
          + " paths changed since a recorded effect. Current hashes are observations, not proof of"
          + " which writer acted. No operation was replayed. Scope leases and versions are"
          + " process-local.";
    }
  }

  static final class Change {
    final String action;
    final String after;
    final boolean auxiliary;
    final String before;
    final String path;

    Change(String path, String action, String before, String after, boolean auxiliary) {
      this.path = path;
      this.action = action;
      this.before = before;
      this.after = after;
      this.auxiliary = auxiliary;
    }

    static Change directory(String path) {
      return new Change(
          path, "CREATE_DIRECTORY", ProjectScope.MISSING, ProjectMutationJournal.DIRECTORY, true);
    }

    static Change staging(String path, String hash) {
      return new Change(path, "STAGING_FILE", ProjectScope.MISSING, hash, true);
    }
  }

  static final class Transaction {
    private final File file;
    final String id;
    private final ProjectMutationJournal journal;
    private final JSONObject record;

    Transaction(ProjectMutationJournal journal, File file, JSONObject record) throws JSONException {
      this.journal = journal;
      this.file = file;
      this.record = record;
      this.id = record.getString("id");
    }

    void applied(String path) throws IOException {
      outcome(path, "APPLIED");
    }

    void cleaned(String path) throws IOException {
      outcome(path, "CLEANED");
    }

    boolean contains(String path) throws IOException {
      try {
        JSONArray changes = this.record.getJSONArray("changes");
        for (int i = 0; i < changes.length(); i++) {
          if (path.equals(changes.getJSONObject(i).getString("path"))) {
            return true;
          }
        }
        return false;
      } catch (JSONException invalid) {
        throw new IOException("Invalid mutation intent", invalid);
      }
    }

    private void outcome(String path, String state) throws IOException {
      try {
        JSONArray changes = this.record.getJSONArray("changes");
        for (int i = 0; i < changes.length(); i++) {
          JSONObject change = changes.getJSONObject(i);
          if (path.equals(change.getString("path"))) {
            change.put("outcome", state).put("observedAt", System.currentTimeMillis());
            persist();
            return;
          }
        }
        throw new IOException("Mutation path has no durable intent");
      } catch (JSONException invalid) {
        throw new IOException("Invalid mutation receipt", invalid);
      }
    }

    void finish(ProjectMutationService.Status status, int cleanupIssueCount) throws IOException {
      try {
        this.record
            .put("terminal", status.name())
            .put("finishedAt", System.currentTimeMillis())
            .put("cleanupIssueCount", cleanupIssueCount);
        persist();
      } catch (JSONException invalid) {
        throw new IOException("Invalid mutation terminal receipt", invalid);
      }
    }

    private void persist() throws IOException {
      this.journal.validate();
      ProjectJournalIO.write(this.file, this.record, false);
      this.journal.validate();
    }
  }

  public ProjectMutationJournal(ProjectScope scope) {
    this.scope = scope;
  }

  Transaction begin(String owner, List<Change> changes) throws IOException {
    this.scope.require(ProjectScope.Capability.WRITE);
    String identity = this.scope.durableIdentity();
    String id = UUID.randomUUID().toString();
    try {
      JSONArray effects = new JSONArray();
      for (Change change : changes) {
        effects.put(
            new JSONObject()
                .put("path", change.path)
                .put("action", change.action)
                .put("before", change.before)
                .put("after", change.after)
                .put("auxiliary", change.auxiliary)
                .put("outcome", "PENDING"));
      }
      JSONObject record =
          new JSONObject()
              .put("schema", 1)
              .put("id", id)
              .put("scope", identity)
              .put("ownerDigest", ProjectScope.sha256(owner.getBytes(StandardCharsets.UTF_8)))
              .put("createdAt", System.currentTimeMillis())
              .put("changes", effects);
      File file = new File(this.scope.mutationJournalDirectory(), id + ".json");
      ProjectJournalIO.write(file, record, true);
      validate();
      return new Transaction(this, file, record);
    } catch (JSONException invalid) {
      throw new IOException("Could not encode mutation intent", invalid);
    }
  }

  /** Observes durable intents and receipts without ever replaying a mutation. */
  public RecoveryReport recover() throws IOException {
    scope.require(ProjectScope.Capability.READ);
    String identity = scope.durableIdentity();
    File[] files = scope.mutationJournalDirectory().listFiles();
    if (files == null) {
      throw new IOException("Project mutation recovery directory cannot be read");
    }
    Arrays.sort(files, Comparator.comparing(File::getName));
    List<RecoveryEntry> entries = new ArrayList<>();
    int issues = 0;
    for (File file : files) {
      if (!file.getName().endsWith(".json")) {
        issues++;
        continue;
      }
      try {
        validate();
        JSONObject record = ProjectJournalIO.read(file);
        if (record.getInt("schema") != 1 || !identity.equals(record.getString("scope"))) {
          issues++;
          continue;
        }
        String id = record.getString("id");
        String owner = record.getString("ownerDigest");
        if (!id.matches("[0-9a-f-]{36}")
            || !file.getName().equals(id + ".json")
            || !owner.matches("[0-9a-f]{64}")) {
          issues++;
          continue;
        }
        String terminal = record.optString("terminal", "");
        if (!terminal.isEmpty()) {
          ProjectMutationService.Status.valueOf(terminal);
        }
        JSONArray changes = record.getJSONArray("changes");
        if (changes.length() == 0) {
          issues++;
          continue;
        }
        List<PathObservation> observations = new ArrayList<>();
        boolean applied = false;
        boolean notApplied = false;
        boolean uncertain = false;
        boolean residual = false;
        boolean stagingResidual = false;
        for (int i = 0; i < changes.length(); i++) {
          PathObservation observation = observe(changes.getJSONObject(i), !terminal.isEmpty());
          observations.add(observation);
          if (observation.auxiliary) {
            if ("STAGING_FILE".equals(observation.action)
                && !ProjectScope.MISSING.equals(observation.currentSha)) {
              stagingResidual = true;
            }
            if ("CREATE_DIRECTORY".equals(observation.action)
                && observation.status == RecoveryStatus.APPLIED) {
              residual = true;
            }
          } else {
            if (observation.status == RecoveryStatus.APPLIED) applied = true;
            if (observation.status == RecoveryStatus.NOT_APPLIED) notApplied = true;
          }
          if (observation.status == RecoveryStatus.UNCERTAIN) uncertain = true;
        }
        RecoveryStatus status;
        if (uncertain) status = RecoveryStatus.UNCERTAIN;
        else if (stagingResidual) status = RecoveryStatus.PARTIAL;
        else if (applied && !notApplied) status = RecoveryStatus.APPLIED;
        else if (applied || residual) status = RecoveryStatus.PARTIAL;
        else status = RecoveryStatus.NOT_APPLIED;
        if (record.optInt("cleanupIssueCount", 0) > 0 && status == RecoveryStatus.APPLIED) {
          status = RecoveryStatus.PARTIAL;
        }
        entries.add(
            new RecoveryEntry(
                id, owner, terminal, record.getLong("createdAt"), status, observations));
      } catch (IOException | JSONException | IllegalArgumentException invalid) {
        issues++;
      }
    }
    validate();
    entries.sort(
        (a, b) ->
            a.createdAt == b.createdAt
                ? a.id.compareTo(b.id)
                : Long.compare(a.createdAt, b.createdAt));
    return new RecoveryReport(entries, issues);
  }

  private PathObservation observe(JSONObject change, boolean terminal)
      throws JSONException, IOException {
    String path = change.getString("path");
    String action = change.getString("action");
    String before = change.getString("before");
    String after = change.getString("after");
    String outcome = change.getString("outcome");
    boolean auxiliary = change.getBoolean("auxiliary");
    if (!validHash(before)
        || !validHash(after)
        || (!"PENDING".equals(outcome)
            && !"APPLIED".equals(outcome)
            && !"CLEANED".equals(outcome))) {
      throw new IOException("Invalid mutation hashes or receipt");
    }
    String current;
    try {
      current = current(path, action);
    } catch (IOException unavailable) {
      current = "unavailable";
    }
    boolean acknowledged = "APPLIED".equals(outcome);
    boolean cleaned = "CLEANED".equals(outcome);
    boolean changed = false;
    RecoveryStatus state;
    if (cleaned) {
      state =
          ProjectScope.MISSING.equals(current)
              ? RecoveryStatus.NOT_APPLIED
              : RecoveryStatus.UNCERTAIN;
    } else if (acknowledged && terminal && !auxiliary) {
      state = RecoveryStatus.APPLIED;
      changed = !after.equals(current);
    } else if (after.equals(current) && (!before.equals(after) || acknowledged)) {
      state = RecoveryStatus.APPLIED;
    } else if (before.equals(current) && !before.equals(after) && !acknowledged) {
      state = RecoveryStatus.NOT_APPLIED;
    } else if (auxiliary && ProjectScope.MISSING.equals(current)) {
      state = RecoveryStatus.NOT_APPLIED;
    } else {
      state = RecoveryStatus.UNCERTAIN;
      changed = acknowledged && !after.equals(current);
    }
    return new PathObservation(path, action, before, after, current, state, auxiliary, changed);
  }

  private String current(String path, String action) throws IOException {
    if ("STAGING_FILE".equals(action)) {
      int slash = path.lastIndexOf('/');
      String name = path.substring(slash + 1);
      if (!name.matches("\\.jarvys-tmp-[0-9a-f-]{36}\\.part")) {
        throw new IOException("Invalid staging evidence path");
      }
      File parent = scope.resolve(slash >= 0 ? path.substring(0, slash) : ".");
      File staged = new File(parent, name);
      if (!ProjectFileIO.exists(staged)) return ProjectScope.MISSING;
      ProjectFileIO.Attributes before = ProjectFileIO.attributes(staged);
      if (!before.isRegularFile() || before.isSymbolicLink()) {
        throw new IOException("Staging evidence is no longer ordinary");
      }
      try (InputStream input = ProjectFileIO.openNoFollow(staged)) {
        String hash = ProjectScope.sha256(input);
        scope.validate();
        if (!ProjectScope.sameIdentity(before, ProjectFileIO.attributes(staged))) {
          throw new IOException("Staging evidence changed");
        }
        return hash;
      }
    }
    if (!scope.normalizePath(path).equals(path) || ".".equals(path)) {
      throw new IOException("Invalid mutation evidence path");
    }
    File file = scope.resolve(path);
    if ("CREATE_DIRECTORY".equals(action)) {
      return !ProjectFileIO.exists(file)
          ? ProjectScope.MISSING
          : ProjectFileIO.isDirectory(file) ? DIRECTORY : "not-directory";
    }
    if (!Arrays.asList("ADD", "WRITE", "EDIT", "DELETE", "MOVE_DESTINATION", "MOVE_SOURCE_DELETE")
        .contains(action)) {
      throw new IOException("Unknown mutation action");
    }
    return scope.revision(path);
  }

  private static boolean validHash(String hash) {
    return ProjectScope.MISSING.equals(hash)
        || DIRECTORY.equals(hash)
        || hash.matches("[0-9a-f]{64}");
  }

  private void validate() throws IOException {
    this.scope.durableIdentity();
  }
}
