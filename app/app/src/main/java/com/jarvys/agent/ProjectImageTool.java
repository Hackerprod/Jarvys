package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.coding.ProjectMutationService;
import com.jarvys.agent.coding.ProjectScope;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.json.JSONObject;

/** Project-bound adapter. It never inherits access to private conversation images. */
final class ProjectImageTool implements CoreTool {
    static final String NAME = "project_image";
    private final Context context;
    private final ProjectScope scope;
    private final String owner, identity, account, authorizationId;
    private final Runnable authority;
    private final ProviderSettings settings;
    private final SecretStore secrets;
    private final CodexImageGenerationClient client;
    private final Function<String, String> persistReceipt;
    private final ToolSpec spec;

    ProjectImageTool(Context context, ProjectScope scope, String owner, Runnable authority,
                     Function<String, String> persistReceipt) throws IOException {
        this(context, scope, owner, authority, persistReceipt, new ProviderSettings(context), SecretStore.get(context), null);
    }
    ProjectImageTool(Context context, ProjectScope scope, String owner, Runnable authority,
                     Function<String, String> persistReceipt, ProviderSettings settings, SecretStore secrets,
                     CodexImageGenerationClient client) throws IOException {
        this.context = context.getApplicationContext(); this.scope = scope; this.owner = owner;
        this.identity = scope.durableIdentity(); this.authority = authority; this.persistReceipt = persistReceipt;
        this.settings = settings; this.secrets = secrets;
        SecretStore.CodexCredentials credentials = secrets.getCodexCredentials();
        this.account = credentials == null ? "" : credentials.accountId;
        this.authorizationId = credentials == null ? "" : credentials.authorizationId;
        this.client = client == null ? new CodexImageGenerationClient(new CodexOAuthManager(secrets), settings, this::guard, account) : client;
        Map<String,Object> properties = new LinkedHashMap<>();
        properties.put("action", enumSchema("inspect", "generate")); properties.put("path", type("string"));
        properties.put("prompt", type("string")); properties.put("expected_sha256", type("string"));
        properties.put("expected_scope_version", type("integer")); properties.put("size", enumSchema(CodexImageGenerationClient.supportedSizes().toArray(new String[0])));
        properties.put("max_bytes", type("integer"));
        Map<String,Object> reference = new LinkedHashMap<>(); reference.put("type", "object");
        Map<String,Object> fields = new LinkedHashMap<>(); fields.put("path", type("string")); fields.put("sha256", type("string"));
        reference.put("properties", fields); reference.put("required", Arrays.asList("path", "sha256")); reference.put("additionalProperties", false);
        Map<String,Object> references = new LinkedHashMap<>(); references.put("type", "array"); references.put("items", reference); references.put("maxItems", 5);
        properties.put("reference_images", references);
        Map<String,Object> schema = new LinkedHashMap<>(); schema.put("type", "object"); schema.put("properties", properties);
        schema.put("required", Arrays.asList("action", "path")); schema.put("additionalProperties", false);
        spec = new ToolSpec(NAME, "openai/codex-project-image",
                "Inspect or generate/edit a scoped project image. action=inspect with path returns actual hash, size and dimensions, never visual observation. "
                + "action=generate requires prompt, PNG output path, expected_sha256 ('missing' only for an observed absent file), and current expected_scope_version. "
                + "Optional reference_images selects at most five existing project images by path and current sha256; their verified snapshots are sent to OpenAI to edit. "
                + "Use only images and visual instructions authorized for this mission; never private history, secrets, URLs or guessed chat references. "
                + "Consumes the existing signed-in ChatGPT image quota. Use when useful for the authorized implementation, not automatically for every project. "
                + "Optional max_bytes (16384..33554432) preserves PNG transparency and may reduce dimensions; set 1048576 for local HTML preview asset limits. "
                + "The default preserves the generated PNG up to 32 MiB; size/preview warnings are returned. Output is saved in this project with a durable mutation receipt. "
                + "A provider call, timeout or partial write must not be blindly repeated. Inspect saved paths and receipts first. No upload, public hosting, shell, or user attachment is implied.",
                "image", ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Arrays.asList("action", "path"), schema);
    }

    static boolean available(Context context, String session) {
        return CodexImageGenerationTool.isAvailable(context, new ProviderSettings(context), 0, session);
    }
    @Override public ToolSpec declaration() { return spec; }
    @Override public boolean canDelegate() { return false; }
    @Override public String auditDetail(Map<String,Object> arguments) { return "Work with a scoped project image"; }

    private void guard() {
        authority.run();
        SecretStore.CodexCredentials credentials = secrets.getCodexCredentials();
        if (settings.getProvider() != ProviderSettings.Provider.OPENAI_CODEX || credentials == null
                || account.isEmpty() || !account.equals(credentials.accountId) || !authorizationId.equals(credentials.authorizationId)) throw new IllegalStateException("Project image account is no longer available for this mission");
        try {
            scope.require(ProjectScope.Capability.WRITE);
            if (!identity.equals(scope.durableIdentity())) throw new IOException("Project identity changed");
        } catch (IOException failure) { throw new IllegalStateException("Project image scope is no longer available"); }
    }

    @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token) {
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent()) {
            token.throwIfCancelled(); guard();
            if (arguments == null || !Arrays.asList("action", "path", "prompt", "expected_sha256", "expected_scope_version", "size", "max_bytes", "reference_images").containsAll(arguments.keySet()))
                return CoreToolResult.failure("Unexpected project_image argument");
            String action = text(arguments, "action"), path = scope.normalizePath(text(arguments, "path"));
            if ("inspect".equals(action)) {
                if (arguments.size() != 2) return CoreToolResult.failure("inspect accepts action and path only");
                File snapshot = ProjectImageAssets.snapshot(scope, path, null, context.getCacheDir(), token);
                try {
                    ProjectImageAssets.Image image = ProjectImageAssets.inspect(ProjectImageAssets.read(snapshot, token));
                    return CoreToolResult.success(image.metadata().put("path", path).put("scope_version", scope.version()).toString());
                } finally { snapshot.delete(); }
            }
            if (!"generate".equals(action)) return CoreToolResult.failure("action must be inspect or generate");
            if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) return CoreToolResult.failure("Generated output path must end in .png");
            String prompt = text(arguments, "prompt"), expected = text(arguments, "expected_sha256");
            long version = integer(arguments, "expected_scope_version", 0, Long.MAX_VALUE);
            int maxBytes = arguments.containsKey("max_bytes") ? (int) integer(arguments, "max_bytes", 16384, ProjectImageAssets.MAX_BYTES) : ProjectImageAssets.MAX_BYTES;
            String size = arguments.containsKey("size") ? text(arguments, "size") : null;
            if (size != null && !CodexImageGenerationClient.supportedSizes().contains(size)) return CoreToolResult.failure("Unsupported image size");
            List<Map<String,Object>> references = references(arguments.get("reference_images"));
            final ProjectImageAssets.Image[] output = new ProjectImageAssets.Image[1];
            ProjectMutationService mutations = new ProjectMutationService(ProjectImageAssets.MAX_BYTES, new ProjectMutationService.CommitObserver() {
                @Override public void beforeCommit(int index, String target) { token.throwIfCancelled(); guard(); }
                @Override public void beforePromotion(String target) { token.throwIfCancelled(); guard(); }
                @Override public void afterCreationChunk(String target, long copied) { token.throwIfCancelled(); guard(); }
            });
            ProjectMutationService.Result result = mutations.produceBinary(scope, owner, version, path, expected, () -> {
                List<File> snapshots = new ArrayList<>();
                try {
                    List<ImageEditInput> inputs = new ArrayList<>();
                    java.util.Set<String> normalizedReferences = new java.util.HashSet<>();
                    for (Map<String,Object> reference : references) {
                        String referencePath = scope.normalizePath(text(reference, "path")), sha = text(reference, "sha256");
                        if (!normalizedReferences.add(referencePath)) throw new IOException("Each reference image must be unique");
                        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("Reference needs its current SHA-256");
                        File file = ProjectImageAssets.snapshot(scope, referencePath, sha, context.getCacheDir(), token); snapshots.add(file);
                        inputs.add(AttachmentImagePreparer.prepareForEdit(context, file,
                                Math.max(1, AttachmentImagePreparer.availableHeapBytes() / Math.max(1, references.size())), token));
                    }
                    token.throwIfCancelled(); guard();
                    CodexImageGenerationClient.GeneratedImage generated = inputs.isEmpty()
                            ? client.generate(scope.conversationId(), prompt, size, token)
                            : client.edit(scope.conversationId(), prompt, size, inputs, token);
                    token.throwIfCancelled(); guard();
                    output[0] = ProjectImageAssets.generated(generated.bytes, maxBytes, token);
                    return output[0].bytes;
                } finally { for (File snapshot : snapshots) snapshot.delete(); }
            }, token);
            JSONObject receipt = receipt(result, path);
            if (output[0] != null) receipt.put("output", output[0].metadata());
            receipt.put("provider_result_received", output[0] != null).put("user_attachment_delivered", false)
                    .put("retry_guidance", "Inspect the path and mutation journal after partial, interrupted or uncertain effects; do not repeat generation to recover a receipt.");
            if (output[0] != null && output[0].bytes.length > ProjectImageAssets.PREVIEW_BYTES)
                receipt.put("warning", "PNG exceeds the 1 MiB local preview asset limit; it may be excluded from HTML preview. Use an appropriately bounded output for that purpose.");
            String text = receipt.toString();
            if (persistReceipt != null) {
                try { String ref = persistReceipt.apply(text); if (ref != null && !ref.isEmpty()) receipt.put("receipt_ref", ref); }
                catch (RuntimeException failed) { receipt.put("receipt_warning", "Project journal remains authoritative; auxiliary receipt storage failed. Do not repeat generation."); }
            }
            return result.isSuccess() ? CoreToolResult.success(receipt.toString()) : CoreToolResult.failure(receipt.toString());
        } catch (org.json.JSONException invalidReceipt) { return CoreToolResult.failure("Image receipt could not be serialized. Inspect project journal before any retry."); }
        catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
        catch (CodexImageGenerationException failure) {
            return CoreToolResult.failure("Project image provider request failed: " + failure.kind + " (" + failure.diagnosticCode() + "). No automatic retry was made; inspect existing evidence before retrying.");
        } catch (IOException | IllegalArgumentException failure) {
            return CoreToolResult.failure("Project image was not completed: " + failure.getMessage() + ". Do not assume generation or persistence succeeded.");
        } catch (RuntimeException failure) {
            return CoreToolResult.failure("Project image capability or storage became unavailable. Inspect current project files and receipts; do not replay an uncertain provider request.");
        } catch (OutOfMemoryError lowMemory) { return CoreToolResult.failure("Insufficient memory for the bounded image operation. No success is claimed; inspect project receipts before retrying."); }
    }

    static JSONObject receipt(ProjectMutationService.Result result, String path) throws org.json.JSONException {
        org.json.JSONArray applied = new org.json.JSONArray();
        for (ProjectMutationService.Applied effect : result.applied) applied.put(new JSONObject().put("path", effect.path)
                .put("before_sha256", effect.beforeSha).put("sha256", effect.afterSha));
        return new JSONObject().put("status", result.status.name()).put("path", path).put("captain_path", "/project/" + path)
                .put("scope_version", result.scopeVersion).put("journal_id", result.journalId == null ? JSONObject.NULL : result.journalId)
                .put("applied", applied).put("warnings", new org.json.JSONArray(result.cleanupWarnings)).put("message", result.message);
    }
    static String text(Map<String,Object> args, String name) {
        Object value = args.get(name); if (!(value instanceof String) || ((String)value).trim().isEmpty()) throw new IllegalArgumentException(name + " requires text");
        return (String)value;
    }
    static long integer(Map<String,Object> args, String name, long minimum, long maximum) {
        Object value = args.get(name); if (!(value instanceof Number)) throw new IllegalArgumentException(name + " requires an integer");
        try { long result = new java.math.BigDecimal(value.toString()).longValueExact(); if (result < minimum || result > maximum) throw new ArithmeticException(); return result; }
        catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(name + " is outside its integer limits"); }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> references(Object value) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List) || ((List<?>)value).size() > 5) throw new IllegalArgumentException("Select at most five project reference images");
        List<Map<String,Object>> result = new ArrayList<>(); java.util.HashSet<String> paths = new java.util.HashSet<>();
        for (Object item : (List<?>)value) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("Each reference needs path and sha256");
            Map<String,Object> reference = (Map<String,Object>)item;
            if (reference.size() != 2 || !reference.containsKey("path") || !reference.containsKey("sha256") || !paths.add(text(reference, "path")))
                throw new IllegalArgumentException("Use unique reference paths with exact SHA-256 values");
            result.add(reference);
        }
        return result;
    }
    static Map<String,Object> type(String type) { return Collections.singletonMap("type", type); }
    static Map<String,Object> enumSchema(String... values) { Map<String,Object> result = new LinkedHashMap<>(); result.put("type", "string"); result.put("enum", Arrays.asList(values)); return result; }
}
