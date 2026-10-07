package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;
import com.jarvys.agent.providers.ChatCompletionsConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenRouter Chat Completions client using only the encrypted, user-provided Bearer API key. */
public final class OpenRouterClient implements ModelProviderClient {
    public static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private final SecretStore secrets;
    private final ProviderSettings settings;
    private final ChatCompletionsConfig config;

    public OpenRouterClient(SecretStore secrets, ProviderSettings settings) {
        this(secrets, settings, ChatCompletionsConfig.openRouterDefault());
    }

    public OpenRouterClient(SecretStore secrets, ProviderSettings settings, ChatCompletionsConfig config) {
        this.secrets = secrets;
        this.settings = settings;
        this.config = config;
    }

    @Override
    public ModelReply complete(String systemPrompt, String userPrompt, List<ScreenData> images,
                               List<ToolSpec> tools, String sessionId, CancellationToken token) {
        try {
            return completeMessages(systemPrompt, messages(systemPrompt, userPrompt, images), tools, token);
        } catch (Exception error) {
            if (error instanceof IllegalStateException) throw (IllegalStateException) error;
            throw new IllegalStateException("Could not prepare OpenRouter request", error);
        }
    }

    @Override
    public ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                                           String userPrompt, String sessionId, CancellationToken token) {
        return completeConversation(systemPrompt, history, userPrompt, Collections.emptyList(), sessionId, token);
    }

    @Override
    public ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                                           String userPrompt, List<ToolSpec> tools,
                                           String sessionId, CancellationToken token) {
        return completeConversation(systemPrompt, history, userPrompt, null, tools, sessionId, token);
    }

    @Override
    public ModelReply completeConversation(String systemPrompt, List<ConversationTurn> history,
                                           String userPrompt, List<ScreenData> images, List<ToolSpec> tools,
                                           String sessionId, CancellationToken token) {
        try {
            return completeMessages(systemPrompt, conversationMessages(systemPrompt, history, userPrompt, images),
                    tools, token);
        } catch (Exception error) {
            if (error instanceof IllegalStateException) throw (IllegalStateException) error;
            throw new IllegalStateException("Could not prepare OpenRouter conversation", error);
        }
    }

    private ModelReply completeMessages(String systemPrompt, JSONArray messages, List<ToolSpec> tools,
                                         CancellationToken token) {
        config.validateEndpoint();
        String apiKey = config.getBearerKey(secrets);
        if (config.isBearerRequired() && (apiKey == null || apiKey.trim().isEmpty())) {
            throw new IllegalStateException("Save an OpenRouter API key in Jarvys settings first");
        }
        JSONObject request = new JSONObject();
        try {
            request.put("model", settings.getModel());
            request.put("messages", messages);
            request.put("stream", false);
            String tokenLimitParameter = config.getOutputTokenLimitParameterName();
            Integer outputTokenLimit = config.getOutputTokenLimit(settings.getModel());
            if (tokenLimitParameter != null && outputTokenLimit != null && outputTokenLimit > 0) {
                request.put(tokenLimitParameter, outputTokenLimit);
            }
            if (config.getTemperature() != null) request.put("temperature", config.getTemperature());
            if (!tools.isEmpty()) {
                request.put("tools", toolDeclarations(tools));
                request.put("tool_choice", "auto");
                request.put("parallel_tool_calls", false);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare OpenRouter request", e);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.trim().isEmpty()) headers.put("Authorization", "Bearer " + apiKey.trim());
        headers.putAll(config.getAdditionalHeaders());
        ProviderHttp.Response response = ProviderHttp.post(config.getEndpoint(), headers,
                request.toString().getBytes(StandardCharsets.UTF_8), token, config.followsRedirects());
        if (response.status < 200 || response.status >= 300) {
            if ((response.status == 401 || response.status == 403)
                    && config.hasSpecificAuthorizationFailureMessage()) {
                throw new IllegalStateException(config.authorizationFailureMessage(response.status));
            }
            if (response.status == 429) throw new ProviderRateLimitException(
                    config.rateLimitMessage(), response.retryAfterMillis);
            if (response.status >= 500) throw new ProviderHttpException(
                    config.serverFailureMessage(response.status),
                    response.status, response.retryAfterMillis, null);
            // Do not include provider response bodies: they may echo request material.
            if (ConversationCompactionPolicy.isContextOverflow(new IllegalStateException(response.body))) {
                throw new IllegalStateException(config.contextOverflowMessage());
            }
            throw new IllegalStateException(config.requestFailureMessage(response.status));
        }
        ModelReply parsed = parseResponse(response.body);
        return new ModelReply(parsed.text, parsed.calls, response.body, response.status, settings.getModel(),
                parsed.contextTokensUsed);
    }

    private static JSONArray conversationMessages(String systemPrompt, List<ConversationTurn> history,
                                                  String userPrompt, List<ScreenData> images) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        if (history != null) {
            for (ConversationTurn turn : history) {
                if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
                    JSONObject assistant = new JSONObject().put("role", "assistant");
                    if (!turn.content.isEmpty()) assistant.put("content", turn.content);
                    JSONArray calls = new JSONArray();
                    for (ModelReply.Call call : turn.toolCalls) {
                        calls.put(new JSONObject().put("id", call.id).put("type", "function")
                                .put("function", new JSONObject().put("name", call.name)
                                        .put("arguments", new JSONObject(call.arguments).toString())));
                    }
                    assistant.put("tool_calls", calls);
                    messages.put(assistant);
                } else if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) {
                    messages.put(new JSONObject().put("role", "tool")
                            .put("tool_call_id", turn.toolCallId).put("content", turn.content));
                } else {
                    messages.put(new JSONObject().put("role", turn.role).put("content", turn.content));
                }
            }
        }
        JSONObject user = new JSONObject().put("role", "user");
        if (images == null || images.isEmpty()) {
            user.put("content", userPrompt);
        } else {
            JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", userPrompt));
            for (ScreenData image : images) {
                if (image == null) continue;
                JSONObject imageUrl = new JSONObject().put("url", "data:image/jpeg;base64," + image.screenshotBase64);
                content.put(new JSONObject().put("type", "image_url").put("image_url", imageUrl));
            }
            user.put("content", content);
        }
        messages.put(user);
        return messages;
    }

    private static JSONArray messages(String systemPrompt, String userPrompt, List<ScreenData> images) throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        messages.put(system);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        if (images == null || images.isEmpty()) {
            user.put("content", userPrompt);
        } else {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", userPrompt));
            for (ScreenData image : images) {
                JSONObject imageUrl = new JSONObject().put("url", "data:image/jpeg;base64," + image.screenshotBase64);
                content.put(new JSONObject().put("type", "image_url").put("image_url", imageUrl));
            }
            user.put("content", content);
        }
        messages.put(user);
        return messages;
    }

    private static JSONArray toolDeclarations(List<ToolSpec> tools) throws Exception {
        JSONArray declarations = new JSONArray();
        for (ToolSpec spec : tools) {
            JSONObject function = new JSONObject();
            function.put("name", spec.name);
            function.put("description", spec.description);
            function.put("parameters", new JSONObject(spec.jsonSchema()));
            declarations.put(new JSONObject().put("type", "function").put("function", function));
        }
        return declarations;
    }

    private static ModelReply parseResponse(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IllegalStateException("OpenRouter returned no choices");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new IllegalStateException("OpenRouter response omitted message");
            List<ModelReply.Call> calls = new ArrayList<>();
            JSONArray toolCalls = message.optJSONArray("tool_calls");
            if (toolCalls != null) {
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject tool = toolCalls.getJSONObject(i);
                    JSONObject function = tool.optJSONObject("function");
                    if (function == null) continue;
                    String name = function.optString("name", "");
                    JSONObject arguments = new JSONObject(function.optString("arguments", "{}"));
                    calls.add(new ModelReply.Call(tool.optString("id", "openrouter-call-" + i),
                            name, toMap(arguments)));
                }
            }
            JSONObject usage = root.optJSONObject("usage");
            return new ModelReply(message.optString("content", ""), calls, "", null, "",
                    contextTokensFromUsage(usage));
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("Could not parse OpenRouter completion response", e);
        }
    }

    private static Integer contextTokensFromUsage(JSONObject usage) {
        if (usage == null) return null;
        int total = usage.optInt("total_tokens", 0);
        if (total <= 0) total = usage.optInt("prompt_tokens", 0) + usage.optInt("completion_tokens", 0);
        return total > 0 ? total : null;
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
