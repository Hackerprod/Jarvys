package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeOperationRequestTest {
    private static final String NONCE = new String(new char[64]).replace('\0', 'a');
    @Test public void exactCanonicalFieldsAndNonceAreBound() throws Exception {
        NativeOperationRequest first = NativeOperationRequest.parse("notifications.cancel", "{\"id\":1}");
        assertEquals("notifications", first.capability); assertEquals("{\"id\":1}", first.canonicalJson());
        assertNotEquals(first.binding(NONCE), NativeOperationRequest.parse("notifications.cancel", "{\"id\":2}").binding(NONCE));
        assertNotEquals(first.binding(NONCE), first.binding(new String(new char[64]).replace('\0', 'b')));
        assertNotEquals(first.binding(NONCE), first.binding(NONCE, "exact verified artifact"));
    }
    @Test public void unknownOperationCapabilitySummaryAndMalformedTypesFailClosed() {
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("anything", "{}"));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("notifications.requestPermission", "{\"capability\":\"network\"}"));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("notifications.cancel", "{\"id\":1,\"summary\":\"Harmless\"}"));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("notifications.cancel", "{\"id\":1.0}"));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("notifications.cancel", new JSONObject().put("id", 1.0)));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("notifications.cancel", "{\"id\":1,\"id\":2}"));
    }
    @Test public void requestSnapshotCannotBeChangedByMutatingInputOrReturnedDetails() throws Exception {
        JSONObject input = new JSONObject().put("id", 9);
        NativeOperationRequest value = NativeOperationRequest.parse("notifications.cancel", input);
        String binding = value.binding(NONCE); input.put("id", 10); value.details().put("id", 11);
        assertEquals(binding, value.binding(NONCE)); assertEquals(9, value.details().getInt("id"));
    }
    @Test public void completeNetworkDisclosureIsTypedAndOrderIndependent() throws Exception {
        JSONObject details = NetworkRequest.fromRequest(new JSONObject().put("url", "https://example.com/path")
            .put("method", "POST").put("body", "synthetic body")).authorizationDetails();
        NativeOperationRequest value = NativeOperationRequest.parse("network.request", details);
        assertEquals("network", value.capability);
        assertEquals(value.binding(NONCE), NativeOperationRequest.parse("network.request", new JSONObject(value.canonicalJson())).binding(NONCE));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("network.probe", details));
        details.put("timeoutMs", 20000);
        assertNotEquals(value.binding(NONCE), NativeOperationRequest.parse("network.request", details).binding(NONCE));
    }
    @Test public void speechAndTextOperationsRequireTheirExactDeclaredGrammar() throws Exception {
        NativeOperationRequest share = NativeOperationRequest.parse("share.text", new JSONObject().put("title", "Synthetic").put("text", "Hello"));
        assertEquals("share", share.capability);
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("speech.stop", "{\"text\":\"Hello\"}"));
        assertEquals("speech", NativeOperationRequest.parse("speech.voices", "{\"engine\":\"\"}").capability);
    }
    @Test public void icmpPingBindsItsExactTypedRequestWithoutShellOrHttpFields() throws Exception {
        JSONObject details = NetworkPingRequest.parse(new JSONObject().put("host", "example.com")).authorizationDetails();
        NativeOperationRequest value = NativeOperationRequest.parse("network.ping", details);
        assertEquals("network", value.capability);
        assertEquals(value.binding(NONCE), NativeOperationRequest.parse("network.ping", value.canonicalJson()).binding(NONCE));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("network.ping", value.details().put("shell", "anything")));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("network.ping", value.details().put("url", "https://example.com")));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("network.ping", value.details().put("count", 1.0)));
        assertNotEquals(value.binding(NONCE), NativeOperationRequest.parse("network.ping", value.details().put("count", 2)).binding(NONCE));
        assertThrows(FactoryException.class, () -> NativeOperationRequest.parse("network.ping", "{\"host\":\"example.com\"}"));
    }
}
