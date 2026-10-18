package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class IncomingShareRequestTest {
    @Test public void invalidEnvelopeAndAttachmentShapeHaveActionableMessages() throws Exception {
        FactoryException envelope = assertThrows(FactoryException.class,
                () -> IncomingShareRequest.take(new JSONObject().put("id", "private-envelope")));
        assertEquals("incomingShare.take: id must be a 64-character lowercase hexadecimal envelope token.", envelope.getMessage());

        FactoryException indices = assertThrows(FactoryException.class,
                () -> IncomingShareRequest.take(new JSONObject().put("id", repeat('a', 64)).put("attachmentIndices", new JSONArray().put("first"))));
        assertEquals("incomingShare.take: attachmentIndices entries must be integers from 0 through 31.", indices.getMessage());
    }

    @Test public void listAndDiscardErrorsNameTheirOwnMethodWithoutEchoingValues() throws Exception {
        FactoryException list = assertThrows(FactoryException.class,
                () -> IncomingShareRequest.list(new JSONObject().put("private", "payload")));
        assertEquals("incomingShare.list: arguments must be an object with only the documented fields.", list.getMessage());

        FactoryException discard = assertThrows(FactoryException.class,
                () -> IncomingShareRequest.discard(new JSONObject().put("id", "private-envelope")));
        assertEquals("incomingShare.discard: id must be a 64-character lowercase hexadecimal envelope token.", discard.getMessage());
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count]; java.util.Arrays.fill(chars, value); return new String(chars);
    }
}
