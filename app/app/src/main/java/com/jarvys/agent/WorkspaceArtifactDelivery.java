package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.coding.ArtifactSnapshotIO;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import com.jarvys.agent.workspaces.WorkspaceRecord;
import com.jarvys.agent.workspaces.WorkspaceRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.json.JSONObject;

/** Explicit, checksum-bound transfer from a registered workspace into its fixed central owner. */
public final class WorkspaceArtifactDelivery {
    private final Context context;
    private final File testFiles;
    private final WorkspaceRegistry testRegistry;

    /** Construction is inert: source resources are opened only after the caller's policy admission. */
    public WorkspaceArtifactDelivery(Context context) {
        if (context == null) throw new IllegalArgumentException("Application context is required");
        this.context = context.getApplicationContext();
        testFiles = null;
        testRegistry = null;
    }

    WorkspaceArtifactDelivery(File files, WorkspaceRegistry registry) {
        context = null;
        testFiles = files;
        testRegistry = registry;
    }

    public JSONObject deliver(String workspaceId, String path, String expectedSha256, String filename,
            CancellationToken token, Runnable admission) throws IOException {
        if (token == null || admission == null) throw new IllegalArgumentException("Cancellation and policy admission are required");
        token.throwIfCancelled();
        if (token.isCrewRun()) throw new IllegalArgumentException("Only the central owner can request workspace file delivery");
        WorkspaceIdentity.requireWorkspaceId(workspaceId);
        requireSourcePath(path);
        if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Provide the file's current lowercase SHA-256 checksum");
        if (filename != null) {
            requireText(filename, "filename");
            if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || filename.equals(".") || filename.equals(".."))
                throw new IllegalArgumentException("Provide one download filename");
        }
        admission.run();
        token.throwIfCancelled();
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent()) {
            if (testRegistry != null) return deliver(testRegistry, testFiles, workspaceId, path, expectedSha256,
                    filename, token, admission);
            try (WorkspaceRegistry registry = new WorkspaceRegistry(context)) {
                return deliver(registry, context.getFilesDir(), workspaceId, path, expectedSha256, filename, token, admission);
            }
        }
    }

    /** Read-only file metadata for an exact subsequent delivery; never invent or trust a worker's hash. */
    public JSONObject inspect(String workspaceId, String path, CancellationToken token, Runnable admission) throws IOException {
        if (token == null || admission == null) throw new IllegalArgumentException("Cancellation and policy admission are required");
        token.throwIfCancelled();
        if (token.isCrewRun()) throw new IllegalArgumentException("Only the central owner can inspect workspace delivery files");
        WorkspaceIdentity.requireWorkspaceId(workspaceId);
        requireSourcePath(path);
        admission.run();
        token.throwIfCancelled();
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent()) {
            if (testRegistry != null) return inspect(testRegistry, testFiles, workspaceId, path, token, admission);
            try (WorkspaceRegistry registry = new WorkspaceRegistry(context)) {
                return inspect(registry, context.getFilesDir(), workspaceId, path, token, admission);
            }
        }
    }

    private JSONObject inspect(WorkspaceRegistry registry, File files, String workspaceId, String path,
            CancellationToken token, Runnable admission) throws IOException {
        SourceScope source = source(registry, files, workspaceId, token, admission);
        source.validate.run();
        File directory = new File(files.getAbsoluteFile(), "jarvys/workspace-inspections");
        String expected = new File(files.getCanonicalFile(), "jarvys/workspace-inspections").getPath();
        if (!directory.getCanonicalPath().equals(expected)) throw new IOException("Inspection storage changed");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create inspection storage");
        if (!directory.getCanonicalPath().equals(expected)) throw new IOException("Inspection storage changed");
        File staged = File.createTempFile(".inspection-", ".tmp", directory);
        try {
            source.validate.run();
            WorkspaceStore.DeliverySource input = source.workspace.deliverySource(path);
            ArtifactSnapshotIO.Snapshot snapshot = ArtifactSnapshotIO.copy(input.root, input.relative, staged,
                    DeliveredArtifactStore.MAX_BYTES, token);
            source.validate.run();
            JSONObject receipt = new JSONObject().put("workspace_id", workspaceId).put("path", path)
                    .put("sha256", snapshot.sha256).put("size_bytes", snapshot.bytes);
            source.validate.run();
            return receipt;
        } catch (org.json.JSONException invalid) { throw new IOException("Could not encode workspace file metadata", invalid); }
        finally {
            if (staged.exists() && !staged.delete()) throw new IOException("Could not remove temporary file inspection");
        }
    }

    private JSONObject deliver(WorkspaceRegistry registry, File files, String workspaceId, String path,
            String expectedSha256, String filename, CancellationToken token, Runnable admission) throws IOException {
        synchronized (DeliveredArtifactStore.DELIVERY_ACTION_LOCK) {
            SourceScope source = source(registry, files, workspaceId, token, admission);
            source.validate.run();
            LocalRunStore conversations = new LocalRunStore(files);
            DeliveredArtifactStore artifacts = new DeliveredArtifactStore(new File(files, "jarvys"));
            ChatAttachment artifact = artifacts.snapshotWorkspace(registry, workspaceId, source.workspace, path, filename,
                    expectedSha256, token, source.validate);
            source.validate.run();
            final JSONObject receipt;
            try {
                // Reuse native delivery/preview metadata without absolute paths or private source bytes.
                receipt = new JSONObject(DeliverFileTool.deliveryResult(WorkspaceIdentity.CENTRAL_SESSION, artifact, artifacts).content)
                        .put("workspace_id", workspaceId).put("source_path", path).put("source_sha256", expectedSha256)
                        .put("destination", WorkspaceIdentity.CENTRAL_SESSION);
            } catch (Exception unavailable) { throw new IOException("Could not prepare the immutable workspace file receipt", unavailable); }
            if (!token.runIfActive(() -> {
                source.validate.run();
                // Existing ledger verifies immutable bytes and coalesces retries before publishing a card.
                conversations.appendDeliveredFile(WorkspaceIdentity.CENTRAL_SESSION, artifact);
                source.validate.run();
                AgentRunUiState.deliveredFileAdded(WorkspaceIdentity.CENTRAL_SESSION, artifact);
            })) throw new CancellationException("Workspace file delivery stopped before attachment");
            source.validate.run();
            return receipt;
        }
    }

    private SourceScope source(WorkspaceRegistry registry, File files, String workspaceId,
            CancellationToken token, Runnable admission) {
        token.throwIfCancelled();
        admission.run();
        token.throwIfCancelled();
        WorkspaceRecord source = requireOwnedWorkspace(registry, workspaceId);
        ConversationMetadataAccess metadata = new ConversationMetadataAccess(files);
        Runnable validate = () -> {
            token.throwIfCancelled();
            admission.run();
            token.throwIfCancelled();
            if (!source.equals(requireOwnedWorkspace(registry, workspaceId)))
                throw new IllegalArgumentException("Workspace ownership changed");
            if (metadata.read(WorkspaceIdentity.CENTRAL_SESSION).deleted || metadata.read(source.getExecutionSessionId()).deleted)
                throw new IllegalArgumentException("The workspace or central conversation was deleted");
        };
        validate.run();
        // No skill or memory mounts. Coding identity still derives solely from the registered session.
        WorkspaceStore workspace = new WorkspaceStore(new File(files, "jarvys/workspaces"),
                WorkspaceStore.projectIdForSession(source.getExecutionSessionId()), null, null, null,
                source.getExecutionSessionId(), false);
        return new SourceScope(workspace, validate);
    }

    private static final class SourceScope {
        final WorkspaceStore workspace;
        final Runnable validate;
        SourceScope(WorkspaceStore workspace, Runnable validate) { this.workspace = workspace; this.validate = validate; }
    }

    static WorkspaceRecord requireOwnedWorkspace(WorkspaceRegistry registry, String workspaceId) {
        WorkspaceIdentity.requireWorkspaceId(workspaceId);
        WorkspaceRecord source = registry.getWorkspace(workspaceId);
        if (source == null || !workspaceId.equals(source.getWorkspaceId())
                || !WorkspaceIdentity.CENTRAL_SESSION.equals(source.getOwnerCentralSessionId())
                || !WorkspaceIdentity.executionSessionId(workspaceId).equals(source.getExecutionSessionId()))
            throw new IllegalArgumentException("Unknown or unowned workspace");
        return source;
    }

    static void requireSourcePath(String path) throws IOException {
        requireText(path, "path");
        // Only this exact mount prefix is accepted; deliverySource enforces its reserved session scope.
        String relative = path.startsWith("/project/") ? path.substring(9) : path;
        if (path.getBytes(StandardCharsets.UTF_8).length > 1024 || relative.startsWith("/")
                || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0 || relative.indexOf('%') >= 0
                || relative.indexOf('?') >= 0 || relative.indexOf('#') >= 0)
            throw new IOException("An exact scoped file path is required");
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".jarvys-tmp-"))
                throw new IOException("Invalid scoped file path");
        }
        // Hidden ordinary output names are allowed; deliverySource denies actual private mounts,
        // while ArtifactSnapshotIO and ProjectScope enforce ordinary files, anchors and symlinks.
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > 1024
                || value.getBytes(StandardCharsets.UTF_8).length > 4096 || !value.equals(value.trim()))
            throw new IllegalArgumentException("Provide a bounded " + label);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029')
                throw new IllegalArgumentException("Invalid " + label);
            if (Character.isHighSurrogate(ch)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index)))
                    throw new IllegalArgumentException("Invalid " + label);
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Invalid " + label);
        }
    }
}
