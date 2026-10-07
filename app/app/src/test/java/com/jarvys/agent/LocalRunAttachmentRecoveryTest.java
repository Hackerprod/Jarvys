package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalRunAttachmentRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private LocalRunStore store() { return new LocalRunStore(temp.getRoot()); }
    private AttachmentStore attachments() { return new AttachmentStore(new File(temp.getRoot(), "jarvys")); }
    private ChatAttachment file(String session, String name) throws Exception {
        return attachments().copyFromStream(session, name, "text/plain", null, new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }
    private File ledger(String session) { return new File(temp.getRoot(), "jarvys/conversations/" + session + ".jsonl"); }

    @Test public void oldAbsentAndNullAttachmentRowsRemainReadableAlongsideNewMetadata() throws Exception {
        LocalRunStore store = store();
        store.appendConversationMessage("chat", "user", "old unchanged");
        JSONObject legacyNull = new JSONObject().put("role", "user").put("content", "null unchanged")
                .put("messageId", "legacy-null").put("attachments", JSONObject.NULL);
        Files.write(ledger("chat").toPath(), (legacyNull + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        ChatAttachment item = file("chat", "notes.txt");
        String message = store.appendConversationMessage("chat", "user", "new unchanged", Collections.singletonList(item));
        List<ConversationTurn> context = store.loadConversationContext("chat");
        assertEquals(3, context.size());
        assertEquals("old unchanged", context.get(0).content);
        assertTrue(context.get(0).attachments.isEmpty());
        assertTrue(context.get(1).attachments.isEmpty());
        assertEquals(Collections.singletonList(item), context.get(2).attachments);
        assertEquals(Collections.singletonList(item), store.readConversationAttachments("chat", message));
        assertTrue(store.readConversationAttachments("other", message).isEmpty());
        assertEquals(Collections.singletonList(item), store.readConversationTimeline("chat").get(2).getAttachments());
        String persisted = new String(Files.readAllBytes(ledger("chat").toPath()), StandardCharsets.UTF_8);
        assertFalse(persisted.contains("base64"));
        assertFalse(persisted.contains("workspace_path"));
        assertTrue(store.conversationHasAttachments("chat"));
    }

    @Test public void assistantProactiveAndScheduledScopesRejectAttachmentPersistence() throws Exception {
        LocalRunStore store = store();
        List<ChatAttachment> items = Collections.singletonList(file("chat", "private.txt"));
        assertThrows(IllegalArgumentException.class, () -> store.appendConversationMessage("chat", "assistant", "x", items));
        assertThrows(IllegalArgumentException.class, () -> store.appendConversationMessage(
                com.jarvys.agent.proactive.ProactiveConversation.SESSION_ID, "user", "x", items));
        assertThrows(IllegalArgumentException.class, () -> store.appendConversationMessage(
                com.jarvys.agent.tasks.ScheduledTaskConversation.SESSION_ID, "user", "x", items));
        assertThrows(IllegalArgumentException.class, () -> store.appendConversationMessage("chat", "user", "x", null,
                "", "", "", "proactive-thread", items));
        assertTrue(store.readConversationMessages("chat").isEmpty());
    }

    @Test public void metadataRenamesPinsArchivesAndDeduplicatesConversationRows() throws Exception {
        LocalRunStore store = store();
        store.appendConversationMessage("older", "user", "old");
        store.beginRun("old run", "older");
        store.beginRun("another old run", "older");
        store.appendConversationMessage("newer", "user", "new");
        store.renameConversation("older", "  New\n name  ");
        store.setConversationPinned("older", true);
        assertEquals("New name", store.readConversationTitle("older"));
        assertFalse(store.claimConversationTitleGeneration("older"));
        assertFalse(store.appendConversationTitleIfAbsent("older", "overwritten"));
        assertEquals("older", store.listConversations().get(0).getString("session_id"));
        assertEquals(2, store.listConversations().size());
        store.setConversationArchived("older", true);
        assertTrue(store.readConversationMetadata("older").archived);
        assertEquals(1, store.listRecentRuns(100, false).size());
        assertTrue(store.listRecentRuns(100).stream().anyMatch(row -> row.optBoolean("archived")));
    }

    @Test public void deletionRemovesOnlyOwnedDataAndTombstonePreventsResurrection() throws Exception {
        LocalRunStore store = store();
        ChatAttachment target = file("target", "private.txt");
        ChatAttachment other = file("other", "other.txt");
        store.appendConversationMessage("target", "user", "remove", Collections.singletonList(target));
        store.appendConversationMessage("other", "user", "keep", Collections.singletonList(other));
        String deletedRun = store.beginRun("target run", "target");
        String keptRun = store.beginRun("other run", "other");
        WorkspaceStore workspace = new WorkspaceStore(new File(temp.getRoot(), "jarvys/workspaces"), WorkspaceStore.projectIdForSession("target"));
        workspace.importAttachment(target, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        workspace.write("keep.txt", "ordinary workspace survives");
        assertTrue(store.deleteConversation("target"));
        assertTrue(store.readConversationMetadata("target").deleted);
        assertFalse(ledger("target").exists());
        assertFalse(new File(temp.getRoot(), "jarvys/runs/" + deletedRun).exists());
        assertTrue(new File(temp.getRoot(), "jarvys/runs/" + keptRun).isDirectory());
        assertEquals("ordinary workspace survives", workspace.read("keep.txt"));
        assertTrue(attachments().resolve("other", other).isFile());
        store.recordSummary(deletedRun, 1, "late callback");
        assertFalse(new File(temp.getRoot(), "jarvys/runs/" + deletedRun).exists());
        assertThrows(IllegalStateException.class, () -> store.appendConversationMessage("target", "assistant", "late"));
        assertThrows(IllegalStateException.class, () -> store.beginRun("resurrect", "target"));
        assertTrue(store.listConversationSessionIds().stream().noneMatch("target"::equals));
        assertTrue(store.listConversations().stream().noneMatch(row -> "target".equals(row.optString("session_id"))));
        assertTrue(store.readConversationMessages("target").isEmpty());
    }

    @Test public void partialRunDeletionKeepsOwnershipLedgerAndNeverFollowsSymlinks() throws Exception {
        LocalRunStore store = store();
        store.appendConversationMessage("target", "user", "remove");
        String run = store.beginRun("target run", "target");
        File directory = new File(temp.getRoot(), "jarvys/runs/" + run);
        File outside = temp.newFile("sentinel");
        Files.write(outside.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(directory, "aliased-image.jpg").toPath(), outside.toPath());
        assertFalse(store.deleteConversation("target"));
        assertTrue(new File(directory, "steps.jsonl").isFile());
        assertEquals("keep", new String(Files.readAllBytes(outside.toPath()), StandardCharsets.UTF_8));
        assertTrue(store.readConversationMetadata("target").deleted);
        assertTrue(store.readAllSteps().isEmpty());
        Files.delete(new File(directory, "aliased-image.jpg").toPath());
        assertTrue(store.deleteConversation("target"));
        assertFalse(directory.exists());
    }

    @Test public void ledgerAndRunRootAliasesAreRejectedAndExternalFilesStayUntouched() throws Exception {
        LocalRunStore store = store();
        File sentinel = temp.newFile("external-ledger");
        Files.write(sentinel.toPath(), "{\"role\":\"user\",\"content\":\"private\"}\n".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(ledger("aliased").toPath(), sentinel.toPath());
        assertThrows(IllegalArgumentException.class, () -> store.readConversationMessages("aliased"));
        assertThrows(IllegalArgumentException.class, () -> store.deleteConversation("aliased"));
        assertTrue(sentinel.isFile());
        File otherFiles = temp.newFolder("other-files");
        File jarvys = new File(otherFiles, "jarvys");
        assertTrue(jarvys.mkdirs());
        Files.createSymbolicLink(new File(jarvys, "runs").toPath(), temp.getRoot().toPath());
        assertThrows(IllegalStateException.class, () -> new LocalRunStore(otherFiles));
    }

    @Test public void privacyFlagsIncludeGeneratedFailuresAndCodeAndCleanupPreservesPersistedAttachments() throws Exception {
        LocalRunStore store = store();
        ChatAttachment keep = file("chat", "keep.txt");
        ChatAttachment orphan = file("chat", "orphan.txt");
        store.appendConversationMessage("chat", "user", "keep", Collections.singletonList(keep));
        store.cleanupOrphanAttachments();
        assertTrue(attachments().resolve("chat", keep).isFile());
        assertThrows(IllegalArgumentException.class, () -> attachments().resolve("chat", orphan));
        assertTrue(store.conversationHasPrivateImagesOrAttachments("chat"));
        assertFalse(store.conversationHasPrivateImagesOrAttachments("image-chat"));
        store.appendGeneratedImageFailure("image-chat", "private prompt", "failure");
        assertTrue(store.conversationHasPrivateImagesOrAttachments("image-chat"));
        assertFalse(store.conversationHasPrivateCode("code-chat"));
        store.markConversationHasPrivateCode("code-chat");
        assertTrue(store.conversationHasPrivateCode("code-chat"));
        assertTrue(new LocalRunStore(temp.getRoot()).conversationHasPrivateCode("code-chat"));
    }
}
