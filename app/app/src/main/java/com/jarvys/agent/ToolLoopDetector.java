package com.jarvys.agent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Rolling per-run detector for repeated tool calls with identical, unchanged outcomes. */
final class ToolLoopDetector {
    static final int WARNING_THRESHOLD = 10;
    static final int CRITICAL_THRESHOLD = 20;
    static final int FAILURE_THRESHOLD = 3;
    private static final int HISTORY_SIZE = 30;

    private static final class Outcome {
        final String toolName;
        final String arguments;
        final String result;
        final boolean success;
        final boolean progress;

        Outcome(String toolName, String arguments, boolean success, boolean progress, String result) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.result = result;
            this.success = success;
            this.progress = progress;
        }
    }

    private final List<Outcome> history = new ArrayList<>();

    String argumentsKey(ModelReply.Call call) {
        return canonical(call.arguments);
    }

    int recentCallCount(String toolName, String arguments) {
        int count = 1;
        for (int index = history.size() - 1; index >= 0; index--) {
            Outcome outcome = history.get(index);
            if (outcome.toolName.equals(toolName) && outcome.arguments.equals(arguments)) count++;
        }
        return count;
    }

    int noProgressStreak(String toolName, String arguments) {
        String latestResult = null;
        int count = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            Outcome outcome = history.get(index);
            if (!outcome.toolName.equals(toolName) || !outcome.arguments.equals(arguments)) continue;
            if (latestResult == null) latestResult = outcome.result;
            else if (!latestResult.equals(outcome.result)) break;
            count++;
        }
        return count;
    }

    /** Error messages can contain changing temporary paths, request IDs, or timestamps. */
    int consecutiveFailureCount(String toolName, String arguments) {
        int count = 0;
        for (int index = history.size() - 1; index >= 0; index--) {
            Outcome outcome = history.get(index);
            // A changed successful observation may have repaired a failing precondition.
            // Repeating an unrelated unchanged success must not keep a failure loop alive.
            if (outcome.progress) break;
            if (!outcome.toolName.equals(toolName) || !outcome.arguments.equals(arguments)) continue;
            if (outcome.success) break;
            count++;
        }
        return count;
    }

    boolean record(String toolName, String arguments, boolean success, String result) {
        String outcomeKey = (success ? "success\n" : "failure\n") + result;
        boolean progress = success;
        if (success) for (int index = history.size() - 1; index >= 0; index--) {
            Outcome previous = history.get(index);
            if (!previous.toolName.equals(toolName) || !previous.arguments.equals(arguments)) continue;
            progress = !outcomeKey.equals(previous.result);
            break;
        }
        history.add(new Outcome(toolName, arguments, success, progress, outcomeKey));
        if (history.size() > HISTORY_SIZE) history.remove(0);
        return progress;
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?>) {
            List<String> keys = new ArrayList<>();
            for (Object key : ((Map<?, ?>) value).keySet()) keys.add(String.valueOf(key));
            Collections.sort(keys, Comparator.naturalOrder());
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(((Map<?, ?>) value).get(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable<?>) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) {
                if (result.length() > 1) result.append(',');
                result.append(canonical(item));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return JSONObject.quote(String.valueOf(value));
    }
}
