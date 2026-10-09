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
    static final int MAX_RESPONSE_BYTES = 96 * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    public static final String DEFAULT_FORMAT = "png";
    static final String EDIT_MODEL = "gpt-image-2";
    public static final int MAX_EDIT_REFERENCES = 5;
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
    private final RequestExecutor editExecutor;

    public CodexImageGenerationClient(CodexOAuthManager oauth, ProviderSettings settings) {
        this(settings, (request, sessionId, token) -> CodexAuthenticatedRequestExecutor.execute(
                request, sessionId, token, settings.getModel(),
                (forceRefresh, currentToken) -> oauth.getValidCredentials(forceRefresh, currentToken),
                (payload, credentials, currentSession, currentToken) -> OpenAICodexResponsesClient.sendImageRequest(
                        payload, (SecretStore.CodexCredentials) credentials, currentSession, currentToken)),
                (request, sessionId, token) -> CodexAuthenticatedRequestExecutor.execute(
                        request, sessionId, token, EDIT_MODEL,
                        (forceRefresh, currentToken) -> oauth.getValidCredentials(forceRefresh, currentToken),
                        (payload, credentials, currentSession, currentToken) -> OpenAICodexImagesClient.sendEditRequest(
                                payload, (SecretStore.CodexCredentials) credentials, currentSession, currentToken)));
    }

    /** Revalidates live mission/account authority for initial dispatch and the existing 401 retry. */
    CodexImageGenerationClient(CodexOAuthManager oauth, ProviderSettings settings, Runnable guard, String account) {
        this(settings, (request, sessionId, token) -> scopedRequest(request, sessionId, token, settings.getModel(), oauth, guard, account, false),
                (request, sessionId, token) -> scopedRequest(request, sessionId, token, EDIT_MODEL, oauth, guard, account, true));
    }

    private static ProviderHttp.Response scopedRequest(JSONObject request, String sessionId, CancellationToken token,
            String model, CodexOAuthManager oauth, Runnable guard, String account, boolean edit) {
        return scopedRequest(request, sessionId, token, model, guard, account,
                (refresh, currentToken) -> oauth.getValidCredentials(refresh, currentToken),
                (payload, value, currentSession, currentToken) -> edit
                        ? OpenAICodexImagesClient.sendEditRequest(payload, (SecretStore.CodexCredentials)value, currentSession, currentToken)
                        : OpenAICodexResponsesClient.sendImageRequest(payload, (SecretStore.CodexCredentials)value, currentSession, currentToken));
    }

    static ProviderHttp.Response scopedRequest(JSONObject request, String sessionId, CancellationToken token,
            String model, Runnable guard, String account,
            CodexAuthenticatedRequestExecutor.CredentialProvider credentialsSource,
            CodexAuthenticatedRequestExecutor.Sender sender) {
        return CodexAuthenticatedRequestExecutor.execute(request, sessionId, token, model,
                (refresh, currentToken) -> {
                    currentToken.throwIfCancelled(); guard.run();
                    SecretStore.CodexCredentials credentials = (SecretStore.CodexCredentials)credentialsSource.get(refresh, currentToken);
                    guard.run();
                    if (credentials == null || !account.equals(credentials.accountId)) throw new IllegalStateException("Image account changed");
                    return credentials;
                }, (payload, value, currentSession, currentToken) -> {
                    currentToken.throwIfCancelled(); guard.run();
                    SecretStore.CodexCredentials credentials = (SecretStore.CodexCredentials) value;
                    if (!account.equals(credentials.accountId)) throw new IllegalStateException("Image account changed");
                    return sender.send(payload, credentials, currentSession, currentToken);
                });
    }

    CodexImageGenerationClient(ProviderSettings settings, RequestExecutor executor) {
        this(settings, executor, executor);
    }

    CodexImageGenerationClient(ProviderSettings settings, RequestExecutor executor, RequestExecutor editExecutor) {
        this.settings = settings;
        this.executor = executor;
        this.editExecutor = editExecutor;
    }

    public GeneratedImage edit(String sessionId, String prompt, String size,
                               List<ImageEditInput> references, CancellationToken token) {
        token.throwIfCancelled();
        try {
            JSONObject request = buildEditRequest(prompt, size, references);
            ProviderHttp.Response response = editExecutor.execute(request, sessionId, token);
            token.throwIfCancelled();
            if (response == null) throw editFailure(CodexImageGenerationException.Kind.INCOMPLETE, null, "empty_response", 0, "images.edits");
            if (response.status < 200 || response.status >= 300) throw editHttpFailure(response.status, response.rawBody, response.retryAfterMillis);
            GeneratedImage image = parseEditResponse(response.body);
            token.throwIfCancelled();
            return image;
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (CodexHttpException error) {
            throw editHttpFailure(error.statusCode, error.responseBody, error.retryAfterMillis);
        } catch (CodexImageGenerationException error) {
            throw editFailure(error.kind, error.httpStatus, safeEditCode(error.errorCode), error.retryAfterMillis, "images.edits");
        } catch (OutOfMemoryError lowMemory) {
            throw editFailure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "image_memory_unavailable", 0, "images.edits");
        } catch (RuntimeException error) {
            token.throwIfCancelled();
            String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
            boolean session = message.contains("token refresh failed") || message.contains("sign in with chatgpt");
            throw editFailure(session ? CodexImageGenerationException.Kind.SESSION : CodexImageGenerationException.Kind.NETWORK,
                    null, session ? "session_expired" : "network_error", 0, "images.edits");
        }
    }

    static JSONObject buildEditRequest(String prompt, String size, List<ImageEditInput> references) {
        if (prompt == null || prompt.trim().isEmpty()) throw editFailure(CodexImageGenerationException.Kind.API, null, "empty_prompt", 0, "request.validation");
        if (size != null && !size.trim().isEmpty() && !SIZES.contains(size.trim())) throw editFailure(CodexImageGenerationException.Kind.API, null, "unsupported_size", 0, "request.validation");
        if (references == null || references.isEmpty() || references.size() > MAX_EDIT_REFERENCES) throw editFailure(CodexImageGenerationException.Kind.API, null, "invalid_references", 0, "request.validation");
        try {
            JSONArray images = new JSONArray();
            for (ImageEditInput reference : references) {
                if (reference == null) throw editFailure(CodexImageGenerationException.Kind.API, null, "invalid_references", 0, "request.validation");
                images.put(new JSONObject().put("image_url", reference.dataUrl()));
            }
            return new JSONObject().put("images", images).put("prompt", prompt).put("model", EDIT_MODEL)
                    .put("n", 1).put("quality", "auto").put("size", size == null || size.trim().isEmpty() ? "auto" : size.trim())
                    .put("background", "auto");
        } catch (CodexImageGenerationException error) {
            throw error;
        } catch (Exception error) {
            throw editFailure(CodexImageGenerationException.Kind.API, null, "invalid_request", 0, "request.validation");
        }
    }

    static GeneratedImage parseEditResponse(String body) {
        if (body == null || body.trim().isEmpty()) throw editFailure(CodexImageGenerationException.Kind.INCOMPLETE, null, "empty_response", 0, "images.edits");
        final JSONObject response;
        try {
            // Android's JSONObject parser is lenient. Check one strict JSON value before using it.
            try (android.util.JsonReader reader = new android.util.JsonReader(new java.io.StringReader(body))) {
                reader.setLenient(false);
                reader.skipValue();
                if (reader.peek() != android.util.JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            }
            org.json.JSONTokener parser = new org.json.JSONTokener(body);
            Object value = parser.nextValue();
            if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw new IllegalArgumentException();
            response = (JSONObject) value;
        } catch (Exception malformed) {
            throw editFailure(CodexImageGenerationException.Kind.INCOMPLETE, null, "malformed_response", 0, "images.edits");
        }
        if (response.has("error")) throw editHttpFailure(null, body, 0);
        JSONArray data = response.optJSONArray("data");
        if (data == null || data.length() == 0) throw editFailure(CodexImageGenerationException.Kind.INCOMPLETE, null, "missing_image", 0, "images.edits");
        if (data.length() != 1 || data.optJSONObject(0) == null) throw editFailure(CodexImageGenerationException.Kind.INCOMPLETE, null, "malformed_response", 0, "images.edits");
        Object encoded = data.optJSONObject(0).opt("b64_json");
        if (!(encoded instanceof String) || ((String) encoded).isEmpty()) throw editFailure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "invalid_base64", 0, "images.edits");
        final byte[] bytes;
        try { bytes = strictBase64((String) encoded); }
        catch (IllegalArgumentException invalid) {
            throw editFailure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "invalid_base64", 0, "images.edits");
        }
        if (!isCompletePng(bytes)) throw editFailure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "invalid_png", 0, "images.edits");
        String size = response.optString("size", "");
        if (!size.matches("[1-9][0-9]*x[1-9][0-9]*") && !"auto".equals(size)) size = "";
        return new GeneratedImage(bytes, "", size, DEFAULT_FORMAT);
    }

    static byte[] strictBase64(String encoded) {
        if (encoded == null || encoded.length() > ((long) MAX_IMAGE_BYTES + 2) / 3 * 4) throw new IllegalArgumentException("Image exceeds decode limit");
        if (!ImageEditInput.isCanonicalBase64(encoded)) throw new IllegalArgumentException("Invalid image encoding");
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
        if (!Base64.encodeToString(bytes, Base64.NO_WRAP).equals(encoded)) throw new IllegalArgumentException("Invalid image encoding");
        return bytes;
    }

    static boolean isCompletePng(byte[] bytes) {
        if (!isPng(bytes)) return false;
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (bytes == null || bytes.length < signature.length || bytes.length > MAX_IMAGE_BYTES) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        int offset = 8;
        boolean header = false;
        boolean pixels = false;
        boolean ended = false;
        while (offset <= bytes.length - 12) {
            long length = uint32(bytes, offset);
            if (length > bytes.length - offset - 12) return false;
            int count = (int) length;
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes, offset + 4, count + 4);
            if (crc.getValue() != uint32(bytes, offset + 8 + count)) return false;
            if (!header) {
                if (!"IHDR".equals(type) || count != 13) return false;
                header = true;
            } else if ("IHDR".equals(type)) return false;
            if ("IDAT".equals(type)) pixels |= count > 0;
            offset += count + 12;
            if ("IEND".equals(type)) {
                if (count != 0 || !pixels || offset != bytes.length) return false;
                ended = true;
                break;
            }
        }
        if (!ended) return false;
        try {
            android.graphics.Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (decoded == null) return false;
            decoded.recycle();
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    private static long uint32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private static CodexImageGenerationException editHttpFailure(Integer status, String body, long retryAfter) {
        ErrorDetail detail = parseError(body);
        return editFailure(classify(status, detail.code, detail.type, detail.message), status, safeEditCode(detail.code), retryAfter, "images.edits");
    }

    private static String safeEditCode(String code) {
        switch (code == null ? "" : code) {
            case "empty_prompt": case "unsupported_size": case "invalid_references": case "invalid_request":
            case "empty_response": case "malformed_response": case "missing_image": case "invalid_base64":
            case "invalid_png": case "image_memory_unavailable": case "session_expired": case "network_error":
            case "invalid_token": case "model_not_found": case "rate_limit_exceeded": case "insufficient_quota":
            case "content_policy_violation": case "image_generation_user_error": case "image_generation_not_enabled":
                return code;
            default: return "image_edit_failed";
        }
    }

    private static CodexImageGenerationException editFailure(CodexImageGenerationException.Kind kind,
                                                              Integer status, String code, long retryAfter, String event) {
        String message;
        switch (kind) {
            case SESSION: message = "Sign in with ChatGPT again to edit images."; break;
            case ACCESS: message = "Image editing is not available for this account or model."; break;
            case QUOTA: message = "The image editing quota or rate limit was reached. Try again later."; break;
            case POLICY: message = "The provider declined this image edit under its safety policy."; break;
            case NETWORK: message = "The image edit request could not be completed. Check the connection and try again."; break;
            case INVALID_IMAGE: message = "The provider did not return a valid PNG image."; break;
            case INCOMPLETE: message = "The image edit response was incomplete or unreadable."; break;
            default: message = "The image edit request was rejected or invalid."; break;
        }
        return failure(kind, status, code, message, retryAfter, event, null);
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
            throw failure(error.kind, error.httpStatus, scrub(error.errorCode, prompt, null),
                    scrub(error.apiMessage, prompt, null), error.retryAfterMillis,
                    scrub(error.lastEvent, prompt, null), null);
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
        } catch (CodexResponseException responseFailure) {
            boolean incomplete = responseFailure.kind == CodexResponseException.Kind.INCOMPLETE
                    || responseFailure.kind == CodexResponseException.Kind.MALFORMED;
            throw failure(incomplete ? CodexImageGenerationException.Kind.INCOMPLETE : CodexImageGenerationException.Kind.API,
                    null, "response_" + responseFailure.kind.name().toLowerCase(java.util.Locale.ROOT),
                    responseFailure.getMessage(), 0L, "responses.stream", responseFailure);
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
        try { if (encoded.length() > ((long) MAX_IMAGE_BYTES + 2) / 3 * 4) throw new IllegalArgumentException("Image exceeds decode limit"); bytes = Base64.decode(encoded, Base64.DEFAULT); }
        catch (IllegalArgumentException invalid) {
            throw failure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "invalid_base64",
                    "The provider returned image data that could not be decoded.", 0L, scrub(lastEvent, prompt, null), invalid);
        }
        if (!isCompletePng(bytes)) {
            throw failure(CodexImageGenerationException.Kind.INVALID_IMAGE, null, "invalid_png",
                    "The provider response was not a valid PNG image.", 0L, scrub(lastEvent, prompt, null), null);
        }
        return new Candidate(new GeneratedImage(bytes, item.optString("revised_prompt", ""),
                item.optString("size", ""), item.optString("output_format", DEFAULT_FORMAT)));
    }

    private static boolean isPng(byte[] bytes) {
        byte[] signature = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (bytes == null || bytes.length < signature.length || bytes.length > MAX_IMAGE_BYTES) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0 && (long) bounds.outWidth * bounds.outHeight <= 16L * 1024 * 1024;
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
        return new CodexImageGenerationException(kind, status, code, message, retryAfter, event, null);
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
