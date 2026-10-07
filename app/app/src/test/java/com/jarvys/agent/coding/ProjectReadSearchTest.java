package com.jarvys.agent.coding;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ProjectReadSearchTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private ProjectScope scope() throws IOException {
    return new ProjectScopeStore(temporary.newFolder()).open("test");
  }

  private void write(ProjectScope scope, String path, String text) throws IOException {
    File file = scope.resolve(path);
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void readPagesPreserveUnicodeCrlfAndByteOffsets() throws Exception {
    ProjectScope scope = scope();
    write(scope, "file.txt", "A😀\r\nβ\nlast");
    ProjectFileReader reader = new ProjectFileReader(scope);
    StringBuilder text = new StringBuilder();
    String cursor = null;
    long offset = 0;
    do {
      ProjectFileReader.Page page =
          reader.read("file.txt", 1, null, cursor, 2, CancellationToken.uncancellable());
      assertEquals(offset, page.firstByte);
      assertTrue(page.text.length() <= 2);
      assertFalse(page.text.equals("\r"));
      text.append(page.text);
      offset = page.nextByte;
      cursor = page.nextCursor;
    } while (cursor != null);
    assertEquals("A😀\r\nβ\nlast", text.toString());
    assertEquals(text.toString().getBytes(StandardCharsets.UTF_8).length, offset);
  }

  @Test
  public void readSupportsInclusiveLinesAndInitialCharacterOffset() throws Exception {
    ProjectScope scope = scope();
    write(scope, "file.txt", "one\r\ntwo\nthree");
    ProjectFileReader reader = new ProjectFileReader(scope);
    assertEquals(
        "two\n", reader.read("file.txt", 2, 2L, null, 100, CancellationToken.uncancellable()).text);
    assertEquals(
        "two\nthree",
        reader.read("file.txt", 1, null, 5, null, 100, CancellationToken.uncancellable()).text);
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.read("file.txt", 1, null, 100, null, 100, CancellationToken.uncancellable()));
  }

  @Test
  public void readRejectsStaleAndForeignCursors() throws Exception {
    ProjectScope first = scope();
    ProjectScope second = scope();
    // Same conversation ID in different app anchors still has the same public ID, but data differs.
    write(first, "file.txt", "abcdef");
    write(second, "file.txt", "different");
    ProjectFileReader reader = new ProjectFileReader(first);
    String cursor =
        reader.read("file.txt", 1, null, null, 2, CancellationToken.uncancellable()).nextCursor;
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.read("file.txt", 2, null, cursor, 2, CancellationToken.uncancellable()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProjectFileReader(second)
                .read("file.txt", 1, null, cursor, 2, CancellationToken.uncancellable()));
    write(first, "file.txt", "changed");
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.read("file.txt", 1, null, cursor, 2, CancellationToken.uncancellable()));
  }

  @Test
  public void readDetectsChangesDuringPageAndRejectsBinaryUtf8() throws Exception {
    ProjectScope scope = scope();
    write(scope, "file.txt", "before");
    ProjectFileReader reader =
        new ProjectFileReader(
            scope,
            new ProjectFileReader.ReadObserver() {
              public void afterInitialHash() throws IOException {
                write(scope, "file.txt", "after");
              }

              public void beforeFinalHash() {}
            });
    assertThrows(
        IOException.class,
        () -> reader.read("file.txt", 1, null, null, 100, CancellationToken.uncancellable()));
    for (byte[] bytes :
        new byte[][] {{0}, {(byte) 0xc0, (byte) 0xaf}, {(byte) 0xf0, (byte) 0x9f}}) {
      Files.write(scope.resolve("binary").toPath(), bytes);
      assertThrows(
          IOException.class,
          () ->
              new ProjectFileReader(scope)
                  .read("binary", 1, null, null, 100, CancellationToken.uncancellable()));
    }
  }

  @Test
  public void globHonorsIgnoresAndOrdersPages() throws Exception {
    ProjectScope scope = scope();
    write(scope, ".gitignore", "*.tmp\nsecret/\n");
    write(scope, "z.txt", "z");
    write(scope, "a.txt", "a");
    write(scope, "src/b.txt", "b");
    write(scope, "skip.tmp", "tmp");
    write(scope, "secret/key.txt", "private");
    write(scope, "node_modules/lib.txt", "dependency");
    ProjectSearch search = new ProjectSearch(scope);
    List<String> found = new ArrayList<>();
    String cursor = null;
    do {
      ProjectSearch.Page page =
          search.glob(".", "**/*.txt", false, cursor, 12, CancellationToken.uncancellable());
      found.addAll(page.entries);
      cursor = page.nextCursor;
    } while (cursor != null);
    assertEquals(Arrays.asList("a.txt", "src/b.txt", "z.txt"), found);
    assertTrue(
        search
            .glob(".", "**/*.txt", true, null, 1000, CancellationToken.uncancellable())
            .entries
            .contains("secret/key.txt"));
    assertFalse(
        search
            .list(".", false, null, 1000, CancellationToken.uncancellable())
            .entries
            .contains("skip.tmp"));
  }

  @Test
  public void grepIsLiteralByDefaultHasPositionsAndCountsBinary() throws Exception {
    ProjectScope scope = scope();
    write(scope, "a.txt", "one\nxx a.b yy\naXb\n");
    Files.write(scope.resolve("binary").toPath(), new byte[] {0, 1});
    ProjectSearch search = new ProjectSearch(scope);
    ProjectSearch.Page literal =
        search.grep(".", "a.b", false, false, null, 1000, CancellationToken.uncancellable());
    assertEquals(Arrays.asList("a.txt:2:4: xx a.b yy"), literal.entries);
    assertEquals(1, literal.skippedBinaryFiles);
    assertEquals(
        2,
        search
            .grep("a.txt", "a.b", true, false, null, 1000, CancellationToken.uncancellable())
            .entries
            .size());
  }

  @Test
  public void longLineSearchFindsMatchBeyondBoundedPrefix() throws Exception {
    ProjectScope scope = scope();
    char[] padding = new char[1000];
    Arrays.fill(padding, 'x');
    write(scope, "long.txt", new String(padding) + "needle😀tail");
    ProjectSearch.Page page =
        new ProjectSearch(scope)
            .grep("long.txt", "needle", false, false, null, 90, CancellationToken.uncancellable());
    assertEquals(1, page.entries.size());
    assertTrue(page.entries.get(0).startsWith("long.txt:1:1001: needle😀tail"));
    assertTrue(page.entries.get(0).contains("[excerpt; use read for the full line]"));
  }

  @Test
  public void searchCursorRejectsDifferentQueryAndChangedScopeVersion() throws Exception {
    ProjectScope scope = scope();
    write(scope, "a", "a");
    write(scope, "b", "b");
    ProjectSearch search = new ProjectSearch(scope);
    String cursor = search.list(".", false, null, 2, CancellationToken.uncancellable()).nextCursor;
    assertNotNull(cursor);
    assertThrows(
        IllegalArgumentException.class,
        () -> search.list(".", true, cursor, 2, CancellationToken.uncancellable()));
    try (ProjectScope.WriterLease lease = scope.acquireWriter("test", scope.version())) {
      lease.markChanged();
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> search.list(".", false, cursor, 2, CancellationToken.uncancellable()));
  }

  @Test
  public void cancellationStopsReadsAndSearch() throws Exception {
    ProjectScope scope = scope();
    write(scope, "file.txt", "text");
    CancellationToken token = CancellationToken.cancellable();
    token.cancel();
    assertThrows(
        CancellationException.class,
        () -> new ProjectFileReader(scope).read("file.txt", 1, null, null, 20, token));
    assertThrows(
        CancellationException.class,
        () -> new ProjectSearch(scope).list(".", false, null, 20, token));
  }

  @Test
  public void collectorNeverAdvancesCursorPastEvictedEarlierResults() {
    ProjectSearch.Collector collector = new ProjectSearch.Collector(12, null);
    collector.add(new ProjectSearch.Key("a.txt", 0), "a.txt");
    collector.add(new ProjectSearch.Key("src/b.txt", 0), "src/b.txt");
    collector.add(new ProjectSearch.Key("z.txt", 0), "z.txt");
    assertEquals(Arrays.asList("a.txt"), new ArrayList<>(collector.items.values()));
    assertTrue(collector.more);
    ProjectSearch.Collector continuation =
        new ProjectSearch.Collector(12, collector.items.lastKey());
    continuation.add(new ProjectSearch.Key("z.txt", 0), "z.txt");
    continuation.add(new ProjectSearch.Key("src/b.txt", 0), "src/b.txt");
    assertEquals(Arrays.asList("src/b.txt"), new ArrayList<>(continuation.items.values()));
  }
}
