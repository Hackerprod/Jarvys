package com.jarvys.agent;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Incremental SSE framing. Completion is a validated protocol event, not socket EOF. */
final class CodexResponseStream {
  private CodexResponseStream() {}

  static String read(InputStream input, CancellationToken token, int limit, boolean successful)
      throws IOException {
    // The caller disconnects before closing the stream, avoiding a close-time socket drain.
    try (ByteArrayOutputStream raw = new ByteArrayOutputStream()) {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      StringBuilder data = new StringBuilder();
      byte[] buffer = new byte[8192];
      Boolean json = null;
      boolean firstLine = true;
      while (true) {
        token.throwIfCancelled();
        int count = input.read(buffer);
        token.throwIfCancelled();
        if (count == -1) break;
        for (int i = 0; i < count; i++) {
          if (raw.size() >= limit) throw new IOException("Provider response exceeds its bounded image limit");
          int value = buffer[i] & 255;
          raw.write(value);
          if (!successful) continue;
          // A UTF-8 BOM is handled on the first SSE line (and stripped for JSON below).
          if (json == null && value != 0xef && value != 0xbb && value != 0xbf
              && !Character.isWhitespace((char) value)) json = value == '{';
          if (Boolean.TRUE.equals(json)) continue;
          if (value == '\n') {
            String current = line.toString(StandardCharsets.UTF_8.name());
            line.reset();
            if (firstLine) { current = stripBom(current); firstLine = false; }
            if (current.endsWith("\r")) current = current.substring(0, current.length() - 1);
            if (acceptLine(current, data)) {
              token.throwIfCancelled();
              return raw.toString(StandardCharsets.UTF_8.name());
            }
          } else line.write(value);
        }
      }
      token.throwIfCancelled();
      String result = raw.toString(StandardCharsets.UTF_8.name());
      if (!successful) return result;
      if (Boolean.TRUE.equals(json)) {
        result = stripBom(result);
        JSONObject object = parseObject(result);
        boolean complete = terminal(object, true);
        // Non-SSE can contain a full response, but never a lone nonterminal stream event.
        if (!complete && !object.optString("type", "").isEmpty())
          throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
        return result;
      }
      // An event requires its blank-line delimiter; EOF cannot finalize partial calls.
      throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
    }
  }

  private static boolean acceptLine(String line, StringBuilder data) {
    if (line.isEmpty()) {
      if (data.length() == 0) return false;
      String payload = data.toString();
      data.setLength(0);
      if ("[DONE]".equals(payload.trim()))
        throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
      return terminal(parseObject(payload), true);
    }
    if (line.startsWith("data:")) {
      if (data.length() > 0) data.append('\n');
      String value = line.substring(5);
      data.append(value.startsWith(" ") ? value.substring(1) : value);
    }
    return false;
  }

  static JSONObject parseObject(String payload) {
    try {
      // JSONObject alone accepts truncated/lenient JSON on Android.
      try (JsonReader reader = new JsonReader(new StringReader(payload))) {
        reader.setLenient(false);
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IllegalArgumentException();
        reader.skipValue();
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
      }
      return new JSONObject(payload);
    } catch (Exception failure) {
      throw new CodexResponseException(CodexResponseException.Kind.MALFORMED);
    }
  }

  static boolean terminal(JSONObject event, boolean requireStatus) {
    String type = event.optString("type", "");
    JSONObject response = event.optJSONObject("response");
    JSONObject state = response == null ? event : response;
    String status = state.optString("status", "");
    if ("response.failed".equals(type) || "failed".equals(status))
      throw new CodexResponseException(CodexResponseException.Kind.FAILED);
    if ("response.incomplete".equals(type) || "incomplete".equals(status)
        || "cancelled".equals(status))
      throw new CodexResponseException(CodexResponseException.Kind.INCOMPLETE);
    if ("error".equals(type) || (state.has("error") && !state.isNull("error")))
      throw new CodexResponseException(CodexResponseException.Kind.ERROR);
    if (!"response.completed".equals(type)) return false;
    if (response == null || response.optJSONArray("output") == null
        || (requireStatus && !"completed".equals(status))
        || (!status.isEmpty() && !"completed".equals(status)))
      throw new CodexResponseException(CodexResponseException.Kind.MALFORMED);
    return true;
  }

  static String stripBom(String value) {
    return value.startsWith("\ufeff") ? value.substring(1) : value;
  }
}
