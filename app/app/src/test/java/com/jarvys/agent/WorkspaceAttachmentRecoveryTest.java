package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WorkspaceAttachmentRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private WorkspaceStore workspace(File root) { return new WorkspaceStore(root, WorkspaceStore.projectIdForSession("chat")); }
    private ChatAttachment attachment(String name, long bytes) {
        String id = UUID.randomUUID().toString();
        return new ChatAttachment(id, name, "text/plain", bytes, ChatAttachment.Kind.FILE, id + "-" + name);
    }

    @Test public void importsStreamLargeFilesAndReadsPaginatedUtf8WithoutChangingEditableFileLimit() throws Exception {
        WorkspaceStore workspace = workspace(temp.newFolder("workspaces"));
        String text = String.join("", Collections.nCopies(300_000, "x")) + "😀tail";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ChatAttachment attachment = attachment("large.txt", bytes.length);
        String path = workspace.importAttachment(attachment, new ByteArrayInputStream(bytes));
        assertTrue(path.startsWith("attachments/"));
        assertThrows(IllegalArgumentException.class, () -> workspace.read(path));
        WorkspaceStore.AttachmentTextPage page = workspace.readAttachmentPage(path, 299_998, 4);
        assertEquals("xx😀", page.text);
        assertEquals(300_002, page.nextOffset);
        assertTrue(page.hasMore);
        assertEquals("tail", workspace.readAttachmentPage(path, page.nextOffset, 100).text);
        assertFalse(workspace.readAttachmentPage(path, page.nextOffset, 100).hasMore);
        assertEquals("tail", workspace.readAttachmentPage(path, 300_001, 100).text);
        assertEquals(path, workspace.importAttachment(attachment, new ByteArrayInputStream(new byte[]{0})));
        assertTrue(workspace.deleteImportedAttachments());
        assertThrows(IllegalArgumentException.class, () -> workspace.readAttachmentPage(path, 0, 100));
    }

    @Test public void rejectsBinaryPdfMalformedUtf8AndInvalidOffsets() throws Exception {
        WorkspaceStore workspace = workspace(temp.newFolder("workspaces"));
        for (byte[] bytes : new byte[][]{{0, 1, 2}, {(byte) 0xff}, "%PDF-1.7".getBytes(StandardCharsets.UTF_8)}) {
            ChatAttachment attachment = attachment("file.txt", bytes.length);
            String path = workspace.importAttachment(attachment, new ByteArrayInputStream(bytes));
            assertThrows(IllegalArgumentException.class, () -> workspace.readAttachmentPage(path, 0, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> WorkspaceStore.readAttachmentTextPage(
                new StringReader("x"), 2, 10, CancellationToken.uncancellable()));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceStore.readAttachmentTextPage(
                new StringReader("😀"), 0, 1, CancellationToken.uncancellable()));
        workspace.write("normal.txt", "editable");
        assertNull(workspace.readAttachmentPage("normal.txt", 0, 100));
    }

    @Test public void delegationCannotReadListSearchWriteImportOrAliasChatAttachments() throws Exception {
        File roots = temp.newFolder("workspaces");
        WorkspaceStore owner = workspace(roots);
        ChatAttachment attachment = attachment("private.txt", 6);
        String path = owner.importAttachment(attachment, new ByteArrayInputStream("secret".getBytes(StandardCharsets.UTF_8)));
        owner.write("public.txt", "public");
        File project = new File(roots, owner.projectId());
        Files.createSymbolicLink(new File(project, "alias.txt").toPath(), new File(project, path).toPath());
        WorkspaceStore delegated = owner.forDelegatedAgent();
        assertFalse(delegated.attachmentsEnabled());
        assertFalse(delegated.skillsEnabled());
        assertFalse(delegated.memoryEnabled());
        assertEquals("public", delegated.read("public.txt"));
        assertThrows(IllegalArgumentException.class, () -> delegated.read(path));
        assertThrows(IllegalArgumentException.class, () -> delegated.read("alias.txt"));
        assertThrows(IllegalArgumentException.class, () -> delegated.write(path, "changed"));
        assertThrows(IllegalArgumentException.class, () -> delegated.importAttachment(attachment, new ByteArrayInputStream(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> delegated.codingProjectScope());
        assertTrue(delegated.list(".").stream().noneMatch(value -> value.contains("attachments") || value.contains("alias")));
        assertEquals(1, delegated.searchDocuments(Collections.singleton("workspace")).size());
        assertEquals("secret", owner.read(path));
    }

    @Test public void attachmentImportRejectsRootAndDirectorySymlinksAndCleansFailedWrites() throws Exception {
        File roots = temp.newFolder("workspaces");
        WorkspaceStore owner = workspace(roots);
        File project = new File(roots, owner.projectId());
        assertTrue(project.mkdirs());
        File outside = temp.newFolder("outside");
        File sentinel = new File(outside, "sentinel");
        Files.write(sentinel.toPath(), new byte[]{1});
        Files.createSymbolicLink(new File(project, "attachments").toPath(), outside.toPath());
        ChatAttachment attachment = attachment("file", 1);
        assertThrows(IllegalArgumentException.class, () -> owner.importAttachment(attachment, new ByteArrayInputStream(new byte[]{0})));
        assertFalse(owner.deleteImportedAttachments());
        assertTrue(sentinel.isFile());
        Files.delete(new File(project, "attachments").toPath());
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("failed"); }
        };
        assertThrows(IOException.class, () -> owner.importAttachment(attachment, failing));
        assertEquals(0, new File(project, "attachments").listFiles().length);
        File aliasRoots = new File(temp.getRoot(), "alias-workspaces");
        Files.createSymbolicLink(aliasRoots.toPath(), roots.toPath());
        WorkspaceStore aliased = workspace(aliasRoots);
        assertThrows(IllegalArgumentException.class, () -> aliased.importAttachment(attachment, new ByteArrayInputStream(new byte[]{0})));
        assertThrows(IllegalArgumentException.class, aliased::forDelegatedAgent);
    }

    @Test public void providerFailuresKeepRetryAndCompactionSemantics() {
        Context context = ApplicationProvider.getApplicationContext();
        AttachmentModelContext.checkImageReply("data: {\"type\":\"response.completed\",\"response\":{\"error\":null}}\n\ndata: [DONE]");
        assertThrows(AttachmentModelContext.RejectedReply.class, () -> AttachmentModelContext.checkImageReply(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"unsupported_image\"}}}"));
        assertThrows(ProviderRateLimitException.class, () -> AttachmentModelContext.checkImageReply(
                "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"));
        ProviderRateLimitException limited = new ProviderRateLimitException("rate", 5000);
        assertSame(limited, AttachmentModelContext.providerFailure(context, limited));
        RuntimeException network = new ProviderTransportException("network", null);
        assertSame(network, AttachmentModelContext.providerFailure(context, network));
        assertThrows(IllegalStateException.class, () -> AttachmentModelContext.requireVision(context, "text-only", false));
        AttachmentModelContext.requireVision(context, "unknown", null);
    }

    @Test public void diagnosticSuppressionNestsRestoresAndIsThreadOwned() throws Exception {
        assertFalse(AgentErrorReporter.attachmentReportsSuppressed());
        AgentErrorReporter.AttachmentScope outer = AgentErrorReporter.suppressForAttachments();
        assertTrue(AgentErrorReporter.attachmentReportsSuppressed());
        try (AgentErrorReporter.AttachmentScope inner = AgentErrorReporter.suppressForPrivateContent()) {
            // A null Context would crash normal reporting. The guard must return before accessing it.
            AgentErrorReporter.report(null, "test", "model", 400, "private", "private", "type", "private");
            final Throwable[] error = {null};
            Thread other = new Thread(() -> {
                assertFalse(AgentErrorReporter.attachmentReportsSuppressed());
                try { outer.close(); } catch (Throwable failure) { error[0] = failure; }
            });
            other.start();
            other.join();
            assertTrue(error[0] instanceof IllegalStateException);
        }
        assertTrue(AgentErrorReporter.attachmentReportsSuppressed());
        outer.close();
        outer.close();
        assertFalse(AgentErrorReporter.attachmentReportsSuppressed());
    }
}
