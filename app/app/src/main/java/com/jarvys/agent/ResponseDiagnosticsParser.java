package com.jarvys.agent;

import static com.jarvys.agent.ResponseDiagnostics.Completion;
import static com.jarvys.agent.ResponseDiagnostics.ItemFinality;
import static com.jarvys.agent.ResponseDiagnostics.Origin;
import static com.jarvys.agent.ResponseDiagnostics.TotalSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Pure, best-effort extraction. Parse failure never changes the client's exception contract. */
final class ResponseDiagnosticsParser {
  // Bound additive work only. Clients still retain/parse their original response as before.
  static final int MAX_BODY_CHARACTERS = 1024 * 1024;
  static final int MAX_ITEMS = 1024;
  static final int MAX_JSON_DEPTH = 128;

  private ResponseDiagnosticsParser() {}

  static ResponseDiagnostics chatCompletions(String body) {
    State state = new State();
    if (body != null && body.length() > MAX_BODY_CHARACTERS) {
      state.uncertain("DIAGNOSTIC_LIMIT");
      return state.build();
    }
    try {
      JSONObject root = object(body);
      state.responseId(root);
      state.usage = readUsage(root, false, state);
      JSONObject error = root.optJSONObject("error");
      if (error != null) {
        state.terminal(Completion.FAILED);
        state.reason = errorReason(error);
      }
      JSONArray choices = root.optJSONArray("choices");
      if (choices != null) {
        if (choices.length() > MAX_ITEMS) throw new DiagnosticLimitException();
        Set<Integer> indexes = new LinkedHashSet<>();
        String firstReason = null;
        boolean sameReason = true;
        for (int i = 0; i < choices.length(); i++) {
          JSONObject choice = choices.optJSONObject(i);
          if (choice == null) {
            state.uncertain("INVALID_CHOICE");
            continue;
          }
          String reason = string(choice, "finish_reason");
          if (i == 0) firstReason = reason;
          else if (!same(firstReason, reason)) sameReason = false;
          Completion completion = chatCompletion(reason);
          state.terminal(completion);
          Integer index = index(choice, "index");
          if (index != null && !indexes.add(index)) state.ambiguous("AMBIGUOUS_ITEM_IDENTITY");
          JSONObject message = choice.optJSONObject("message");
          if (message == null) {
            state.ambiguous("MISSING_ASSISTANT_MESSAGE");
            continue;
          }
          JSONArray calls = message.optJSONArray("tool_calls");
          if (calls != null && calls.length() > 0) state.hasToolCalls = true;
          if ("tool_calls".equals(reason) || "function_call".equals(reason)
              || message.optJSONObject("function_call") != null) state.hasToolCalls = true;
          if (!"assistant".equals(string(message, "role"))) {
            state.ambiguous("UNCONFIRMED_ITEM_ROLE");
            continue;
          }
          String content = string(message, "content");
          if (content == null) content = "";
          if (present(message, "content") && !(message.opt("content") instanceof String))
            state.ambiguous("UNSUPPORTED_CONTENT");
          state.chatItems.add(new ResponseDiagnostics.AssistantItem("choice:" + i,
              string(message, "id"), index, content, reason, null, Origin.CHAT_COMPLETION_CHOICE,
              finality(completion)));
        }
        if (error == null) state.reason = sameReason ? firstReason : null;
        if (!sameReason) state.warn("MULTIPLE_FINISH_REASONS");
        if (choices.length() > 1) state.ambiguous("MULTIPLE_CHOICES");
      }
    } catch (DiagnosticLimitException ignored) {
      state.uncertain("DIAGNOSTIC_LIMIT");
    } catch (Exception ignored) {
      state.uncertain("UNREADABLE_JSON");
    }
    return state.build();
  }

  static ResponseDiagnostics codex(String rawBody) {
    State state = new State();
    if (rawBody != null && rawBody.length() > MAX_BODY_CHARACTERS) {
      state.uncertain("DIAGNOSTIC_LIMIT");
      return state.build();
    }
    if (rawBody == null || rawBody.trim().isEmpty()) return state.build();
    String body = rawBody.charAt(0) == '\ufeff' ? rawBody.substring(1) : rawBody;
    try {
      if (body.trim().startsWith("{")) {
        // A complete JSON response has its own explicit status evidence. NDJSON is not SSE.
        consume(object(body), null, state);
      } else {
        consumeSse(body, state);
      }
    } catch (DiagnosticLimitException ignored) {
      state.uncertain("DIAGNOSTIC_LIMIT");
    } catch (Exception ignored) {
      state.uncertain("UNREADABLE_JSON");
    }
    return state.build();
  }

  private static void consumeSse(String raw, State state) {
    String body = raw.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder data = new StringBuilder();
    String eventName = null;
    boolean hasData = false;
    boolean pending = false;
    int start = 0;
    for (int end = 0; end < body.length(); end++) {
      if (body.charAt(end) != '\n') continue;
      String line = body.substring(start, end);
      start = end + 1;
      if (line.isEmpty()) {
        if (hasData) {
          String payload = data.toString();
          if (!"[DONE]".equals(payload.trim())) {
            try {
              consume(object(payload), eventName, state);
            } catch (DiagnosticLimitException ignored) {
              state.uncertain("DIAGNOSTIC_LIMIT");
              return;
            } catch (Exception ignored) {
              state.uncertain("UNREADABLE_SSE_EVENT");
            }
          }
        }
        data.setLength(0);
        eventName = null;
        hasData = false;
        pending = false;
      } else if (!line.startsWith(":")) {
        pending = true;
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ("data".equals(field)) {
          if (hasData) data.append('\n');
          data.append(value);
          hasData = true;
        } else if ("event".equals(field)) eventName = value;
      }
    }
    // SSE only dispatches on an empty line; EOF is never a synthetic delimiter.
    if (pending || start < body.length() && !body.substring(start).startsWith(":"))
      state.uncertain("INCOMPLETE_SSE_FRAME");
  }

  private static void consume(JSONObject event, String eventName, State state) {
    String type = string(event, "type");
    if (eventName != null && !eventName.isEmpty() && !"message".equals(eventName)) {
      if (type != null && !type.equals(eventName)) state.uncertain("CONFLICTING_EVENT_TYPE");
      if (type == null) type = eventName;
    }
    JSONObject response = event.optJSONObject("response");
    if (response == null && type == null) response = event;
    Completion eventCompletion = eventCompletion(type);
    if (eventCompletion == Completion.INCOMPLETE && response != null
        && "content_filter".equals(string(response.optJSONObject("incomplete_details"), "reason")))
      eventCompletion = Completion.FILTERED;
    if (eventCompletion != null) state.terminal(eventCompletion);
    if ("response.function_call_arguments.delta".equals(type)
        || "response.function_call_arguments.done".equals(type)) state.hasToolCalls = true;
    if ("error".equals(type)) {
      state.terminal(Completion.FAILED);
      String reason = errorReason(event);
      if (reason != null) state.reason = reason;
    }
    boolean terminalSnapshot = false;
    if (response != null) {
      state.responseId(response);
      String status = string(response, "status");
      Completion statusCompletion = responseCompletion(status, response);
      if (statusCompletion != null) {
        state.terminal(statusCompletion);
        terminalSnapshot = statusCompletion == Completion.SUCCEEDED;
      }
      if (eventCompletion != null && status != null && statusCompletion == null)
        state.uncertain("CONFLICTING_TERMINAL_STATUS");
      if (state.terminal != null && ("in_progress".equals(status) || "queued".equals(status)))
        state.uncertain("CONFLICTING_TERMINAL_STATUS");
      JSONObject details = response.optJSONObject("incomplete_details");
      String reason = string(details, "reason");
      if (reason != null && (eventCompletion == Completion.SUCCEEDED
          || statusCompletion == Completion.SUCCEEDED))
        state.uncertain("CONFLICTING_TERMINAL_STATUS");
      JSONObject error = response.optJSONObject("error");
      if (error != null) {
        state.terminal(Completion.FAILED);
        reason = errorReason(error);
      }
      if (reason != null) state.reason = reason;
      ResponseDiagnostics.Usage reportedUsage = readUsage(response, true, state);
      if (reportedUsage != null) state.usage = reportedUsage;
      JSONArray output = response.optJSONArray("output");
      if (output != null) {
        if (output.length() > MAX_ITEMS) throw new DiagnosticLimitException();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < output.length(); i++) {
          JSONObject item = output.optJSONObject(i);
          if (item == null) {
            state.ambiguous("UNSUPPORTED_OUTPUT_ITEM");
            continue;
          }
          String id = string(item, "id");
          if (id != null && !ids.add(id)) state.ambiguous("AMBIGUOUS_ITEM_IDENTITY");
          consumeItem(item, i, terminalSnapshot || eventCompletion == Completion.SUCCEEDED,
              state);
        }
      }
    }
    JSONObject item = event.optJSONObject("item");
    if (item != null)
      consumeItem(item, index(event, "output_index"), "response.output_item.done".equals(type),
          state);
    if ("response.output_text.delta".equals(type) || "response.output_text.done".equals(type)) {
      MutableItem target = state.item(string(event, "item_id"), index(event, "output_index"));
      Integer contentIndex = index(event, "content_index");
      if (contentIndex == null) {
        state.ambiguous("UNCONFIRMED_CONTENT_INDEX");
        contentIndex = -1;
      }
      Part part = target.part(contentIndex);
      if ("response.output_text.delta".equals(type)) {
        String delta = string(event, "delta");
        if (delta != null) {
          if (part.done) state.ambiguous("TEXT_AFTER_PART_DONE");
          part.deltas.append(delta);
        }
      } else {
        String text = string(event, "text");
        if (text != null) part.snapshot(text, true, state);
      }
    }
  }

  private static void consumeItem(JSONObject item, Integer index, boolean done, State state) {
    String type = string(item, "type");
    if ("function_call".equals(type)) {
      state.hasToolCalls = true;
      return;
    }
    if ("reasoning".equals(type)) return;
    if (!"message".equals(type)) {
      state.ambiguous("UNSUPPORTED_OUTPUT_ITEM");
      return;
    }
    MutableItem target = state.item(string(item, "id"), index);
    String role = string(item, "role");
    if (role != null) {
      if (target.role != null && !target.role.equals(role)) state.ambiguous("CONFLICTING_ITEM_ROLE");
      target.role = role;
    }
    String phase = string(item, "phase");
    if (phase != null) {
      if (target.phase != null && !target.phase.equals(phase)) state.ambiguous("CONFLICTING_ITEM_PHASE");
      target.phase = phase;
    }
    String status = string(item, "status");
    if (status != null) {
      if (target.finality == ItemFinality.COMPLETED && !"completed".equals(status)
          || target.finality == ItemFinality.INCOMPLETE && !"incomplete".equals(status))
        state.ambiguous("CONFLICTING_ITEM_STATUS");
      target.status = status;
    }
    if ("incomplete".equals(status)) target.finality = ItemFinality.INCOMPLETE;
    else if ("completed".equals(status) || done && status == null) {
      if (target.finality == ItemFinality.INCOMPLETE) state.ambiguous("CONFLICTING_ITEM_STATUS");
      target.finality = ItemFinality.COMPLETED;
    } else if (status != null) {
      target.finality = ItemFinality.UNKNOWN;
      if (done) state.ambiguous("CONFLICTING_ITEM_STATUS");
    }
    JSONArray content = item.optJSONArray("content");
    if (content == null) return;
    for (int i = 0; i < content.length(); i++) {
      JSONObject part = content.optJSONObject(i);
      if (part == null || !"output_text".equals(string(part, "type"))) {
        state.ambiguous("UNSUPPORTED_CONTENT");
        continue;
      }
      String text = string(part, "text");
      if (text != null) target.part(i).snapshot(text, done || "completed".equals(status), state);
    }
  }

  private static Completion chatCompletion(String reason) {
    if ("stop".equals(reason) || "tool_calls".equals(reason) || "function_call".equals(reason))
      return Completion.SUCCEEDED;
    if ("length".equals(reason)) return Completion.INCOMPLETE;
    if ("content_filter".equals(reason)) return Completion.FILTERED;
    return Completion.UNKNOWN;
  }

  private static Completion eventCompletion(String type) {
    if ("response.completed".equals(type)) return Completion.SUCCEEDED;
    if ("response.incomplete".equals(type)) return Completion.INCOMPLETE;
    if ("response.failed".equals(type)) return Completion.FAILED;
    if ("response.cancelled".equals(type)) return Completion.CANCELLED;
    return null;
  }

  private static Completion responseCompletion(String status, JSONObject response) {
    if ("completed".equals(status)) return Completion.SUCCEEDED;
    if ("incomplete".equals(status)) {
      String reason = string(response.optJSONObject("incomplete_details"), "reason");
      return "content_filter".equals(reason) ? Completion.FILTERED : Completion.INCOMPLETE;
    }
    if ("failed".equals(status)) return Completion.FAILED;
    if ("cancelled".equals(status)) return Completion.CANCELLED;
    return null;
  }

  private static ItemFinality finality(Completion completion) {
    if (completion == Completion.SUCCEEDED) return ItemFinality.COMPLETED;
    if (completion == Completion.INCOMPLETE || completion == Completion.FILTERED)
      return ItemFinality.INCOMPLETE;
    return ItemFinality.UNKNOWN;
  }

  private static ResponseDiagnostics.Usage usage(JSONObject object, boolean codex, State state) {
    if (object == null) return null;
    String inputName = codex ? "input_tokens" : "prompt_tokens";
    String outputName = codex ? "output_tokens" : "completion_tokens";
    Long input = count(object, inputName, state);
    Long output = count(object, outputName, state);
    Long total = count(object, "total_tokens", state);
    Long cached = count(object.optJSONObject(inputName + "_details"), "cached_tokens", state);
    Long reasoning = count(object.optJSONObject(outputName + "_details"), "reasoning_tokens", state);
    for (String field : new String[] {inputName + "_details", outputName + "_details"}) {
      if (present(object, field) && object.optJSONObject(field) == null) state.warn("INVALID_USAGE");
    }
    TotalSource source = total == null ? TotalSource.UNAVAILABLE : TotalSource.REPORTED;
    boolean inconsistent = false;
    if (input != null && output != null) {
      if (Long.MAX_VALUE - input < output) inconsistent = true;
      else if (total != null && total != input + output) inconsistent = true;
      else if (total == null && !present(object, "total_tokens")) {
        total = input + output;
        source = TotalSource.CALCULATED;
      }
    }
    if (input != null && cached != null && cached > input) inconsistent = true;
    if (output != null && reasoning != null && reasoning > output) inconsistent = true;
    if (total != null && (input != null && input > total || output != null && output > total))
      inconsistent = true;
    if (inconsistent) state.warn("INCONSISTENT_USAGE");
    return new ResponseDiagnostics.Usage(input, output, total, cached, reasoning, source, inconsistent);
  }

  private static ResponseDiagnostics.Usage readUsage(JSONObject parent, boolean codex, State state) {
    JSONObject object = parent.optJSONObject("usage");
    if (object == null && present(parent, "usage")) state.warn("INVALID_USAGE");
    return usage(object, codex, state);
  }

  private static Long count(JSONObject object, String name, State state) {
    if (!present(object, name)) return null;
    Object value = object.opt(name);
    if (value instanceof Integer || value instanceof Long) {
      long number = ((Number) value).longValue();
      if (number >= 0) return number;
    }
    state.warn("INVALID_USAGE");
    return null;
  }

  private static Integer index(JSONObject object, String name) {
    Object value = object == null ? null : object.opt(name);
    if (value instanceof Integer || value instanceof Long) {
      long number = ((Number) value).longValue();
      if (number >= 0 && number <= Integer.MAX_VALUE) return (int) number;
    }
    return null;
  }

  private static JSONObject object(String text) throws Exception {
    if (text == null) throw new IllegalArgumentException();
    checkDepth(text);
    JSONTokener tokener = new JSONTokener(text);
    Object value = tokener.nextValue();
    if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException();
    return (JSONObject) value;
  }

  private static void checkDepth(String text) {
    int depth = 0;
    char quote = 0;
    boolean escaped = false;
    for (int i = 0; i < text.length(); i++) {
      char character = text.charAt(i);
      if (quote != 0) {
        if (escaped) escaped = false;
        else if (character == '\\') escaped = true;
        else if (character == quote) quote = 0;
      } else if (character == '"' || character == '\'') quote = character;
      else if (character == '{' || character == '[') {
        if (++depth > MAX_JSON_DEPTH) throw new DiagnosticLimitException();
      } else if (character == '}' || character == ']') depth--;
    }
  }

  private static String string(JSONObject object, String name) {
    Object value = object == null ? null : object.opt(name);
    return value instanceof String ? (String) value : null;
  }

  private static boolean present(JSONObject object, String name) {
    return object != null && object.has(name) && !object.isNull(name);
  }

  private static String errorReason(JSONObject error) {
    String code = string(error, "code");
    return code == null ? string(error, "type") : code;
  }

  private static boolean same(Object one, Object two) {
    return one == null ? two == null : one.equals(two);
  }

  private static final class DiagnosticLimitException extends RuntimeException {
    DiagnosticLimitException() { super(null, null, false, false); }
  }

  private static final class Part {
    final StringBuilder deltas = new StringBuilder();
    String text;
    boolean done;

    void snapshot(String value, boolean complete, State state) {
      if (done && !same(text, value)) state.ambiguous("CONFLICTING_CONTENT_SNAPSHOT");
      text = value;
      done = done || complete;
    }

    String text() {
      if (done || deltas.length() == 0) return text == null ? "" : text;
      return (text == null ? "" : text) + deltas;
    }
  }

  private static final class MutableItem {
    final String key;
    String id;
    Integer index;
    String role;
    String status;
    String phase;
    ItemFinality finality = ItemFinality.UNKNOWN;
    final Map<Integer, Part> parts = new TreeMap<>();

    MutableItem(String key, String id, Integer index) {
      this.key = key;
      this.id = id;
      this.index = index;
    }

    Part part(int index) {
      Part part = parts.get(index);
      if (part == null) {
        part = new Part();
        parts.put(index, part);
      }
      return part;
    }

    ResponseDiagnostics.AssistantItem build() {
      StringBuilder text = new StringBuilder();
      for (Part part : parts.values()) text.append(part.text());
      return new ResponseDiagnostics.AssistantItem(key, id, index, text.toString(), status, phase,
          Origin.CODEX_OUTPUT_ITEM, finality);
    }
  }

  private static final class State {
    Completion terminal;
    boolean uncertain;
    boolean ambiguous;
    boolean hasToolCalls;
    boolean conflictingResponseId;
    String responseId;
    String reason;
    ResponseDiagnostics.Usage usage;
    final Set<String> warnings = new LinkedHashSet<>();
    final List<ResponseDiagnostics.AssistantItem> chatItems = new ArrayList<>();
    final List<MutableItem> items = new ArrayList<>();
    final Map<String, MutableItem> byId = new HashMap<>();
    final Map<Integer, MutableItem> byIndex = new HashMap<>();

    void warn(String warning) { warnings.add(warning); }
    void uncertain(String warning) { uncertain = true; warn(warning); }
    void ambiguous(String warning) { ambiguous = true; warn(warning); }

    void terminal(Completion completion) {
      if (terminal != null && terminal != completion) uncertain("CONFLICTING_TERMINAL_STATUS");
      terminal = completion;
    }

    void responseId(JSONObject response) {
      String id = string(response, "id");
      if (id == null || id.isEmpty() || conflictingResponseId) return;
      if (responseId != null && !responseId.equals(id)) {
        responseId = null;
        conflictingResponseId = true;
        uncertain("CONFLICTING_RESPONSE_ID");
      } else responseId = id;
    }

    MutableItem item(String id, Integer index) {
      if (id != null && id.isEmpty()) id = null;
      MutableItem named = id == null ? null : byId.get(id);
      MutableItem indexed = index == null ? null : byIndex.get(index);
      if (named != null && indexed != null && named != indexed)
        ambiguous("AMBIGUOUS_ITEM_IDENTITY");
      MutableItem item = indexed != null && id != null && id.equals(indexed.id)
          ? indexed : named != null ? named : indexed;
      if (item != null && (id != null && item.id != null && !id.equals(item.id)
          || index != null && item.index != null && !index.equals(item.index))) {
        ambiguous("AMBIGUOUS_ITEM_IDENTITY");
        item = null;
      }
      if (item == null) {
        if (items.size() >= MAX_ITEMS) throw new DiagnosticLimitException();
        item = new MutableItem("output:" + items.size(), id, index);
        items.add(item);
      }
      if (id == null && index == null) ambiguous("UNCONFIRMED_ITEM_IDENTITY");
      if (item.id == null) item.id = id;
      if (item.index == null) item.index = index;
      if (id != null && !byId.containsKey(id)) byId.put(id, item);
      if (index != null && !byIndex.containsKey(index)) byIndex.put(index, item);
      return item;
    }

    ResponseDiagnostics build() {
      List<ResponseDiagnostics.AssistantItem> result = new ArrayList<>(chatItems);
      for (MutableItem item : items) {
        if ("assistant".equals(item.role)) result.add(item.build());
        else ambiguous("UNCONFIRMED_ITEM_ROLE");
      }
      Completion completion = uncertain || terminal == null ? Completion.UNKNOWN : terminal;
      String finalKey = null;
      if (!ambiguous && !hasToolCalls && completion == Completion.SUCCEEDED) {
        ResponseDiagnostics.AssistantItem candidate = null;
        for (ResponseDiagnostics.AssistantItem item : result) {
          boolean finalPhase = "final_answer".equals(item.phase);
          if (item.phase != null && !finalPhase && !"commentary".equals(item.phase)) {
            ambiguous("UNKNOWN_ITEM_PHASE");
            break;
          }
          if (finalPhase || result.size() == 1 && item.phase == null) {
            if (candidate != null) {
              ambiguous("MULTIPLE_FINAL_ITEMS");
              break;
            }
            candidate = item;
          } else if (item.phase == null) ambiguous("AMBIGUOUS_FINAL_ITEM");
        }
        if (!ambiguous && candidate != null && candidate.finality == ItemFinality.COMPLETED
            && !candidate.text.trim().isEmpty()) finalKey = candidate.key;
        else if (result.size() > 1) warn("AMBIGUOUS_FINAL_ITEM");
      }
      return new ResponseDiagnostics(completion, reason, responseId, result, finalKey, usage,
          hasToolCalls, new ArrayList<>(warnings));
    }
  }
}
