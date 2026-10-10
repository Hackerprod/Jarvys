package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public final class FactoryConfig {
    public static final Set<String> SUPPORTED = CapabilityCatalog.SUPPORTED;
    public final String appId;
    public final String name;
    public final String entryPoint;
    public final Set<String> capabilities;
    public final DocumentBroker documentBroker;
    public static final class DocumentBroker {
        public final String packageName, certificateSha256;
        private DocumentBroker(String packageName, String certificateSha256) {
            this.packageName = packageName; this.certificateSha256 = certificateSha256;
        }
    }

    private FactoryConfig(String appId, String name, String entryPoint, Set<String> capabilities, DocumentBroker documentBroker) {
        this.documentBroker = documentBroker;
        this.appId = appId; this.name = name; this.entryPoint = entryPoint;
        this.capabilities = Collections.unmodifiableSet(capabilities);
    }
    public static FactoryConfig parse(String text, String installedPackage) throws FactoryException {
        if (installedPackage == null) throw new FactoryException("INVALID_CONFIG", "Installed identity required.");
        return parseInternal(text, installedPackage);
    }
    public static FactoryConfig parsePreview(String text) throws FactoryException { return parseInternal(text, null); }
    private static FactoryConfig parseInternal(String text, String installedPackage) throws FactoryException {
        try {
            JSONObject object = StrictJson.object(text, 8192);
            exactKeys(object, "schemaVersion", "appId", "name", "entryPoint", "capabilities", "documentBroker");
            if (!(object.get("schemaVersion") instanceof Integer) || object.getInt("schemaVersion") != 1)
                throw new FactoryException("INVALID_CONFIG", "Unsupported application configuration version.");
            String appId = string(object, "appId");
            if (!appId.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,15}") || appId.length() > 160 || (installedPackage != null && !appId.equals(installedPackage)))
                throw new FactoryException("INVALID_CONFIG", "Application identity does not match the installed package.");
            String name = string(object, "name");
            if (name.trim().isEmpty() || name.length() > 80 || containsControl(name))
                throw new FactoryException("INVALID_CONFIG", "Invalid application name.");
            String entry = string(object, "entryPoint");
            if (!entry.startsWith("www/") || !safeAssetPath(entry) || !entry.endsWith(".html"))
                throw new FactoryException("INVALID_CONFIG", "Entry point must be a local HTML asset under www/.");
            JSONArray declared = object.getJSONArray("capabilities");
            if (declared.length() > SUPPORTED.size()) throw new FactoryException("INVALID_CONFIG", "Too many declared capabilities.");
            Set<String> capabilities = new LinkedHashSet<>();
            for (int i = 0; i < declared.length(); i++) {
                Object value = declared.get(i);
                if (!(value instanceof String) || !SUPPORTED.contains(value) || !capabilities.add((String) value))
                    throw new FactoryException("INVALID_CONFIG", "Unknown or duplicate application capability.");
            }
            DocumentBroker broker = null;
            if (object.has("documentBroker")) {
                if (!capabilities.contains("documents") && !capabilities.contains("browser") && !capabilities.contains("maps") && !capabilities.contains("phone")) throw new FactoryException("INVALID_CONFIG", "Host broker requires documents, browser, maps or phone capability.");
                JSONObject metadata = object.getJSONObject("documentBroker");
                exactKeys(metadata, "packageName", "certificateSha256");
                String host = string(metadata, "packageName"), certificate = string(metadata, "certificateSha256");
                if (!CapabilityCatalog.DOCUMENT_BROKER_PACKAGES.contains(host) || !certificate.matches("[a-f0-9]{64}"))
                    throw new FactoryException("INVALID_CONFIG", "Invalid pinned document broker.");
                broker = new DocumentBroker(host, certificate);
            }
            if (installedPackage != null && (capabilities.contains("documents") || capabilities.contains("browser") || capabilities.contains("maps") || capabilities.contains("phone")) && broker == null)
                throw new FactoryException("INVALID_CONFIG", "Documents, browser, maps or phone requires a pinned compatible Jarvys broker.");
            return new FactoryConfig(appId, name, entry, capabilities, broker);
        } catch (JSONException e) {
            throw new FactoryException("INVALID_CONFIG", "Invalid application configuration.");
        }
    }
    static boolean safeAssetPath(String path) {
        if (path == null || path.length() > 240 || !path.matches("[a-zA-Z0-9_./-]+")) return false;
        for (String segment : path.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        return true;
    }
    static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    static String string(JSONObject object, String key) throws JSONException, FactoryException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new FactoryException("INVALID_ARGUMENT", key + " must be a string.");
        return (String) value;
    }
    static void exactKeys(JSONObject object, String... keys) throws FactoryException {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(keys));
        Iterator<String> actual = object.keys();
        while (actual.hasNext()) if (!allowed.contains(actual.next()))
            throw new FactoryException("INVALID_ARGUMENT", "Unknown field.");
    }
}
