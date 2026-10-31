package com.jarvys.factory.runtime;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NfcRequestTest {
    static final NfcRequest.Limits DEFAULTS = NfcRequest.Limits.defaults();
    static JSONObject record() throws Exception { return new JSONObject().put("tnf", 1).put("typeHex", "54").put("idHex", "").put("payloadHex", "02656e4869"); }
    static JSONObject writeArgs() throws Exception { return new JSONObject().put("records", new JSONArray().put(record())); }
    interface Checked { void run() throws Exception; }
    static void fails(String code, Checked task) throws Exception { try { task.run(); fail("Expected " + code); } catch (FactoryException failure) { assertEquals(code, failure.code); } }
    @Test public void defaultsAndCanonicalExactWrite() throws Exception {
        NfcRequest.Operation request = NfcRequest.write(writeArgs(), DEFAULTS);
        assertTrue(request.write); assertFalse(request.allowFormat); assertEquals(30000, request.timeoutMs);
        assertEquals("nfc.write", request.method()); assertEquals(9, request.message.byteLength);
        assertFalse(request.authorizationDetails().getBoolean("allowFormat"));
        assertEquals("02656e4869", request.authorizationDetails().getJSONArray("records").getJSONObject(0).getString("payloadHex"));
    }
    @Test public void immutableInputAndOutput() throws Exception {
        JSONObject args = writeArgs(); NfcRequest.Operation request = NfcRequest.write(args, DEFAULTS);
        args.getJSONArray("records").getJSONObject(0).put("payloadHex", "ff");
        byte[] returned = request.message.records.get(0).payload(); returned[0] = 99;
        request.authorizationDetails().getJSONArray("records").getJSONObject(0).put("payloadHex", "aa");
        assertEquals("02656e4869", request.message.records.get(0).json().getString("payloadHex"));
        try { request.message.records.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void closedGrammarAndTypedValues() throws Exception {
        fails("INVALID_ARGUMENT", () -> NfcRequest.read(new JSONObject().put("silent", true), DEFAULTS));
        fails("INVALID_ARGUMENT", () -> NfcRequest.read(new JSONObject().put("timeoutMs", "1000"), DEFAULTS));
        fails("INVALID_ARGUMENT", () -> NfcRequest.read(new JSONObject().put("timeoutMs", 1000.0), DEFAULTS));
        fails("INVALID_ARGUMENT", () -> NfcRequest.write(writeArgs().put("allowFormat", "true"), DEFAULTS));
        fails("INVALID_ARGUMENT", () -> NfcRequest.write(writeArgs().put("extra", "x"), DEFAULTS));
        fails("INVALID_ARGUMENT", () -> NfcRequest.write(new JSONObject().put("records", new JSONArray().put("text")), DEFAULTS));
        FactoryException field = assertThrows(FactoryException.class,
                () -> NfcRequest.write(writeArgs().put("allowFormat", "true"), DEFAULTS));
        assertEquals("nfc: read accepts optional integer timeoutMs; write requires records as NDEF objects with integer tnf and lowercase even-length typeHex, idHex, and payloadHex strings plus optional boolean allowFormat; clear accepts no fields.", field.getMessage());
        fails("INVALID_ARGUMENT", () -> NfcRequest.empty(new JSONObject().put("id", "foreign")));
    }
    @Test public void duplicateAndNonJsonRejected() throws Exception {
        fails("INVALID_REQUEST", () -> NfcRequest.read("{\"timeoutMs\":1000,\"timeoutMs\":2000}", DEFAULTS));
        fails("INVALID_REQUEST", () -> NfcRequest.read("{'timeoutMs':1000}", DEFAULTS));
    }
    @Test public void noTnfChunksReservedOrMalformedEmpty() throws Exception {
        for (int tnf : new int[] {-1, 6, 7, 8}) {
            final int value = tnf;
            fails("INVALID_ARGUMENT", () -> NfcRequest.write(new JSONObject().put("records", new JSONArray().put(record().put("tnf", value))), DEFAULTS));
        }
        fails("INVALID_ARGUMENT", () -> NfcRequest.record(0, new byte[0], new byte[0], new byte[1], DEFAULTS));
        fails("INVALID_ARGUMENT", () -> NfcRequest.record(5, new byte[1], new byte[0], new byte[1], DEFAULTS));
        fails("INVALID_ARGUMENT", () -> NfcRequest.record(1, new byte[0], new byte[0], new byte[1], DEFAULTS));
    }
    @Test public void canonicalHexOnly() throws Exception {
        for (String payload : new String[] {"0", "AA", "gg", "00 00"}) {
            fails("INVALID_ARGUMENT", () -> NfcRequest.write(new JSONObject().put("records", new JSONArray().put(record().put("payloadHex", payload))), DEFAULTS));
        }
    }
    @Test public void wireLengthAccountsForHeaders() throws Exception {
        assertEquals(3, NfcRequest.record(0, new byte[0], new byte[0], new byte[0], DEFAULTS).byteLength());
        assertEquals(259, NfcRequest.record(1, new byte[] {84}, new byte[0], new byte[255], DEFAULTS).byteLength());
        assertEquals(263, NfcRequest.record(1, new byte[] {84}, new byte[0], new byte[256], DEFAULTS).byteLength());
        assertEquals(265, NfcRequest.record(1, new byte[] {84}, new byte[] {1}, new byte[256], DEFAULTS).byteLength());
    }
    @Test public void budgetsPermitLargerSignedArtifacts() throws Exception {
        NfcRequest.Limits larger = new NfcRequest.Limits(128 * 1024, 256, 300000);
        JSONArray records = new JSONArray(); for (int i = 0; i < 256; i++) records.put(record());
        JSONObject args = new JSONObject().put("records", records).put("timeoutMs", 300000);
        assertEquals(256, NfcRequest.write(args.toString(), larger).message.records.size());
        fails("INVALID_ARGUMENT", () -> NfcRequest.write(args, DEFAULTS));
        NfcRequest.Operation big = NfcRequest.write(args, larger);
        fails("INVALID_ARGUMENT", () -> NfcRequest.validate(big, DEFAULTS));
    }
    @Test public void maximumSerializedSizeAndAggregateAreEnforced() throws Exception {
        NfcRequest.Limits larger = new NfcRequest.Limits(128 * 1024, 256, 300000);
        JSONObject max = record().put("tnf", 5).put("typeHex", "").put("payloadHex", NfcRequest.hex(new byte[128 * 1024 - 6]));
        assertEquals(128 * 1024, NfcRequest.write(new JSONObject().put("records", new JSONArray().put(max)).toString(), larger).message.byteLength);
        max.put("payloadHex", NfcRequest.hex(new byte[128 * 1024 - 5]));
        fails("TOO_LARGE", () -> NfcRequest.write(new JSONObject().put("records", new JSONArray().put(max)), larger));
        NfcRequest.Limits tiny = new NfcRequest.Limits(10, 16, 1000);
        fails("TOO_LARGE", () -> NfcRequest.write(new JSONObject().put("records", new JSONArray().put(record()).put(record())), tiny));
    }
    @Test public void emptyReadAllowedEmptyWriteForbidden() throws Exception {
        assertTrue(NfcRequest.emptyMessage().records.isEmpty());
        fails("INVALID_ARGUMENT", () -> NfcRequest.write(new JSONObject().put("records", new JSONArray()), DEFAULTS));
    }
    @Test public void nativeRecordCopiesAndRecordCountEnforced() throws Exception {
        byte[] raw = {1}; NfcRequest.Record record = NfcRequest.record(5, new byte[0], new byte[0], raw, DEFAULTS); raw[0] = 2;
        assertEquals(1, record.payload()[0]);
        fails("TOO_LARGE", () -> NfcRequest.records(Arrays.asList(record, record), new NfcRequest.Limits(8192, 1, 1000)));
    }
    @Test public void limitsRejectOutsideImplementationCeilings() {
        for (int[] values : new int[][] {{2, 1, 1000}, {131073, 1, 1000}, {10, 257, 1000}, {10, 1, 300001}}) {
            try { new NfcRequest.Limits(values[0], values[1], values[2]); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
}
