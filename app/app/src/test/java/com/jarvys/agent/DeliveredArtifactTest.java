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
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File files;
    private LocalRunStore ledger;
    private DeliveredArtifactStore artifacts;
    private WorkspaceStore workspace;
    private final String session = "delivery-chat";
    @Before public void setUp() {
        files = temporary.getRoot(); ledger = new LocalRunStore(files);
        artifacts = new DeliveredArtifactStore(new File(files, "jarvys"));
        workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), WorkspaceStore.projectIdForSession(session),
                null, null, null, session, false);
        ledger.appendConversationMessage(session, "user", "Attach the report");
    }
    private DeliverFileTool tool() { return new DeliverFileTool(session, workspace, artifacts, ledger); }
    private Map<String,Object> args(String path) { return Collections.singletonMap("path", path); }
    private ChatAttachment delivered() {
        return new LocalRunStore(files).readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null)
                .findFirst().get().getDeliveredArtifact();
    }
    @Test public void nativeSnapshotSurvivesWorkspaceEditAndProcessRecreation() throws Exception {
        workspace.write("report.txt", "original");
        CoreToolResult result = tool().execute(args("report.txt"), CancellationToken.uncancellable());
        assertTrue(result.content, result.success);
        ChatAttachment file = delivered(); assertEquals("report.txt", file.name); assertEquals(8, file.sizeBytes);
        workspace.write("report.txt", "replacement");
        File saved = new DeliveredArtifactStore(new File(files, "jarvys")).resolve(session, file);
        assertEquals("original", new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
        assertTrue(new LocalRunStore(files).conversationHasPrivateImagesOrAttachments(session));
        assertFalse(result.content.contains(files.getAbsolutePath()));
        assertFalse(result.content.contains("original"));
        assertEquals(artifacts.sha256(session,file), new JSONObject(result.content).getString("sha256"));
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
    @Test public void appPrivateAbsoluteTraversalMemorySkillsAndOtherChatCannotBeDelivered() throws Exception {
        File secret = new File(files, "secret-token"); Files.write(secret.toPath(), "sentinel".getBytes(StandardCharsets.UTF_8));
        for (String path : Arrays.asList(secret.getPath(), "../secret-token", "/memory/private.md", "/skills/private.md", "memory/private.md", "skills/private.md", "attachments/private.txt", "/project/../secret-token"))
            assertFalse(path, tool().execute(args(path), CancellationToken.uncancellable()).success);
        workspace.write("owned.txt", "mine"); tool().execute(args("owned.txt"), CancellationToken.uncancellable());
        assertThrows(IllegalArgumentException.class, () -> artifacts.resolve("another-chat", delivered()));
        assertNull(ledger.findChatFile("another-chat", "delivered", delivered().id));
        assertEquals("sentinel", new String(Files.readAllBytes(secret.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void symlinkFilesAndParentsFailClosedWithoutLeakingPrivateBytes() throws Exception {
        workspace.write("base.txt", "safe");
        File root = new File(files, "jarvys/workspaces/" + WorkspaceStore.projectIdForSession(session));
        File outside = temporary.newFolder("private"); Files.write(new File(outside, "token").toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(root, "link").toPath(), new File(outside, "token").toPath());
        Files.createSymbolicLink(new File(root, "directory").toPath(), outside.toPath());
        assertFalse(tool().execute(args("link"), CancellationToken.uncancellable()).success);
        assertFalse(tool().execute(args("directory/token"), CancellationToken.uncancellable()).success);
        assertFalse(ledger.readConversationTimeline(session).stream().anyMatch(e -> e.getDeliveredArtifact() != null));
    }
    @Test public void descriptorIdentityRejectsSwapAndRestoreDuringOpen() throws Exception {
        workspace.write("folder/file.txt", "safe");
        File root = new File(files, "jarvys/workspaces/" + WorkspaceStore.projectIdForSession(session));
        File parent = new File(root,"folder"), kept = new File(root,"kept");
        File outside = temporary.newFolder("outside"); Files.write(new File(outside,"file.txt").toPath(), "evil".getBytes(StandardCharsets.UTF_8));
        ArtifactOsShadow.beforeOpen = () -> { try { Files.move(parent.toPath(), kept.toPath()); Files.createSymbolicLink(parent.toPath(), outside.toPath()); } catch(Exception e) { throw new RuntimeException(e); } };
        ArtifactOsShadow.afterOpen = () -> { try { Files.delete(parent.toPath()); Files.move(kept.toPath(), parent.toPath()); } catch(Exception e) { throw new RuntimeException(e); } };
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
    @Test public void toolIsActuallyRegisteredButUnavailableToDelegatedAndSystemScopes() {
        Context context = ApplicationProvider.getApplicationContext();
        CoreToolRegistry registry = new CoreAgentRuntime(context, "real-delivery", Collections.emptyList()).createTools();
        assertTrue(registry.names().contains("deliver_file"));
        assertFalse(registry.withoutAttachments().names().contains("deliver_file"));
        assertFalse(WorkspaceTools.forDelegatedAgent(registry.handlers()).stream().anyMatch(t -> t.declaration().name.equals("deliver_file")));
        assertFalse(registry.forDelegatedAgent().names().contains("deliver_file"));
        assertFalse(CoreAgentRuntime.crewBotCapabilityScope(registry).names().contains("deliver_file"));
        assertFalse(tool().execute(args("file.txt"), CancellationToken.crewChild()).success);
        DeliverFileTool scheduled = new DeliverFileTool(com.jarvys.agent.tasks.ScheduledTaskConversation.SESSION_ID, workspace, artifacts, ledger);
        assertFalse(scheduled.execute(args("file.txt"), CancellationToken.uncancellable()).success);
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
            LocalRunStore restored = new LocalRunStore(files);
            assertTrue(restored.loadConversationContext(session).stream().anyMatch(t -> t.kind == ConversationTurn.Kind.TOOL_RESULT
                    && t.toolCallId.equals("deliver-real-1") && t.content.contains("delivered:" + artifact.id)));
            assertEquals(1, restored.readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null).count());
        }
        assertEquals(2, turns.get());
    }
    @Test public void providerOnlyOpensOwnedArtifactsReadOnlyAndSuppliesFilenameSizeMime() throws Exception {
        Context context = ApplicationProvider.getApplicationContext(); String chat = "provider-" + UUID.randomUUID();
        WorkspaceStore actualWorkspace = new WorkspaceStore(new File(context.getFilesDir(), "jarvys/workspaces"), WorkspaceStore.projectIdForSession(chat), null,null,null,chat,false);
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
