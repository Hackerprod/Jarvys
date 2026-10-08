package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.proactive.ProactiveConversation;
import com.jarvys.agent.tasks.ScheduledTaskConversation;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import org.json.JSONObject;

/** Explicit invocation-local bridge from a connector to immutable files owned by the current chat. */
public final class ConnectorArtifactAccess {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private static final class Binding {
        final Context context; final String session;
        Binding(Context context, String session) { this.context = context.getApplicationContext(); this.session = session; }
    }
    public static final class Snapshot {
        public final byte[] bytes;
        public final String name, mime, sha256;
        Snapshot(byte[] bytes, String name, String mime, String sha256) {
            this.bytes = bytes; this.name = name; this.mime = mime; this.sha256 = sha256;
        }
    }
    private ConnectorArtifactAccess() { }

    static <T> T invoke(Context context, String session, Callable<T> action) {
        Binding old = CURRENT.get();
        try {
            if (context == null || session == null) CURRENT.remove();
            else CURRENT.set(new Binding(context, session));
            return action.call();
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Connector invocation failed", failure); }
        finally { if (old == null) CURRENT.remove(); else CURRENT.set(old); }
    }

    private static Binding requireBinding(CancellationToken token) {
        token.throwIfCancelled();
        Binding binding = CURRENT.get();
        if (binding == null || token.isCrewRun() || binding.session.isEmpty()
                || ProactiveConversation.SESSION_ID.equals(binding.session)
                || ScheduledTaskConversation.SESSION_ID.equals(binding.session))
            throw new IllegalStateException("Connector files are available only in the current main chat.");
        if (new LocalRunStore(binding.context).readConversationMetadata(binding.session).deleted)
            throw new IllegalStateException("This conversation was deleted.");
        return binding;
    }

    /** Copies remote bytes into the existing native attachment pipeline; never opens or uploads them. */
    public static JSONObject publish(byte[] bytes, String name, String mime, CancellationToken token) {
        Binding binding = requireBinding(token);
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Connector file exceeds the 8 MiB limit.");
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent()) {
            DeliveredArtifactStore store = new DeliveredArtifactStore(binding.context);
            ChatAttachment artifact = store.snapshotBytes(binding.session, bytes, name, mime, token);
            token.throwIfCancelled();
            LocalRunStore conversations = new LocalRunStore(binding.context);
            if (conversations.readConversationMetadata(binding.session).deleted)
                throw new IllegalStateException("This conversation was deleted.");
            conversations.appendDeliveredFile(binding.session, artifact);
            AgentRunUiState.deliveredFileAdded(binding.session, artifact);
            return new JSONObject().put("attached", true).put("artifact_id", "delivered:" + artifact.id)
                    .put("filename", artifact.name).put("mime_type", artifact.mimeType)
                    .put("size_bytes", artifact.sizeBytes).put("sha256", store.sha256(binding.session, artifact))
                    .put("delivery", "Native attachment in this conversation; choose Download or Share to export it.");
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (Exception failure) { throw new IllegalStateException("Could not confirm the native attachment. Check this chat before retrying."); }
    }

    /** Resolves an opaque, chat-owned attachment reference. No model-provided filesystem path is accepted. */
    public static Snapshot read(String artifactId, CancellationToken token) {
        Binding binding = requireBinding(token);
        if (artifactId == null || !artifactId.matches("(?:attachment|delivered):[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("Use an attachment:<id> or delivered:<id> from this conversation.");
        String[] ref = artifactId.split(":", 2);
        LocalRunStore conversations = new LocalRunStore(binding.context);
        ChatAttachment attachment = conversations.findChatFile(binding.session, ref[0], ref[1]);
        if (attachment == null || attachment.sizeBytes > MAX_BYTES)
            throw new IllegalArgumentException("File is unavailable in this chat or exceeds 8 MiB.");
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent();
             InputStream input = "delivered".equals(ref[0])
                     ? new DeliveredArtifactStore(binding.context).open(binding.session, attachment)
                     : new FileInputStream(new AttachmentStore(binding.context).resolve(binding.session, attachment));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                token.throwIfCancelled();
                if (output.size() + count > MAX_BYTES) throw new IllegalArgumentException("File exceeds 8 MiB.");
                output.write(buffer, 0, count);
            }
            token.throwIfCancelled();
            byte[] bytes = output.toByteArray();
            if (bytes.length != attachment.sizeBytes || conversations.findChatFile(binding.session, ref[0], ref[1]) == null)
                throw new IllegalArgumentException("File changed or is no longer owned by this conversation.");
            String hash = com.jarvys.agent.coding.ArtifactSnapshotIO.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Snapshot(bytes, attachment.name, attachment.mimeType, hash);
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (Exception failure) { throw new IllegalStateException("Could not read the approved file from this conversation."); }
    }
}
