package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class DeliveredArtifactTest {
    @org.junit.Rule public final ChatTestStore chatTestStore = new ChatTestStore(true, true);
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File files;
    private LocalRunStore ledger;
    private DeliveredArtifactStore artifacts;
    private WorkspaceStore workspace;
    private Object priorSecrets;
    private final String session = "delivery-chat";
    @Before public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        java.lang.reflect.Field singleton = SecretStore.class.getDeclaredField("singleton"); singleton.setAccessible(true);
        priorSecrets = singleton.get(null);
        singleton.set(null, new SecretStore(context.getSharedPreferences("delivery-fixture", Context.MODE_PRIVATE)));
        files = chatTestStore.newFilesDirectory(); ledger = chatTestStore.localRunStore(files);
        artifacts = new DeliveredArtifactStore(files);
        workspace = new WorkspaceStore(new StorageGeneration(files), WorkspaceStore.projectIdForSession(session), null, null, session);
        ledger.appendConversationMessage(session, "user", "Attach the report");
    }
    @After public void tearDown() throws Exception {
        java.lang.reflect.Field singleton = SecretStore.class.getDeclaredField("singleton"); singleton.setAccessible(true); singleton.set(null,priorSecrets);
        ArtifactOsShadow.reset();
    }
    private DeliverFileTool tool() { return new DeliverFileTool(session, workspace, artifacts, ledger); }
    private Map<String,Object> args(String path) { return Collections.singletonMap("path", path); }
    private ChatAttachment delivered() {
        return chatTestStore.localRunStore(files).readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null)
                .findFirst().get().getDeliveredArtifact();
    }
    @Test public void nativeSnapshotSurvivesWorkspaceEditAndProcessRecreation() throws Exception {
        workspace.write("report.txt", "original");
        CoreToolResult result = tool().execute(args("./report.txt"), CancellationToken.uncancellable());
        assertTrue(result.content, result.success);
        ChatAttachment file = delivered(); assertEquals("report.txt", file.name); assertEquals(8, file.sizeBytes);
        workspace.write("report.txt", "replacement");
        File saved = new DeliveredArtifactStore(files).resolve(session, file);
        assertEquals("original", new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
        assertFalse(result.content.contains(files.getAbsolutePath()));
        assertFalse(result.content.contains("original"));
        assertEquals(artifacts.sha256(session,file), new JSONObject(result.content).getString("sha256"));
    }
    @Test @Config(sdk = 24, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
    public void api24UsesDescriptorCheckedStreamingWithoutNioProjectCalls() throws Exception {
        workspace.write("legacy.txt", "Android 7 snapshot");
        CoreToolResult result = tool().execute(args("legacy.txt"), CancellationToken.uncancellable());
        assertTrue(result.content,result.success);
        assertEquals("Android 7 snapshot", new String(Files.readAllBytes(artifacts.resolve(session,delivered()).toPath()),StandardCharsets.UTF_8));
    }
    @Test public void longCompactedConversationKeepsDurableFileCardAndChecksumWithoutReexecution() throws Exception {
        workspace.write("report.txt", "original bytes");
        assertTrue(tool().execute(args("report.txt"),CancellationToken.uncancellable()).success);
        ChatAttachment artifact = delivered(); String sha = artifacts.sha256(session,artifact);
        for (int i=0;i<80;i++) { ledger.appendConversationMessage(session,"user","Follow-up " + i); ledger.appendConversationMessage(session,"assistant","Discussed " + i); }
        ledger.appendCompaction(session,"Earlier work produced a report attachment.",150,"automatic","all",150);
        workspace.write("report.txt","new workspace version");
        LocalRunStore reopened = chatTestStore.localRunStore(files);
        assertEquals(1,reopened.readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact()!=null).count());
        assertEquals(artifact,reopened.findChatFile(session,"delivered",artifact.id));
        assertEquals(sha,new DeliveredArtifactStore(files).sha256(session,artifact));
        assertEquals("original bytes",new String(Files.readAllBytes(artifacts.resolve(session,artifact).toPath()),StandardCharsets.UTF_8));
    }

    @Test public void staleTimelineRefreshKeepsLiveDeliveredFileAndFreshRefreshDeduplicates() throws Exception {
        workspace.write("live.txt","safe");
        AgentRunUiState.resetSession(session);
        AgentRunUiState.beginRun(session,"Attach live file",Collections.emptyList());
        java.util.List<AgentRunUiEvent> before = ledger.readConversationTimeline(session);
        assertTrue(tool().execute(args("live.txt"),CancellationToken.uncancellable()).success);
        AgentRunUiState.refreshPersistedSession(session,before);
        assertEquals(1,AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream().filter(e -> e.getDeliveredArtifact()!=null).count());
        AgentRunUiState.refreshPersistedSession(session,ledger.readConversationTimeline(session));
        assertEquals(1,AgentRunUiState.INSTANCE.getState().getValue().getEvents().stream().filter(e -> e.getDeliveredArtifact()!=null).count());
        AgentRunUiState.resetSession(session);
    }

    @Test public void repeatedDeliveryCoalescesAndChangedVersionHasNewIdentity() throws Exception {
        workspace.write("same.txt", "one");
        assertTrue(tool().execute(args("same.txt"), CancellationToken.uncancellable()).success);
        String id = delivered().id;
        assertTrue(tool().execute(args("same.txt"), CancellationToken.uncancellable()).success);
        assertEquals(1, ledger.readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null).count());
        workspace.write("same.txt", "two");
        assertTrue(tool().execute(args("same.txt"), CancellationToken.uncancellable()).success);
        List<AgentRunUiEvent> events = ledger.readConversationTimeline(session);
        assertEquals(2, events.stream().filter(e -> e.getDeliveredArtifact() != null).count());
        assertNotEquals(id, events.get(events.size()-1).getDeliveredArtifact().id);
    }
    @Test public void binarySharedCodingAndFactoryOutputsAreAttachedWithoutDecodingOrExecution() throws Exception {
        com.jarvys.agent.coding.ProjectScope scope = workspace.codingProjectScope();
        File binary = new File(scope.rootDirectory(), "factory.apk");
        byte[] bytes = new byte[1024 * 1024 + 13]; new Random(42).nextBytes(bytes); Files.write(binary.toPath(), bytes);
        CoreToolResult result = tool().execute(args("/project/factory.apk"), CancellationToken.uncancellable());
        assertTrue(result.content, result.success); ChatAttachment file = delivered();
        assertEquals("application/vnd.android.package-archive", file.mimeType);
        assertArrayEquals(bytes, Files.readAllBytes(artifacts.resolve(session, file).toPath()));
        assertEquals(bytes.length, file.sizeBytes);
    }

    @Test public void moduleArtifactThumbnailAndBytesRestoreFromTheExistingRoomReference() throws Exception {
        byte[] presentationBytes = new byte[] { 80, 75, 3, 4, 1, 2, 3 };
        byte[] thumbnailBytes = pngFixture();
        ChatAttachment artifact = artifacts.snapshotBytes(session, presentationBytes, "report.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", CancellationToken.uncancellable());
        DeliveredArtifactStore.ThumbnailFile thumbnail = artifacts.snapshotThumbnailBytes(session, artifact,
                thumbnailBytes, "image/png", CancellationToken.uncancellable());
        String moduleId = "com.test.module.presentation";
        String manifest = "{\"schemaVersion\":1}";
        String thumbnailRef = ledger.appendDeliveredModuleArtifact(session, artifact,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", moduleId, manifest,
                "Quarterly report", thumbnail);

        assertNotNull(thumbnailRef);
        com.jarvys.agent.chatdb.ChatDatabaseBridge bridge = com.jarvys.agent.chatdb.ChatDatabaseBridge
                .findInstanceForFilesDirectory(files);
        assertNotNull(bridge);
        com.jarvys.agent.chatdb.FileRef reference = bridge.findArtifactThumbnailSync(session, artifact.id);
        assertEquals(thumbnailRef, reference.getId());
        assertArrayEquals(thumbnailBytes, Files.readAllBytes(
                artifacts.resolveThumbnail(session, artifact.id, reference).toPath()));
        assertArrayEquals(presentationBytes, Files.readAllBytes(artifacts.resolve(session, artifact).toPath()));

        AgentRunUiEvent restored = chatTestStore.localRunStore(files).readConversationTimeline(session).stream()
                .filter(event -> event.getPresentationArtifact() != null).findFirst().get();
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                restored.getPresentationArtifact().getArtifactType());
        assertEquals(moduleId, restored.getPresentationArtifact().getModuleId());
        assertEquals(thumbnailRef, restored.getPresentationArtifact().getThumbnailRef());
    }

    @Test public void moduleDeliveriesAreUniquePerRequestAndRetriesReuseThePersistedArtifactAndThumbnail() throws Exception {
        byte[] artifactBytes = new byte[] { 80, 75, 3, 4, 5, 6 };
        byte[] thumbnailBytes = pngFixture();
        String deliveryId = UUID.randomUUID().toString();
        ChatAttachment first = artifacts.snapshotModuleBytes(session, artifactBytes, "report.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                deliveryId, CancellationToken.uncancellable());
        DeliveredArtifactStore.ThumbnailFile firstThumbnail = artifacts.snapshotThumbnailBytes(session, first,
                thumbnailBytes, "image/png", CancellationToken.uncancellable());
        String firstRef = ledger.appendDeliveredModuleArtifact(session, first,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", "com.test.module.presentation",
                "{\"schemaVersion\":1}", "Quarterly report", firstThumbnail);

        // A second user request for identical output is a new card with its own delivery identity.
        ChatAttachment second = artifacts.snapshotModuleBytes(session, artifactBytes, "report.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                UUID.randomUUID().toString(), CancellationToken.uncancellable());
        assertNotEquals(first.id, second.id);
        DeliveredArtifactStore.ThumbnailFile secondThumbnail = artifacts.snapshotThumbnailBytes(session, second,
                thumbnailBytes, "image/png", CancellationToken.uncancellable());
        ledger.appendDeliveredModuleArtifact(session, second,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", "com.test.module.presentation",
                "{\"schemaVersion\":1}", "Quarterly report", secondThumbnail);

        // Re-opening the same delivery identity returns the stored copy, even after store recreation.
        DeliveredArtifactStore restoredStore = new DeliveredArtifactStore(files);
        ChatAttachment retry = restoredStore.snapshotModuleBytes(session, artifactBytes, "report.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", deliveryId,
                CancellationToken.uncancellable());
        assertEquals(first.id, retry.id);
        assertThrows(IOException.class, () -> restoredStore.snapshotModuleBytes(session,
                new byte[] { 80, 75, 3, 4, 5, 7 }, "report.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", deliveryId,
                CancellationToken.uncancellable()));
        DeliveredArtifactStore.ThumbnailFile changedRasterRetry = restoredStore.snapshotThumbnailBytes(session, retry,
                new byte[] { 1, 2, 3 }, "image/png", CancellationToken.uncancellable());
        assertEquals(firstThumbnail.sha256, changedRasterRetry.sha256);
        com.jarvys.agent.chatdb.ChatDatabaseBridge restoredBridge =
                com.jarvys.agent.chatdb.ChatDatabaseBridge.findInstanceForFilesDirectory(files);
        assertNotNull(restoredBridge);
        assertArrayEquals(thumbnailBytes, Files.readAllBytes(restoredStore.resolveThumbnail(session, retry.id,
                restoredBridge.findArtifactThumbnailSync(session, retry.id)).toPath()));
        assertEquals(firstRef, ledger.appendDeliveredModuleArtifact(session, retry,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", "com.test.module.presentation",
                "{\"schemaVersion\":1}", "Quarterly report", changedRasterRetry));

        assertEquals(2, ledger.readConversationTimeline(session).stream()
                .filter(event -> event.getPresentationArtifact() != null).count());
    }

    private byte[] pngFixture() throws IOException {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(38, 116, 184));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }
    @Test public void appPrivateAbsoluteTraversalMemorySkillsAndOtherChatCannotBeDelivered() throws Exception {
        File secret = new File(files, "secret-token"); Files.write(secret.toPath(), "sentinel".getBytes(StandardCharsets.UTF_8));
        for (String path : Arrays.asList(secret.getPath(), "../secret-token", "/memory/private.md", "/skills/private.md", "memory/private.md", "./memory/private.md", "skills/private.md", "attachments/private.txt", "/project/../secret-token"))
            assertFalse(path, tool().execute(args(path), CancellationToken.uncancellable()).success);
        workspace.write("owned.txt", "mine"); tool().execute(args("owned.txt"), CancellationToken.uncancellable());
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolve("another-chat", delivered()));
        assertNull(ledger.findChatFile("another-chat", "delivered", delivered().id));
        assertEquals("sentinel", new String(Files.readAllBytes(secret.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void symlinkFilesAndParentsFailClosedWithoutLeakingPrivateBytes() throws Exception {
        workspace.write("base.txt", "safe");
        File root = new File(files, "jarvys/" + StorageGeneration.NAME + "/workspaces/" + WorkspaceStore.projectIdForSession(session));
        File outside = chatTestStore.newFilesDirectory("private"); Files.write(new File(outside, "token").toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(root, "link").toPath(), new File(outside, "token").toPath());
        Files.createSymbolicLink(new File(root, "directory").toPath(), outside.toPath());
        assertFalse(tool().execute(args("link"), CancellationToken.uncancellable()).success);
        assertFalse(tool().execute(args("directory/token"), CancellationToken.uncancellable()).success);
        assertFalse(ledger.readConversationTimeline(session).stream().anyMatch(e -> e.getDeliveredArtifact() != null));
    }
    @Test public void descriptorIdentityRejectsSwapAndRestoreDuringOpen() throws Exception {
        workspace.write("folder/file.txt", "safe");
        File root = new File(files, "jarvys/" + StorageGeneration.NAME + "/workspaces/" + WorkspaceStore.projectIdForSession(session));
        File parent = new File(root,"folder"), kept = new File(root,"kept");
        ArtifactOsShadow.hookTargetPath = new File(parent, "file.txt").toPath();
        ArtifactOsShadow.beforeOpen = () -> { try {
            Files.move(parent.toPath(), kept.toPath());
            Files.createDirectory(parent.toPath());
            // Same-size replacement bytes ensure identity checks do not rely on size alone.
            Files.write(new File(parent,"file.txt").toPath(), "evil".getBytes(StandardCharsets.UTF_8));
        } catch(Exception e) { throw new RuntimeException(e); } };
        ArtifactOsShadow.afterClose = () -> { try {
            Files.delete(new File(parent,"file.txt").toPath());
            Files.delete(parent.toPath());
            Files.move(kept.toPath(), parent.toPath());
        } catch(Exception e) { throw new RuntimeException(e); } };
        assertFalse(tool().execute(args("folder/file.txt"), CancellationToken.uncancellable()).success);
        assertFalse(ledger.readConversationTimeline(session).stream().anyMatch(e -> e.getDeliveredArtifact()!=null));
        assertEquals("safe",workspace.read("folder/file.txt"));
    }

    @Test public void corruptMetadataBytesAndDeletedChatFailClosed() throws Exception {
        workspace.write("file.txt", "safe"); tool().execute(args("file.txt"), CancellationToken.uncancellable());
        ChatAttachment artifact = delivered(); File saved = artifacts.resolve(session, artifact);
        Files.write(saved.toPath(), "evil".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolve(session, artifact));
        assertTrue(ledger.deleteConversation(session));
        assertFalse(tool().execute(args("file.txt"), CancellationToken.uncancellable()).success);
        assertNull(ledger.findChatFile(session, "delivered", artifact.id));
    }
    @Test public void cancellationAndOversizeNeverProduceVisibleAttachment() throws Exception {
        workspace.write("file.txt", "safe"); CancellationToken cancelled = CancellationToken.cancellable(); cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> tool().execute(args("file.txt"), cancelled));
        File root = workspace.codingProjectScope().rootDirectory();
        try (RandomAccessFile huge = new RandomAccessFile(new File(root, "huge.zip"), "rw")) { huge.setLength(DeliveredArtifactStore.MAX_BYTES + 1); }
        assertFalse(tool().execute(args("/project/huge.zip"), CancellationToken.uncancellable()).success);
        assertFalse(ledger.readConversationTimeline(session).stream().anyMatch(e -> e.getDeliveredArtifact() != null));
    }
    @Test public void filenameAndMimeAreSanitizedWithoutTrustingArbitraryModelMetadata() throws Exception {
        workspace.write("file.txt", "safe"); Map<String,Object> request = new LinkedHashMap<>(args("file.txt"));
        request.put("filename", "../bad\nname.zip"); assertTrue(tool().execute(request, CancellationToken.uncancellable()).success);
        ChatAttachment artifact = delivered(); assertEquals(".._bad_name.zip", artifact.name); assertEquals("application/zip", artifact.mimeType);
        request.put("mime_type", "image/png"); assertFalse(tool().execute(request, CancellationToken.uncancellable()).success);
    }
    @Test public void toolIsActuallyRegisteredButUnavailableToDelegatedAndSessionlessScopes() {
        Context context = ApplicationProvider.getApplicationContext();
        CoreToolRegistry registry = new CoreAgentRuntime(context, "real-delivery", Collections.emptyList()).createTools();
        assertTrue(registry.names().contains("deliver_file"));
        assertFalse(registry.withoutAttachments().names().contains("deliver_file"));
        assertFalse(WorkspaceTools.forDelegatedAgent(registry.handlers()).stream().anyMatch(t -> t.declaration().name.equals("deliver_file")));
        assertFalse(registry.forDelegatedAgent().names().contains("deliver_file"));
        assertFalse(CoreAgentRuntime.crewBotCapabilityScope(registry).names().contains("deliver_file"));
        assertFalse(tool().execute(args("file.txt"), CancellationToken.crewChild()).success);
        DeliverFileTool sessionless = new DeliverFileTool("", workspace, artifacts, ledger);
        assertFalse(sessionless.execute(args("file.txt"), CancellationToken.uncancellable()).success);
    }
    @Test public void realLoopPersistsStableToolResultAndReopensNativeCardWithoutReplay() throws Exception {
        workspace.write("report.pdf", "%PDF-1.4 fixture"); AtomicInteger turns = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((history,prompt,tools,token) -> turns.getAndIncrement() == 0
                ? new ModelReply("", Collections.singletonList(new ModelReply.Call("deliver-real-1", "deliver_file", args("report.pdf"))))
                : new ModelReply("Attached", Collections.emptyList()), new CoreToolRegistry(Collections.singletonList(tool())), "Use files", session);
        new MainChatTranscriptStore(files, session, ledger).attach(loop, Collections.emptyList());
        assertEquals("COMPLETED", loop.run("Attach report", Collections.emptyList(), CancellationToken.uncancellable(), null).outcome);
        ChatAttachment artifact = delivered();
        for (int i=0; i<3; i++) {
            LocalRunStore restored = chatTestStore.localRunStore(files);
            assertTrue(restored.loadConversationContext(session).stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT
                    && t.toolCallId.equals("deliver-real-1") && t.content.contains("delivered:" + artifact.id)));
            assertEquals(1, restored.readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null).count());
        }
        assertEquals(2, turns.get());
    }
    @Test public void providerOnlyOpensOwnedArtifactsReadOnlyAndSuppliesFilenameSizeMime() throws Exception {
        Context context = ApplicationProvider.getApplicationContext(); String chat = "provider-" + UUID.randomUUID();
        WorkspaceStore actualWorkspace = new WorkspaceStore(new StorageGeneration(context), WorkspaceStore.projectIdForSession(chat), null, null, chat);
        actualWorkspace.write("bundle.zip", "zip fixture"); LocalRunStore actualLedger = new LocalRunStore(context);
        actualLedger.appendConversationMessage(chat,"user","send file");
        assertTrue(new DeliverFileTool(context,chat,actualWorkspace).execute(args("bundle.zip"), CancellationToken.uncancellable()).success);
        ChatAttachment artifact = actualLedger.readConversationTimeline(chat).stream().filter(e -> e.getDeliveredArtifact()!=null).findFirst().get().getDeliveredArtifact();
        ChatFileProvider provider = Robolectric.buildContentProvider(ChatFileProvider.class).create().get();
        Uri uri = ChatFileProvider.uri(context,chat,"delivered",artifact.id);
        assertEquals("application/zip",provider.getType(uri));
        try(android.database.Cursor cursor=provider.query(uri,null,null,null,null)) { assertTrue(cursor.moveToFirst()); assertEquals("bundle.zip",cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))); assertEquals(11,cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE))); }
        try(ParcelFileDescriptor descriptor=provider.openFile(uri,"r")) { assertNotNull(descriptor); }
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri,"rw"));
        assertThrows(IllegalArgumentException.class, () -> ChatFileProvider.uri(context,"other-chat","delivered",artifact.id));
        assertThrows(IllegalArgumentException.class, () -> provider.getType(Uri.parse(uri + "?path=/private")));
        actualLedger.deleteConversation(chat);
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri,"r"));
    }
}
