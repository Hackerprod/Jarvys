package com.jarvys.agent;

import android.content.Context;
import android.util.Base64;
import com.jarvys.agent.coding.ProjectMutationService;
import com.jarvys.agent.coding.ProjectScope;
import com.jarvys.agent.coding.ProjectScopeStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Explicit captain-owned copy; workers never receive a mount of private chat images. */
final class ImportProjectImageTool implements CoreTool {
    static final String NAME = "import_project_image";
    private final Context context;
    private final String session;
    private final ToolSpec spec;
    ImportProjectImageTool(Context context, String session) {
        this.context = context.getApplicationContext(); this.session = session;
        Map<String,String> fields = new LinkedHashMap<>(); fields.put("image_ref", "string"); fields.put("path", "string");
        fields.put("expected_sha256", "string"); fields.put("expected_scope_version", "integer"); fields.put("max_bytes", "integer");
        spec = new ToolSpec(NAME, "jarvys/project-images",
                "Copy exactly one user-authorized image from this conversation into its isolated Coding project. "
                + "Use an exact image_ref returned by attachment metadata, generate_image or list_image_references, and a relative PNG destination. "
                + "Requires current project expected_scope_version and destination expected_sha256 ('missing' for an observed absent file). "
                + "Only import the specific image the user authorized for this project; never bulk-copy private history. Original is preserved; PNG copy strips metadata. "
                + "Optional max_bytes (16384..33554432) may reduce dimensions; 1048576 fits local HTML preview asset limits. "
                + "No provider call or image quota, no user attachment, no publication. Main chat only. Return the confirmed project path to Coding; coding_adopt cannot import private images.",
                "image", ToolSpec.Status.IMPLEMENTED, fields, Arrays.asList("image_ref", "path", "expected_sha256", "expected_scope_version"));
    }
    @Override public ToolSpec declaration() { return spec; }
    @Override public boolean canDelegate() { return false; }
    @Override public String auditDetail(Map<String,Object> arguments) { return "Copy one selected image into this conversation's project"; }
    @Override public CoreToolResult execute(Map<String,Object> args, CancellationToken token) {
        token.throwIfCancelled();
        if (token.isCrewRun() || !BotCatalogTool.isAvailable(context, 0, session)) return CoreToolResult.failure("Image import is available only in the main chat");
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent()) {
            if (args == null || !Arrays.asList("image_ref", "path", "expected_sha256", "expected_scope_version", "max_bytes").containsAll(args.keySet()))
                return CoreToolResult.failure("Unexpected image import argument");
            if (new LocalRunStore(context).readConversationMetadata(session).deleted) return CoreToolResult.failure("Conversation was deleted");
            String reference = ProjectImageTool.text(args, "image_ref");
            List<String> selected = ImageReferenceResolver.parse(Collections.singletonList(reference));
            ProjectScope scope = new ProjectScopeStore(context.getFilesDir()).open(session);
            String path = scope.normalizePath(ProjectImageTool.text(args, "path"));
            if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) return CoreToolResult.failure("Import destination must end in .png");
            long version = ProjectImageTool.integer(args, "expected_scope_version", 0, Long.MAX_VALUE);
            int maxBytes = args.containsKey("max_bytes") ? (int) ProjectImageTool.integer(args, "max_bytes", 16384, ProjectImageAssets.MAX_BYTES) : ProjectImageAssets.MAX_BYTES;
            final ProjectImageAssets.Image[] image = new ProjectImageAssets.Image[1];
            ProjectMutationService.CommitObserver guard = new ProjectMutationService.CommitObserver() {
                private void check() throws java.io.IOException {
                    token.throwIfCancelled();
                    if (new LocalRunStore(context).readConversationMetadata(session).deleted) throw new java.io.IOException("Conversation was deleted");
                }
                @Override public void beforeCommit(int index, String target) throws java.io.IOException { check(); }
                @Override public void beforePromotion(String target) throws java.io.IOException { check(); }
                @Override public void afterCreationChunk(String target, long copied) throws java.io.IOException { check(); }
            };
            ProjectMutationService.Result result = new ProjectMutationService(ProjectImageAssets.MAX_BYTES, guard).produceBinary(scope,
                    "captain:" + session, version, path, ProjectImageTool.text(args, "expected_sha256"), () -> {
                        ImageEditInput input = new ImageReferenceResolver(context, session).resolve(selected, token).get(0);
                        if (input.base64.length() > ((long)ProjectImageAssets.MAX_BYTES + 2) / 3 * 4) throw new java.io.IOException("Selected image exceeds the import limit");
                        image[0] = ProjectImageAssets.generated(Base64.decode(input.base64, Base64.NO_WRAP), maxBytes, token);
                        token.throwIfCancelled();
                        if (new LocalRunStore(context).readConversationMetadata(session).deleted) throw new java.io.IOException("Conversation was deleted");
                        return image[0].bytes;
                    }, token);
            JSONObject receipt = ProjectImageTool.receipt(result, path).put("source_image_ref", reference)
                    .put("original_preserved", true).put("provider_called", false);
            boolean verified = result.isSuccess() && image[0] != null && ProjectScope.sha256(image[0].bytes).equals(scope.revision(path, token));
            if (image[0] != null) receipt.put("produced_image", image[0].metadata());
            if (verified) receipt.put("output", image[0].metadata());
            receipt.put("destination_verified", verified).put("project_id", scope.id());
            return verified ? CoreToolResult.success(receipt.toString()) : CoreToolResult.failure(receipt.toString());
        } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (Exception failure) { return CoreToolResult.failure("Image import was not confirmed. Verify the selected reference, project hash/version and saved mutation evidence before retrying."); }
        catch (OutOfMemoryError failure) { return CoreToolResult.failure("Insufficient memory to import the selected image. No success is claimed."); }
    }
}
