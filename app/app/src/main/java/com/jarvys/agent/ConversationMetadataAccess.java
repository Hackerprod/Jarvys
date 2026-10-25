package com.jarvys.agent;

import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.ConversationMetadataValue;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** DB-backed conversation metadata access and identity policy. */
public final class ConversationMetadataAccess {
  private static final java.util.concurrent.ConcurrentMap<String, Object> SESSION_LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.io.File filesDirectory;

  public static final class Snapshot {
    public final String title;
    public final boolean pinned;
    public final boolean archived;
    public final boolean deleted;

    Snapshot(String title, boolean pinned, boolean archived, boolean deleted) {
      this.title = title;
      this.pinned = pinned;
      this.archived = archived;
      this.deleted = deleted;
    }
  }

  ConversationMetadataAccess(java.io.File filesDirectory) {
    this.filesDirectory = filesDirectory.getAbsoluteFile();
  }

  static void validateSessionId(String sessionId) {
    if (sessionId == null
        || !sessionId.matches("[A-Za-z0-9_.-]{1,100}")
        || sessionId.equals(".")
        || sessionId.equals("..")) {
      throw new IllegalArgumentException("Invalid conversation session ID");
    }
  }

  /** Shared filesystem coordination for one chat; unrelated conversations never serialize each other. */
  static Object lockForSession(String sessionId) {
    validateSessionId(sessionId);
    return SESSION_LOCKS.computeIfAbsent(sessionId, ignored -> new Object());
  }

  private ChatDatabaseBridge database() {
    ChatDatabaseBridge bridge = ChatDatabaseBridge.findInstanceForFilesDirectory(filesDirectory);
    if (bridge == null) {
      throw new IllegalStateException("The chat database bridge has not been registered for this files directory");
    }
    return bridge;
  }

  public Snapshot read(String sessionId) {
    return read(sessionId, null);
  }

  Snapshot read(String sessionId, Consumer<Throwable> databaseFailureReporter) {
    validateSessionId(sessionId);
    ConversationMetadataValue value = databaseCall(databaseFailureReporter,
        () -> database().readConversationMetadataSync(sessionId));
    if (value == null) return new Snapshot(null, false, false, false);
    return new Snapshot(value.getTitle(), value.getPinned(), value.getArchived(), value.getDeleted());
  }

  public static String normalizeTitle(String title) {
    if (title == null) throw new IllegalArgumentException("A chat name is required");
    StringBuilder result = new StringBuilder();
    boolean space = false;
    for (int index = 0; index < title.length(); ) {
      int point = title.codePointAt(index);
      index += Character.charCount(point);
      if (Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.isISOControl(point)) {
        space = result.length() > 0;
      } else {
        if (space) result.append(' ');
        result.appendCodePoint(point);
        space = false;
      }
    }
    if (result.length() == 0) throw new IllegalArgumentException("A chat name is required");
    return result.toString();
  }

  void update(String sessionId, String key, Object value) {
    update(sessionId, key, value, null);
  }

  void update(String sessionId, String key, Object value, Consumer<Throwable> databaseFailureReporter) {
    validateSessionId(sessionId);
    if ("deleted".equals(key) && com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION.equals(sessionId))
      throw new IllegalArgumentException("Central cannot be deleted");
    ChatDatabaseBridge bridge = databaseCall(databaseFailureReporter, this::database);
    switch (key) {
      case "deleted":
        if (!Boolean.TRUE.equals(value)) throw new IllegalArgumentException("Conversation deletion is irreversible");
        ChatDatabaseBridge.requireSuccessSync(databaseCall(databaseFailureReporter,
            () -> bridge.deleteConversationSync(sessionId)), "Could not delete conversation");
        return;
      case "title":
        ChatDatabaseBridge.requireSuccessSync(
            databaseCall(databaseFailureReporter, () -> bridge.renameConversationSync(sessionId, (String) value)),
            "Could not rename conversation");
        return;
      case "pinned":
        ChatDatabaseBridge.requireSuccessSync(
            databaseCall(databaseFailureReporter,
                () -> bridge.updateMetadataSync(sessionId, null, null, (Boolean) value, null)),
            "Could not update pinned state");
        return;
      case "archived":
        ChatDatabaseBridge.requireSuccessSync(
            databaseCall(databaseFailureReporter,
                () -> bridge.updateMetadataSync(sessionId, null, null, null, (Boolean) value)),
            "Could not update archived state");
        return;
      default:
        throw new IllegalArgumentException("Unsupported conversation metadata field: " + key);
    }
  }

  private static <T> T databaseCall(Consumer<Throwable> failureReporter, Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RuntimeException | Error failure) {
      if (failureReporter != null && !(failure instanceof CancellationException)) {
        try { failureReporter.accept(failure); } catch (Throwable ignored) { }
      }
      throw failure;
    }
  }
}
