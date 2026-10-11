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
        try {
            if (args == null || args.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES)
                throw DatabaseRequest.invalid();
            if ("database.backupExport".equals(method)) {
                required(args, "version", "tables", "filename");
                String filename = DatabaseRequest.string(args, "filename");
                if (!filename.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || filename.endsWith(".") || filename.contains(".."))
                    throw DatabaseRequest.invalid();
            } else if ("database.backupImport".equals(method)) {
                required(args, "version", "handle", "tables", "mode");
                if (!DatabaseRequest.string(args, "handle").matches("[a-f0-9]{64}")) throw DatabaseRequest.invalid();
                mode(DatabaseRequest.string(args, "mode"));
            } else throw DatabaseRequest.invalid();
            DatabaseRequest.integer(args, "version", 1, 1000);
            tables(args.getJSONArray("tables"));
        } catch (JSONException e) { throw DatabaseRequest.invalid(); }
    }

    static void required(JSONObject object, String... names) throws FactoryException {
        DatabaseRequest.exact(object, names);
        if (object.length() != names.length) throw DatabaseRequest.invalid();
        for (String name : names) if (!object.has(name)) throw DatabaseRequest.invalid();
    }
    static List<String> tables(JSONArray values) throws JSONException, FactoryException {
        if (values.length() < 1 || values.length() > DatabaseRequest.MAX_TABLES) throw DatabaseRequest.invalid();
        List<String> result = new ArrayList<>();
        String previous = null;
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String)) throw DatabaseRequest.invalid();
            String name = DatabaseRequest.identifier((String) value);
            if (previous != null && previous.compareTo(name) >= 0) throw DatabaseRequest.invalid();
            result.add(name); previous = name;
        }
        return result;
    }
    static void mode(String value) throws FactoryException {
        if (!"insertOnly".equals(value) && !"replaceSelectedTables".equals(value)) throw DatabaseRequest.invalid();
    }
}
