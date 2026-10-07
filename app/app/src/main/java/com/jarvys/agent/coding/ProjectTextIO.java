package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ProjectTextIO {
  private ProjectTextIO() {}

  static String hash(ProjectScope scope, String path, CancellationToken token) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = scope.openRead(path)) {
        Runnable unregister =
            token.registerCancelAction(
                () -> {
                  try {
                    input.close();
                  } catch (IOException ignored) {
                  }
                });
        try {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) >= 0) {
            token.throwIfCancelled();
            digest.update(buffer, 0, count);
          }
        } finally {
          unregister.run();
        }
      }
      token.throwIfCancelled();
      return hex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String signature(String value) {
    try {
      return hex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      out.append(Character.forDigit((value >>> 4) & 15, 16));
      out.append(Character.forDigit(value & 15, 16));
    }
    return out.toString();
  }

  static String cursor(String binding, String revision, long offset, long line) {
    return "1:" + signature(binding) + ":" + revision + ":" + offset + ":" + line;
  }

  static long[] parseCursor(String cursor, String binding, String revision) {
    String[] pieces = cursor.split(":", -1);
    if (pieces.length != 5
        || !pieces[0].equals("1")
        || !pieces[1].equals(signature(binding))
        || !pieces[2].equals(revision)) {
      throw new IllegalArgumentException("Stale or foreign cursor; restart this exact query.");
    }
    try {
      long offset = Long.parseLong(pieces[3]);
      long line = Long.parseLong(pieces[4]);
      if (offset < 0 || line < 1) {
        throw new NumberFormatException();
      }
      return new long[] {offset, line};
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid continuation cursor.");
    }
  }

  static final class Unit {
    final long end;
    final boolean newline;
    final long start;
    final String text;

    Unit(String text, long start, long end, boolean newline) {
      this.text = text;
      this.start = start;
      this.end = end;
      this.newline = newline;
    }
  }

  static final class Stream implements Closeable {
    private final BufferedInputStream in;
    private long offset;
    private int pending = -2;
    private long pendingStart;
    private final CancellationToken token;
    private final Runnable unregister;

    Stream(ProjectScope scope, String path, long offset, CancellationToken token)
        throws IOException {
      this.token = token;
      token.throwIfCancelled();
      this.in = new BufferedInputStream(scope.openRead(path));
      this.unregister =
          token.registerCancelAction(
              () -> {
                try {
                  in.close();
                } catch (IOException ignored) {
                }
              });
      try {
        while (this.offset < offset) {
          token.throwIfCancelled();
          long skipped = in.skip(offset - this.offset);
          if (skipped == 0) {
            if (in.read() < 0) throw new IllegalArgumentException("Cursor lies beyond the file.");
            skipped = 1;
          }
          this.offset += skipped;
        }
      } catch (IOException | RuntimeException failure) {
        close();
        throw failure;
      }
    }

    Unit next() throws IOException {
      int cp;
      this.token.throwIfCancelled();
      long start = this.pending == -2 ? this.offset : this.pendingStart;
      if (this.pending != -2) {
        cp = this.pending;
        this.pending = -2;
      } else {
        cp = codepoint();
      }
      if (cp < 0) {
        return null;
      }
      if (cp == 13) {
        long afterCr = this.offset;
        int following = codepoint();
        if (following == 10) {
          return new Unit("\r\n", start, this.offset, true);
        }
        this.pending = following;
        this.pendingStart = afterCr;
        return new Unit("\r", start, afterCr, true);
      }
      return new Unit(new String(Character.toChars(cp)), start, this.offset, cp == 10);
    }

    private int nextByte() throws IOException {
      this.token.throwIfCancelled();
      int value = this.in.read();
      if (value >= 0) {
        this.offset++;
      }
      return value;
    }

    private int codepoint() throws IOException {
      int count;
      int value;
      int minimum;
      int first = nextByte();
      if (first < 0) {
        return -1;
      }
      if (first == 0) {
        throw new IOException("Binary file: NUL bytes are not supported by text tools.");
      }
      if (first < 128) {
        return first;
      }
      if (first >= 194 && first <= 223) {
        count = 1;
        value = first & 31;
        minimum = 128;
      } else if (first >= 224 && first <= 239) {
        count = 2;
        value = first & 15;
        minimum = 2048;
      } else {
        if (first < 240 || first > 244) {
          throw new IOException("Binary or invalid UTF-8 file; only UTF-8 text is supported.");
        }
        count = 3;
        value = first & 7;
        minimum = 65536;
      }
      for (int i = 0; i < count; i++) {
        int next = nextByte();
        if (next < 0 || (next & 192) != 128) {
          throw new IOException("Binary or invalid UTF-8 file; incomplete UTF-8 sequence.");
        }
        value = (value << 6) | (next & 63);
      }
      if (value < minimum || value > 1114111 || (value >= 55296 && value <= 57343)) {
        throw new IOException("Binary or invalid UTF-8 file; invalid code point.");
      }
      return value;
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() throws IOException {
      this.unregister.run();
      this.in.close();
    }
  }
}
