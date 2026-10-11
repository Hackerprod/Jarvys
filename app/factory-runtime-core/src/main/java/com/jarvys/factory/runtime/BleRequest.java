package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Closed immutable bridge input; arbitrary standard UUIDs and bounded bytes, no profile commands. */
public final class BleRequest {
    public static final int MAX_ARGS_BYTES = 8192;
    public final String method, handle, connectionId, deviceId, attributeId, subscriptionId, operationId, mode, writeType;
    public final int mtu, offset, limit; public final long durationMs; public final boolean scan, connect; public final List<UUID> serviceUuids;
    private final byte[] bytes; private final String authorizationJson; private boolean cleared;
    private BleRequest(String method, JSONObject args) throws FactoryException, JSONException {
        this.method = method;
        String handle = null, connectionId = null, deviceId = null, attributeId = null, subscriptionId = null, operationId = null, mode = null, writeType = null;
        int mtu = 0, offset = 0, limit = 0; long durationMs = 0; boolean scan = false, connect = false; byte[] bytes = null; List<UUID> uuids = new ArrayList<>();
        JSONObject details = new JSONObject(args.toString());
        switch (method) {
            case "bluetooth.status": exact(args); break;
            case "bluetooth.requestPermissions":
                exact(args, "scan", "connect"); scan = bool(args, "scan"); connect = bool(args, "connect");
                if (!scan && !connect) throw invalid(); break;
            case "bluetooth.scan": {
                exact(args, "serviceUuids", "durationMs"); durationMs = integer(args, "durationMs", 1, 300000);
                Object value = args.get("serviceUuids"); if (!(value instanceof JSONArray)) throw invalid(); JSONArray list = (JSONArray)value;
                if (list.length() > 16) throw invalid();
                for (int i = 0; i < list.length(); i++) {
                    Object raw = list.get(i); if (!(raw instanceof String)) throw invalid();
                    String canonical = (String)raw;
                    if (!canonical.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
                    UUID uuid = UUID.fromString(canonical); if (uuids.contains(uuid)) throw invalid(); uuids.add(uuid);
                }
                break;
            }
            case "bluetooth.poll": exact(args, "handle"); handle = id(args, "handle"); break;
            case "bluetooth.stopScan": exact(args, "scanId"); handle = id(args, "scanId"); break;
            case "bluetooth.connect": exact(args, "deviceId"); deviceId = id(args, "deviceId"); break;
            case "bluetooth.services": exact(args, "connectionId", "offset", "limit"); connectionId = id(args, "connectionId"); offset = (int)integer(args, "offset", 0, 4352); limit = (int)integer(args, "limit", 1, 256); break;
            case "bluetooth.disconnect": exact(args, "connectionId"); connectionId = id(args, "connectionId"); break;
            case "bluetooth.requestMtu": exact(args, "connectionId", "mtu"); connectionId = id(args, "connectionId"); mtu = (int)integer(args, "mtu", 23, 517); break;
            case "bluetooth.read": exact(args, "connectionId", "attributeId"); connectionId = id(args, "connectionId"); attributeId = id(args, "attributeId"); break;
            case "bluetooth.write": {
                exact(args, "connectionId", "attributeId", "valueBase64", "writeType"); connectionId = id(args, "connectionId"); attributeId = id(args, "attributeId");
                writeType = text(args, "writeType"); if (!"response".equals(writeType) && !"noResponse".equals(writeType)) throw invalid();
                String encoded = text(args, "valueBase64"); if (encoded.length() > 684 || !encoded.matches("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")) throw invalid();
                try { bytes = BleBytes.decode(encoded); } catch (IllegalArgumentException malformed) { throw invalid(); }
                if (bytes.length > BleCentral.MAX_VALUE_BYTES || !BleBytes.encode(bytes).equals(encoded)) throw invalid();
                details.remove("valueBase64"); details.put("byteLength", bytes.length); details.put("valueSha256", digest(bytes)); break;
            }
            case "bluetooth.subscribe":
                exact(args, "connectionId", "attributeId", "mode"); connectionId = id(args, "connectionId"); attributeId = id(args, "attributeId"); mode = text(args, "mode");
                if (!"notify".equals(mode) && !"indicate".equals(mode)) throw invalid(); break;
            case "bluetooth.unsubscribe": exact(args, "subscriptionId"); subscriptionId = id(args, "subscriptionId"); break;
            case "bluetooth.cancel": exact(args, "operationId"); operationId = id(args, "operationId"); break;
            default: throw invalid();
        }
        this.handle = handle; this.connectionId = connectionId; this.deviceId = deviceId; this.attributeId = attributeId;
        this.subscriptionId = subscriptionId; this.operationId = operationId; this.mode = mode; this.writeType = writeType;
        this.mtu = mtu; this.offset = offset; this.limit = limit; this.durationMs = durationMs; this.scan = scan; this.connect = connect; this.serviceUuids = Collections.unmodifiableList(uuids);
        this.bytes = bytes; authorizationJson = details.toString();
    }
    public static BleRequest parse(String method, String json) throws FactoryException { return parse(method, StrictJson.object(json, MAX_ARGS_BYTES)); }
    public static BleRequest parse(String method, JSONObject args) throws FactoryException {
        if (args == null || method == null) throw invalid();
        String encoded = args.toString(); if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES) throw invalid();
        try { return new BleRequest(method, args); }
        catch (JSONException | IllegalArgumentException failure) { throw invalid(); }
    }
    public byte[] bytes() { if (cleared) throw new IllegalStateException("BLE request bytes were retired"); return bytes == null ? null : bytes.clone(); }
    /** Raw write bytes never enter host disclosure, logs or tracing. Keep this exact parsed request until execution. */
    public JSONObject authorizationDetails() {
        try { return new JSONObject(authorizationJson); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Poll and owner teardown do not acquire an additional policy lease. */
    public boolean needsPolicy() {
        return !(method.equals("bluetooth.status") || method.equals("bluetooth.poll") || method.equals("bluetooth.stopScan") || method.equals("bluetooth.unsubscribe") || method.equals("bluetooth.disconnect") || method.equals("bluetooth.cancel"));
    }
    public void clear() { cleared = true; if (bytes != null) Arrays.fill(bytes, (byte)0); }
    static String digest(byte[] bytes) {
        try { StringBuilder s = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString(); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static void exact(JSONObject args, String... keys) throws FactoryException {
        if (args.length() != keys.length) throw invalid();
        for (String key : keys) if (!args.has(key)) throw invalid();
    }
    static long integer(JSONObject value, String name, long min, long max) throws FactoryException, JSONException {
        Object v = value.get(name); if (!(v instanceof Integer) && !(v instanceof Long)) throw invalid();
        long n = ((Number)v).longValue(); if (n < min || n > max) throw invalid(); return n;
    }
    static String text(JSONObject args, String key) throws FactoryException, JSONException { Object v = args.get(key); if (!(v instanceof String)) throw invalid(); return (String)v; }
    static String id(JSONObject args, String key) throws FactoryException, JSONException {
        String s = text(args, key); if (!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid(); return s;
    }
    static boolean bool(JSONObject args, String key) throws FactoryException, JSONException { Object v = args.get(key); if (!(v instanceof Boolean)) throw invalid(); return (Boolean)v; }
    static FactoryException invalid() { return BleCentral.error("INVALID_REQUEST", "Invalid Bluetooth request shape or bounded value."); }
}
