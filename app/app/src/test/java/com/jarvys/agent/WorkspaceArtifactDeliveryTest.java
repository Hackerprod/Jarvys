package com.jarvys.agent;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.jarvys.agent.coding.ArtifactSnapshotIO;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import com.jarvys.agent.workspaces.WorkspaceRecord;
import com.jarvys.agent.workspaces.WorkspaceRegistry;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Synthetic local bytes only; no provider, network, download, install or execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class WorkspaceArtifactDeliveryTest {
    @org.junit.Rule public final ChatTestStore chatTestStore = new ChatTestStore();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String CENTRAL = WorkspaceIdentity.CENTRAL_SESSION;
    private Context context;
    private File files;
    private WorkspaceRegistry registry;
    private WorkspaceRecord source;
    private WorkspaceStore workspace;
    private LocalRunStore ledger;
    private DeliveredArtifactStore artifacts;
    private Object priorSecrets;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME);
        registry = new WorkspaceRegistry(context);
        source = registry.createWorkspace("delivery-source", "Synthetic workspace");
        files = chatTestStore.newFilesDirectory();
        java.lang.reflect.Field singleton = SecretStore.class.getDeclaredField("singleton");
        singleton.setAccessible(true);
        priorSecrets = singleton.get(null);
        singleton.set(null, new SecretStore(context.getSharedPreferences("workspace-delivery-fixture", Context.MODE_PRIVATE)));
        workspace = workspace(source);
        ledger = chatTestStore.localRunStore(files);
        artifacts = new DeliveredArtifactStore(files);
        ledger.appendConversationMessage(CENTRAL, "user", "Attach the workspace file");
        AgentRunUiState.resetSession(CENTRAL);
    }

    @After public void tearDown() throws Exception {
        registry.close();
        context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME);
        java.lang.reflect.Field singleton = SecretStore.class.getDeclaredField("singleton");
        singleton.setAccessible(true); singleton.set(null, priorSecrets);
        AgentRunUiState.resetSession(CENTRAL);
        ArtifactOsShadow.reset();
    }

    private WorkspaceStore workspace(WorkspaceRecord record) {
        return new WorkspaceStore(new StorageGeneration(files),
                WorkspaceStore.projectIdForSession(record.getExecutionSessionId()), null, null, record.getExecutionSessionId());
    }
    private WorkspaceArtifactDelivery service() { return new WorkspaceArtifactDelivery(files, registry); }
    private JSONObject deliver(String path, String text) throws Exception {
        return service().deliver(source.getWorkspaceId(), path, hash(text), null, CancellationToken.cancellable(), () -> { });
    }
    private static String hash(String text) throws Exception { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    private static String hash(byte[] bytes) throws Exception { return ArtifactSnapshotIO.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private long count(String session) {
        return ledger.readConversationTimeline(session).stream().filter(event -> event.getDeliveredArtifact() != null).count();
    }
    private ChatAttachment attached() {
        return ledger.readConversationTimeline(CENTRAL).stream().filter(event -> event.getDeliveredArtifact() != null)
                .findFirst().get().getDeliveredArtifact();
    }
    private long manifests() {
        File[] entries = new File(files, "jarvys/" + StorageGeneration.NAME + "/delivered/" + CENTRAL).listFiles();
        return entries == null ? 0 : Arrays.stream(entries).filter(file -> file.getName().endsWith(".json")).count();
    }
    private void mutate(String column, String value) {
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).getPath(),
                null, SQLiteDatabase.OPEN_READWRITE)) {
            database.execSQL("UPDATE workspace SET " + column + "=? WHERE workspace_id=?", new Object[] {value, source.getWorkspaceId()});
        }
    }

    @Test public void registeredCodingApkIsAttachedOnlyToCentralAndSurvivesSourceEdit() throws Exception {
        byte[] bytes = new byte[1024 * 1024 + 13];
        new java.util.Random(43).nextBytes(bytes);
        File original = new File(workspace.codingProjectScope().rootDirectory(), "factory.apk");
        Files.write(original.toPath(), bytes);
        JSONObject receipt = service().deliver(source.getWorkspaceId(), "/project/factory.apk", hash(bytes), "factory.apk",
                CancellationToken.cancellable(), () -> { });
        assertTrue(receipt.getBoolean("attached"));
        assertEquals(source.getWorkspaceId(), receipt.getString("workspace_id"));
        assertEquals(hash(bytes), receipt.getString("source_sha256"));
        assertEquals(hash(bytes), receipt.getString("sha256"));
        assertEquals(CENTRAL, receipt.getString("destination"));
        assertEquals("/project/factory.apk", receipt.getString("source_path"));
        assertEquals("application/vnd.android.package-archive", attached().mimeType);
        assertEquals(1, count(CENTRAL));
        assertEquals(0, count(source.getExecutionSessionId()));
        assertEquals(0, count("private-chat"));
        assertFalse(receipt.toString().contains(files.getAbsolutePath()));
        Files.write(original.toPath(), "new workspace bytes".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(bytes, Files.readAllBytes(artifacts.resolve(CENTRAL, attached()).toPath()));
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolve(source.getExecutionSessionId(), attached()));
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolve("private-chat", attached()));
        assertEquals(1, AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream()
                .filter(event -> event.getDeliveredArtifact() != null).count());
    }

    @Test public void unchangedRetryAcrossServiceRecreationIsIdempotentAndChangedBytesHaveNewIdentity() throws Exception {
        workspace.write("report.txt", "first");
        JSONObject first = deliver("report.txt", "first");
        JSONObject repeated = deliver("report.txt", "first");
        assertEquals(first.getString("artifact_id"), repeated.getString("artifact_id"));
        assertEquals(1, count(CENTRAL));
        workspace.write("report.txt", "second");
        JSONObject changed = deliver("report.txt", "second");
        assertNotEquals(first.getString("artifact_id"), changed.getString("artifact_id"));
        assertEquals(2, count(CENTRAL));
    }

    @Test public void htmlPreviewCapturesImmutableWorkspaceAssetsAndNeverFollowsPrivateMounts() throws Exception {
        String html = "<!doctype html><html><head><link rel='stylesheet' href='style.css'></head><body>Preview</body></html>";
        workspace.write("site/index.html", html);
        workspace.write("site/style.css", "body{color:blue}");
        JSONObject receipt = deliver("site/index.html", html);
        assertTrue(receipt.getBoolean("preview_available"));
        assertEquals(2, receipt.getInt("preview_file_count"));
        assertEquals(deliver("site/index.html", html).getString("artifact_id"), receipt.getString("artifact_id"));
        workspace.write("site/style.css", "body{color:red}");
        HtmlPreviewDescriptor preview = artifacts.resolvePreview(CENTRAL, receipt.getString("preview_id"));
        assertEquals(2, preview.fileCount);
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolvePreview("private-chat", receipt.getString("preview_id")));
        assertThrows(IOException.class, () -> artifacts.openPreview(CENTRAL, preview.token, "memory/private.md"));
    }

    @Test public void unknownWorkspaceFailsBeforeOpeningSourceOrCreatingArtifactStorage() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        ArtifactOsShadow.beforeOpen = opened::incrementAndGet;
        assertThrows(IllegalArgumentException.class, () -> service().deliver(UUID.randomUUID().toString(), "/project/report.txt",
                hash("secret"), null, CancellationToken.cancellable(), () -> { }));
        assertEquals(0, opened.get());
        assertFalse(new File(files, "jarvys/" + StorageGeneration.NAME + "/delivered").exists());
        assertEquals(0, count(CENTRAL));
    }

    @Test public void wrongOwnerAndNoncanonicalExecutionSessionFailBeforeSourceRead() throws Exception {
        workspace.write("report.txt", "safe");
        AtomicInteger opened = new AtomicInteger();
        ArtifactOsShadow.beforeOpen = opened::incrementAndGet;
        mutate("owner_central_session_id", "private-chat");
        assertThrows(RuntimeException.class, () -> deliver("report.txt", "safe"));
        mutate("owner_central_session_id", CENTRAL);
        mutate("execution_session_id", "private-chat");
        assertThrows(RuntimeException.class, () -> deliver("report.txt", "safe"));
        assertEquals(0, opened.get());
        assertEquals(0, count(CENTRAL));
        assertEquals(0, manifests());
    }

    @Test public void anotherWorkspaceOrPrivateChatIsNeverUsedAsFallbackSource() throws Exception {
        WorkspaceRecord other = registry.createWorkspace("other-source", "Other workspace");
        workspace(other).write("report.txt", "other workspace");
        WorkspaceStore privateChat = new WorkspaceStore(new StorageGeneration(files),
                WorkspaceStore.projectIdForSession("private-chat"), null, null, "private-chat");
        privateChat.write("report.txt", "private sentinel");
        assertThrows(IOException.class, () -> deliver("report.txt", "other workspace"));
        assertThrows(IOException.class, () -> deliver("report.txt", "private sentinel"));
        assertEquals(0, count(CENTRAL));
        assertEquals("private sentinel", privateChat.read("report.txt"));
    }

    @Test public void staleChecksumRejectsBeforeAnyImmutableCommitOrAttachment() throws Exception {
        workspace.write("report.txt", "current");
        IOException failure = assertThrows(IOException.class, () -> deliver("report.txt", "older"));
        assertTrue(failure.getMessage().contains("changed"));
        assertEquals(0, manifests());
        assertEquals(0, count(CENTRAL));
        assertTrue(deliver("report.txt", "current").getBoolean("attached"));
    }

    @Test public void initialPolicyDenialHasNoSourceReadsOrStorageEffects() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        ArtifactOsShadow.beforeOpen = opened::incrementAndGet;
        assertThrows(SecurityException.class, () -> service().deliver(source.getWorkspaceId(), "/project/report.txt", hash("safe"), null,
                CancellationToken.cancellable(), () -> { throw new SecurityException("Revoked"); }));
        assertEquals(0, opened.get());
        assertFalse(new File(files, "jarvys/" + StorageGeneration.NAME + "/coding-projects").exists());
        assertFalse(new File(files, "jarvys/" + StorageGeneration.NAME + "/delivered").exists());
    }

    @Test public void policyRevocationDuringCopyPreventsImmutableCommitAndAttachment() throws Exception {
        workspace.write("report.txt", "safe");
        AtomicBoolean revoked = new AtomicBoolean();
        ArtifactOsShadow.afterOpen = () -> revoked.set(true);
        assertThrows(SecurityException.class, () -> service().deliver(source.getWorkspaceId(), "report.txt", hash("safe"), null,
                CancellationToken.cancellable(), () -> { if (revoked.get()) throw new SecurityException("Revoked"); }));
        assertTrue(revoked.get());
        assertEquals(0, manifests());
        assertEquals(0, count(CENTRAL));
    }

    @Test public void registryOwnerMutationDuringCopyPreventsImmutableCommitAndAttachment() throws Exception {
        workspace.write("report.txt", "safe");
        ArtifactOsShadow.afterOpen = () -> mutate("owner_central_session_id", "private-chat");
        assertThrows(RuntimeException.class, () -> deliver("report.txt", "safe"));
        assertEquals(0, manifests());
        assertEquals(0, count(CENTRAL));
        assertEquals(0, count("private-chat"));
    }

    @Test public void revocationAfterImmutableCommitPreventsCentralAppendAndLaterAuthorizedRetryCoalesces() throws Exception {
        workspace.write("report.txt", "safe");
        assertThrows(SecurityException.class, () -> service().deliver(source.getWorkspaceId(), "report.txt", hash("safe"), null,
                CancellationToken.cancellable(), () -> { if (manifests() > 0) throw new SecurityException("Revoked before append"); }));
        assertEquals(1, manifests());
        assertEquals(0, count(CENTRAL));
        assertTrue(deliver("report.txt", "safe").getBoolean("attached"));
        assertTrue(deliver("report.txt", "safe").getBoolean("attached"));
        assertEquals(1, count(CENTRAL));
        assertEquals(1, manifests());
    }

    @Test public void stopBeforeCopyDuringCopyAndBeforeAppendNeverProduceAttachment() throws Exception {
        workspace.write("report.txt", "safe");
        CancellationToken stopped = CancellationToken.cancellable(); stopped.cancel();
        assertThrows(CancellationException.class, () -> service().deliver(source.getWorkspaceId(), "report.txt", hash("safe"), null, stopped, () -> { }));
        CancellationToken during = CancellationToken.cancellable();
        ArtifactOsShadow.afterOpen = during::cancel;
        assertThrows(CancellationException.class, () -> service().deliver(source.getWorkspaceId(), "report.txt", hash("safe"), null, during, () -> { }));
        assertEquals(0, manifests());
        CancellationToken beforeAppend = CancellationToken.cancellable();
        assertThrows(CancellationException.class, () -> service().deliver(source.getWorkspaceId(), "report.txt", hash("safe"), null, beforeAppend,
                () -> { if (manifests() > 0) beforeAppend.cancel(); }));
        assertEquals(0, count(CENTRAL));
    }

    @Test public void unsafePathsSymlinksOversizeAndCrewAreRejectedWithoutAttachment() throws Exception {
        for (String path : Arrays.asList("../secret", "/private-chat/report.txt", "memory/secret", "skills/secret", "attachments/report.txt",
                "./report.txt", "/project/../secret", "/project/.jarvys-tmp-hidden", "file:report.txt", "site//index.html")) {
            assertThrows(path, Exception.class, () -> deliver(path, "secret"));
        }
        workspace.write("base.txt", "safe");
        File root = new File(files, "jarvys/" + StorageGeneration.NAME + "/workspaces/" + WorkspaceStore.projectIdForSession(source.getExecutionSessionId()));
        File outside = temporary.newFile("private-sentinel.txt");
        Files.write(outside.toPath(), "sentinel".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(root, "link.txt").toPath(), outside.toPath());
        assertThrows(IOException.class, () -> deliver("link.txt", "sentinel"));
        File huge = new File(workspace.codingProjectScope().rootDirectory(), "huge.apk");
        try (RandomAccessFile file = new RandomAccessFile(huge, "rw")) { file.setLength(DeliveredArtifactStore.MAX_BYTES + 1); }
        assertThrows(IOException.class, () -> deliver("/project/huge.apk", "irrelevant"));
        assertThrows(IllegalArgumentException.class, () -> service().deliver(source.getWorkspaceId(), "base.txt", hash("safe"), null,
                CancellationToken.crewChild(), () -> { }));
        assertEquals(0, count(CENTRAL));
        assertEquals(0, manifests());
    }

    @Test public void centralDeletionIsRejectedAndOrdinarySnapshotStillRejectsCrossChat() throws Exception {
        workspace.write("report.txt", "safe");
        assertThrows(IOException.class, () -> artifacts.snapshot(CENTRAL, workspace, "report.txt", null, CancellationToken.cancellable()));
        assertThrows(IOException.class, () -> artifacts.snapshot("private-chat", workspace, "report.txt", null, CancellationToken.cancellable()));
        assertThrows(IllegalStateException.class, () -> ledger.deleteConversation(CENTRAL));
        deliver("report.txt", "safe");
        assertEquals(1, manifests());
    }

    @Test public void internalTransferRejectsDifferentWorkspaceStoreIdentity() throws Exception {
        WorkspaceRecord other = registry.createWorkspace("other-source", "Other workspace");
        WorkspaceStore wrongSource = workspace(other);
        wrongSource.write("report.txt", "other");
        AtomicInteger opened = new AtomicInteger(); ArtifactOsShadow.beforeOpen = opened::incrementAndGet;
        assertThrows(IllegalArgumentException.class, () -> artifacts.snapshotWorkspace(registry, source.getWorkspaceId(), wrongSource,
                "report.txt", null, hash("other"), CancellationToken.cancellable(), () -> { }));
        assertEquals(0, opened.get());
        assertEquals(0, manifests());
    }

    @Test public void inspectReturnsNativeHashForOrdinaryFileWithoutAttachmentThenBindsDelivery() throws Exception {
        workspace.write("report.txt", "native bytes");
        JSONObject metadata = service().inspect(source.getWorkspaceId(), "report.txt", CancellationToken.cancellable(), () -> { });
        assertEquals(4, metadata.length());
        assertEquals(source.getWorkspaceId(), metadata.getString("workspace_id"));
        assertEquals("report.txt", metadata.getString("path"));
        assertEquals(hash("native bytes"), metadata.getString("sha256"));
        assertEquals(12, metadata.getLong("size_bytes"));
        assertEquals(0, count(CENTRAL));
        assertEquals(0, count(source.getExecutionSessionId()));
        assertFalse(new File(files, "jarvys/" + StorageGeneration.NAME + "/delivered").exists());
        assertEquals(0, new File(files, "jarvys/" + StorageGeneration.NAME + "/workspace-inspections").listFiles().length);
        assertTrue(service().deliver(source.getWorkspaceId(), "report.txt", metadata.getString("sha256"), null,
                CancellationToken.cancellable(), () -> { }).getBoolean("attached"));
        workspace.write("report.txt", "changed bytes");
        assertThrows(IOException.class, () -> service().deliver(source.getWorkspaceId(), "report.txt", metadata.getString("sha256"), null,
                CancellationToken.cancellable(), () -> { }));
        assertEquals(1, count(CENTRAL));
    }

    @Test public void inspectProjectBinaryAndHiddenOrdinaryNamesUseExistingReaderRules() throws Exception {
        workspace.write(".config", "ordinary output");
        File project = workspace.codingProjectScope().rootDirectory();
        byte[] bytes = new byte[] { 0, 1, 2, (byte) 255 };
        Files.write(new File(project, ".config").toPath(), bytes);
        JSONObject ordinary = service().inspect(source.getWorkspaceId(), ".config", CancellationToken.cancellable(), () -> { });
        JSONObject coding = service().inspect(source.getWorkspaceId(), "/project/.config", CancellationToken.cancellable(), () -> { });
        assertEquals(hash("ordinary output"), ordinary.getString("sha256"));
        assertEquals(hash(bytes), coding.getString("sha256"));
        assertEquals(4, coding.getLong("size_bytes"));
        assertEquals(0, count(CENTRAL));
        assertTrue(service().deliver(source.getWorkspaceId(), "/project/.config", coding.getString("sha256"), "config.bin",
                CancellationToken.cancellable(), () -> { }).getBoolean("attached"));
        assertArrayEquals(bytes, Files.readAllBytes(artifacts.resolve(CENTRAL, attached()).toPath()));
    }

    @Test public void inspectRevocationOrStopDuringCopyDisclosesNoMetadataAndRemovesTemporaryBytes() throws Exception {
        workspace.write("report.txt", "private file bytes");
        AtomicBoolean revoked = new AtomicBoolean();
        ArtifactOsShadow.afterOpen = () -> revoked.set(true);
        assertThrows(SecurityException.class, () -> service().inspect(source.getWorkspaceId(), "report.txt", CancellationToken.cancellable(),
                () -> { if (revoked.get()) throw new SecurityException("Revoked before disclosure"); }));
        assertEquals(0, new File(files, "jarvys/" + StorageGeneration.NAME + "/workspace-inspections").listFiles().length);
        CancellationToken token = CancellationToken.cancellable();
        ArtifactOsShadow.afterOpen = token::cancel;
        assertThrows(CancellationException.class, () -> service().inspect(source.getWorkspaceId(), "report.txt", token, () -> { }));
        assertEquals(0, new File(files, "jarvys/" + StorageGeneration.NAME + "/workspace-inspections").listFiles().length);
        assertEquals(0, count(CENTRAL));
        assertFalse(new File(files, "jarvys/" + StorageGeneration.NAME + "/delivered").exists());
    }

    @Test public void inspectUnknownOrPrivateSourceFailsWithoutReadingOtherChats() throws Exception {
        AtomicInteger opened = new AtomicInteger(); ArtifactOsShadow.beforeOpen = opened::incrementAndGet;
        assertThrows(IllegalArgumentException.class, () -> service().inspect(UUID.randomUUID().toString(), "report.txt",
                CancellationToken.cancellable(), () -> { }));
        assertFalse(new File(files, "jarvys/" + StorageGeneration.NAME + "/workspace-inspections").exists());
        assertThrows(IOException.class, () -> service().inspect(source.getWorkspaceId(), "memory/private.md",
                CancellationToken.cancellable(), () -> { }));
        assertEquals(0, opened.get());
        assertEquals(0, new File(files, "jarvys/" + StorageGeneration.NAME + "/workspace-inspections").listFiles().length);
        assertEquals(0, count(CENTRAL));
    }

    @Test public void sourceNamespaceAndWorkspaceArePartOfImmutableIdentity() throws Exception {
        workspace.write("report.txt", "identical");
        Files.write(new File(workspace.codingProjectScope().rootDirectory(), "report.txt").toPath(),
                "identical".getBytes(StandardCharsets.UTF_8));
        JSONObject ordinary = deliver("report.txt", "identical");
        JSONObject coding = deliver("/project/report.txt", "identical");
        assertNotEquals(ordinary.getString("artifact_id"), coding.getString("artifact_id"));
        WorkspaceRecord other = registry.createWorkspace("another-source", "Another workspace");
        workspace(other).write("report.txt", "identical");
        JSONObject second = service().deliver(other.getWorkspaceId(), "report.txt", hash("identical"), null,
                CancellationToken.cancellable(), () -> { });
        assertNotEquals(ordinary.getString("artifact_id"), second.getString("artifact_id"));
        assertEquals(3, count(CENTRAL));
    }

    @Test public void canonicalSessionCannotBePairedWithAnotherWorkspaceProjectId() throws Exception {
        WorkspaceStore forged = new WorkspaceStore(new StorageGeneration(files),
                WorkspaceStore.projectIdForSession("private-chat"), null, null, source.getExecutionSessionId());
        forged.write("report.txt", "private sentinel");
        AtomicInteger opened = new AtomicInteger(); ArtifactOsShadow.beforeOpen = opened::incrementAndGet;
        assertThrows(IllegalArgumentException.class, () -> artifacts.snapshotWorkspace(registry, source.getWorkspaceId(), forged,
                "report.txt", null, hash("private sentinel"), CancellationToken.cancellable(), () -> { }));
        assertEquals(0, opened.get());
        assertEquals(0, manifests());
    }

    @Test public void concurrentUnchangedDeliveriesShareOneReceiptWithoutInvertingStopAndStoreLocks() throws Exception {
        workspace.write("report.txt", "concurrent");
        CancellationToken token = CancellationToken.cancellable();
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> action = () -> service().deliver(source.getWorkspaceId(), "report.txt", hash("concurrent"), null,
                    token, () -> { }).getString("artifact_id");
            java.util.concurrent.Future<String> first = workers.submit(action);
            java.util.concurrent.Future<String> second = workers.submit(action);
            assertEquals(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, count(CENTRAL));
        } finally { token.cancel(); workers.shutdownNow(); }
    }


    @Test(timeout = 20000) public void realStopControllerOverlappingOrdinaryConnectorAndThumbnailWritersComplete() throws Exception {
        realStopControllerOverlap(false);
    }

    @Test(timeout = 20000) public void realStopWhileOrdinaryConnectorAndThumbnailWritersAreQueuedPreventsPublication() throws Exception {
        realStopControllerOverlap(true);
    }

    private void realStopControllerOverlap(boolean stopWhileQueued) throws Exception {
        StopController controller = StopController.getInstance();
        controller.stopRun();
        CancellationToken token = controller.beginRun();
        assertNotNull("A real foreground run owns the synchronized STOP controller", token);
        workspace.write("overlap.txt", "overlap bytes");
        String thumbSession = "thumbnail-overlap";
        ChatAttachment html = artifacts.snapshotBytes(thumbSession, "<html><body>Preview</body></html>".getBytes(StandardCharsets.UTF_8),
                "preview.html", "text/html", token);
        ledger.appendDeliveredFile(thumbSession, html);
        HtmlThumbnailStore thumbnails = new HtmlThumbnailStore(files);
        HtmlThumbnailStore.Key key = thumbnails.prepare(thumbSession, HtmlPreviewDescriptor.TOKEN_PREFIX + html.id, 1, 1, "overlap-v1");
        byte[] png = overlapPng();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        java.util.List<Async<?>> operations = new java.util.ArrayList<>();
        try {
            Async<JSONObject> publication = new Async<>("central-publication", () -> service().deliver(source.getWorkspaceId(),
                    "overlap.txt", hash("overlap bytes"), null, token, () -> {
                        if (manifests() > 0 && paused.compareAndSet(false, true)) {
                            assertTrue(Thread.holdsLock(DeliveredArtifactStore.DELIVERY_ACTION_LOCK));
                            ready.countDown();
                            try { assertTrue("Release publication", proceed.await(8, java.util.concurrent.TimeUnit.SECONDS)); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException("Interrupted test publication"); }
                        }
                    }));
            operations.add(publication);
            assertTrue("Publication reached the gap before its final STOP/ledger commit", ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Async<ChatAttachment> ordinary = new Async<>("ordinary-snapshot", () -> artifacts.snapshot(source.getExecutionSessionId(),
                    workspace, "overlap.txt", null, token));
            Async<ChatAttachment> connector = new Async<>("connector-snapshot", () -> artifacts.snapshotBytes("connector-overlap",
                    "connector bytes".getBytes(StandardCharsets.UTF_8), "connector.txt", "text/plain", token));
            Async<Boolean> thumbnail = new Async<>("thumbnail-write", () -> thumbnails.write(key, png, token));
            operations.add(ordinary); operations.add(connector); operations.add(thumbnail);
            // All three would acquire artifact-store -> synchronized StopController.isActive without
            // this outer gate, opposing the central append's STOP -> artifact-store verification.
            awaitDeliveryGate(ordinary.thread);
            awaitDeliveryGate(connector.thread);
            awaitDeliveryGate(thumbnail.thread);
            if (stopWhileQueued) {
                Async<Boolean> stop = new Async<>("stop-queued-deliveries", controller::stopRun);
                operations.add(stop);
                assertTrue("STOP must not wait for the shared delivery gate", stop.value());
                assertTrue(token.isCancellationRequested());
            }
            proceed.countDown();
            if (stopWhileQueued) {
                assertCancelled(publication); assertCancelled(ordinary); assertCancelled(connector); assertCancelled(thumbnail);
                assertEquals(0, count(CENTRAL));
                assertEquals(1, manifests()); // Immutable commit preceded STOP; no visible attachment did.
                assertFalse(new File(files, "jarvys/" + StorageGeneration.NAME + "/delivered/connector-overlap").exists());
                assertNull(thumbnails.read(key));
            } else {
                assertTrue(publication.value().getBoolean("attached"));
                assertNotNull(ordinary.value()); assertNotNull(connector.value()); assertTrue(thumbnail.value());
                assertEquals(1, count(CENTRAL));
                assertArrayEquals(png, thumbnails.read(key));
            }
        } finally {
            proceed.countDown();
            for (Async<?> operation : operations) operation.thread.interrupt();
            for (Async<?> operation : operations) operation.thread.join(1000);
            // Daemon workers and bounded cleanup keep a regression from stranding the build process.
            Async<Boolean> stop = new Async<>("stop-overlap-cleanup", controller::stopRun);
            stop.thread.join(1000);
            assertFalse("STOP cleanup must complete", stop.thread.isAlive());
            for (Async<?> operation : operations) assertFalse("Worker did not terminate: " + operation.thread.getName(), operation.thread.isAlive());
        }
    }

    private static void awaitDeliveryGate(Thread thread) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        // Android's compile boot classpath omits java.lang.management. Robolectric runs on a
        // host JVM, so use its public management API reflectively, never JDK-internal classes.
        Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
        Class<?> threadMxBean = Class.forName("java.lang.management.ThreadMXBean");
        Class<?> threadInfo = Class.forName("java.lang.management.ThreadInfo");
        Class<?> lockInfo = Class.forName("java.lang.management.LockInfo");
        Object threads = managementFactory.getMethod("getThreadMXBean").invoke(null);
        java.lang.reflect.Method getThreadInfo = threadMxBean.getMethod("getThreadInfo", long.class);
        java.lang.reflect.Method getThreadState = threadInfo.getMethod("getThreadState");
        java.lang.reflect.Method getLockInfo = threadInfo.getMethod("getLockInfo");
        java.lang.reflect.Method getIdentityHashCode = lockInfo.getMethod("getIdentityHashCode");
        while (System.nanoTime() < deadline) {
            Object info = getThreadInfo.invoke(threads, thread.getId());
            Object blockedLock = info == null ? null : getLockInfo.invoke(info);
            if (info != null && getThreadState.invoke(info) == Thread.State.BLOCKED && blockedLock != null
                    && ((Integer) getIdentityHashCode.invoke(blockedLock)).intValue()
                        == System.identityHashCode(DeliveredArtifactStore.DELIVERY_ACTION_LOCK)) return;
            if (!thread.isAlive()) break;
            Thread.sleep(2);
        }
        fail("Expected writer queued on the shared delivery gate: " + thread.getName());
    }

    private static void assertCancelled(Async<?> operation) throws Exception {
        java.util.concurrent.ExecutionException error = assertThrows(java.util.concurrent.ExecutionException.class, operation::value);
        assertTrue("Expected STOP cancellation: " + error.getCause(), error.getCause() instanceof CancellationException);
    }

    private static final class Async<T> {
        final java.util.concurrent.FutureTask<T> result;
        final Thread thread;
        Async(String name, java.util.concurrent.Callable<T> action) {
            result = new java.util.concurrent.FutureTask<>(action);
            thread = new Thread(result, name); thread.setDaemon(true); thread.start();
        }
        T value() throws Exception { return result.get(5, java.util.concurrent.TimeUnit.SECONDS); }
    }

    private static byte[] overlapPng() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.write(new byte[] { (byte)137, 80, 78, 71, 13, 10, 26, 10 });
        java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream output = new java.io.DataOutputStream(header);
        output.writeInt(1); output.writeInt(1); output.write(new byte[] { 8, 6, 0, 0, 0 });
        overlapChunk(bytes, "IHDR", header.toByteArray());
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream deflate = new java.util.zip.DeflaterOutputStream(compressed)) {
            deflate.write(new byte[] { 0, 50, 60, 70, (byte)255 });
        }
        overlapChunk(bytes, "IDAT", compressed.toByteArray()); overlapChunk(bytes, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void overlapChunk(java.io.ByteArrayOutputStream bytes, String type, byte[] data) throws Exception {
        java.io.DataOutputStream output = new java.io.DataOutputStream(bytes);
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(data.length); output.write(name); output.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(name); crc.update(data);
        output.writeInt((int)crc.getValue());
    }

}
