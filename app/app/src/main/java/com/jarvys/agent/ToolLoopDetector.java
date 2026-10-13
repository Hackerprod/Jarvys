package com.jarvys.agent;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Detects only consecutive identical tool errors within the current run. */
final class ToolLoopDetector {
    static final int FAILURE_THRESHOLD = 3;

    private String lastFailureSignature;
    private int repeatedFailureCount;

    String argumentsKey(ModelReply.Call call) {
        return canonical(call.arguments);
    }

    int recordOutcome(String toolName, String arguments, boolean success, String result) {
        if (success) {
            lastFailureSignature = null;
            repeatedFailureCount = 0;
            return 0;
        }
        String signature = toolName + "\n" + arguments + "\n" + sha256(result == null ? "" : result);
        if (signature.equals(lastFailureSignature)) repeatedFailureCount++;
        else {
            lastFailureSignature = signature;
            repeatedFailureCount = 1;
        }
        return repeatedFailureCount;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) encoded.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
