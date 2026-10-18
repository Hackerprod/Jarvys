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
        String request = method == null || !method.startsWith("storage.") ? "storage" : method;
        try {
            if (args == null) throw invalid(request + ": args must be an object.");
            if (args.toString().getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES)
                throw invalid(request + ": args exceeds the bridge message byte limit.");
            switch (method) {
                case "storage.get": case "storage.remove":
                    FactoryConfig.exactKeys(args, "key"); key(string(args, "key", request + ".key must be a string"), request + ".key"); break;
                case "storage.set":
                    FactoryConfig.exactKeys(args, "key", "value"); key(string(args, "key", request + ".key must be a string"), request + ".key");
                    value(string(args, "value", request + ".value must be a string"), request + ".value"); break;
                case "storage.list": FactoryConfig.exactKeys(args); break;
                case "storage.listPage":
                    FactoryConfig.exactKeys(args, "afterKey", "limit");
                    String afterKey = args.has("afterKey") ? string(args, "afterKey", request + ".afterKey must be a string") : null;
                    if (afterKey != null) key(afterKey, request + ".afterKey");
                    int limit = args.has("limit") ? integer(args, "limit", 1, MAX_PAGE_KEYS, request + ".limit must be an integer from 1 through " + MAX_PAGE_KEYS + ".") : 256;
                    if (limit < 1 || limit > MAX_PAGE_KEYS) throw invalid(request + ".limit must be from 1 through " + MAX_PAGE_KEYS + ".");
                    break;
                default: throw invalid(request + ": method must be get, set, remove, list, or listPage.");
            }
        } catch (JSONException failure) { throw invalid(request + ": args fields have invalid JSON types or shapes."); }
    }
    private static String string(JSONObject object, String key, String expectation) throws JSONException, FactoryException {
        Object value;
        try { value = object.get(key); } catch (JSONException missing) { throw invalid(expectation); }
        if (!(value instanceof String)) throw invalid(expectation);
        return (String) value;
    }
    private static int integer(JSONObject object, String key, int min, int max, String expectation) throws JSONException, FactoryException {
        Object value;
        try { value = object.get(key); } catch (JSONException missing) { throw invalid(expectation); }
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid(expectation);
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw invalid(expectation);
        return (int) number;
    }
    static void key(String key) throws FactoryException {
        key(key, "storage key");
    }
    private static void key(String key, String field) throws FactoryException {
        if (key == null || !key.matches("[a-zA-Z0-9_.:-]{1,96}")) throw invalid(field + " must be 1 to 96 letters, digits, underscore, period, colon, or hyphen.");
    }
    static void page(String afterKey, int limit) throws FactoryException {
        if (afterKey != null) key(afterKey);
        if (limit < 1 || limit > MAX_PAGE_KEYS) throw invalid("storage.listPage.limit must be from 1 through " + MAX_PAGE_KEYS + ".");
    }
    static void value(String value) throws FactoryException {
        value(value, "storage value");
    }
    private static void value(String value, String field) throws FactoryException {
        if (value == null || value.length() > MAX_VALUE_BYTES || value.indexOf('\0') >= 0 ||
                value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) throw invalid(field + " must be valid text within the storage byte limit.");
        try { BackupJson.unicode(value); }
        catch (FactoryException invalid) { throw invalid(field + " must contain well-formed Unicode text."); }
        // Admission must guarantee the whole value remains retrievable without truncation.
        if (encodedStringBytes(value) > MAX_RESULT_BYTES) throw invalid(field + " must fit one bridge response.");
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
    private static FactoryException invalid(String message) { return new FactoryException("INVALID_ARGUMENT", message); }
}
