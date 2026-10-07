package com.jarvys.agent.coding;

import com.jarvys.agent.CancellationToken;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProjectSearch {
  private static final List<String> ARTIFACT_DIRECTORIES =
      Arrays.asList(
          ".git",
          ".gradle",
          ".idea",
          "build",
          "dist",
          "target",
          "node_modules",
          ".cache",
          "__pycache__");
  private static final Pattern ARTIFACT_FILES =
      Pattern.compile(".*\\.(?:class|jar|apk|aab|dex|pyc|o|obj|so|dylib|dll)$");
  private final ProjectScope scope;

  interface Visitor {
    void accept(String str, boolean z) throws IOException;
  }

  public ProjectSearch(ProjectScope scope) {
    this.scope = scope;
  }

  public static final class Page {
    public final boolean complete;
    public final List<String> entries;
    public final String nextCursor;
    public final long scopeVersion;
    public final int skippedBinaryFiles;

    Page(List<String> entries, String nextCursor, long version, int binary) {
      this.entries = Collections.unmodifiableList(entries);
      this.nextCursor = nextCursor;
      this.scopeVersion = version;
      this.complete = nextCursor == null;
      this.skippedBinaryFiles = binary;
    }
  }

  public Page list(
      String path, boolean includeIgnored, String cursor, int maxChars, CancellationToken token)
      throws IOException {
    return search("list", path, "", false, includeIgnored, cursor, maxChars, token);
  }

  public Page glob(
      String path,
      String glob,
      boolean includeIgnored,
      String cursor,
      int maxChars,
      CancellationToken token)
      throws IOException {
    return search("glob", path, glob, false, includeIgnored, cursor, maxChars, token);
  }

  public Page grep(
      String path,
      String query,
      boolean regex,
      boolean includeIgnored,
      String cursor,
      int maxChars,
      CancellationToken token)
      throws IOException {
    if (query == null || query.isEmpty()) {
      throw new IllegalArgumentException("query must not be empty.");
    }
    return search("grep", path, query, regex, includeIgnored, cursor, maxChars, token);
  }

  private Page search(
      String kind,
      String path,
      String query,
      boolean regex,
      boolean includeIgnored,
      String cursor,
      int maxChars,
      CancellationToken token)
      throws IOException {
    if (maxChars < 1) throw new IllegalArgumentException("Response budget must be positive.");
    token.throwIfCancelled();
    String root = scope.normalizePath(path);
    if (root.equals(".")) root = "";
    long version = scope.version();
    String binding =
        ProjectTextIO.signature(
            scope.id()
                + "\n"
                + kind
                + "\n"
                + root
                + "\n"
                + query
                + "\n"
                + regex
                + "\n"
                + includeIgnored);
    Key after = parseCursor(cursor, binding, version);
    Collector collector = new Collector(maxChars, after);
    Pattern matcher =
        "grep".equals(kind)
            ? Pattern.compile(regex ? query : Pattern.quote(query))
            : "glob".equals(kind) ? globPattern(query) : null;
    int[] binary = {0};
    List<IgnoreRule> ancestors = new ArrayList<>();
    if (!includeIgnored) loadAncestors(root, ancestors, token);
    Visitor visit =
        (relative, directory) ->
            visitResult(
                kind, collector, after, matcher, token, maxChars, binary, relative, directory);
    if (scope.resolve(root).isDirectory()) {
      walk(root, ancestors, includeIgnored, !"list".equals(kind), visit, token);
    } else if (includeIgnored || !ignored(root, false, ancestors)) {
      visit.accept(root, false);
    }
    token.throwIfCancelled();
    if (scope.version() != version) {
      throw new IOException("Project changed while searching; restart the query.");
    }
    List<String> results = new ArrayList<>(collector.items.values());
    String next =
        collector.more && !collector.items.isEmpty()
            ? encodeCursor(binding, version, collector.items.lastKey())
            : null;
    return new Page(results, next, version, binary[0]);
  }

  private void visitResult(
      String kind,
      Collector collector,
      Key after,
      Pattern matcher,
      CancellationToken token,
      int maxChars,
      int[] binary,
      String relative,
      boolean directory)
      throws IOException {
    if ("list".equals(kind)) {
      collector.add(new Key(relative, 0L), relative + (directory ? "/" : ""));
      return;
    }
    if (directory) {
      return;
    }
    if (after == null || relative.compareTo(after.path) >= 0) {
      if (!"glob".equals(kind)) {
        try {
          String revision = ProjectTextIO.hash(this.scope, relative, token);
          Collector fileResults = new Collector(maxChars, after);
          grepFile(relative, matcher, fileResults, maxChars, token);
          if (!revision.equals(ProjectTextIO.hash(this.scope, relative, token))) {
            throw new IOException("File changed while searching; restart the query.");
          }
          for (Map.Entry<Key, String> match : fileResults.items.entrySet()) {
            collector.add(match.getKey(), match.getValue());
          }
          if (fileResults.firstExcluded != null) collector.excludeFrom(fileResults.firstExcluded);
          collector.more |= fileResults.more;
          return;
        } catch (InvalidTextFile e) {
          binary[0] = binary[0] + 1;
          return;
        }
      }
      if (matcher.matcher(relative).matches()) {
        collector.add(new Key(relative, 0L), relative);
      }
    }
  }

  private void walk(
      String directory,
      List<IgnoreRule> parentRules,
      boolean includeIgnored,
      boolean recursive,
      Visitor visitor,
      CancellationToken token)
      throws IOException {
    token.throwIfCancelled();
    List<IgnoreRule> rules = new ArrayList<>(parentRules);
    if (!includeIgnored) loadRules(directory, rules, token);
    File folder = scope.resolve(directory);
    File[] children = folder.listFiles();
    if (children == null) throw new IOException("Cannot list project directory: " + directory);
    for (File child : children) {
      token.throwIfCancelled();
      String relative = directory.isEmpty() ? child.getName() : directory + "/" + child.getName();
      if (!child.getAbsoluteFile().equals(child.getCanonicalFile())
          || child.getName().startsWith(".jarvys-tmp-")) continue;
      File resolved = scope.resolve(relative);
      boolean isDirectory = resolved.isDirectory();
      if (includeIgnored || !ignored(relative, isDirectory, rules)) {
        visitor.accept(relative, isDirectory);
        if (recursive && isDirectory) walk(relative, rules, includeIgnored, true, visitor, token);
      }
    }
    scope.resolve(directory);
  }

  private void loadAncestors(String root, List<IgnoreRule> rules, CancellationToken token)
      throws IOException {
    if (root.isEmpty()) {
      return;
    }
    loadRules("", rules, token);
    int slash = root.indexOf(47);
    while (slash >= 0) {
      loadRules(root.substring(0, slash), rules, token);
      slash = root.indexOf(47, slash + 1);
    }
  }

  private void loadRules(String directory, List<IgnoreRule> rules, CancellationToken token)
      throws IOException {
    String path = directory.isEmpty() ? ".gitignore" : directory + "/.gitignore";
    if (!scope.resolve(path).isFile()) return;
    try (BufferedReader input =
        new BufferedReader(
            new InputStreamReader(
                scope.openRead(path),
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
      Runnable unregister =
          token.registerCancelAction(
              () -> {
                try {
                  input.close();
                } catch (IOException ignored) {
                }
              });
      try {
        String line;
        while ((line = input.readLine()) != null) {
          token.throwIfCancelled();
          if (line.isEmpty() || line.charAt(0) == '#') continue;
          boolean negate = line.charAt(0) == '!';
          if (negate) line = line.substring(1);
          if (line.startsWith("\\#") || line.startsWith("\\!")) line = line.substring(1);
          line = trimUnescapedSpaces(line);
          if (line.isEmpty()) continue;
          boolean directoryOnly = line.endsWith("/");
          if (directoryOnly) line = line.substring(0, line.length() - 1);
          boolean anchored = line.startsWith("/") || line.contains("/");
          if (line.startsWith("/")) line = line.substring(1);
          rules.add(new IgnoreRule(directory, globPattern(line), anchored, directoryOnly, negate));
        }
      } finally {
        unregister.run();
      }
    }
  }

  private static String trimUnescapedSpaces(String text) {
    int end = text.length();
    while (end > 0 && text.charAt(end - 1) == ' ' && (end < 2 || text.charAt(end - 2) != '\\')) {
      end--;
    }
    return text.substring(0, end).replace("\\ ", " ");
  }

  private static final class IgnoreRule {
    final boolean anchored;
    final String directory;
    final boolean directoryOnly;
    final boolean negate;
    final Pattern pattern;

    IgnoreRule(
        String directory,
        Pattern pattern,
        boolean anchored,
        boolean directoryOnly,
        boolean negate) {
      this.directory = directory;
      this.pattern = pattern;
      this.anchored = anchored;
      this.directoryOnly = directoryOnly;
      this.negate = negate;
    }

    boolean matches(String path, boolean isDirectory) {
      if (!this.directory.isEmpty()) {
        if (!path.startsWith(this.directory + "/")) {
          return false;
        }
        path = path.substring(this.directory.length() + 1);
      }
      String[] pieces = path.split("/");
      String prefix = "";
      int i = 0;
      while (i < pieces.length) {
        prefix = prefix.isEmpty() ? pieces[i] : prefix + "/" + pieces[i];
        boolean directorySegment = i < pieces.length - 1 || isDirectory;
        if (!this.directoryOnly || directorySegment) {
          if (this.pattern.matcher(this.anchored ? prefix : pieces[i]).matches()) {
            return true;
          }
        }
        i++;
      }
      return false;
    }
  }

  private static boolean ignored(String path, boolean directory, List<IgnoreRule> rules) {
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length; i++) {
      if ((i < parts.length - 1 || directory) && ARTIFACT_DIRECTORIES.contains(parts[i])) {
        return true;
      }
    }
    if (!directory && ARTIFACT_FILES.matcher(path).matches()) {
      return true;
    }
    boolean ignored = false;
    for (IgnoreRule rule : rules) {
      if (rule.matches(path, directory)) {
        ignored = !rule.negate;
      }
    }
    return ignored;
  }

  static Pattern globPattern(String glob) {
    if (glob == null || glob.isEmpty()) {
      throw new IllegalArgumentException("glob must not be empty.");
    }
    StringBuilder regex = new StringBuilder("^");
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          i++;
          if (i + 1 >= glob.length() || glob.charAt(i + 1) != '/') {
            regex.append(".*");
          } else {
            regex.append("(?:.*/)?");
            i++;
          }
        } else {
          regex.append("[^/]*");
        }
      } else if (c == '?') {
        regex.append("[^/]");
      } else if (c == '[') {
        int end = glob.indexOf(93, i + 1);
        if (end < 0) {
          regex.append("\\[");
        } else {
          String range = glob.substring(i + 1, end);
          if (range.startsWith("!")) {
            range = "^" + range.substring(1);
          }
          regex.append('[').append(range.replace("\\", "\\\\")).append(']');
          i = end;
        }
      } else {
        if (c == '\\' && i + 1 < glob.length()) {
          i++;
          c = glob.charAt(i);
        }
        if (".(){}+$^|[]\\".indexOf(c) >= 0) {
          regex.append('\\');
        }
        regex.append(c);
      }
      i++;
    }
    return Pattern.compile(regex.append('$').toString());
  }

  static final class Key implements Comparable<Key> {
    final long line;
    final String path;

    Key(String path, long line) {
      this.path = path;
      this.line = line;
    }

    @Override // java.lang.Comparable
    public int compareTo(Key other) {
      int compare = this.path.compareTo(other.path);
      return compare == 0 ? Long.compare(this.line, other.line) : compare;
    }
  }

  static final class Collector {
    final Key after;
    final int budget;
    final TreeMap<Key, String> items = new TreeMap<>();
    boolean more;
    int used;
    Key firstExcluded;

    Collector(int budget, Key after) {
      this.budget = budget;
      this.after = after;
    }

    void add(Key key, String text) {
      if (after != null && key.compareTo(after) <= 0) return;
      int cost = text.length() + 1;
      if (cost > budget) {
        throw new IllegalArgumentException(
            "Response budget cannot fit a result path; use a larger max_chars budget.");
      }
      // A cursor page must be a contiguous sorted prefix. Reusing space after an
      // eviction would let a later short result skip the evicted key forever.
      if (firstExcluded != null && key.compareTo(firstExcluded) >= 0) return;
      String previous = items.put(key, text);
      if (previous != null) used -= previous.length() + 1;
      used += cost;
      while (used > budget) excludeFrom(items.lastKey());
    }

    void excludeFrom(Key key) {
      more = true;
      if (firstExcluded == null || key.compareTo(firstExcluded) < 0) firstExcluded = key;
      while (!items.isEmpty() && items.lastKey().compareTo(firstExcluded) >= 0) {
        Map.Entry<Key, String> removed = items.pollLastEntry();
        used -= removed.getValue().length() + 1;
      }
    }
  }

  private static String encodeCursor(String binding, long version, Key key) {
    return "2:"
        + binding
        + ":"
        + version
        + ":"
        + ProjectTextIO.hex(key.path.getBytes(StandardCharsets.UTF_8))
        + ":"
        + key.line;
  }

  private static Key parseCursor(String cursor, String binding, long version) {
    if (cursor == null || cursor.isEmpty()) {
      return null;
    }
    String[] parts = cursor.split(":", -1);
    try {
      if (parts.length != 5
          || !parts[0].equals("2")
          || !parts[1].equals(binding)
          || Long.parseLong(parts[2]) != version
          || parts[3].length() % 2 != 0) {
        throw new IllegalArgumentException();
      }
      byte[] bytes = new byte[parts[3].length() / 2];
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = (byte) Integer.parseInt(parts[3].substring(i * 2, (i * 2) + 2), 16);
      }
      long line = Long.parseLong(parts[4]);
      if (line < 0) {
        throw new IllegalArgumentException();
      }
      return new Key(new String(bytes, StandardCharsets.UTF_8), line);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "Stale or foreign search cursor; restart this exact query.");
    }
  }

  private static final class InvalidTextFile extends IOException {
    InvalidTextFile(IOException cause) {
      super(cause);
    }
  }

  private void grepFile(
      String path, Pattern pattern, Collector results, int budget, CancellationToken token)
      throws IOException {
    StringBuilder prefix = new StringBuilder(Math.min(budget, 8192));
    try (ProjectTextIO.Stream input = new ProjectTextIO.Stream(scope, path, 0, token)) {
      int chars = 0;
      boolean longLine = false;
      long lineStart = 0;
      long line = 1;
      ProjectTextIO.Unit unit;
      while ((unit = input.next()) != null) {
        if (unit.newline) {
          matchLine(
              path,
              line,
              lineStart,
              chars,
              prefix.toString(),
              longLine,
              pattern,
              results,
              budget,
              token);
          line++;
          lineStart = unit.end;
          chars = 0;
          prefix.setLength(0);
          longLine = false;
        } else {
          if (chars > Integer.MAX_VALUE - unit.text.length()) {
            throw new IOException(
                "A line exceeds the platform regex character range; use paginated read.");
          }
          chars += unit.text.length();
          if (longLine || prefix.length() + unit.text.length() > budget) longLine = true;
          else prefix.append(unit.text);
        }
      }
      if (chars > 0)
        matchLine(
            path,
            line,
            lineStart,
            chars,
            prefix.toString(),
            longLine,
            pattern,
            results,
            budget,
            token);
    } catch (IOException failure) {
      token.throwIfCancelled();
      if (failure.getMessage() != null
          && (failure.getMessage().startsWith("Binary")
              || failure.getMessage().contains("UTF-8"))) {
        throw new InvalidTextFile(failure);
      }
      throw failure;
    }
  }

  private void matchLine(
      String path,
      long line,
      long byteStart,
      int length,
      String prefix,
      boolean longLine,
      Pattern pattern,
      Collector results,
      int budget,
      CancellationToken token)
      throws IOException {
    if (results.after != null && new Key(path, line).compareTo(results.after) <= 0) return;
    try (LineSequence sequence = new LineSequence(scope, path, byteStart, length, prefix, token)) {
      Matcher matcher = pattern.matcher(sequence);
      boolean matches;
      try {
        matches = matcher.find();
      } catch (LineReadFailure failure) {
        throw failure.error;
      } catch (StackOverflowError failure) {
        throw new IOException(
            "Regex exhausted the platform stack; simplify the expression or use literal search.");
      }
      if (!matches) return;
      String label = path + ":" + line + ":" + (matcher.start() + 1) + ": ";
      int available = budget - label.length() - 1;
      if (available < 0)
        throw new IllegalArgumentException("Response budget cannot fit a match path.");
      String excerpt = prefix;
      if (longLine || excerpt.length() > available) {
        String suffix = " [excerpt; use read for the full line]";
        int snippetBudget = Math.max(0, available - suffix.length());
        int begin = Math.min(matcher.start(), length);
        int end = Math.min(length, begin + snippetBudget);
        StringBuilder snippet = new StringBuilder(snippetBudget);
        try {
          if (begin < end && begin > 0 && Character.isLowSurrogate(sequence.charAt(begin))) begin++;
          if (end > begin && end < length && Character.isHighSurrogate(sequence.charAt(end - 1)))
            end--;
          for (int i = begin; i < end; i++) snippet.append(sequence.charAt(i));
        } catch (LineReadFailure failure) {
          throw failure.error;
        }
        excerpt = snippet + (suffix.length() <= available ? suffix : "");
      }
      results.add(new Key(path, line), label + excerpt);
    }
  }

  private static final class LineReadFailure extends RuntimeException {
    final IOException error;

    LineReadFailure(IOException error) {
      super(error);
      this.error = error;
    }
  }

  private static final class LineSequence implements CharSequence, Closeable {
    final int length;
    final String path;
    String pending = "";
    final String prefix;
    final ProjectScope scope;
    final long start;
    ProjectTextIO.Stream stream;
    int streamPosition;
    final CancellationToken token;

    LineSequence(
        ProjectScope scope,
        String path,
        long start,
        int length,
        String prefix,
        CancellationToken token) {
      this.scope = scope;
      this.path = path;
      this.start = start;
      this.length = length;
      this.prefix = prefix;
      this.token = token;
    }

    @Override // java.lang.CharSequence
    public int length() {
      return this.length;
    }

    @Override // java.lang.CharSequence
    public char charAt(int index) {
      this.token.throwIfCancelled();
      if (index < 0 || index >= this.length) {
        throw new IndexOutOfBoundsException();
      }
      if (index < this.prefix.length()) {
        return this.prefix.charAt(index);
      }
      try {
        if (this.stream == null || index < this.streamPosition) {
          if (this.stream != null) {
            this.stream.close();
          }
          this.stream = new ProjectTextIO.Stream(this.scope, this.path, this.start, this.token);
          this.streamPosition = 0;
          this.pending = "";
        }
        while (true) {
          if (this.pending.isEmpty()) {
            ProjectTextIO.Unit unit = this.stream.next();
            if (unit == null || unit.newline) {
              break;
            }
            this.pending = unit.text;
          }
          if (index < this.streamPosition + this.pending.length()) {
            return this.pending.charAt(index - this.streamPosition);
          }
          this.streamPosition += this.pending.length();
          this.pending = "";
        }
        throw new IOException("File changed while searching; restart the query.");
      } catch (IOException failure) {
        throw new LineReadFailure(failure);
      }
    }

    @Override // java.lang.CharSequence
    public CharSequence subSequence(final int start, final int end) {
      if (start < 0 || end < start || end > this.length) {
        throw new IndexOutOfBoundsException();
      }
      return new CharSequence() {
        @Override // java.lang.CharSequence
        public int length() {
          return end - start;
        }

        @Override // java.lang.CharSequence
        public char charAt(int index) {
          if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException();
          }
          return LineSequence.this.charAt(start + index);
        }

        @Override // java.lang.CharSequence
        public CharSequence subSequence(int from, int to) {
          if (from < 0 || to < from || to > length()) {
            throw new IndexOutOfBoundsException();
          }
          return LineSequence.this.subSequence(start + from, start + to);
        }
      };
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() throws IOException {
      if (this.stream != null) {
        this.stream.close();
      }
    }
  }
}
