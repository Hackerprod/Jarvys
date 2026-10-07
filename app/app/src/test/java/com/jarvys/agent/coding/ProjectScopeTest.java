package com.jarvys.agent.coding;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ProjectScopeTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void conversationsAreIsolatedAndReopenUsesSameScope() throws Exception {
    ProjectScopeStore store = new ProjectScopeStore(temporary.newFolder());
    ProjectScope first = store.open("conversation-a");
    ProjectScope second = store.open("conversation-b");
    assertNotEquals(first.id(), second.id());
    assertNotEquals(first.rootDirectory(), second.rootDirectory());
    Files.write(first.resolve("notes.txt").toPath(), "private".getBytes(StandardCharsets.UTF_8));
    assertEquals(ProjectScope.MISSING, second.revision("notes.txt"));
    assertSame(first, store.open("conversation-a"));
    assertEquals(first.durableIdentity(), store.open("conversation-a").durableIdentity());
  }

  @Test
  public void rejectsTraversalAbsolutePathsAndInternalStaging() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("scope");
    for (String path :
        new String[] {
          "../escape",
          "a/../escape",
          "/tmp/escape",
          "C:/escape",
          "a\\b",
          "x\u0000y",
          ".jarvys-tmp-123",
          "nested/.jarvys-tmp-123"
        }) {
      assertThrows(path, IOException.class, () -> scope.resolve(path));
    }
    assertEquals("a/b", scope.normalizePath("./a//b/"));
    assertEquals(".", scope.normalizePath("./"));
  }

  @Test
  public void rejectsSymlinkFilesAndParents() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("scope");
    File outside = temporary.newFolder();
    Files.write(new File(outside, "secret").toPath(), new byte[] {1});
    Files.createSymbolicLink(new File(scope.rootDirectory(), "link").toPath(), outside.toPath());
    assertThrows(IOException.class, () -> scope.resolve("link"));
    assertThrows(IOException.class, () -> scope.resolve("link/secret"));
  }

  @Test
  public void replacedRootInvalidatesExistingAndReopenedScope() throws Exception {
    ProjectScopeStore store = new ProjectScopeStore(temporary.newFolder());
    ProjectScope scope = store.open("scope");
    File root = scope.rootDirectory();
    Files.move(root.toPath(), new File(root.getParentFile(), "old-root").toPath());
    assertTrue(root.mkdir());
    assertThrows(IOException.class, scope::validate);
    assertThrows(IOException.class, () -> store.open("scope"));
  }

  @Test
  public void readOnlyRestrictionCannotAcquireWriterOrRegainPermission() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("scope");
    ProjectScope readOnly = scope.restrict(EnumSet.of(ProjectScope.Capability.READ));
    assertEquals(scope.rootDirectory(), readOnly.rootDirectory());
    assertThrows(IOException.class, () -> readOnly.acquireWriter("writer", 0));
    assertFalse(
        readOnly
            .restrict(EnumSet.allOf(ProjectScope.Capability.class))
            .capabilities()
            .contains(ProjectScope.Capability.WRITE));
  }

  @Test
  public void writerLeaseIsExclusiveVersionedAndIdempotentlyClosed() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("scope");
    ProjectScope.WriterLease lease = scope.acquireWriter("one", 0);
    assertThrows(ProjectScope.ConflictException.class, () -> scope.acquireWriter("two", 0));
    lease.markChanged();
    lease.markChanged();
    assertEquals(1, scope.version());
    lease.close();
    lease.close();
    assertThrows(ProjectScope.ConflictException.class, lease::markChanged);
    assertThrows(ProjectScope.ConflictException.class, () -> scope.acquireWriter("two", 0));
    try (ProjectScope.WriterLease next = scope.acquireWriter("two", 1)) {
      next.validate();
    }
  }
}
