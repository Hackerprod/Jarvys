package com.jarvys.agent;

import android.os.Build;
import android.util.Log;
import com.jarvys.agent.device.ScreenData;
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
  static final String OUTPUT_PROVIDER = ProviderSettings.Provider.OPENAI_CODEX.name();
  static final String OUTPUT_API = "responses";
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
    return completeConversation(systemPrompt, history, userPrompt, images, tools, sessionId, token, null);
  }

  @Override
  public ModelReply completeConversation(
      String systemPrompt,
      List<ConversationTurn> history,
      String userPrompt,
      List<ScreenData> images,
      List<ToolSpec> tools,
      String sessionId,
      CancellationToken token,
      TextDeltaListener listener) {
    JSONObject request =
        buildRequest(systemPrompt, userPrompt, images, tools, history, false, null);
    return execute(request, sessionId, token, AttachmentModelContext.hasImages(history), listener);
  }

  private ModelReply execute(
      JSONObject request,
      String sessionId,
      CancellationToken token,
      boolean attachmentImages) {
    return execute(request, sessionId, token, attachmentImages, null);
  }

  private ModelReply execute(
      JSONObject request,
      String sessionId,
      CancellationToken token,
      boolean attachmentImages,
      TextDeltaListener listener) {
    String requestModel = request.optString("model", settings.getModel());
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
                    connectionFactory,
                    listener));
    if (response.status < 200 || response.status >= 300) {
      throw new CodexHttpException(
          response.status, response.rawBody, settings.getModel(), null, response.retryAfterMillis);
    }
    if (attachmentImages) AttachmentModelContext.checkImageReply(response.body);
    ModelReply parsed = parseResponse(response.body);
    return new ModelReply(
        parsed.text,
        parsed.calls,
        response.rawBody,
        response.status,
        settings.getModel(),
        parsed.contextTokensUsed,
        ResponseDiagnostics.fromCodex(response.rawBody),
        identifyReasoningItems(parsed.providerOutputItems, requestModel));
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
      String model = settings.getModel();
      request.put("model", model);
      request.put("store", false);
      request.put("stream", true);
      request.put(
          "instructions",
          includeAndroidAgentInstructions
              ? AgentPrompts.CODEX_INSTRUCTIONS + "\n\n" + instructions
              : instructions);
      request.put("input", userInput(userText, images, history, model));
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
        request.put("parallel_tool_calls", true);
      }
      return request;
    } catch (Exception e) {
      throw new IllegalStateException("Could not prepare Codex Responses request", e);
    }
  }

  static JSONArray userInput(
      String text, List<ScreenData> images, List<ConversationTurn> history) throws Exception {
    return userInput(text, images, history, null);
  }

  static JSONArray userInput(
      String text, List<ScreenData> images, List<ConversationTurn> history, String currentModel) throws Exception {
    JSONArray input = new JSONArray();
    if (history != null) {
      for (ConversationTurn turn : history) {
        if (turn.kind == ConversationTurn.Kind.MODEL_ERROR) continue;
        if (turn.kind == ConversationTurn.Kind.MESSAGE && "assistant".equals(turn.role)
            && turn.modelContent.trim().isEmpty() && turn.images.isEmpty()) continue;
        if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
          if (!turn.providerOutputItems.isEmpty()) {
            appendProviderOutputItems(input, turn, currentModel);
            continue;
          }
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
        } else if (!turn.providerOutputItems.isEmpty()) {
          appendProviderOutputItems(input, turn, currentModel);
        } else {
          String modelContent = turn.renderedModelContent("responses");
          Object parts = modelContent;
          if (!turn.images.isEmpty()) {
            JSONArray content =
                new JSONArray()
                    .put(new JSONObject().put("type", "input_text").put("text", modelContent));
            for (ConversationTurn.Image image : turn.images) {
              content.put(
                  new JSONObject().put("type", "input_image").put("image_url", image.dataUrl()));
            }
            parts = content;
          }
          input.put(new JSONObject().put("role", turn.providerMessageRole()).put("content", parts));
        }
      }
    }
    boolean hasPrompt = text != null && !text.trim().isEmpty();
    boolean hasImage = false;
    if (images != null) for (ScreenData image : images) hasImage |= image != null;
    if (!hasPrompt && !hasImage) {
      if (input.length() == 0) throw new IllegalArgumentException("Responses input requires a prompt, image, or transcript.");
      return input;
    }
    JSONArray content = new JSONArray();
    if (hasPrompt || !hasImage) {
      content.put(new JSONObject().put("type", "input_text").put("text", text == null ? "" : text));
    }
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

  private static void appendProviderOutputItems(JSONArray input, ConversationTurn turn, String currentModel) throws Exception {
    Map<String, ModelReply.Call> calls = new LinkedHashMap<>();
    for (ModelReply.Call call : turn.toolCalls) calls.put(call.id, call);
    boolean emittedMessage = false;
    for (ModelReply.ProviderOutputItem item : turn.providerOutputItems) {
      switch (item.kind) {
        case REASONING:
          if (!item.belongsTo(OUTPUT_PROVIDER, OUTPUT_API, currentModel)) break;
          try {
            input.put(new JSONObject(item.value));
          } catch (Exception invalidStoredItem) {
            throw new IllegalArgumentException("Stored Codex reasoning item is invalid");
          }
          break;
        case MESSAGE:
          if (!item.value.isEmpty()) {
            input.put(new JSONObject().put("role", "assistant").put("content", item.value));
            emittedMessage = true;
          }
          break;
        case TOOL_CALL:
          ModelReply.Call call = calls.get(item.value);
          if (call == null) throw new IllegalArgumentException("Stored Codex tool output item is invalid");
          input.put(new JSONObject().put("type", "function_call").put("call_id", call.id)
              .put("name", call.name).put("arguments", new JSONObject(call.arguments).toString()));
          break;
      }
    }
    if (turn.kind == ConversationTurn.Kind.TOOL_CALLS && turn.providerOutputItems.stream()
        .noneMatch(item -> item.kind == ModelReply.ProviderOutputItem.Kind.TOOL_CALL)) {
      for (ModelReply.Call call : turn.toolCalls)
        input.put(new JSONObject().put("type", "function_call").put("call_id", call.id)
            .put("name", call.name).put("arguments", new JSONObject(call.arguments).toString()));
    }
    if (!emittedMessage && turn.kind == ConversationTurn.Kind.MESSAGE && !turn.content.isEmpty()
        && turn.providerOutputItems.stream().noneMatch(item -> item.kind == ModelReply.ProviderOutputItem.Kind.MESSAGE))
      input.put(new JSONObject().put("role", "assistant").put("content", turn.modelContent));
  }

  private static List<ModelReply.ProviderOutputItem> identifyReasoningItems(
      List<ModelReply.ProviderOutputItem> items, String model) {
    List<ModelReply.ProviderOutputItem> identified = new ArrayList<>();
    for (ModelReply.ProviderOutputItem item : items) {
      identified.add(item.withIdentity(OUTPUT_PROVIDER, OUTPUT_API, model));
    }
    return identified;
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
    return sendImageRequest(request, credentials, sessionId, token, ProviderHttp.DEFAULT_CONNECTION_FACTORY);
  }

  static ProviderHttp.Response sendImageRequest(
      JSONObject request,
      SecretStore.CodexCredentials credentials,
      String sessionId,
      CancellationToken token,
      ProviderHttp.ConnectionFactory connectionFactory) {
    return send(request, credentials, sessionId, token, false, true, connectionFactory);
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
    return send(request, credentials, sessionId, token, logErrors, captureRetryAfter,
        connectionFactory, null);
  }

  private static ProviderHttp.Response send(
      JSONObject request,
      SecretStore.CodexCredentials credentials,
      String sessionId,
      CancellationToken token,
      boolean logErrors,
      boolean captureRetryAfter,
      ProviderHttp.ConnectionFactory connectionFactory,
      TextDeltaListener listener) {
    HttpURLConnection connection = null;
    InputStream responseStream = null;
    Runnable unregister = () -> {};
    try {
      connection = connectionFactory.open(ENDPOINT);
      // Image requests are dispatched only to this approved origin; redirects require a new decision.
      if (captureRetryAfter) connection.setInstanceFollowRedirects(false);
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
      NativeEffectDispatchScope.beforeDispatch();
      try (OutputStream output = connection.getOutputStream()) {
        output.write(body);
        output.flush();
      }
      token.throwIfCancelled();
      int status = connection.getResponseCode();
      boolean successful = status >= 200 && status < 300;
      responseStream =
          successful ? connection.getInputStream() : connection.getErrorStream();
      String rawResponseBody = responseStream == null ? "" : CodexResponseStream.read(
          responseStream, token,
          captureRetryAfter ? CodexImageGenerationClient.MAX_RESPONSE_BYTES : Integer.MAX_VALUE,
          successful, listener);
      String responseBody = successful ? normalizeSseBody(rawResponseBody) : rawResponseBody;
      if (!successful && logErrors) {
        logHttpError(status, sessionId, rawResponseBody);
      }
      long retryAfter =
          captureRetryAfter
              ? ProviderHttp.parseRetryAfterMillis(connection.getHeaderField("Retry-After"))
              : 0L;
      return new ProviderHttp.Response(status, responseBody, rawResponseBody, retryAfter);
    } catch (java.io.IOException e) {
      token.throwIfCancelled(); // Preserve genuine STOP; a socket timeout never manufactures it.
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
      if (responseStream != null) {
        try { responseStream.close(); } catch (java.io.IOException ignored) { }
      }
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

  private static String normalizeSseBody(String rawBody) {
    if (rawBody != null) rawBody = CodexResponseStream.stripBom(rawBody);
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
    if (!"[DONE]".equals(payload)) normalized.append(CodexResponseStream.parseObject(payload).toString()).append('\n');
  }

  static ModelReply parseResponse(String body) {
    if (body == null || body.trim().isEmpty())
      throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
    LinkedHashMap<String, ModelReply.Call> calls = new LinkedHashMap<>();
    Map<String, String> fallbackIds = new LinkedHashMap<>();
    StringBuilder text = new StringBuilder();
    List<ModelReply.ProviderOutputItem> providerOutputItems = new ArrayList<>();
    try {
      JSONObject completed = null;
      boolean completedEvent = false;
      // A full non-SSE response can be pretty printed. Normalized SSE is one JSON per line.
      try {
        JSONObject single = CodexResponseStream.parseObject(body.trim());
        CodexResponseStream.terminal(single, true);
        JSONObject candidate = single.optJSONObject("response");
        if (candidate == null && single.optJSONArray("output") != null) candidate = single;
        String eventType = single.optString("type", "");
        if (candidate != null
            && (candidate.optJSONArray("output") != null || "response.completed".equals(eventType))
            && (eventType.isEmpty() || "response.completed".equals(eventType))) {
          String status = candidate.optString("status", "");
          if (!status.isEmpty() && !"completed".equals(status))
            throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
          completed = candidate;
          completedEvent = "response.completed".equals(eventType);
        }
      } catch (CodexResponseException failure) {
        if (failure.kind != CodexResponseException.Kind.MALFORMED || !body.contains("\n")) throw failure;
      }
      if (completed == null) {
        String lastEventType = "";
        for (String event : body.split("\n")) {
          if (event.trim().isEmpty()) continue;
          JSONObject value = CodexResponseStream.parseObject(event);
          lastEventType = value.optString("type", "");
          if (CodexResponseStream.terminal(value, true)) {
            JSONObject response = value.optJSONObject("response");
            if (response != null) completed = response;
          }
        }
        completedEvent = "response.completed".equals(lastEventType);
      }
      if (completed == null) throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
      // Only authoritative final output may create tool calls; intermediate fragments are never executable.
      JSONArray output = completed.optJSONArray("output");
      consumeOutput(output, calls, text, fallbackIds, providerOutputItems);
      boolean hasUsefulOutput = !text.toString().trim().isEmpty() || !calls.isEmpty();
      List<JSONObject> fallbackEvents = null;
      if (!hasUsefulOutput && completedEvent) {
        fallbackEvents = responseEvents(body);
        for (JSONObject event : fallbackEvents) {
          if (!"response.output_item.done".equals(event.optString("type"))) continue;
          JSONObject item = event.optJSONObject("item");
          if (item == null) continue;
          String type = item.optString("type", "");
          String fallbackKey = "done:" + event.optString("output_index", item.optString("id", "unknown"));
          if ("function_call".equals(type)) {
            // A done event is not sufficient authority for a tool call; the item itself must be complete.
            if (!"completed".equals(item.optString("status", ""))) continue;
            consumeItem(item, calls, text, fallbackIds, fallbackKey, providerOutputItems);
          } else if ("message".equals(type)
              && (!item.has("status") || "completed".equals(item.optString("status", "")))) {
            consumeItem(item, calls, text, fallbackIds, fallbackKey, providerOutputItems);
          }
        }
      }
      if (!hasUsefulOutput && text.toString().trim().isEmpty() && completedEvent) {
        if (fallbackEvents == null) fallbackEvents = responseEvents(body);
        StringBuilder deltas = completedTextDeltas(fallbackEvents);
        if (deltas.length() > 0) text.append(deltas);
      }
      if (output == null && text.length() == 0 && calls.isEmpty())
        throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
    } catch (Exception e) {
      if (e instanceof CodexResponseException) throw (CodexResponseException) e;
      throw new CodexResponseException(CodexResponseException.Kind.MALFORMED);
    }
    return new ModelReply(
        text.toString(),
        new ArrayList<>(calls.values()),
        "",
        null,
        "",
        contextTokensFromUsage(body),
        ResponseDiagnostics.unknown(),
        providerOutputItems);
  }

  private static List<JSONObject> responseEvents(String body) {
    try {
      return java.util.Collections.singletonList(CodexResponseStream.parseObject(body.trim()));
    } catch (CodexResponseException singleObjectFailure) {
      if (!body.contains("\n")) throw singleObjectFailure;
    }
    List<JSONObject> events = new ArrayList<>();
    for (String line : body.split("\n")) {
      if (!line.trim().isEmpty()) events.add(CodexResponseStream.parseObject(line));
    }
    return events;
  }

  private static StringBuilder completedTextDeltas(List<JSONObject> events) {
    StringBuilder text = new StringBuilder();
    String previousItem = null;
    for (JSONObject event : events) {
      if (!"response.output_text.delta".equals(event.optString("type", ""))) continue;
      String delta = event.optString("delta", "");
      if (delta.isEmpty()) continue;
      int outputIndex = event.optInt("output_index", -1);
      String itemId = event.optString("item_id", "");
      String item = outputIndex >= 0 ? "index:" + outputIndex
          : itemId.isEmpty() ? "unknown" : "id:" + itemId;
      if (previousItem != null && !previousItem.equals(item)) text.append('\n');
      text.append(delta);
      previousItem = item;
    }
    return text;
  }

  private static Integer contextTokensFromUsage(String body) {
    if (body == null) return null;
    Integer result = null;
    try {
      body = CodexResponseStream.parseObject(body).toString();
    } catch (CodexResponseException ignored) { /* Normalized stream contains multiple objects. */ }
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

  private static void consumeOutput(
      JSONArray output, Map<String, ModelReply.Call> calls, StringBuilder text,
      Map<String, String> fallbackIds, List<ModelReply.ProviderOutputItem> providerOutputItems) throws Exception {
    if (output == null) return;
    for (int i = 0; i < output.length(); i++) {
      if (output.optJSONObject(i) == null) throw new CodexResponseException(CodexResponseException.Kind.MALFORMED);
      consumeItem(output.getJSONObject(i), calls, text, fallbackIds, "output:" + i, providerOutputItems);
    }
  }

  private static void consumeItem(
      JSONObject item, Map<String, ModelReply.Call> calls, StringBuilder text,
      Map<String, String> fallbackIds, String fallbackKey,
      List<ModelReply.ProviderOutputItem> providerOutputItems) throws Exception {
    if (item == null) return;
    String type = item.optString("type", "");
    if ("reasoning".equals(type)) {
      providerOutputItems.add(ModelReply.ProviderOutputItem.reasoning(item.toString()));
    } else if ("function_call".equals(type)) {
      String status = item.optString("status", "");
      if (!status.isEmpty() && !"completed".equals(status))
        throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
      String id = item.optString("call_id", "");
      if (id.isEmpty()) id = item.optString("id", "");
      if (id.isEmpty()) id = fallbackIds.computeIfAbsent(fallbackKey,
          ignored -> "jarvys-call-" + java.util.UUID.randomUUID());
      else {
        String previousId = fallbackIds.put(fallbackKey, id);
        if (previousId != null && !previousId.equals(id)) calls.remove(previousId);
      }
      String name = item.optString("name", "");
      if (name.isEmpty()) throw new CodexResponseException(CodexResponseException.Kind.MALFORMED);
      Map<String, Object> parsedArguments = java.util.Collections.emptyMap();
      String invalidArgumentsReason = null;
      Object rawArguments = item.opt("arguments");
      String argsRaw = rawArguments instanceof String ? (String) rawArguments : "{}";
      if (rawArguments != null && !(rawArguments instanceof String)) {
        invalidArgumentsReason = "Tool arguments must be a JSON string";
      } else if (!argsRaw.trim().isEmpty()) {
        try {
          parsedArguments = toMap(CodexResponseStream.parseObject(argsRaw));
        } catch (Exception malformedArguments) {
          invalidArgumentsReason = "Malformed tool arguments JSON object";
        }
      }
      calls.put(id, new ModelReply.Call(id, name, parsedArguments, invalidArgumentsReason));
      providerOutputItems.add(ModelReply.ProviderOutputItem.toolCall(id));
    } else if ("message".equals(type)) {
      JSONArray content = item.optJSONArray("content");
      if (content != null) {
        for (int j = 0; j < content.length(); j++) {
          JSONObject part = content.optJSONObject(j);
          if (part != null && "output_text".equals(part.optString("type"))) {
            String outputText = part.optString("text", "");
            if (!outputText.isEmpty()) {
              if (text.length() > 0) text.append('\n');
              text.append(outputText);
              providerOutputItems.add(ModelReply.ProviderOutputItem.message(outputText));
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
