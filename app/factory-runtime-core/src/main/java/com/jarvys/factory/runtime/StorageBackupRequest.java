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
        try {
            if (args == null || args.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) throw invalid();
            if ("storage.backupExport".equals(method)) {
                required(args, "version", "keys", "filename");
                filename(string(args, "filename"));
            } else if ("storage.backupImport".equals(method)) {
                required(args, "version", "keys", "handle", "mode");
                if (!string(args, "handle").matches("[a-f0-9]{64}")) throw invalid();
                mode(string(args, "mode"));
            } else throw invalid();
            version(args, "version");
            Object selected = args.get("keys");
            if (!(selected instanceof JSONArray)) throw invalid();
            keys((JSONArray) selected);
        } catch (JSONException failure) { throw invalid(); }
    }
    static void required(JSONObject object, String... expected) throws FactoryException {
        Set<String> actual = new HashSet<>();
        for (Iterator<String> it = object.keys(); it.hasNext();) actual.add(it.next());
        if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) throw invalid();
    }
    static int version(JSONObject object, String name) throws JSONException, FactoryException {
        Object value = object.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        if (((Number) value).longValue() != 1) throw invalid();
        return 1;
    }
    static String string(JSONObject object, String name) throws JSONException, FactoryException {
        Object value = object.get(name);
        if (!(value instanceof String)) throw invalid();
        return (String) value;
    }
    static List<String> keys(JSONArray values) throws JSONException, FactoryException {
        if (values.length() < 1 || values.length() > MAX_KEYS) throw invalid();
        List<String> result = new ArrayList<>(); String previous = null;
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String)) throw invalid();
            String name = key((String) value);
            if (previous != null && previous.compareTo(name) >= 0) throw invalid();
            result.add(name); previous = name;
        }
        return result;
    }
    static String key(String value) throws FactoryException {
        if (value == null || !value.matches("[a-zA-Z0-9_.:-]{1,96}")) throw invalid();
        return value;
    }
    static void filename(String value) throws FactoryException {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || value.endsWith(".") || value.contains("..")) throw invalid();
    }
    static void mode(String value) throws FactoryException {
        if (!"insertOnly".equals(value) && !"replaceSelectedKeys".equals(value)) throw invalid();
    }
    static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid storage backup arguments or limit exceeded."); }
}
