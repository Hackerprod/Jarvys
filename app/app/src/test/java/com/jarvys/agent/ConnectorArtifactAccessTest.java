package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class ConnectorArtifactAccessTest {
    private Context context;
    private String session;
    private final byte[] binary = new byte[] {0, 1, 2, (byte) 0x80, (byte) 0xff, 13, 10};
    @Before public void prepare() {
        context = ApplicationProvider.getApplicationContext();
        session = "connector-" + UUID.randomUUID();
        new LocalRunStore(context).appendConversationMessage(session, "user", "Download the requested file");
    }
    private JSONObject publish(String name, String mime, CancellationToken token) {
        return ConnectorArtifactAccess.invoke(context, session, () -> ConnectorArtifactAccess.publish(binary, name, mime, token));
    }
    @Test public void nativeBinaryFilePersistsWithoutBase64OrLocalPathInResult() throws Exception {
        JSONObject result = publish("report.pdf", "application/pdf", CancellationToken.uncancellable());
        assertTrue(result.getBoolean("attached"));
        String reference = result.getString("artifact_id");
        ChatAttachment file = new LocalRunStore(context).findChatFile(session, "delivered", reference.substring(10));
        assertArrayEquals(binary, Files.readAllBytes(new DeliveredArtifactStore(context).resolve(session, file).toPath()));
        assertEquals("application/pdf", file.mimeType);
        assertFalse(result.toString().contains(context.getFilesDir().getPath()));
        assertFalse(result.has("data"));
        assertFalse(result.has("bytes"));
        assertTrue(new LocalRunStore(context).conversationHasPrivateImagesOrAttachments(session));
    }
    @Test public void unchangedDownloadIsIdempotentAndByteCopyIsImmutable() throws Exception {
        JSONObject first = publish("data.bin", "application/octet-stream", CancellationToken.uncancellable());
        JSONObject second = publish("data.bin", "application/octet-stream", CancellationToken.uncancellable());
        assertEquals(first.getString("artifact_id"), second.getString("artifact_id"));
        assertEquals(1, new LocalRunStore(context).readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null).count());
        ConnectorArtifactAccess.Snapshot snapshot = ConnectorArtifactAccess.invoke(context, session,
                () -> ConnectorArtifactAccess.read(first.getString("artifact_id"), CancellationToken.uncancellable()));
        assertArrayEquals(binary, snapshot.bytes);
        snapshot.bytes[0] = 42;
        ConnectorArtifactAccess.Snapshot again = ConnectorArtifactAccess.invoke(context, session,
                () -> ConnectorArtifactAccess.read(first.getString("artifact_id"), CancellationToken.uncancellable()));
        assertArrayEquals(binary, again.bytes);
        assertEquals(first.getString("sha256"), again.sha256);
    }
    @Test public void callerCannotBorrowAnotherChatsArtifact() throws Exception {
        JSONObject first = publish("data.bin", "application/octet-stream", CancellationToken.uncancellable());
        String other = "other-" + UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ConnectorArtifactAccess.invoke(context, other,
                () -> ConnectorArtifactAccess.read(first.getString("artifact_id"), CancellationToken.uncancellable())));
    }
    @Test public void missingInvocationCannotReadOrPublish() {
        assertThrows(IllegalStateException.class, () -> ConnectorArtifactAccess.publish(binary, "x", "application/octet-stream", CancellationToken.uncancellable()));
        assertThrows(IllegalStateException.class, () -> ConnectorArtifactAccess.read("delivered:" + UUID.randomUUID(), CancellationToken.uncancellable()));
    }
    @Test public void scopeRestoresAfterNestedAndFailedInvocation() throws Exception {
        ConnectorArtifactAccess.invoke(context, session, () -> {
            assertThrows(IllegalStateException.class, () -> ConnectorArtifactAccess.invoke(null, null,
                    () -> ConnectorArtifactAccess.publish(binary, "x", "application/octet-stream", CancellationToken.uncancellable())));
            assertTrue(ConnectorArtifactAccess.publish(binary, "x", "application/octet-stream", CancellationToken.uncancellable()).getBoolean("attached"));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> ConnectorArtifactAccess.publish(binary, "x", "application/octet-stream", CancellationToken.uncancellable()));
    }
    @Test public void crewAndBackgroundCannotUseMainChatFiles() {
        assertThrows(IllegalStateException.class, () -> publish("x", "application/octet-stream", CancellationToken.crewChild()));
        assertThrows(IllegalStateException.class, () -> ConnectorArtifactAccess.invoke(context,
                com.jarvys.agent.proactive.ProactiveConversation.SESSION_ID,
                () -> ConnectorArtifactAccess.publish(binary, "x", "application/octet-stream", CancellationToken.uncancellable())));
        assertThrows(IllegalStateException.class, () -> ConnectorArtifactAccess.invoke(context,
                com.jarvys.agent.tasks.ScheduledTaskConversation.SESSION_ID,
                () -> ConnectorArtifactAccess.publish(binary, "x", "application/octet-stream", CancellationToken.uncancellable())));
    }
    @Test public void cancellationPreventsFilePublication() {
        CancellationToken token = CancellationToken.cancellable(); token.cancel();
        assertThrows(CancellationException.class, () -> publish("x", "application/octet-stream", token));
        assertEquals(0, new LocalRunStore(context).readConversationTimeline(session).stream().filter(e -> e.getDeliveredArtifact() != null).count());
    }
    @Test public void oversizedBytesAreRejectedBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> ConnectorArtifactAccess.invoke(context, session,
                () -> ConnectorArtifactAccess.publish(new byte[ConnectorArtifactAccess.MAX_BYTES + 1], "x", "application/octet-stream", CancellationToken.uncancellable())));
    }
    @Test public void filenamesAndMimeCannotEscapeStorage() throws Exception {
        JSONObject result = publish("../../report\u0000.bin", "application/pdf\r\nX: injected", CancellationToken.uncancellable());
        assertFalse(result.getString("filename").contains("/"));
        assertFalse(result.getString("filename").contains("\u0000"));
        assertEquals("application/octet-stream", result.getString("mime_type"));
    }
    @Test public void uploadReadsOnlyVerifiedChatReferencesNotPaths() {
        for (String path : new String[]{"/etc/passwd", "../secret", "memory/key", "file:///tmp/x", "delivered:../x"})
            assertThrows(IllegalArgumentException.class, () -> ConnectorArtifactAccess.invoke(context, session,
                    () -> ConnectorArtifactAccess.read(path, CancellationToken.uncancellable())));
    }
    @Test public void userUploadedArtifactReadRequiresLedgerOwnership() throws Exception {
        ChatAttachment attachment = new AttachmentStore(context).copyFromStream(session, "upload.bin", "application/octet-stream",
                ChatAttachment.Kind.FILE, new ByteArrayInputStream(binary));
        String reference = "attachment:" + attachment.id;
        assertThrows(IllegalArgumentException.class, () -> ConnectorArtifactAccess.invoke(context, session,
                () -> ConnectorArtifactAccess.read(reference, CancellationToken.uncancellable())));
        new LocalRunStore(context).appendConversationMessage(session, "user", "Upload this file", java.util.Collections.singletonList(attachment));
        ConnectorArtifactAccess.Snapshot snapshot = ConnectorArtifactAccess.invoke(context, session,
                () -> ConnectorArtifactAccess.read(reference, CancellationToken.uncancellable()));
        assertArrayEquals(binary, snapshot.bytes);
        assertEquals("upload.bin", snapshot.name);
    }
}
