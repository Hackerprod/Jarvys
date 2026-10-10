package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import com.jarvys.factory.contract.BrowserUrl;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Protocol and validation are platform-independent and shared by production and unit tests. */
public final class BridgeProtocol {
    public static final String ORIGIN = "https://app.jarvys.invalid";
    public static final int MAX_MESSAGE_BYTES = 524288;
    public static final int MAX_TEXT_BYTES = 262144;
    public static final int MAX_VALUE_BYTES = 65536;
    public static final int MAX_DOCUMENT_CHUNK_BYTES = 32768;
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    public static final class Request {
        public final String id, method;
        public final JSONObject args;
        public final CapabilityCatalog.Method operation;
        Request(String id, String method, JSONObject args) { this.id = id; this.method = method; this.args = args; this.operation = CapabilityCatalog.METHODS.get(method); }
    }

    public static boolean trustedSender(String origin, boolean mainFrame) {
        return mainFrame && ORIGIN.equals(origin);
    }
    public static Request validate(String origin, boolean mainFrame, String raw, FactoryConfig config) throws FactoryException {
        if (!trustedSender(origin, mainFrame)) throw new FactoryException("UNTRUSTED_SOURCE", "Only the trusted main frame can call native APIs.");
        try {
            JSONObject request = StrictJson.object(raw, MAX_MESSAGE_BYTES);
            FactoryConfig.exactKeys(request, "v", "id", "method", "args");
            if (!(request.get("v") instanceof Integer) || request.getInt("v") != 1)
                throw new FactoryException("INVALID_REQUEST", "Unsupported bridge version.");
            String id = FactoryConfig.string(request, "id");
            if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new FactoryException("INVALID_REQUEST", "Invalid request identifier.");
            String method = FactoryConfig.string(request, "method");
            JSONObject args = request.getJSONObject("args");
            CapabilityCatalog.Method operation = CapabilityCatalog.METHODS.get(method);
            if (operation == null) throw new FactoryException("UNKNOWN_METHOD", "Native method is not implemented.");
            String capability = operation.capability;
            switch (operation) {
                case RUNTIME_INFO: FactoryConfig.exactKeys(args); break;
                case STORAGE_GET: case STORAGE_REMOVE:
                    FactoryConfig.exactKeys(args, "key"); key(args); break;
                case STORAGE_SET:
                    FactoryConfig.exactKeys(args, "key", "value"); key(args);
                    text(args, "value", MAX_VALUE_BYTES, true); break;
                case STORAGE_LIST: FactoryConfig.exactKeys(args); break;
                case EXPORT_TEXT:
                    FactoryConfig.exactKeys(args, "filename", "text", "mimeType");
                    String filename = text(args, "filename", 120, false);
                    if (!filename.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || filename.endsWith(".") || filename.contains(".."))
                        throw new FactoryException("INVALID_ARGUMENT", "Use a simple document filename without paths.");
                    text(args, "text", MAX_TEXT_BYTES, true);
                    String mime = args.has("mimeType") ? FactoryConfig.string(args, "mimeType") : "text/plain";
                    if (!mime.equals("text/plain") && !mime.equals("text/markdown") && !mime.equals("application/json") && !mime.equals("text/csv"))
                        throw new FactoryException("INVALID_ARGUMENT", "Unsupported text export format.");
                    break;
                case MAPS_OPEN: case PHONE_DIAL: case EMAIL_COMPOSE: case SMS_COMPOSE:
                    ExternalLaunchRequest.parse(method, args.toString());
                    break;
                case CONTACTS_PICK:
                    ContactPickRequest.parse(args.toString());
                    break;
                case BROWSER_OPEN:
                    FactoryConfig.exactKeys(args, "url");
                    try { BrowserUrl.parse(FactoryConfig.string(args, "url")); }
                    catch (IllegalArgumentException invalid) {
                        throw new FactoryException("INVALID_ARGUMENT", "Use a bounded canonical HTTPS URL without credentials or fragment.");
                    }
                    break;
                case AUDIO_PLAY:
                    FactoryConfig.exactKeys(args, "handle"); documentHandle(args);
                    if (!config.capabilities.contains("documents"))
                        throw new FactoryException("CAPABILITY_DENIED", "Audio also requires documents capability.");
                    break;
                case SHARE_FILE:
                    FactoryConfig.exactKeys(args, "handle", "filename", "mimeType"); documentHandle(args);
                    String shareName = text(args, "filename", 120, false);
                    if (!shareName.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || shareName.endsWith(".") || shareName.contains(".."))
                        throw new FactoryException("INVALID_ARGUMENT", "Use a simple document filename without paths.");
                    documentMime(args, false);
                    if (!config.capabilities.contains("documents"))
                        throw new FactoryException("CAPABILITY_DENIED", "File sharing also requires documents capability.");
                    break;
                case SHARE_TEXT:
                    FactoryConfig.exactKeys(args, "text", "title");
                    text(args, "text", MAX_TEXT_BYTES, false);
                    if (args.has("title")) text(args, "title", 160, false);
                    break;
                case CLIPBOARD_WRITE:
                    FactoryConfig.exactKeys(args, "text"); text(args, "text", MAX_TEXT_BYTES, true); break;
                case HAPTICS_PERFORM:
                    FactoryConfig.exactKeys(args, "kind");
                    if (args.has("kind")) {
                        String kind = FactoryConfig.string(args, "kind");
                        if (!kind.equals("tap") && !kind.equals("longPress")) throw new FactoryException("INVALID_ARGUMENT", "Unsupported haptic feedback kind.");
                    }
                    break;
                case DEVICE_INFO: FactoryConfig.exactKeys(args); break;
                case PHOTOS_PICK: case PHOTOS_CAPTURE:
                    FactoryConfig.exactKeys(args);
                    if (!config.capabilities.contains("documents"))
                        throw new FactoryException("CAPABILITY_DENIED", "Photos also require documents capability.");
                    break;
                case DOCUMENTS_OPEN:
                    FactoryConfig.exactKeys(args, "mimeType"); documentMime(args, true); break;
                case DOCUMENTS_CREATE:
                    FactoryConfig.exactKeys(args, "filename", "mimeType");
                    String documentName = text(args, "filename", 120, false);
                    if (!documentName.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || documentName.endsWith(".") || documentName.contains(".."))
                        throw new FactoryException("INVALID_ARGUMENT", "Use a simple document filename without paths.");
                    documentMime(args, false); break;
                case DOCUMENTS_READ:
                    FactoryConfig.exactKeys(args, "handle", "offset", "length"); documentHandle(args);
                    documentInteger(args, "offset", 0, MAX_DOCUMENT_BYTES);
                    documentInteger(args, "length", 1, MAX_DOCUMENT_CHUNK_BYTES);
                    break;
                case DOCUMENTS_WRITE:
                    FactoryConfig.exactKeys(args, "handle", "offset", "data"); documentHandle(args);
                    long writeOffset = documentInteger(args, "offset", 0, MAX_DOCUMENT_BYTES);
                    int byteCount = documentDataLength(FactoryConfig.string(args, "data"));
                    if (writeOffset + byteCount > MAX_DOCUMENT_BYTES)
                        throw new FactoryException("INVALID_ARGUMENT", "Write exceeds the document limit.");
                    break;
                case DOCUMENTS_CLOSE:
                    FactoryConfig.exactKeys(args, "handle"); documentHandle(args); break;
                case DOCUMENTS_CANCEL: FactoryConfig.exactKeys(args); break;
                default: throw new FactoryException("UNKNOWN_METHOD", "Native method is not implemented.");
            }
            if (capability != null && !config.capabilities.contains(capability))
                throw new FactoryException("CAPABILITY_DENIED", "This application did not declare the required capability.");
            return new Request(id, method, args);
        } catch (JSONException e) {
            throw new FactoryException("INVALID_REQUEST", "Missing or incorrectly typed bridge fields.");
        }
    }
    private static void documentMime(JSONObject args, boolean wildcard) throws JSONException, FactoryException {
        String mime = FactoryConfig.string(args, "mimeType");
        boolean plain = mime.matches("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*");
        boolean pattern = mime.equals("*/*") || mime.matches("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/\\*");
        if (mime.length() > 127 || !(plain || (wildcard && pattern)))
            throw new FactoryException("INVALID_ARGUMENT", "Use one plain MIME type.");
    }
    private static void documentHandle(JSONObject args) throws JSONException, FactoryException {
        if (!FactoryConfig.string(args, "handle").matches("[a-f0-9]{64}"))
            throw new FactoryException("INVALID_ARGUMENT", "Invalid document handle.");
    }
    private static long documentInteger(JSONObject args, String key, long min, long max) throws JSONException, FactoryException {
        Object value = args.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new FactoryException("INVALID_ARGUMENT", key + " must be an integer.");
        long number = ((Number)value).longValue();
        if (number < min || number > max) throw new FactoryException("INVALID_ARGUMENT", key + " is outside the document limit.");
        return number;
    }
    private static int documentDataLength(String encoded) throws FactoryException {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        if (encoded == null || encoded.isEmpty() || encoded.length() > 43692 || encoded.length() % 4 != 0)
            throw new FactoryException("INVALID_ARGUMENT", "Use canonical bounded base64 data.");
        int length = encoded.length();
        int padding = encoded.charAt(length - 1) == '=' ? (encoded.charAt(length - 2) == '=' ? 2 : 1) : 0;
        int bytes = (length / 4) * 3 - padding;
        for (int i = 0; i < length - padding; i++) if (alphabet.indexOf(encoded.charAt(i)) < 0)
            throw new FactoryException("INVALID_ARGUMENT", "Use canonical bounded base64 data.");
        if (bytes > MAX_DOCUMENT_CHUNK_BYTES ||
                (padding == 2 && (alphabet.indexOf(encoded.charAt(length - 3)) & 15) != 0) ||
                (padding == 1 && (alphabet.indexOf(encoded.charAt(length - 2)) & 3) != 0))
            throw new FactoryException("INVALID_ARGUMENT", "Use canonical bounded base64 data.");
        return bytes;
    }
    static String requestId(String raw) {
        try {
            Object value = StrictJson.object(raw, MAX_MESSAGE_BYTES).opt("id");
            return value instanceof String && ((String) value).matches("[a-zA-Z0-9_-]{1,64}") ? (String) value : null;
        } catch (FactoryException ignored) { return null; }
    }
    private static void key(JSONObject args) throws JSONException, FactoryException {
        String key = FactoryConfig.string(args, "key");
        if (!key.matches("[a-zA-Z0-9_.:-]{1,96}")) throw new FactoryException("INVALID_ARGUMENT", "Invalid storage key.");
    }
    private static String text(JSONObject args, String name, int maxBytes, boolean emptyAllowed) throws JSONException, FactoryException {
        String text = FactoryConfig.string(args, name);
        if ((!emptyAllowed && text.isEmpty()) || text.length() > maxBytes || text.getBytes(StandardCharsets.UTF_8).length > maxBytes || text.indexOf('\0') >= 0)
            throw new FactoryException("INVALID_ARGUMENT", name + " exceeds the supported text limit.");
        return text;
    }
    public static String success(String id, Object value) {
        try { return new JSONObject().put("v", 1).put("id", id).put("ok", true).put("result", value == null ? JSONObject.NULL : value).toString(); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public static String failure(String id, String code, String message) {
        try {
            return new JSONObject().put("v", 1).put("id", id == null ? JSONObject.NULL : id).put("ok", false)
                    .put("error", new JSONObject().put("code", code).put("message", message)).toString();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
