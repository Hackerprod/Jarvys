package com.jarvys.factory.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Closed NDEF data grammar. No record is interpreted as an Intent, URI action, or command. */
public final class NfcRequest {
    public static final int MAX_ARGS_BYTES = 384 * 1024;
    public static final int MAX_MESSAGE_BYTES = 128 * 1024, MAX_RECORDS = 256;
    public static final int MIN_TIMEOUT_MS = 1000, MAX_TIMEOUT_MS = 300000;
    private NfcRequest() { }

    /** Native-only resolved signed budget. Never accept these values from bridge arguments. */
    public static final class Limits {
        public final int messageBytes, records, timeoutMs;
        public Limits(int messageBytes, int records, int timeoutMs) {
            if (messageBytes < 3 || messageBytes > MAX_MESSAGE_BYTES || records < 1 || records > MAX_RECORDS
                    || timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS)
                throw new IllegalArgumentException("Invalid signed NFC limits.");
            this.messageBytes = messageBytes; this.records = records; this.timeoutMs = timeoutMs;
        }
        public static Limits defaults() { return new Limits(8192, 16, 30000); }
    }

    public static final class Record {
        public final int tnf;
        private final byte[] type, id, payload;
        private Record(int tnf, byte[] type, byte[] id, byte[] payload) {
            this.tnf = tnf; this.type = type.clone(); this.id = id.clone(); this.payload = payload.clone();
        }
        public byte[] type() { return type.clone(); }
        public byte[] id() { return id.clone(); }
        public byte[] payload() { return payload.clone(); }
        public int byteLength() {
            // MB/ME flags do not add bytes; ID-length exists only with nonempty ID; SR at <=255.
            return 2 + (payload.length <= 255 ? 1 : 4) + (id.length == 0 ? 0 : 1)
                    + type.length + id.length + payload.length;
        }
        public JSONObject json() {
            try { return new JSONObject().put("tnf", tnf).put("typeHex", hex(type))
                    .put("idHex", hex(id)).put("payloadHex", hex(payload)); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    public static final class Message {
        public final List<Record> records;
        public final int byteLength;
        private Message(List<Record> records, int bytes) {
            this.records = Collections.unmodifiableList(new ArrayList<>(records)); this.byteLength = bytes;
        }
        public JSONArray json() { JSONArray value = new JSONArray(); for (Record r : records) value.put(r.json()); return value; }
    }
    public static final class Operation {
        public final boolean write, allowFormat;
        public final int timeoutMs;
        public final Message message;
        private Operation(boolean write, int timeout, boolean allowFormat, Message message) {
            this.write = write; this.timeoutMs = timeout; this.allowFormat = allowFormat; this.message = message;
        }
        public String method() { return write ? "nfc.write" : "nfc.read"; }
        /** Exact native-owned payload, not a preview/hash-only disclosure. */
        public JSONObject authorizationDetails() {
            try {
                JSONObject result = new JSONObject().put("timeoutMs", timeoutMs);
                if (write) result.put("allowFormat", allowFormat).put("records", message.json());
                return result;
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    public static Operation read(String json, Limits limits) throws FactoryException { return read(StrictJson.object(json, MAX_ARGS_BYTES, 8, 4096), limits); }
    public static Operation read(JSONObject args, Limits limits) throws FactoryException {
        bounded(args); keys(args, "timeoutMs");
        return new Operation(false, timeout(args, limits), false, null);
    }
    public static Operation write(String json, Limits limits) throws FactoryException { return write(StrictJson.object(json, MAX_ARGS_BYTES, 8, 4096), limits); }
    public static Operation write(JSONObject args, Limits limits) throws FactoryException {
        bounded(args); keys(args, "timeoutMs", "allowFormat", "records");
        try {
            if (!(args.get("records") instanceof JSONArray)) throw invalid();
            boolean format = false;
            if (args.has("allowFormat")) {
                if (!(args.get("allowFormat") instanceof Boolean)) throw invalid();
                format = args.getBoolean("allowFormat");
            }
            return new Operation(true, timeout(args, limits), format, message(args.getJSONArray("records"), limits, false));
        } catch (JSONException malformed) { throw invalid(); }
    }
    public static void empty(JSONObject args) throws FactoryException { bounded(args); keys(args); }
    public static Message emptyMessage() { return new Message(Collections.<Record>emptyList(), 0); }
    public static Message message(JSONArray values, Limits limits, boolean emptyAllowed) throws FactoryException {
        if (values == null || values.length() > limits.records) throw tooLarge();
        if (values.length() == 0 && !emptyAllowed) throw invalid();
        List<Record> records = new ArrayList<>(); int bytes = 0;
        try {
            for (int i = 0; i < values.length(); i++) {
                if (!(values.get(i) instanceof JSONObject)) throw invalid();
                JSONObject value = values.getJSONObject(i); keys(value, "tnf", "typeHex", "idHex", "payloadHex");
                int tnf = integer(value.get("tnf"), 0, 5);
                Record record = record(tnf, unhex(value.get("typeHex"), 255), unhex(value.get("idHex"), 255),
                        unhex(value.get("payloadHex"), limits.messageBytes), limits);
                bytes += record.byteLength(); if (bytes > limits.messageBytes) throw tooLarge(); records.add(record);
            }
        } catch (JSONException malformed) { throw invalid(); }
        return new Message(records, bytes);
    }
    public static Record record(int tnf, byte[] type, byte[] id, byte[] payload, Limits limits) throws FactoryException {
        if (type == null || id == null || payload == null || type.length > 255 || id.length > 255
                || payload.length > limits.messageBytes) throw tooLarge();
        // Logical complete records only: TNF_UNCHANGED/chunks and reserved TNF are not exposed.
        if (tnf < 0 || tnf > 5 || (tnf == 0 && (type.length + id.length + payload.length != 0))
                || (tnf == 5 && type.length != 0) || (tnf >= 1 && tnf <= 4 && type.length == 0)) throw invalid();
        Record result = new Record(tnf, type, id, payload);
        if (result.byteLength() > limits.messageBytes) throw tooLarge(); return result;
    }
    public static Message records(List<Record> values, Limits limits) throws FactoryException {
        if (values == null || values.size() > limits.records) throw tooLarge();
        int bytes = 0;
        for (Record record : values) { if (record == null) throw invalid(); bytes += record.byteLength(); if (bytes > limits.messageBytes) throw tooLarge(); }
        return new Message(values, bytes);
    }
    /** Check again at admission so a request parsed under another artifact's limits cannot escape. */
    public static void validate(Operation request, Limits limits) throws FactoryException {
        if (request == null || request.timeoutMs > limits.timeoutMs) throw invalid();
        if (request.write && (request.message == null || request.message.records.size() > limits.records
                || request.message.byteLength > limits.messageBytes)) throw tooLarge();
    }
    private static int timeout(JSONObject args, Limits limits) throws FactoryException {
        try { return args.has("timeoutMs") ? integer(args.get("timeoutMs"), MIN_TIMEOUT_MS, limits.timeoutMs) : Math.min(30000, limits.timeoutMs); }
        catch (JSONException malformed) { throw invalid(); }
    }
    private static int integer(Object value, int min, int max) throws FactoryException {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long number = ((Number) value).longValue(); if (number < min || number > max) throw invalid(); return (int) number;
    }
    private static void bounded(JSONObject value) throws FactoryException {
        if (value == null) throw invalid(); String json = value.toString();
        if (json == null || json.length() > MAX_ARGS_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_ARGS_BYTES) throw tooLarge();
    }
    private static void keys(JSONObject value, String... permitted) throws FactoryException {
        Set<String> keys = new HashSet<>(); Collections.addAll(keys, permitted);
        Iterator<String> iterator = value.keys(); while (iterator.hasNext()) if (!keys.contains(iterator.next())) throw invalid();
    }
    public static String hex(byte[] bytes) {
        char[] text = new char[bytes.length * 2]; char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { text[i * 2] = hex[(bytes[i] & 255) >>> 4]; text[i * 2 + 1] = hex[bytes[i] & 15]; }
        return new String(text);
    }
    private static byte[] unhex(Object value, int maxBytes) throws FactoryException {
        if (!(value instanceof String)) throw invalid(); String text = (String) value;
        if (text.length() > maxBytes * 2) throw tooLarge(); if ((text.length() & 1) != 0) throw invalid();
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = digit(text.charAt(i * 2)), low = digit(text.charAt(i * 2 + 1));
            if (high < 0 || low < 0) throw invalid(); result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
    private static int digit(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1; }
    static FactoryException invalid() {
        return new FactoryException("INVALID_ARGUMENT",
                "nfc: read accepts optional integer timeoutMs; write requires records as NDEF objects with integer tnf and lowercase even-length typeHex, idHex, and payloadHex strings plus optional boolean allowFormat; clear accepts no fields.");
    }
    static FactoryException tooLarge() { return new FactoryException("TOO_LARGE", "NDEF data exceeds the signed NFC budget."); }
}
