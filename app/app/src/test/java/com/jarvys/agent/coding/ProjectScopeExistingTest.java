package com.jarvys.agent.coding;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ProjectScopeExistingTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void absentScopeDoesNotCreateAnything() throws Exception {
    File root = temporary.newFolder();
    assertThrows(IOException.class, () -> new ProjectScopeStore(root).openExisting("missing"));
    assertEquals(0, root.list().length);
  }

  @Test public void completeExistingScopePreservesIdentityAndFileBytes() throws Exception {
    File root = temporary.newFolder();
    ProjectScopeStore store = new ProjectScopeStore(root);
    ProjectScope original = store.open("existing");
    File identity = ProjectScopeIdentity.manifestPath(root, original.id());
    byte[] before = Files.readAllBytes(identity.toPath());
    assertEquals(original.durableIdentity(), store.openExisting("existing").durableIdentity());
    assertArrayEquals(before, Files.readAllBytes(identity.toPath()));
  }

  @Test public void missingRootIsNeverRecreatedThroughCachedScope() throws Exception {
    File root = temporary.newFolder();
    ProjectScopeStore store = new ProjectScopeStore(root);
    ProjectScope original = store.open("existing");
    File directory = original.rootDirectory();
    assertTrue(directory.delete());
    assertThrows(IOException.class, () -> store.openExisting("existing"));
    assertFalse(directory.exists());
  }

  @Test public void uncachedRootWithoutIdentityDoesNotCreateState() throws Exception {
    File root = temporary.newFolder();
    String id = ProjectScope.sha256("incomplete".getBytes(StandardCharsets.UTF_8));
    assertTrue(new File(root, "jarvys/g3/coding-projects/" + id + "/root").mkdirs());
    assertThrows(IOException.class, () -> new ProjectScopeStore(root).openExisting("incomplete"));
    assertFalse(new File(root, "jarvys/g3/coding-project-state").exists());
  }

  @Test public void cachedMissingManifestAndJournalNeverGetRepaired() throws Exception {
    for (boolean deleteManifest : new boolean[] {true, false}) {
      File root = temporary.newFolder();
      ProjectScopeStore store = new ProjectScopeStore(root);
      ProjectScope scope = store.open("existing");
      File manifest = ProjectScopeIdentity.manifestPath(root, scope.id());
      File missing = deleteManifest ? manifest : new File(manifest.getParentFile(), "mutations");
      assertTrue(missing.delete());
      assertThrows(IOException.class, () -> store.openExisting("existing"));
      assertFalse(missing.exists());
    }
  }
}
