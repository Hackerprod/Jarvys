package com.jarvys.agent;

import android.content.Context;

import com.jarvys.agent.proactive.ProactiveConversation;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-chat-only image tool backed by the existing ChatGPT Codex sign-in. */
final class CodexImageGenerationTool implements CoreTool {
    static final String NAME = "generate_image";
    private final Context context;
    private final String sessionId;
    private final ProviderSettings settings;
    private final CodexImageGenerationClient client;
    private final SecretStore secrets;
    private final GeneratedImageStore images;
    private final LocalRunStore conversations;
    private final ToolSpec declaration;

    CodexImageGenerationTool(Context context, String sessionId, ProviderSettings settings) {
        this(context, sessionId, settings, SecretStore.get(context.getApplicationContext()));
    }

    CodexImageGenerationTool(Context context, String sessionId, ProviderSettings settings, SecretStore secrets) {
        this(context, sessionId, settings, secrets, new CodexImageGenerationClient(new CodexOAuthManager(secrets), settings));
    }

    CodexImageGenerationTool(Context context, String sessionId, ProviderSettings settings,
                              SecretStore secrets, CodexImageGenerationClient client) {
        this.context = context.getApplicationContext();
        this.sessionId = sessionId;
        this.settings = settings;
        this.client = client;
        this.secrets = secrets;
        this.images = new GeneratedImageStore(this.context);
        this.conversations = new LocalRunStore(this.context);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Collections.singletonMap("type", "string"));
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("type", "string");
        size.put("enum", CodexImageGenerationClient.supportedSizes());
        properties.put("size", size);
        Map<String, Object> references = new LinkedHashMap<>();
        references.put("type", "array");
        references.put("items", Collections.singletonMap("type", "string"));
        references.put("maxItems", CodexImageGenerationClient.MAX_EDIT_REFERENCES);
        references.put("uniqueItems", true);
        references.put("description", "Exact image_ref values for only the images the user asks to use, from this conversation's attachment metadata or generated image results. Omit for text-only generation. Never use paths, URLs, indices or guessed references.");
        properties.put("image_refs", references);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("prompt"));
        schema.put("additionalProperties", false);
        declaration = new ToolSpec(NAME, "openai/codex-image",
                this.context.getString(R.string.image_tool_description,
                        android.text.TextUtils.join(", ", CodexImageGenerationClient.supportedSizes())),
                "image", ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.singletonList("prompt"), schema);
    }

    static boolean isAvailable(Context context, ProviderSettings settings, int depth, String sessionId) {
        if (context == null) return false;
        return isAvailable(context, settings, depth, sessionId, SecretStore.get(context.getApplicationContext()));
    }

    static boolean isAvailable(Context context, ProviderSettings settings, int depth, String sessionId,
                               SecretStore secrets) {
        if (context == null || depth != 0 || sessionId == null || sessionId.isEmpty()) return false;
        if (ProactiveConversation.SESSION_ID.equals(sessionId)
                || com.jarvys.agent.tasks.ScheduledTaskConversation.SESSION_ID.equals(sessionId)) return false;
        if (settings.getProvider() != ProviderSettings.Provider.OPENAI_CODEX) return false;
        return secrets != null && secrets.getCodexCredentials() != null;
    }

    @Override public ToolSpec declaration() { return declaration; }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForAttachments()) {
            return executePrivate(arguments, token);
        }
    }

    private CoreToolResult executePrivate(Map<String, Object> arguments, CancellationToken token) {
        token.throwIfCancelled();
        if (!isAvailable(context, settings, 0, sessionId, secrets)) {
            return CoreToolResult.failure(imageString(R.string.image_error_session, "SESSION_UNAVAILABLE",
                    "ChatGPT sign-in is no longer available for this run."));
        }
        Object rawPrompt = arguments.get("prompt");
        if (!(rawPrompt instanceof String) || ((String) rawPrompt).trim().isEmpty()) {
            return CoreToolResult.failure(imageString(R.string.image_error_prompt_required));
        }
        String prompt = (String) rawPrompt;
        Object rawSize = arguments.get("size");
        if (rawSize != null && !(rawSize instanceof String)) {
            return CoreToolResult.failure(imageString(R.string.image_error_size_invalid,
                    android.text.TextUtils.join(", ", CodexImageGenerationClient.supportedSizes())));
        }
        String size = rawSize == null ? null : (String) rawSize;
        final List<ImageEditInput> inputs;
        try {
            List<String> selected = ImageReferenceResolver.parse(arguments.get("image_refs"));
            inputs = selected.isEmpty() ? Collections.emptyList()
                    : new ImageReferenceResolver(context, sessionId).resolve(selected, token);
        } catch (IllegalArgumentException invalidReference) {
            return CoreToolResult.failure(imageString(R.string.image_error_reference, invalidReference.getMessage()));
        }
        try {
            token.throwIfCancelled();
            CodexImageGenerationClient.GeneratedImage generated = inputs.isEmpty()
                    ? client.generate(sessionId, prompt, size, token)
                    : client.edit(sessionId, prompt, size, inputs, token);
            token.throwIfCancelled();
            String imageId = UUID.randomUUID().toString();
            String relativePath = images.save(sessionId, imageId, generated.bytes);
            try {
                token.throwIfCancelled();
                conversations.appendGeneratedImageEvent(sessionId, relativePath, prompt,
                        generated.revisedPrompt, generated.size, generated.mimeType);
            } catch (RuntimeException persistFailure) {
                images.deleteImage(sessionId, relativePath);
                throw persistFailure;
            }
            AgentRunUiEvent event = AgentRunUiEvent.generatedImageEvent(0L, relativePath, prompt,
                    generated.revisedPrompt, generated.mimeType, generated.size, "COMPLETED", null,
                    System.currentTimeMillis());
            AgentRunUiState.generatedImageAdded(sessionId, event);
            return CoreToolResult.success("Image " + (inputs.isEmpty() ? "generated" : "edited")
                    + " and added to this conversation. image_ref: generated:" + imageId + "; format: PNG"
                    + (generated.size.isEmpty() ? "" : "; size: " + generated.size));
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (CodexImageGenerationException failure) {
            String userMessage = formatFailure(failure);
            persistFailure(prompt, userMessage);
            reportFailure(failure);
            return CoreToolResult.failure(userMessage);
        } catch (RuntimeException failure) {
            String detail = CodexImageGenerationClient.scrub(failure.getMessage(), prompt, null);
            String userMessage = imageString(R.string.image_error_storage, detail);
            persistFailure(prompt, userMessage);
            AgentErrorReporter.report(context, "OPENAI_CODEX_IMAGE", settings.getModel(), null,
                    null, userMessage, failure.getClass().getSimpleName(), null);
            return CoreToolResult.failure(userMessage);
        }
    }

    private void persistFailure(String prompt, String message) {
        try {
            conversations.appendGeneratedImageFailure(sessionId, prompt, message);
            AgentRunUiState.generatedImageAdded(sessionId, AgentRunUiEvent.generatedImageEvent(0L, null,
                    prompt, null, "image/png", null, "FAILED", message, System.currentTimeMillis()));
        } catch (RuntimeException ignored) { }
    }

    private void reportFailure(CodexImageGenerationException error) {
        AgentErrorReporter.report(context, "OPENAI_CODEX_IMAGE", settings.getModel(), error.httpStatus,
                null, error.apiMessage, error.diagnosticCode(), null);
    }

    private String formatFailure(CodexImageGenerationException error) {
        String code = error.diagnosticCode();
        String message = error.apiMessage.isEmpty() ? code : error.apiMessage;
        switch (error.kind) {
            case SESSION:
                return imageString(R.string.image_error_session, code, message);
            case ACCESS:
                return imageString(R.string.image_error_access, code, message);
            case QUOTA:
                return error.retryAfterMillis > 0
                        ? imageString(R.string.image_error_quota_retry, code, message,
                                Long.toString((error.retryAfterMillis + 999L) / 1000L))
                        : imageString(R.string.image_error_quota, code, message);
            case POLICY:
                return imageString(R.string.image_error_policy, code, message);
            case INCOMPLETE:
                return imageString(R.string.image_error_incomplete, code, message, error.lastEvent);
            case INVALID_IMAGE:
                return imageString(R.string.image_error_invalid_image, code, message);
            case NETWORK:
                return imageString(R.string.image_error_network, code, message);
            case STORAGE:
                return imageString(R.string.image_error_storage, message);
            default:
                return imageString(R.string.image_error_api, code, message);
        }
    }

    private String imageString(int resource, Object... arguments) {
        android.content.Context localized = AppLanguageRuntime.localizedContext(context);
        return localized.getString(resource, arguments);
    }
}
