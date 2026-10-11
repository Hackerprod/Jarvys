package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Closed credential grammar. Metadata/review never includes the value, its hash or its size. */
public final class SecretRequest {
    public static final int MAX_VALUE_BYTES = 32768;
    public static final int MAX_REQUEST_BYTES = MAX_VALUE_BYTES * 6 + 8192;
    private SecretRequest() { }

    public static final class Scope {
        public final String origin, header, prefix;
        public final Set<String> methods;
        private Scope(String origin, Set<String> methods, String header, String prefix) {
            this.origin = origin; this.methods = Collections.unmodifiableSet(methods);
            this.header = header; this.prefix = prefix;
        }
        public static Scope parse(JSONObject object) throws FactoryException {
            try {
                if (object == null) throw invalid();
                FactoryConfig.exactKeys(object, "origin", "methods", "header", "prefix");
                String origin = canonicalOrigin(text(object, "origin", null), true);
                Object rawMethods = object.get("methods");
                if (!(rawMethods instanceof JSONArray)) throw invalid();
                JSONArray values = (JSONArray) rawMethods;
                if (values.length() < 1 || values.length() > 32) throw invalid();
                Set<String> methods = new LinkedHashSet<>();
                for (int i = 0; i < values.length(); i++) {
                    Object item = values.get(i);
                    if (!(item instanceof String)) throw invalid();
                    String method = (String) item;
                    if (!method(method) || !methods.add(method)) throw invalid();
                }
                String header = text(object, "header", null).toLowerCase(Locale.ROOT);
                if (!header.matches("[!#$%&'*+.^_`|~0-9a-z-]{1,128}") || reservedHeader(header)) throw invalid();
                String prefix = text(object, "prefix", "");
                if (prefix.length() > 128 || !asciiField(prefix)) throw invalid();
                return new Scope(origin, methods, header, prefix);
            } catch (FactoryException failure) { throw failure; }
            catch (Exception failure) { throw invalid(); }
        }
        public boolean permits(URL destination, String method) {
            if (destination == null || method == null || !methods.contains(method)) return false;
            try { return origin.equals(canonicalOrigin(destination.toExternalForm(), false)); }
            catch (FactoryException failure) { return false; }
        }
        public JSONObject metadata() throws FactoryException {
            try { return new JSONObject().put("origin", origin).put("methods", new JSONArray(methods))
                    .put("header", header).put("prefix", prefix); }
            catch (Exception failure) { throw unavailable(); }
        }
    }

    public static class Capture {
        public final String label, replaceId;
        public final Scope scope;
        private Capture(String label, String replaceId, Scope scope) {
            this.label = label; this.replaceId = replaceId; this.scope = scope;
        }
        /** Suitable for policy binding, not for logging. Destinations and labels can be private. */
        public JSONObject authorizationDetails() throws FactoryException {
            try {
                JSONObject result = new JSONObject().put("label", label).put("scope", scope.metadata());
                if (replaceId != null) result.put("replaceId", replaceId);
                return result;
            } catch (FactoryException failure) { throw failure; }
            catch (Exception failure) { throw unavailable(); }
        }
    }
    public static final class Put extends Capture implements AutoCloseable {
        private byte[] value;
        private Put(Capture input, byte[] value) { super(input.label, input.replaceId, input.scope); this.value = value; }
        synchronized byte[] copyValue() throws FactoryException {
            if (value == null) throw new FactoryException("CANCELLED", "Secret input has been released.");
            return value.clone();
        }
        @Override public synchronized void close() { if (value != null) Arrays.fill(value, (byte) 0); value = null; }
    }

    public static Capture parseCapture(String text) throws FactoryException { return parseCapture(StrictJson.object(text, 8192)); }
    public static Capture parseCapture(JSONObject object) throws FactoryException { return metadata(object, false); }
    public static Put parsePut(String text) throws FactoryException { return parsePut(StrictJson.object(text, MAX_REQUEST_BYTES)); }
    public static Put parsePut(JSONObject object) throws FactoryException {
        Capture input = metadata(object, true);
        char[] chars = null;
        try {
            String raw = text(object, "value", null);
            if (raw.length() > MAX_VALUE_BYTES) throw invalid();
            chars = raw.toCharArray();
            return new Put(input, encode(chars));
        } finally { if (chars != null) Arrays.fill(chars, '\0'); }
    }
    public static String parseId(String text) throws FactoryException { return parseId(StrictJson.object(text, 256)); }
    public static String parseId(JSONObject object) throws FactoryException {
        if (object == null) throw invalid();
        FactoryConfig.exactKeys(object, "id");
        return id(text(object, "id", null));
    }
    public static void parseEmpty(JSONObject object) throws FactoryException {
        if (object == null) throw invalid();
        FactoryConfig.exactKeys(object);
    }
    /** Bind get/describe/remove to the opaque ID, without retrieving any stored value. */
    public static JSONObject idAuthorizationDetails(String id) throws FactoryException {
        try { return new JSONObject().put("id", id(id)); }
        catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw unavailable(); }
    }
    /** An immutable typed network request supplies the URL/method; no stored header value is read. */
    public static JSONObject injectionAuthorizationDetails(String id, URL destination, String method) throws FactoryException {
        try {
            if (destination == null || !method(method)) throw invalid();
            canonicalOrigin(destination.toExternalForm(), false);
            return idAuthorizationDetails(id).put("url", destination.toExternalForm()).put("method", method);
        } catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw unavailable(); }
    }
    static String id(String value) throws FactoryException {
        if (value == null || !value.matches("sec_[a-f0-9]{32}")) throw invalid();
        return value;
    }
    private static Capture metadata(JSONObject object, boolean put) throws FactoryException {
        try {
            if (object == null) throw invalid();
            if (put) FactoryConfig.exactKeys(object, "label", "replaceId", "scope", "value");
            else FactoryConfig.exactKeys(object, "label", "replaceId", "scope");
            String label = text(object, "label", "");
            if (label.length() > 80 || FactoryConfig.containsControl(label) || !validUnicode(label)) throw invalid();
            String replaceId = object.has("replaceId") ? id(text(object, "replaceId", null)) : null;
            Object scope = object.get("scope");
            if (!(scope instanceof JSONObject)) throw invalid();
            return new Capture(label, replaceId, Scope.parse((JSONObject) scope));
        } catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }
    static byte[] encode(char[] value) throws FactoryException {
        if (value == null || value.length == 0 || value.length > MAX_VALUE_BYTES) throw invalid();
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (encoded.remaining() > MAX_VALUE_BYTES) throw invalid();
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (CharacterCodingException invalid) { throw invalid(); }
        finally { if (encoded != null && encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0); }
    }
    static String decode(byte[] value) throws FactoryException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString(); }
        catch (CharacterCodingException invalid) { throw new FactoryException("SECRET_CORRUPT", "Secret storage is unreadable."); }
    }
    static boolean asciiField(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
        return true;
    }
    private static boolean method(String value) {
        return value != null && !"*".equals(value) && value.matches("[!#$%&'*+.^_`|~0-9A-Z-]{1,32}");
    }
    private static String canonicalOrigin(String value, boolean originOnly) throws FactoryException {
        try {
            if (value == null || value.length() > (originOnly ? 1024 : 8192) || !validUnicode(value)) throw invalid();
            for (int i = 0; i < value.length(); i++)
                if (value.charAt(i) <= 32 || value.charAt(i) == 127 || value.charAt(i) == '\\') throw invalid();
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isEmpty()
                    || host.endsWith(".") || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) throw invalid();
            if (originOnly && (uri.getRawQuery() != null || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))) throw invalid();
            return "https://" + host.toLowerCase(Locale.ROOT) + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
        } catch (Exception failure) { throw invalid(); }
    }
    private static boolean reservedHeader(String name) {
        return "host".equals(name) || "content-length".equals(name) || "transfer-encoding".equals(name)
                || "connection".equals(name) || "proxy-connection".equals(name) || "expect".equals(name)
                || "upgrade".equals(name) || "trailer".equals(name) || "te".equals(name)
                || "proxy-authorization".equals(name) || "proxy-authenticate".equals(name);
    }
    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private static String text(JSONObject object, String name, String fallback) throws FactoryException {
        try {
            if (!object.has(name) && fallback != null) return fallback;
            Object value = object.get(name);
            if (!(value instanceof String)) throw invalid();
            return (String) value;
        } catch (Exception failure) { throw invalid(); }
    }
    static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid typed secret arguments."); }
    private static FactoryException unavailable() { return new FactoryException("UNAVAILABLE", "Secret metadata unavailable."); }
}
