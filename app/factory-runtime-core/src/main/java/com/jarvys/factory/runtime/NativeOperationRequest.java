package com.jarvys.factory.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Closed, immutable disclosure grammar shared by trusted packaged DEX and host review. */
public final class NativeOperationRequest {
    public static final int MAX_BYTES = BridgeProtocol.MAX_MESSAGE_BYTES;
    public final String operation, capability;
    private final String canonical;
    private NativeOperationRequest(String operation, String capability, JSONObject details) throws JSONException {
        this.operation = operation; this.capability = capability; this.canonical = canonical(details);
    }
    public String canonicalJson() { return canonical; }
    public JSONObject details() {
        try { return new JSONObject(canonical); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public static NativeOperationRequest parse(String operation, JSONObject details) throws FactoryException {
        if (details == null) throw invalid();
        String encoded = details.toString();
        if (encoded == null || encoded.length() > MAX_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
        return parseValue(operation, details);
    }
    public static NativeOperationRequest parse(String operation, String details) throws FactoryException {
        return parseValue(operation, StrictJson.object(details, MAX_BYTES));
    }
    private static NativeOperationRequest parseValue(String operation, JSONObject value) throws FactoryException {
        try {
            String capability;
            switch (operation == null ? "" : operation) {
                case "secrets.put": case "secrets.capture":
                    capability = "secrets";
                    if (value.has("replaceId")) exact(value, "label", "scope", "replaceId"); else exact(value, "label", "scope");
                    value = SecretRequest.parseCapture(value).authorizationDetails(); break;
                case "secrets.get": case "secrets.describe": case "secrets.remove":
                    capability = "secrets"; exact(value, "id"); SecretRequest.parseId(value); break;
                case "secrets.list": case "secrets.reset": capability = "secrets"; exact(value); break;
                case "secrets.request":
                    capability = "secrets"; exact(value, "id", "request");
                    SecretRequest.id(text(value, "id", 36, false));
                    if (!(value.get("request") instanceof JSONObject)) throw invalid();
                    validateNetwork(value.getJSONObject("request"), false);
                    if (!"https".equalsIgnoreCase(NetworkRequest.parseUrl(text(value.getJSONObject("request"), "url", NetworkRequest.MAX_URL_BYTES, false)).getProtocol())) throw invalid();
                    break;
                case "incomingShare.take":
                    capability = "incomingShare";
                    exact(value, "envelopeId", "action", "declaredMimeType", "subject", "textPreview", "textPreviewTruncated", "textBytes", "attachmentIndices", "attachmentCount", "contentSha256");
                    if (!text(value, "envelopeId", 64, false).matches("[a-f0-9]{64}") || !text(value, "contentSha256", 64, false).matches("[a-f0-9]{64}")) throw invalid();
                    String action = text(value, "action", 64, false);
                    if (!action.equals("android.intent.action.SEND") && !action.equals("android.intent.action.SEND_MULTIPLE")) throw invalid();
                    String mime = text(value, "declaredMimeType", 127, false);
                    if (!mime.equals("*/*") && !mime.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/(\\*|[a-z0-9][a-z0-9!#$&^_.+-]{0,62})")) throw invalid();
                    text(value, "subject", 1024, true); text(value, "textPreview", 2048, true); bool(value, "textPreviewTruncated");
                    integer(value, "textBytes", 0, 262144);
                    int count = integer(value, "attachmentCount", 0, 32);
                    Object rawIndices = value.get("attachmentIndices"); if (!(rawIndices instanceof JSONArray)) throw invalid();
                    JSONArray indices = (JSONArray) rawIndices; if (indices.length() != count) throw invalid();
                    int previous = -1;
                    for (int i = 0; i < count; i++) {
                        Object rawIndex = indices.get(i); if (!(rawIndex instanceof Integer) && !(rawIndex instanceof Long)) throw invalid();
                        long index = ((Number) rawIndex).longValue(); if (index <= previous || index >= 32) throw invalid(); previous = (int) index;
                    }
                    break;
                case "voiceRecognition.recognize":
                    capability = "voiceRecognition"; exact(value, "language", "languageModel", "prompt", "maxResults", "preferOffline", "timeoutMs");
                    value = VoiceRecognitionRequest.parseRecognize(value).authorizationDetails(); break;
                case "steps.current": capability = "steps"; exact(value, "timeoutMs"); value = StepsRequest.parseCurrent(value).authorizationDetails(); break;
                case "steps.requestPermission": capability = "steps"; exact(value); break;
                case "sensors.subscribe": capability = "sensors"; exact(value, "kind", "samplingPeriodUs"); value = SensorSubscriptionRequest.parseSubscribe(value).authorizationDetails(); break;
                case "recording.record":
                    capability = "recording"; exact(value, "format", "durationMs", "maxBytes", "sampleRate", "channels"); value = RecordingRequest.parseRecord(value).authorizationDetails(); break;
                case "recording.requestPermission": capability = "recording"; exact(value); break;
                case "location.current":
                    capability = "location"; exact(value, "provider", "timeoutMs", "maxAgeMs", "maxAccuracyMeters", "allowMock");
                    value = LocationRequest.parseCurrent(value).authorizationDetails(); break;
                case "location.requestPermission":
                    capability = "location"; exact(value, "precision"); value = LocationRequest.parsePermission(value).authorizationDetails(); break;
                case "network.request": case "network.probe":
                    capability = "network"; validateNetwork(value, "network.probe".equals(operation)); break;
                case "network.ping":
                    capability = "network"; exact(value, "host", "family", "count", "timeoutMs", "packetTimeoutMs", "intervalMs", "packetBytes", "maxOutputBytes");
                    value = NetworkPingRequest.parse(value).authorizationDetails(); break;
                case "notifications.show":
                    capability = "notifications";
                    if (value.has("actions")) {
                        exact(value, "id", "channelId", "title", "body", "priority", "actions");
                        value = NotificationScheduleRequest.parseNotification(value).details();
                    } else {
                        exact(value, "id", "channelId", "title", "body", "priority");
                        value = NotificationRequest.parseShow(value).authorizationDetails();
                    }
                    break;
                case "notifications.schedule":
                    capability = "notifications"; exact(value, "scheduleId", "atEpochMs", "expiresAtEpochMs", "precision", "allowWhileIdle", "notification");
                    value = NotificationScheduleRequest.parseSchedule(value).authorizationDetails(); break;
                case "notifications.requestExactAlarmAccess": capability = "notifications"; exact(value); break;
                case "notifications.channel":
                    capability = "notifications"; exact(value, "id", "name", "description", "importance");
                    value = NotificationRequest.parseChannel(value).authorizationDetails(); break;
                case "notifications.cancel":
                    capability = "notifications"; exact(value, "id"); NotificationRequest.parseCancel(value); break;
                case "notifications.requestPermission":
                    capability = "notifications"; exact(value); break;
                case "share.text":
                    capability = "share"; exact(value, "text", "title"); text(value, "text", BridgeProtocol.MAX_TEXT_BYTES, true); text(value, "title", 1024, true); break;
                case "export.text":
                    capability = "export"; exact(value, "text", "filename", "mimeType");
                    text(value, "text", BridgeProtocol.MAX_TEXT_BYTES, true); text(value, "filename", 240, false); text(value, "mimeType", 127, false); break;
                case "speech.speak":
                    capability = "speech"; exact(value, "text", "language", "engine", "voice", "rate", "pitch", "volume");
                    value = SpeechRequest.parseSpeak(value).authorizationDetails(); break;
                case "speech.voices":
                    capability = "speech"; exact(value, "engine"); value = SpeechRequest.voicesAuthorizationDetails(SpeechRequest.parseVoices(value)); break;
                case "speech.stop": capability = "speech"; exact(value); break;
                default: throw invalid();
            }
            return new NativeOperationRequest(operation, capability, value);
        } catch (JSONException | IllegalArgumentException failure) { throw invalid(); }
    }
    private static void validateNetwork(JSONObject value, boolean probe) throws FactoryException, JSONException {
        exact(value, "url", "method", "headers", "bodyBytes", "bodySha256", "probe", "redirect", "maxRedirects",
                "allowHttpsDowngrade", "bodyPreview", "bodyPreviewTruncated", "bodyEncoding", "responseType",
                "connectTimeoutMs", "readTimeoutMs", "timeoutMs", "maxRequestBytes", "maxResponseBytes", "maxHeaderBytes", "hasBody");
        NetworkRequest.parseUrl(text(value, "url", NetworkRequest.MAX_URL_BYTES, false));
        String method = text(value, "method", 32, false);
        if (!method.matches("[!#$%&'*+.^_`|~0-9A-Z-]+") || (probe && !method.equals("HEAD") && !method.equals("GET"))) throw invalid();
        if (bool(value, "probe") != probe) throw invalid();
        choice(value, "redirect", "follow", "manual", "error"); choice(value, "responseType", "text", "base64");
        choice(value, "bodyEncoding", "utf-8", "base64"); bool(value, "allowHttpsDowngrade"); bool(value, "bodyPreviewTruncated");
        boolean hasBody = bool(value, "hasBody");
        int bodyBytes = integer(value, "bodyBytes", 0, NetworkRequest.MAX_BODY_BYTES);
        if (!text(value, "bodySha256", 64, false).matches("[a-f0-9]{64}")) throw invalid();
        text(value, "bodyPreview", 2048, true);
        int requestLimit = integer(value, "maxRequestBytes", 0, NetworkRequest.MAX_BODY_BYTES);
        int responseLimit = integer(value, "maxResponseBytes", 0, NetworkRequest.MAX_BODY_BYTES);
        int headerLimit = integer(value, "maxHeaderBytes", 256, NetworkRequest.MAX_HEADER_BYTES);
        integer(value, "maxRedirects", 0, NetworkRequest.MAX_REDIRECTS);
        for (String key : new String[]{"connectTimeoutMs", "readTimeoutMs", "timeoutMs"}) integer(value, key, 1, NetworkRequest.MAX_TIMEOUT_MS);
        if (bodyBytes > requestLimit || (probe && (hasBody || bodyBytes != 0 || responseLimit != 0))) throw invalid();
        Object rawHeaders = value.get("headers");
        if (!(rawHeaders instanceof JSONObject)) throw invalid();
        JSONObject headers = (JSONObject) rawHeaders;
        if (headers.length() > 64) throw invalid();
        int total = 0;
        for (Iterator<String> keys = headers.keys(); keys.hasNext();) {
            String key = keys.next(); String header = text(headers, key, NetworkRequest.MAX_HEADER_BYTES, true);
            if (!key.matches("[!#$%&'*+.^_`|~0-9a-z-]{1,256}")) throw invalid();
            for (int i = 0; i < header.length(); i++) if ((header.charAt(i) < 32 && header.charAt(i) != '\t') || header.charAt(i) > 126) throw invalid();
            total += key.length() + header.length() + 4;
        }
        if (total > headerLimit) throw invalid();
    }
    /** Includes the complete canonical request; the host additionally binds verified source proof. */
    public String binding(String nonce) { return binding(nonce, ""); }
    public String binding(String nonce, String sourceEvidence) {
        if (nonce == null || !nonce.matches("[a-f0-9]{64}") || sourceEvidence == null) throw new IllegalArgumentException();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{"factory-native-operation-v1", operation, nonce, canonical, sourceEvidence}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String canonical(JSONObject value) throws JSONException { return canonicalValue(value, 0); }
    private static String canonicalValue(Object value, int depth) throws JSONException {
        if (depth > 16) throw new JSONException("Native disclosure nesting exceeds bound");
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>(); for (Iterator<String> it = object.keys(); it.hasNext();) keys.add(it.next());
            Collections.sort(keys); StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonicalValue(object.get(key), depth + 1));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) result.append(','); result.append(canonicalValue(array.get(index), depth + 1));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Number) return JSONObject.numberToString((Number) value);
        if (value instanceof Boolean) return value.toString();
        throw new JSONException("Unsupported native disclosure value");
    }
    private static void exact(JSONObject object, String... allowed) throws FactoryException {
        HashSet<String> keys = new HashSet<>(); for (Iterator<String> it = object.keys(); it.hasNext();) keys.add(it.next());
        if (!keys.equals(new HashSet<>(Arrays.asList(allowed)))) throw invalid();
    }
    private static String text(JSONObject object, String key, int limit, boolean empty) throws JSONException, FactoryException {
        Object raw = object.get(key); if (!(raw instanceof String)) throw invalid(); String value = (String) raw;
        if ((!empty && value.isEmpty()) || value.length() > limit || value.getBytes(StandardCharsets.UTF_8).length > limit) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(); }
            else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return value;
    }
    private static int integer(JSONObject object, String key, int min, int max) throws JSONException, FactoryException {
        Object value = object.get(key); if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue(); if (number < min || number > max) throw invalid(); return (int) number;
    }
    private static boolean bool(JSONObject object, String key) throws JSONException, FactoryException {
        Object value = object.get(key); if (!(value instanceof Boolean)) throw invalid(); return (Boolean) value;
    }
    private static void choice(JSONObject object, String key, String... values) throws JSONException, FactoryException {
        if (!Arrays.asList(values).contains(text(object, key, 32, false))) throw invalid();
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid native operation disclosure."); }
}
