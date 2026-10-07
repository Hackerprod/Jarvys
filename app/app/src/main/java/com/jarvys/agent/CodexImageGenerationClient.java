package com.jarvys.agent;

import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Dedicated image request path; image bytes never pass through the chat model context. */
public final class CodexImageGenerationClient {
    public static final String DEFAULT_FORMAT = "png";
    private static final List<String> SIZES = Collections.unmodifiableList(Arrays.asList(
            "1024x1024", "1536x1024", "1024x1536", "auto"));
    private static final int MAX_DIAGNOSTIC_CHARS = 600;

    public interface RequestExecutor {
        ProviderHttp.Response execute(JSONObject request, String sessionId, CancellationToken token);
    }

    public static final class GeneratedImage {
        public final byte[] bytes;
        public final String revisedPrompt;
        public final String size;
        public final String mimeType;
        public final String outputFormat;

        GeneratedImage(byte[] bytes, String revisedPrompt, String size, String outputFormat) {
            this.bytes = bytes.clone();
            this.revisedPrompt = revisedPrompt == null ? "" : revisedPrompt;
            this.size = size == null ? "" : size;
            this.outputFormat = outputFormat == null || outputFormat.isEmpty() ? DEFAULT_FORMAT : outputFormat;
            this.mimeType = "image/" + this.outputFormat;
        }
    }

    private final ProviderSettings settings;
    private final RequestExecutor executor;

    public CodexImageGenerationClient(CodexOAuthManager oauth, ProviderSettings settings) {
        this(settings, (request, sessionId, token) -> CodexAuthenticatedRequestExecutor.execute(
                request, sessionId, token, settings.getModel(),
                (forceRefresh, currentToken) -> oauth.getValidCredentials(forceRefresh, currentToken),
                (payload, credentials, currentSession, currentToken) -> OpenAICodexResponsesClient.sendImageRequest(
                        payload, (SecretStore.CodexCredentials) credentials, currentSession, currentToken)));
    }

    CodexImageGenerationClient(ProviderSettings settings, RequestExecutor executor) {
        this.settings = settings;
        this.executor = executor;
    }

    public GeneratedImage generate(String sessionId, String prompt, String size, CancellationToken token) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw failure(CodexImageGenerationException.Kind.API, null, "empty_prompt",
                    "Enter a description for the image.", 0L, "request.validation", null);
        }
        if (size != null && !size.trim().isEmpty() && !SIZES.contains(size.trim())) {
            throw failure(CodexImageGenerationException.Kind.API, null, "unsupported_size",
                    "Supported sizes: " + android.text.TextUtils.join(", ", SIZES), 0L, "request.validation", null);
        }
        token.throwIfCancelled();
        JSONObject request = buildRequest(settings.getModel(), prompt, size);
        ProviderHttp.Response response;
        try {
            response = executor.execute(request, sessionId, token);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (CodexImageGenerationException error) {
            throw error;
        } catch (CodexHttpException authenticationFailure) {
            if (authenticationFailure.statusCode == 401) {
                ProviderHttp.Response unauthorized = new ProviderHttp.Response(401,
                        authenticationFailure.responseBody, authenticationFailure.responseBody);
                throw httpFailure(unauthorized, prompt);
            }
            String text = scrub(authenticationFailure.getMessage(), prompt, null);
            throw failure(CodexImageGenerationException.Kind.API, authenticationFailure.statusCode,
                    "http_" + authenticationFailure.statusCode, text, authenticationFailure.retryAfterMillis,
                    "authentication", authenticationFailure);
        } catch (RuntimeException transportFailure) {
            String text = transportFailure.getMessage() == null
                    ? transportFailure.getClass().getSimpleName() : transportFailure.getMessage();
            boolean session = text.toLowerCase(java.util.Locale.ROOT).contains("token refresh failed")
                    || text.toLowerCase(java.util.Locale.ROOT).contains("sign in with chatgpt");
            throw failure(session ? CodexImageGenerationException.Kind.SESSION : CodexImageGenerationException.Kind.NETWORK,
                    null, session ? "session_expired" : "network_error",
                    scrub(text, prompt, null), 0L, "transport", transportFailure);
        }
        token.throwIfCancelled();
        if (response.status < 200 || response.status >= 300) {
            throw httpFailure(response, prompt);
        }
        return parseStream(response.body, prompt);
    }

    public static List<String> supportedSizes() { return SIZES; }

    static JSONObject buildRequest(String model, String prompt, String size) {
        try {
            JSONObject imageTool = new JSONObject()
                    .put("type", "image_generation")
                    .put("action", "generate")
                    .put("output_format", DEFAULT_FORMAT);
            if (size != null && !size.trim().isEmpty()) imageTool.put("size", size.trim());
            JSONArray tools = new JSONArray().put(imageTool);
            JSONObject input = new JSONObject().put("role", "user").put("content",
                    new JSONArray().put(new JSONObject().put("type", "input_text")
                            .put("text", "Generate a new image from this user request: " + prompt)));
            return new JSONObject()
                    .put("model", model)
                    .put("store", false)
                    .put("stream", true)
                    .put("instructions", "Generate exactly one new image for the user's request. Do not use other tools or add unrelated content.")
                    .put("input", new JSONArray().put(input))
                    .put("tools", tools)
                    .put("tool_choice", new JSONObject().put("type", "image_generation"))
                    .put("parallel_tool_calls", false);
        } catch (Exception error) {
            throw new IllegalStateException("Could not prepare the Codex image-generation request", error);
        }
    }

    static GeneratedImage parseStream(String body, String prompt) {
        if (body == null || body.trim().isEmpty()) {
            throw failure(CodexImageGenerationException.Kind.INCOMPLETE, null, "empty_stream",
                    "The stream ended without an image.", 0L, "empty", null);
        }
        final List<String> payloads;
        try { payloads = dataPayloads(body); }
        catch (RuntimeException malformed) {
            throw failure(CodexImageGenerationException.Kind.INCOMPLETE, null, "malformed_stream",
                    scrub(malformed.getMessage(), prompt, null), 0L, "parse", malformed);
        }
        String lastEvent = "stream.started";
        GeneratedImage finalImage = null;
        Set<String> eventTypes = new LinkedHashSet<>();
        int eventCount = 0;
        for (String payload : payloads) {
            if (payload.trim().isEmpty() || "[DONE]".equals(payload.trim())) continue;
            final JSONObject event;
            try { event = new JSONObject(payload); }
            catch (Exception malformed) {
                throw failure(CodexImageGenerationException.Kind.INCOMPLETE, null, "malformed_event",
                        "The image stream contained an unreadable event.", 0L, scrub(lastEvent, prompt, null), malformed);
            }
            lastEvent = event.optString("type", "response.event");
            eventCount++;
            eventTypes.add(safeEventType(lastEvent));
            JSONObject response = event.optJSONObject("response");
            if (lastEvent.equals("response.failed") || lastEvent.equals("error")) {
                JSONObject error = response == null ? event.optJSONObject("error") : response.optJSONObject("error");
                throw apiEventFailure(error, prompt, lastEvent);
            }
            if (lastEvent.equals("response.incomplete")) {
                JSONObject details = response == null ? null : response.optJSONObject("incomplete_details");
                String reason = details == null ? "" : details.optString("reason", "");
                throw failure(CodexImageGenerationException.Kind.INCOMPLETE, null,
                        reason.isEmpty() ? "response_incomplete" : reason,
                        reason.isEmpty() ? "The provider marked the response incomplete." : reason,
                        0L, scrub(lastEvent, prompt, null), null);
            }
            if (lastEvent.equals("response.output_item.done")) {
                Candidate candidate = candidate(event.optJSONObject("item"), prompt, lastEvent);
                if (candidate.image != null) finalImage = candidate.image;
            }
            if (response != null) {
                JSONArray output = response.optJSONArray("output");
                if (output != null) {
                    for (int i = 0; i < output.length(); i++) {
                        Candidate candidate = candidate(output.optJSONObject(i), prompt, lastEvent);
                        if (candidate.image != null) finalImage = candidate.image;
                    }
                }
            }
            if (lastEvent.equals("response.completed")) {
                if (response != null) {
                    JSONArray output = response.optJSONArray("output");
                    if (output != null) {
                        for (int i = 0; i < output.length(); i++) {
                            JSONObject item = output.optJSONObject(i);
                            if (item != null && "image_generation_call".equals(item.optString("type"))) {
                                Candidate candidate = candidate(item, prompt, lastEvent);
                                if (candidate.image != null) finalImage = candidate.image;
                            }
                        }
                    }
                }
            }
        }
        if (finalImage == null) {
            String errorCode = "response.created".equals(lastEvent) && eventCount == 1
                    ? "stream_truncated" : "no_image_in_stream";
            String diagnostic = "events=" + android.text.TextUtils.join(",", eventTypes);
            throw failure(CodexImageGenerationException.Kind.INCOMPLETE, null, errorCode,
                    "The stream completed without a final image.", 0L, scrub(diagnostic, prompt, null), null);
        }
        return finalImage;
    }

    private static Candidate candidate(JSONObject item, String prompt, String lastEvent) {
        if (item == null || !"image_generation_call".equals(item.optString("type"))) return Candidate.EMPTY;
        String status = item.optString("status", "");
        if ("failed".equals(status)) {
            JSONObject error = item.optJSONObject("error");
            throw apiEventFailure(error, prompt, lastEvent);
        }
        String encoded = item.optString("result", "");
        if (encoded.isEmpty() || (!status.isEmpty() && !"completed".equals(status))) return Candidate.EMPTY;
        final byte[] bytes;
        try { bytes = Base64.decode(encoded, Base64.DEFAULT); }
        catch (IllegalArgumentException invalid) {
            throw failure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "invalid_base64",
                    "The provider returned image data that could not be decoded.", 0L, scrub(lastEvent, prompt, null), invalid);
        }
        if (!isPng(bytes)) {
            throw failure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "invalid_png",
                    "The provider response was not a valid PNG image.", 0L, scrub(lastEvent, prompt, null), null);
        }
        return new Candidate(new GeneratedImage(bytes, item.optString("revised_prompt", ""),
                item.optString("size", ""), item.optString("output_format", DEFAULT_FORMAT)));
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (bytes == null || bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    private static List<String> dataPayloads(String body) {
        List<String> payloads = new ArrayList<>();
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            List<String> lineObjects = new ArrayList<>();
            boolean everyLineIsObject = true;
            for (String line : trimmed.split("\\r?\\n", -1)) {
                String candidate = line.trim();
                if (candidate.isEmpty()) continue;
                try { new JSONObject(candidate); }
                catch (Exception notAnEventLine) { everyLineIsObject = false; break; }
                lineObjects.add(candidate);
            }
            if (everyLineIsObject && !lineObjects.isEmpty()) {
                payloads.addAll(lineObjects);
                return payloads;
            }
            // Pretty-printed/non-stream JSON is one payload if its individual lines are not objects.
            payloads.add(trimmed);
            return payloads;
        }
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\r?\\n", -1)) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                data.append(value);
            } else if (line.isEmpty()) {
                flushData(payloads, data);
            } else if (line.trim().startsWith("{")) {
                flushData(payloads, data);
                payloads.add(line.trim());
            }
        }
        flushData(payloads, data);
        return payloads;
    }

    private static String safeEventType(String eventType) {
        if (eventType == null || eventType.isEmpty() || eventType.length() > 120) return "unknown_event";
        for (int i = 0; i < eventType.length(); i++) {
            char c = eventType.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '.' && c != '_' && c != '-') return "unknown_event";
        }
        return eventType;
    }

    private static void flushData(List<String> payloads, StringBuilder data) {
        if (data.length() == 0) return;
        payloads.add(data.toString().trim());
        data.setLength(0);
    }

    private static CodexImageGenerationException httpFailure(ProviderHttp.Response response, String prompt) {
        ErrorDetail detail = parseError(response.rawBody);
        CodexImageGenerationException.Kind kind = classify(response.status, detail.code, detail.type, detail.message);
        String message = detail.message.isEmpty() ? "The image request was rejected by the provider." : detail.message;
        return failure(kind, response.status, scrub(detail.code, prompt, null), scrub(message, prompt, null),
                response.retryAfterMillis, scrub("http." + response.status, prompt, null), null);
    }

    private static CodexImageGenerationException apiEventFailure(JSONObject error, String prompt, String lastEvent) {
        String code = error == null ? "" : error.optString("code", "");
        String type = error == null ? "" : error.optString("type", "");
        String message = error == null ? "The provider failed while generating the image."
                : error.optString("message", "The provider failed while generating the image.");
        CodexImageGenerationException.Kind kind = classify(null, code, type, message);
        return failure(kind, null, scrub(code, prompt, null), scrub(message, prompt, null), 0L,
                scrub(lastEvent, prompt, null), null);
    }

    private static ErrorDetail parseError(String body) {
        try {
            JSONObject root = new JSONObject(body == null ? "{}" : body);
            JSONObject error = root.optJSONObject("error");
            if (error == null) error = root.optJSONObject("response") == null
                    ? root : root.optJSONObject("response").optJSONObject("error");
            if (error == null) return ErrorDetail.EMPTY;
            return new ErrorDetail(error.optString("code", ""), error.optString("type", ""),
                    error.optString("message", ""));
        } catch (Exception ignored) { return ErrorDetail.EMPTY; }
    }

    private static CodexImageGenerationException.Kind classify(Integer status, String code,
                                                                 String type, String message) {
        String text = (code + " " + type + " " + message).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("content_policy") || text.contains("safety") || text.contains("moderation")
                || text.contains("image_generation_user_error") || text.contains("policy_violation")) {
            return CodexImageGenerationException.Kind.POLICY;
        }
        if (status != null && status == 401) return CodexImageGenerationException.Kind.SESSION;
        if ((status != null && (status == 403 || status == 404)) || text.contains("model_not_found")
                || text.contains("not entitled") || text.contains("not available for this account")) {
            return CodexImageGenerationException.Kind.ACCESS;
        }
        if (status != null && status == 429) return CodexImageGenerationException.Kind.QUOTA;
        return CodexImageGenerationException.Kind.API;
    }

    static String scrub(String value, String prompt, String token) {
        if (value == null || value.isEmpty()) return "";
        String safe = value;
        if (prompt != null && !prompt.isEmpty()) safe = replaceIgnoringCase(safe, prompt, "[prompt omitted]");
        if (token != null && !token.isEmpty()) safe = safe.replace(token, "[token omitted]");
        safe = safe.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [token omitted]");
        safe = safe.replaceAll("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}", "[token omitted]");
        safe = safe.replaceAll("\\bsk-[A-Za-z0-9_-]{16,}", "[token omitted]");
        if (safe.length() > MAX_DIAGNOSTIC_CHARS) safe = safe.substring(0, MAX_DIAGNOSTIC_CHARS) + "…";
        return safe;
    }

    private static String replaceIgnoringCase(String value, String target, String replacement) {
        String foldedValue = value.toLowerCase(java.util.Locale.ROOT);
        String foldedTarget = target.toLowerCase(java.util.Locale.ROOT);
        StringBuilder result = new StringBuilder(value.length());
        int from = 0;
        int found;
        while ((found = foldedValue.indexOf(foldedTarget, from)) >= 0) {
            result.append(value, from, found).append(replacement);
            from = found + target.length();
        }
        return result.append(value, from, value.length()).toString();
    }

    private static CodexImageGenerationException failure(CodexImageGenerationException.Kind kind,
                                                          Integer status, String code, String message,
                                                          long retryAfter, String event, Throwable cause) {
        return new CodexImageGenerationException(kind, status, code, message, retryAfter, event, cause);
    }

    private static final class Candidate {
        static final Candidate EMPTY = new Candidate(null);
        final GeneratedImage image;
        Candidate(GeneratedImage image) { this.image = image; }
    }

    private static final class ErrorDetail {
        static final ErrorDetail EMPTY = new ErrorDetail("", "", "");
        final String code;
        final String type;
        final String message;
        ErrorDetail(String code, String type, String message) {
            this.code = code == null ? "" : code;
            this.type = type == null ? "" : type;
            this.message = message == null ? "" : message;
        }
    }
}
