package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Grammar only. Capability, installed identity and native consent are checked by the host. */
public final class DatabaseBackupRequest {
    private DatabaseBackupRequest() { }
    public static final int MAX_ARGUMENT_BYTES = 4096;

    public static void validate(String method, JSONObject args) throws FactoryException {
        String request = "database.backupExport".equals(method) || "database.backupImport".equals(method)
                ? method : "database.backup";
        try {
            if (args == null) throw invalid(request + ".args must be an object");
            if (args.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES)
                throw invalid(request + ".args exceeds the 4096-byte request limit");
            if ("database.backupExport".equals(method)) {
                requiredForMethod(args, request, "version", "tables", "filename");
                String filename = string(args, "filename", request + ".filename must be a string");
                if (!filename.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || filename.endsWith(".") || filename.contains(".."))
                    throw invalid(request + ".filename must be a simple document name of at most 120 characters");
            } else if ("database.backupImport".equals(method)) {
                requiredForMethod(args, request, "version", "handle", "tables", "mode");
                if (!string(args, "handle", request + ".handle must be an opaque 64-character lowercase hexadecimal token").matches("[a-f0-9]{64}"))
                    throw invalid(request + ".handle must be an opaque 64-character lowercase hexadecimal token");
                String mode = string(args, "mode", request + ".mode must be insertOnly or replaceSelectedTables");
                if (!"insertOnly".equals(mode) && !"replaceSelectedTables".equals(mode))
                    throw invalid(request + ".mode must be insertOnly or replaceSelectedTables");
            } else throw invalid("database.backup.method must be database.backupExport or database.backupImport");
            version(args, request);
            Object rawTables = args.get("tables");
            if (!(rawTables instanceof JSONArray)) throw invalid(request + ".tables must be an array of table names");
            tables((JSONArray) rawTables, request + ".tables");
        } catch (JSONException e) { throw invalid(request + ".args fields have invalid JSON types or shapes"); }
    }

    private static int version(JSONObject args, String request) throws JSONException, FactoryException {
        Object value;
        try { value = args.get("version"); } catch (JSONException missing) {
            throw invalid(request + ".version must be an integer from 1 through 1000");
        }
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw invalid(request + ".version must be an integer from 1 through 1000");
        long number = ((Number) value).longValue();
        if (number < 1 || number > 1000) throw invalid(request + ".version must be an integer from 1 through 1000");
        return (int) number;
    }

    private static String string(JSONObject object, String key, String expectation) throws JSONException, FactoryException {
        Object value;
        try { value = object.get(key); } catch (JSONException missing) { throw invalid(expectation); }
        if (!(value instanceof String) || ((String) value).isEmpty()) throw invalid(expectation);
        return (String) value;
    }

    private static FactoryException invalid(String message) { return new FactoryException("INVALID_ARGUMENT", message); }

    static void required(JSONObject object, String... names) throws FactoryException {
        if (object.length() != names.length)
            throw invalid("database.backupImport.archive must contain exactly these fields: " + String.join(", ", names));
        for (String name : names) if (!object.has(name))
            throw invalid("database.backupImport.archive." + name + " is required");
    }
    private static void requiredForMethod(JSONObject object, String method, String... names) throws FactoryException {
        java.util.HashSet<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(names));
        for (java.util.Iterator<String> it = object.keys(); it.hasNext();)
            if (!allowed.contains(it.next())) throw invalid(method + ".args may contain only " + String.join(", ", names));
        for (String name : names) if (!object.has(name)) throw invalid(method + "." + name + " is required");
    }
    static List<String> tables(JSONArray values) throws JSONException, FactoryException {
        return tables(values, "database.backupImport.tables");
    }
    private static List<String> tables(JSONArray values, String field) throws JSONException, FactoryException {
        if (values.length() < 1 || values.length() > DatabaseRequest.MAX_TABLES)
            throw invalid(field + " must contain 1 to " + DatabaseRequest.MAX_TABLES + " table names");
        List<String> result = new ArrayList<>();
        String previous = null;
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String)) throw invalid(field + " entries must be lowercase table identifiers");
            String name;
            try { name = DatabaseRequest.identifier((String) value); }
            catch (FactoryException rejected) { throw invalid(field + " entries must be lowercase nonreserved table identifiers"); }
            if (previous != null && previous.compareTo(name) >= 0)
                throw invalid(field + " entries must be sorted and unique");
            result.add(name); previous = name;
        }
        return result;
    }
    static void mode(String value) throws FactoryException {
        if (!"insertOnly".equals(value) && !"replaceSelectedTables".equals(value))
            throw invalid("database.backupImport.mode must be insertOnly or replaceSelectedTables");
    }
}
