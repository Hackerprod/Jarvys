package com.jarvys.factory.runtime;

import org.json.JSONObject;
import java.net.IDN;
import java.util.Locale;

/** Typed ICMP ping settings; no DNS lookup, executable, shell, interface or raw flag input. */
public final class NetworkPingRequest {
    public static final int MAX_COUNT = 1000, MAX_TIMEOUT_MS = 120000, MAX_PACKET_BYTES = 65507;
    public static final int MAX_OUTPUT_BYTES = 65536;
    public final String host, family;
    public final int count, timeoutMs, packetTimeoutMs, intervalMs, packetBytes, maxOutputBytes;
    private NetworkPingRequest(JSONObject args) throws Exception {
        FactoryConfig.exactKeys(args, "host", "family", "count", "timeoutMs", "packetTimeoutMs", "intervalMs", "packetBytes", "maxOutputBytes");
        host = normalizeHost(text(args, "host", null));
        family = text(args, "family", "auto");
        if (!"auto".equals(family) && !"ipv4".equals(family) && !"ipv6".equals(family)) throw invalid();
        if ((host.indexOf(':') >= 0 && "ipv4".equals(family)) || (ipv4(host) && "ipv6".equals(family))) throw invalid();
        count = integer(args, "count", 4, 1, MAX_COUNT);
        timeoutMs = integer(args, "timeoutMs", 10000, 100, MAX_TIMEOUT_MS);
        packetTimeoutMs = integer(args, "packetTimeoutMs", 1000, 1000, MAX_TIMEOUT_MS);
        intervalMs = integer(args, "intervalMs", 1000, 200, 60000);
        packetBytes = integer(args, "packetBytes", 56, 0, MAX_PACKET_BYTES);
        maxOutputBytes = integer(args, "maxOutputBytes", 16384, 1024, MAX_OUTPUT_BYTES);
    }
    public static NetworkPingRequest parse(JSONObject args) throws FactoryException {
        try { if (args == null) throw invalid(); return new NetworkPingRequest(args); }
        catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }
    public JSONObject authorizationDetails() throws FactoryException {
        try { return new JSONObject().put("host", host).put("family", family).put("count", count)
                .put("timeoutMs", timeoutMs).put("packetTimeoutMs", packetTimeoutMs).put("intervalMs", intervalMs)
                .put("packetBytes", packetBytes).put("maxOutputBytes", maxOutputBytes); }
        catch (Exception failure) { throw invalid(); }
    }
    /** Pure syntax validation: IPv6 is checked locally without InetAddress.getByName or DNS. */
    static String normalizeHost(String value) throws FactoryException {
        if (value == null || value.isEmpty() || value.length() > 1024) throw invalid();
        for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i)) || Character.isISOControl(value.charAt(i))) throw invalid();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
            if (value.indexOf(':') < 0) throw invalid();
        }
        if (value.indexOf(':') >= 0) {
            String address = value, zone = "";
            int percent = address.indexOf('%');
            if (percent >= 0) {
                zone = address.substring(percent + 1); address = address.substring(0, percent);
                if (!zone.matches("[A-Za-z0-9_.-]{1,64}")) throw invalid();
            }
            if (!ipv6(address)) throw invalid();
            return address.toLowerCase(Locale.ROOT) + (zone.isEmpty() ? "" : "%" + zone);
        }
        if (value.matches("[0-9.]+")) { if (!ipv4(value)) throw invalid(); return value; }
        final String ascii;
        try { ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        String labels = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
        if (labels.isEmpty() || labels.length() > 253) throw invalid();
        for (String label : labels.split("\\.", -1))
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw invalid();
        return ascii;
    }
    static boolean ipv4(String value) {
        String[] groups = value.split("\\.", -1);
        if (groups.length != 4) return false;
        for (String group : groups) {
            if (!group.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(group) > 255) return false;
        }
        return true;
    }
    private static boolean ipv6(String value) {
        if (!value.matches("[0-9A-Fa-f:.]+") || value.length() > 45 || value.contains(":::")) return false;
        int compression = value.indexOf("::");
        if (compression != value.lastIndexOf("::")) return false;
        if (compression < 0 && (value.startsWith(":") || value.endsWith(":"))) return false;
        String[] groups = value.split(":", -1);
        int units = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            if (group.isEmpty()) continue;
            if (group.indexOf('.') >= 0) {
                if (i != groups.length - 1 || !ipv4(group)) return false;
                units += 2;
            } else { if (!group.matches("[0-9A-Fa-f]{1,4}")) return false; units++; }
        }
        return compression >= 0 ? units < 8 : units == 8;
    }
    private static String text(JSONObject object, String key, String fallback) throws Exception {
        if (!object.has(key) && fallback != null) return fallback;
        Object value = object.get(key); if (!(value instanceof String)) throw invalid(); return (String) value;
    }
    private static int integer(JSONObject object, String key, int fallback, int min, int max) throws Exception {
        if (!object.has(key)) return fallback;
        Object value = object.get(key); if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue(); if (number < min || number > max) throw invalid(); return (int) number;
    }
    private static FactoryException invalid() { return new FactoryException("INVALID_ARGUMENT", "Invalid ICMP host, family or finite ping limits."); }
}
