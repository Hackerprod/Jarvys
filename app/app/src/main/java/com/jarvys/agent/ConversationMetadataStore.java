package com.jarvys.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Atomic conversation metadata recovered from the v28 APK. */
public final class ConversationMetadataStore {
  static final Object LOCK = new Object();
  private final String boundary;
  private final File directory;

  public static final class Snapshot {
    public final String title;
    public final boolean pinned;
    public final boolean archived;
    public final boolean deleted;
    public final boolean privateCode;

    Snapshot(JSONObject row) {
      title = row.optString("title", "").isEmpty() ? null : row.optString("title");
      pinned = row.optBoolean("pinned");
      archived = row.optBoolean("archived");
      deleted = row.optBoolean("deleted");
      privateCode = row.optBoolean("privateCode");
    }
  }

  ConversationMetadataStore(File filesDirectory) {
    directory = new File(new File(filesDirectory, "jarvys"), "conversations");
    try {
      boundary =
          new File(new File(filesDirectory.getCanonicalFile(), "jarvys"), "conversations")
              .getPath();
      verify(directory);
    } catch (Exception error) {
      throw new IllegalStateException("Unsafe conversation metadata directory", error);
    }
  }

  private void verify(File file) throws IOException {
    String expected =
        file.equals(directory) ? boundary : new File(boundary, file.getName()).getPath();
    if (!directory.getCanonicalPath().equals(boundary)
        || !file.getCanonicalPath().equals(expected)) {
      throw new IOException("Conversation metadata must not follow symbolic links");
    }
  }

  static void validateSessionId(String sessionId) {
    if (sessionId == null
        || !sessionId.matches("[A-Za-z0-9_.-]{1,100}")
        || sessionId.equals(".")
        || sessionId.equals("..")) {
      throw new IllegalArgumentException("Invalid conversation session ID");
    }
  }

  private File file(String sessionId) throws IOException {
    validateSessionId(sessionId);
    File target = new File(directory, sessionId + ".meta.json");
    verify(target);
    verify(new File(directory, target.getName() + ".bak"));
    verify(new File(directory, target.getName() + ".new"));
    return target;
  }

  private JSONObject readRow(String sessionId) {
    validateSessionId(sessionId);
    try {
      File target = file(sessionId);
      if (!target.exists()) return new JSONObject();
      try (FileInputStream input = new FileInputStream(target);
          ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
        JSONObject row = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        for (String flag : new String[] {"pinned", "archived", "deleted", "privateCode"}) {
          if (row.has(flag) && !(row.get(flag) instanceof Boolean)) {
            throw new IOException("Invalid chat settings flag");
          }
        }
        if (row.has("title") && !row.isNull("title") && !(row.get("title") instanceof String)) {
          throw new IOException("Invalid chat name");
        }
        return row;
      }
    } catch (Exception error) {
      throw new IllegalStateException("Could not read chat settings", error);
    }
  }

  public Snapshot read(String sessionId) {
    synchronized (LOCK) {
      return new Snapshot(readRow(sessionId));
    }
  }

  public static String normalizeTitle(String title) {
    if (title == null) throw new IllegalArgumentException("A chat name is required");
    StringBuilder result = new StringBuilder();
    boolean space = false;
    for (int index = 0; index < title.length(); ) {
      int point = title.codePointAt(index);
      index += Character.charCount(point);
      if (Character.isWhitespace(point)
          || Character.isSpaceChar(point)
          || Character.isISOControl(point)) {
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
    synchronized (LOCK) {
      JSONObject row = readRow(sessionId);
      if (row.optBoolean("deleted") && !"deleted".equals(key)) {
        throw new IllegalStateException("This chat has been deleted");
      }
      File pending = null;
      try {
        if ("deleted".equals(key)) row = new JSONObject();
        row.put(key, value);
        File target = file(sessionId);
        if (!directory.isDirectory() && !directory.mkdirs()) {
          throw new IOException("Could not create chat settings directory");
        }
        pending = new File(directory, target.getName() + ".new");
        verify(pending);
        try (FileOutputStream output = new FileOutputStream(pending)) {
          output.write(row.toString().getBytes(StandardCharsets.UTF_8));
          output.flush();
          output.getFD().sync();
        }
        verify(target);
        verify(pending);
        if (!pending.renameTo(target)) throw new IOException("Could not replace chat settings");
      } catch (Exception error) {
        throw new IllegalStateException("Could not save chat settings", error);
      } finally {
        if (pending != null) {
          try {
            verify(pending);
            if (pending.isFile()) pending.delete();
          } catch (IOException | RuntimeException ignored) {
            // Never follow an unsafe cleanup target after a failed write.
          }
        }
      }
    }
  }
}
