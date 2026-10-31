package com.jarvys.agent.coding;

import static org.junit.Assert.*;

import com.jarvys.agent.CancellationToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProjectMutationRecoveryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private ProjectScope scope() throws IOException {
    return new ProjectScopeStore(temporary.newFolder()).open("mutation-test");
  }

  private ProjectMutationService.Result apply(
      ProjectScope scope, ProjectMutationService.Operation... operations) {
    return new ProjectMutationService()
        .apply(scope, "test", scope.version(), Arrays.asList(operations), null);
  }

  private static void write(ProjectScope scope, String path, String value) throws IOException {
    Files.write(scope.resolve(path).toPath(), value.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(ProjectScope scope, String path) throws IOException {
    return new String(Files.readAllBytes(scope.resolve(path).toPath()), StandardCharsets.UTF_8);
  }

  private static ProjectMutationJournal.RecoveryEntry entry(ProjectScope scope, String id)
      throws IOException {
    for (ProjectMutationJournal.RecoveryEntry entry : scope.mutationRecovery().entries) {
      if (entry.id.equals(id)) return entry;
    }
    throw new AssertionError("Missing journal " + id);
  }

  private static ProjectMutationJournal.Change add(String path, String text) {
    return new ProjectMutationJournal.Change(
        path,
        "ADD",
        ProjectScope.MISSING,
        ProjectScope.sha256(text.getBytes(StandardCharsets.UTF_8)),
        false);
  }

  @Test
  public void addEditMoveDeletePreservesTextFormatAndReportsEffects() throws IOException {
    ProjectScope scope = scope();
    ProjectMutationService.Result added =
        apply(scope, ProjectMutationService.Operation.add("src/a.txt", "\ufeffone\r\ntwo\r\n"));
    assertEquals(ProjectMutationService.Status.APPLIED, added.status);
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.APPLIED, entry(scope, added.journalId).status);
    ProjectMutationService.Result edited =
        apply(
            scope,
            ProjectMutationService.Operation.edit(
                "src/a.txt",
                scope.revision("src/a.txt"),
                Collections.singletonList(
                    new ProjectMutationService.Hunk("one\ntwo\n", "three\nfour\n"))));
    assertEquals(ProjectMutationService.Status.APPLIED, edited.status);
    assertEquals("\ufeffthree\r\nfour\r\n", read(scope, "src/a.txt"));
    ProjectMutationService.Result moved =
        apply(
            scope,
            ProjectMutationService.Operation.move(
                "src/a.txt", scope.revision("src/a.txt"), "b.txt", ProjectScope.MISSING));
    assertEquals(ProjectMutationService.Status.APPLIED, moved.status);
    assertEquals(ProjectMutationService.Kind.MOVE, moved.applied.get(0).kind);
    assertEquals(ProjectScope.MISSING, scope.revision("src/a.txt"));
    assertEquals("\ufeffthree\r\nfour\r\n", read(scope, "b.txt"));
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.APPLIED, entry(scope, moved.journalId).status);
    ProjectMutationService.Result deleted =
        apply(scope, ProjectMutationService.Operation.delete("b.txt", scope.revision("b.txt")));
    assertEquals(ProjectMutationService.Status.APPLIED, deleted.status);
    assertEquals(ProjectScope.MISSING, scope.revision("b.txt"));
    assertEquals(4, scope.version());
  }

  @Test
  public void staleHashesAndAmbiguousHunksDoNotWrite() throws IOException {
    ProjectScope scope = scope();
    write(scope, "a.txt", "same same");
    ProjectMutationService.Result stale =
        apply(scope, ProjectMutationService.Operation.write("a.txt", ProjectScope.MISSING, "bad"));
    assertEquals(ProjectMutationService.Status.CONFLICT, stale.status);
    assertTrue(stale.applied.isEmpty());
    assertNull(stale.journalId);
    ProjectMutationService.Result ambiguous =
        apply(
            scope,
            ProjectMutationService.Operation.edit(
                "a.txt",
                scope.revision("a.txt"),
                Collections.singletonList(new ProjectMutationService.Hunk("same", "new"))));
    assertEquals(ProjectMutationService.Status.CONFLICT, ambiguous.status);
    assertEquals("same same", read(scope, "a.txt"));
    assertEquals(0, scope.version());
  }

  @Test
  public void overlappingPathsAndInvalidOrOversizeTextFailWithoutChanges() throws IOException {
    ProjectScope scope = scope();
    ProjectMutationService.Result overlap =
        apply(
            scope,
            ProjectMutationService.Operation.add("dir", "x"),
            ProjectMutationService.Operation.add("dir/file", "y"));
    assertEquals(ProjectMutationService.Status.FAILED, overlap.status);
    assertFalse(scope.resolve("dir").exists());
    ProjectMutationService.Result binary =
        apply(scope, ProjectMutationService.Operation.add("binary", "a\0b"));
    assertEquals(ProjectMutationService.Status.FAILED, binary.status);
    ProjectMutationService.Result large =
        new ProjectMutationService(2)
            .apply(
                scope,
                "test",
                scope.version(),
                Collections.singletonList(ProjectMutationService.Operation.add("large", "abc")),
                null);
    assertEquals(ProjectMutationService.Status.FAILED, large.status);
    assertFalse(scope.resolve("large").exists());
  }

  @Test
  public void cancelledSecondOperationRetainsOnlyFirstReceipt() throws IOException {
    ProjectScope scope = scope();
    CancellationToken token = CancellationToken.cancellable();
    ProjectMutationService service =
        new ProjectMutationService(
            262144,
            new ProjectMutationService.CommitObserver() {
              @Override
              public void beforeCommit(int index, String path) {}

              @Override
              public void afterPromotion(String path) {
                token.cancel();
              }
            });
    ProjectMutationService.Result result =
        service.apply(
            scope,
            "test",
            scope.version(),
            Arrays.asList(
                ProjectMutationService.Operation.add("first", "1"),
                ProjectMutationService.Operation.add("second", "2")),
            token);
    assertEquals(ProjectMutationService.Status.PARTIAL, result.status);
    assertEquals(1, result.applied.size());
    assertEquals("1", read(scope, "first"));
    assertFalse(scope.resolve("second").exists());
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.PARTIAL, entry(scope, result.journalId).status);
    // Cancellation closed the writer lease, so a later caller can proceed.
    assertEquals(
        ProjectMutationService.Status.APPLIED,
        apply(scope, ProjectMutationService.Operation.add("third", "3")).status);
  }

  @Test
  public void cancelledBeforeCommitHasNoEffectsAndReadOnlyRecovery() throws IOException {
    ProjectScope scope = scope();
    CancellationToken token = CancellationToken.cancellable();
    token.cancel();
    ProjectMutationService.Result result =
        new ProjectMutationService()
            .apply(
                scope,
                "test",
                scope.version(),
                Collections.singletonList(ProjectMutationService.Operation.add("never", "written")),
                token);
    assertEquals(ProjectMutationService.Status.CANCELLED, result.status);
    assertNull(result.journalId);
    assertFalse(scope.resolve("never").exists());
  }

  @Test
  public void failureBeforePromotionCleansCreatedDirectories() throws IOException {
    ProjectScope scope = scope();
    ProjectMutationService service =
        new ProjectMutationService(
            262144,
            new ProjectMutationService.CommitObserver() {
              @Override
              public void beforeCommit(int index, String path) {}

              @Override
              public void afterCreateDirectory(String path) throws IOException {
                throw new IOException("injected");
              }
            });
    ProjectMutationService.Result result =
        service.apply(
            scope,
            "test",
            scope.version(),
            Collections.singletonList(ProjectMutationService.Operation.add("new/deep/a", "text")),
            null);
    assertEquals(ProjectMutationService.Status.FAILED, result.status);
    assertTrue(result.cleanupWarnings.isEmpty());
    assertFalse(scope.resolve("new").exists());
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.NOT_APPLIED, entry(scope, result.journalId).status);
  }

  @Test
  public void cleanupFailureCannotReportSuccess() throws IOException {
    ProjectScope scope = scope();
    ProjectMutationService service =
        new ProjectMutationService(
            262144,
            new ProjectMutationService.CommitObserver() {
              @Override
              public void beforeCommit(int index, String path) {}

              @Override
              public void beforeCleanup(String path, boolean directory) throws IOException {
                throw new IOException("blocked cleanup");
              }
            });
    ProjectMutationService.Result result =
        service.apply(
            scope,
            "test",
            scope.version(),
            Collections.singletonList(ProjectMutationService.Operation.add("a", "text")),
            null);
    assertEquals(ProjectMutationService.Status.PARTIAL, result.status);
    assertFalse(result.isSuccess());
    assertFalse(result.cleanupWarnings.isEmpty());
    assertEquals("text", read(scope, "a"));
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.PARTIAL, entry(scope, result.journalId).status);
  }

  @Test
  public void missingTerminalJournalCannotPreserveCancelledStatus() throws IOException {
    ProjectScope scope = scope();
    ProjectMutationService service =
        new ProjectMutationService(
            262144,
            (index, path) -> {
              File[] journals = scope.mutationJournalDirectory().listFiles();
              assertNotNull(journals);
              for (File journal : journals) Files.delete(journal.toPath());
              throw new CancellationException("injected cancellation");
            });
    ProjectMutationService.Result result =
        service.apply(
            scope,
            "test",
            scope.version(),
            Collections.singletonList(ProjectMutationService.Operation.add("a", "text")),
            null);
    assertEquals(ProjectMutationService.Status.FAILED, result.status);
    assertFalse(result.cleanupWarnings.isEmpty());
    assertTrue(result.applied.isEmpty());
    assertFalse(scope.resolve("a").exists());
  }

  @Test
  public void recoveryDistinguishesUnappliedPartialAppliedAndUncertainWithoutReplay()
      throws IOException {
    ProjectScope scope = scope();
    ProjectMutationJournal journal = new ProjectMutationJournal(scope);
    ProjectMutationJournal.Transaction transaction =
        journal.begin("test", Arrays.asList(add("a", "one"), add("b", "two")));
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.NOT_APPLIED, entry(scope, transaction.id).status);
    assertFalse(scope.resolve("a").exists());
    write(scope, "a", "one");
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.PARTIAL, entry(scope, transaction.id).status);
    assertFalse(scope.resolve("b").exists());
    write(scope, "b", "two");
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.APPLIED, entry(scope, transaction.id).status);
    write(scope, "a", "other");
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.UNCERTAIN, entry(scope, transaction.id).status);
    assertEquals("other", read(scope, "a"));
    assertEquals(0, scope.version());
  }

  @Test
  public void terminalReceiptsKeepHistoricalEffectsAndFlagLaterChanges() throws IOException {
    ProjectScope scope = scope();
    ProjectMutationService.Result applied =
        apply(scope, ProjectMutationService.Operation.add("a", "one"));
    write(scope, "a", "later");
    ProjectMutationJournal.RecoveryEntry recovered = entry(scope, applied.journalId);
    assertEquals(ProjectMutationJournal.RecoveryStatus.APPLIED, recovered.status);
    ProjectMutationJournal.PathObservation path = recovered.paths.get(recovered.paths.size() - 1);
    assertEquals("a", path.path);
    assertTrue(path.changedSinceReceipt);
    assertEquals(ProjectScope.sha256("later".getBytes(StandardCharsets.UTF_8)), path.currentSha);
    assertEquals("later", read(scope, "a"));
  }

  @Test
  public void stagingAndCleanupWarningsDowngradeOtherwiseAppliedRecovery() throws IOException {
    ProjectScope scope = scope();
    String staging = ".jarvys-tmp-" + UUID.randomUUID() + ".part";
    String hash = ProjectScope.sha256("one".getBytes(StandardCharsets.UTF_8));
    ProjectMutationJournal.Transaction transaction =
        new ProjectMutationJournal(scope)
            .begin(
                "test",
                Arrays.asList(
                    ProjectMutationJournal.Change.staging(staging, hash), add("a", "one")));
    write(scope, "a", "one");
    File residual = new File(scope.rootDirectory(), staging);
    Files.write(residual.toPath(), "one".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.PARTIAL, entry(scope, transaction.id).status);
    Files.delete(residual.toPath());
    transaction.cleaned(staging);
    transaction.applied("a");
    transaction.finish(ProjectMutationService.Status.PARTIAL, 1);
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.PARTIAL, entry(scope, transaction.id).status);
  }

  @Test
  public void unrecognizedAndCorruptEvidenceIsCountedAndPreserved() throws IOException {
    ProjectScope scope = scope();
    File unknown = new File(scope.mutationJournalDirectory(), "unexpected.part");
    File corrupt = new File(scope.mutationJournalDirectory(), UUID.randomUUID() + ".json");
    Files.write(unknown.toPath(), new byte[] {1});
    Files.write(corrupt.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
    ProjectMutationJournal.RecoveryReport report = scope.mutationRecovery();
    assertEquals(2, report.issueCount);
    assertTrue(report.entries.isEmpty());
    assertTrue(unknown.exists());
    assertTrue(corrupt.exists());
  }

  @Test
  public void effectBeforeReceiptFailureReportsWhatActuallyChanged() throws IOException {
    ProjectScope scope = scope();
    ProjectMutationService service =
        new ProjectMutationService(
            262144,
            new ProjectMutationService.CommitObserver() {
              @Override
              public void beforeCommit(int index, String path) {}

              @Override
              public void afterEffectBeforeJournal(String path) throws IOException {
                throw new IOException("injected receipt failure");
              }
            });
    ProjectMutationService.Result result =
        service.apply(
            scope,
            "test",
            scope.version(),
            Collections.singletonList(ProjectMutationService.Operation.add("a", "effect")),
            null);
    assertEquals(ProjectMutationService.Status.PARTIAL, result.status);
    assertEquals(1, result.applied.size());
    assertEquals("effect", read(scope, "a"));
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.APPLIED, entry(scope, result.journalId).status);
    write(scope, "a", "changed later");
    // A terminal record alone cannot replace an absent per-path acknowledgement.
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.UNCERTAIN, entry(scope, result.journalId).status);
    assertEquals("changed later", read(scope, "a"));
  }

  @Test
  public void moveConflictAfterDestinationPromotionKeepsCopyReceiptAndSource() throws IOException {
    ProjectScope scope = scope();
    write(scope, "source", "original");
    ProjectMutationService service =
        new ProjectMutationService(
            262144,
            new ProjectMutationService.CommitObserver() {
              @Override
              public void beforeCommit(int index, String path) {}

              @Override
              public void afterPromotion(String path) throws IOException {
                if (path.equals("destination")) write(scope, "source", "new external content");
              }
            });
    ProjectMutationService.Result result =
        service.apply(
            scope,
            "test",
            scope.version(),
            Collections.singletonList(
                ProjectMutationService.Operation.move(
                    "source", scope.revision("source"), "destination", ProjectScope.MISSING)),
            null);
    assertEquals(ProjectMutationService.Status.PARTIAL, result.status);
    assertEquals(1, result.applied.size());
    assertEquals(ProjectMutationService.Kind.ADD, result.applied.get(0).kind);
    assertEquals("destination", result.applied.get(0).path);
    assertEquals("original", read(scope, "destination"));
    assertEquals("new external content", read(scope, "source"));
    assertEquals(
        ProjectMutationJournal.RecoveryStatus.UNCERTAIN, entry(scope, result.journalId).status);
  }
}
