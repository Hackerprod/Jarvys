package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.proactive.ProactiveConversation;
import com.jarvys.agent.tasks.ScheduledTaskConversation;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Local delivery to the current owner chat; never uploads a file or grants a child new access. */
final class DeliverFileTool implements CoreTool {
    static final String NAME = "deliver_file";
    private final String session;
    private final WorkspaceStore workspace;
    private final DeliveredArtifactStore artifacts;
    private final LocalRunStore conversations;
    private final ToolSpec spec;

    DeliverFileTool(Context context, String session, WorkspaceStore workspace) {
        this(session, workspace, new DeliveredArtifactStore(context), new LocalRunStore(context));
    }
    DeliverFileTool(String session, WorkspaceStore workspace, DeliveredArtifactStore artifacts, LocalRunStore conversations) {
        this.session = session; this.workspace = workspace; this.artifacts = artifacts; this.conversations = conversations;
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("path", "string"); properties.put("filename", "string");
        spec = new ToolSpec(NAME, "jarvys/workspace",
                "Attach an existing file you worked on to this conversation as a durable native attachment. "
                + "Use /project/path for shared Coding or backend files (including factory APKs), or a relative ordinary workspace path. "
                + "Optional filename changes its download name only. Maximum 256 MiB. The immutable copy survives later workspace edits. "
                + "UTF-8 HTML also receives an immutable offline preview of bounded, statically referenced local assets; missing or excluded assets are reported. "
                + "Use this when the user asks for the actual file; plain paths and Markdown links do not deliver files. "
                + "No remote upload, automatic download, install, execution, memory/skills/credentials access, or cross-chat access. Main chat only. "
                + "The user chooses Preview, Download or Share; only report attached=true after success. Repeating unchanged bytes/name and captured assets returns the same artifact.",
                "workspace", ToolSpec.Status.IMPLEMENTED, properties, Collections.singletonList("path"));
    }
    @Override public ToolSpec declaration() { return spec; }
    @Override public boolean canDelegate() { return false; }
    @Override public String auditDetail(Map<String, Object> arguments) { return "Deliver a local file to this conversation"; }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        token.throwIfCancelled();
        if (token.isCrewRun() || session == null || session.isEmpty() || ProactiveConversation.SESSION_ID.equals(session)
                || ScheduledTaskConversation.SESSION_ID.equals(session)) return CoreToolResult.failure("File delivery is available only in the main chat.");
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent()) {
            if (arguments == null || !arguments.keySet().stream().allMatch(Arrays.asList("path", "filename")::contains)
                    || !(arguments.get("path") instanceof String) || ((String) arguments.get("path")).isEmpty()
                    || ((String) arguments.get("path")).length() > 1024
                    || arguments.containsKey("filename") && (!(arguments.get("filename") instanceof String)
                        || ((String) arguments.get("filename")).isEmpty() || ((String) arguments.get("filename")).length() > 1024))
                return CoreToolResult.failure("Provide an existing scoped path and optional filename.");
            if (conversations.readConversationMetadata(session).deleted) return CoreToolResult.failure("This conversation was deleted.");
            ChatAttachment artifact = artifacts.snapshot(session, workspace, (String) arguments.get("path"),
                    (String) arguments.get("filename"), token);
            token.throwIfCancelled();
            conversations.appendDeliveredFile(session, artifact);
            AgentRunUiState.deliveredFileAdded(session, artifact);
            return deliveryResult(session, artifact, artifacts);
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (IOException | IllegalArgumentException unavailable) {
            return CoreToolResult.failure("Could not attach file. Check that it is an ordinary file in this conversation's workspace, within 256 MiB, and that storage is available. No file was uploaded.");
        } catch (Exception failure) {
            return CoreToolResult.failure("File delivery could not be confirmed. Check this conversation for the attachment before retrying the same path and filename.");
        }
    }
    static CoreToolResult deliveryResult(String session, ChatAttachment artifact, DeliveredArtifactStore artifacts) throws Exception {
        HtmlPreviewDescriptor preview = artifacts.previewForAttachment(session, artifact);
        JSONObject result = new JSONObject().put("attached", true).put("artifact_id", "delivered:" + artifact.id)
                .put("filename", artifact.name).put("mime_type", artifact.mimeType).put("size_bytes", artifact.sizeBytes)
                .put("sha256", artifacts.sha256(session, artifact))
                .put("delivery", "Native attachment in this conversation. The user can Download or Share it.")
                .put("preview_available", preview != null);
        if (preview != null) {
            result.put("preview_id", preview.token).put("preview_entry", preview.entryPath)
                    .put("preview_file_count", preview.fileCount).put("preview_total_bytes", preview.totalBytes)
                    .put("preview_warnings", new org.json.JSONArray(preview.warnings))
                    .put("preview_policy", "Immutable local HTML; external URL resource loads are blocked and no Android bridge is installed. Only bounded static dependencies are captured.");
            return CoreToolResult.preview(result.toString(), preview.token);
        }
        String warning = artifacts.previewUnavailable(session, artifact);
        if (!warning.isEmpty()) result.put("preview_warnings", new org.json.JSONArray().put(warning));
        return CoreToolResult.success(result.toString());
    }

}
