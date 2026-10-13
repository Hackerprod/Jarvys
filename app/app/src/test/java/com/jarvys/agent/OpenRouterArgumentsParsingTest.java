package com.jarvys.agent;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
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
}
