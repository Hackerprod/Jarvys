package com.jarvys.agent;

import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.ConversationMetadataValue;

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
    public final boolean privateCode;

    Snapshot(String title, boolean pinned, boolean archived, boolean deleted, boolean privateCode) {
      this.title = title;
      this.pinned = pinned;
      this.archived = archived;
      this.deleted = deleted;
      this.privateCode = privateCode;
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
    validateSessionId(sessionId);
    ConversationMetadataValue value = database().readConversationMetadataSync(sessionId);
    if (value == null) return new Snapshot(null, false, false, false, false);
    return new Snapshot(value.getTitle(), value.getPinned(), value.getArchived(), value.getDeleted(),
        value.getPrivateCode());
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
    validateSessionId(sessionId);
    ChatDatabaseBridge bridge = database();
    switch (key) {
      case "deleted":
        if (!Boolean.TRUE.equals(value)) throw new IllegalArgumentException("Conversation deletion is irreversible");
        ChatDatabaseBridge.requireSuccessSync(bridge.deleteConversationSync(sessionId), "Could not delete conversation");
        return;
      case "title":
        ChatDatabaseBridge.requireSuccessSync(
            bridge.renameConversationSync(sessionId, (String) value), "Could not rename conversation");
        return;
      case "pinned":
        ChatDatabaseBridge.requireSuccessSync(
            bridge.updateMetadataSync(sessionId, null, null, (Boolean) value, null, null), "Could not update pinned state");
        return;
      case "archived":
        ChatDatabaseBridge.requireSuccessSync(
            bridge.updateMetadataSync(sessionId, null, null, null, (Boolean) value), "Could not update archived state");
        return;
      case "privateCode":
        ChatDatabaseBridge.requireSuccessSync(
            bridge.updateMetadataSync(sessionId, null, null, null, null, Boolean.TRUE.equals(value)),
            "Could not update conversation privacy state");
        return;
      default:
        throw new IllegalArgumentException("Unsupported conversation metadata field: " + key);
    }
  }
}
