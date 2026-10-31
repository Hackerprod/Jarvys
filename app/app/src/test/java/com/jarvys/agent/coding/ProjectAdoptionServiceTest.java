package com.jarvys.agent.coding;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProjectAdoptionServiceTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private ProjectScope scope() throws IOException {
    return new ProjectScopeStore(temporary.newFolder()).open("adoption-test");
  }

  private static void write(File root, String path, String value) throws IOException {
    File file = new File(root, path);
    Files.createDirectories(file.getParentFile().toPath());
    Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void explicitAdoptionIsIdempotentAndLeavesOriginals() throws IOException {
    ProjectScope scope = scope();
    File legacy = temporary.newFolder();
    write(legacy, "nested/a.txt", "original");
    ProjectAdoptionService service = new ProjectAdoptionService();
    ProjectAdoptionService.Review review =
        service.review(scope, legacy, Collections.singletonList("nested/a.txt"), null);
    assertEquals(ProjectAdoptionService.EntryStatus.READY, review.entries.get(0).status);
    ProjectAdoptionService.Result first =
        service.adopt(scope, "test", scope.version(), review, null);
    assertEquals(ProjectAdoptionService.Status.COMPLETE, first.status);
    assertEquals(Collections.singletonList("nested/a.txt"), first.copied);
    assertTrue(new File(legacy, "nested/a.txt").exists());
    long version = scope.version();
    ProjectAdoptionService.Result second = service.adopt(scope, "test", version, review, null);
    assertEquals(ProjectAdoptionService.Status.COMPLETE, second.status);
    assertTrue(second.copied.isEmpty());
    assertEquals(Collections.singletonList("nested/a.txt"), second.alreadyPresent);
    assertEquals(version, scope.version());
  }

  @Test
  public void collisionFailsTheWholePreflight() throws IOException {
    ProjectScope scope = scope();
    File legacy = temporary.newFolder();
    write(legacy, "a", "one");
    write(legacy, "b", "two");
    write(scope.rootDirectory(), "b", "collision");
    ProjectAdoptionService service = new ProjectAdoptionService();
    ProjectAdoptionService.Review review =
        service.review(scope, legacy, Arrays.asList("a", "b"), null);
    assertEquals(ProjectAdoptionService.EntryStatus.COLLISION, review.entries.get(1).status);
    ProjectAdoptionService.Result result =
        service.adopt(scope, "test", scope.version(), review, null);
    assertEquals(ProjectAdoptionService.Status.CONFLICT, result.status);
    assertTrue(result.copied.isEmpty());
    assertFalse(scope.resolve("a").exists());
    assertEquals(
        "collision",
        new String(Files.readAllBytes(scope.resolve("b").toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void sourceChangesAfterReviewAreRejected() throws IOException {
    ProjectScope scope = scope();
    File legacy = temporary.newFolder();
    write(legacy, "a", "before");
    ProjectAdoptionService service = new ProjectAdoptionService();
    ProjectAdoptionService.Review review =
        service.review(scope, legacy, Collections.singletonList("a"), null);
    write(legacy, "a", "after");
    ProjectAdoptionService.Result result =
        service.adopt(scope, "test", scope.version(), review, null);
    assertEquals(ProjectAdoptionService.Status.CONFLICT, result.status);
    assertFalse(scope.resolve("a").exists());
  }

  @Test
  public void cancelledSecondCopyReportsFirstAndReleasesLease() throws IOException {
    ProjectScope scope = scope();
    File legacy = temporary.newFolder();
    write(legacy, "a", "one");
    write(legacy, "b", "two");
    CancellationToken token = CancellationToken.cancellable();
    ProjectAdoptionService service =
        new ProjectAdoptionService(
            new ProjectAdoptionService.CopyObserver() {
              @Override
              public void beforeCopy(int index, String path) {}

              @Override
              public void afterPromotion(String path) {
                token.cancel();
              }
            });
    ProjectAdoptionService.Review review =
        service.review(scope, legacy, Arrays.asList("a", "b"), null);
    ProjectAdoptionService.Result result =
        service.adopt(scope, "test", scope.version(), review, token);
    assertEquals(ProjectAdoptionService.Status.PARTIAL, result.status);
    assertEquals(Collections.singletonList("a"), result.copied);
    assertFalse(scope.resolve("b").exists());
    assertTrue(new File(legacy, "a").exists());
    assertTrue(new File(legacy, "b").exists());
    ProjectAdoptionService.Result rest =
        new ProjectAdoptionService().adopt(scope, "test", scope.version(), review, null);
    assertEquals(ProjectAdoptionService.Status.COMPLETE, rest.status);
    assertEquals(Collections.singletonList("b"), rest.copied);
  }

  @Test
  public void failedDirectoryCreationHookCleansOnlyCreatedParents() throws IOException {
    ProjectScope scope = scope();
    File legacy = temporary.newFolder();
    write(legacy, "new/a", "text");
    ProjectAdoptionService service =
        new ProjectAdoptionService(
            new ProjectAdoptionService.CopyObserver() {
              @Override
              public void beforeCopy(int index, String path) {}

              @Override
              public void afterCreateDirectory(String path) throws IOException {
                throw new IOException("injected");
              }
            });
    ProjectAdoptionService.Review review =
        service.review(scope, legacy, Collections.singletonList("new/a"), null);
    ProjectAdoptionService.Result result =
        service.adopt(scope, "test", scope.version(), review, null);
    assertEquals(ProjectAdoptionService.Status.FAILED, result.status);
    assertTrue(result.cleanupWarnings.isEmpty());
    assertFalse(scope.resolve("new").exists());
    assertTrue(new File(legacy, "new/a").exists());
  }

  @Test
  public void failedStagingCleanupIsPartialEvenAfterSuccessfulCopy() throws IOException {
    ProjectScope scope = scope();
    File legacy = temporary.newFolder();
    write(legacy, "a", "text");
    ProjectAdoptionService service =
        new ProjectAdoptionService(
            new ProjectAdoptionService.CopyObserver() {
              @Override
              public void beforeCopy(int index, String path) {}

              @Override
              public void beforeCleanup(String path, boolean directory) throws IOException {
                throw new IOException("blocked");
              }
            });
    ProjectAdoptionService.Review review =
        service.review(scope, legacy, Collections.singletonList("a"), null);
    ProjectAdoptionService.Result result =
        service.adopt(scope, "test", scope.version(), review, null);
    assertEquals(ProjectAdoptionService.Status.PARTIAL, result.status);
    assertFalse(result.cleanupWarnings.isEmpty());
    assertTrue(scope.resolve("a").exists());
    assertTrue(new File(legacy, "a").exists());
  }

  @Test
  public void privateLegacyZonesAndSymlinksCannotBeAdopted() throws IOException {
    ProjectScope scope = scope();
    File legacy = temporary.newFolder();
    write(legacy, "memory/private.txt", "private");
    ProjectAdoptionService service = new ProjectAdoptionService();
    assertThrows(
        IOException.class,
        () -> service.review(scope, legacy, Collections.singletonList("memory/private.txt"), null));
    File outside = temporary.newFile();
    Files.createSymbolicLink(new File(legacy, "link").toPath(), outside.toPath());
    assertThrows(
        IOException.class,
        () -> service.review(scope, legacy, Collections.singletonList("link"), null));
    assertThrows(
        IOException.class,
        () -> service.review(scope, legacy, Collections.singletonList(".."), null));
    assertThrows(
        IOException.class, () -> service.review(scope, legacy, Collections.emptyList(), null));
  }
}
