package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalRunAttachmentRecoveryTest {
    @org.junit.Rule public final ChatTestStore chatTestStore = new ChatTestStore();
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File files;
    @org.junit.Before public void setUp() { files = chatTestStore.newFilesDirectory(); }
    private LocalRunStore store() { return chatTestStore.localRunStore(files); }
    private AttachmentStore attachments() { return new AttachmentStore(new File(files, "jarvys")); }
    private ChatAttachment file(String session, String name) throws Exception {
        return attachments().copyFromStream(session, name, "text/plain", null, new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }
    @Test public void assistantAndThreadRepliesRejectAttachmentPersistence() throws Exception {
        LocalRunStore store = store();
        List<ChatAttachment> items = Collections.singletonList(file("chat", "private.txt"));
        assertThrows(IllegalArgumentException.class, () -> store.appendConversationMessage("chat", "assistant", "x", items));
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
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"), WorkspaceStore.projectIdForSession("target"));
        workspace.importAttachment(target, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        workspace.write("keep.txt", "ordinary workspace survives");
        assertTrue(store.deleteConversation("target"));
        assertTrue(store.readConversationMetadata("target").deleted);
        assertFalse(new File(files, "jarvys/runs/" + deletedRun).exists());
        assertTrue(new File(files, "jarvys/runs/" + keptRun).isDirectory());
        assertEquals("ordinary workspace survives", workspace.read("keep.txt"));
        assertTrue(attachments().resolve("other", other).isFile());
        store.recordSummary(deletedRun, 1, "late callback");
        assertFalse(new File(files, "jarvys/runs/" + deletedRun).exists());
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
        File directory = new File(files, "jarvys/runs/" + run);
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

    @Test public void runRootAliasesAreRejected() throws Exception {
        LocalRunStore store = store();
        File otherFiles = chatTestStore.newFilesDirectory("other-files");
        File jarvys = new File(otherFiles, "jarvys");
        assertTrue(jarvys.mkdirs());
        File alias = new File(jarvys, "runs");
        try {
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                String command = "mklink /J \"" + alias.getAbsolutePath() + "\" \"" + files.getAbsolutePath() + "\"";
                Process process = new ProcessBuilder("cmd.exe", "/c", command).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("Could not create test junction: " + output, 0, process.waitFor());
            } else {
                Files.createSymbolicLink(alias.toPath(), files.toPath());
            }
            assertThrows(IllegalStateException.class, () -> chatTestStore.localRunStore(otherFiles));
        } finally {
            if (Files.exists(alias.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(alias.toPath());
        }
    }

    @Test public void drawerListsOnlyLiveDatabaseConversationsAndTheirRuns() throws Exception {
        LocalRunStore store = store();
        store.appendConversationMessage("live-chat", "user", "Live request");
        String liveRun = store.beginRun("Live goal", "live-chat");

        store.appendConversationMessage("deleted-chat", "user", "Deleted request");
        String deletedRun = store.beginRun("Private deleted goal", "deleted-chat");
        assertTrue(store.deleteConversation("deleted-chat"));
        File survivingDeletedRun = new File(files, "jarvys/runs/" + deletedRun);
        assertTrue(survivingDeletedRun.mkdirs());
        Files.write(new File(survivingDeletedRun, "steps.jsonl").toPath(),
                ("{\"event\":\"run_start\",\"run_id\":\"" + deletedRun
                        + "\",\"session_id\":\"deleted-chat\",\"goal\":\"Private deleted goal\"}\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        File preDatabaseRun = new File(files, "jarvys/runs/pre-database-run");
        assertTrue(preDatabaseRun.mkdirs());
        Files.write(new File(preDatabaseRun, "steps.jsonl").toPath(),
                "{\"event\":\"run_start\",\"run_id\":\"pre-database-run\",\"session_id\":\"old-chat\",\"goal\":\"Private old goal\"}\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<org.json.JSONObject> rows = store.listConversations();
        assertEquals(1, rows.size());
        assertEquals("live-chat", rows.get(0).optString("session_id"));
        assertEquals(liveRun, rows.get(0).optString("run_id"));
        assertEquals("Live goal", rows.get(0).optString("goal"));
    }

    @Test public void cleanupPreservesPersistedAttachments() throws Exception {
        LocalRunStore store = store();
        ChatAttachment keep = file("chat", "keep.txt");
        ChatAttachment orphan = file("chat", "orphan.txt");
        store.appendConversationMessage("chat", "user", "keep", Collections.singletonList(keep));
        store.cleanupOrphanAttachments();
        assertTrue(attachments().resolve("chat", keep).isFile());
        assertThrows(IllegalArgumentException.class, () -> attachments().resolve("chat", orphan));
        assertTrue(store.conversationHasAttachments("chat"));
    }
}
