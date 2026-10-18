package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Argument-only grammar. The native host separately checks capability, identity and consent. */
public final class StorageBackupRequest {
    public static final int MAX_ARGUMENT_BYTES = 4096, MAX_KEYS = 32;
    private StorageBackupRequest() { }

    public static void validate(String method, JSONObject args) throws FactoryException {
        String request = "storage.backupExport".equals(method) || "storage.backupImport".equals(method) ? method : "storage.backup";
        try {
            if (args == null) throw invalid(request, ".args must be an object");
            if (args.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES)
                throw invalid(request, ".args exceeds the 4096-byte request limit");
            if ("storage.backupExport".equals(method)) {
                requiredForMethod(args, request, "version", "keys", "filename");
                filename(string(args, "filename", request + ".filename must be a string"), request + ".filename");
            } else if ("storage.backupImport".equals(method)) {
                requiredForMethod(args, request, "version", "keys", "handle", "mode");
                if (!string(args, "handle", request + ".handle must be an opaque 64-character lowercase hexadecimal token").matches("[a-f0-9]{64}"))
                    throw invalid(request, ".handle must be an opaque 64-character lowercase hexadecimal token");
                mode(string(args, "mode", request + ".mode must be insertOnly or replaceSelectedKeys"), request + ".mode");
            } else throw invalid(request, ".method must be storage.backupExport or storage.backupImport");
            version(args, "version", request + ".version must be the integer 1");
            Object selected = args.get("keys");
            if (!(selected instanceof JSONArray)) throw invalid(request, ".keys must be a sorted array of supported key names");
            keys((JSONArray) selected, request + ".keys");
        } catch (JSONException failure) { throw invalid(request, ".args fields must have their documented JSON types"); }
    }
    static void required(JSONObject object, String... expected) throws FactoryException {
        Set<String> actual = new HashSet<>();
        for (Iterator<String> it = object.keys(); it.hasNext();) actual.add(it.next());
        if (!actual.equals(new HashSet<>(Arrays.asList(expected))) || actual.size() != expected.length)
            throw invalid("storage.backup", ".args must contain only the documented fields");
    }
    private static void requiredForMethod(JSONObject object, String method, String... expected) throws FactoryException {
        Set<String> allowed = new HashSet<>(Arrays.asList(expected));
        for (Iterator<String> it = object.keys(); it.hasNext();) if (!allowed.contains(it.next()))
            throw invalid(method, ".args may contain only " + String.join(", ", expected));
        for (String key : expected) if (!object.has(key)) throw invalid(method, "." + key + " is required");
    }
    static int version(JSONObject object, String name) throws JSONException, FactoryException {
        return version(object, name, "storage.backup." + name + " must be the integer 1");
    }
    private static int version(JSONObject object, String name, String expectation) throws JSONException, FactoryException {
        Object value = object.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid(expectation);
        if (((Number) value).longValue() != 1) throw invalid(expectation);
        return 1;
    }
    static String string(JSONObject object, String name) throws JSONException, FactoryException {
        return string(object, name, "storage.backup." + name + " must be a string");
    }
    private static String string(JSONObject object, String name, String expectation) throws JSONException, FactoryException {
        Object value = object.get(name);
        if (!(value instanceof String)) throw invalid(expectation);
        return (String) value;
    }
    static List<String> keys(JSONArray values) throws JSONException, FactoryException {
        return keys(values, "storage.backup.keys");
    }
    private static List<String> keys(JSONArray values, String field) throws JSONException, FactoryException {
        if (values.length() < 1 || values.length() > MAX_KEYS) throw invalid(field + " must contain 1 to " + MAX_KEYS + " names");
        List<String> result = new ArrayList<>(); String previous = null;
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String)) throw invalid(field + " entries must be supported key names");
            String name;
            try { name = key((String) value); } catch (FactoryException rejected) { throw invalid(field + " entries must match the supported key-name grammar"); }
            if (previous != null && previous.compareTo(name) >= 0) throw invalid(field + " entries must be sorted and unique");
            result.add(name); previous = name;
        }
        return result;
    }
    static String key(String value) throws FactoryException {
        if (value == null || !value.matches("[a-zA-Z0-9_.:-]{1,96}")) throw invalid();
        return value;
    }
    static void filename(String value) throws FactoryException {
        filename(value, "storage.backup.filename");
    }
    private static void filename(String value, String field) throws FactoryException {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || value.endsWith(".") || value.contains(".."))
            throw invalid(field + " must be a simple document name of at most 120 characters");
    }
    static void mode(String value) throws FactoryException {
        mode(value, "storage.backup.mode");
    }
    private static void mode(String value, String field) throws FactoryException {
        if (!"insertOnly".equals(value) && !"replaceSelectedKeys".equals(value)) throw invalid(field + " must be insertOnly or replaceSelectedKeys");
    }
    static FactoryException invalid() { return invalid("storage.backup", "archive fields must match the frozen v1 format and limits"); }
    private static FactoryException invalid(String message) { return new FactoryException("INVALID_ARGUMENT", message + "."); }
    private static FactoryException invalid(String method, String expectation) { return invalid(method + expectation); }
}
