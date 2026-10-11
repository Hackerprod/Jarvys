package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Fixed per-message grammar, independent of signed per-artifact storage admission. */
public final class StorageRequest {
    private StorageRequest() { }
    // Reserve space for the bridge envelope, a 64-character request ID and response metadata.
    public static final int MAX_RESULT_BYTES = ResourceBudgets.BRIDGE_MESSAGE_BYTES - 1024;
    public static final int MAX_VALUE_BYTES = MAX_RESULT_BYTES;
    // A shortest encoded key plus a separator costs four bytes. Actual pages also stop by bytes.
    public static final int MAX_PAGE_KEYS = MAX_RESULT_BYTES / 4;
    public static void validate(String method, JSONObject args) throws FactoryException {
        try {
            if (args == null || args.toString().getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) throw invalid();
            switch (method) {
                case "storage.get": case "storage.remove":
                    FactoryConfig.exactKeys(args, "key"); key(FactoryConfig.string(args, "key")); break;
                case "storage.set":
                    FactoryConfig.exactKeys(args, "key", "value"); key(FactoryConfig.string(args, "key"));
                    value(FactoryConfig.string(args, "value")); break;
                case "storage.list": FactoryConfig.exactKeys(args); break;
                case "storage.listPage":
                    FactoryConfig.exactKeys(args, "afterKey", "limit");
                    page(args.has("afterKey") ? FactoryConfig.string(args, "afterKey") : null,
                            args.has("limit") ? DatabaseRequest.integer(args, "limit", 1, MAX_PAGE_KEYS) : 256); break;
                default: throw invalid();
            }
        } catch (JSONException failure) { throw invalid(); }
    }
    static void key(String key) throws FactoryException {
        if (key == null || !key.matches("[a-zA-Z0-9_.:-]{1,96}")) throw invalid();
    }
    static void page(String afterKey, int limit) throws FactoryException {
        if (afterKey != null) key(afterKey);
        if (limit < 1 || limit > MAX_PAGE_KEYS) throw invalid();
    }
    static void value(String value) throws FactoryException {
        if (value == null || value.length() > MAX_VALUE_BYTES || value.indexOf('\0') >= 0 ||
                value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) throw invalid();
        BackupJson.unicode(value);
        // Admission must guarantee the whole value remains retrievable without truncation.
        if (encodedStringBytes(value) > MAX_RESULT_BYTES) throw invalid();
    }
    static void checkResultValue(String value) throws FactoryException {
        if (value.length() > MAX_VALUE_BYTES || encodedStringBytes(value) > MAX_RESULT_BYTES)
            throw new FactoryException("TOO_LARGE", "Storage value exceeds the bounded response envelope.");
    }
    static long encodedStringBytes(String value) {
        return JSONObject.quote(value).getBytes(StandardCharsets.UTF_8).length;
    }
    static FactoryException listTooLarge() {
        return new FactoryException("TOO_LARGE", "Storage keys exceed one response; use storage.listPage with nextAfterKey.");
    }
    private static FactoryException invalid() {
        return new FactoryException("INVALID_ARGUMENT", "Invalid storage arguments or bounded message limit exceeded.");
    }
}
