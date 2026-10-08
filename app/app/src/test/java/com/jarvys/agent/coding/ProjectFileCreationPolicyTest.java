package com.jarvys.agent.coding;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ProjectFileCreationPolicyTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private File source(String name, String value) throws IOException {
    File file = temporary.newFile(name);
    Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private String text(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  @Test public void exclusiveCreationUsesIndependentInodeAndRetainsStaging() throws Exception {
    File source = source("staging", "complete bytes");
    File target = new File(temporary.getRoot(), "new.txt");
    ProjectFileIO.copyNew(source, target);
    assertEquals("complete bytes", text(target));
    assertEquals("complete bytes", text(source));
    assertNotEquals(ProjectFileIO.attributes(source).fileKey(), ProjectFileIO.attributes(target).fileKey());
  }

  @Test public void existingFileAndSymlinkAreNeverOverwritten() throws Exception {
    File source = source("staging", "new");
    File existing = source("existing", "user data");
    assertThrows(IOException.class, () -> ProjectFileIO.copyNew(source, existing));
    assertEquals("user data", text(existing));
    File link = new File(temporary.getRoot(), "link");
    Files.createSymbolicLink(link.toPath(), existing.toPath());
    assertThrows(IOException.class, () -> ProjectFileIO.copyNew(source, link));
    assertEquals("user data", text(existing));
    File missing = new File(temporary.getRoot(), "missing");
    File dangling = new File(temporary.getRoot(), "dangling");
    Files.createSymbolicLink(dangling.toPath(), missing.toPath());
    assertThrows(IOException.class, () -> ProjectFileIO.copyNew(source, dangling));
    assertFalse(missing.exists());
  }

  @Test public void interruptedCopyRetainsEvidenceAndCannotBeBlindlyRetried() throws Exception {
    String complete = String.join("", java.util.Collections.nCopies(20000, "x"));
    File source = source("staging", complete);
    File target = new File(temporary.getRoot(), "partial");
    assertThrows(ProjectFileIO.IncompleteCreationException.class,
        () -> ProjectFileIO.copyNew(source, target, count -> { throw new IOException("simulated storage failure"); }));
    assertTrue(target.length() > 0 && target.length() < source.length());
    byte[] partial = Files.readAllBytes(target.toPath());
    assertThrows(IOException.class, () -> ProjectFileIO.copyNew(source, target));
    assertArrayEquals(partial, Files.readAllBytes(target.toPath()));
    assertEquals(complete, text(source));
  }

  @Test public void concurrentCreatorsHaveOneWinnerWithoutMixedBytes() throws Exception {
    File first = source("first", "first bytes");
    File second = source("second", "second bytes");
    File target = new File(temporary.getRoot(), "target");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    Thread[] threads = new Thread[2];
    for (int i = 0; i < 2; i++) {
      File candidate = i == 0 ? first : second;
      threads[i] = new Thread(() -> {
        ready.countDown();
        try { start.await(); ProjectFileIO.copyNew(candidate, target); successes.incrementAndGet(); }
        catch (IOException expected) { failures.incrementAndGet(); }
        catch (InterruptedException unexpected) { Thread.currentThread().interrupt(); }
      });
      threads[i].start();
    }
    ready.await(); start.countDown();
    for (Thread thread : threads) thread.join();
    assertEquals(1, successes.get());
    assertEquals(1, failures.get());
    assertTrue(Arrays.asList("first bytes", "second bytes").contains(text(target)));
  }

  @Test public void privateJournalPublishesWholeRecordAndDoesNotReplaceExisting() throws Exception {
    File record = new File(temporary.getRoot(), "identity.json");
    ProjectJournalIO.write(record, new JSONObject().put("value", "one"), true);
    assertEquals("one", ProjectJournalIO.read(record).getString("value"));
    assertThrows(IOException.class, () -> ProjectJournalIO.write(record, new JSONObject().put("value", "two"), true));
    assertEquals("one", ProjectJournalIO.read(record).getString("value"));
    ProjectJournalIO.write(record, new JSONObject().put("value", "three"), false);
    assertEquals("three", ProjectJournalIO.read(record).getString("value"));
    assertEquals(1, temporary.getRoot().list().length);
  }

  @Test public void projectStorageCannotDependOnAndroidForbiddenHardLinks() throws Exception {
    // Linux/Robolectric permits this syscall; AOSP's app_neverallows.te forbids it for real apps.
    // Guard the compiled production implementation, rather than treating host success as Android proof.
    for (Class<?> implementation : new Class<?>[] {ProjectFileIO.class,
        Class.forName("com.jarvys.agent.coding.ProjectFileIO$Nio")}) {
      String resource = "/" + implementation.getName().replace('.', '/') + ".class";
      try (InputStream input = implementation.getResourceAsStream(resource)) {
        assertNotNull(input);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192]; int count;
        while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
        String constants = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse("Android app storage cannot call Files.createLink", constants.contains("createLink"));
        assertFalse("Android app storage cannot call old hardlink backend", constants.contains("linkNoReplace"));
      }
    }
  }
  @Test public void copiedSourceMustMatchPlannedHashEvenWhenLengthAndInodeStayTheSame() throws Exception {
    String complete = String.join("", java.util.Collections.nCopies(20000, "x"));
    File source = source("changing", complete);
    File target = new File(temporary.getRoot(), "changed-target");
    assertThrows(ProjectFileIO.IncompleteCreationException.class, () -> ProjectFileIO.copyNew(
        source, target, ProjectScope.sha256(complete.getBytes(StandardCharsets.UTF_8)), complete.length(),
        count -> {
          if (count == 8192) {
            try (java.io.RandomAccessFile writer = new java.io.RandomAccessFile(source, "rw")) {
              writer.seek(8192); writer.write('y');
            }
          }
        }));
    assertTrue(source.exists());
    assertTrue(target.exists());
  }

  @Test public void replacementSymlinkCannotRedirectPermissionChanges() throws Exception {
    File source = source("executable-stage", "script");
    Files.setPosixFilePermissions(source.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    File unrelated = source("unrelated", "private");
    java.util.Set<java.nio.file.attribute.PosixFilePermission> before = Files.getPosixFilePermissions(unrelated.toPath());
    File target = new File(temporary.getRoot(), "race-target");
    assertThrows(ProjectFileIO.IncompleteCreationException.class, () -> ProjectFileIO.copyNew(source, target, count -> {
      Files.delete(target.toPath());
      Files.createSymbolicLink(target.toPath(), unrelated.toPath());
    }));
    assertEquals("private", text(unrelated));
    assertEquals(before, Files.getPosixFilePermissions(unrelated.toPath()));
  }

  @Test public void executableModeIsSetAtExclusiveCreationWithoutPathChmod() throws Exception {
    File source = source("script-stage", "script");
    java.util.Set<java.nio.file.attribute.PosixFilePermission> mode = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
    Files.setPosixFilePermissions(source.toPath(), mode);
    File target = new File(temporary.getRoot(), "script");
    ProjectFileIO.copyNew(source, target);
    assertEquals(mode, Files.getPosixFilePermissions(target.toPath()));
  }

  @Test public void interruptedCreationReturnsPartialAndRetainsDurableEvidenceWithoutReplay() throws Exception {
    ProjectScope scope = new ProjectScopeStore(temporary.newFolder()).open("partial-create");
    String complete = String.join("", java.util.Collections.nCopies(20000, "x"));
    ProjectMutationService service = new ProjectMutationService(262144,
        new ProjectMutationService.CommitObserver() {
          @Override public void beforeCommit(int index, String path) {}
          @Override public void afterCreationChunk(String path, long copied) throws IOException {
            throw new IOException("simulated storage failure");
          }
        });
    ProjectMutationService.Result result = service.apply(scope, "test", 0,
        java.util.Collections.singletonList(ProjectMutationService.Operation.add("nested/file", complete)), null);
    assertEquals(ProjectMutationService.Status.PARTIAL, result.status);
    assertTrue(result.applied.isEmpty());
    assertFalse(result.cleanupWarnings.isEmpty());
    assertEquals(1, scope.version());
    File target = scope.resolve("nested/file");
    assertTrue(target.length() > 0 && target.length() < complete.length());
    byte[] beforeRecovery = Files.readAllBytes(target.toPath());
    ProjectMutationJournal.RecoveryReport recovery = scope.mutationRecovery();
    assertEquals(1, recovery.entries.size());
    assertEquals(ProjectMutationJournal.RecoveryStatus.UNCERTAIN, recovery.entries.get(0).status);
    assertArrayEquals(beforeRecovery, Files.readAllBytes(target.toPath()));
    assertEquals(2, scope.resolve("nested").list().length);
    ProjectMutationService.Result retry = new ProjectMutationService().apply(scope, "test", scope.version(),
        java.util.Collections.singletonList(ProjectMutationService.Operation.add("nested/file", complete)), null);
    assertEquals(ProjectMutationService.Status.CONFLICT, retry.status);
    assertArrayEquals(beforeRecovery, Files.readAllBytes(target.toPath()));
  }

}
