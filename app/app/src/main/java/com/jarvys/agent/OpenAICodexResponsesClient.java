package com.jarvys.agent;

import android.os.Build;
import android.util.Log;
import com.jarvys.agent.device.ScreenData;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Responses API transport using the exact ChatGPT OAuth/Codex headers and endpoint in the
 * references.
 */
public final class OpenAICodexResponsesClient implements ModelProviderClient {
  public static final String ENDPOINT = "https://chatgpt.com/backend-api/codex/responses";
  private static final String TAG = "JarvysCodex";
  private static final String OPENAI_BETA = "responses=experimental";
  private final ProviderSettings settings;
  private final CodexAuthenticatedRequestExecutor.CredentialProvider credentialProvider;
  private final ProviderHttp.ConnectionFactory connectionFactory;

  public OpenAICodexResponsesClient(CodexOAuthManager oauth, ProviderSettings settings) {
    this(settings, (forceRefresh, token) -> oauth.getValidCredentials(forceRefresh, token),
        ProviderHttp.DEFAULT_CONNECTION_FACTORY);
  }

  OpenAICodexResponsesClient(ProviderSettings settings,
      CodexAuthenticatedRequestExecutor.CredentialProvider credentialProvider,
      ProviderHttp.ConnectionFactory connectionFactory) {
    this.settings = settings;
    this.credentialProvider = java.util.Objects.requireNonNull(credentialProvider);
    this.connectionFactory = java.util.Objects.requireNonNull(connectionFactory);
  }

  @Override
  public ModelReply complete(
      String systemPrompt,
      String userPrompt,
      List<ScreenData> images,
      List<ToolSpec> tools,
      String sessionId,
      CancellationToken token) {
    return execute(buildRequest(systemPrompt, userPrompt, images, tools), sessionId, token, false);
  }

  @Override
  public ModelReply completeConversation(
      String systemPrompt,
      List<ConversationTurn> history,
      String userPrompt,
      String sessionId,
      CancellationToken token) {
    return completeConversation(
        systemPrompt, history, userPrompt, java.util.Collections.emptyList(), sessionId, token);
  }

  @Override
  public ModelReply completeConversation(
      String systemPrompt,
      List<ConversationTurn> history,
      String userPrompt,
      List<ToolSpec> tools,
      String sessionId,
      CancellationToken token) {
    return completeConversation(systemPrompt, history, userPrompt, null, tools, sessionId, token);
  }

  @Override
  public ModelReply completeConversation(
      String systemPrompt,
      List<ConversationTurn> history,
      String userPrompt,
      List<ScreenData> images,
      List<ToolSpec> tools,
      String sessionId,
      CancellationToken token) {
    JSONObject request =
        buildRequest(systemPrompt, userPrompt, images, tools, history, false, null);
    return execute(request, sessionId, token, true, AttachmentModelContext.hasImages(history));
  }

  private ModelReply execute(
      JSONObject request, String sessionId, CancellationToken token, boolean logEmptyReply) {
    return execute(request, sessionId, token, logEmptyReply, false);
  }

  private ModelReply execute(
      JSONObject request,
      String sessionId,
      CancellationToken token,
      boolean logEmptyReply,
      boolean attachmentImages) {
    ProviderHttp.Response response =
        CodexAuthenticatedRequestExecutor.execute(
            request,
            sessionId,
            token,
            settings.getModel(),
            credentialProvider,
            (payload, credentials, currentSession, currentToken) ->
                send(
                    payload,
                    (SecretStore.CodexCredentials) credentials,
                    currentSession,
                    currentToken,
                    true,
                    false,
                    connectionFactory));
    if (response.status < 200 || response.status >= 300) {
      throw new CodexHttpException(
          response.status, response.rawBody, settings.getModel(), null, response.retryAfterMillis);
    }
    if (attachmentImages) AttachmentModelContext.checkImageReply(response.body);
    ModelReply parsed;
    try {
      parsed = parseResponse(response.body);
    } catch (RuntimeException failure) {
      if (logEmptyReply)
        logEmptyReply(response.status, sessionId, settings.getModel(), response.rawBody);
      throw failure;
    }
    if (logEmptyReply && parsed.text.trim().isEmpty()) {
      logEmptyReply(response.status, sessionId, settings.getModel(), response.rawBody);
    }
    return new ModelReply(
        parsed.text,
        parsed.calls,
        response.rawBody,
        response.status,
        settings.getModel(),
        parsed.contextTokensUsed,
        ResponseDiagnostics.fromCodex(response.rawBody));
  }

  private JSONObject buildRequest(
      String instructions, String userText, List<ScreenData> images, List<ToolSpec> tools) {
    return buildRequest(instructions, userText, images, tools, null, true, null);
  }

  private JSONObject buildRequest(
      String instructions,
      String userText,
      List<ScreenData> images,
      List<ToolSpec> tools,
      List<ConversationTurn> history,
      boolean includeAndroidAgentInstructions,
      ModelVariant variantOverride) {
    try {
      JSONObject request = new JSONObject();
      request.put("model", settings.getModel());
      request.put("store", false);
      request.put("stream", true);
      request.put(
          "instructions",
          includeAndroidAgentInstructions
              ? AgentPrompts.CODEX_INSTRUCTIONS + "\n\n" + instructions
              : instructions);
      request.put("input", userInput(userText, images, history));
      ModelVariant variant =
          variantOverride == null ? settings.getSelectedCodexVariant() : variantOverride;
      request.put(
          "reasoning",
          new JSONObject()
              .put("effort", variant.getReasoningEffort())
              .put("summary", variant.getReasoningSummary()));
      request.put("text", new JSONObject().put("verbosity", variant.getTextVerbosity()));
      request.put("include", new JSONArray().put("reasoning.encrypted_content"));
      if (tools != null && !tools.isEmpty()) {
        request.put("tools", responseTools(tools));
        request.put("tool_choice", "auto");
        request.put("parallel_tool_calls", false);
      }
      return request;
    } catch (Exception e) {
      throw new IllegalStateException("Could not prepare Codex Responses request", e);
    }
  }

  private static JSONArray userInput(
      String text, List<ScreenData> images, List<ConversationTurn> history) throws Exception {
    JSONArray input = new JSONArray();
    if (history != null) {
      for (ConversationTurn turn : history) {
        if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
          if (!turn.content.isEmpty())
            input.put(new JSONObject().put("role", "assistant").put("content", turn.content));
          for (ModelReply.Call call : turn.toolCalls) {
            input.put(
                new JSONObject()
                    .put("type", "function_call")
                    .put("call_id", call.id)
                    .put("name", call.name)
                    .put("arguments", new JSONObject(call.arguments).toString()));
          }
        } else if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) {
          input.put(
              new JSONObject()
                  .put("type", "function_call_output")
                  .put("call_id", turn.toolCallId)
                  .put("output", turn.content));
        } else {
          Object parts = turn.modelContent;
          if (!turn.images.isEmpty()) {
            JSONArray content =
                new JSONArray()
                    .put(new JSONObject().put("type", "input_text").put("text", turn.modelContent));
            for (ConversationTurn.Image image : turn.images) {
              content.put(
                  new JSONObject().put("type", "input_image").put("image_url", image.dataUrl()));
            }
            parts = content;
          }
          input.put(new JSONObject().put("role", turn.role).put("content", parts));
        }
      }
    }
    if (ConversationTurn.hasCurrentUserMessage(history)
        && (text == null || text.isEmpty())
        && (images == null || images.isEmpty())) return input;
    JSONArray content = new JSONArray();
    content.put(new JSONObject().put("type", "input_text").put("text", text == null ? "" : text));
    if (images != null) {
      for (ScreenData image : images) {
        if (image == null) continue;
        content.put(
            new JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64," + image.screenshotBase64));
      }
    }
    input.put(new JSONObject().put("role", "user").put("content", content));
    return input;
  }

  private static JSONArray responseTools(List<ToolSpec> tools) throws Exception {
    JSONArray declarations = new JSONArray();
    for (ToolSpec tool : tools) {
      JSONObject function = new JSONObject();
      function.put("type", "function");
      function.put("name", tool.name);
      function.put("description", tool.description);
      function.put("parameters", new JSONObject(tool.jsonSchema()));
      function.put("strict", false);
      declarations.put(function);
    }
    return declarations;
  }

  private static ProviderHttp.Response send(
      JSONObject request,
      SecretStore.CodexCredentials credentials,
      String sessionId,
      CancellationToken token) {
    return send(request, credentials, sessionId, token, true, false);
  }

  static ProviderHttp.Response sendImageRequest(
      JSONObject request,
      SecretStore.CodexCredentials credentials,
      String sessionId,
      CancellationToken token) {
    return send(request, credentials, sessionId, token, false, true);
  }

  private static ProviderHttp.Response send(
      JSONObject request,
      SecretStore.CodexCredentials credentials,
      String sessionId,
      CancellationToken token,
      boolean logErrors,
      boolean captureRetryAfter) {
    return send(request, credentials, sessionId, token, logErrors, captureRetryAfter,
        ProviderHttp.DEFAULT_CONNECTION_FACTORY);
  }

  private static ProviderHttp.Response send(
      JSONObject request,
      SecretStore.CodexCredentials credentials,
      String sessionId,
      CancellationToken token,
      boolean logErrors,
      boolean captureRetryAfter,
      ProviderHttp.ConnectionFactory connectionFactory) {
    HttpURLConnection connection = null;
    Runnable unregister = () -> {};
    try {
      connection = connectionFactory.open(ENDPOINT);
      HttpURLConnection activeConnection = connection;
      unregister = token.registerCancelAction(activeConnection::disconnect);
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(20000);
      connection.setReadTimeout(90000);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.setRequestProperty("Accept", "text/event-stream");
      connection.setRequestProperty("Authorization", "Bearer " + credentials.accessToken);
      connection.setRequestProperty("chatgpt-account-id", credentials.accountId);
      connection.setRequestProperty("OpenAI-Beta", OPENAI_BETA);
      connection.setRequestProperty("originator", "jarvys");
      connection.setRequestProperty(
          "User-Agent",
          "Jarvys/1.2.0"
              + " (Android "
              + Build.VERSION.RELEASE
              + "; "
              + (Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0])
              + ")");
      if (sessionId != null && !sessionId.isEmpty()) {
        connection.setRequestProperty("session-id", sessionId);
        connection.setRequestProperty("session_id", sessionId);
        connection.setRequestProperty("conversation_id", sessionId);
      }
      token.throwIfCancelled();
      byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(body);
        output.flush();
      }
      token.throwIfCancelled();
      int status = connection.getResponseCode();
      boolean successful = status >= 200 && status < 300;
      InputStream responseStream =
          successful ? connection.getInputStream() : connection.getErrorStream();
      String rawResponseBody = responseStream == null ? "" : readFullBody(responseStream, token);
      String responseBody = successful ? normalizeSseBody(rawResponseBody) : rawResponseBody;
      if (!successful && logErrors) {
        logHttpError(status, sessionId, rawResponseBody);
      }
      long retryAfter =
          captureRetryAfter
              ? ProviderHttp.parseRetryAfterMillis(connection.getHeaderField("Retry-After"))
              : 0L;
      return new ProviderHttp.Response(status, responseBody, rawResponseBody, retryAfter);
    } catch (java.io.InterruptedIOException e) {
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException(
          "OpenAI Codex request interrupted by STOP");
    } catch (java.io.IOException e) {
      if (token.isCancelled())
        throw new java.util.concurrent.CancellationException(
            "OpenAI Codex request cancelled by STOP");
      String detail =
          e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
      if (logErrors)
        Log.e(
            TAG,
            "Request failed before a complete HTTP response. requestHeaders="
                + safeRequestHeaders(sessionId)
                + " cause="
                + detail,
            e);
      throw new ProviderTransportException("OpenAI Codex request failed: " + detail, e);
    } finally {
      unregister.run();
      if (connection != null) connection.disconnect();
    }
  }

  private static String safeRequestHeaders(String sessionId) {
    StringBuilder headers =
        new StringBuilder("{Content-Type=application/json; charset=utf-8")
            .append(", Accept=text/event-stream")
            .append(", Authorization=Bearer [REDACTED]")
            .append(", chatgpt-account-id=[REDACTED]")
            .append(", OpenAI-Beta=")
            .append(OPENAI_BETA)
            .append(", originator=jarvys")
            .append(", User-Agent=Jarvys/1.2.0 (Android ")
            .append(Build.VERSION.RELEASE)
            .append("; ")
            .append(Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0])
            .append(')');
    if (sessionId != null && !sessionId.isEmpty()) {
      headers
          .append(", session-id=")
          .append(sessionId)
          .append(", session_id=")
          .append(sessionId)
          .append(", conversation_id=")
          .append(sessionId);
    }
    return headers.append('}').toString();
  }

  private static void logHttpError(int status, String sessionId, String body) {
    final int chunkSize = 3000;
    int chunkCount = Math.max(1, (body.length() + chunkSize - 1) / chunkSize);
    Log.e(
        TAG,
        "HTTP status="
            + status
            + " requestHeaders="
            + safeRequestHeaders(sessionId)
            + " errorBodyChars="
            + body.length()
            + " errorBodyChunks="
            + chunkCount);
    if (body.isEmpty()) {
      Log.e(TAG, "errorBody[1/1]=<empty>");
      return;
    }
    for (int start = 0, index = 1; start < body.length(); start += chunkSize, index++) {
      int end = Math.min(body.length(), start + chunkSize);
      Log.e(TAG, "errorBody[" + index + "/" + chunkCount + "]=" + body.substring(start, end));
    }
  }

  private static void logEmptyReply(int status, String sessionId, String model, String rawBody) {
    final int chunkSize = 3000;
    int chunkCount = Math.max(1, (rawBody.length() + chunkSize - 1) / chunkSize);
    Log.e(
        TAG,
        "Successful response contained no visible assistant text. HTTP status="
            + status
            + " model="
            + model
            + " sessionId="
            + sessionId
            + " requestHeaders="
            + safeRequestHeaders(sessionId)
            + " rawBodyChars="
            + rawBody.length()
            + " rawBodyChunks="
            + chunkCount);
    if (rawBody.isEmpty()) {
      Log.e(TAG, "rawResponseBody[1/1]=<empty>");
      return;
    }
    for (int start = 0, index = 1; start < rawBody.length(); start += chunkSize, index++) {
      int end = Math.min(rawBody.length(), start + chunkSize);
      Log.e(
          TAG,
          "rawResponseBody[" + index + "/" + chunkCount + "]=" + rawBody.substring(start, end));
    }
  }

  private static String readFullBody(InputStream input, CancellationToken token)
      throws java.io.IOException {
    try (InputStream in = input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        token.throwIfCancelled();
        output.write(buffer, 0, read);
      }
      return output.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static String normalizeSseBody(String rawBody) {
    if (rawBody == null || rawBody.trim().startsWith("{")) return rawBody == null ? "" : rawBody;
    StringBuilder normalized = new StringBuilder();
    StringBuilder eventData = new StringBuilder();
    for (String line : rawBody.split("\\r?\\n")) {
      if (line.startsWith("data:")) {
        if (eventData.length() > 0) eventData.append('\n');
        String data = line.substring(5);
        if (data.startsWith(" ")) data = data.substring(1);
        eventData.append(data);
      } else if (line.isEmpty()) {
        appendSseEvent(normalized, eventData);
      }
    }
    appendSseEvent(normalized, eventData);
    return normalized.toString();
  }

  private static void appendSseEvent(StringBuilder normalized, StringBuilder eventData) {
    if (eventData.length() == 0) return;
    String payload = eventData.toString().trim();
    eventData.setLength(0);
    if (!"[DONE]".equals(payload)) normalized.append(payload).append('\n');
  }

  private static ModelReply parseResponse(String body) {
    if (body == null || body.trim().isEmpty())
      throw new IllegalStateException("OpenAI Codex returned an empty response");
    LinkedHashMap<String, ModelReply.Call> calls = new LinkedHashMap<>();
    Map<String, String> fallbackIds = new LinkedHashMap<>();
    StringBuilder text = new StringBuilder();
    try {
      String[] events = body.split("\\n");
      for (String event : events) {
        String payload = event.trim();
        if (payload.isEmpty() || !payload.startsWith("{")) continue;
        consumeResponse(new JSONObject(payload), calls, text, fallbackIds);
      }
    } catch (Exception e) {
      if (e instanceof IllegalStateException) throw (IllegalStateException) e;
      throw new IllegalStateException("Could not parse OpenAI Codex Responses stream", e);
    }
    return new ModelReply(
        text.toString(),
        new ArrayList<>(calls.values()),
        "",
        null,
        "",
        contextTokensFromUsage(body));
  }

  private static Integer contextTokensFromUsage(String body) {
    if (body == null) return null;
    Integer result = null;
    for (String event : body.split("\\n")) {
      String payload = event.trim();
      if (!payload.startsWith("{")) continue;
      try {
        JSONObject root = new JSONObject(payload);
        JSONObject response = root.optJSONObject("response");
        JSONObject usage =
            response == null ? root.optJSONObject("usage") : response.optJSONObject("usage");
        if (usage == null) continue;
        int tokens = usage.optInt("total_tokens", 0);
        if (tokens <= 0)
          tokens = usage.optInt("input_tokens", 0) + usage.optInt("output_tokens", 0);
        if (tokens > 0) result = tokens;
      } catch (Exception ignored) {
      }
    }
    return result;
  }

  private static void consumeResponse(
      JSONObject event, Map<String, ModelReply.Call> calls, StringBuilder text, Map<String, String> fallbackIds) throws Exception {
    JSONObject response = event.optJSONObject("response");
    JSONObject item = event.optJSONObject("item");
    if (response != null) consumeOutput(response.optJSONArray("output"), calls, text, fallbackIds);
    consumeItem(item, calls, text, fallbackIds, "output:" + event.optInt("output_index", 0));
    String eventType = event.optString("type", "");
    if ("response.output_text.delta".equals(eventType)) text.append(event.optString("delta", ""));
    if ("response.output_text.done".equals(eventType) && text.length() == 0) {
      text.append(event.optString("text", ""));
    }
  }

  private static void consumeOutput(
      JSONArray output, Map<String, ModelReply.Call> calls, StringBuilder text, Map<String, String> fallbackIds) throws Exception {
    if (output == null) return;
    for (int i = 0; i < output.length(); i++) consumeItem(output.optJSONObject(i), calls, text, fallbackIds, "output:" + i);
  }

  private static void consumeItem(
      JSONObject item, Map<String, ModelReply.Call> calls, StringBuilder text,
      Map<String, String> fallbackIds, String fallbackKey) throws Exception {
    if (item == null) return;
    String type = item.optString("type", "");
    if ("function_call".equals(type)) {
      String id = item.optString("call_id", "");
      if (id.isEmpty()) id = item.optString("id", "");
      if (id.isEmpty()) id = fallbackIds.computeIfAbsent(fallbackKey,
          ignored -> "jarvys-call-" + java.util.UUID.randomUUID());
      else {
        String previousId = fallbackIds.put(fallbackKey, id);
        if (previousId != null && !previousId.equals(id)) calls.remove(previousId);
      }
      String name = item.optString("name", "");
      String argsRaw = item.optString("arguments", "{}");
      JSONObject args = new JSONObject(argsRaw.trim().isEmpty() ? "{}" : argsRaw);
      if (!name.isEmpty())
        calls.put(
            id, new ModelReply.Call(id, name, toMap(args)));
    } else if ("message".equals(type)) {
      JSONArray content = item.optJSONArray("content");
      if (content != null) {
        for (int j = 0; j < content.length(); j++) {
          JSONObject part = content.optJSONObject(j);
          if (part != null && "output_text".equals(part.optString("type"))) {
            String outputText = part.optString("text", "");
            if (!outputText.isEmpty() && !text.toString().contains(outputText)) {
              if (text.length() > 0) text.append('\n');
              text.append(outputText);
            }
          }
        }
      }
    }
  }

  private static Map<String, Object> toMap(JSONObject object) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    java.util.Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = object.get(key);
      if (value instanceof JSONObject) value = toMap((JSONObject) value);
      else if (value instanceof JSONArray) value = toList((JSONArray) value);
      result.put(key, value == JSONObject.NULL ? null : value);
    }
    return result;
  }

  private static List<Object> toList(JSONArray array) throws Exception {
    List<Object> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      Object value = array.get(i);
      if (value instanceof JSONObject) value = toMap((JSONObject) value);
      else if (value instanceof JSONArray) value = toList((JSONArray) value);
      result.add(value == JSONObject.NULL ? null : value);
    }
    return result;
  }
}
