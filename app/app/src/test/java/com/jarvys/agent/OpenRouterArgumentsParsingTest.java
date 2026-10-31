package com.jarvys.agent;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class OpenRouterArgumentsParsingTest {
    @Test public void malformedToolArgumentsAreRetainedAsAnInvalidCall() throws Exception {
        JSONObject function = new JSONObject().put("name", "write").put("arguments", "{broken");
        JSONObject call = new JSONObject().put("id", "call-1").put("type", "function")
                .put("function", function);
        JSONObject message = new JSONObject().put("content", "")
                .put("tool_calls", new JSONArray().put(call));
        JSONObject body = new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", message)));
        Method parser = OpenRouterClient.class.getDeclaredMethod("parseResponse", String.class);
        parser.setAccessible(true);

        ModelReply reply = (ModelReply) parser.invoke(null, body.toString());

        assertEquals(1, reply.calls.size());
        assertEquals("call-1", reply.calls.get(0).id);
        assertEquals("write", reply.calls.get(0).name);
        assertTrue(reply.calls.get(0).arguments.isEmpty());
        assertEquals("Malformed tool arguments JSON object", reply.calls.get(0).invalidArgumentsReason);
    }

    @Test public void emptyToolArgumentsAreParsedAsAnEmptyObject() throws Exception {
        Method parser = OpenRouterClient.class.getDeclaredMethod("parseResponse", String.class);
        parser.setAccessible(true);

        for (String arguments : new String[] {"", "   \t\r\n"}) {
            JSONObject function = new JSONObject().put("name", "no_args").put("arguments", arguments);
            JSONObject call = new JSONObject().put("id", "call-empty").put("type", "function")
                    .put("function", function);
            JSONObject body = new JSONObject().put("choices", new JSONArray().put(
                    new JSONObject().put("message", new JSONObject().put("tool_calls", new JSONArray().put(call)))));

            ModelReply reply = (ModelReply) parser.invoke(null, body.toString());

            assertEquals(1, reply.calls.size());
            assertEquals(Collections.emptyMap(), reply.calls.get(0).arguments);
            assertNull(reply.calls.get(0).invalidArgumentsReason);
        }
    }

    @Test public void emptyAssistantTurnIsOmittedFromChatCompletionsRequest() throws Exception {
        ConversationTurn emptyAssistant = ConversationTurn.assistantReply("", Collections.emptyList());

        JSONArray messages = OpenRouterClient.conversationMessages("system",
                Collections.singletonList(emptyAssistant), "next user message", Collections.emptyList());

        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        for (int i = 0; i < messages.length(); i++) {
            assertFalse(messages.getJSONObject(i).optString("role").equals("assistant"));
        }
    }

    @Test public void nonStringArgumentsAreSerializedOrRetainedAsPerCallErrors() throws Exception {
        Method parser = OpenRouterClient.class.getDeclaredMethod("parseResponse", String.class);
        parser.setAccessible(true);
        JSONArray calls = new JSONArray()
                .put(new JSONObject().put("id", "call-object").put("type", "function")
                        .put("function", new JSONObject().put("name", "write")
                                .put("arguments", new JSONObject().put("path", "note.txt"))))
                .put(new JSONObject().put("id", "call-null").put("type", "function")
                        .put("function", new JSONObject().put("name", "write").put("arguments", JSONObject.NULL)));
        JSONObject body = new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", new JSONObject().put("tool_calls", calls))));

        ModelReply reply = (ModelReply) parser.invoke(null, body.toString());

        assertEquals(2, reply.calls.size());
        assertTrue(reply.calls.get(0).arguments.isEmpty());
        assertNotNull(reply.calls.get(0).invalidArgumentsReason);
        assertEquals("call-null", reply.calls.get(1).id);
        assertTrue(reply.calls.get(1).arguments.isEmpty());
        assertNotNull(reply.calls.get(1).invalidArgumentsReason);
    }
}
