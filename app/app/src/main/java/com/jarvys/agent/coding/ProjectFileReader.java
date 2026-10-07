package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import java.io.IOException;

public final class ProjectFileReader {
  private final ReadObserver observer;
  private final ProjectScope scope;

  interface ReadObserver {
    void afterInitialHash() throws IOException;

    void beforeFinalHash() throws IOException;
  }

  public ProjectFileReader(ProjectScope scope) {
    this(scope, null);
  }

  ProjectFileReader(ProjectScope scope, ReadObserver observer) {
    this.scope = scope;
    this.observer = observer;
  }

  public static final class Page {
    public final boolean complete;
    public final long firstByte;
    public final long firstLine;
    public final long nextByte;
    public final String nextCursor;
    public final long nextLine;
    public final String path;
    public final long scopeVersion;
    public final String sha256;
    public final String text;

    Page(
        String path,
        String text,
        String sha256,
        String nextCursor,
        long firstLine,
        long nextLine,
        long firstByte,
        long nextByte,
        long scopeVersion) {
      this.path = path;
      this.text = text;
      this.sha256 = sha256;
      this.nextCursor = nextCursor;
      this.firstLine = firstLine;
      this.nextLine = nextLine;
      this.firstByte = firstByte;
      this.nextByte = nextByte;
      this.scopeVersion = scopeVersion;
      this.complete = nextCursor == null;
    }
  }

  public Page read(
      String path,
      long startLine,
      Long endLine,
      String cursor,
      int maxChars,
      CancellationToken token)
      throws IOException {
    return read(path, startLine, endLine, 0L, cursor, maxChars, token);
  }

  public Page read(
      String path,
      long startLine,
      Long endLine,
      long characterOffset,
      String cursor,
      int maxChars,
      CancellationToken token)
      throws IOException {
    if (characterOffset < 0) throw new IllegalArgumentException("offset cannot be negative.");
    if (characterOffset > 0 && startLine != 1) {
      throw new IllegalArgumentException("Use either offset or start_line, not both.");
    }
    if (startLine < 1 || (endLine != null && endLine < startLine)) {
      throw new IllegalArgumentException("Invalid inclusive line range.");
    }
    if (maxChars < 2) {
      throw new IllegalArgumentException("Read budget must fit a Unicode code point or CRLF pair.");
    }
    token.throwIfCancelled();
    long guardedVersion = scope.version();
    scope.resolve(path);
    String hash = ProjectTextIO.hash(scope, path, token);
    if (observer != null) observer.afterInitialHash();
    String binding =
        scope.id() + "\nread\n" + path + "\n" + startLine + "\n" + endLine + "\n" + characterOffset;
    long offset = 0;
    long line = 1;
    boolean resuming = cursor != null && !cursor.isEmpty();
    if (resuming) {
      long[] position = ProjectTextIO.parseCursor(cursor, binding, hash);
      offset = position[0];
      line = position[1];
    }
    long firstByte = offset;
    long firstLine = line;
    StringBuilder text = new StringBuilder(Math.min(maxChars, 8192));
    boolean more = false;
    boolean started = false;
    long skippedCharacters = 0;
    try (ProjectTextIO.Stream input = new ProjectTextIO.Stream(scope, path, offset, token)) {
      ProjectTextIO.Unit unit;
      while ((unit = input.next()) != null) {
        if (line < startLine || (!resuming && skippedCharacters < characterOffset)) {
          offset = unit.end;
          skippedCharacters += unit.text.length();
          if (unit.newline) line++;
          continue;
        }
        if (endLine != null && line > endLine) break;
        if (!started) {
          firstByte = unit.start;
          firstLine = line;
          started = true;
        }
        if (text.length() + unit.text.length() > maxChars) {
          more = true;
          break;
        }
        text.append(unit.text);
        offset = unit.end;
        if (unit.newline) line++;
      }
    }
    if (!resuming && skippedCharacters < characterOffset) {
      throw new IllegalArgumentException("offset is beyond the end of the file.");
    }
    token.throwIfCancelled();
    if (observer != null) observer.beforeFinalHash();
    if (!hash.equals(ProjectTextIO.hash(scope, path, token))) {
      throw new IOException("File changed while reading; discard this page and restart.");
    }
    if (scope.version() != guardedVersion) {
      throw new IOException("Project changed while reading; discard this page and restart.");
    }
    if (!started) {
      firstByte = offset;
      firstLine = line;
    }
    String next = more ? ProjectTextIO.cursor(binding, hash, offset, line) : null;
    return new Page(
        path, text.toString(), hash, next, firstLine, line, firstByte, offset, guardedVersion);
  }
}
