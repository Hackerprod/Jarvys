package com.jarvys.factory.runtime;

import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Protocol and validation are platform-independent and shared by production and unit tests. */
public final class BridgeProtocol {
    public static final String ORIGIN = "https://app.jarvys.invalid";
    public static final int MAX_MESSAGE_BYTES = 524288;
    public static final int MAX_TEXT_BYTES = 262144;
    public static final int MAX_VALUE_BYTES = 65536;

    public static final class Request {
        public final String id, method;
        public final JSONObject args;
        Request(String id, String method, JSONObject args) { this.id = id; this.method = method; this.args = args; }
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
            String capability;
            switch (method) {
                case "runtime.info": capability = null; FactoryConfig.exactKeys(args); break;
                case "storage.get": case "storage.remove":
                    capability = "storage"; FactoryConfig.exactKeys(args, "key"); key(args); break;
                case "storage.set":
                    capability = "storage"; FactoryConfig.exactKeys(args, "key", "value"); key(args);
                    text(args, "value", MAX_VALUE_BYTES, true); break;
                case "storage.list": capability = "storage"; FactoryConfig.exactKeys(args); break;
                case "export.text":
                    capability = "export"; FactoryConfig.exactKeys(args, "filename", "text", "mimeType");
                    String filename = text(args, "filename", 120, false);
                    if (!filename.matches("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}") || filename.endsWith(".") || filename.contains(".."))
                        throw new FactoryException("INVALID_ARGUMENT", "Use a simple document filename without paths.");
                    text(args, "text", MAX_TEXT_BYTES, true);
                    String mime = args.has("mimeType") ? FactoryConfig.string(args, "mimeType") : "text/plain";
                    if (!mime.equals("text/plain") && !mime.equals("text/markdown") && !mime.equals("application/json") && !mime.equals("text/csv"))
                        throw new FactoryException("INVALID_ARGUMENT", "Unsupported text export format.");
                    break;
                case "share.text":
                    capability = "share"; FactoryConfig.exactKeys(args, "text", "title");
                    text(args, "text", MAX_TEXT_BYTES, false);
                    if (args.has("title")) text(args, "title", 160, false);
                    break;
                case "clipboard.write":
                    capability = "clipboard"; FactoryConfig.exactKeys(args, "text"); text(args, "text", MAX_TEXT_BYTES, true); break;
                case "haptics.perform":
                    capability = "haptics"; FactoryConfig.exactKeys(args, "kind");
                    if (args.has("kind")) {
                        String kind = FactoryConfig.string(args, "kind");
                        if (!kind.equals("tap") && !kind.equals("longPress")) throw new FactoryException("INVALID_ARGUMENT", "Unsupported haptic feedback kind.");
                    }
                    break;
                case "device.info": capability = "device"; FactoryConfig.exactKeys(args); break;
                default: throw new FactoryException("UNKNOWN_METHOD", "Native method is not implemented.");
            }
            if (capability != null && !config.capabilities.contains(capability))
                throw new FactoryException("CAPABILITY_DENIED", "This application did not declare the required capability.");
            return new Request(id, method, args);
        } catch (JSONException e) {
            throw new FactoryException("INVALID_REQUEST", "Missing or incorrectly typed bridge fields.");
        }
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
